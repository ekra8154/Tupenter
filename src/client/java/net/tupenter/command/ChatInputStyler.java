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

    // NOTE: no bold anywhere in these styles — bold glyphs are wider, which
    // would desync the editor overlay (and the chat cursor) from plain text
    private static final Style SEPARATOR = Style.EMPTY.withColor(ChatFormatting.GOLD);
    private static final Style MARKER = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style DIRECTIVE_WORD = Style.EMPTY.withColor(ChatFormatting.GOLD);
    // line modifiers (#silent #norecord #record #stage #unstage) — annotations
    // about how the line is sent/recorded, distinct from control-flow keywords
    private static final Style PREFIX_WORD = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
    private static final Style GROUP_PAREN = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
    private static final Style CHAT_TEXT = Style.EMPTY.withColor(ChatFormatting.WHITE);
    private static final Style ERROR = Style.EMPTY.withColor(ChatFormatting.RED).withUnderlined(true);
    // vanilla CommandSuggestions.LITERAL_STYLE — the soft gray of command
    // literals; unstyled spans otherwise fall back to the edit box's near-white
    // default (0xE0E0E0), which reads as a jarring "white" once we take over
    private static final Style COMMAND_LITERAL = Style.EMPTY.withColor(ChatFormatting.GRAY);
    // vanilla CommandSuggestions argument color cycle
    private static final List<Style> ARG_STYLES = List.of(
            Style.EMPTY.withColor(ChatFormatting.AQUA),
            Style.EMPTY.withColor(ChatFormatting.YELLOW),
            Style.EMPTY.withColor(ChatFormatting.GREEN),
            Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE),
            Style.EMPTY.withColor(ChatFormatting.GOLD));

    /** Directives that START a statement — seeing one mid-statement means a missing && (#else/#elseif legitimately continue). */
    private static final java.util.Set<String> STATEMENT_STARTERS = java.util.Set.of(
            "#set", "#local", "#wait", "#repeat", "#if", "#while", "#for", "#foreach",
            "#silent", "#norecord", "#record", "#stage", "#unstage", "#chat",
            "#s", "#nr", "#r", "#st", "#ust", "#c");

    /** Line/statement prefixes — a statement-starter may legally follow these without &&: #silent #local x = ... */
    private static final java.util.Set<String> PREFIX_WORDS = java.util.Set.of(
            "#silent", "#norecord", "#record", "#stage", "#chat",
            "#s", "#nr", "#r", "#st", "#c");

    /** Line modifiers — colored as annotations (PREFIX_WORD), not control-flow keywords. */
    private static final java.util.Set<String> LINE_MODIFIERS = java.util.Set.of(
            "#silent", "#norecord", "#record", "#stage", "#unstage", "#chat",
            "#s", "#nr", "#r", "#st", "#ust", "#c");

    /** Everything tab-completion should offer for a '#' word. */
    private static final List<String> DIRECTIVE_WORDS = List.of(
            "#set", "#local", "#wait", "#repeat", "#if", "#elseif", "#else", "#while", "#for", "#foreach",
            "#silent", "#norecord", "#record", "#stage", "#unstage", "#chat",
            "#s", "#nr", "#r", "#st", "#ust", "#c");

    /** Directives whose header carries an EXPRESSION (condition/iterable) — an implicit $...$ zone for styling and completion. */
    private static final java.util.Set<String> HEADER_EXPR_DIRECTIVES = java.util.Set.of(
            "#repeat", "#if", "#elseif", "#while", "#for", "#foreach");

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
            if (full.length() > 1 && full.charAt(1) == '#' && ScriptParser.isDirectiveLine(full.substring(1))) {
                return true; // /#silent /cmd — command-typed directive form
            }
            if (innerLineStart(full) > 0) {
                return true; // /unroll <line> or /customcommand add|update — the argument is a whole line
            }
            boolean chained = TupenterConfig.INSTANCE.commandChainingEnabled && segments(full).size() > 1;
            return chained || containsMarker(full);
        }
        return ScriptParser.isDirectiveLine(full) || isModifierLine(full);
    }

    /**
     * #stage / #unstage lines are handled entirely client-side and aren't
     * known to the parser's isDirectiveLine, so they'd otherwise never get
     * styled. Recognize them here (covers the other modifiers too).
     */
    private static boolean isModifierLine(String full) {
        String trimmed = full.trim();
        if (!trimmed.startsWith("#")) {
            return false;
        }
        String word = trimmed.substring(0, skipWord(trimmed, 0)).toLowerCase(java.util.Locale.ROOT);
        return LINE_MODIFIERS.contains(word);
    }

    /**
     * Meta-commands carry a whole LINE as their tail: /unroll <line>, and
     * /customcommand add|update <name> <decls...> <body>. Returns where
     * that embedded line's statements begin (past the name and any
     * parameter declarations), or 0 when this isn't such a command.
     */
    public static int innerLineStart(String full) {
        if (full.regionMatches(true, 0, "/unroll ", 0, 8)) {
            return 8;
        }
        if (!full.regionMatches(true, 0, "/customcommand ", 0, 15)) {
            return 0;
        }
        int i = skipWhitespace(full, 15);
        int subEnd = skipWord(full, i);
        String sub = full.substring(i, subEnd).toLowerCase(java.util.Locale.ROOT);
        if (!sub.equals("add") && !sub.equals("update")) {
            return 0;
        }
        i = skipWhitespace(full, subEnd);
        int nameEnd = skipWord(full, i);
        if (nameEnd == i) {
            return 0; // no name yet
        }
        i = skipWhitespace(full, nameEnd);
        while (i < full.length() && full.charAt(i) == '<') {
            int close = angleClose(full, i);
            if (close < 0) {
                return i; // unfinished declaration — statements "start" here
            }
            i = skipWhitespace(full, close + 1);
        }
        return i;
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipWord(String text, int i) {
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The '>' closing the '<' at {@code open}, outside $...$ markers; -1 if unclosed. */
    private static int angleClose(String text, int open) {
        boolean marker = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                marker = !marker;
            } else if (c == '>' && !marker) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Where the suggestion parse should re-root for the cursor position:
     * {commandStart, segmentEnd} in real-input coordinates, or null when
     * vanilla already handles it. Understands && chains, prefixed commands
     * (#norecord /cmd), the /#directive form, and /unroll's inner line.
     */
    public static int[] rerootTarget(String full, int cursor) {
        int base = innerLineStart(full);
        String region = full.substring(base);
        List<Segment> segments = segments(region);
        if (base == 0 && full.startsWith("/")
                && !(full.length() > 1 && full.charAt(1) == '#') && segments.size() < 2) {
            return null; // plain single command — vanilla is already right
        }
        int index = segments.indexOf(segmentAt(segments, Math.max(0, cursor - base)));
        while (index >= 0) {
            Segment segment = segments.get(index);
            int commandStart = -1;
            if (segment.kind() == Kind.COMMAND) {
                commandStart = segment.textStart();
            } else if (segment.kind() == Kind.DIRECTIVE) {
                int rest = statementStartAfterPrefixes(region, segment.textStart(), segment.end());
                if (rest < segment.end() && region.charAt(rest) == '/') {
                    commandStart = rest; // a prefixed command underneath
                }
            }
            if (commandStart >= 0 && commandStart < segment.end()) {
                return new int[]{commandStart + base, segment.end() + base};
            }
            index--;
        }
        return null;
    }

    /**
     * When the cursor is typing a '#'-word in a directive position (line or
     * statement start, after prefixes, or after a ')' where #elseif/#else
     * live), the index of the '#'; -1 otherwise (tags in markers excluded).
     */
    public static int directiveTokenStart(String text, int cursor) {
        // inside a meta-command's embedded line, positions are relative to it
        int base = innerLineStart(text);
        if (base > 0 && cursor >= base) {
            int local = directiveTokenStartRaw(text.substring(base), cursor - base);
            return local < 0 ? -1 : local + base;
        }
        return directiveTokenStartRaw(text, cursor);
    }

    private static int directiveTokenStartRaw(String text, int cursor) {
        int start = Math.min(cursor, text.length());
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            start--;
        }
        if (start == 0 || text.charAt(start - 1) != '#') {
            return -1;
        }
        if (markerTokenStart(text, cursor) >= 0) {
            return -1; // '#' inside a marker is a tag, handled elsewhere
        }
        int hash = start - 1;
        List<Segment> segments = segments(text);
        Segment segment = segmentAt(segments, cursor);
        int textStart = segment.textStart();
        if (hash == textStart
                || hash == statementStartAfterPrefixes(text, textStart, Math.min(segment.end(), text.length()))) {
            return hash;
        }
        int back = hash - 1;
        while (back >= 0 && Character.isWhitespace(text.charAt(back))) {
            back--;
        }
        return back >= 0 && text.charAt(back) == ')' ? hash : -1;
    }

    /** After a ')' only the chain continuations make sense; elsewhere, everything. */
    public static List<String> directiveWordSuggestions(String text, int hashStart) {
        int back = hashStart - 1;
        while (back >= 0 && Character.isWhitespace(text.charAt(back))) {
            back--;
        }
        return back >= 0 && text.charAt(back) == ')' ? List.of("#elseif", "#else") : DIRECTIVE_WORDS;
    }

    /**
     * Splits a line on top-level {@code &&} (quotes and $...$ markers bind
     * tighter; {@code \} escapes). Always returns at least one segment.
     */
    public static List<Segment> segments(String full) {
        return segments(full, false);
    }

    /**
     * The paren-aware variant keeps a DIRECTIVE segment together with its
     * whole {@code (...)} group chain — the {@code &&}s inside a group
     * belong to the group. Styling uses this; the suggestion re-rooter
     * keeps the flat split so completion still works inside groups.
     */
    private static List<Segment> segments(String full, boolean groupAware) {
        List<Segment> result = new ArrayList<>();
        boolean marker = false;
        boolean quoted = false;
        boolean directiveSegment = false;
        boolean sawSegmentStart = false;
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < full.length()) {
            char c = full.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (!sawSegmentStart && !Character.isWhitespace(c)) {
                sawSegmentStart = true;
                directiveSegment = c == '#';
            }
            if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted) {
                    if (groupAware && directiveSegment && c == '(') {
                        depth++;
                    } else if (groupAware && directiveSegment && c == ')') {
                        depth = Math.max(0, depth - 1);
                    } else if (c == '&' && depth == 0 && i + 1 < full.length() && full.charAt(i + 1) == '&') {
                        result.add(makeSegment(full, start, i));
                        i += 2;
                        start = i;
                        sawSegmentStart = false;
                        directiveSegment = false;
                        continue;
                    }
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
                // "/#silent /cmd" — the command-typed directive form
                if (textStart + 1 < end && full.charAt(textStart + 1) == '#') {
                    kind = Kind.DIRECTIVE;
                    textStart++;
                } else {
                    kind = Kind.COMMAND;
                }
            } else if (first == '#') {
                kind = Kind.DIRECTIVE;
            }
        }
        return new Segment(start, end, textStart, kind);
    }

    /**
     * The index just past any leading prefix words (#silent #norecord
     * #record #stage) and their whitespace — where the statement they wrap
     * actually starts. Returns {@code from} itself when there are none.
     */
    public static int statementStartAfterPrefixes(String text, int from, int end) {
        int i = from;
        while (i < end && text.charAt(i) == '#') {
            int word = i;
            while (word < end && !Character.isWhitespace(text.charAt(word)) && text.charAt(word) != '(') {
                word++;
            }
            if (!PREFIX_WORDS.contains(text.substring(i, word).toLowerCase(java.util.Locale.ROOT))) {
                return i;
            }
            i = word;
            while (i < end && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }
        return i;
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

    /**
     * Styles for the resend-preset editor: unlike editorStyles (one flattened
     * runtime line), each NEWLINE-separated line is an independent command, so
     * each is styled on its own. Indices stay aligned with the raw value.
     */
    public static Style[] editorStylesPerLine(String value) {
        Style[] styles = new Style[value.length()];
        java.util.Arrays.fill(styles, Style.EMPTY);
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '\n') {
                if (i > start) {
                    Style[] lineStyles = editorStyles(value.substring(start, i), false);
                    System.arraycopy(lineStyles, 0, styles, start, Math.min(lineStyles.length, i - start));
                }
                start = i + 1;
            }
        }
        return styles;
    }

    /** name white · <declarations> green · = gold (never bold — width-safe). */
    private static void styleSignature(String flat, Style[] styles, int separator) {
        int nameEnd = 0;
        while (nameEnd < separator && !Character.isWhitespace(flat.charAt(nameEnd)) && flat.charAt(nameEnd) != '<') {
            nameEnd++;
        }
        fill(styles, 0, nameEnd, Style.EMPTY.withColor(ChatFormatting.WHITE));
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
        styleStatements(text, from, text.length(), styles, 0);
    }

    private static void styleStatements(String text, int from, int to, Style[] styles, int depth) {
        if (depth > 8) {
            return; // absurd nesting — leave it plain
        }
        String region = text.substring(from, to);
        List<Segment> segments = segments(region, true);
        for (int s = 0; s < segments.size(); s++) {
            Segment local = segments.get(s);
            Segment segment = new Segment(local.start() + from, local.end() + from, local.textStart() + from, local.kind());
            switch (segment.kind()) {
                case CHAT -> fill(styles, segment.start(), segment.end(), CHAT_TEXT);
                case DIRECTIVE -> styleDirective(text, styles, segment, depth);
                case COMMAND -> styleCommand(text, styles, segment, depth);
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
                if (marker) {
                    flagUnbalancedParens(text, styles, lastOpen + 1, i); // validate the expression
                    marker = false;
                } else {
                    lastOpen = i;
                    marker = true;
                }
            } else if (marker) {
                styles[i] = MARKER;
            }
        }
        if (marker && lastOpen >= 0) {
            styles[lastOpen] = ERROR; // unclosed marker — its opening $ turns red
        }
    }

    /**
     * Within an expression span [from, to) (a $...$ interior or a scanner
     * header), turns any unmatched '(' or ')' red — the same live-paren
     * feedback commands get, but for Tupenter's expression grammar. Nested
     * $...$ markers and "quoted" strings are skipped.
     */
    private static void flagUnbalancedParens(String text, Style[] styles, int from, int to) {
        java.util.Deque<Integer> opens = new java.util.ArrayDeque<>();
        boolean quoted = false;
        boolean marker = false;
        for (int i = Math.max(0, from); i < Math.min(to, text.length()); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                marker = !marker;
            } else if (marker) {
                // skip nested marker interiors
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    opens.push(i);
                } else if (c == ')') {
                    if (opens.isEmpty()) {
                        styles[i] = ERROR; // stray close
                    } else {
                        opens.pop();
                    }
                }
            }
        }
        while (!opens.isEmpty()) {
            styles[opens.pop()] = ERROR; // never closed
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

    /**
     * A directive segment: gold #words, dimmed group parens, and the INSIDE
     * of each statement group styled recursively as statements — so the
     * commands in {@code #else (/tp a && /tp b)} get real command styling
     * instead of dragging the closing paren into a red parse error.
     * Condition groups (content that isn't a statement) stay plain.
     * Unmatched parens turn red.
     */
    private static void styleDirective(String full, Style[] styles, Segment segment, int depth) {
        // leading prefix words are gold, and whatever they WRAP gets its own
        // real styling: #norecord /time set day is a command underneath
        int rest = statementStartAfterPrefixes(full, segment.textStart(), segment.end());
        for (int i = segment.textStart(); i < rest; i++) {
            if (!Character.isWhitespace(full.charAt(i))) {
                styles[i] = PREFIX_WORD; // leading words here are all line modifiers
            }
        }
        if (rest >= segment.end()) {
            return; // nothing but prefixes (yet)
        }
        char first = full.charAt(rest);
        if (first == '/') {
            styleCommand(full, styles, new Segment(segment.start(), segment.end(), rest, Kind.COMMAND), depth);
            return;
        }
        if (first != '#') {
            fill(styles, rest, segment.end(), CHAT_TEXT); // prefixed chat
            return;
        }
        int wordEnd = rest;
        while (wordEnd < segment.end() && !Character.isWhitespace(full.charAt(wordEnd)) && full.charAt(wordEnd) != '(') {
            wordEnd++;
        }
        String dir = full.substring(rest, wordEnd).toLowerCase(java.util.Locale.ROOT);
        if (HEADER_EXPR_DIRECTIVES.contains(dir)) {
            styleScannerHeader(full, styles, rest, wordEnd, segment.end(), dir, depth);
            return;
        }
        styleDirectiveCore(full, styles, rest, segment.end(), depth);
    }

    /**
     * A scanner directive (#repeat/#if/#for/#foreach): the header's condition
     * or iterable is an EXPRESSION, styled aqua like a $...$ marker (function
     * names, tags, and operators all read as "code"); the loop variable and
     * 'in' keyword get their own colors; and the trailing (...) body recurses
     * as statements. Foreach's literal (a | b | c) list is styled as a list,
     * not an expression.
     */
    private static void styleScannerHeader(String full, Style[] styles, int wordStart, int wordEnd,
                                           int end, String directive, int depth) {
        fill(styles, wordStart, wordEnd, DIRECTIVE_WORD);
        int bodyOpen = lastTopLevelGroupOpen(full, wordEnd, end);
        int headerEnd = bodyOpen >= 0 ? bodyOpen : end;
        while (headerEnd > wordEnd && Character.isWhitespace(full.charAt(headerEnd - 1))) {
            headerEnd--;
        }
        int exprFrom = skipWhitespace(full, wordEnd);
        if (directive.equals("#foreach") || directive.equals("#for")) {
            int varEnd = varTokenEnd(full, exprFrom, headerEnd);
            fill(styles, exprFrom, varEnd, MARKER); // loop variable, $-wrapped or bare
            int afterVar = skipWhitespace(full, varEnd);
            int inEnd = skipWord(full, afterVar);
            if (afterVar < headerEnd
                    && full.substring(afterVar, Math.min(inEnd, headerEnd)).equalsIgnoreCase("in")) {
                fill(styles, afterVar, inEnd, DIRECTIVE_WORD); // the 'in' keyword
                exprFrom = skipWhitespace(full, inEnd);
            } else {
                exprFrom = afterVar;
            }
        }
        if (directive.equals("#foreach") && exprFrom < headerEnd && full.charAt(exprFrom) == '(') {
            styleLiteralList(full, styles, exprFrom, headerEnd); // (a | b | c)
        } else {
            fill(styles, exprFrom, headerEnd, MARKER); // condition/iterable expression
            flagUnbalancedParens(full, styles, exprFrom, headerEnd);
        }
        if (bodyOpen >= 0) {
            int close = matchingClose(full, bodyOpen, end);
            if (close < 0) {
                styles[bodyOpen] = ERROR;
            } else {
                styles[bodyOpen] = GROUP_PAREN;
                styles[close] = GROUP_PAREN;
                styleStatements(full, bodyOpen + 1, close, styles, depth + 1);
            }
        }
    }

    /** #foreach's literal iterable: dim parens, gold '|' separators, gray items. */
    private static void styleLiteralList(String full, Style[] styles, int from, int end) {
        int close = matchingClose(full, from, end);
        if (close < 0) {
            styles[from] = ERROR;
            return;
        }
        styles[from] = GROUP_PAREN;
        styles[close] = GROUP_PAREN;
        boolean marker = false;
        for (int i = from + 1; i < close; i++) {
            char c = full.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                marker = !marker;
            } else if (!marker && c == '|') {
                styles[i] = SEPARATOR;
            } else if (!marker && !Character.isWhitespace(c)) {
                styles[i] = COMMAND_LITERAL;
            }
        }
    }

    /** End index of a loop variable token at {@code from} ($x$ or bare x), bounded by {@code end}. */
    private static int varTokenEnd(String text, int from, int end) {
        int i = from;
        boolean wrapped = i < end && text.charAt(i) == '$';
        if (wrapped) {
            i++;
        }
        while (i < end && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        if (wrapped && i < end && text.charAt(i) == '$') {
            i++;
        }
        return i;
    }

    /** Open-paren index of the last top-level (...) group in [from, end), or -1. Marker/quote/nesting aware. */
    private static int lastTopLevelGroupOpen(String text, int from, int end) {
        boolean marker = false;
        boolean quoted = false;
        int depth = 0;
        int open = -1;
        int lastOpen = -1;
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted) {
                    if (c == '(') {
                        if (depth == 0) {
                            open = i;
                        }
                        depth++;
                    } else if (c == ')') {
                        depth = Math.max(0, depth - 1);
                        if (depth == 0) {
                            lastOpen = open;
                        }
                    }
                }
            }
        }
        return lastOpen;
    }

    private static void styleDirectiveCore(String full, Style[] styles, int from, int end, int depth) {
        boolean marker = false;
        boolean quoted = false;
        boolean contentSeen = false; // prefix words (#silent ...) don't count as content
        for (int i = from; i < end; i++) {
            char c = full.charAt(i);
            if (c == '\\') {
                i++;
                contentSeen = true;
                continue;
            }
            if (c == '$') {
                marker = !marker;
                contentSeen = true;
                continue;
            }
            if (marker) {
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                contentSeen = true;
            } else if (!quoted) {
                if (c == '#') {
                    int word = i;
                    while (word < end && !Character.isWhitespace(full.charAt(word)) && full.charAt(word) != '(') {
                        word++;
                    }
                    // a statement-STARTING directive after real content means
                    // a missing && before it (newlines don't chain!) — red.
                    // After only prefixes (#silent #local x = ...) it's fine.
                    String directive = full.substring(i, word).toLowerCase(java.util.Locale.ROOT);
                    boolean midStatement = contentSeen && STATEMENT_STARTERS.contains(directive);
                    Style wordStyle = LINE_MODIFIERS.contains(directive) ? PREFIX_WORD : DIRECTIVE_WORD;
                    fill(styles, i, word, midStatement ? ERROR : wordStyle);
                    if (!PREFIX_WORDS.contains(directive)) {
                        contentSeen = true;
                    }
                    i = word - 1;
                } else if (c == '(') {
                    int close = matchingClose(full, i, end);
                    if (close < 0) {
                        styles[i] = ERROR; // never closed
                        continue;
                    }
                    styles[i] = GROUP_PAREN;
                    styles[close] = GROUP_PAREN;
                    int contentStart = i + 1;
                    while (contentStart < close && Character.isWhitespace(full.charAt(contentStart))) {
                        contentStart++;
                    }
                    if (contentStart < close
                            && (full.charAt(contentStart) == '/' || full.charAt(contentStart) == '#')) {
                        styleStatements(full, i + 1, close, styles, depth + 1); // statement group
                    }
                    contentSeen = true;
                    i = close; // conditions and headers keep default styling
                } else if (c == ')') {
                    styles[i] = ERROR; // stray close
                    contentSeen = true;
                } else if (!Character.isWhitespace(c)) {
                    contentSeen = true; // ordinary header text
                }
            }
        }
    }

    /** The meta-command's own text: command word(s) gold, param declarations green like a signature. */
    private static void styleMetaHead(String full, Style[] styles, int to) {
        int wordEnd = skipWord(full, 0);
        fill(styles, 0, wordEnd, DIRECTIVE_WORD); // /unroll or /customcommand
        int i = skipWhitespace(full, wordEnd);
        int subEnd = skipWord(full, i);
        String sub = full.substring(i, Math.min(subEnd, to)).toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("add") || sub.equals("update")) {
            fill(styles, i, subEnd, DIRECTIVE_WORD);
        }
        boolean marker = false;
        int angle = 0;
        for (int j = 0; j < to && j < full.length(); j++) {
            char c = full.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '<' || c == '>') {
                    styles[j] = SEPARATOR;
                    angle += c == '<' ? 1 : -1;
                } else if (angle > 0) {
                    styles[j] = Style.EMPTY.withColor(ChatFormatting.GREEN);
                }
            }
        }
    }

    /** Index of the ')' matching the '(' at {@code open}, honoring nesting, markers, and quotes; -1 if unclosed. */
    private static int matchingClose(String text, int open, int end) {
        boolean marker = false;
        boolean quoted = false;
        int depth = 0;
        for (int i = open; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static void styleCommand(String full, Style[] styles, Segment segment, int depth) {
        // meta-commands (/unroll <line>, /customcommand add|update ...) carry
        // a whole line — style their head, then the embedded statements for real
        int textStart = segment.textStart();
        if (depth < 8 && textStart == 0) {
            int inner = innerLineStart(full);
            if (inner > 0 && inner <= segment.end()) {
                styleMetaHead(full, styles, inner);
                styleStatements(full, inner, segment.end(), styles, depth + 1);
                return;
            }
        }

        // vanilla paints command literals gray; argument ranges (below) and
        // any leftover-red overlay on top. Without this base the literals stay
        // Style.EMPTY and render at the edit box's near-white default.
        fill(styles, textStart, trimmedEnd(full, segment), COMMAND_LITERAL);

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
     * When the cursor sits in the condition/iterable EXPRESSION of a scanner
     * directive (#repeat/#if/#for/#foreach — outside its body group and any
     * literal (a|b) list), the identifier start for expression/tag
     * completion; -1 otherwise. The header expression is an implicit $...$
     * zone, so it completes the same way marker interiors do.
     */
    public static int headerExprTokenStart(String text, int cursor) {
        if (markerTokenStart(text, cursor) >= 0) {
            return -1; // a real marker owns the cursor
        }
        List<Segment> segs = segments(text, true);
        Segment seg = segmentAt(segs, Math.min(cursor, text.length()));
        int rest = statementStartAfterPrefixes(text, seg.textStart(), Math.min(seg.end(), text.length()));
        if (rest >= seg.end() || text.charAt(rest) != '#') {
            return -1;
        }
        int wordEnd = rest;
        while (wordEnd < seg.end() && !Character.isWhitespace(text.charAt(wordEnd)) && text.charAt(wordEnd) != '(') {
            wordEnd++;
        }
        String dir = text.substring(rest, wordEnd).toLowerCase(java.util.Locale.ROOT);
        if (!HEADER_EXPR_DIRECTIVES.contains(dir) || cursor <= wordEnd) {
            return -1; // still typing the directive word — let #-word completion cycle
        }
        int bodyOpen = lastTopLevelGroupOpen(text, wordEnd, seg.end());
        int headerEnd = bodyOpen >= 0 ? bodyOpen : seg.end();
        int exprFrom = skipWhitespace(text, wordEnd);
        if (dir.equals("#foreach") || dir.equals("#for")) {
            int varEnd = varTokenEnd(text, exprFrom, headerEnd);
            int afterVar = skipWhitespace(text, varEnd);
            int inEnd = skipWord(text, afterVar);
            boolean foundIn = afterVar < headerEnd
                    && text.substring(afterVar, Math.min(inEnd, headerEnd)).equalsIgnoreCase("in");
            if (!foundIn) {
                return -1; // still on the loop variable (a fresh name) — nothing to complete
            }
            exprFrom = skipWhitespace(text, inEnd);
        }
        if (dir.equals("#foreach") && exprFrom < headerEnd && text.charAt(exprFrom) == '(') {
            return -1; // literal (a|b) list — not an expression
        }
        if (cursor < exprFrom || cursor > headerEnd) {
            return -1;
        }
        // anchor: broad walk first (tag ids carry : and /), '#' wins, else identifier
        int broad = cursor;
        while (broad > exprFrom) {
            char c = text.charAt(broad - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':' || c == '/' || c == '#') {
                broad--;
            } else {
                break;
            }
        }
        for (int i = broad; i < cursor; i++) {
            if (text.charAt(i) == '#') {
                return i;
            }
        }
        int start = cursor;
        while (start > exprFrom) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        return start;
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
