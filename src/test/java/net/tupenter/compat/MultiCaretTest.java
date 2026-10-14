package net.tupenter.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Multiple carets stay on the right characters while the text changes under
 * them, including in repeated text where a naive diff can't tell where an
 * edit happened.
 */
class MultiCaretTest {

    /** A tiny single-caret editor with the edits the real one makes. */
    private static final class FakeEditor implements MultiCaret.Editor {
        String text;
        MultiCaret.Caret caret = MultiCaret.Caret.at(0);

        FakeEditor(String text) {
            this.text = text;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public MultiCaret.Caret caret() {
            return caret;
        }

        @Override
        public void setCaret(MultiCaret.Caret caret) {
            this.caret = caret;
        }

        /** Replace the selection (or insert at the caret), caret after the insert. */
        void insert(String s) {
            text = text.substring(0, caret.start()) + s + text.substring(caret.end());
            caret = MultiCaret.Caret.at(caret.start() + s.length());
        }

        void backspace() {
            if (caret.hasSelection()) {
                insert("");
            } else if (caret.pos() > 0) {
                text = text.substring(0, caret.pos() - 1) + text.substring(caret.pos());
                caret = MultiCaret.Caret.at(caret.pos() - 1);
            }
        }

        void delete() {
            if (caret.hasSelection()) {
                insert("");
            } else if (caret.pos() < text.length()) {
                text = text.substring(0, caret.pos()) + text.substring(caret.pos() + 1);
            }
        }

        /** An auto-bracket: "()" with the caret between. */
        void pair() {
            insert("()");
            caret = MultiCaret.Caret.at(caret.pos() - 1);
        }

        /** Ctrl+Backspace: back to the previous space. */
        void deleteWordBack() {
            int to = caret.pos();
            int from = to;
            while (from > 0 && text.charAt(from - 1) == ' ') {
                from--;
            }
            while (from > 0 && text.charAt(from - 1) != ' ') {
                from--;
            }
            text = text.substring(0, from) + text.substring(to);
            caret = MultiCaret.Caret.at(from);
        }
    }

    private static List<MultiCaret.Caret> at(int... positions) {
        List<MultiCaret.Caret> carets = new ArrayList<>();
        for (int p : positions) {
            carets.add(MultiCaret.Caret.at(p));
        }
        return carets;
    }

    private static List<Integer> positions(MultiCaret.Result result) {
        return result.carets().stream().map(MultiCaret.Caret::pos).toList();
    }

    private static MultiCaret.Result run(FakeEditor editor, List<MultiCaret.Caret> carets, IntConsumer edit) {
        return MultiCaret.applyAtEach(editor, carets, 0, edit);
    }

    @Test
    void typingAtSeveralCarets() {
        FakeEditor editor = new FakeEditor("abc");
        MultiCaret.Result result = run(editor, at(1, 2, 3), rank -> editor.insert("X"));
        assertEquals("aXbXcX", editor.text);
        assertEquals(List.of(2, 4, 6), positions(result));
    }

    @Test
    void typingIntoRepeatedTextLandsEachCaretOnItsOwnSpot() {
        FakeEditor editor = new FakeEditor("aaaa");
        MultiCaret.Result result = run(editor, at(2, 4), rank -> editor.insert("a"));
        assertEquals("aaaaaa", editor.text);
        assertEquals(List.of(3, 6), positions(result));
    }

    @Test
    void caretsGivenOutOfOrderAreStillRunInTextOrder() {
        FakeEditor editor = new FakeEditor("abc");
        MultiCaret.Result result = MultiCaret.applyAtEach(editor, at(3, 1), 0, rank -> editor.insert("X"));
        assertEquals("aXbcX", editor.text);
        assertEquals(List.of(2, 5), positions(result));
        assertEquals(1, result.primary(), "the primary (given first, at 3) is now second in text order");
    }

    @Test
    void backspaceAtNeighbouringCaretsMergesThem() {
        FakeEditor editor = new FakeEditor("abc");
        MultiCaret.Result result = run(editor, at(1, 2), rank -> editor.backspace());
        assertEquals("c", editor.text);
        assertEquals(List.of(0), positions(result));
    }

    @Test
    void deleteForwardInRepeatedText() {
        FakeEditor editor = new FakeEditor("aaaa");
        MultiCaret.Result result = run(editor, at(0, 2), rank -> editor.delete());
        assertEquals("aa", editor.text);
        assertEquals(List.of(0, 1), positions(result));
    }

    @Test
    void typingReplacesEverySelection() {
        FakeEditor editor = new FakeEditor("foo bar foo");
        List<MultiCaret.Caret> carets = List.of(new MultiCaret.Caret(3, 0), new MultiCaret.Caret(11, 8));
        MultiCaret.Result result = run(editor, carets, rank -> editor.insert("x"));
        assertEquals("x bar x", editor.text);
        assertEquals(List.of(1, 7), positions(result));
    }

