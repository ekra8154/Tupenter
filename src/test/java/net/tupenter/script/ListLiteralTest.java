package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code list(...)} — the language's ONE way to build a list, in its two flavours.
 *
 * <p>The rule these hold up: <b>commas mean VALUES, pipes mean TEXT</b>, and
 * parentheses mean neither — they group, everywhere, with no exceptions. The
 * pipe form used to be bare parentheses in a #foreach header; moving it inside
 * list(...) is what let parentheses go back to having a single meaning.
 *
 * <p>So the cases here come in three kinds: the two flavours differing, the
 * things that also contain a pipe and must NOT become lists, and the errors that
 * catch the old spellings and name the new one.
 */
class ListLiteralTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    private static ScriptParser.ParseResult parse(String line) {
        SessionVariableStore store = new SessionVariableStore();
        return ScriptParser.parse(line, new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT,
                new LinkedHashMap<>(), true, true, true, true, 100, 1000, new Random(42), store, store));
    }

    private static List<String> run(String line) {
        ScriptParser.ParseResult result = parse(line);
        assertNull(result.error(), "expected no error, got: " + result.error());
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    // -------------------------------------------------------- the two flavours

    @Test
    void pipesMakeTextAndNeedNoQuotes() {
        assertEquals("list(\"short\", \"tall\")", calc("list(short | tall)"));
        assertEquals("list(\"a\", \"b\", \"c\")", calc("list(a | b | c)"));
    }

    @Test
    void commasMakeValuesAndAreComputed() {
        assertEquals("list(1, 2)", calc("list(1, 2)"));
        assertEquals("list(6, 7)", calc("list(2 * 3, 7)"));
    }

    /**
     * The distinction in one pair of lines. Pipes give the TEXT "1", so x + 1
     * concatenates; commas give the NUMBER 1, so it adds.
     */
    @Test
    void theSameDigitsMeanDifferentThings() {
        assertEquals(List.of("say 11", "say 21"), run("#foreach $x$ in list(1 | 2) (/say $x + 1$)"));
        assertEquals(List.of("say 2", "say 3"), run("#foreach $x$ in list(1, 2) (/say $x + 1$)"));
    }

    @Test
    void pipeItemsAreNeverEvaluated() {
        assertEquals("list(\"2 * 3\", \"7\")", calc("list(2 * 3 | 7)"), "taken as typed");
        assertEquals("list(6, 7)", calc("list(2 * 3, 7)"), "computed");
    }

    /**
     * Permissive on purpose: a pipe item is text, so characters that are
     * operators in expression-land are ordinary content here. This is what the
     * form is FOR, and it is why it could never have been "just parse an
     * expression".
     */
    @Test
    void pipeItemsMayBeThingsNoExpressionParserWouldAccept() {
        assertEquals("list(\"a(1)\", \"b\")", calc("list(a(1) | b)"));
        assertEquals("list(\"0,0]}\", \"5.0,1]}\")", calc("list(0,0]} | 5.0,1]})"));
        assertEquals("list(\"one two\", \"three\")", calc("list(one two | three)"), "spaces are content");
        assertEquals("list(\"a|b\", \"c\")", calc("list(a\\|b | c)"),
                "an escaped pipe is content, not a separator");
    }

    @Test
    void pipeItemsAreTrimmed() {
        assertEquals("list(\"a\", \"b\")", calc("list(  a   |   b  )"));
    }

    @Test
    void aListGoesInALocalAndLoops() {
        assertEquals(List.of("say short", "say tall"),
                run("#local kinds = list(short | tall) && #foreach $k$ in kinds (/say $k$)"));
    }

    @Test
    void listsFeedEveryListConsumer() {
        assertEquals("2", calc("len(list(a | b))"));
        assertEquals("b", calc("nth(list(a | b), 1)"));
        assertEquals("true", calc("contains(list(a | b), \"b\")"));
    }

    // -------------------------------------------------------------------- NBT

    /**
     * NBT goes in either way, and the pipe form takes it RAW — braces, brackets,
     * colons and commas are all just characters there, so nothing needs escaping.
     * That is the case the permissiveness was for.
     */
    @Test
    void nbtSurvivesBothForms() {
        assertEquals("list(\"{Count:1b}\", \"{id:5}\")", calc("list(\"{Count:1b}\", \"{id:5}\")"),
                "quoted, comma form");
        assertEquals("list(\"{Count:1b}\", \"{id:5}\")", calc("list({Count:1b} | {id:5})"),
                "raw, pipe form — identical result, no quotes needed");
        assertEquals("list(\"{Enchantments:[{id:sharpness,lvl:5}]}\", \"b\")",
                calc("list({Enchantments:[{id:sharpness,lvl:5}]} | b)"),
                "nested braces and brackets, and a comma inside an item");
    }

    /**
     * Quotes are CONTENT in the pipe form, not delimiters — which is exactly what
     * makes NBT-with-strings work, since {id:"minecraft:stone"} carries its own.
     */
    @Test
    void quotesInsideAPipeItemAreJustCharacters() {
        assertEquals("list(\"{id:\\\"minecraft:stone\\\"}\", \"b\")",
                calc("list({id:\"minecraft:stone\"} | b)"));
    }

    /**
     * Quotes protect a pipe from being read as a SEPARATOR, which is the check
     * that keeps an ordinary comma list intact: list("a|b", "c") must stay two
     * strings, not get torn into pieces because one of them contains a pipe.
     *
     * <p>They protect it, but they do not consume it — the quote characters stay
     * in a pipe item, because pipe items are raw text. That is deliberate and is
     * what makes the NBT cases above work.
     */
    @Test
    void quotesHideAPipeFromTheSeparatorScan() {
        assertEquals("list(\"a|b\", \"c\")", calc("list(\"a|b\", \"c\")"),
                "a comma list is not hijacked by a pipe inside one of its strings");
        assertEquals("list(\"\\\"a|b\\\"\", \"c\")", calc("list(\"a|b\" | c)"),
                "in pipe mode the quotes are kept as content, and the pipe inside them does not split");
        assertEquals("list(\"a|b\", \"c\")", calc("list(a\\|b | c)"),
                "escaping is the other way to put a literal pipe in an item");
    }

    @Test
    void nbtSubstitutesIntoACommand() {
        assertEquals(List.of("give @s stick{Count:1b}", "give @s stick{Count:2b}"),
                run("#foreach $n$ in list({Count:1b} | {Count:2b}) (/give @s stick$n$)"));
    }

    // ------------------------------------------- parentheses only ever group

    @Test
    void parenthesesGroupAndNothingElse() {
        assertEquals("9", calc("(1 + 2) * 3"));
        assertEquals("5", calc("(5)"));
        assertEquals("true", calc("(false || true)"), "|| is boolean or, not a separator");
        assertEquals("true", calc("(1 > 0) || (2 > 3)"));
        assertEquals(List.of("say yes"), run("#if (1 > 0 || 2 > 3) (/say yes)"));
    }

    /** The old bare-parenthesis list is caught by name, with the new spelling. */
    @Test
    void bareParenthesesWithPipesNameTheNewForm() {
        String error = errorFrom("(a | b)");
        assertTrue(error.contains("group"), error);
        assertTrue(error.contains("list(a | b)"), error);
    }

    /** ...including in a #foreach header, where it used to be the only spelling. */
    @Test
    void theOldForeachHeaderSpellingIsCaughtAndRewritten() {
        String error = parse("#foreach $x$ in (a | b | c) (/say $x$)").error();
        assertTrue(error != null && error.contains("list(a | b | c)"), String.valueOf(error));
    }

    // ---------------------------------------------------------------- errors

    /**
     * A pipe WINS, and a comma next to one is content — there is deliberately no
     * "mixed separators" error, because there could not be an honest one:
     * list(0,0]} | 5.0,1]}) is a real two-item list whose items contain commas.
     * Once pipe mode is on, a comma is as ordinary as a bracket.
     */
    @Test
    void aCommaBesideAPipeIsContentNotASeparator() {
        assertEquals("list(\"1, 2\", \"3\")", calc("list(1, 2 | 3)"));
        assertEquals("list(\"0,0]}\", \"5.0,1]}\")", calc("list(0,0]} | 5.0,1]})"));
    }

    /** Pipes belong to list(...) alone, so any other function says so. */
    @Test
    void aPipeInAnyOtherFunctionPointsAtList() {
        String error = errorFrom("len(a | b)");
        assertTrue(error.contains("Only list(...)"), error);
        assertTrue(error.contains("list(a | b)"), error);
    }

    // ------------------------------------------------------------------ pick

    /**
     * pick's options are EXPRESSIONS, so its old pipe separator now contradicts
     * what a pipe means everywhere else. It errors rather than quietly
     * reinterpreting: turning pick(rand(1,5) | client.pos.y) into two strings is
     * exactly the silent wrong answer this whole design exists to avoid.
     */
    @Test
    void pickTakesCommasAndRefusesItsOldPipe() {
        assertEquals("b", calc("pick(\"a\", \"b\")"));
        assertEquals("6", calc("pick(2 * 3, 2 * 3)"), "options compute");

        String error = errorFrom("pick(\"a\" | \"b\")");
        assertTrue(error.contains("pick(a, b, c)"), error);
        assertTrue(error.contains("literal text"), error);
    }

    // ------------------------------------------------------------- rendering

    /**
     * The printed form is what you would type to get the list back, with the
     * element types visible — so /calc on a pipe list shows you it made text.
     */
    @Test
    void listsPrintAsSourceYouCouldPasteBack() {
        assertEquals("list(1, 2, 3)", calc("range(1, 3)"));
        assertEquals("list(\"a\", \"b\")", calc("list(a | b)"));
        assertEquals("list(\"a\", 2)", calc("list(\"a\", 2)"), "mixed types stay distinguishable");
        assertEquals("list(\"say \\\"hi\\\"\")", calc("list(\"say \\\"hi\\\"\")"), "quotes in content are escaped");
    }
}
