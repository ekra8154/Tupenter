package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tupenter's promise to the rest of Minecraft: a command that asks for none of
 * its features must leave the client EXACTLY as typed. The mod inspects every
 * command on the way out (auto-detect number math runs even with no $...$
 * markers), so "it does nothing" is a property that has to be defended, not
 * assumed — an over-eager auto-detect once rewrote {@code matches 1..5} into
 * {@code matches 0.5}.
 *
 * <p>The commands here are ordinary vanilla, {@code /function} and the data-pack
 * surface especially: an mcfunction's own lines run server-side and Tupenter
 * never sees them, but the {@code /function} call that starts them is typed in
 * chat and passes through here. If any of these stops coming back unchanged,
 * the mod has started interfering with vanilla, which it must never do.
 */
class VanillaPassthroughTest {

    /** Every command a data-pack author might type, none of which is Tupenter's business. */
    private static final List<String> VANILLA = List.of(
            // /function: plain, macro-argument (NBT), with-clause, tag, nested in execute
            "function mypack:setup",
            "function mypack:stage_2",
            "function pack:tier3",
            "function mypack:generate {count:5,radius:10}",
            "function mypack:dmg {amount:2.5}",
            "function mypack:loop with storage mypack:data args",
            "function mypack:math {multiplier:64*5}",       // math-looking, but it's NBT data
            "function #mypack:tagged",
            "execute if score @s x matches 1..5 run function mypack:win",
            "execute as @e[distance=..10] run function mypack:tick",
            // the range/selector syntax auto-detect must never touch
            "execute if score @s n matches 3.. run say ok",
            "scoreboard players set @s n 1..5",
            // NBT and component data that contains operators
            "data modify storage m:x v set value {a:1,b:2}",
            "give @s stick[custom_data={id:1}]",
            "tp @s ~ ~1 ~",
            "tp @s 82 -2 0",                                 // a coordinate pair, not 82 minus 2
            "setblock ~ ~-1 ~ minecraft:stone",
            // ordinary commands with nothing math-shaped at all
            "say hello world",
            "gamemode creative",
            "time set day");

    private static ScriptParser.Options options(NumberMathMode mode) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, mode, Map.of(), true, true, true, true,
                100, 1000, new Random(1), store, store);
    }

    /**
     * In the default mode (auto-detect on), every one of these is left alone.
     * "Changed" is the parser's own word for "I rewrote this line", so asserting
     * it's unchanged is asserting the server sees exactly what was typed.
     */
    @Test
    void vanillaCommandsPassThroughUntouchedInAutoDetect() {
        for (String command : VANILLA) {
            ScriptParser.ParseResult result = ScriptParser.parse(command, options(NumberMathMode.AUTO_DETECT));
            assertFalse(result.changed(), "auto-detect rewrote a vanilla command: '" + command
                    + "' -> " + (result.changed() ? result.script().statements().get(0).content() : ""));
        }
    }

    @Test
    void vanillaCommandsPassThroughUntouchedInExplicitAndDisabled() {
        for (NumberMathMode mode : new NumberMathMode[]{NumberMathMode.EXPLICIT_ONLY, NumberMathMode.DISABLED}) {
            for (String command : VANILLA) {
                ScriptParser.ParseResult result = ScriptParser.parse(command, options(mode));
                assertFalse(result.changed(), mode + " rewrote a vanilla command: '" + command + "'");
            }
        }
    }

    /**
     * The seam is the marker. The SAME /function call gains Tupenter behaviour
     * only once you put a $...$ in it on purpose — proof that passthrough is
     * about the absence of markers, not about /function being special-cased.
     */
    @Test
    void aMarkerIsWhatOptsACommandIn() {
        ScriptParser.ParseResult plain = ScriptParser.parse(
                "function mypack:go {tier:3}", options(NumberMathMode.AUTO_DETECT));
        assertFalse(plain.changed(), "no marker, no change");

        SessionVariableStore store = new SessionVariableStore();
        store.set("t", Value.ofNumber(3));
        ScriptParser.Options withVar = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(),
                true, true, true, true, 100, 1000, new Random(1), store, store);
        ScriptParser.ParseResult marked = ScriptParser.parse("function mypack:go {tier:$t$}", withVar);
        assertEquals("function mypack:go {tier:3}", marked.script().statements().get(0).content(),
                "a $...$ marker is evaluated — this is the user explicitly asking for it");
    }
}
