package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptNameTest {

    @Test
    void aLeadingIdentifierBeforeEqualsIsTheName() {
        assertEquals("restock", ScriptName.name("restock = /clear && #wait 1s"));
        assertEquals("/clear && #wait 1s", ScriptName.body("restock = /clear && #wait 1s"));
    }

    @Test
    void noEqualsMeansAllBody() {
        assertEquals("", ScriptName.name("/give @s stick"));
        assertEquals("/give @s stick", ScriptName.body("/give @s stick"));
    }

    @Test
    void aCommandBodyContainingEqualsIsNotAName() {
        // the head "/setblock ~ ~ ~ minecraft:note_block[note" isn't an identifier
        String text = "/setblock ~ ~ ~ minecraft:note_block[note=5]";
        assertEquals("", ScriptName.name(text));
        assertEquals(text, ScriptName.body(text));
    }

    @Test
    void aDirectiveBodyWithAnInnerEqualsStaysUnnamed() {
        // first '=' is in "$x$ = 5"; head "#set $x$" is not an identifier
        String text = "#set $x$ = 5";
        assertEquals("", ScriptName.name(text));
        assertEquals(text, ScriptName.body(text));
    }

    @Test
    void aNamedBodyMayItselfContainEquals() {
        assertEquals("tally", ScriptName.name("tally = #set $n$ = $n$ + 1"));
        assertEquals("#set $n$ = $n$ + 1", ScriptName.body("tally = #set $n$ = $n$ + 1"));
    }

    @Test
    void whitespaceAroundTheEqualsIsOptional() {
        assertEquals("go", ScriptName.name("go=/tp @s ~ ~10 ~"));
        assertEquals("/tp @s ~ ~10 ~", ScriptName.body("go=/tp @s ~ ~10 ~"));
    }

    @Test
    void namesAllowUnderscoresAndDigitsButNotLeadingDigit() {
        assertEquals("night_2", ScriptName.name("night_2 = /time set night"));
        assertEquals("", ScriptName.name("2fast = /effect give @s speed"), "a leading digit isn't a valid name");
    }
}