    @Test
    void autoBracketPairsAtEveryCaret() {
        FakeEditor editor = new FakeEditor("ab");
        MultiCaret.Result result = run(editor, at(1, 2), rank -> editor.pair());
        assertEquals("a()b()", editor.text);
        assertEquals(List.of(2, 5), positions(result));
    }

    @Test
    void wordDeletesThatReachBackPastNothingOfTheOthers() {
        FakeEditor editor = new FakeEditor("aa bb cc");
        MultiCaret.Result result = run(editor, at(5, 8), rank -> editor.deleteWordBack());
        assertEquals("aa  ", editor.text);
        assertEquals(List.of(3, 4), positions(result));
    }

    @Test
    void eachCaretCanGetItsOwnLineOfAPaste() {
        FakeEditor editor = new FakeEditor("a\nb\nc");
        String[] lines = {"1", "2", "3"};
        MultiCaret.Result result = run(editor, at(5, 1, 3), rank -> editor.insert(lines[rank]));
        assertEquals("a1\nb2\nc3", editor.text, "lines go by text order, not by the order carets were added");
        assertEquals(List.of(2, 5, 8), positions(result));
    }

    @Test
    void anotherCaretInsideTextThatGetsReplacedMovesToTheReplacement() {
        int[] span = {2, 6, 3}; // [2,6) became [2,3)
        assertEquals(1, MultiCaret.remapIndex(1, span));
        assertEquals(2, MultiCaret.remapIndex(2, span));
        assertEquals(2, MultiCaret.remapIndex(4, span));
        assertEquals(3, MultiCaret.remapIndex(6, span));
        assertEquals(5, MultiCaret.remapIndex(8, span));
    }

    @Test
    void theSpanIsPinnedToTheCaretThatEdited() {
        // typing "a" at 1 in "aaa": a free diff would say the insert was at 3
        assertEquals(List.of(1, 1, 2), List.of(MultiCaret.editSpan("aaa", "aaaa", MultiCaret.Caret.at(1))[0],
                MultiCaret.editSpan("aaa", "aaaa", MultiCaret.Caret.at(1))[1],
                MultiCaret.editSpan("aaa", "aaaa", MultiCaret.Caret.at(1))[2]));
    }

    @Test
    void mergeKeepsThePrimaryAndSwallowsCaretsInsideSelections() {
        List<MultiCaret.Caret> carets = List.of(MultiCaret.Caret.at(4), new MultiCaret.Caret(6, 2), MultiCaret.Caret.at(9));
        MultiCaret.Result merged = MultiCaret.merge(carets, 0, 10);
        assertEquals(List.of(new MultiCaret.Caret(6, 2), MultiCaret.Caret.at(9)), merged.carets());
        assertEquals(0, merged.primary(), "the primary (at 4) was folded into the selection");

        MultiCaret.Result touching = MultiCaret.merge(List.of(new MultiCaret.Caret(3, 0), new MultiCaret.Caret(6, 3)), 1, 10);
        assertEquals(2, touching.carets().size(), "touching selections stay separate");
        assertEquals(1, touching.primary());
    }

    /**
     * Thousands of random edits on two-letter text (the worst case for
     * ambiguity), checked against positions computed independently.
     */
    @Test
    void randomEditsMatchAnIndependentModel() {
        Random random = new Random(7);
        for (int round = 0; round < 4000; round++) {
            int length = random.nextInt(12);
            StringBuilder start = new StringBuilder();
            for (int i = 0; i < length; i++) {
                start.append(random.nextBoolean() ? 'a' : 'b');
            }
            TreeSet<Integer> spots = new TreeSet<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                spots.add(random.nextInt(length + 1));
            }
            List<Integer> sorted = new ArrayList<>(spots);
            List<MultiCaret.Caret> carets = new ArrayList<>();
            for (int p : sorted) {
                carets.add(MultiCaret.Caret.at(p));
            }
            FakeEditor editor = new FakeEditor(start.toString());
            String original = start.toString();

            if (random.nextBoolean()) {
                String insert = random.nextBoolean() ? "a" : (random.nextBoolean() ? "ab" : "ba");
                MultiCaret.Result result = MultiCaret.applyAtEach(editor, carets, 0, rank -> editor.insert(insert));
                StringBuilder expected = new StringBuilder(original);
                for (int k = sorted.size() - 1; k >= 0; k--) {
                    expected.insert(sorted.get(k), insert);
                }
                List<Integer> expectedCarets = new ArrayList<>();
                for (int k = 0; k < sorted.size(); k++) {
                    expectedCarets.add(sorted.get(k) + (k + 1) * insert.length());
                }
                assertEquals(expected.toString(), editor.text, "insert into " + original + " at " + sorted);
                assertEquals(expectedCarets, positions(result), "insert into " + original + " at " + sorted);
            } else {
                MultiCaret.Result result = MultiCaret.applyAtEach(editor, carets, 0, rank -> editor.backspace());
                TreeSet<Integer> deleted = new TreeSet<>();
                for (int p : sorted) {
                    if (p > 0) {
                        deleted.add(p - 1);
                    }
                }
                StringBuilder expected = new StringBuilder();
                for (int i = 0; i < original.length(); i++) {
                    if (!deleted.contains(i)) {
                        expected.append(original.charAt(i));
                    }
                }
                TreeSet<Integer> expectedCarets = new TreeSet<>();
                for (int p : sorted) {
                    expectedCarets.add(p - deleted.headSet(p).size());
                }
                assertEquals(expected.toString(), editor.text, "backspace in " + original + " at " + sorted);
                assertEquals(new ArrayList<>(expectedCarets), positions(result), "backspace in " + original + " at " + sorted);
            }
        }
    }

