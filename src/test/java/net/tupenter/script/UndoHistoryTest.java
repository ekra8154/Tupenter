package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoHistoryTest {

    @Test
    void undoReturnsToTheStateBeforeAnEditGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("h", 1, 100);
        h.record("he", 2, 150);
        h.record("hey", 3, 200); // one coalesced group of inserts
        UndoHistory.State s = h.undo();
        assertEquals("", s.text());
        assertEquals(0, s.cursor());
        assertNull(h.undo(), "nothing before the first group");
    }

    @Test
    void redoReplaysAnUndoneGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("hi", 2, 100);
        assertEquals("", h.undo().text());
        UndoHistory.State r = h.redo();
        assertEquals("hi", r.text());
        assertEquals(2, r.cursor());
        assertNull(h.redo());
    }

    @Test
    void aPauseStartsANewUndoGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("abc", 3, 100);
        h.record("abcdef", 6, 2000); // >500ms later — separate group
        assertEquals("abc", h.undo().text(), "second group undone first");
        assertEquals("", h.undo().text());
    }

    @Test
    void whitespaceUndoesWordByWord() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("hello", 5, 100);
        h.record("hello ", 6, 150); // the space closes the "hello" group
        h.record("hello w", 7, 200);
        h.record("hello world", 11, 250);
        assertEquals("hello ", h.undo().text(), "second word peeled off");
        assertEquals("", h.undo().text(), "then the first");
    }

    @Test
    void switchingFromInsertToDeleteBreaksTheGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("abc", 3, 100);
        h.record("ab", 2, 150);  // delete — a new group even within the pause window
        h.record("a", 1, 200);
        assertEquals("abc", h.undo().text(), "deletes undo back to the pre-delete state");
        assertEquals("", h.undo().text());
    }

    @Test
    void aCaretMoveEndsTheGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("abc", 3, 100);
        h.record("abc", 0, 150);   // caret jumped, text unchanged — no undo entry, but a break
        h.record("Xabc", 1, 200);  // typing here is a new group
        assertEquals("abc", h.undo().text(), "the X is its own undo");
        assertEquals("", h.undo().text());
    }

    @Test
    void aNewEditClearsTheRedoStack() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("one", 3, 100);
        h.undo();                 // redo now has "one"
        assertTrue(h.canRedo());
        h.record("two", 3, 2000); // a fresh edit invalidates redo
        assertFalse(h.canRedo());
        assertNull(h.redo());
    }

    @Test
    void caretOnlyMovesNeverCreateUndoEntries() {
        UndoHistory h = new UndoHistory("abc", 0);
        h.record("abc", 1, 100);
        h.record("abc", 2, 150);
        assertFalse(h.canUndo(), "moving the caret is not an edit");
    }

    @Test
    void editingExistingContentUndoesToTheOriginal() {
        UndoHistory h = new UndoHistory("existing", 8);
        h.record("existing!", 9, 100);
        assertEquals("existing", h.undo().text());
    }

    @Test
    void thereIsNothingToUndoOrRedoOnAFreshHistory() {
        UndoHistory h = new UndoHistory("", 0);
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertNull(h.undo());
        assertNull(h.redo());
    }

    /**
     * Deleting and inserting are different KINDS of edit, so switching between
     * them starts a new group — otherwise one undo would swallow both the word
     * you typed and the word you deleted before it.
     */
    @Test
    void switchingBetweenTypingAndDeletingStartsANewGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("ab", 2, 100);
        h.record("abc", 3, 150);   // still inserting — same group
        h.record("ab", 2, 200);    // now deleting — boundary
        assertEquals("abc", h.undo().text(), "the delete undoes on its own");
        assertEquals("", h.undo().text(), "and the whole insert run undoes together");
    }

    /** A pause longer than the coalescing window ends the group too. */
    @Test
    void aPauseEndsTheGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("a", 1, 100);
        h.record("ab", 2, 200);       // within the window
        h.record("abc", 3, 5000);     // long pause → its own group
        assertEquals("ab", h.undo().text());
        assertEquals("", h.undo().text());
    }

    /** Typing a space is a natural word boundary, so the next edit starts fresh. */
    @Test
    void aSpaceBreaksTheGroupAtTheNextEdit() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("hi", 2, 100);
        h.record("hi ", 3, 150);      // the space ends this group
        h.record("hi there", 8, 200); // a new one
        assertEquals("hi ", h.undo().text(), "the second word undoes by itself");
    }

    /** Moving the caret mid-typing also splits the group. */
    @Test
    void aCaretMoveBetweenEditsSplitsTheGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("ab", 2, 100);
        h.record("ab", 0, 150);   // caret move only — no entry, but a boundary
        h.record("Xab", 1, 200);
        assertEquals("ab", h.undo().text());
        assertEquals("", h.undo().text());
    }

    /** History is bounded, so a long session drops its oldest states instead of growing forever. */
    @Test
    void theUndoStackIsCappedAtItsDepth() {
        UndoHistory h = new UndoHistory("", 0);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            text.append('x');
            h.record(text.toString(), text.length(), i * 1000L); // a pause each time = 250 groups
        }
        int undone = 0;
        while (h.undo() != null) {
            undone++;
        }
        assertEquals(200, undone, "capped at MAX_DEPTH, oldest dropped");
    }

    // ---- several carets ----

    private static java.util.List<UndoHistory.Caret> carets(int... posAnchorPairs) {
        java.util.List<UndoHistory.Caret> out = new java.util.ArrayList<>();
        for (int k = 0; k < posAnchorPairs.length; k += 2) {
            out.add(new UndoHistory.Caret(posAnchorPairs[k], posAnchorPairs[k + 1]));
        }
        return out;
    }

    @Test
    void undoBringsBackEveryCaretFromBeforeTheEdit() {
        UndoHistory h = new UndoHistory("ab", 0);
        h.record("ab", carets(1, 1, 2, 2), 0);          // a second caret is added
        h.record("aXbX", carets(2, 2, 4, 4), 100);      // typed at both
        UndoHistory.State before = h.undo();
        assertEquals("ab", before.text());
        assertEquals(carets(1, 1, 2, 2), before.carets());
        UndoHistory.State after = h.redo();
        assertEquals("aXbX", after.text());
        assertEquals(carets(2, 2, 4, 4), after.carets());
    }

    @Test
    void selectionsComeBackToo() {
        UndoHistory h = new UndoHistory("foo foo", 0);
        h.record("foo foo", carets(3, 0, 7, 4), 0);     // both "foo" selected (Ctrl+D)
        h.record("x x", carets(1, 1, 3, 3), 100);       // typed over them
        UndoHistory.State before = h.undo();
        assertEquals("foo foo", before.text());
        assertEquals(carets(3, 0, 7, 4), before.carets());
        assertEquals(3, before.cursor(), "cursor() is the primary's position");
    }

    @Test
    void changingTheCaretsEndsTheTypingGroup() {
        UndoHistory h = new UndoHistory("", 0);
        h.record("a", 1, 0);
        h.record("ab", 2, 50);
        h.record("ab", carets(2, 2, 0, 0), 60);         // a caret added mid-typing
        h.record("cacbc", carets(5, 5, 1, 1), 70);      // typed at both
        assertEquals("ab", h.undo().text(), "the multi-caret edit is its own step");
        assertEquals("", h.undo().text());
    }
}
