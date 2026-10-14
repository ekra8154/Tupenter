package net.tupenter.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.tupenter.TupenterModClient;
import net.tupenter.script.ListLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Vanilla command completion for the config screen's editors: command names
 * and arguments, from the same Brigadier tree the chat bar asks, including
 * the suggestions a command fetches from the server.
 *
 * <p>The chat bar gets this from vanilla's suggestion widget, which only
 * exists on the chat screen. An editor holds a whole script, so the work here
 * is finding the ONE command the cursor is in: past a definition's signature
 * or a script's name, down into the statement group that holds the cursor
 * (#if (...) (/cmd), #silent (/cmd)), then along the && chain to the command
 * segment. That command alone is what gets parsed and, when an argument asks
 * the server, what gets sent; the answer comes back in its coordinates and is
 * moved to the editor's.
 */
public final class CommandCompletion {

    private CommandCompletion() {
    }

    /**
     * Completions for the command under the cursor, or null when the cursor
     * isn't in a command (or there is no connection to ask). The future may
     * complete later, when an argument's suggestions come from the server.
     */
    public static CompletableFuture<ExpressionCompletion.Result> forEditor(String raw, int cursor,
                                                                           boolean definition, boolean perLine) {
        int[] region = ExpressionCompletion.editorRegion(raw, cursor, definition, perLine);
        if (region == null) {
            return null;
        }
        String flat = raw.replace('\r', ' ').replace('\n', ' ');
        cursor = Math.max(0, Math.min(cursor, flat.length()));
        int[] command = commandAt(flat, region[0], region[1], cursor);
        if (command == null) {
            return null;
        }
        return complete(flat.substring(command[0], command[1]), cursor - command[0], command[0]);
    }

    /**
     * {commandStart, commandEnd} of the command the cursor is in, within
     * [from, to): the index of its '/' and the end of its && segment. Null
     * when the cursor is somewhere else (a condition, a marker, chat text).
     */
    static int[] commandAt(String text, int from, int to, int cursor) {
        // a statement group holding the cursor narrows the search to its inside
        int[] group = groupAround(text, from, to, cursor);
        if (group != null) {
            return commandAt(text, group[0], group[1], cursor);
        }
        for (int[] segment : topLevelSegments(text, from, to)) {
            if (cursor <= segment[1]) {
                int start = ChatInputStyler.statementStartAfterPrefixes(text, skipSpaces(text, segment[0], segment[1]), segment[1]);
                if (start < segment[1] && text.charAt(start) == '/' && cursor > start) {
                    return new int[]{start, segment[1]};
                }
                return null;
            }
        }
        return null;
    }

    /**
     * The inside of the statement group in [from, to) whose parentheses hold
     * the cursor, or null. A statement group is one whose content starts with
     * / or # (a #if body, #silent (...)); a condition like #if (x > 5) isn't
     * one. An unclosed group runs to {@code to}, since that is what you have
     * while still typing it.
     */
    private static int[] groupAround(String text, int from, int to, int cursor) {
        boolean quoted = false;
        boolean marker = false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && !quoted) {
                marker = !marker;
            } else if (marker) {
                // a marker is an expression, not statements
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                int close = ListLiteral.groupEnd(text, i);
                int end = close < 0 || close > to ? to : close;
                int content = skipSpaces(text, i + 1, end);
                boolean statements = content < end && (text.charAt(content) == '/' || text.charAt(content) == '#');
                if (cursor > i && cursor <= end) {
                    return statements ? new int[]{i + 1, end} : null;
                }
                i = end;
            }
        }
        return null;
    }

    /** [from, to) split on top-level && (quotes, markers and groups bind tighter). */
    private static List<int[]> topLevelSegments(String text, int from, int to) {
        List<int[]> segments = new ArrayList<>();
        boolean quoted = false;
        boolean marker = false;
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && !quoted) {
                marker = !marker;
            } else if (marker) {
                // inside a marker
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (depth == 0 && c == '&' && i + 1 < to && text.charAt(i + 1) == '&') {
                    segments.add(new int[]{start, i});
                    start = i + 2;
                    i++;
                }
            }
        }
        segments.add(new int[]{start, to});
        return segments;
    }

    private static int skipSpaces(String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        return from;
    }

    /**
     * Ask Brigadier about {@code command} (it starts with '/'), completing at
     * {@code cursor} within it, and move the answer to {@code offset} in the
     * editor. Parsed twice when it holds $...$ markers, the second time with
     * them masked, keeping whichever got further: the chat bar does the same
     * so /setblock ~ ~ ~$x$ sto| still completes the block.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CompletableFuture<ExpressionCompletion.Result> complete(String command, int cursor, int offset) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || cursor < 1) {
            return null;
        }
        // 1.21.6 narrowed this dispatcher's type to ClientSuggestionProvider
        CommandDispatcher<SharedSuggestionProvider> dispatcher = connection.getCommands();
        ClientSuggestionProvider source = connection.getSuggestionsProvider();
        ParseResults<SharedSuggestionProvider> parse = parse(dispatcher, source, command);
        String masked = TupenterModClient.smartMaskMarkers(command);
        if (!masked.equals(command)) {
            ParseResults<SharedSuggestionProvider> maskedParse = parse(dispatcher, source, masked);
            if (maskedParse.getReader().getCursor() > parse.getReader().getCursor()) {
                parse = maskedParse;
            }
        }
        // a server-side suggestion request must carry this command alone, not
        // whatever chat segment the chat bar last re-rooted to
        ChainSuggestionScope.clear();
        CompletableFuture<Suggestions> future;
        try {
            future = dispatcher.getCompletionSuggestions(parse, cursor);
        } catch (RuntimeException ex) {
            return null; // Brigadier can't place the cursor (it can throw mid-edit); no list is right
        }
        return future.thenApply(suggestions -> toResult(suggestions, offset, cursor));
    }

    private static ParseResults<SharedSuggestionProvider> parse(CommandDispatcher<SharedSuggestionProvider> dispatcher,
                                                               SharedSuggestionProvider source, String command) {
        StringReader reader = new StringReader(command);
        reader.skip(); // one '/', as vanilla: //replace stays WorldEdit's /replace
        return dispatcher.parse(reader, source);
    }

    private static ExpressionCompletion.Result toResult(Suggestions suggestions, int offset, int cursor) {
        List<String> texts = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            if (!texts.contains(suggestion.getText())) {
                texts.add(suggestion.getText());
            }
        }
        int start = suggestions.isEmpty() ? cursor : suggestions.getRange().getStart();
        return new ExpressionCompletion.Result(offset + start, texts);
    }
}
