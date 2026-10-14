package net.tupenter.compat;

import net.tupenter.script.Comments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** An edit made in the collapsed box keeps the row's line breaks and indents. */
class CollapsedTextTest {

    private static final String RAW = "#if (x) (\n  /say hi &&\n  /say there\n)";

    /** Edit the flattened text with {@code edit} and replay it onto RAW. */
    private static String replay(String raw, java.util.function.UnaryOperator<String> edit) {
        String flat = Comments.flatten(raw);
        return CollapsedText.applyEdit(raw, flat, edit.apply(flat));
    }

    @Test
    void anInsertKeepsTheFormatting() {
        assertEquals("#if (x) (\n  /say hello &&\n  /say there\n)",
                replay(RAW, flat -> flat.replace("/say hi", "/say hello")));
    }

    @Test
    void textTypedAtTheStartOfALineLandsAfterItsBreak() {
        assertEquals("#if (x) (\n  /say hi &&\n  #silent /say there\n)",
                replay(RAW, flat -> flat.replace("/say there", "#silent /say there")));
    }

    @Test
    void anAppendAtTheEnd() {
        assertEquals(RAW + " && /say done", replay(RAW, flat -> flat + " && /say done"));
    }

    @Test
    void deletingTheSpaceThatStandsForALineBreakJoinsTheLines() {
        String raw = "/say a\n/say b";
        String flat = Comments.flatten(raw); // "/say a /say b"
        int space = flat.indexOf(" /say b");
        String joined = flat.substring(0, space) + flat.substring(space + 1);
        assertEquals("/say a/say b", CollapsedText.applyEdit(raw, flat, joined));
    }

    @Test
    void anUnchangedTextIsReturnedAsIs() {
        assertEquals(RAW, CollapsedText.applyEdit(RAW, Comments.flatten(RAW), Comments.flatten(RAW)));
    }

    @Test
    void textsThatDontLineUpAreRefused() {
        assertNull(CollapsedText.applyEdit("/say a", "/say b && c", "/say bb && c"));
        assertEquals(-1, CollapsedText.rawIndex("/say a", "/say b", 2));
    }

    @Test
    void caretPositionsMapAcrossLineBreaks() {
        String raw = "/say a\n  /say b";
        String flat = Comments.flatten(raw);
        assertEquals(raw.indexOf("/say b"), CollapsedText.rawIndex(raw, flat, flat.indexOf("/say b")));
        assertEquals("/say a".length(), CollapsedText.rawIndex(raw, flat, "/say a".length()));
        assertEquals(raw.length(), CollapsedText.rawIndex(raw, flat, flat.length()));
        assertEquals(0, CollapsedText.rawIndex(raw, flat, 0));
    }
}
