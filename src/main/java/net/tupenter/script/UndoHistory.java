package net.tupenter.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Undo/redo for a text field — pure string bookkeeping, no Minecraft, so it
 * unit-tests. {@link net.tupenter.command} / editor adapters feed it every edit
 * ({@link #record}) and apply what {@link #undo}/{@link #redo} return.
 *
 * <p>Edits COALESCE so undo doesn't step one character at a time: a run of
 * edits collapses into a single undo unit until a boundary — a typing pause,
 * a whitespace/newline (so words undo one at a time), a switch between
 * inserting and deleting, or a caret move. That's roughly what VS Code and
 * IntelliJ do.
 *
 * <p>Each state keeps every caret and its selection, not just one position,
 * so undoing an edit made with several carets brings all of them back to
 * where they were before it (and redo to where they were after).
 */
public final class UndoHistory {
    /** One caret: where it is, and where its selection began (equal when nothing is selected). */
    public record Caret(int pos, int anchor) {
        public static Caret at(int pos) {
            return new Caret(pos, pos);
        }
    }

    /** A text snapshot and every caret in it, the primary first. */
    public record State(String text, List<Caret> carets) {
        public State {
            if (carets.isEmpty()) {
                throw new IllegalArgumentException("a state needs at least one caret");
            }
            carets = List.copyOf(carets);
        }

        public State(String text, int cursor) {
            this(text, List.of(Caret.at(cursor)));
        }

        /** Where the primary caret sat. */
        public int cursor() {
            return carets.get(0).pos();
        }
    }

    private static final long PAUSE_MS = 500;
    private static final int MAX_DEPTH = 200;

    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    private State current;
    private long lastEditTime;
    private int lastKind;        // -1 delete, 0 replace/none, +1 insert
    private boolean groupOpen;   // is a coalescing group in progress?
    private boolean breakNext;   // force a boundary on the next edit

    public UndoHistory(String text, int cursor) {
        this(text, List.of(Caret.at(cursor)));
    }

    public UndoHistory(String text, List<Caret> carets) {
        this.current = new State(text, carets);
    }

    /** {@link #record(String, List, long)} for a single bare caret. */
    public void record(String newText, int newCursor, long now) {
        record(newText, List.of(Caret.at(newCursor)), now);
    }

    /**
     * Record the editor's new state after a keystroke. A caret-only move keeps
     * no undo entry but ends the current group; a text change opens a new undo
     * unit when a boundary is crossed, otherwise folds into the current one.
     */
    public void record(String newText, List<Caret> newCarets, long now) {
        if (newText.equals(current.text())) {
            if (!newCarets.equals(current.carets())) {
                breakNext = true; // moved, added or reselected carets: the next edit starts fresh
            }
            current = new State(newText, newCarets);
            return;
        }
        int kind = classify(current.text(), newText);
        boolean boundary = !groupOpen
                || breakNext
                || now - lastEditTime > PAUSE_MS
                || (lastKind != 0 && kind != lastKind);
        if (boundary) {
            pushUndo(current); // checkpoint the pre-edit state
            redo.clear();
            groupOpen = true;
        }
        current = new State(newText, newCarets);
        lastEditTime = now;
        lastKind = kind;
        breakNext = endsWithWhitespace(newText, current.cursor()); // typed a space → break after
    }

    /** Step back one undo unit, or null if there's nothing to undo. */
    public State undo() {
        if (undo.isEmpty()) {
            return null;
        }
        redo.push(current);
        current = undo.pop();
        resetGroup();
        return current;
    }

    /** Redo the last undone unit, or null if there's nothing to redo. */
    public State redo() {
        if (redo.isEmpty()) {
            return null;
        }
        pushUndo(current);
        current = redo.pop();
        resetGroup();
        return current;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private void resetGroup() {
        groupOpen = false;
        breakNext = false;
        lastKind = 0;
    }

    private void pushUndo(State state) {
        undo.push(state);
        if (undo.size() > MAX_DEPTH) {
            undo.removeLast(); // drop the oldest checkpoint
        }
    }

    private static int classify(String oldText, String newText) {
        if (newText.length() > oldText.length()) {
            return 1;
        }
        if (newText.length() < oldText.length()) {
            return -1;
        }
        return 0;
    }

    private static boolean endsWithWhitespace(String text, int cursor) {
        return cursor > 0 && cursor <= text.length() && Character.isWhitespace(text.charAt(cursor - 1));
    }
}
