package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the DIRECTIVE layer says when you get it wrong — the other half of the
 * error surface from {@link ExpressionErrorsTest}, which covers expressions.
 *
 * <p>Same standard: every message has to name the directive, and either show
 * its syntax or give a worked example. A #for that says only "syntax error"
 * has regressed even though it still refuses the line.
 *
 * <p>Errors arrive two ways and both are checked here. A malformed HEADER is
 * caught while parsing, so it comes back as ParseResult.error(). A bad VALUE
 * (a condition that isn't boolean, a list that isn't a list) can't be known
 * until the line runs, so under lazy execution it surfaces when the statement
 * is pulled — which is exactly when the user sees it in game.
 */
class ScriptParserErrorsTest {

    private static ScriptParser.Options options() {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(), true, true, true, true,
                100, 1000, new Random(1), store, store).withLazyExecution(true);
    }

    /** The message a line produces, whether it fails at parse time or at pull time. */
    private static String errorFrom(String line) {
        ScriptParser.ParseResult parsed = ScriptParser.parse(line, options());
        if (parsed.error() != null) {
            return parsed.error();
        }
        assertTrue(parsed.changed(), "expected '" + line + "' to be handled by the parser at all");
        Script.StatementSource source = parsed.script().source();
        try {
            for (int i = 0; i < 200; i++) {
                if (source.next() == null) {
                    break;
                }
            }
        } catch (RuntimeException thrown) {
            return thrown.getMessage();
        }
        throw new AssertionError("expected '" + line + "' to fail, but it ran clean");
    }

    private static void assertErrorNames(String line, String... mustContain) {
        String message = errorFrom(line);
        for (String fragment : mustContain) {
            assertTrue(message.contains(fragment),
                    "'" + line + "' should mention \"" + fragment + "\" but said: " + message);
        }
    }

    /** The statements a line actually produces, drained. */
    private static List<String> sent(String line) {
        ScriptParser.ParseResult parsed = ScriptParser.parse(line, options());
        assertNull(parsed.error(), line);
        List<String> out = new ArrayList<>();
        Script.StatementSource source = parsed.script().source();
        for (int i = 0; i < 200; i++) {
            Script.SendStatement next = source.next();
            if (next == null) {
                break;
            }
            out.add(next.content());
        }
        return out;
    }

    // ----------------------------------------------------------------- #for

    @Test
    void aMalformedForHeaderShowsTheWholeSyntax() {
        String syntax = "#for $x$ in 1..10 step 2";
        assertErrorNames("#for $i$ 1..5 (/say hi)", syntax);       // no "in"
        assertErrorNames("#for in 1..5 (/say hi)", syntax);        // no variable
        assertErrorNames("#for $i$ in (/say hi)", "#for needs a range like 1..10");
        assertErrorNames("#for $i$ in 1.. (/say hi)", "#for needs a range like 1..10");
        assertErrorNames("#for $i$ in ..5 (/say hi)", "#for needs a range like 1..10");
        assertErrorNames("#for $i$ in 1..5 step (/say hi)", "#for step needs a value");
    }

    @Test
    void aForWithNoBodySaysSoWithAnExample() {
        assertErrorNames("#for $i$ in 1..5", "#for needs a (...) body", "#for $x$ in 1..10 (");
        assertErrorNames("#for $i$ in 1..5 /say hi", "#for needs a (...) body");
    }

    @Test
    void aForLoopCounterMustBeAUsableName() {
        assertErrorNames("#for $$ in 1..5 (/say hi)", "Variable names must start with a letter");
        assertErrorNames("#for $1x$ in 1..5 (/say hi)", "Variable names must start with a letter");
    }

    @Test
    void aWorkingForLoopStillWorks() {
        assertEquals(List.of("say 1", "say 2", "say 3"), sent("#for $i$ in 1..3 (/say $i$)"));
        assertEquals(List.of("say 1", "say 3", "say 5"), sent("#for $i$ in 1..5 step 2 (/say $i$)"));
        assertEquals(List.of("say 3", "say 2", "say 1"), sent("#for $i$ in 3..1 (/say $i$)"));
    }

    // ------------------------------------------------------------- #foreach

    @Test
    void aMalformedForeachHeaderShowsTheWholeSyntax() {
        String syntax = "#foreach $x$ in list(a | b | c)";
        assertErrorNames("#foreach $b$ 1 (/say hi)", syntax);
        assertErrorNames("#foreach in range(1,3) (/say hi)", syntax);
        assertErrorNames("#foreach $b$ in (/say hi)", syntax);
    }

    @Test
    void aForeachOverSomethingThatIsNotAListSaysWhatOneLooksLike() {
        assertErrorNames("#foreach $b$ in 5 (/say hi)", "#foreach needs a list", "(a | b | c)", "range(1, 10)");
    }

    /**
     * "#foreach $b$ in range(1, 3)" with the body forgotten: the group scanner
     * takes the (1, 3) as the body, so the header is left holding a bare
     * function name. That used to surface as "Unknown variable 'range' — did
     * you mean 'rand'?", which sends you looking for a typo that isn't there.
     */
    @Test
    void aForeachWithNoBodyBlamesTheBodyNotTheFunctionName() {
        assertErrorNames("#foreach $b$ in range(1,3)",
                "#foreach needs a (...) body", "range(...) was read as the body");
        assertErrorNames("#foreach $b$ in blockset(\"stone\")",
                "#foreach needs a (...) body", "blockset(...) was read as the body");
    }

    @Test
    void aWorkingForeachStillWorks() {
        assertEquals(List.of("say a", "say b"), sent("#foreach $x$ in list(a | b) (/say $x$)"));
        assertEquals(List.of("say 1", "say 2"), sent("#foreach $x$ in range(1, 2) (/say $x$)"));
    }

    // -------------------------------------------------------- #if / #while

    @Test
    void conditionalsNameTheHalfThatIsMissing() {
        assertErrorNames("#if (/say hi)", "#if needs a (body) after the condition");
        assertErrorNames("#if 5 (/say hi)", "#if needs a (condition)");
        assertErrorNames("#if (true) (/say hi) #else", "#else needs a (body)");
        assertErrorNames("#while (true)", "#while needs a (condition)");
    }

    /** A non-boolean condition is a VALUE error, so it surfaces when the line runs. */
    @Test
    void aNonBooleanConditionSaysWhatAConditionLooksLike() {
        assertErrorNames("#while (5) (/say hi)", "#while condition must be true/false");
        assertErrorNames("#if (5) (/say hi)", "true/false");
    }

    @Test
    void textAfterTheIfBodyIsQuotedBackSoTyposAreVisible() {
        assertErrorNames("#if (true) (/say hi) #elsif (/say bye)",
                "Unexpected text after #if body", "#elsif");
    }

    @Test
    void workingConditionalsStillWork() {
        assertEquals(List.of("say yes"), sent("#if (true) (/say yes) #else (/say no)"));
        assertEquals(List.of("say no"), sent("#if (false) (/say yes) #else (/say no)"));
        assertEquals(List.of("say mid"), sent("#if (false) (/say yes) #elseif (true) (/say mid) #else (/say no)"));
    }

    // ------------------------------------------------------------- #repeat

    @Test
    void repeatNamesItsCountProblem() {
        assertErrorNames("#repeat (/say hi)", "#repeat needs a count", "#repeat 5 (");
        assertErrorNames("#repeat 3 /say hi", "#repeat needs a (...) body");
        assertErrorNames("#repeat -1 (/say hi)", "can't be negative", "(got -1)");
        assertErrorNames("#repeat abc (/say hi)", "#repeat count", "Unknown variable 'abc'");
    }

    @Test
    void repeatOfZeroRunsNothingRatherThanFailing() {
        assertEquals(List.of(), sent("#repeat 0 (/say hi)"));
    }

    // --------------------------------------------------------------- #wait

    @Test
    void waitNamesEveryUnitItAccepts() {
        assertErrorNames("#wait", "#wait needs a duration", "10t", "0.5s", "2m");
        assertErrorNames("#wait abc", "got 'abc'");
        assertErrorNames("#wait 5x", "got '5x'");
    }

    @Test
    void waitModeMustBeOneOfTheTwoAndSaysWhichTwo() {
        assertErrorNames("#wait 5 sideways", "'realtime' or 'gametime'", "got 'sideways'");
        assertErrorNames("#wait 5s extra words", "#wait takes a duration and optional mode");
    }

    @Test
    void waitIsCappedAndSaysTheCap() {
        assertErrorNames("#wait 999999t", "capped at 72000 ticks", "one hour");
    }

    @Test
    void everyDocumentedWaitFormParses() {
        for (String form : List.of("#wait 10t", "#wait 0.5s", "#wait 2m", "#wait 3d",
                "#wait 5 realtime", "#wait 5s gametime", "#wait 5s real", "#wait 5s game")) {
            ScriptParser.ParseResult parsed = ScriptParser.parse(form + " && /say after", options());
            assertNull(parsed.error(), form + " should parse: " + parsed.error());
        }
    }

    // ------------------------------------------------------- #set / #local

    @Test
    void setShowsItsSyntaxIncludingTheOptionalDollars() {
        String syntax = "#set name = expression";
        assertErrorNames("#set", syntax, "$ around the name is optional");
        assertErrorNames("#set $x$", syntax);
        assertErrorNames("#set = 5", syntax);
        assertErrorNames("#set $x$ =", "#set $x$ needs a value");
    }

    @Test
    void variableNamesAreValidatedWithTheRuleTheyBroke() {
        assertErrorNames("#set $1x$ = 5", "Variable names must start with a letter");
        assertErrorNames("#set $x..y$ = 5", "can't start/end with a dot or contain '..'");
        assertErrorNames("#set $x.$ = 5", "can't start/end with a dot");
        // a leading dot breaks the FIRST rule, and gets that rule's message
        assertErrorNames("#set $.x$ = 5", "Variable names must start with a letter");
    }

    /** Compound assignment reads the old value, so it needs one to exist. */
    @Test
    void compoundAssignmentToAnUnsetNameSaysItIsUnset() {
        assertErrorNames("#set x *= 2", "#set $x$", "Unknown variable 'x'");
    }

    @Test
    void everyCompoundOperatorWorks() {
        assertEquals(List.of("$x$ = 5", "$x$ = 8", "say 8"), sent("#set x = 5 && #set x += 3 && /say $x$"));
        assertEquals(List.of("$x$ = 5", "$x$ = 2", "say 2"), sent("#set x = 5 && #set x -= 3 && /say $x$"));
        assertEquals(List.of("$x$ = 5", "$x$ = 15", "say 15"), sent("#set x = 5 && #set x *= 3 && /say $x$"));
        assertEquals(List.of("$x$ = 6", "$x$ = 2", "say 2"), sent("#set x = 6 && #set x /= 3 && /say $x$"));
        assertEquals(List.of("$x$ = 5", "$x$ = 2", "say 2"), sent("#set x = 5 && #set x %= 3 && /say $x$"));
    }

    @Test
    void aFailedCompoundAssignmentNamesTheVariableAndTheReason() {
        assertErrorNames("#set x = 5 && #set x /= 0 && /say $x$", "#set $x$", "Division by zero");
    }

    /** #local never reaches the session store, so it prints no notice. */
    @Test
    void localSubstitutesWithoutAnnouncingItself() {
        assertEquals(List.of("say 5"), sent("#local x = 5 && /say $x$"));
        assertEquals(List.of("$x$ = 5", "say 5"), sent("#set x = 5 && /say $x$"),
                "#set does announce — that's the difference");
    }

    // ------------------------------------------------------ line prefixes

    /**
     * #silent, #norecord and #record are properties of the LINE, decided before
     * it runs, so none can appear mid-chain. All three say where they belong —
     * #record used to fall through to "Unknown directive", which was wrong
     * twice: it exists, and the real problem was its position.
     */
    @Test
    void aMidChainLinePrefixSaysWhereItBelongs() {
        assertErrorNames("say hi && #silent /say bye", "#silent", "start of the line");
        assertErrorNames("say hi && #norecord /say bye", "#norecord goes at the start of the line");
        assertErrorNames("say hi && #record /say bye", "#record goes at the start of the line");
    }

    /** #silent has a second form, and its error points at it. */
    @Test
    void midChainSilentOffersTheWrappingForm() {
        assertErrorNames("say hi && #silent /say bye", "wrap statements", "#silent (");
    }

    @Test
    void returnOutsideAFunctionSaysWhereItWorks() {
        assertErrorNames("say hi && #return 5", "#return only works inside a custom function body");
    }

    // ------------------------------------------------------------ markers

    @Test
    void aBrokenMarkerQuotesItAndOffersTheEscape() {
        assertErrorNames("give @s stick $$", "Invalid expression $$", "write \\$ for a literal dollar");
        assertErrorNames("give @s $unknownthing$", "Unknown variable 'unknownthing'", "write \\$");
    }

    @Test
    void anEscapedDollarSurvivesAsText() {
        assertEquals(List.of("give @s stick $5"), sent("give @s stick \\$5"));
    }

    /** An unpaired $ isn't a marker at all — it's a literal, and stays one. */
    @Test
    void anUnpairedDollarIsJustText() {
        assertEquals(List.of("give @s stick $x"), sent("give @s stick $x"));
    }

    // ------------------------------------------------------------- groups

    @Test
    void unbalancedGroupsSayWhichWay() {
        assertErrorNames("#repeat 2 (/say hi", "Missing closing parenthesis");
        assertErrorNames("#repeat 2 /say hi)", "Unbalanced parentheses", "unexpected ')'");
    }

    /** Empty chain segments are skipped rather than treated as errors. */
    @Test
    void emptyChainSegmentsAreForgiven() {
        assertEquals(List.of("say hi"), sent("&& say hi"));
        assertEquals(List.of("say a", "say b"), sent("say a && && say b"));
    }
}
