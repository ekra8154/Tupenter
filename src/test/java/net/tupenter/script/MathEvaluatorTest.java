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

    /**
     * Auto-detect rewrites bare math inside an ordinary command, with no $...$
     * asked for — which means it has to be RIGHT about what is and isn't math.
     * Every miss here is a command silently rewritten into something the user
     * didn't type, so the rule is: when in doubt, leave it exactly alone.
     */
    @Test
    void autoDetectRewritesOnlyWhatIsUnambiguouslyMath() {
        assertEquals("/give @s stick 320", apply("/give @s stick 64*5", NumberMathMode.AUTO_DETECT));
        assertEquals("/give @s stick 320", apply("/give @s stick 64 * 5", NumberMathMode.AUTO_DETECT));

        // a coordinate PAIR is not a subtraction: "space before, none after"
        // is how a negative coordinate is written, so the span bails out
        assertEquals("/tp @s 82 -2 0", apply("/tp @s 82 -2 0", NumberMathMode.AUTO_DETECT));
        // ...and a run of space-separated numbers stays a run even with
        // symmetric spacing, because "82 - 2 0" is still three arguments
        assertEquals("/tp @s 82 - 2 0", apply("/tp @s 82 - 2 0", NumberMathMode.AUTO_DETECT));
        // adjacency is what makes it unambiguous math
        assertEquals("/give @s stick 80", apply("/give @s stick 82-2", NumberMathMode.AUTO_DETECT));
    }

    /** Anything that isn't purely numeric is left byte-for-byte alone. */
    @Test
    void autoDetectLeavesNonMathUntouched() {
        for (String command : new String[]{
                "/give @s minecraft:stick",
                "/say hello world",
                "/tp @s ~ ~1 ~",
                "/give @s stick{display:{Name:'x'}}",       // NBT braces are skipped wholesale
                "/give @s stick[custom_data={a:1}]",
                "/say 2 + ",                                  // incomplete
                "/say a-b",                                   // not numbers
                "/execute if score @s x matches 1..5 run say hi",
                // UUIDs hold digit-hyphen-digit runs that look like subtraction
                // (25-4, 164-82916); rewriting them pointed at a different entity
                "/data merge entity 1e4b5647-bd25-4ff4-b164-82916aa0bb33 {NoGravity:true}",
                "/attribute 12345678-1234-5678-9abc-def012345678 minecraft:scale base set 2",
                "/say v1-2 and x3*4"}) {
            assertEquals(command, apply(command, NumberMathMode.AUTO_DETECT), command);
        }
    }

    /** A chat message is prose: dates, scores, "3s" and versions mean what was typed. */
    @Test
    void autoDetectLeavesMessagesAlone() {
        for (String command : new String[]{
                "/say the score is 10-2",
                "/say see you at 2026-10-13",
                "/say I waited 3s for that",
                "/say ratio is 16/9",
                "/me scored 2+2",
                "/msg Steve the score is 10-2",
                "/tell Steve 5*5",
                "/w Steve 5*5",
                "/teammsg 3-1 us",
                "/minecraft:say 10-2",
                "/execute as @a run say 10-2"}) {
            assertEquals(command, apply(command, NumberMathMode.AUTO_DETECT), command);
        }
        // ...but an explicit $...$ in a message is still asked for, so it still runs
        assertEquals("/say the score is 8", apply("/say the score is $10-2$", NumberMathMode.AUTO_DETECT));
        // and a non-message command after /execute keeps its math
        assertEquals("/execute as @a run give @s stick 320",
                apply("/execute as @a run give @s stick 64*5", NumberMathMode.AUTO_DETECT));
    }

    /** Quoted text is a name or a message, never math to rewrite. */
    @Test
    void autoDetectLeavesQuotedTextAlone() {
        for (String command : new String[]{
                "/tellraw @a \"Wave 1-2\"",
                "/title @a title \"Round 3-1\"",
                "/bossbar add test \"Boss 1-2\"",
                "/give @s stick[custom_name='Stick 1-2']",
                "/tellraw @a \"say \\\"1-2\\\" twice\"",
                "/give @s stick 'unclosed 1-2"}) {
            assertEquals(command, apply(command, NumberMathMode.AUTO_DETECT), command);
        }
        // math outside the quotes still works
        assertEquals("/give @s stick[custom_name='x'] 320",
                apply("/give @s stick[custom_name='x'] 64*5", NumberMathMode.AUTO_DETECT));
    }

    /** Nothing to compute means nothing to rewrite. */
    @Test
    void autoDetectOnlyRewritesRealMath() {
        for (String command : new String[]{
                "/attribute @s minecraft:scale base set 1.50",
                "/say 3.0",
                "/give @s stick (1)",
                "/data modify storage x:y version set value 1.21.5",
                "/scoreboard players add @s x -5"}) {
            assertEquals(command, apply(command, NumberMathMode.AUTO_DETECT), command);
        }
    }

    /** A UUID is an id even when every group is digits. */
    @Test
    void autoDetectLeavesAllDigitUuidsAlone() {
        assertEquals("/kill 00000000-0000-0000-0000-000000000001",
                apply("/kill 00000000-0000-0000-0000-000000000001", NumberMathMode.AUTO_DETECT));
        assertEquals("/data merge entity 12345678-1234-5678-1234-567812345678 {NoGravity:true}",
                apply("/data merge entity 12345678-1234-5678-1234-567812345678 {NoGravity:true}", NumberMathMode.AUTO_DETECT));
    }

    /** int(...)/float(...) are the two function forms auto-detect will start a span on. */
    @Test
    void autoDetectHandlesTheTwoConversionFunctions() {
        assertEquals("/give @s stick 3", apply("/give @s stick int(7/2)", NumberMathMode.AUTO_DETECT));
        assertEquals("/give @s stick 3.5", apply("/give @s stick float(7/2)", NumberMathMode.AUTO_DETECT));
        assertEquals("/give @s stick 3", apply("/give @s stick INT(7/2)", NumberMathMode.AUTO_DETECT));
        // a bare name that ISN'T followed by ( is not a function call, so no span starts
        assertEquals("/give @s intangible", apply("/give @s intangible", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void theStackSuffixWorksInBareMathToo() {
        assertEquals("/give @s stick 192", apply("/give @s stick 3s", NumberMathMode.AUTO_DETECT));
        assertEquals("/give @s stick 96", apply("/give @s stick 1.5s", NumberMathMode.AUTO_DETECT));
    }

    @Test
    void disabledModeChangesNothingAtAll() {
        assertEquals("/give @s stick 64*5", apply("/give @s stick 64*5", NumberMathMode.DISABLED));
    }

    /** The function grammar is tiny, and says so rather than guessing. */
    @Test
    void theBareMathFunctionsRefuseWhatTheyDoNotKnow() {
        assertTrue(assertThrows(ExpressionException.class,
                () -> MathEvaluator.evaluateExpressionAsCommandValue("sqrt(4)"))
                .getMessage().contains("sqrt"));
        assertTrue(assertThrows(ExpressionException.class,
                () -> MathEvaluator.evaluateExpressionAsCommandValue("int 4"))
                .getMessage().contains("'('"));
        assertTrue(assertThrows(ExpressionException.class,
                () -> MathEvaluator.evaluateExpressionAsCommandValue("int(4"))
                .getMessage().contains("Missing closing parenthesis"));
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
    void autoDetectNeverEatsCoordinateRuns() {
        // the /fill regression: whitespace-separated numbers are arguments,
        // not implicit multiplication, and "82 -2" is a pair, not 80
        assertEquals("fill 495 72 -15 467 82 -2 minecraft:ice",
                apply("fill 495 72 -15 467 82 -2 minecraft:ice", NumberMathMode.AUTO_DETECT));
        assertEquals("tp 100 64 -200", apply("tp 100 64 -200", NumberMathMode.AUTO_DETECT));
        assertEquals("setblock 467 81 -2 minecraft:stone",
                apply("setblock 467 81 -2 minecraft:stone", NumberMathMode.AUTO_DETECT));
        // real math still auto-detects: adjacency or symmetric spacing
        assertEquals("give @s stick 320", apply("give @s stick 64*5", NumberMathMode.AUTO_DETECT));
        assertEquals("give @s stick 320", apply("give @s stick 64 * 5", NumberMathMode.AUTO_DETECT));
        assertEquals("give @s stick 69", apply("give @s stick 64+5", NumberMathMode.AUTO_DETECT));
        assertEquals("give @s stick 69", apply("give @s stick 64 + 5", NumberMathMode.AUTO_DETECT));
        assertEquals("give @s stick 8", apply("give @s stick 2(3+1)", NumberMathMode.AUTO_DETECT));
        // markers are explicit code and keep full math semantics
        assertEquals("say 80", apply("say $82 - 2$", NumberMathMode.AUTO_DETECT));
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

        String pickResult = apply("summon $pick(\"zombie\", \"zombie\")$", NumberMathMode.EXPLICIT_ONLY);
        assertEquals("summon zombie", pickResult);
    }

    @Test
    void markerExtendsPastExplicitlyWrappedInnerExpression() {
        // the inner $...$ is explicit wrapping (grouping), not a marker
        // boundary — the scan extends because "min(" isn't balanced
        assertEquals("give @s stick 3", apply("give @s stick $min($1+2$, 5)$", NumberMathMode.EXPLICIT_ONLY));
        // balanced content still closes at the FIRST $ — two independent markers
        assertEquals("tp @s 10 64 20", apply("tp @s $5+5$ 64 $4*5$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void markerExtendsPastDollarInsideAQuotedString() {
        // the first candidate cuts mid-string, so the scan extends and the
        // $ inside the quotes is just a character
        assertEquals("say a$b", apply("say $\"a$b\"$", NumberMathMode.EXPLICIT_ONLY));
    }

    @Test
    void unbalancedMarkerStillReportsTheFirstCandidateError() {
        // nothing balances — fall back to the first $ pair, same error as ever
        assertThrows(ExpressionException.class, () -> apply("say $min(1,$ oops", NumberMathMode.EXPLICIT_ONLY));
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
    void autoDetectLeavesDottedNumbersAlone() {
        // "1.2.3" used to parse as 1.2 * .3 via implicit multiplication and go
        // out as 0.36; with no operator in it there is nothing to compute, so
        // a version string or dotted id now stays as typed
        assertEquals("tp @s 1.2.3", apply("tp @s 1.2.3", NumberMathMode.AUTO_DETECT));
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
