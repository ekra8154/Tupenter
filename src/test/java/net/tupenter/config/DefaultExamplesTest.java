package net.tupenter.config;

import net.tupenter.command.CommandAliasManager;
import net.tupenter.script.AliasDefinition;
import net.tupenter.script.BlockReader;
import net.tupenter.script.BuiltinFunctions;
import net.tupenter.script.FunctionResolver;
import net.tupenter.script.NumberMathMode;
import net.tupenter.script.ScriptName;
import net.tupenter.script.ScriptParser;
import net.tupenter.script.SessionVariableStore;
import net.tupenter.script.TagResolver;
import net.tupenter.script.VariableProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped examples are a promise: a fresh install runs them the moment a
 * player flips one on, so a broken seed is a broken first impression. This pins
 * that every seeded command and script actually parses, that the two custom
 * commands don't collide with a vanilla or built-in name, and that every script
 * ships DISARMED. The complex bodies (the tunnel, the despawn timer) are proven
 * out in their own tests; here the whole shipped set is checked as a unit.
 */
class DefaultExamplesTest {

    private TupenterConfig previous;

    @BeforeEach
    void isolate(@TempDir Path directory) {
        previous = ConfigTestSupport.isolate(directory);
    }

    @AfterEach
    void restore() {
        ConfigTestSupport.restore(previous);
    }

    /** A permissive, world-free parse context: enough to check STRUCTURE (parens,
     *  directives, markers), lazily so variable/function values aren't needed. */
    private static ScriptParser.Options parseOptions() {
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(),
                true, true, true, true, 1000, 1000, new Random(1),
                VariableProvider.EMPTY, new SessionVariableStore(),
                TagResolver.NONE, BlockReader.NONE, FunctionResolver.NONE, true);
    }

    @Test
    void everySeededScriptParsesWhenArmed() {
        for (TupenterConfig.GlobalScript script : DefaultExamples.globalScripts()) {
            // exactly what TickScriptRunner does: collapse to one line, strip the
            // name, wrap in the per-tick loop, parse
            String collapsed = script.text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
            String body = ScriptName.body(collapsed);
            assertTrue(!body.isBlank(), script.text + " — empty body");
            ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(
                    "#while (1 > 0) (" + body + " && #wait 1t)", body, parseOptions());
            assertNull(result.error(), ScriptName.name(collapsed) + " failed to parse: " + result.error());
        }
    }

    @Test
    void everySeededScriptShipsWithAName() {
        for (TupenterConfig.GlobalScript script : DefaultExamples.globalScripts()) {
            String collapsed = script.text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
            assertTrue(!ScriptName.name(collapsed).isBlank(), "a seeded script has no name: " + script.text);
        }
    }

    @Test
    void bothSeededCommandsParseWithTheirExpectedSignatures() {
        TupenterConfig.INSTANCE.aliases = new java.util.ArrayList<>(DefaultExamples.aliases());
        Map<String, AliasDefinition> parsed = CommandAliasManager.getAliasMap();

        assertTrue(parsed.containsKey("blink"), "blink parsed: " + parsed.keySet());
        assertEquals(1, parsed.get("blink").params().size(), "blink takes one optional distance");
        assertTrue(parsed.containsKey("ironkit"), "ironkit parsed: " + parsed.keySet());
        assertEquals(0, parsed.get("ironkit").params().size(), "ironkit takes no params");
    }

    @Test
    void theCustomCommandNamesCollideWithNothing() {
        for (String name : new String[]{"blink", "ironkit"}) {
            assertNull(CommandAliasManager.vanillaShadowWarning(name),
                    name + " must not shadow a vanilla command");
            assertTrue(!CommandAliasManager.MOD_COMMANDS.contains(name),
                    name + " must not collide with a Tupenter command");
            assertTrue(!BuiltinFunctions.NAMES.contains(name),
                    name + " must not collide with a built-in function");
        }
    }

    @Test
    void everySeededScriptIsDisarmedEverywhere() {
        DefaultExamples.seed(TupenterConfig.INSTANCE);
        assertEquals(4, TupenterConfig.INSTANCE.globalScripts.size(), "all four scripts seeded");
        assertTrue(TupenterConfig.INSTANCE.worldScripts.isEmpty(), "no world arms anything");
        assertTrue(TupenterConfig.INSTANCE.armedScriptLines("world:anywhere").isEmpty(),
                "nothing runs until the player enables it");
    }

    @Test
    void seedOnlyFillsEmptyListsSoItNeverOverwritesTheUser() {
        TupenterConfig config = new TupenterConfig();
        config.aliases = new java.util.ArrayList<>(java.util.List.of("mine \"kept\" = /say hi"));
        // globalScripts starts empty
        DefaultExamples.seed(config);

        assertEquals(java.util.List.of("mine \"kept\" = /say hi"), config.aliases,
                "the user's own command is left alone");
        assertEquals(4, config.globalScripts.size(), "the empty script list still gets seeded");
    }
}
