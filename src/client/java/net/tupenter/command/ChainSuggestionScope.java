package net.tupenter.command;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place a chained line has to leave its own coordinate system: an
 * ask-the-server suggestion request.
 *
 * <p>Chain-aware suggestions work by re-rooting the parse at the segment the
 * cursor is in while keeping every index 1:1 with the chat bar — the line is
 * truncated at the segment's end and the parse simply STARTS later. That is
 * exactly right for anything computed on this side, because the ranges that come
 * back already address the real input.
 *
 * <p>It is wrong for the one kind of suggestion that is computed on the other
 * side. When a command's arguments say "ask the server" — which is how every
 * mod-defined argument arrives, WorldEdit's patterns and masks among them — the
 * client sends the parse's whole input string and the server parses it from the
 * beginning. The beginning is the PREVIOUS segment, and no server has ever heard
 * of {@code &&}, so the request either fails outright or, when the command's
 * last argument is greedy, completes a blob containing another whole command.
 * Either way the answer is nothing, which is why autocomplete used to go dead
 * after the first {@code &&} on exactly those commands.
 *
 * <p>So this trims the outgoing request to the segment the cursor is in, and
 * moves the answer back by the same amount when it arrives. The offset is
 * remembered against the request id rather than read live, because the reply is
 * asynchronous and the cursor may have moved on to a different segment by then.
 */
public final class ChainSuggestionScope {

    /** Where the current parse's segment starts, or 0 when the line isn't chained. */
    private static int segmentStart;

    private static int pendingId = -1;
    private static int pendingStart;

    private ChainSuggestionScope() {
    }

    /** Called for every suggestion pass: the segment's start, or 0 for none. */
    public static void reroot(int start) {
        segmentStart = Math.max(0, start);
    }

    public static void clear() {
        segmentStart = 0;
    }

    /**
     * The text to send for request {@code id} — the cursor's segment alone. The
     * server strips one leading slash of its own, so a segment keeps whichever
     * slashes it was written with and {@code //replace} still arrives whole.
     */
    public static String outgoingText(String input, int id) {
        if (segmentStart <= 0 || segmentStart >= input.length()) {
            pendingId = -1;
            return input;
        }
        pendingId = id;
        pendingStart = segmentStart;
        return input.substring(segmentStart);
    }

    /** The server's answer, moved back into chat-bar coordinates. */
    public static Suggestions realign(int id, Suggestions suggestions) {
        if (id != pendingId || pendingStart == 0) {
            return suggestions;
        }
        pendingId = -1;
        return shift(suggestions, pendingStart);
    }

    /** Every range in {@code suggestions} moved right by {@code by}. */
    public static Suggestions shift(Suggestions suggestions, int by) {
        if (by == 0 || suggestions.isEmpty()) {
            return suggestions;
        }
        List<Suggestion> moved = new ArrayList<>(suggestions.getList().size());
        for (Suggestion suggestion : suggestions.getList()) {
            moved.add(new Suggestion(shift(suggestion.getRange(), by),
                    suggestion.getText(), suggestion.getTooltip()));
        }
        return new Suggestions(shift(suggestions.getRange(), by), moved);
    }

    private static StringRange shift(StringRange range, int by) {
        return StringRange.between(range.getStart() + by, range.getEnd() + by);
    }
}
