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
