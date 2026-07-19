package net.tupenter.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.ScriptParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Live chat-bar highlighting for Tupenter syntax: each top-level {@code &&}
 * segment is styled by its statement form (command = vanilla-style argument
 * colors via a per-segment Brigadier parse, directive = gold word + dimmed
 * group parens, bare chat = yellow), {@code $...$} markers are aqua
 * everywhere, and {@code &&} separators are gold. Styles for the full line
 * are computed once per text change and sliced per frame.
 */
public final class ChatInputStyler {

    private static final Style SEPARATOR = Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true);
    private static final Style MARKER = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style DIRECTIVE_WORD = Style.EMPTY.withColor(ChatFormatting.GOLD);
    private static final Style GROUP_PAREN = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
    private static final Style CHAT_TEXT = Style.EMPTY.withColor(ChatFormatting.YELLOW);
    private static final Style ERROR = Style.EMPTY.withColor(ChatFormatting.RED).withUnderlined(true);
    // vanilla CommandSuggestions argument color cycle
    private static final List<Style> ARG_STYLES = List.of(
            Style.EMPTY.withColor(ChatFormatting.AQUA),
            Style.EMPTY.withColor(ChatFormatting.YELLOW),
            Style.EMPTY.withColor(ChatFormatting.GREEN),
            Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE),
            Style.EMPTY.withColor(ChatFormatting.GOLD));

    public enum Kind {
        COMMAND,
        DIRECTIVE,
        CHAT
    }

    /** One top-level chain segment; [start, end) in full-line coordinates, textStart = first non-space. */
    public record Segment(int start, int end, int textStart, Kind kind) {
    }

    private static String cachedText;
    private static Style[] cachedStyles;

    private ChatInputStyler() {
    }

    /** Master gate for both highlighting and chain-aware suggestions. */
    public static boolean highlightingEnabled() {
        return TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                && TupenterConfig.INSTANCE.chatHighlightingEnabled;
    }

    public static boolean chainRerootEnabled() {
        return highlightingEnabled() && TupenterConfig.INSTANCE.commandChainingEnabled;
    }

    /** Should Tupenter take over styling for this line? */
    public static boolean shouldStyle(String full) {
        if (!highlightingEnabled() || full.isEmpty()) {
            return false;
        }
        if (full.startsWith("/")) {
            boolean chained = TupenterConfig.INSTANCE.commandChainingEnabled && segments(full).size() > 1;
            return chained || containsMarker(full);
        }
        return ScriptParser.isDirectiveLine(full);
    }

    /**
     * Splits a line on top-level {@code &&} (quotes and $...$ markers bind
     * tighter; {@code \} escapes). Always returns at least one segment.
     */
    public static List<Segment> segments(String full) {
        List<Segment> result = new ArrayList<>();
        boolean marker = false;
        boolean quoted = false;
        int start = 0;
        int i = 0;
        while (i < full.length()) {
            char c = full.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && c == '&' && i + 1 < full.length() && full.charAt(i + 1) == '&') {
                    result.add(makeSegment(full, start, i));
                    i += 2;
                    start = i;
                    continue;
                }
            }
            i++;
        }
        result.add(makeSegment(full, start, full.length()));
        return result;
    }

    private static Segment makeSegment(String full, int start, int end) {
        int textStart = start;
        while (textStart < end && Character.isWhitespace(full.charAt(textStart))) {
            textStart++;
        }
        Kind kind = Kind.CHAT;
        if (textStart < end) {
            char first = full.charAt(textStart);
            if (first == '/') {
                kind = Kind.COMMAND;
            } else if (first == '#') {
                kind = Kind.DIRECTIVE;
            }
        }
        return new Segment(start, end, textStart, kind);
    }

    /** The segment the cursor sits in (separator gaps count toward the segment on their left). */
    public static Segment segmentAt(List<Segment> segments, int cursor) {
        for (Segment segment : segments) {
            if (cursor <= segment.end()) {
                return segment;
            }
        }
        return segments.get(segments.size() - 1);
    }

    /** Formats the visible slice [offset, offset+visible.length()) of the full line. */
    public static FormattedCharSequence format(String full, String visible, int offset) {
        Style[] styles = stylesFor(full);
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < visible.length()) {
            int fullIndex = offset + i;
            Style style = fullIndex < styles.length ? styles[fullIndex] : Style.EMPTY;
            int runEnd = i + 1;
            while (runEnd < visible.length()) {
                int nextFull = offset + runEnd;
                Style next = nextFull < styles.length ? styles[nextFull] : Style.EMPTY;
                if (next != style) {
                    break;
                }
                runEnd++;
            }
            parts.add(FormattedCharSequence.forward(visible.substring(i, runEnd), style));
            i = runEnd;
        }
        return FormattedCharSequence.composite(parts);
    }

    private static Style[] stylesFor(String full) {
        if (full.equals(cachedText) && cachedStyles != null) {
            return cachedStyles;
        }
        Style[] styles = new Style[full.length()];
        java.util.Arrays.fill(styles, Style.EMPTY);
        styleStatements(full, 0, styles);
        overlayMarkers(full, styles);
        cachedText = full;
        cachedStyles = styles;
        return styles;
    }

    /**
     * Styles for the Mod Menu script editor: raw multi-line text, styled as
     * the single line it becomes at runtime (newlines mapped 1:1 to spaces
     * so every index lines up). Definitions get their signature styled too.
     */
    public static Style[] editorStyles(String raw, boolean definition) {
        String flat = raw.replace('\r', ' ').replace('\n', ' ');
        Style[] styles = new Style[flat.length()];
        java.util.Arrays.fill(styles, Style.EMPTY);

        int bodyStart = 0;
        if (definition) {
            int separator = net.tupenter.script.AliasDefinition.signatureSeparator(flat);
            if (separator >= 0) {
                styleSignature(flat, styles, separator);
                bodyStart = separator + 1;
            }
        }
        styleStatements(flat, bodyStart, styles);
        overlayMarkers(flat, styles);
        return styles;
    }

    /** name bold · <declarations> green · = gold. */
    private static void styleSignature(String flat, Style[] styles, int separator) {
        int nameEnd = 0;
        while (nameEnd < separator && !Character.isWhitespace(flat.charAt(nameEnd)) && flat.charAt(nameEnd) != '<') {
            nameEnd++;
        }
        fill(styles, 0, nameEnd, Style.EMPTY.withBold(true));
        boolean marker = false;
        for (int i = nameEnd; i < separator; i++) {
            char c = flat.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                marker = !marker;
            } else if (!marker && (c == '<' || c == '>')) {
                styles[i] = SEPARATOR;
            } else if (!marker && c != ' ') {
                styles[i] = Style.EMPTY.withColor(ChatFormatting.GREEN);
            }
        }
        styles[separator] = SEPARATOR;
    }

    /** Per-segment statement-form styling for [from, end) of the text. */
    private static void styleStatements(String text, int from, Style[] styles) {
        String region = text.substring(from);
        List<Segment> segments = segments(region);
        for (int s = 0; s < segments.size(); s++) {
            Segment local = segments.get(s);
            Segment segment = new Segment(local.start() + from, local.end() + from, local.textStart() + from, local.kind());
            switch (segment.kind()) {
                case CHAT -> fill(styles, segment.start(), segment.end(), CHAT_TEXT);
                case DIRECTIVE -> styleDirective(text, styles, segment);
                case COMMAND -> styleCommand(text, styles, segment);
            }
            if (s + 1 < segments.size()) {
                int gapStart = segment.end();
                fill(styles, gapStart, gapStart + 2, SEPARATOR); // the &&
            }
        }
    }

    /** Markers overlay everything — they're Tupenter's, not the command's. */
    private static void overlayMarkers(String text, Style[] styles) {
        boolean marker = false;
        int lastOpen = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                styles[i] = MARKER;
                marker = !marker;
                if (marker) {
                    lastOpen = i;
                }
            } else if (marker) {
                styles[i] = MARKER;
            }
        }
        if (marker && lastOpen >= 0) {
            styles[lastOpen] = ERROR; // unclosed marker — its opening $ turns red
        }
    }

    /** A styled slice [from, to) of text, for the editor's line-by-line rendering. */
    public static FormattedCharSequence sequence(String text, Style[] styles, int from, int to) {
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = Math.max(0, from);
        int end = Math.min(to, text.length());
        while (i < end) {
            Style style = i < styles.length ? styles[i] : Style.EMPTY;
            int runEnd = i + 1;
            while (runEnd < end && (runEnd < styles.length ? styles[runEnd] : Style.EMPTY) == style) {
                runEnd++;
            }
            parts.add(FormattedCharSequence.forward(text.substring(i, runEnd), style));
            i = runEnd;
        }
        return FormattedCharSequence.composite(parts);
    }

    private static void styleDirective(String full, Style[] styles, Segment segment) {
        // gold #words (the leading one and any nested ones), dimmed group
        // parens — unmatched parens turn red so a truncated or mistyped
        // definition shows itself before Enter
        boolean marker = false;
        boolean quoted = false;
        java.util.Deque<Integer> openParens = new java.util.ArrayDeque<>();
        for (int i = segment.textStart(); i < segment.end(); i++) {
            char c = full.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                marker = !marker;
                continue;
            }
            if (marker) {
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '#') {
                    int word = i;
                    while (word < segment.end() && !Character.isWhitespace(full.charAt(word)) && full.charAt(word) != '(') {
                        word++;
                    }
                    fill(styles, i, word, DIRECTIVE_WORD);
                    i = word - 1;
                } else if (c == '(') {
                    styles[i] = GROUP_PAREN;
                    openParens.push(i);
                } else if (c == ')') {
                    if (openParens.isEmpty()) {
                        styles[i] = ERROR; // stray close
                    } else {
                        styles[i] = GROUP_PAREN;
                        openParens.pop();
                    }
                }
            }
        }
        for (int unmatched : openParens) {
            styles[unmatched] = ERROR; // never closed
        }
    }

    private static void styleCommand(String full, Style[] styles, Segment segment) {
        boolean hasMarker = containsMarker(full.substring(segment.textStart(), segment.end()));
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
        ClientSuggestionProvider source = connection.getSuggestionsProvider();

        // parse the segment in full-line coordinates: reader over the line
        // truncated at the segment end, cursor just past the segment's '/'
        StringReader reader = new StringReader(full.substring(0, trimmedEnd(full, segment)));
        reader.setCursor(segment.textStart() + 1);
        ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(reader, source);

        int argIndex = 0;
        for (ParsedCommandNode<ClientSuggestionProvider> node : parse.getContext().getLastChild().getNodes()) {
            if (node.getNode() instanceof ArgumentCommandNode<?, ?>) {
                StringRange range = node.getRange();
                fill(styles, range.getStart(), Math.min(range.getEnd(), segment.end()),
                        ARG_STYLES.get(argIndex % ARG_STYLES.size()));
                argIndex++;
            }
        }

        int parseEnd = parse.getReader().getCursor();
        if (parseEnd < trimmedEnd(full, segment) && !hasMarker) {
            // leftover the dispatcher couldn't place — red, unless a marker is
            // involved (Tupenter evaluates those after the tree gives up)
            fill(styles, parseEnd, segment.end(), ERROR);
        }
    }

    private static int trimmedEnd(String full, Segment segment) {
        int end = segment.end();
        while (end > segment.textStart() && Character.isWhitespace(full.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static void fill(Style[] styles, int from, int to, Style style) {
        for (int i = Math.max(0, from); i < Math.min(styles.length, to); i++) {
            styles[i] = style;
        }
    }

    /**
     * When the cursor sits inside an (open) $...$ marker, the start index of
     * the identifier being typed — where variable/function suggestions
     * anchor. -1 when the cursor isn't inside a marker.
     */
    public static int markerTokenStart(String text, int cursor) {
        boolean inMarker = false;
        int open = -1;
        int limit = Math.min(cursor, text.length());
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                inMarker = !inMarker;
                open = i;
            }
        }
        if (!inMarker) {
            return -1;
        }
        // walk back broadly first (tag ids contain : and /), then anchor:
        // a token with a '#' starts AT the '#'; a plain identifier re-trims
        // to letters/digits/_/. so "1/clien" still anchors at "clien"
        int broad = cursor;
        while (broad > open + 1) {
            char c = text.charAt(broad - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':' || c == '/' || c == '#') {
                broad--;
            } else {
                break;
            }
        }
        int hash = text.indexOf('#', broad);
        if (hash >= 0 && hash < cursor) {
            return hash;
        }
        int start = cursor;
        while (start > open + 1) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        return start;
    }

    /** The function name whose '(' immediately precedes tokenStart, or null — blockset(#| knows it wants block tags. */
    public static String enclosingCallName(String text, int tokenStart) {
        int i = tokenStart - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (i < 0 || text.charAt(i) != '(') {
            return null;
        }
        i--;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0 && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i--;
        }
        return end > i + 1 ? text.substring(i + 1, end).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /**
     * Every complete $...$ span replaced by same-length '0's, so a Brigadier
     * parse for SUGGESTIONS can get past evaluated markers — positions stay
     * 1:1 with the real text. /setblock ~ ~ ~$1+2$ mine| : the masked z
     * coordinate ~00000 parses, and the block argument completes normally.
     */
    public static String maskMarkers(String text) {
        StringBuilder out = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '$') {
                int close = -1;
                for (int j = i + 1; j < text.length(); j++) {
                    char d = text.charAt(j);
                    if (d == '\\') {
                        j++;
                    } else if (d == '$') {
                        close = j;
                        break;
                    }
                }
                if (close < 0) {
                    break; // unclosed marker — leave it; the variable-suggestion path owns the cursor there
                }
                if (out == null) {
                    out = new StringBuilder(text);
                }
                for (int j = i; j <= close; j++) {
                    out.setCharAt(j, '0');
                }
                i = close + 1;
                continue;
            }
            i++;
        }
        return out == null ? text : out.toString();
    }

    private static boolean containsMarker(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                return true;
            }
        }
        return false;
    }
}
