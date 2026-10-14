package net.tupenter.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The bookkeeping behind multiple carets in one editor: running an edit at
 * every caret and keeping every OTHER caret on the same text while the text
 * under it moves.
 *
 * <p>Each edit runs once per caret through the editor's own single-caret code
 * (typing, auto-brackets, word deletes, Enter's indent all behave exactly as
 * with one caret). After each run, the span of text that changed is worked out
 * ANCHORED AT THE CARET THAT CHANGED IT, and every other caret is moved by
 * that span: before it, untouched; after it, shifted by the length change;
 * inside text that was replaced, moved to where the replacement starts.
 *
 * <p>Anchoring is what makes this exact. A plain before/after comparison is
 * ambiguous in repeated text: typing "a" into "aa|aa" could be read as an
 * insert at any of five places, and reading it at the wrong one walks the
 * other carets off their characters. The edit can't start after the caret's
 * selection, nor end before it, which pins the span to the right place.
 *
 * <p>No Minecraft types here, so all of it is unit tested.
 */
final class MultiCaret {

    private MultiCaret() {
    }

    /** One caret: where it is, and where its selection began (equal when nothing is selected). */
    record Caret(int pos, int anchor) {
        static Caret at(int pos) {
            return new Caret(pos, pos);
        }

        int start() {
            return Math.min(pos, anchor);
        }

        int end() {
            return Math.max(pos, anchor);
        }

        boolean hasSelection() {
            return pos != anchor;
        }
    }

    /** The editor the carets live in: its text, and its single real caret. */
    interface Editor {
        String text();

        Caret caret();

        void setCaret(Caret caret);
    }

    /** The carets after an edit, and which of them is the primary (the editor's own caret). */
    record Result(List<Caret> carets, int primary) {
    }

    /**
     * Run {@code edit} once at every caret, in text order, keeping the others
     * in place as each run changes the text, then merge carets that ended up
     * on top of each other. {@code edit} receives the caret's index in text
     * order (paste uses it to hand each caret its own line).
     */
    static Result applyAtEach(Editor editor, List<Caret> carets, int primary, IntConsumer edit) {
        List<Integer> order = textOrder(carets);
        List<Caret> current = new ArrayList<>(carets);
        for (int rank = 0; rank < order.size(); rank++) {
            int i = order.get(rank);
            Caret at = current.get(i);
            String before = editor.text();
            editor.setCaret(at);
            edit.accept(rank);
            String after = editor.text();
            Caret moved = editor.caret();
            if (!before.equals(after)) {
                int[] span = editSpan(before, after, at);
                for (int j = 0; j < current.size(); j++) {
                    if (j != i) {
                        current.set(j, remap(current.get(j), span));
                    }
                }
            }
            current.set(i, moved);
        }
        return merge(current, primary, editor.text().length());
    }

