package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** split(text[, separator]) and the &lt;name:list&gt; custom-command parameter. */
class ListParamAndSplitTest {

    private static ScriptParser.Options options(Map<String, String> raw) {
        Map<String, AliasDefinition> aliases = new LinkedHashMap<>();
        raw.forEach((name, body) -> aliases.put(name, AliasDefinition.parse(body)));
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliases, true, true, true, true,
                100, 1000, new Random(1), store, store);
    }

    /** What a line sends; fails the test on a parse error. Lines arrive without their slash. */
    private static List<String> sends(String line, Map<String, String> aliases) {
        ScriptParser.ParseResult result = ScriptParser.parse(line, options(aliases));
        assertNull(result.error(), "parsed: " + result.error());
        assertNotNull(result.script(), "the line was a script");
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    private static String error(String line, Map<String, String> aliases) {
        ScriptParser.ParseResult result = ScriptParser.parse(line, options(aliases));
        assertNotNull(result.error(), "expected an error for: " + line);
        return result.error();
    }

    private static Value eval(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)));
    }

    // ---- split ----

    @Test
    void splitWithoutASeparatorSplitsOnRunsOfSpaces() {
        assertEquals(List.of("oak", "birch", "spruce"), items(eval("split(\"  oak birch   spruce \")")));
    }

    @Test
    void splitOnASeparatorTrimsEachPieceAndDropsEmptyOnes() {
        assertEquals(List.of("pink_wool_slab ^spruce_slab", "yellow_wool_slab ^stone_brick_slab"),
                items(eval("split(\"pink_wool_slab ^spruce_slab | yellow_wool_slab ^stone_brick_slab |\", \"|\")")));
    }

    @Test
    void theSeparatorIsPlainTextNotAPattern() {
        assertEquals(List.of("a", "b"), items(eval("split(\"a.b\", \".\")")));
    }

    @Test
    void anEmptySeparatorIsRejected() {
        assertThrows(ExpressionException.class, () -> eval("split(\"abc\", \"\")"));
    }

    @Test
    void splitFeedsForeachFromATextParam() {
        Map<String, String> aliases = Map.of("swaps",
                "<rad:int=10> <pairs:text> = #foreach $p$ in split(pairs, \"|\") (/replacenear $rad$ $p$)");
        assertEquals(List.of("replacenear 100 stone ^dirt", "replacenear 100 sand ^gravel"),
                stripSlashes(sends("swaps 100 stone ^dirt | sand ^gravel", aliases)));
    }

    // ---- <name:list> ----

    private static final Map<String, String> REPLACE = Map.of("replacenearmult",
            "<rad:int=10> <pairs:list> = #foreach $p$ in pairs (/replacenear $rad$ $p$)");

    @Test
    void aListParamTakesComputedItems() {
        assertEquals(List.of("replacenear 100 pink_wool_slab ^spruce_slab", "replacenear 100 yellow_wool_slab ^stone_brick_slab"),
                stripSlashes(sends("replacenearmult 100 list(\"pink_wool_slab ^spruce_slab\", \"yellow_wool_slab ^stone_brick_slab\")",
                        REPLACE)));
    }

    @Test
    void aListParamTakesPipeItemsExactlyAsTyped() {
        assertEquals(List.of("replacenear 5 stone ^dirt", "replacenear 5 sand ^gravel"),
                stripSlashes(sends("replacenearmult 5 list(stone ^dirt | sand ^gravel)", REPLACE)));
    }

    @Test
    void aListParamCanSitMidCommand() {
        Map<String, String> aliases = Map.of("kit", "<items:list> <n:int> = #foreach $i$ in items (/give @s $i$ $n$)");
        assertEquals(List.of("give @s oak_log 3", "give @s iron_ingot 3"),
                stripSlashes(sends("kit list(oak_log | iron_ingot) 3", aliases)));
    }

    @Test
    void anyListExpressionWorks() {
        Map<String, String> aliases = Map.of("count", "<ns:list> = #foreach $n$ in ns (/say $n$)");
        assertEquals(List.of("say 1", "say 2", "say 3"), stripSlashes(sends("count range(1, 3)", aliases)));
    }

    @Test
    void somethingThatIsNotAListIsRejected() {
        String message = error("replacenearmult 5 stone", REPLACE);
        assertTrue(message.contains("must be a list"), message);
    }

    @Test
    void anOptionalListFallsBackToItsDefault() {
        Map<String, String> aliases = Map.of("hi", "<who:list=list(alex | sam)> = #foreach $w$ in who (/say hi $w$)");
        assertEquals(List.of("say hi alex", "say hi sam"), stripSlashes(sends("hi", aliases)));
    }

    @Test
    void theListArgumentEndsAtItsClosingParenthesis() {
        assertEquals("list(a b | c)".length(), ScriptParser.listArgumentLength("list(a b | c) 5"));
        assertEquals("list(\"a)\", b)".length(), ScriptParser.listArgumentLength("list(\"a)\", b) rest"));
        assertEquals("range(1, max(2, 3))".length(), ScriptParser.listArgumentLength("range(1, max(2, 3)) x"));
        assertEquals("pairs".length(), ScriptParser.listArgumentLength("pairs 5"));
    }

    @Test
    void aTypedVariableCanBeAList() {
        assertEquals(List.of("say a", "say b"),
                stripSlashes(sends("#local xs:list = list(a | b) && #foreach $x$ in xs (/say $x$)", Map.of())));
        String message = error("#local xs:list = 5 && /say hi", Map.of());
        assertTrue(message.contains("not a list"), message);
    }

    // ---- bare words in comma lists ----

    @Test
    void bareWordsInACommaListAreTheirText() {
        assertEquals(List.of("short_", "tall_", "dry_", "sea_"), items(eval("list(short_,tall_,dry_,sea_)")));
        assertEquals(List.of("stone", "minecraft:dirt"), items(eval("list(stone, minecraft:dirt)")));
    }

    @Test
    void aVariableStillWinsOverItsName() {
        assertEquals(List.of("say 5", "say dirt"),
                stripSlashes(sends("#local stone = 5 && #foreach $b$ in list(stone, dirt) (/say $b$)", Map.of())));
    }

    @Test
    void expressionsStillCompute() {
        assertEquals(List.of("6", "7"), items(eval("list(2*3, 7)")));
        assertEquals(List.of("6", "7"), items(eval("list(2 * 3, 7)")));
    }

    @Test
    void numbersBooleansAndDottedNamesAreNotTakenAsText() {
        Value value = eval("list(3s, true)");
        List<Value> values = ((Value.ListValue) value).values();
        assertTrue(values.get(0) instanceof Value.NumberValue, "3s is three stacks: " + values.get(0));
        assertTrue(values.get(1) instanceof Value.BoolValue, "true is a boolean: " + values.get(1));
        // a dotted name can only be a variable read, so a typo stays an error
        assertThrows(IllegalArgumentException.class, () -> eval("list(client.poss.y, 1)"));
    }

    @Test
    void theFallbackIsOnlyInsideList() {
        assertThrows(IllegalArgumentException.class, () -> eval("max(stone, 1)"));
    }

    private static List<String> items(Value value) {
        assertTrue(value instanceof Value.ListValue, "a list: " + value);
        return ((Value.ListValue) value).values().stream().map(Value::displayString).toList();
    }

    private static List<String> stripSlashes(List<String> commands) {
        return commands.stream().map(c -> c.startsWith("/") ? c.substring(1) : c).toList();
    }
}
