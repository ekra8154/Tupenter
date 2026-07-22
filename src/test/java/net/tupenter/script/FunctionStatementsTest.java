package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement-body custom functions: the {@link UserFunctions} call machinery now
 * runs a body through the Walker in function mode when it leads with a statement
 * keyword (#set/#for/#if/#return/...), while a pure-expression body stays on the
 * fast path. These tests drive the REAL resolver the game uses.
 */
class FunctionStatementsTest {

    // A solid block sits at (3, 4, 5); everything else is air — so a ray marched
    // from (0, 4, 5) along +x first hits solid at (3, 4, 5).
    private static final BlockReader STUB_BLOCKS = (x, y, z) ->
            (x == 3 && y == 4 && z == 5) ? "minecraft:stone" : "minecraft:air";

    private static final Map<String, AliasDefinition> FUNCTIONS = Map.ofEntries(
            // backward compat: a plain expression, and a boolean whose top-level && is logical-AND
            Map.entry("double", AliasDefinition.parse("<n:int> = n * 2")),
            Map.entry("inrange", AliasDefinition.parse("<x:int> = x >= 0 && x <= 100")),
            // a statement, then a trailing expression that is the result
            Map.entry("foo", AliasDefinition.parse("<n:int> = #set $x$ = n + 1 && x * 2")),
            // #return early-exits out of the loop; else the ray misses
            Map.entry("rayhit", AliasDefinition.parse("<p:vec3> <d:vec3> <n:int> = "
                    + "#set $x$ = p.x && #set $y$ = p.y && #set $z$ = p.z "
                    + "&& #for $i$ in 1..n ("
                    + "#if (block(x, y, z) != \"minecraft:air\") (#return vec(x, y, z)) "
                    + "&& #set $x$ = x + d.x && #set $y$ = y + d.y && #set $z$ = z + d.z) "
                    + "&& #return \"miss\"")),
            // same march with #while and a trailing expression instead of #return
            // $k$ is our own counter — the #while's built-in $i$ isn't visible in the condition
            Map.entry("rayhit2", AliasDefinition.parse("<p:vec3> <d:vec3> <n:int> = "
                    + "#set $x$ = p.x && #set $y$ = p.y && #set $z$ = p.z "
                    + "&& #set $hit$ = \"miss\" && #set $k$ = 0 "
                    + "&& #while (k < n && hit == \"miss\") ("
                    + "#if (block(x, y, z) != \"minecraft:air\") (#set $hit$ = vec(x, y, z)) "
                    + "&& #set $x$ = x + d.x && #set $y$ = y + d.y && #set $z$ = z + d.z && #set $k$ = k + 1) "
                    + "&& hit")),
            // writes a variable, then reads it — must not leak to the caller's session
            Map.entry("leaky", AliasDefinition.parse("<n:int> = #set $g$ = 5 && g")),
            // ends on a statement — produces no value
            Map.entry("bad", AliasDefinition.parse("<n:int> = #set $x$ = n")),
            // a command segment inside a statement body
            Map.entry("runcmd", AliasDefinition.parse("<n:int> = #set $x$ = n && /say hi")),
            // never terminates — the loop cap must stop it
            Map.entry("spin", AliasDefinition.parse("<n:int> = #while (true) (#set $x$ = 1)")),
            // recursion with a loop — must still hit the depth guard
            Map.entry("rec", AliasDefinition.parse("<n:int> = "
                    + "#if (n <= 0) (#return 0) && #for $i$ in 1..2 (#set $s$ = i) && #return rec(n - 1)")));

    private static EvalContext ctx() {
        return ctx(100);
    }

    private static EvalContext ctx(int maxLoopIterations) {
        return new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE, STUB_BLOCKS,
                UserFunctions.resolver(FUNCTIONS, maxLoopIterations));
    }

    private static String eval(String expression) {
        return ExpressionEvaluator.evaluate(expression, ctx()).displayString();
    }

    @Test
    void plainExpressionBodyStillWorks() {
        assertEquals("10", eval("double(5)"));
    }

    @Test
    void topLevelAndStaysLogicalAndForBooleanFunctions() {
        // inrange has no statement keyword — it stays ONE expression, so && is logical-AND
        assertEquals("true", eval("inrange(50)"));
        assertEquals("false", eval("inrange(200)"));
    }

    @Test
    void trailingExpressionAfterAStatementIsTheResult() {
        assertEquals("12", eval("foo(5)"));
    }

    @Test
    void returnEarlyExitsALoop() {
        // ray from (0,4,5) marching +x hits the solid block at (3,4,5)
        assertEquals("3 4 5", eval("rayhit(\"0 4 5\", \"1 0 0\", 10)"));
    }

    @Test
    void whileWithTrailingExpressionMatchesTheReturnVariant() {
        assertEquals("3 4 5", eval("rayhit2(\"0 4 5\", \"1 0 0\", 10)"));
    }

    @Test
    void functionLocalSetDoesNotLeakToTheCaller() {
        SessionVariableStore caller = new SessionVariableStore();
        EvalContext context = new EvalContext(new Random(1), caller, TagResolver.NONE, STUB_BLOCKS,
                UserFunctions.resolver(FUNCTIONS, 100));
        assertEquals("5", ExpressionEvaluator.evaluate("leaky(1)", context).displayString());
        // the function's #set $g$ = 5 must stay inside the call
        assertTrue(caller.isEmpty(), "caller session should be untouched");
        assertTrue(caller.resolve("g").isEmpty(), "$g$ leaked into the caller");
    }

    @Test
    void loopCapProducesAFunctionAppropriateError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("spin(1)", ctx(5)));
        assertTrue(ex.getMessage().contains("Max Loop Iterations"), ex.getMessage());
        assertFalse(ex.getMessage().contains("#wait"), ex.getMessage());
    }

    @Test
    void aBodyThatProducesNoValueIsAnError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("bad(1)", ctx()));
        assertTrue(ex.getMessage().contains("no value"), ex.getMessage());
    }

    @Test
    void aCommandInAFunctionBodyIsAnError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("runcmd(1)", ctx()));
        assertTrue(ex.getMessage().contains("can't run commands"), ex.getMessage());
    }

    @Test
    void returnOutsideAFunctionIsRejected() {
        ScriptParser.Options options = new ScriptParser.Options(
                true, NumberMathMode.EXPLICIT_ONLY, Map.of(), true, true, true, true, 100, 100,
                new Random(1), VariableProvider.EMPTY, new SessionVariableStore());
        ScriptParser.ParseResult result = ScriptParser.parse("#return 5", options);
        assertTrue(result.error() != null && result.error().contains("function"), String.valueOf(result.error()));
    }

    @Test
    void recursionStillRespectsTheDepthCap() {
        // rec(100) would recurse 100 deep — past the 32-frame guard
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("rec(100)", ctx()));
        assertTrue(ex.getMessage().contains("too deep"), ex.getMessage());
    }
}
