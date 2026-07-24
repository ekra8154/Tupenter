package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-drift contract: the {@link BuiltinFunctions} registry IS the
 * function list (the evaluator's call-decision set derives from it, the help
 * pages render from it), so it must agree exactly with what the dispatch
 * switch actually implements — and every entry must be a complete doc. Add a
 * function without documenting it, or document one that doesn't exist, and
 * this fails the build.
 */
class BuiltinFunctionsTest {

    @Test
    void registryMatchesTheDispatchSwitch() throws Exception {
        Path source = Path.of("src", "main", "java", "net", "tupenter", "script", "ExpressionEvaluator.java");
        assertTrue(Files.exists(source),
                "expected to run from the project root; can't find " + source.toAbsolutePath());
        String code = Files.readString(source);

        int start = code.indexOf("return switch (identifier.toLowerCase())");
        assertTrue(start >= 0, "dispatch switch not found in ExpressionEvaluator");
        int end = code.indexOf("default ->", start);
        assertTrue(end > start, "dispatch switch default arm not found");

        Set<String> dispatched = new TreeSet<>();
        Matcher cases = Pattern.compile("case \"([a-z0-9_]+)\"").matcher(code.substring(start, end));
        while (cases.find()) {
            dispatched.add(cases.group(1));
        }
        // pick is dispatched BEFORE the switch — its | option separators need a special parse
        assertTrue(code.contains("identifier.equalsIgnoreCase(\"pick\")"),
                "pick's pre-switch dispatch not found");
        dispatched.add("pick");

        assertEquals(dispatched, new TreeSet<>(BuiltinFunctions.NAMES),
                "BuiltinFunctions registry and the ExpressionEvaluator dispatch switch disagree — "
                        + "every function needs BOTH a case and a doc entry");
    }

    @Test
    void everyFunctionIsFullyDocumented() {
        Set<String> seen = new HashSet<>();
        for (BuiltinFunctions.Doc doc : BuiltinFunctions.ALL) {
            String name = doc.name();
            assertTrue(seen.add(name), "duplicate doc entry: " + name);
            assertEquals(name.toLowerCase(Locale.ROOT), name, name + ": names are lowercase");
            assertTrue(doc.signature().startsWith(name + "("),
                    name + ": the signature should open with the call form, got '" + doc.signature() + "'");
            assertFalse(doc.blurb().isBlank(), name + " needs a blurb");
            assertFalse(doc.detail().isEmpty(), name + " needs detail lines");
            assertTrue(doc.exampleSimple().contains(name),
                    name + ": the simple example should actually use the function");
            assertTrue(doc.exampleComposed().contains(name),
                    name + ": the composed example should actually use the function");
        }
    }

    /**
     * Every example that needs nothing but the evaluator actually RUNS.
     *
     * <p>The float(...) page advertised text-to-number conversion for a while
     * before the function could do it — and since help examples are
     * click-to-run, a wrong one is a broken promise, not a typo. World-dependent
     * examples (registry sets, block reads, entity lookups, client.* variables)
     * can't run here and are skipped; everything self-contained is checked.
     */
    @Test
    void selfContainedCalcExamplesActuallyEvaluate() {
        int checked = 0;
        for (BuiltinFunctions.Doc doc : BuiltinFunctions.ALL) {
            for (String example : java.util.List.of(doc.exampleSimple(), doc.exampleComposed())) {
                String expression = selfContainedCalc(example);
                if (expression == null) {
                    continue;
                }
                checked++;
                try {
                    ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)));
                } catch (RuntimeException broken) {
                    throw new AssertionError(doc.name() + ": documented example doesn't evaluate — "
                            + example + " → " + broken.getMessage(), broken);
                }
            }
        }
        assertTrue(checked >= 15, "expected a meaningful sample of runnable examples, got " + checked);
    }

    /** The expression of a "/calc ..." example that needs no world, else null. */
    private static String selfContainedCalc(String example) {
        if (!example.startsWith("/calc ")) {
            return null;
        }
        String expression = example.substring("/calc ".length());
        if (expression.contains("client.") || expression.contains("#")) {
            return null; // live variables and registry tags need a world
        }
        for (String needsWorld : java.util.List.of("blockset(", "itemset(", "effectset(", "entityset(",
                "block(", "raycast", "entity(", "entities(", "nearest_entity(", "keys(", "slot(")) {
            if (expression.contains(needsWorld)) {
                return null;
            }
        }
        return expression;
    }

    @Test
    void findIsCaseInsensitiveAndMissesCleanly() {
        assertNotNull(BuiltinFunctions.find("BLOCKSET"));
        assertNull(BuiltinFunctions.find("no_such_function"));
    }

    @Test
    void unknownFunctionErrorNamesTheNearMissAndTheIndex() {
        // a small typo gets a "did you mean" — and every miss points at the index
        String near = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("flor(2)", new EvalContext(new Random(1)))).getMessage();
        assertTrue(near.contains("floor"), near);
        assertTrue(near.contains("/tupenter help functions"), near);

        String far = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("zzzzzz(2)", new EvalContext(new Random(1)))).getMessage();
        assertTrue(far.contains("Unknown function: zzzzzz"), far);
        assertFalse(far.contains("did you mean"), far);
        assertTrue(far.contains("/tupenter help functions"), far);
    }
}