    /** Random non-overlapping selections, each replaced by the same text. */
    @Test
    void randomSelectionReplacementsMatchAnIndependentModel() {
        Random random = new Random(11);
        for (int round = 0; round < 4000; round++) {
            int length = 1 + random.nextInt(14);
            StringBuilder start = new StringBuilder();
            for (int i = 0; i < length; i++) {
                start.append(random.nextBoolean() ? 'a' : 'b');
            }
            String original = start.toString();
            // cut [0, length] into ordered, non-overlapping, non-touching ranges
            List<int[]> ranges = new ArrayList<>();
            int at = random.nextInt(2);
            while (at <= length && ranges.size() < 4) {
                int end = Math.min(length, at + random.nextInt(4));
                ranges.add(new int[]{at, end});
                at = end + 1 + random.nextInt(3);
            }
            List<MultiCaret.Caret> carets = new ArrayList<>();
            for (int[] r : ranges) {
                // either direction of selection
                carets.add(random.nextBoolean() ? new MultiCaret.Caret(r[1], r[0]) : new MultiCaret.Caret(r[0], r[1]));
            }
            String insert = random.nextInt(3) == 0 ? "" : (random.nextBoolean() ? "a" : "ab");
            FakeEditor editor = new FakeEditor(original);
            MultiCaret.Result result = MultiCaret.applyAtEach(editor, carets, 0, rank -> editor.insert(insert));

            StringBuilder expected = new StringBuilder();
            List<Integer> expectedCarets = new ArrayList<>();
            int from = 0;
            for (int[] r : ranges) {
                expected.append(original, from, r[0]).append(insert);
                expectedCarets.add(expected.length());
                from = r[1];
            }
            expected.append(original.substring(from));
            List<Integer> distinct = new ArrayList<>(new TreeSet<>(expectedCarets));
            String what = "replace " + ranges.stream().map(r -> r[0] + ".." + r[1]).toList() + " in " + original + " with '" + insert + "'";
            assertEquals(expected.toString(), editor.text, what);
            assertEquals(distinct, positions(result), what);
        }
    }

