package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathEvaluatorTest {

    private static String apply(String command, NumberMathMode mode) {
        return MathEvaluator.applyNumberMath(command, mode, new EvalContext(new Random(42)));
    }

    // --- legacy numeric evaluation (auto-detect grammar) ---

    @Test
    void basicArithmetic() {
        assertEquals("37", MathEvaluator.evaluateExpressionAsCommandValue("32+5"));
        assertEquals("10", MathEvaluator.evaluateExpressionAsCommandValue("2*3+4"));
        assertEquals("14", MathEvaluator.evaluateExpressionAsCommandValue("2*(3+4)"));
        assertEquals("0.75", MathEvaluator.evaluateExpressionAsCommandValue("3/4"));
    }

    @Test
    void exactRationalsAvoidFloatDrift() {
        assertEquals("0.3", MathEvaluator.evaluateExpressionAsCommandValue("0.1+0.2"));
        assertEquals("1", MathEvaluator.evaluateExpressionAsCommandValue("(1/3)*3"));
    }

    @Test
    void implicitMultiplication() {
        assertEquals("8", MathEvaluator.evaluateExpressionAsCommandValue("2(3+1)"));
        assertEquals("6", MathEvaluator.evaluateExpressionAsCommandValue("(2)(3)"));
        assertEquals("4", MathEvaluator.evaluateExpressionAsCommandValue("(1+1)2"));
    }

    @Test
    void stackSuffix() {
        assertEquals("192", MathEvaluator.evaluateExpressionAsCommandValue("3s"));
        assertEquals("192", MathEvaluator.evaluateExpressionAsCommandValue("(2+1)s"));
        assertEquals("66", MathEvaluator.evaluateExpressionAsCommandValue("2+1s"));
        assertEquals("8192", MathEvaluator.evaluateExpressionAsCommandValue("2ss"));
        assertEquals("-64", MathEvaluator.evaluateExpressionAsCommandValue("-(1)s"));
    }

    @Test
    void casts() {
        assertEquals("3", MathEvaluator.evaluateExpressionAsCommandValue("int(7/2)"));
        assertEquals("3.5", MathEvaluator.evaluateExpressionAsCommandValue("float(7/2)"));
        assertEquals("3", MathEvaluator.evaluateExpressionAsCommandValue("INT(7/2)"));
    }

    @Test
    void parenthesisErrors() {
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("(2+3"));
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("(2+3))"));
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("()"));
    }

    @Test
    void divisionByZero() {
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("1/0"));
    }

    @Test
    void unsupportedContentRejected() {
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("1+x{}"));
        assertThrows(IllegalArgumentException.class, () -> MathEvaluator.evaluateExpressionAsCommandValue("foo(3)"));
    }

    // --- explicit $...$ markers (full expression engine, hard-fail) ---

    @Test
    void explicitMarkersEvaluateAndStrip() {
        assertEquals("give @s stick 37", apply("give @s stick $32+5$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void multipleMarkerPairsEvaluateIndependently() {
        assertEquals("tp @s 10 64 20", apply("tp @s $5+5$ 64 $4*5$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void markersSupportTernariesAndStrings() {
        assertEquals("tp @s ~ ~10 ~", apply("tp @s ~ ~$3 > 2 ? 10 : 0$ ~", NumberMathMode.EXPLICIT_ONLY));
        assertEquals("say big", apply("say $2 > 1 ? \"big\" : \"small\"$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void markersSupportRandAndPick() {
        String randResult = apply("give @s stick $rand(1, 5)$", NumberMathMode.EXPLICIT_ONLY);
        int amount = Integer.parseInt(randResult.substring("give @s stick ".length()));
        assertTrue(amount >= 1 && amount <= 5);

        String pickResult = apply("summon $pick(zombie | zombie)$", NumberMathMode.EXPLICIT_ONLY);
        assertEquals("summon zombie", pickResult);
    }

    @Test
    void invalidMarkerExpressionIsAHardError() {
        assertThrows(ExpressionException.class, () -> apply("say $hello world$", NumberMathMode.EXPLICIT_ONLY));
        assertThrows(ExpressionException.class, () -> apply("say $1/0$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void booleanMarkerResultIsAHardError() {
        assertThrows(ExpressionException.class, () -> apply("say $2 > 1$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void escapedDollarsAreLiteral() {
        assertEquals("say I paid $5", apply("say I paid \\$5", NumberMathMode.EXPLICIT_ONLY));
        assertEquals("say $5 and $10", apply("say \\$5 and \\$10", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void unpairedMarkerLeftAlone() {
        assertEquals("say price is 5$", apply("say price is 5$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void explicitOnlyModeLeavesBareExpressionsAlone() {
        assertEquals("give @s stick 32+5", apply("give @s stick 32+5", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void disabledModeIsIdentity() {
        assertEquals("give @s stick $32+5$", apply("give @s stick $32+5$", NumberMathMode.DISABLED));
    }

    @Test
    void explicitMarkersWorkInsideNbtBraces() {
        assertEquals("summon zombie ~ ~ ~ {Health:40}",
                apply("summon zombie ~ ~ ~ {Health:$8*5$}", NumberMathMode.EXPLICIT_ONLY));
    }

    // --- auto-detect mode (soft-fail, numeric only) ---

    @Test
    void autoDetectSolvesBareExpressions() {
        assertEquals("give @s stick 37", apply("give @s stick 32+5", NumberMathMode.AUTO_DETECT));
        assertEquals("give @s stick 192", apply("give @s stick 3s", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectSkipsInsideNbtBraces() {
        assertEquals("summon zombie ~ ~ ~ {Health:8*5}",
                apply("summon zombie ~ ~ ~ {Health:8*5}", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectLeavesPlainNumbersAlone() {
        assertEquals("give @s stick 64", apply("give @s stick 64", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectSoftFailsOnUnparseableSpans() {
        assertEquals("give @s stick 5+", apply("give @s stick 5+", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectQuirkDottedNumbersImplicitlyMultiply() {
        // documents existing behavior: "1.2.3" parses as 1.2 * .3 via implicit
        // multiplication rather than soft-failing
        assertEquals("tp @s 0.36", apply("tp @s 1.2.3", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectHandlesFunctionCalls() {
        assertEquals("give @s stick 3", apply("give @s stick int(7/2)", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void autoDetectStaysNumericOnly() {
        // ternaries etc. are marker-only syntax; bare ones soft-fail untouched
        assertEquals("say 3 > 2 ? 10 : 0", apply("say 3 > 2 ? 10 : 0", NumberMathMode.AUTO_DETECT));
    }
}
