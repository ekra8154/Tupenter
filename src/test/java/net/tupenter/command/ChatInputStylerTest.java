package net.tupenter.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat-bar highlighter, which turns out to load fine under JUnit — so the
 * one piece of it with real logic rather than colour choices gets checked here.
 *
 * <p>That piece is the list-pipe overlay. It runs last, over the whole line, and
 * has to agree with the evaluator about which pipes are SEPARATORS: a pipe
 * inside quotes or a marker is content, {@code ||} is boolean or, and a
 * {@code list(} in plain chat is prose rather than code.
 */
class ChatInputStylerTest {

    private static final TextColor GOLD = TextColor.fromLegacyFormat(ChatFormatting.GOLD);

    /** Is the character at {@code index} painted as a separator? */
    private static boolean gold(String line, int index) {
        Style[] styles = ChatInputStyler.stylesFor(line);
        return GOLD.equals(styles[index].getColor());
    }

    private static int pipeAt(String line, int occurrence) {
        int at = -1;
        for (int n = 0; n <= occurrence; n++) {
            at = line.indexOf('|', at + 1);
        }
        assertTrue(at >= 0, "no pipe #" + occurrence + " in: " + line);
        return at;
    }

    @Test
    void separatorPipesAreGoldInADirective() {
        String line = "#local g = list(a | b | c)";
        assertTrue(gold(line, pipeAt(line, 0)), line);
        assertTrue(gold(line, pipeAt(line, 1)), line);
    }

    /**
     * Bodies here are directives rather than commands throughout: a /command
     * statement sends the styler down Brigadier's path, which needs a live
     * Minecraft. The overlay itself is indifferent to which it is.
     */
    @Test
    void andInsideAForeachHeader() {
        String line = "#foreach $x$ in list(short | tall) (#set y = 1)";
        assertTrue(gold(line, pipeAt(line, 0)), line);
    }

    @Test
    void andInsideAMarker() {
        String line = "#local h = $nth(list(a | b), 0)$";
        assertTrue(gold(line, pipeAt(line, 0)), line);
    }

    /** Plain chat is prose — "list(a | b)" there is text someone typed, not code. */
    @Test
    void notInPlainChat() {
        String line = "say list(a | b)";
        assertFalse(gold(line, pipeAt(line, 0)), line);
    }

    @Test
    void booleanOrIsNotASeparator() {
        String line = "#if (a || b) (#set x = 1)";
        assertFalse(gold(line, pipeAt(line, 0)), line);
        assertFalse(gold(line, pipeAt(line, 1)), line);
    }

    /**
     * The quote and escape rules the splitter uses, mirrored — a pipe the
     * evaluator treats as content must not be painted as if it split something.
     */
    @Test
    void contentPipesAreNotPainted() {
        String quoted = "#local g = list(\"a|b\" | c)";
        assertFalse(gold(quoted, pipeAt(quoted, 0)), "inside quotes: content");
        assertTrue(gold(quoted, pipeAt(quoted, 1)), "outside: the real separator");

        String escaped = "#local g = list(a\\|b | c)";
        assertFalse(gold(escaped, pipeAt(escaped, 0)), "escaped: content");
        assertTrue(gold(escaped, pipeAt(escaped, 1)), "the real separator");

        String comma = "#local g = list(\"a|b\", \"c\")";
        assertFalse(gold(comma, pipeAt(comma, 0)), "a comma list has no separator pipe at all");
    }

    /** A name that merely ends in "list" is not a list call. */
    @Test
    void onlyAWholeWordListCounts() {
        String line = "#local g = mylist(a | b)";
        assertFalse(gold(line, pipeAt(line, 0)), line);
    }

    @Test
    void theStyleArrayCoversTheWholeLine() {
        String line = "#local g = list(a | b)";
        assertEquals(line.length(), ChatInputStyler.stylesFor(line).length);
    }
}
