package net.tupenter.script;

import java.util.ArrayDeque;
import java.util.Deque;

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
 */
public final class UndoHistory {
    /** A text snapshot and where the caret sat. */
    public record State(String text, int cursor) {
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
        this.current = new State(text, cursor);
    }

    /**
     * Record the editor's new state after a keystroke. A caret-only move keeps
     * no undo entry but ends the current group; a text change opens a new undo
     * unit when a boundary is crossed, otherwise folds into the current one.
     */
    public void record(String newText, int newCursor, long now) {
        if (newText.equals(current.text())) {
            if (newCursor != current.cursor()) {
                breakNext = true; // moved the caret → next edit starts fresh
            }
            current = new State(newText, newCursor);
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
        current = new State(newText, newCursor);
        lastEditTime = now;
        lastKind = kind;
        breakNext = endsWithWhitespace(newText, newCursor); // typed a space → break after
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
