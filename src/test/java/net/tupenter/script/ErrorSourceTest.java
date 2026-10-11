package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Errors carry where they came from, so chat can offer [edit command], and
 * the tick loop keeps a script's leading #silent working.
 */
class ErrorSourceTest {

    private static ScriptParser.Options options(Map<String, AliasDefinition> aliases, boolean lazy) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliases, true, true, true, true,
                100, 1000, new Random(1), store, store).withLazyExecution(lazy);
    }

    private static final Map<String, AliasDefinition> ALIASES = Map.of(
            "broken", AliasDefinition.parse("= /say hi && #if (1 +) (/say no)"),
            "outer", AliasDefinition.parse("= /say outer && /broken"),
            "fine", AliasDefinition.parse("= /say fine"));

    // A line reaches the parser without its leading slash, as the chat hook passes it.
    @Test
    void anErrorInACustomCommandsBodyNamesThatCommand() {
        ScriptParser.ParseResult result = ScriptParser.parse("broken", options(ALIASES, false));
        assertNotNull(result.error());
        assertEquals("broken", result.customCommand());
    }

    @Test
    void theInnermostCommandIsTheOneNamed() {
        ScriptParser.ParseResult result = ScriptParser.parse("outer", options(ALIASES, false));
        assertNotNull(result.error());
        assertEquals("broken", result.customCommand());
    }

    @Test
    void anErrorOutsideAnyCommandNamesNone() {
        ScriptParser.ParseResult result = ScriptParser.parse("/fine && #if (1 +) (/say no)", options(ALIASES, false));
        assertNotNull(result.error());
        assertNull(result.customCommand());
    }

    @Test
    void aLazyRunCarriesTheCommandIntoTheRuntimeError() {
        ScriptParser.ParseResult result = ScriptParser.parse("#wait 1t && /broken", options(ALIASES, true));
        assertNull(result.error(), "parses lazily");
        Script.RuntimeError error = assertThrows(Script.RuntimeError.class, () -> {
            while (result.script().source().next() != null) {
                // pull until it fails
            }
        });
        assertEquals("broken", error.customCommand());
    }

    @Test
    void aLeadingSilentIsHoistedInFrontOfTheTickLoop() {
        assertEquals("#silent #while (1 > 0) (/setblock ~ ~-1 ~ stone && #wait 1t)",
                ScriptParser.tickLoopLine("#silent /setblock ~ ~-1 ~ stone"));
        assertEquals("#silent #norecord #while (1 > 0) (/say hi && #wait 1t)",
                ScriptParser.tickLoopLine("#s #nr /say hi"));
        // the group form is a statement and stays inside
        assertEquals("#while (1 > 0) (#silent (/say hi) && /say there && #wait 1t)",
                ScriptParser.tickLoopLine("#silent (/say hi) && /say there"));
    }

    @Test
    void aTickScriptStartingWithSilentParses() {
        String body = "#silent /setblock ~ ~-1 ~ stone";
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(
                ScriptParser.tickLoopLine(body), body, options(Map.of(), true));
        assertNull(result.error(), "parsed: " + result.error());
        Script.SendStatement first = result.script().source().next();
        assertNotNull(first);
        org.junit.jupiter.api.Assertions.assertTrue(first.content().contains("setblock ~ ~-1 ~ stone"), first.content());
        result.script().source().close();
    }
}
