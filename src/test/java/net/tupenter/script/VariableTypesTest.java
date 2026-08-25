package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed variables: {@code #local c:blockpos = -10 20 85}.
 *
 * <p>Two halves, and they fail differently. The PARSER half checks the value at
 * assignment — an annotation nobody enforces is a lie the tab-completer then
 * repeats. The SCAN half ({@link VariableTypes#declaredOn}) runs against
 * half-typed chat text where nothing parses, and its job is to be right about
 * the shape of a value that does not exist yet; its failure mode is not an
 * exception but a wrong mask, so the cases here are mostly about what it must
 * NOT claim.
 */
class VariableTypesTest {

    private static ScriptParser.Options options() {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, new LinkedHashMap<>(),
                true, true, true, true, 100, 1000, new Random(42), store, store);
    }

    private static List<String> run(String line) {
        ScriptParser.ParseResult result = ScriptParser.parse(line, options());
        assertNull(result.error(), "expected no error, got: " + result.error());
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    private static String errorFrom(String line) {
        ScriptParser.ParseResult result = ScriptParser.parse(line, options());
        assertTrue(result.error() != null, "expected an error from: " + line);
        return result.error();
    }

    // ------------------------------------------------------- it just works

    @Test
    void aTypedLocalBehavesExactlyLikeAnUntypedOne() {
        assertEquals(List.of("tp -10 20 85"), run("#local c:blockpos = blockpos(-10, 20, 85) && /tp $c$"));
        assertEquals(List.of("tp -10 20 85"), run("#local c = blockpos(-10, 20, 85) && /tp $c$"),
                "the annotation changes nothing about the value");
        assertEquals(List.of("tp -10 20 85"), run("#local c:blockpos = \"-10 20 85\" && /tp $c$"),
                "quoted literal text is the other spelling");
    }

    /**
     * The mistake the type check exists to catch, and it is not a niche one: a
     * right-hand side is an EXPRESSION, so "-10 20 85" is implicit multiplication.
     * Untyped it evaluates to -17000 and ships a broken /tp with no complaint.
     * With a type it stops, and the message has to name the way out.
     */
    @Test
    void bareCoordinatesMultiplyAndTheErrorSaysSo() {
        assertEquals(List.of("tp -17000"), run("#local c = -10 20 85 && /tp $c$"),
                "untyped, the collapse is silent");

        String error = errorFrom("#local c:blockpos = -10 20 85");
        assertTrue(error.contains("-17000"), error);
        assertTrue(error.contains("multiply"), error);
        assertTrue(error.contains("blockpos(x, y, z)"), error);
        assertTrue(error.contains("\"x y z\""), error);
    }

    @Test
    void setAndSetdefaultTakeATypeToo() {
        assertEquals(List.of("tp 1 2 3"), run("#set home:blockpos = blockpos(1, 2, 3) && /tp $home$"));
        assertEquals(List.of("tp 1 2 3"), run("#setdefault home:blockpos = blockpos(1, 2, 3) && /tp $home$"));
    }

    /** The $ around the name is optional, so a type has to work on either side of it. */
    @Test
    void bothDollarSpellingsAccepted() {
        assertEquals(List.of("tp 1 2 3"), run("#local $c:blockpos$ = blockpos(1, 2, 3) && /tp $c$"));
        assertEquals(List.of("tp 1 2 3"), run("#local $c$:blockpos = blockpos(1, 2, 3) && /tp $c$"));
    }

    @Test
    void compoundAssignmentKeepsItsType() {
        assertEquals(List.of("say 6"), run("#set i:int = 5 && #set i += 1 && /say $i$"));
    }

    // ------------------------------------------------- the checks that bite

    @Test
    void blockposWantsThreeWholeNumbers() {
        String tooFew = errorFrom("#local c:blockpos = \"1 2\"");
        assertTrue(tooFew.contains("3 numbers"), tooFew);
        assertTrue(tooFew.contains("blockpos"), tooFew);

        String fractional = errorFrom("#local c:blockpos = vec(1.5, 2, 3)");
        assertTrue(fractional.contains("whole"), fractional);
        // the error should name the way out, not just the problem
        assertTrue(fractional.contains("blockpos("), fractional);
    }

    /** pos is the PRECISE one — the same value blockpos rejects is correct here. */
    @Test
    void posAllowsDecimalsWhereBlockposDoesNot() {
        assertEquals(List.of("tp 1.5 2 3"), run("#local p:pos = vec(1.5, 2, 3) && /tp $p$"));
        assertTrue(errorFrom("#local c:blockpos = vec(1.5, 2, 3)").contains("whole"));
    }

    /**
     * Relative coordinates pass every coordinate type, and a relative OFFSET is
     * allowed to be fractional even where the absolute form must be whole —
     * "~0.5" is a legal blockpos argument to a real command.
     */
    @Test
    void relativeCoordinatesArePositions() {
        // ~ and ^ are not expression syntax, so they go in as quoted text — the
        // one spelling that works for every coordinate form
        assertEquals(List.of("setblock ~ ~1 ~ stone"), run("#local c:blockpos = \"~ ~1 ~\" && /setblock $c$ stone"));
        assertEquals(List.of("setblock ~0.5 ~ ^2 stone"),
                run("#local c:blockpos = \"~0.5 ~ ^2\" && /setblock $c$ stone"));
    }

    @Test
    void twoAndOneComponentTypes() {
        assertEquals(List.of("say 1 2"), run("#local c:column_pos = \"1 2\" && /say $c$"));
        assertTrue(errorFrom("#local c:column_pos = \"1 2 3\"").contains("2 numbers"));
        assertEquals(List.of("say 1.5 -2"), run("#local r:rotation = \"1.5 -2\" && /say $r$"));
        assertEquals(List.of("say 90"), run("#local a:angle = 90 && /say $a$"));
    }

    @Test
    void numbersAndBooleans() {
        assertEquals(List.of("say 5"), run("#local n:int = 5 && /say $n$"));
        assertTrue(errorFrom("#local n:int = 2.5").contains("whole"));
        assertEquals(List.of("say 2.5"), run("#local n:float = 2.5 && /say $n$"));
        assertTrue(errorFrom("#local n:int = \"hello\"").contains("not a number"));

        assertEquals(List.of("say yes"), run("#local b:bool = 1 > 0 && #if (b) (/say yes)"));
        assertTrue(errorFrom("#local b:bool = 5").contains("true or false"));
    }

    /**
     * The id-ish types are deliberately unchecked. Validating them needs the
     * registries, and a computed value like "minecraft:" + name is perfectly good
     * but unresolvable at assignment time — checking nothing beats rejecting
     * something correct.
     */
    @Test
    void freeFormTypesAcceptAnything() {
        assertEquals(List.of("give @s not_a_real_item"), run("#local i:item = \"not_a_real_item\" && /give @s $i$"));
        assertEquals(List.of("say hello there"), run("#local w:text = \"hello there\" && /say $w$"));
    }

    @Test
    void aListCannotWearAScalarType() {
        String error = errorFrom("#local c:blockpos = list(1, 2, 3)");
        assertTrue(error.contains("list"), error);
        assertTrue(error.contains("#foreach"), "the error should point at what a list IS for: " + error);
    }

    // ------------------------------------------------------------ bad input

    @Test
    void anUnknownTypeNamesTheRealOnes() {
        String error = errorFrom("#local c:notatype = 1");
        assertTrue(error.contains("notatype"), error);
        assertTrue(error.contains("blockpos"), "the valid keywords should be listed: " + error);
    }

    @Test
    void aColonWithNoKeywordSaysSo() {
        String error = errorFrom("#local c: = 1");
        assertTrue(error.contains("type after the colon"), error);
    }

    // ------------------------------------------------- the chat-bar scanner

    @Test
    void theScanFindsDeclarationsOnAHalfTypedLine() {
        Map<String, AliasDefinition.ParamType> found =
                VariableTypes.declaredOn("#local c:blockpos = -10 20 85 && /tp $c$");
        assertEquals(Map.of("c", AliasDefinition.ParamType.BLOCKPOS), found);
    }

    @Test
    void everyAssignmentDirectiveIsScanned() {
        Map<String, AliasDefinition.ParamType> found = VariableTypes.declaredOn(
                "#set a:int = 1 && #local b:pos = 1 2 3 && #setdefault c:column_pos = 1 2");
        assertEquals(AliasDefinition.ParamType.INT, found.get("a"));
        assertEquals(AliasDefinition.ParamType.POS, found.get("b"));
        assertEquals(AliasDefinition.ParamType.COLUMN_POS, found.get("c"));
        assertEquals(3, found.size());
    }

    /** "#set" is a prefix of "#setdefault" — it must not match inside one. */
    @Test
    void setDoesNotMatchInsideSetdefault() {
        Map<String, AliasDefinition.ParamType> found = VariableTypes.declaredOn("#setdefault s:blockpos = 1 2 3");
        assertEquals(Map.of("s", AliasDefinition.ParamType.BLOCKPOS), found);
    }

    /**
     * Mid-keystroke input must produce nothing rather than a guess. A wrong entry
     * here silently mis-sizes a suggestion mask, which is harder to notice than an
     * empty one.
     */
    @Test
    void halfTypedDeclarationsClaimNothing() {
        assertTrue(VariableTypes.declaredOn("#local c = 1 2 3").isEmpty(), "untyped");
        assertTrue(VariableTypes.declaredOn("#local c:").isEmpty(), "colon, no keyword");
        assertTrue(VariableTypes.declaredOn("#local c:bloc").isEmpty(), "keyword half typed");
        assertTrue(VariableTypes.declaredOn("#local :blockpos = 1").isEmpty(), "no name");
        assertTrue(VariableTypes.declaredOn("").isEmpty());
        assertTrue(VariableTypes.declaredOn(null).isEmpty());
    }

    @Test
    void theScanReadsBothDollarSpellings() {
        assertEquals(Map.of("c", AliasDefinition.ParamType.BLOCKPOS),
                VariableTypes.declaredOn("#local $c:blockpos$ = 1 2 3"));
        assertEquals(Map.of("c", AliasDefinition.ParamType.BLOCKPOS),
                VariableTypes.declaredOn("#local $c$:blockpos = 1 2 3"));
    }

    /** Synonyms resolve to the same type, so vec3 and pos scan identically. */
    @Test
    void synonymsScanToTheSameType() {
        assertEquals(Map.of("v", AliasDefinition.ParamType.POS),
                VariableTypes.declaredOn("#local v:vec3 = 1 2 3"));
    }

    // ------------------------------------------------------------- arities

    /**
     * The numbers that size a suggestion mask. If these are wrong, the command
     * AFTER the marker stops completing, which is exactly the bug typed variables
     * exist to fix.
     */
    @Test
    void arityMatchesHowManyTokensTheValueTakes() {
        assertEquals(3, VariableTypes.arity(AliasDefinition.ParamType.BLOCKPOS));
        assertEquals(3, VariableTypes.arity(AliasDefinition.ParamType.POS));
        assertEquals(2, VariableTypes.arity(AliasDefinition.ParamType.COLUMN_POS));
        assertEquals(2, VariableTypes.arity(AliasDefinition.ParamType.ROTATION));
        assertEquals(1, VariableTypes.arity(AliasDefinition.ParamType.ANGLE));
        assertEquals(1, VariableTypes.arity(AliasDefinition.ParamType.INT));
        assertEquals(1, VariableTypes.arity(AliasDefinition.ParamType.ITEM));
    }

    /** Every type has an arity and none of them throws — the switch has no holes. */
    @Test
    void everyTypeHasAnArity() {
        for (AliasDefinition.ParamType type : AliasDefinition.ParamType.values()) {
            assertTrue(VariableTypes.arity(type) >= 1, type + " needs at least one token");
        }
    }

    /**
     * A list is only rejected where a SHAPE was promised. text and string are
     * free-form, so the type check waves a list through — what happens next is
     * the language's ordinary rule, not the type's: a list still cannot be
     * SUBSTITUTED into a command, it has to be looped over or indexed.
     */
    @Test
    void freeFormTypesStillTakeAList() {
        assertEquals(List.of("say 1", "say 2"), run("#local t:text = list(1, 2) && #foreach $v$ in t (/say $v$)"));
        assertEquals(List.of("say 2"), run("#local t:string = list(1, 2) && /say $nth(t, 1)$"));
        // and the refusal to substitute is the list rule, unrelated to the type
        assertTrue(errorFrom("#local t:text = list(1, 2) && /say $t$").contains("list can't be inserted"));
    }

    @Test
    void theOneComponentTypesRejectNonNumbers() {
        assertTrue(errorFrom("#local a:angle = \"abc\"").contains("not a coordinate"));
        assertTrue(errorFrom("#local a:angle = \"\"").contains("empty"));
        assertTrue(errorFrom("#local f:float = \"abc\"").contains("not a number"));
    }

    @Test
    void aCoordinateThatIsNotANumberSaysWhichOne() {
        String error = errorFrom("#local c:blockpos = \"1 b 3\"");
        assertTrue(error.contains("\"b\""), error);
        assertTrue(error.contains("not a coordinate"), error);
    }

    /** The precise types point at vec(...), the whole ones at blockpos(...). */
    @Test
    void theCollapseHintNamesTheRightConstructor() {
        assertTrue(errorFrom("#local p:pos = 1 2 3").contains("vec(x, y, z)"));
        assertTrue(errorFrom("#local c:blockpos = 1 2 3").contains("blockpos(x, y, z)"));
    }

    @Test
    void dottedNamesAreScanned() {
        assertEquals(Map.of("home.base", AliasDefinition.ParamType.BLOCKPOS),
                VariableTypes.declaredOn("#local home.base:blockpos = blockpos(1, 2, 3)"));
        assertEquals(List.of("tp 1 2 3"),
                run("#local home.base:blockpos = blockpos(1, 2, 3) && /tp $home.base$"));
    }

    /**
     * The scan runs on text that stops mid-word, so every one of these is a real
     * keystroke someone passes through. None may throw, and none may guess.
     */
    @Test
    void theScanSurvivesEveryTruncation() {
        for (String truncated : List.of(
                "#local c:int = 1 && #set",          // directive word, nothing after
                "#local c:int = 1 && #set ",         // ...and a space
                "#local c:int = 1 && #set x",        // a name, no colon
                "#local c:int = 1 && #local $",      // an opening $ and no more
                "#local c:int = 1 && #set $x$",      // wrapped name, no colon
                "#local c:int = 1 && #set x:")) {    // colon, no keyword
            Map<String, AliasDefinition.ParamType> found = VariableTypes.declaredOn(truncated);
            assertEquals(Map.of("c", AliasDefinition.ParamType.INT), found,
                    "only the complete declaration should be claimed in: " + truncated);
        }
    }

    /** The list the ":" completion offers must be keywords the parser accepts. */
    @Test
    void everyOfferedKeywordParses() {
        List<String> keywords = ParamTypeDocs.keywords();
        assertTrue(keywords.contains("blockpos"), "the flagship type should be offered");
        assertFalse(keywords.contains("block_pos"),
                "synonyms still PARSE but aren't offered — two spellings of one type read as two types");
        assertFalse(keywords.contains("vec3"), "same for pos/vec3");
        assertTrue(keywords.stream().noneMatch(k -> k.equals("choice")),
                "choice has no keyword — you write the options themselves");
        for (String keyword : keywords) {
            VariableTypes.parse(keyword); // throws if the offer is a lie
        }
    }

    /** The keyword the errors print has to be one the parser accepts back. */
    @Test
    void everyTypeKeywordRoundTrips() {
        for (AliasDefinition.ParamType type : AliasDefinition.ParamType.values()) {
            if (type == AliasDefinition.ParamType.CHOICE) {
                continue; // no keyword: you write the options themselves
            }
            String keyword = VariableTypes.keywordFor(type);
            assertEquals(type, VariableTypes.parse(keyword), keyword + " should parse back to " + type);
        }
    }
}