    /** Caret indices sorted by where they start in the text. */
    private static List<Integer> textOrder(List<Caret> carets) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < carets.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> carets.get(i).start()).thenComparingInt(i -> carets.get(i).end()));
        return order;
    }

    /**
     * {start, oldEnd, newEnd}: text [start, oldEnd) of {@code before} became
     * [start, newEnd) of {@code after}, for an edit made at {@code at}. The
     * shared prefix may not run past the caret's selection start, and the
     * shared suffix may not reach back past its end, so the span sits where
     * the edit really happened even in repeated text.
     */
    static int[] editSpan(String before, String after, Caret at) {
        int limit = Math.min(before.length(), after.length());
        int prefix = 0;
        while (prefix < limit && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        prefix = Math.min(prefix, Math.min(at.start(), limit));
        int suffix = 0;
        while (suffix < limit - prefix
                && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        suffix = Math.min(suffix, Math.max(0, before.length() - at.end()));
        return new int[]{prefix, before.length() - suffix, after.length() - suffix};
    }

    /** Where {@code caret} belongs once the span has been replaced. */
    static Caret remap(Caret caret, int[] span) {
        return new Caret(remapIndex(caret.pos(), span), remapIndex(caret.anchor(), span));
    }

    /**
     * Before the span (or exactly at its start): untouched. After it: shifted
     * by how much the span grew or shrank. Inside text that was replaced: at
     * the start of the replacement, since what it sat on is gone.
     */
    static int remapIndex(int index, int[] span) {
        int start = span[0];
        int oldEnd = span[1];
        int newEnd = span[2];
        if (index <= start) {
            return index;
        }
        if (index >= oldEnd) {
            return index + (newEnd - oldEnd);
        }
        return start;
    }

    /**
     * Clamp every caret into the text, then fold carets that overlap (or sit
     * at the same spot) into one, keeping whichever of them is the primary.
     * Touching selections stay separate, as in other editors.
     */
    static Result merge(List<Caret> carets, int primary, int textLength) {
        List<Caret> clamped = new ArrayList<>();
        for (Caret caret : carets) {
            clamped.add(new Caret(clamp(caret.pos(), textLength), clamp(caret.anchor(), textLength)));
        }
        List<Integer> order = textOrder(clamped);
        List<Caret> out = new ArrayList<>();
        int outPrimary = 0;
        Caret open = null;
        boolean openHasPrimary = false;
        for (int i : order) {
            Caret next = clamped.get(i);
            boolean nextIsPrimary = i == primary;
            if (open != null && overlaps(open, next)) {
                open = union(open, next, nextIsPrimary);
                openHasPrimary |= nextIsPrimary;
                continue;
            }
            if (open != null) {
                if (openHasPrimary) {
                    outPrimary = out.size();
                }
                out.add(open);
            }
            open = next;
            openHasPrimary = nextIsPrimary;
        }
        if (open != null) {
            if (openHasPrimary) {
                outPrimary = out.size();
            }
            out.add(open);
        }
        return new Result(out, outPrimary);
    }

    private static boolean overlaps(Caret a, Caret b) {
        if (!a.hasSelection() && !b.hasSelection()) {
            return a.pos() == b.pos();
        }
        if (a.hasSelection() && b.hasSelection()) {
            return b.start() < a.end(); // b starts no earlier than a (text order)
        }
        // a bare caret inside (not on the edge of) a selection is swallowed by it
        Caret selection = a.hasSelection() ? a : b;
        Caret bare = a.hasSelection() ? b : a;
        return bare.pos() > selection.start() && bare.pos() < selection.end();
    }

    /** The two carets as one, keeping the direction of whichever is the primary (else the first). */
    private static Caret union(Caret a, Caret b, boolean bIsPrimary) {
        int start = Math.min(a.start(), b.start());
        int end = Math.max(a.end(), b.end());
        Caret direction = bIsPrimary ? b : a;
        boolean forward = direction.pos() >= direction.anchor();
        return forward ? new Caret(end, start) : new Caret(start, end);
    }

    private static int clamp(int index, int length) {
        return Math.max(0, Math.min(index, length));
    }

    /** Text and carets after moving lines. */
    record Moved(String text, List<Caret> carets, int primary) {
    }

    /**
     * Alt+Up/Down: move every line a caret is on (all the lines a selection
     * touches) up or down one line, carets riding along at the same column.
     * A selection ending at the very start of a line doesn't take that line
     * (you selected up to it, not into it). Blocks from several carets that
     * touch move as one. Null when a block is already at the top or bottom,
     * so nothing moves at all rather than some blocks and not others.
     *
     * <p>Lines are the text's own (split at '\n'), not the editor's wrapped
     * display lines.
     */
    static Moved moveLines(String text, List<Caret> carets, int primary, boolean up) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int[] lineStarts = lineStarts(lines);
        // the blocks of lines to move, merged where they touch
        List<int[]> blocks = new ArrayList<>();
        for (Caret caret : carets) {
            int first = lineOf(lineStarts, caret.start());
            int last = lineOf(lineStarts, caret.end());
            if (caret.hasSelection() && last > first && caret.end() == lineStarts[last]) {
                last--; // ends at the start of a line: that line isn't part of it
            }
            blocks.add(new int[]{first, last});
        }
        blocks.sort(Comparator.comparingInt(b -> b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] block : blocks) {
            int[] previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null && block[0] <= previous[1] + 1) {
                previous[1] = Math.max(previous[1], block[1]);
            } else {
                merged.add(new int[]{block[0], block[1]});
            }
        }
        if (up ? merged.get(0)[0] == 0 : merged.get(merged.size() - 1)[1] == lines.size() - 1) {
            return null;
        }
        // where each old line ends up: a block shifts by one, the line it
        // passes jumps to the block's other side
        int[] newIndex = new int[lines.size()];
        for (int i = 0; i < newIndex.length; i++) {
            newIndex[i] = i;
        }
        for (int[] block : merged) {
            int size = block[1] - block[0] + 1;
            for (int i = block[0]; i <= block[1]; i++) {
                newIndex[i] = i + (up ? -1 : 1);
            }
            int passed = up ? block[0] - 1 : block[1] + 1;
            newIndex[passed] = passed + (up ? size : -size);
        }
        String[] moved = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            moved[newIndex[i]] = lines.get(i);
        }
        List<String> movedLines = List.of(moved);
        int[] newStarts = lineStarts(movedLines);
        List<Caret> out = new ArrayList<>();
        for (Caret caret : carets) {
            int newStart = movePosition(caret.start(), lineStarts, newIndex, newStarts);
            int newEnd = movePosition(caret.end(), lineStarts, newIndex, newStarts);
            int endLine = lineOf(lineStarts, caret.end());
            if (caret.hasSelection() && endLine > lineOf(lineStarts, caret.start()) && caret.end() == lineStarts[endLine]) {
                // ends at the start of a line it didn't take: that end means "just
                // after my last line", so it stays right after it, wherever it went
                int last = endLine - 1;
                newEnd = newStarts[newIndex[last]] + lines.get(last).length() + 1;
            }
            boolean forward = caret.pos() >= caret.anchor();
            out.add(forward ? new Caret(newEnd, newStart) : new Caret(newStart, newEnd));
        }
        return new Moved(String.join("\n", movedLines), out, primary);
    }

    /** The same column on the line's new place. */
    private static int movePosition(int pos, int[] oldStarts, int[] newIndex, int[] newStarts) {
        int line = lineOf(oldStarts, pos);
        return newStarts[newIndex[line]] + (pos - oldStarts[line]);
    }

    private static int[] lineStarts(List<String> lines) {
        int[] starts = new int[lines.size()];
        int at = 0;
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = at;
            at += lines.get(i).length() + 1;
        }
        return starts;
    }

    /** The line holding {@code pos} (a position at a line's end belongs to that line). */
    private static int lineOf(int[] starts, int pos) {
        int line = 0;
        while (line + 1 < starts.length && starts[line + 1] <= pos) {
            line++;
        }
        return line;
    }
}
