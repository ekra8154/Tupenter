package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the language says when you get it wrong.
 *
 * <p>For a scripting language people learn by typing into a chat box, the
 * error message IS the documentation you read most often — you meet it more
 * than any help page. So these aren't "does it throw" tests; each one pins the
 * MESSAGE, because a message that stops naming the function, or stops
 * suggesting the fix, has regressed even though nothing crashed.
 */
class ExpressionErrorsTest {

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    /** Every error names the thing you got wrong — never just "invalid argument". */
    private static void assertErrorNames(String expression, String... mustContain) {
        String message = errorFrom(expression);
        for (String fragment : mustContain) {
            assertTrue(message.contains(fragment),
                    "'" + expression + "' should mention \"" + fragment + "\" but said: " + message);
        }
    }

    // ------------------------------------------------------------ arity

    /**
     * Called with the wrong number of arguments, a function answers with its
     * own SIGNATURE — that's the one piece of information that resolves the
     * mistake without opening a help page.
     */
    @Test
    void theWrongArgumentCountShowsTheSignature() {
        assertErrorNames("nth(1)", "nth(list, index)");
        assertErrorNames("nth(1,2,3)", "nth(list, index)");
        assertErrorNames("indexof(1)", "indexof(list, value)");
        assertErrorNames("contains(1)", "contains(list, value)");
        assertErrorNames("substr(\"a\")", "substr(text, start[, count])");
        assertErrorNames("substr(\"a\",1,2,3)", "substr(text, start[, count])");
        assertErrorNames("replace(\"a\",\"b\")", "replace(text, find, replacement)");
        assertErrorNames("rand()", "rand(min, max)", "rand(list)");
        assertErrorNames("rand(1,2,3)", "rand(min, max)", "rand(list)");
        assertErrorNames("range(1)", "range(start, stop)");
        assertErrorNames("range(1,2,3,4)", "range(start, stop)");
        assertErrorNames("vec(1,2)", "vec(x, y, z)", "three numbers");
        assertErrorNames("component(1)", "component(v, axis)");
        assertErrorNames("keys(1)", "keys(selector, path)");
    }

    @Test
    void singleArgumentFunctionsSayExactlyOne() {
        for (String call : new String[]{"len()", "len(1,2)", "trim()", "upper(1,2)", "lower()",
                "abs(1,2)", "sqrt()", "floor()", "ceil(1,2)", "round()", "int()", "float(1,2)"}) {
            String name = call.substring(0, call.indexOf('('));
            assertErrorNames(call, name + "(...)", "exactly one argument");
        }
    }

    @Test
    void variadicMathSaysItNeedsAtLeastOne() {
        assertErrorNames("min()", "min(...)", "at least one number");
        assertErrorNames("max()", "max(...)", "at least one number");
    }

    @Test
    void anEmptyPickSaysSoInsteadOfBlamingTheParenthesis() {
        // it used to fall through to the generic "Unexpected ')'", which told
        // you where the parser stopped but nothing about pick
        assertErrorNames("pick()", "pick(...)", "at least one option");
    }

    // ------------------------------------------------------- argument types

    /** A wrong TYPE gets a worked example, not just a complaint. */
    @Test
    void theWrongArgumentTypeShowsAWorkingCall() {
        assertErrorNames("nth(5, 0)", "must be a list", "nth(blockset(");
        assertErrorNames("contains(5, 1)", "must be a list", "contains(blockset(");
        assertErrorNames("component(\"x\", \"q\")", "\"x\", \"y\", or \"z\"", "'q'");
    }

    @Test
    void aNumberWhereTextOrAListBelongsIsNamedByType() {
        assertErrorNames("len(true)", "len(...)", "a list or text");
        assertErrorNames("abs(\"x\")", "abs(...)", "got text");
        assertErrorNames("sqrt(\"x\")", "sqrt(...)", "got text");
        assertErrorNames("floor(\"x\")", "floor(...)", "got text");
        assertErrorNames("vec(\"a\",\"b\",\"c\")", "vec(...)", "got text");
    }

    /**
     * int/float are the only coercions, so they're the only place text-that-
     * isn't-a-number can fail — and it quotes what you actually passed.
     */
    @Test
    void theConversionsQuoteTheTextTheyCouldNotRead() {
        assertErrorNames("int(\"abc\")", "int(...)", "\"abc\"", "isn't a number");
        assertErrorNames("float(\"\")", "float(...)", "isn't a number");
    }

    /**
     * Numbers ARE accepted where text is expected — that direction is the same
     * everyday coercion as "lvl " + client.xp_level, and it's what makes
     * upper(client.gamemode)-style calls work on anything. Only the reverse
     * (text into math) needs int()/float().
     */
    @Test
    void aNumberReadsAsTextWithoutComplaint() {
        assertEquals("5", calc("upper(5)"));
        assertEquals("5", calc("trim(5)"));
        assertEquals("5", calc("substr(5, 0)"));
        assertEquals("5", calc("replace(5, \"a\", \"b\")"));
        assertEquals("31", calc("\"3\" + 1"), "but text stays text in MATH — this concatenates");
    }

    // ------------------------------------------------------ numeric guards

    /** Guards that stop a typo from hanging the game rather than erroring. */
    @Test
    void runawayNumbersAreRefusedWithTheirLimit() {
        assertErrorNames("2^100000", "exponent is too large", "1024");
        assertErrorNames("range(1,200000)", "range is too large");
        assertErrorNames("range(1,10,0)", "step can't be 0");
        assertErrorNames("range(1,10,-1)", "wrong way", "start 1", "stop 10", "step -1");
        assertErrorNames("range(10,1,1)", "wrong way");
    }

