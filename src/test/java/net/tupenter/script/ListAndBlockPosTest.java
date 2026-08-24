package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * list(...) and blockpos(...) — the pair that makes a literal list of positions
 * expressible. Before them, an arbitrary list only existed inside a #foreach
 * header's (a | b | c), and a computed position had to be spelled
 * round(component(v, "x")) three times to survive /setblock.
 */
class ListAndBlockPosTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    // ------------------------------------------------------------ list(...)

    @Test
    void listHoldsExactlyTheValuesNamed() {
        assertEquals("list(1, 2, 3)", calc("list(1, 2, 3)"));
        assertEquals("3", calc("len(list(1, 2, 3))"));
        assertEquals("list()", calc("list()"), "an empty list is a legal list, not an error");
        assertEquals("0", calc("len(list())"));
    }

    @Test
    void elementsAreExpressions() {
        assertEquals("list(4, 9)", calc("list(2 * 2, 3 * 3)"));
        assertEquals("list(\"a\", 2)", calc("list(\"a\", 2)"), "mixed types are fine — a list isn't typed");
    }

    /**
     * Flattening is the deliberate choice: a list-of-lists has no consumer (every
     * path out ends at substitutionString, which refuses a list), so nesting could
     * only produce a confusing error one step later. The payoff is concatenation.
     */
    @Test
    void nestedListsFlattenSoListAlsoConcatenates() {
        assertEquals("list(1, 2, 3, 9)", calc("list(range(1, 3), 9)"));
        assertEquals("list(1, 2, 8, 9)", calc("list(range(1, 2), range(8, 9))"), "join two lists");
        assertEquals("4", calc("len(list(range(1, 2), range(8, 9)))"), "flat, so len counts members not lists");
    }

    @Test
    void theListPlugsIntoEveryListConsumer() {
        assertEquals("20", calc("nth(list(10, 20, 30), 1)"));
        assertEquals("true", calc("contains(list(10, 20, 30), 20)"));
        assertEquals("2", calc("indexof(list(10, 20, 30), 30)"));
    }

    // -------------------------------------------------------- blockpos(...)

    @Test
    void blockposFloorsToWholeCoordinates() {
        assertEquals("10 64 -4", calc("blockpos(10.7, 64.2, -3.4)"),
                "floor, so -3.4 lands in the block at -4");
        assertEquals("1 2 3", calc("blockpos(1, 2, 3)"), "already whole — unchanged");
    }

    /**
     * The distinction that will otherwise bite silently: floor is "the block this
     * point is INSIDE" (what client.blockpos means), round is "the NEAREST block"
     * (what plotting a circle wants). They differ by half a block.
     */
    @Test
    void blockposFloorsWhereRoundWouldNotAndTheGapIsHalfABlock() {
        assertEquals("10 64 -4", calc("blockpos(10.7, 64.2, -3.4)"));
        assertEquals("11", calc("round(10.7)"), "round would have said 11, not 10");
        assertEquals("-3", calc("round(-3.4)"), "and -3, not -4");
    }

    @Test
    void theOneArgumentFormSnapsAWholeVec() {
        assertEquals("10 64 -4", calc("blockpos(vec(10.7, 64.2, -3.4))"));
        // component-by-component, the long way it replaces
        assertEquals("10", calc("floor(component(blockpos(vec(10.7, 64.2, -3.4)), \"x\"))"));
        assertEquals("-4", calc("floor(component(blockpos(vec(10.7, 64.2, -3.4)), \"z\"))"));
    }

    @Test
    void blockposIsIdempotent() {
        assertEquals(calc("blockpos(10.7, 64.2, -3.4)"),
                calc("blockpos(blockpos(10.7, 64.2, -3.4))"));
    }

    @Test
    void blockposComposesWithVectorArithmetic() {
        assertEquals("10 69 -4", calc("blockpos(vadd(vec(10.7, 64.2, -3.4), vec(0, 5, 0)))"));
    }

    // ---------------------------------------------------------- the errors

    @Test
    void wrongShapesSayWhatTheRightOnesAre() {
        String twoArgs = errorFrom("blockpos(1, 2)");
        assertTrue(twoArgs.contains("blockpos(x, y, z)"), twoArgs);
        assertTrue(twoArgs.contains("blockpos(v)"), twoArgs);

        String notAVec = errorFrom("blockpos(\"nope\")");
        assertTrue(notAVec.contains("blockpos(v)"), notAVec);
    }

    /** The pair together — the shape the whole feature exists for. */
    @Test
    void aLiteralListOfPositions() {
        assertEquals("list(\"1 2 3\", \"-4 5 6\")", calc("list(blockpos(1, 2, 3), blockpos(-4, 5, 6))"));
        assertEquals("-4 5 6", calc("nth(list(blockpos(1, 2, 3), blockpos(-4, 5, 6)), 1)"));
    }

    /**
     * The whole stack at once: a custom command with an optional typed parameter,
     * a list in a #local, a #foreach over it, and the loop variable glued to a
     * suffix. This is the shape people actually write, and every piece of it is
     * somewhere else's test — which is exactly why it deserves one of its own.
     */
    @Test
    void aCustomCommandCanLoopOverAStoredList() {
        java.util.Map<String, AliasDefinition> aliases = new java.util.LinkedHashMap<>();
        aliases.put("cutgrass", AliasDefinition.parse(
                "<r:int=15> = #local kinds = list(\"short\", \"tall\") && "
                        + "#foreach $kind$ in kinds (/fill ~$r$ ~$r$ ~$r$ ~$-r$ ~$-r$ ~$-r$ air replace $kind$_grass)"));
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliases,
                true, true, true, true, 100, 1000, new Random(42),
                new SessionVariableStore(), new SessionVariableStore());

        ScriptParser.ParseResult defaulted = ScriptParser.parse("cutgrass", options);
        assertNull(defaulted.error(), String.valueOf(defaulted.error()));
        assertEquals(java.util.List.of(
                        "fill ~15 ~15 ~15 ~-15 ~-15 ~-15 air replace short_grass",
                        "fill ~15 ~15 ~15 ~-15 ~-15 ~-15 air replace tall_grass"),
                defaulted.script().statements().stream().map(Script.SendStatement::content).toList());

        ScriptParser.ParseResult given = ScriptParser.parse("cutgrass 4", options);
        assertNull(given.error(), String.valueOf(given.error()));
        assertEquals(java.util.List.of(
                        "fill ~4 ~4 ~4 ~-4 ~-4 ~-4 air replace short_grass",
                        "fill ~4 ~4 ~4 ~-4 ~-4 ~-4 air replace tall_grass"),
                given.script().statements().stream().map(Script.SendStatement::content).toList());
    }

    /**
     * End to end, through the parser: a list of positions in a #local, looped
     * over, substituted into a command. This is the line that was unwriteable
     * before — the (a | b | c) form only parses in a #foreach HEADER, so a
     * reusable list had nowhere to live.
     */
    @Test
    void aListOfPositionsSurvivesALocalAndAForeach() {
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT,
                new java.util.LinkedHashMap<>(), true, true, true, true, 100, 1000,
                new Random(42), new SessionVariableStore(), new SessionVariableStore());
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#local spots = list(blockpos(0, 64, 0), blockpos(10.9, 64.2, -5.1)) "
                        + "&& #foreach $s$ in spots (/setblock $s$ glowstone)", options);

        assertNull(result.error(), String.valueOf(result.error()));
        assertEquals(java.util.List.of("setblock 0 64 0 glowstone", "setblock 10 64 -6 glowstone"),
                result.script().statements().stream().map(Script.SendStatement::content).toList());
    }
}
