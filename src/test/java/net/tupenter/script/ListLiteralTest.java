package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code (a | b | c)} literal list, now that it works wherever an expression
 * does rather than only in a #foreach header.
 *
 * <p>The rule it has to hold up: commas mean VALUES, pipes mean TEXT. Those are
 * two genuinely different things, and every case here is either a demonstration
 * of the difference or a guard on something that also contains a pipe and must
 * not be swallowed.
 */
class ListLiteralTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static List<String> run(String line) {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT,
                new LinkedHashMap<>(), true, true, true, true, 100, 1000, new Random(42), store, store);
        ScriptParser.ParseResult result = ScriptParser.parse(line, options);
        assertNull(result.error(), "expected no error, got: " + result.error());
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    // ------------------------------------------------ it works everywhere now

    @Test
    void theLiteralListIsAnExpression() {
        assertEquals("list(\"a\", \"b\")", calc("(a | b)"));
        assertEquals("2", calc("len((a | b))"));
        assertEquals("b", calc("nth((a | b), 1)"));
        assertEquals("true", calc("contains((a | b), \"b\")"));
    }

    /** The line that started this: a reusable list of names, no quotes. */
    @Test
    void itGoesInALocalNow() {
        assertEquals(List.of("say short", "say tall"),
                run("#local kinds = (short | tall) && #foreach $k$ in kinds (/say $k$)"));
    }

    /** And the header form is now literally the same thing, not a special case. */
    @Test
    void theHeaderFormAndTheExpressionFormAgree() {
        assertEquals(run("#foreach $x$ in (a | b) (/say $x$)"),
                run("#local g = (a | b) && #foreach $x$ in g (/say $x$)"));
    }

    // ------------------------------------------------------ commas vs pipes

    /**
     * The whole distinction in one pair of lines. Pipes give the TEXT "1", so
     * x + 1 concatenates; commas give the NUMBER 1, so it adds.
     */
    @Test
    void pipesMakeTextAndCommasMakeValues() {
        assertEquals(List.of("say 11", "say 21"), run("#foreach $x$ in (1 | 2) (/say $x + 1$)"));
        assertEquals(List.of("say 2", "say 3"), run("#foreach $x$ in list(1, 2) (/say $x + 1$)"));
    }

    /** Pipe items are taken as typed; comma arguments are computed. */
    @Test
    void pipeItemsAreNeverEvaluated() {
        assertEquals("list(\"2 * 3\", \"7\")", calc("(2 * 3 | 7)"), "taken as typed");
        assertEquals("list(6, 7)", calc("list(2 * 3, 7)"), "computed");
    }

    /**
     * Permissive on purpose: a pipe item is text, so characters that are operators
     * in expression-land are ordinary here. This is what the form is FOR, and it
     * is why it could not simply become "parse an expression".
     */
    @Test
    void itemsMayBeThingsNoExpressionParserWouldAccept() {
        assertEquals("list(\"a(1)\", \"b\")", calc("(a(1) | b)"));
        assertEquals("list(\"0,0]}\", \"5.0,1]}\")", calc("(0,0]} | 5.0,1]})"));
        assertEquals("list(\"one two\", \"three\")", calc("(one two | three)"), "spaces are content");
        assertEquals("list(\"a|b\", \"c\")", calc("(a\\|b | c)"),
                "an escaped pipe is content, not a separator");
    }

    @Test
    void itemsAreTrimmed() {
        assertEquals("list(\"a\", \"b\")", calc("(  a   |   b  )"));
    }

    // ------------------------------- things with pipes that are NOT list literals

    /** || is boolean or, and a group holding one stays an expression. */
    @Test
    void booleanOrIsNotAList() {
        assertEquals("true", calc("(false || true)"));
        assertEquals("true", calc("(1 > 0) || (2 > 3)"));
        assertEquals(List.of("say yes"), run("#if (1 > 0 || 2 > 3) (/say yes)"));
    }

    /** A group with no pipe at all is an ordinary parenthesised expression. */
    @Test
    void aPlainGroupIsStillAGroup() {
        assertEquals("9", calc("(1 + 2) * 3"));
        assertEquals("5", calc("(5)"));
    }

    // ------------------------------------------------------------------ pick

    /**
     * pick's options have always been EXPRESSIONS, and they stay that way — a
     * text rule would have silently turned every computed pick into a string.
     * Commas are the new spelling; the pipe still works so existing scripts do.
     */
    @Test
    void pickTakesCommasNowAndStillTakesPipes() {
        assertEquals("b", calc("pick(\"a\", \"b\")"));
        assertEquals("b", calc("pick(\"a\" | \"b\")"));
        // the options COMPUTE, either way
        assertEquals("6", calc("pick(2 * 3, 2 * 3)"));
        assertEquals("6", calc("pick(2 * 3 | 2 * 3)"));
    }

    @Test
    void pickErrorsNameTheCommaForm() {
        // ";" is nobody's separator — "1 2" would NOT do, that is implicit
        // multiplication and a perfectly good single option worth 2
        ExpressionException thrown = org.junit.jupiter.api.Assertions.assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("pick(1 ; 2)", new EvalContext(new Random(1))));
        assertTrue(thrown.getMessage().contains("','"), thrown.getMessage());
    }

    // ------------------------------------------------------------- the errors

    @Test
    void aPipeAmongArgumentsPointsAtTheParenthesisedForm() {
        ExpressionException thrown = org.junit.jupiter.api.Assertions.assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("list(short | tall)", new EvalContext(new Random(1))));
        assertTrue(thrown.getMessage().contains("(a | b | c)"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("len((a | b))"), thrown.getMessage());
    }

    /**
     * A ONE-item literal list cannot be written in expression position, and that
     * is a consequence rather than an oversight: with no separator pipe there is
     * nothing to tell (a) apart from a parenthesised expression, so the group wins.
     * list("a") is the spelling for that.
     */
    @Test
    void aSingleItemNeedsListNotParentheses() {
        assertEquals("5", calc("(5)"), "no pipe, so it is a group");
        assertEquals("list(\"a\")", calc("list(\"a\")"));
    }

    // ------------------------------------------------------------- rendering

    /**
     * The printed form is the form you would type to get the list back, with the
     * element types visible — so /calc on a pipe list shows you it made text.
     */
    @Test
    void listsPrintAsSourceYouCouldPasteBack() {
        assertEquals("list(1, 2, 3)", calc("range(1, 3)"));
        assertEquals("list(\"a\", \"b\")", calc("(a | b)"));
        assertEquals("list(\"a\", 2)", calc("list(\"a\", 2)"), "mixed types stay distinguishable");
        assertEquals("list(\"say \\\"hi\\\"\")", calc("list(\"say \\\"hi\\\"\")"), "quotes in content are escaped");
    }
}