    @Test
    void zeroToANegativePowerIsNamedNotInfinite() {
        assertErrorNames("0^-1", "0 raised to a negative power");
    }

    @Test
    void aRangeOfOneElementIsFineNotAWrongWayStep() {
        assertEquals("(5)", calc("range(5, 5)"));
        assertEquals("(1 | 3 | 5)", calc("range(1, 5, 2)"));
        assertEquals("(5 | 3 | 1)", calc("range(5, 1, -2)"));
        assertEquals("(5 | 4 | 3 | 2 | 1)", calc("range(5, 1)"), "a backwards range picks its own step");
    }

    // ---------------------------------------------------------- comparisons

    @Test
    void booleansCompareForEqualityAndSayWhyNotForOrdering() {
        assertEquals("true", calc("true == true"));
        assertEquals("false", calc("true == false"));
        assertEquals("true", calc("true != false"));
        assertEquals("false", calc("false != false"));
        // ordering booleans is meaningless; the message points at what DOES work
        assertErrorNames("true < false", "'<' only compares numbers", "== or != for true/false");
        assertErrorNames("true >= false", "'>=' only compares numbers", "== or != for true/false");
    }

    @Test
    void textComparesForEqualityAndSaysWhyNotForOrdering() {
        assertEquals("true", calc("\"a\" == \"a\""));
        assertEquals("true", calc("\"a\" != \"b\""));
        assertErrorNames("\"a\" < \"b\"", "'<' only compares numbers", "== or != for text");
        assertErrorNames("\"a\" > \"b\"", "'>' only compares numbers", "== or != for text");
    }

    /** Mixing types names BOTH types, so you can see which end surprised you. */
    @Test
    void comparingAcrossTypesNamesBothOfThem() {
        assertErrorNames("1 < \"a\"", "Cannot compare", "number", "text");
        assertErrorNames("1 == true", "Cannot compare", "number");
        assertErrorNames("\"a\" == true", "Cannot compare", "text");
    }

    @Test
    void logicalOperatorsSayWhichSideWasNotBoolean() {
        assertErrorNames("true && 1", "right of &&", "got a number");
        assertErrorNames("1 && true", "left of &&", "got a number");
        assertErrorNames("1 || true", "left of ||", "got a number");
        assertErrorNames("false || 1", "right of ||", "got a number");
        assertErrorNames("!5", "after !", "got a number");
        assertErrorNames("1 ? 2 : 3", "before '?'", "got a number");
    }

    /** && and || short-circuit, so the dead side is never evaluated — or blamed. */
    @Test
    void shortCircuitingSkipsTheSideItDoesNotNeed() {
        assertEquals("false", calc("false && (1/0 == 1)"), "the right side would have divided by zero");
        assertEquals("true", calc("true || (1/0 == 1)"));
        assertEquals("1", calc("true ? 1 : 1/0"), "the untaken ternary branch isn't evaluated either");
        assertEquals("1", calc("false ? 1/0 : 1"));
    }

    // ------------------------------------------------------------ structure

    @Test
    void unbalancedExpressionsSayWhatIsMissing() {
        assertErrorNames("(1", "Missing closing parenthesis");
        assertErrorNames("1)", "Unexpected ')'");
        assertErrorNames("\"unclosed", "Unterminated string", "closing \"");
        assertErrorNames("1 +", "Unexpected end of expression");
        assertErrorNames("", "Unexpected end of expression");
        assertErrorNames("+", "Unexpected '+'");
        assertErrorNames("min(1", "Missing closing parenthesis");
    }

    @Test
    void aTernaryMissingItsElseSaysSo() {
        assertErrorNames("true ? 1", "?");
    }

    /** An unknown name is quoted exactly as typed, so a typo is visible. */
    @Test
    void unknownNamesAreQuotedBackAtYou() {
        assertErrorNames("unknownvar", "Unknown variable", "'unknownvar'");
        assertErrorNames("client.nope", "Unknown variable", "'client.nope'");
    }

    /** An unknown FUNCTION offers the near miss and the index — never a bare refusal. */
    @Test
    void unknownFunctionsSuggestAndPointAtTheIndex() {
        assertErrorNames("flor(2)", "floor", "/tupenter help functions");
        assertErrorNames("zzzzzz(2)", "Unknown function: zzzzzz", "/tupenter help functions");
    }

    /**
     * Reaching for the #foreach header's (a | b | c) inside an expression is the
     * obvious next guess once you have seen it work in a loop, and the old answer
     * was "Missing closing parenthesis" — true, useless, and about the wrong
     * thing. The two forms are not interchangeable: the header makes TEXT, so
     * (1 | 2) then x + 1 concatenates to "11", while list(1, 2) keeps numbers and
     * gives 3. The error has to say which one you want.
     */
    @Test
    void thePipeListFormIsRedirectedToList() {
        assertErrorNames("(\"a\" | \"b\")", "#foreach", "list(a, b, c)");
        assertErrorNames("(1 | 2 | 3)", "list(a, b, c)");
        assertErrorNames("(\"a\" | \"b\")", "quote anything meant as a bare word");
    }

    /** ...without disturbing the two things that legitimately carry a pipe. */
    @Test
    void booleanOrAndPickStillTakeTheirPipes() {
        assertEquals("true", calc("(1 > 0) || (2 > 3)"), "|| is still boolean or");
        assertEquals("true", calc("(false || true)"), "even directly inside the parens");
        // pick parses its own | options before the group parser ever sees them
        assertEquals("b", calc("pick(\"a\" | \"b\")"));
    }

}
