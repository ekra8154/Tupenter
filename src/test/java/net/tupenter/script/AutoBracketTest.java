package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoBracketTest {

    private static AutoBracket.Edit type(String text, int cursor, char c) {
        return AutoBracket.onChar(text, cursor, cursor, c);
    }

    @Test
    void autoClosesOnAnEmptyCaret() {
        AutoBracket.Edit e = type("/say ", 5, '(');
        assertEquals("/say ()", e.text());
        assertEquals(6, e.cursor(), "caret lands between the pair");
    }

    @Test
    void autoClosesTheSymmetricDollar() {
        AutoBracket.Edit e = type("/say ", 5, '$');
        assertEquals("/say $$", e.text());
        assertEquals(6, e.cursor());
    }

    @Test
    void wrapsASelection() {
        // "world" (indices 11..16) selected in /say hello world
        AutoBracket.Edit e = AutoBracket.onChar("/say hello world", 11, 16, '(');
        assertEquals("/say hello (world)", e.text());
        assertEquals(18, e.cursor(), "caret just past the close");
    }

    @Test
    void wrapsASelectionInDollars() {
        AutoBracket.Edit e = AutoBracket.onChar("/echo client.x", 6, 14, '$');
        assertEquals("/echo $client.x$", e.text());
    }

    @Test
    void skipsOverAnExistingCloser() {
        // caret between ( and ), typing ) steps past instead of inserting
        AutoBracket.Edit e = type("/say ()", 6, ')');
        assertEquals("/say ()", e.text(), "text unchanged");
        assertEquals(7, e.cursor(), "caret moved past the )");
    }

    @Test
    void skipsOverAClosingDollar() {
        AutoBracket.Edit e = type("/echo $x$", 8, '$');
        assertEquals("/echo $x$", e.text());
        assertEquals(9, e.cursor());
    }

    @Test
    void doesNotAutoCloseInsideAQuotedString() {
        // inside "…" a ( is literal text, so type normally
        assertNull(type("/echo $pick(\"a", 13, '('));
    }

    @Test
    void dollarInsideQuotesIsLiteral() {
        assertNull(type("/say \"i owe ", 11, '$'));
    }

    @Test
    void doesNotAutoCloseAfterABackslashEscape() {
        // \$ \( … is an escape for a literal char — don't pair it
        assertNull(type("/echo \\", 7, '$'));
        assertNull(type("/say \\", 6, '('));
    }

    @Test
    void anEscapedBackslashStillAutoCloses() {
        // \\ is an escaped backslash, so the next char is NOT escaped
        AutoBracket.Edit e = type("/echo \\\\", 8, '$');
        assertEquals("/echo \\\\$$", e.text());
    }

    @Test
    void deletesAnEmptyPairOnBackspace() {
        AutoBracket.Edit e = AutoBracket.onBackspace("/say ()", 6);
        assertEquals("/say ", e.text());
        assertEquals(5, e.cursor());
    }

    @Test
    void deletesAnEmptyDollarPairOnBackspace() {
        AutoBracket.Edit e = AutoBracket.onBackspace("/echo $$", 7);
        assertEquals("/echo ", e.text());
    }

    @Test
    void backspaceLeavesANonPairAlone() {
        assertNull(AutoBracket.onBackspace("/say hi", 7), "no pair around the caret");
        assertNull(AutoBracket.onBackspace("/say (x)", 6), "( and x aren't an empty pair");
    }

    @Test
    void aClosingCharWithNothingToSkipTypesNormally() {
        assertNull(type("/say ", 5, ')'), "no ) ahead — fall through to normal typing");
    }

    @Test
    void quoteCountingIsPerLineForTheEditor() {
        // a stray quote on line 1 must not make line 2 look "inside a string"
        AutoBracket.Edit e = type("say \"hi\n/echo ", 14, '(');
        assertEquals("say \"hi\n/echo ()", e.text());
    }

    /** All five pairs behave the same — the two symmetric ones included. */
    @Test
    void everyPairAutoCloses() {
        assertEquals("/say ()", type("/say ", 5, '(').text());
        assertEquals("/say []", type("/say ", 5, '[').text());
        assertEquals("/say {}", type("/say ", 5, '{').text());
        assertEquals("/say \"\"", type("/say ", 5, '"').text());
        assertEquals("/say $$", type("/say ", 5, '$').text());
    }

    @Test
    void everyCloserSkipsOverItself() {
        assertEquals(7, type("/say ()", 6, ')').cursor());
        assertEquals(7, type("/say []", 6, ']').cursor());
        assertEquals(7, type("/say {}", 6, '}').cursor());
        assertEquals(7, type("/say $$", 6, '$').cursor());
    }

    @Test
    void everyPairIsDeletedWholeOnBackspace() {
        assertEquals("/say ", AutoBracket.onBackspace("/say []", 6).text());
        assertEquals("/say ", AutoBracket.onBackspace("/say {}", 6).text());
        assertEquals("/say ", AutoBracket.onBackspace("/say \"\"", 6).text());
    }

    @Test
    void backspaceAtEitherEndOfTheTextIsNormal() {
        assertNull(AutoBracket.onBackspace("()", 0), "nothing before the caret");
        assertNull(AutoBracket.onBackspace("()", 2), "nothing after the caret");
        assertNull(AutoBracket.onBackspace("", 0));
    }

    @Test
    void everyPairWrapsASelection() {
        // "hi" selected in "say hi" (indices 4..6)
        assertEquals("say (hi)", AutoBracket.onChar("say hi", 4, 6, '(').text());
        assertEquals("say [hi]", AutoBracket.onChar("say hi", 4, 6, '[').text());
        assertEquals("say \"hi\"", AutoBracket.onChar("say hi", 4, 6, '"').text());
        assertEquals("say $hi$", AutoBracket.onChar("say hi", 4, 6, '$').text());
    }

    /** A non-bracket key with a selection replaces it the normal way — hands off. */
    @Test
    void anOrdinaryKeyOverASelectionIsLeftToTheWidget() {
        assertNull(AutoBracket.onChar("say hi", 4, 6, 'x'));
        assertNull(AutoBracket.onChar("say hi", 4, 6, ')'), "a closer doesn't wrap either");
    }

    /**
     * A backslash means the user wants the literal character, so auto-close
     * stays out of the way — but only for an ODD run, since \\ is itself an
     * escaped backslash and the $ after it is a real marker again.
     */
    @Test
    void escapingSuppressesAutoCloseButDoubleBackslashDoesNot() {
        assertNull(type("/echo \\", 7, '$'), "\\$ is a literal dollar");
        assertNull(type("/echo \\", 7, '('));
        assertEquals("/echo \\\\$$", type("/echo \\\\", 8, '$').text(), "\\\\ is an escaped backslash");
    }

    @Test
    void autoCloseStaysOutOfQuotedStrings() {
        assertNull(type("/echo \"hi ", 10, '('), "inside a string a ( is just text");
        assertNull(type("/echo \"hi ", 10, '$'));
        // after the string closes it resumes
        assertEquals("/echo \"hi\" ()", type("/echo \"hi\" ", 11, '(').text());
        // an escaped quote doesn't open a string
        assertEquals("/echo \\\" ()", type("/echo \\\" ", 9, '(').text());
    }
}