    /** Forward delete at random carets, checked independently. */
    @Test
    void randomForwardDeletesMatchAnIndependentModel() {
        Random random = new Random(13);
        for (int round = 0; round < 4000; round++) {
            int length = random.nextInt(12);
            StringBuilder start = new StringBuilder();
            for (int i = 0; i < length; i++) {
                start.append(random.nextBoolean() ? 'a' : 'b');
            }
            String original = start.toString();
            TreeSet<Integer> spots = new TreeSet<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                spots.add(random.nextInt(length + 1));
            }
            List<Integer> sorted = new ArrayList<>(spots);
            List<MultiCaret.Caret> carets = new ArrayList<>();
            for (int p : sorted) {
                carets.add(MultiCaret.Caret.at(p));
            }
            FakeEditor editor = new FakeEditor(original);
            MultiCaret.Result result = MultiCaret.applyAtEach(editor, carets, 0, rank -> editor.delete());

            TreeSet<Integer> deleted = new TreeSet<>();
            for (int p : sorted) {
                if (p < length) {
                    deleted.add(p);
                }
            }
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < length; i++) {
                if (!deleted.contains(i)) {
                    expected.append(original.charAt(i));
                }
            }
            TreeSet<Integer> expectedCarets = new TreeSet<>();
            for (int p : sorted) {
                expectedCarets.add(p - deleted.headSet(p).size());
            }
            assertEquals(expected.toString(), editor.text, "delete in " + original + " at " + sorted);
            assertEquals(new ArrayList<>(expectedCarets), positions(result), "delete in " + original + " at " + sorted);
        }
    }

    // ---- Alt+Up/Down line moves ----

    private static MultiCaret.Moved move(String text, boolean up, MultiCaret.Caret... carets) {
        return MultiCaret.moveLines(text, List.of(carets), 0, up);
    }

    @Test
    void aLineMovesUpAndDownWithItsCaret() {
        MultiCaret.Moved up = move("one\ntwo\nthree", true, MultiCaret.Caret.at(5)); // "t|wo" col 1
        assertEquals("two\none\nthree", up.text());
        assertEquals(MultiCaret.Caret.at(1), up.carets().get(0));
        MultiCaret.Moved down = move("one\ntwo\nthree", false, MultiCaret.Caret.at(5));
        assertEquals("one\nthree\ntwo", down.text());
        assertEquals(MultiCaret.Caret.at(11), down.carets().get(0));
    }

    @Test
    void aSelectionMovesEveryLineItTouches() {
        // selects from "ne" on line 0 into "tw" on line 1
        MultiCaret.Moved down = move("one\ntwo\nthree", false, new MultiCaret.Caret(6, 1));
        assertEquals("three\none\ntwo", down.text());
        assertEquals(new MultiCaret.Caret(12, 7), down.carets().get(0), "the selection rides along");
    }

    @Test
    void aSelectionEndingAtALineStartLeavesThatLine() {
        // "one\n" fully selected, ending at the start of "two"
        MultiCaret.Moved down = move("one\ntwo\nthree", false, new MultiCaret.Caret(4, 0));
        assertEquals("two\none\nthree", down.text());
        assertEquals(new MultiCaret.Caret(8, 4), down.carets().get(0),
                "still exactly one line selected, ending just after the moved line");
    }

    @Test
    void nothingMovesPastTheTopOrBottom() {
        assertNull(move("one\ntwo", true, MultiCaret.Caret.at(1)));
        assertNull(move("one\ntwo", false, MultiCaret.Caret.at(5)));
        assertNull(move("one\ntwo\nthree", true, MultiCaret.Caret.at(1), MultiCaret.Caret.at(9)),
                "one block at the top holds every block back");
    }

    @Test
    void severalCaretsMoveTheirOwnLinesAndTouchingBlocksMoveTogether() {
        MultiCaret.Moved apart = move("a\nb\nc\nd\ne", true, MultiCaret.Caret.at(2), MultiCaret.Caret.at(6));
        assertEquals("b\na\nd\nc\ne", apart.text());
        assertEquals(List.of(MultiCaret.Caret.at(0), MultiCaret.Caret.at(4)), apart.carets());
        MultiCaret.Moved touching = move("a\nb\nc\nd", false, MultiCaret.Caret.at(0), MultiCaret.Caret.at(2));
        assertEquals("c\na\nb\nd", touching.text());
        assertEquals(List.of(MultiCaret.Caret.at(2), MultiCaret.Caret.at(4)), touching.carets());
    }

    @Test
    void movingUpThenDownPutsEverythingBack() {
        Random random = new Random(17);
        int checked = 0;
        for (int round = 0; round < 3000; round++) {
            int lineCount = 1 + random.nextInt(6);
            StringBuilder text = new StringBuilder();
            for (int l = 0; l < lineCount; l++) {
                if (l > 0) {
                    text.append('\n');
                }
                int len = random.nextInt(4);
                for (int c = 0; c < len; c++) {
                    text.append((char) ('a' + random.nextInt(3)));
                }
            }
            String original = text.toString();
            List<MultiCaret.Caret> carets = new ArrayList<>();
            int count = 1 + random.nextInt(3);
            for (int k = 0; k < count; k++) {
                int a = random.nextInt(original.length() + 1);
                int b = random.nextBoolean() ? a : random.nextInt(original.length() + 1);
                carets.add(new MultiCaret.Caret(a, b));
            }
            MultiCaret.Moved up = MultiCaret.moveLines(original, carets, 0, true);
            if (up == null) {
                continue;
            }
            MultiCaret.Moved back = MultiCaret.moveLines(up.text(), up.carets(), 0, false);
            if (back == null) {
                continue; // the moved blocks can merge differently on the way back; nothing to compare
            }
            if (!back.text().equals(original)) {
                continue; // merged into one block on the way up, so down moves a different set
            }
            assertEquals(carets, back.carets(), "up then down in " + original.replace("\n", "|") + " " + carets);
            checked++;
        }
        org.junit.jupiter.api.Assertions.assertTrue(checked > 1000, "enough rounds compared: " + checked);
    }
}
