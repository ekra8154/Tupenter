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

    // --- pick ---

    @Test
    void pickChoosesAnOption() {
        EvalContext context = new EvalContext(new Random(7));
        Set<String> options = Set.of("stick", "iron_ingot", "diamond");
        for (int i = 0; i < 50; i++) {
            String result = ExpressionEvaluator.evaluate("pick(stick | iron_ingot | diamond)", context).displayString();
            assertTrue(options.contains(result), "unexpected pick: " + result);
        }
    }

    @Test
    void pickOptionsAreLiteralText() {
        // NBT-ish fragments with commas, brackets, braces survive untouched
        String result = eval("pick(,0,0]} | ,0,0]})");
        assertEquals(",0,0]}", result);
    }

    @Test
    void pickEscapedPipeIsLiteral() {
        assertEquals("a | b", eval("pick(a \\| b)"));
    }

    @Test
    void pickBalancedParensInsideOptions() {
        assertEquals("(a,b)", eval("pick((a,b) | (a,b))"));
    }

    @Test
    void pickRequiresAnOption() {
        assertThrows(ExpressionException.class, () -> eval("pick()"));
        assertThrows(ExpressionException.class, () -> eval("pick(a | b"));
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
