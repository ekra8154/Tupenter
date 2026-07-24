package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corners of the expression language: the lexer's odd-but-legal inputs,
 * the list and text functions at their boundaries, and the registry-set
 * functions driven by a stub registry (they resolve tags and ids, which the
 * rest of the suite never exercised because it has no world).
 */
class EvaluatorEdgeCasesTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    // ---------------------------------------------------------- the lexer

    @Test
    void everyWayToWriteANumberParses() {
        assertEquals("0.5", calc("0.5"));
        assertEquals("0.5", calc(".5"), "a leading dot is a decimal");
        assertEquals("5", calc("5."), "a trailing dot is too");
        assertEquals("192", calc("3s"), "the stack suffix");
        assertEquals("96", calc("1.5s"));
        assertEquals("12288", calc("3ss"), "and it stacks");
        assertEquals("-4", calc("-2^2"), "^ binds tighter than unary minus");
    }

    @Test
    void anExponentNeedNotBeWhole() {
        assertEquals("2", calc("4^0.5"));
        assertEquals("0.5", calc("4^-0.5"));
    }

    /** Strings carry ids, paths and escapes — the characters ids actually contain. */
    @Test
    void stringsCarryNamespacedIdsAndEscapes() {
        assertEquals("minecraft:oak_log", calc("\"minecraft:oak_log\""));
        assertEquals("a/b.c_d", calc("\"a/b.c_d\""));
        assertEquals("say \"hi\"", calc("\"say \\\"hi\\\"\""));
        assertEquals("back\\slash", calc("\"back\\\\slash\""));
        assertEquals("a$b", calc("\"a\\$b\""));
    }

    @Test
    void unaryNotAndItsLookalikeAreToldApart() {
        assertEquals("false", calc("!true"));
        assertEquals("true", calc("!!true"));
        assertEquals("true", calc("1 != 2"), "!= is an operator, not a negated =");
        assertEquals("false", calc("!(1 != 2)"));
    }

    @Test
    void allSixComparisonsWorkOnNumbers() {
        assertEquals("true", calc("1 == 1"));
        assertEquals("true", calc("1 != 2"));
        assertEquals("true", calc("1 < 2"));
        assertEquals("true", calc("1 <= 1"));
        assertEquals("true", calc("2 > 1"));
        assertEquals("true", calc("2 >= 2"));
        assertEquals("false", calc("1 >= 2"));
        assertEquals("false", calc("2 <= 1"));
    }

    @Test
    void parenthesesNestAndMustClose() {
        assertEquals("14", calc("2(3+4)"), "implicit multiplication");
        assertEquals("24", calc("((2+2)*(3+3))"));
        assertEquals("Missing closing parenthesis", errorFrom("((1+2)"));
    }

    // ------------------------------------------------------ text functions

    @Test
    void substrClampsRatherThanThrowingAtTheEdges() {
        assertEquals("ell", calc("substr(\"hello\", 1, 3)"));
        assertEquals("ello", calc("substr(\"hello\", 1)"), "no count means to the end");
        assertEquals("hello", calc("substr(\"hello\", 0, 999)"), "an over-long count clamps");
        assertEquals("", calc("substr(\"hello\", 99)"), "a start past the end is empty, not an error");
        assertEquals("h", calc("substr(\"hello\", 0, 1)"));
    }

    @Test
    void aNegativeSubstrCountIsRefused() {
        assertTrue(errorFrom("substr(\"hello\", 0, -1)").contains("substr"), "names the function");
    }

    @Test
    void replaceNeedsSomethingToFind() {
        assertEquals("heLLo", calc("replace(\"hello\", \"l\", \"L\")"));
        assertEquals("hello", calc("replace(\"hello\", \"z\", \"L\")"), "no match changes nothing");
        assertTrue(errorFrom("replace(\"hello\", \"\", \"x\")").contains("replace"));
    }

    @Test
    void textFunctionsHandleEmptyText() {
        assertEquals("0", calc("len(\"\")"));
        assertEquals("", calc("trim(\"   \")"));
        assertEquals("", calc("upper(\"\")"));
    }

    // ------------------------------------------------------ list functions

    @Test
    void listFunctionsAgreeOnWhereThingsAre() {
        assertEquals("3", calc("len(range(1, 3))"));
        assertEquals("2", calc("nth(range(1, 3), 1)"));
        assertEquals("1", calc("indexof(range(1, 3), 2)"));
        assertEquals("-1", calc("indexof(range(1, 3), 99)"), "a miss is -1, not an error");
        assertEquals("true", calc("contains(range(1, 3), 2)"));
        assertEquals("false", calc("contains(range(1, 3), 99)"));
    }

    @Test
    void indexingOffTheEndOfAListSaysSo() {
        assertTrue(errorFrom("nth(range(1, 3), 99)").contains("nth"));
        assertTrue(errorFrom("nth(range(1, 3), -1)").contains("nth"));
    }

    @Test
    void indexofNeedsAListJustLikeNth() {
        assertTrue(errorFrom("indexof(5, 1)").contains("must be a list"));
    }

    // ------------------------------------------------- registry set functions

    /**
     * A stub registry, so the set functions can be driven without a world.
     * Two tags and the concrete ids they hold, plus a bare name that is BOTH
     * a concrete id and a tag — the case the resolution rule exists for.
     */
    private static final TagResolver REGISTRY = new TagResolver() {
        @Override
        public List<String> resolve(TagKind kind, String tagId) {
            if (tagId == null) {
                return List.of("minecraft:stone", "minecraft:ice", "minecraft:oak_log");
            }
            String full = tagId.contains(":") ? tagId : "minecraft:" + tagId;
            return switch (full) {
                case "minecraft:logs" -> List.of("minecraft:oak_log", "minecraft:birch_log");
                case "minecraft:ice" -> List.of("minecraft:ice", "minecraft:packed_ice");
                default -> List.of();
            };
        }

        @Override
        public String lookup(TagKind kind, String id) {
            return Map.of("minecraft:stone", "minecraft:stone",
                            "minecraft:ice", "minecraft:ice",
                            "minecraft:oak_log", "minecraft:oak_log")
                    .get(id.contains(":") ? id : "minecraft:" + id);
        }
    };

    private static String withRegistry(String expression) {
        return ExpressionEvaluator.evaluate(expression,
                new EvalContext(new Random(1), VariableProvider.EMPTY, REGISTRY, BlockReader.NONE,
                        FunctionResolver.NONE)).displayString();
    }

    @Test
    void aTagExpandsToItsMembersAndAnIdToItself() {
        assertEquals("(minecraft:oak_log | minecraft:birch_log)", withRegistry("blockset(\"#minecraft:logs\")"));
        assertEquals("(minecraft:stone)", withRegistry("blockset(\"minecraft:stone\")"));
        assertEquals("(minecraft:stone)", withRegistry("blockset(\"stone\")"), "the namespace defaults");
    }

    /** A bare name that is both an id and a tag means the ID; # asks for the family. */
    @Test
    void aBareNameMeansTheBlockAndTheHashMeansTheFamily() {
        assertEquals("(minecraft:ice)", withRegistry("blockset(\"ice\")"));
        assertEquals("(minecraft:ice | minecraft:packed_ice)", withRegistry("blockset(\"#ice\")"));
    }

    @Test
    void setsUnionTheirArgumentsAndDeduplicate() {
        assertEquals("(minecraft:oak_log | minecraft:birch_log | minecraft:stone)",
                withRegistry("blockset(\"#minecraft:logs\", \"minecraft:stone\")"));
        assertEquals("(minecraft:oak_log | minecraft:birch_log)",
                withRegistry("blockset(\"#minecraft:logs\", \"minecraft:oak_log\")"), "oak_log appears once");
    }

    /**
     * One string argument can carry a WHOLE set, space- or comma-separated —
     * that's what lets a <set:blockset> command parameter hold more than one
     * member, since an argument flattens to a single token.
     */
    @Test
    void oneStringArgumentCanCarryAWholeSet() {
        assertEquals("(minecraft:stone | minecraft:ice)", withRegistry("blockset(\"stone ice\")"));
        assertEquals("(minecraft:stone | minecraft:ice)", withRegistry("blockset(\"stone,ice\")"));
        assertEquals("(minecraft:stone | minecraft:oak_log | minecraft:birch_log)",
                withRegistry("blockset(\"stone #minecraft:logs\")"));
    }

    @Test
    void anEmptySetArgumentSaysWhatOneLooksLike() {
        assertTrue(errorFrom("blockset(\"\")").contains("needs a tag or id"));
        assertTrue(errorFrom("blockset(\"#\")").contains("needs a tag or id"));
    }

    @Test
    void noArgumentMeansTheWholeRegistry() {
        assertEquals("(minecraft:stone | minecraft:ice | minecraft:oak_log)", withRegistry("blockset()"));
        assertEquals("3", withRegistry("len(blockset())"));
    }

    @Test
    void aSetOfANumberIsRefusedByName() {
        assertTrue(errorFrom("blockset(5)").contains("blockset"));
    }
}
