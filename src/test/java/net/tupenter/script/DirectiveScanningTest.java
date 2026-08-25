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
 * The scanning layer: how a line is carved into prefixes, directives, groups
 * and list items before anything is evaluated.
 *
 * <p>All of it is marker-aware — a {@code $...$} span is opaque, so a {@code |}
 * or {@code ..} or the word {@code in} inside one belongs to the expression and
 * must not be mistaken for punctuation. That's the rule these tests exist to
 * hold, because breaking it doesn't throw: it silently splits a line somewhere
 * the user didn't mean.
 *
 * <p>Both execution modes are driven, because {@code #wait} (and the loops)
 * are parsed twice — once eagerly and once in the lazy walker — and a fix
 * applied to one copy is a bug in the other.
 */
class DirectiveScanningTest {

    private static ScriptParser.Options options(boolean lazy) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(), true, true, true, true,
                100, 1000, new Random(1), store, store).withLazyExecution(lazy);
    }

    /**
     * What the line actually SENDS. #set notices are excluded because they
     * travel on a different channel in each mode — see
     * {@link #setNoticesArriveOnADifferentChannelInEachMode()} — while the
     * sends themselves must be identical either way.
     */
    private static List<String> sent(String line, boolean lazy) {
        ScriptParser.ParseResult parsed = ScriptParser.parse(line, options(lazy));
        assertNull(parsed.error(), line + " → " + parsed.error());
        List<String> out = new ArrayList<>();
        Script.StatementSource source = parsed.script().source();
        for (int i = 0; i < 200; i++) {
            Script.SendStatement next = source.next();
            if (next == null) {
                break;
            }
            if (next.kind() != Script.Kind.NOTICE) {
                out.add(next.content());
            }
        }
        return out;
    }

    /** Runs a line both eagerly and lazily and asserts the two agree. */
    private static List<String> sentBothWays(String line) {
        List<String> eager = sent(line, false);
        assertEquals(eager, sent(line, true), "eager and lazy disagree about: " + line);
        return eager;
    }

    /**
     * The one thing the two modes legitimately do differently. An eager parse
     * evaluates up front, so the "$x$ = 5" notice is known before the line runs
     * and rides along on the ParseResult. A lazy parse hasn't evaluated
     * anything yet — the value doesn't exist until the statement is pulled — so
     * the notice can only arrive as a statement in the stream. Same information,
     * different channel, and it's forced by WHEN the value becomes known.
     */
    @Test
    void setNoticesArriveOnADifferentChannelInEachMode() {
        ScriptParser.ParseResult eager = ScriptParser.parse("#set a = 1 && /say $a$", options(false));
        assertEquals(List.of("$a$ = 1"), eager.notices());
        assertEquals(List.of("say 1"),
                eager.script().statements().stream().map(Script.SendStatement::content).toList());

        ScriptParser.ParseResult lazy = ScriptParser.parse("#set a = 1 && /say $a$", options(true));
        assertEquals(List.of(), lazy.notices(), "nothing is known at parse time");
        List<String> pulled = new ArrayList<>();
        Script.StatementSource source = lazy.script().source();
        for (Script.SendStatement next = source.next(); next != null; next = source.next()) {
            pulled.add(next.kind() + ":" + next.content());
        }
        assertEquals(List.of("NOTICE:$a$ = 1", "COMMAND:say 1"), pulled);
    }

    /** #local never announces, in either mode — that's the difference from #set. */
    @Test
    void localAnnouncesNothingEitherWay() {
        for (boolean lazy : List.of(false, true)) {
            ScriptParser.ParseResult parsed = ScriptParser.parse("#local a = 1 && /say $a$", options(lazy));
            assertEquals(List.of(), parsed.notices(), "lazy=" + lazy);
            assertEquals(List.of("say 1"), sent("#local a = 1 && /say $a$", lazy));
        }
    }

    private static String errorFrom(String line, boolean lazy) {
        ScriptParser.ParseResult parsed = ScriptParser.parse(line, options(lazy));
        if (parsed.error() != null) {
            return parsed.error();
        }
        try {
            Script.StatementSource source = parsed.script().source();
            for (int i = 0; i < 200 && source.next() != null; i++) {
                // drain
            }
        } catch (RuntimeException thrown) {
            return thrown.getMessage();
        }
        throw new AssertionError("expected '" + line + "' to fail");
    }

    // ------------------------------------------------------------ prefixes

    /** Every prefix has a shorthand, and they combine in any order. */
    @Test
    void prefixShorthandsExpandToTheirFullForms() {
        assertEquals(List.of("say hi"), sentBothWays("#s /say hi"));
        assertEquals(List.of("say hi"), sentBothWays("#nr /say hi"));
        assertEquals(List.of("say hi"), sentBothWays("#r /say hi"));
        assertEquals(List.of("say hi"), sentBothWays("#s #nr /say hi"));
        assertEquals(List.of("say hi"), sentBothWays("#nr #s /say hi"));
        // extra whitespace between prefixes is fine
        assertEquals(List.of("say hi"), sentBothWays("#s    #nr    /say hi"));
    }

    @Test
    void theSilentPrefixIsRecordedOnTheStatement() {
        ScriptParser.ParseResult loud = ScriptParser.parse("say hi", options(false));
        ScriptParser.ParseResult quiet = ScriptParser.parse("#silent /say hi", options(false));
        assertTrue(quiet.changed());
        assertEquals("say hi", quiet.script().statements().get(0).content());
        assertTrue(quiet.script().statements().get(0).silent(), "#silent marks the statement");
        if (loud.changed()) {
            assertTrue(!loud.script().statements().get(0).silent());
        }
    }

    // --------------------------------------------------------------- #wait

    /** Every unit, in both execution modes — the two parse paths must agree. */
    @Test
    void everyWaitUnitParsesInBothModes() {
        for (String duration : List.of("10t", "1s", "1.5s", "2m", "1d", "10", "1T", "1S")) {
            for (boolean lazy : List.of(false, true)) {
                ScriptParser.ParseResult parsed =
                        ScriptParser.parse("say a && #wait " + duration + " && /say b", options(lazy));
                assertNull(parsed.error(), "#wait " + duration + " (lazy=" + lazy + "): " + parsed.error());
            }
        }
    }

    @Test
    void bothWaitModesParseInBothExecutionModes() {
        for (String mode : List.of("realtime", "real", "gametime", "game")) {
            for (boolean lazy : List.of(false, true)) {
                ScriptParser.ParseResult parsed =
                        ScriptParser.parse("#wait 5s " + mode + " && /say b", options(lazy));
                assertNull(parsed.error(), "#wait 5s " + mode + " (lazy=" + lazy + "): " + parsed.error());
            }
        }
    }

    @Test
    void badWaitDurationsAreRefusedTheSameWayInBothModes() {
        for (boolean lazy : List.of(false, true)) {
            assertTrue(errorFrom("#wait 5x && /say b", lazy).contains("5x"), "lazy=" + lazy);
            assertTrue(errorFrom("#wait sideways && /say b", lazy).contains("sideways"), "lazy=" + lazy);
            assertTrue(errorFrom("#wait 999999t && /say b", lazy).contains("72000"), "lazy=" + lazy);
        }
    }

    // ---------------------------------------------------- marker awareness

    /**
     * A {@code $...$} span is opaque to every scanner. The word "in", the
     * range "..", and the list separator "|" all appear inside real
     * expressions, and none of them may be read as punctuation there.
     */
    @Test
    void aRangeInsideAMarkerIsNotTheLoopsRange() {
        assertEquals(List.of("say 1", "say 2"), sentBothWays("#set a = 1 && #for $i$ in $a$..2 (/say $i$)"));
    }

    @Test
    void parenthesesInsideAListItemDoNotSplitIt() {
        assertEquals(List.of("say a(1)", "say b"), sentBothWays("#foreach $x$ in list(a(1) | b) (/say $x$)"));
    }

    @Test
    void anEscapedSeparatorStaysInsideItsItem() {
        assertEquals(List.of("say a|b", "say c"), sentBothWays("#foreach $x$ in list(a\\|b | c) (/say $x$)"));
    }

    /**
     * Bare parentheses are grouping now, so an empty pair is a malformed
     * expression rather than an empty list. list() IS an empty list, and looping
     * over it zero times is correct, not a typo.
     */
    @Test
    void emptyParenthesesAreNotAnEmptyList() {
        assertTrue(errorFrom("#foreach $x$ in () (/say $x$)", true).length() > 0,
                "an empty group is not a list");
        assertEquals(List.of(), sentBothWays("#foreach $x$ in list() (/say $x$)"),
                "an empty list loops zero times");
    }

    @Test
    void aLiteralListKeepsItsItemsVerbatim() {
        assertEquals(List.of("say a", "say b", "say c"), sentBothWays("#foreach $x$ in list(a | b | c) (/say $x$)"));
        assertEquals(List.of("say one two", "say three"), sentBothWays("#foreach $x$ in list(one two | three) (/say $x$)"),
                "spaces inside an item are part of it");
        assertEquals(List.of("say one two"), sentBothWays("#foreach $x$ in list(\"one two\") (/say $x$)"),
                "a ONE-item text list has no pipe to switch on, so it takes quotes");
    }

    // ------------------------------------------------------------- groups

    @Test
    void groupsNestAndHoldChains() {
        assertEquals(List.of("say a", "say b"), sentBothWays("#repeat 1 (/say a && /say b)"));
        assertEquals(List.of("say x", "say x"), sentBothWays("#repeat 2 (#repeat 1 (/say x))"));
    }

    @Test
    void aChainAfterAGroupKeepsRunning() {
        assertEquals(List.of("say in", "say after"), sentBothWays("#repeat 1 (/say in) && /say after"));
    }

    /** #silent's group form wraps a whole chain rather than one statement. */
    @Test
    void silentCanWrapAGroup() {
        assertEquals(List.of("say a", "say b"), sentBothWays("#silent (/say a && /say b)"));
    }

    /** The group form takes exactly one group — trailing text is the mistake it catches. */
    @Test
    void silentTakesJustOneGroup() {
        for (boolean lazy : List.of(false, true)) {
            assertTrue(errorFrom("#silent (/say a) after", lazy).contains("just one (...) group"), "lazy=" + lazy);
        }
    }
}
