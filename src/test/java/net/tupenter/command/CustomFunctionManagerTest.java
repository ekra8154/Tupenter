package net.tupenter.command;

import net.tupenter.config.ConfigTestSupport;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;
import net.tupenter.script.BuiltinFunctions;
import net.tupenter.script.EvalContext;
import net.tupenter.script.MathEvaluator;
import net.tupenter.script.TagResolver;
import net.tupenter.script.BlockReader;
import net.tupenter.script.VariableProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom functions — user-defined {@code name(args)} for expressions. Stored
 * like custom commands (one "name signature = body" string, re-parsed on read),
 * with the same silent-skip risk, plus one rule of their own: a body has to
 * COMPUTE a value, never send a command.
 */
class CustomFunctionManagerTest {

    private TupenterConfig previous;

    @BeforeEach
    void isolate(@TempDir Path directory) {
        previous = ConfigTestSupport.isolate(directory);
    }

    @AfterEach
    void restore() {
        ConfigTestSupport.restore(previous);
    }

    @Test
    void aFunctionSurvivesTheConfigRoundTripAndThenEvaluates() {
        CustomFunctionManager.addFunction("mydist",
                "<a:vec3> <b:vec3> = sqrt((a.x-b.x)^2 + (a.y-b.y)^2 + (a.z-b.z)^2)");
        TupenterConfig.save();

        TupenterConfig.INSTANCE = new TupenterConfig();
        TupenterConfig.load();

        AliasDefinition reloaded = CustomFunctionManager.getFunctionMap().get("mydist");
        assertNotNull(reloaded, "the function came back");
        assertEquals(2, reloaded.params().size());

        // and it actually runs through the real evaluator + resolver
        EvalContext context = new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE,
                BlockReader.NONE, CustomFunctionManager.resolver());
        assertEquals("5", MathEvaluator.evaluateForDisplay("mydist(\"0 0 0\", \"3 4 0\")", context));
    }

    @Test
    void addRefusesADuplicateAndUpdateRefusesAMissingOne() {
        CustomFunctionManager.addFunction("half", "<n:int> = n / 2");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CustomFunctionManager.addFunction("half", "<n:int> = n / 2")).getMessage().contains("already exists"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CustomFunctionManager.updateFunction("nope", "<n:int> = n")).getMessage().contains("doesn't exist"));
    }

    @Test
    void updatingReplacesInPlace() {
        CustomFunctionManager.addFunction("f", "<n:int> = n + 1");
        CustomFunctionManager.updateFunction("f", "<n:int> = n + 2");
        assertEquals(1, TupenterConfig.INSTANCE.functions.size());
        assertEquals("n + 2", CustomFunctionManager.getFunctionMap().get("f").body());
    }

    // -------------------------------------------------------- the body rule

    /** A function computes; a command sends. The error points at the other tool. */
    @Test
    void aBodyThatSendsACommandIsRefused() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> CustomFunctionManager.addFunction("bad", "= /say hi")).getMessage();
        assertTrue(message.contains("can't be a command"), message);
        assertTrue(message.contains("/customcommand"), "it names the tool that DOES send commands: " + message);
    }

    @Test
    void anExpressionBodyAndAStatementBodyAreBothAllowed() {
        CustomFunctionManager.addFunction("plain", "<n:int> = n * 2");
        CustomFunctionManager.addFunction("clamped",
                "<x:int> = #if (x > 100) (#return 100) && #if (x < 0) (#return 0) && x");
        assertTrue(CustomFunctionManager.hasFunction("plain"));
        assertTrue(CustomFunctionManager.hasFunction("clamped"));
    }

    @Test
    void aNonStatementDirectiveBodyIsRefusedWithTheAllowedSet() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> CustomFunctionManager.addFunction("bad", "= #wait 5s")).getMessage();
        assertTrue(message.contains("#wait"), message);
        assertTrue(message.contains("#set") && message.contains("#return"), "it lists what IS allowed: " + message);
    }

    @Test
    void anEmptyBodyIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CustomFunctionManager.addFunction("empty", "=  ")).getMessage().contains("cannot be empty"));
    }

    // ------------------------------------------------------- the name rule

    /**
     * A function must not shadow a built-in — and the guarded set is derived
     * from the BuiltinFunctions registry, so it can't fall behind the real
     * function list. Every built-in name is refused.
     */
    @Test
    void noFunctionCanShadowABuiltin() {
        for (String builtin : BuiltinFunctions.NAMES) {
            String message = assertThrows(IllegalArgumentException.class,
                    () -> CustomFunctionManager.addFunction(builtin, "<n:int> = n")).getMessage();
            assertTrue(message.contains("built-in"), builtin + ": " + message);
        }
        // and the boolean literals, which aren't functions but are still owned
        assertThrows(IllegalArgumentException.class, () -> CustomFunctionManager.addFunction("true", "= 1"));
        assertThrows(IllegalArgumentException.class, () -> CustomFunctionManager.addFunction("false", "= 1"));
    }

    @Test
    void functionNamesAreLetterDigitUnderscoreOnly() {
        CustomFunctionManager.addFunction("my_fn2", "<n:int> = n");
        assertTrue(CustomFunctionManager.hasFunction("my_fn2"));
        for (String illegal : List.of("my-fn", "my.fn", "my fn", "bang!")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CustomFunctionManager.addFunction(illegal, "<n:int> = n"), illegal);
        }
    }

    // -------------------------------------------------------- silent skip

    @Test
    void anUnparseableFunctionIsSkippedAndTheRestStillLoad() {
        TupenterConfig.INSTANCE.functions = new java.util.ArrayList<>(List.of(
                "good <n:int> = n + 1",
                "nobody",                        // name only
                "broken <n:int = n",             // unclosed declaration
                "alsogood = 42"));
        assertEquals(java.util.Set.of("good", "alsogood"), CustomFunctionManager.getFunctionMap().keySet());
    }

    @Test
    void removingReportsWhetherThereWasAnythingToRemove() {
        CustomFunctionManager.addFunction("f", "= 1");
        assertTrue(CustomFunctionManager.removeFunction("f"));
        assertFalse(CustomFunctionManager.removeFunction("f"));
    }

    /** Recursion works, and the depth guard is wired from the config limit. */
    @Test
    void aRecursiveFunctionRunsThroughTheResolver() {
        CustomFunctionManager.addFunction("countdown", "<n:int> = n <= 0 ? \"done\" : countdown(n - 1)");
        EvalContext context = new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE,
                BlockReader.NONE, CustomFunctionManager.resolver());
        assertEquals("done", MathEvaluator.evaluateForDisplay("countdown(5)", context));
    }
}
