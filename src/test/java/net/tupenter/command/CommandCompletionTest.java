package net.tupenter.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Finding the one command an editor's cursor is in, inside a whole script. */
class CommandCompletionTest {

    /** {start, end} of the command at the end of {@code text}, or null. */
    private static int[] atEnd(String text) {
        return CommandCompletion.commandAt(text, 0, text.length(), text.length());
    }

    private static int[] span(String text, String command) {
        int start = text.lastIndexOf(command);
        return new int[]{start, start + command.length()};
    }

    @Test
    void aPlainCommand() {
        assertArrayEquals(new int[]{0, 8}, atEnd("/time se"));
    }

    @Test
    void theCommandAfterAnAndAnd() {
        String text = "/say hi && /give @s st";
        assertArrayEquals(span(text, "/give @s st"), atEnd(text));
    }

    @Test
    void theFirstCommandWhenTheCursorIsThere() {
        String text = "/say hi && /give @s stone";
        assertArrayEquals(new int[]{0, 8}, CommandCompletion.commandAt(text, 0, text.length(), 5));
    }

    @Test
    void insideAnIfBody() {
        String text = "#if (client.health < 6) (/effect give @s rege";
        assertArrayEquals(span(text, "/effect give @s rege"), atEnd(text));
    }

    @Test
    void insideAClosedGroupWithMoreAfterIt() {
        String text = "#if (x) (/time set d) && /say done";
        int cursor = text.indexOf("set d") + "set d".length();
        int start = text.indexOf("/time");
        assertArrayEquals(new int[]{start, text.indexOf(')', start)},
                CommandCompletion.commandAt(text, 0, text.length(), cursor));
    }

    @Test
    void insideSilentWithSpacesAndAMarker() {
        String text = "#silent ( /execute as @e at @s run tp @s ^ ^ ^$speed$ fac";
        assertArrayEquals(span(text, "/execute as @e at @s run tp @s ^ ^ ^$speed$ fac"), atEnd(text));
    }

    @Test
    void nestedGroupsAndChainsInside() {
        String text = "#if (a) (#set b = 1 && #if (c) (/say x && /tp @s ~ ~";
        assertArrayEquals(span(text, "/tp @s ~ ~"), atEnd(text));
    }

    @Test
    void behindLinePrefixes() {
        String text = "#norecord #silent /give @s dia";
        assertArrayEquals(span(text, "/give @s dia"), atEnd(text));
    }

    @Test
    void notInAConditionOrChatOrADirective() {
        assertNull(atEnd("#if (client.heal"), "a condition is an expression");
        assertNull(atEnd("hello there"), "chat text");
        assertNull(atEnd("#set x = 5"), "a directive, not a command");
        assertNull(atEnd("/say hi && "), "between statements");
    }

    @Test
    void anAndAndInsideQuotesOrAGroupDoesNotSplit() {
        String text = "/say \"a && b\" and mo";
        assertArrayEquals(new int[]{0, text.length()}, atEnd(text));
    }
}
