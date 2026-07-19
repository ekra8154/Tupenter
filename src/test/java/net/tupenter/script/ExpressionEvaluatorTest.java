package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private static String eval(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(42))).displayString();
    }

    // --- numbers (inherited behavior) ---

    @Test
    void arithmeticAndPrecedence() {
        assertEquals("14", eval("2+3*4"));
        assertEquals("20", eval("(2+3)*4"));
        assertEquals("8", eval("2(3+1)"));
        assertEquals("192", eval("3s"));
        assertEquals("8192", eval("2ss"));
        assertEquals("3", eval("int(7/2)"));
        assertEquals("0.3", eval("0.1+0.2"));
    }

    // --- strings ---

    @Test
    void stringLiterals() {
        assertEquals("hello world", eval("\"hello world\""));
        assertEquals("say \"hi\"", eval("\"say \\\"hi\\\"\""));
    }

    @Test
    void concatenation() {
        assertEquals("a1", eval("\"a\" + 1"));
        assertEquals("1a", eval("1 + \"a\""));
        assertEquals("ab", eval("\"a\" + \"b\""));
        assertEquals("x64", eval("\"x\" + 1s"));
    }

    // --- booleans and comparisons ---

    @Test
    void comparisons() {
        assertEquals("true", eval("2 > 1"));
        assertEquals("false", eval("2 < 1"));
        assertEquals("true", eval("1 <= 1"));
        assertEquals("true", eval("3 == 1+2"));
        assertEquals("true", eval("1 != 2"));
        assertEquals("true", eval("\"a\" == \"a\""));
        assertEquals("true", eval("\"a\" != \"b\""));
    }

    @Test
    void booleanOperators() {
        assertEquals("true", eval("1 > 0 && 2 > 1"));
        assertEquals("true", eval("1 > 2 || 2 > 1"));
        assertEquals("false", eval("!(2 > 1)"));
        assertEquals("true", eval("true"));
        assertEquals("false", eval("false && true"));
    }

    @Test
    void ternary() {
        assertEquals("yes", eval("3 > 2 ? \"yes\" : \"no\""));
        assertEquals("0", eval("3 < 2 ? 10 : 0"));
        assertEquals("b", eval("1 > 2 ? \"a\" : 2 > 1 ? \"b\" : \"c\""));
    }

    @Test
    void typeErrors() {
        assertThrows(ExpressionException.class, () -> eval("\"a\" < \"b\""));
        assertThrows(ExpressionException.class, () -> eval("1 == \"1\""));
        assertThrows(ExpressionException.class, () -> eval("1 && 2"));
        assertThrows(ExpressionException.class, () -> eval("!5"));
        assertThrows(ExpressionException.class, () -> eval("\"a\" * 2"));
        assertThrows(ExpressionException.class, () -> eval("5 ? 1 : 2"));
    }

    @Test
    void booleansRefuseCommandSubstitution() {
        Value bool = ExpressionEvaluator.evaluate("1 > 0", new EvalContext(new Random()));
        assertThrows(ExpressionException.class, bool::substitutionString);
    }

    // --- rand ---

    @Test
    void randStaysInInclusiveRange() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 200; i++) {
            String result = ExpressionEvaluator.evaluate("rand(1, 5)", context).displayString();
            int value = Integer.parseInt(result);
            assertTrue(value >= 1 && value <= 5, "rand out of range: " + value);
        }
    }

    @Test
    void randDegenerateAndInvalidRanges() {
        assertEquals("3", eval("rand(3, 3)"));
        assertThrows(ExpressionException.class, () -> eval("rand(5, 1)"));
        assertThrows(ExpressionException.class, () -> eval("rand(1.5, 3)"));
        assertThrows(ExpressionException.class, () -> eval("rand(1)"));
    }

    @Test
    void randComposesWithMath() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 50; i++) {
            int value = Integer.parseInt(ExpressionEvaluator.evaluate("10 + rand(1, 5)", context).displayString());
            assertTrue(value >= 11 && value <= 15);
        }
    }

    // --- tag sets ---

    private static final TagResolver STUB_TAGS = (kind, tagId) -> {
        if (tagId == null) { // whole-registry enumeration
            return kind == TagResolver.TagKind.EFFECT
                    ? java.util.List.of("minecraft:speed", "minecraft:slowness", "minecraft:levitation")
                    : java.util.List.of("minecraft:everything");
        }
        if (kind == TagResolver.TagKind.BLOCK && tagId.equals("minecraft:logs")) {
            return java.util.List.of("minecraft:oak_log", "minecraft:birch_log");
        }
        if (kind == TagResolver.TagKind.ITEM && tagId.equals("c:ores")) {
            return java.util.List.of("minecraft:iron_ore");
        }
        return java.util.List.of();
    };

    private static EvalContext tagContext() {
        return new EvalContext(new Random(7), VariableProvider.EMPTY, STUB_TAGS);
    }

    @Test
    void tagSetsResolveToListsAndLeadingHashIsOptional() {
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"#minecraft:logs\"))", tagContext()).displayString());
        // single-member set: rand() is deterministic, and the # prefix is optional
        assertEquals("minecraft:iron_ore", ExpressionEvaluator.evaluate("rand(itemset(\"c:ores\"))", tagContext()).displayString());
    }

    @Test
    void noArgSetsEnumerateTheWholeRegistry() {
        assertEquals("3", ExpressionEvaluator.evaluate("len(effectset())", tagContext()).displayString());
        assertEquals("minecraft:everything", ExpressionEvaluator.evaluate("rand(blockset())", tagContext()).displayString());
        Set<String> effects = Set.of("minecraft:speed", "minecraft:slowness", "minecraft:levitation");
        for (int i = 0; i < 20; i++) {
            assertTrue(effects.contains(
                    ExpressionEvaluator.evaluate("rand(effectset())", tagContext()).displayString()));
        }
        // still needs a live world
        assertThrows(ExpressionException.class, () -> eval("effectset()"));
    }

    @Test
    void randPicksFromLists() {
        EvalContext context = tagContext();
        Set<String> logs = Set.of("minecraft:oak_log", "minecraft:birch_log");
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            String result = ExpressionEvaluator.evaluate("rand(blockset(\"#minecraft:logs\"))", context).displayString();
            assertTrue(logs.contains(result), "unexpected member: " + result);
            seen.add(result);
        }
        assertEquals(logs, seen);
        // rand(list) works on any list, not just tag sets
        int fromRange = Integer.parseInt(ExpressionEvaluator.evaluate("rand(range(1, 5))", context).displayString());
        assertTrue(fromRange >= 1 && fromRange <= 5);
    }

    @Test
    void tagSetErrors() {
        // unknown tag
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(\"#minecraft:nope\")", tagContext()));
        // no resolver available (not in-game)
        assertThrows(ExpressionException.class, () -> eval("blockset(\"#minecraft:logs\")"));
        // not a string
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(5)", tagContext()));
        // rand of a non-list single arg still errors
        assertThrows(ExpressionException.class, () -> eval("rand(1)"));
    }

    // --- block(x, y, z) ---

    private static final BlockReader STUB_BLOCKS = (x, y, z) ->
            y >= 64 ? "minecraft:air" : (x == 1 && y == 2 && z == 3 ? "minecraft:oak_log" : "minecraft:stone");

    private static EvalContext blockContext() {
        return new EvalContext(new Random(7), VariableProvider.EMPTY, TagResolver.NONE, STUB_BLOCKS);
    }

    @Test
    void blockReadsThePositionInBothForms() {
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(1, 2, 3)", blockContext()).displayString());
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(\"1 2 3\")", blockContext()).displayString());
        // decimal coordinates floor to block positions
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(1.9, 2.5, 3.2)", blockContext()).displayString());
        // composes into conditions
        assertEquals("high", ExpressionEvaluator.evaluate(
                "block(0, 70, 0) == \"minecraft:air\" ? \"high\" : \"low\"", blockContext()).displayString());
    }

    @Test
    void blockErrors() {
        // no world / unloaded position
        assertThrows(ExpressionException.class, () -> eval("block(1, 2, 3)"));
        // wrong shapes
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(1, 2)", blockContext()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(\"1 2\")", blockContext()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(\"a b c\")", blockContext()));
    }

    // --- pick ---

    @Test
    void pickChoosesAnOption() {
        EvalContext context = new EvalContext(new Random(7));
        Set<String> options = Set.of("stick", "iron_ingot", "diamond");
        for (int i = 0; i < 50; i++) {
            String result = ExpressionEvaluator.evaluate("pick(\"stick\" | \"iron_ingot\" | \"diamond\")", context).displayString();
            assertTrue(options.contains(result), "unexpected pick: " + result);
        }
    }

    @Test
    void pickOptionsAreExpressions() {
        assertEquals("4", eval("pick(2+2 | 2*2)"));
        // nested pick evaluates instead of returning its own source text
        assertEquals("7", eval("pick(pick(7 | 7) | 7)"));
        // literal text (spaces, commas, pipes) goes in quotes
        String quoted = eval("pick(\"say hi there\" | \"say hi there\")");
        assertEquals("say hi there", quoted);
        assertEquals("a | b", eval("pick(\"a | b\" | \"a | b\")"));
    }

    @Test
    void pickSeparatorDoesNotEatBooleanOr() {
        // || binds inside an option; a single | separates options
        EvalContext context = new EvalContext(new Random(3));
        Set<String> results = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            results.add(ExpressionEvaluator.evaluate("pick(true || false | 9)", context).displayString());
        }
        assertEquals(Set.of("true", "9"), results);
    }

    @Test
    void pickRequiresAnOption() {
        assertThrows(ExpressionException.class, () -> eval("pick()"));
        assertThrows(ExpressionException.class, () -> eval("pick(1 | 2"));
    }

    // --- variables ---

    private static EvalContext contextWith(SessionVariableStore store) {
        return new EvalContext(new Random(42), store);
    }

    @Test
    void variablesResolveBareAndWrapped() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("x", Value.ofNumber(5));
        assertEquals("6", ExpressionEvaluator.evaluate("x + 1", contextWith(store)).displayString());
        assertEquals("6", ExpressionEvaluator.evaluate("$x$ + 1", contextWith(store)).displayString());
    }

    @Test
    void dottedProviderNamesResolve() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.y", Value.ofNumber(70));
        assertEquals("true", ExpressionEvaluator.evaluate("client.y > 60", contextWith(store)).displayString());
    }

    @Test
    void stringVariablesSubstituteAndCompare() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("spawn", Value.of("100 64 -200"));
        assertEquals("100 64 -200", ExpressionEvaluator.evaluate("spawn", contextWith(store)).substitutionString());
        assertEquals("true", ExpressionEvaluator.evaluate("spawn == \"100 64 -200\"", contextWith(store)).displayString());
    }

    @Test
    void unknownVariableSuggestsNearestName() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("health", Value.ofNumber(20));
        ExpressionException ex = org.junit.jupiter.api.Assertions.assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("helth", contextWith(store)));
        assertTrue(ex.getMessage().contains("did you mean 'health'"), ex.getMessage());
    }

    // --- math functions ---

    @Test
    void trigTakesDegrees() {
        assertEquals("1", eval("sin(90)"));
        assertEquals("1", eval("cos(0)"));
        assertEquals("0", eval("sin(0)"));
        double sin30 = Double.parseDouble(eval("sin(30)"));
        assertTrue(sin30 > 0.499 && sin30 < 0.501, "sin(30) ≈ 0.5, got " + sin30);
        double halfCircle = Double.parseDouble(eval("-sin(client_yaw_stub + 0)".replace("client_yaw_stub + 0", "180")));
        assertTrue(Math.abs(halfCircle) < 1e-9, "-sin(180) ≈ 0");
    }

    @Test
    void trigComposesWithVariables() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(90));
        String result = ExpressionEvaluator.evaluate("-sin(client.yaw)", contextWith(store)).displayString();
        assertEquals("-1", result);
    }

    @Test
    void sqrtAndAbs() {
        assertEquals("3", eval("sqrt(9)"));
        assertEquals("5", eval("abs(-5)"));
        assertEquals("5", eval("abs(5)"));
        assertThrows(ExpressionException.class, () -> eval("sqrt(-1)"));
    }

    @Test
    void floorCeilRound() {
        assertEquals("2", eval("floor(2.9)"));
        assertEquals("-3", eval("floor(-2.1)"));
        assertEquals("3", eval("ceil(2.1)"));
        assertEquals("-2", eval("ceil(-2.9)"));
        assertEquals("3", eval("round(2.5)"));
        assertEquals("2", eval("round(2.4)"));
        assertEquals("-2", eval("round(-2.5)"));
        assertEquals("7", eval("floor(15/2)"));
    }

    @Test
    void minMaxAndLen() {
        assertEquals("1", eval("min(3, 1, 2)"));
        assertEquals("3", eval("max(3, 1, 2)"));
        assertEquals("2.5", eval("min(2.5, 3)"));
        assertEquals("3", eval("len(range(1, 3))"));
        assertEquals("5", eval("len(\"hello\")"));
        assertThrows(ExpressionException.class, () -> eval("min()"));
        assertThrows(ExpressionException.class, () -> eval("len(5)"));
    }

    @Test
    void randfStaysInRange() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 100; i++) {
            double value = Double.parseDouble(ExpressionEvaluator.evaluate("randf(1, 2)", context).displayString());
            assertTrue(value >= 1.0 && value <= 2.0, "randf out of range: " + value);
        }
        assertThrows(ExpressionException.class, () -> eval("randf(2, 1)"));
    }

    // --- errors ---

    @Test
    void unknownFunctionAndValueErrors() {
        assertThrows(ExpressionException.class, () -> eval("foo(1)"));
        assertThrows(ExpressionException.class, () -> eval("hello"));
        assertThrows(ExpressionException.class, () -> eval("1/0"));
        assertThrows(ExpressionException.class, () -> eval("\"unterminated"));
        assertThrows(ExpressionException.class, () -> eval("1 +"));
    }
}
