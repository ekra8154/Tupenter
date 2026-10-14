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
}
