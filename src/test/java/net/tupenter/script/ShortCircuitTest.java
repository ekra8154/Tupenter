package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ternary {@code ?:}, {@code &&}, and {@code ||} operators must short-circuit:
 * only the taken branch/operand evaluates, the other is dry-parsed. That's what
 * lets a recursion terminate (a recursive call in the untaken branch must NOT
 * fire) and lets guard patterns (x != 0 ? 10/x : 0) skip the dead divide.
 */
class ShortCircuitTest {

    private static String eval(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(42))).displayString();
    }

    // Real /customfunction bodies through the real call machinery (depth guard = 32).
    private static final Map<String, AliasDefinition> FUNCTIONS = Map.of(
            "count", AliasDefinition.parse("<n:int> = n <= 0 ? \"done\" : count(n - 1)"),
            "sum", AliasDefinition.parse("<a:int> <n:int> = n <= 0 ? a : sum(a + n, n - 1)"));

    private static EvalContext fnCtx() {
        return new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE, BlockReader.NONE,
                UserFunctions.resolver(FUNCTIONS));
    }

    private static String evalFn(String expression) {
        return ExpressionEvaluator.evaluate(expression, fnCtx()).displayString();
    }

    @Test
    void ternaryShortCircuitsRecursion() {
        // untaken branch's recursive call must not fire → base case terminates,
        // NOT "Function recursion too deep"
        assertEquals("done", evalFn("count(5)"));
        assertEquals("15", evalFn("sum(0, 5)"));
    }

    @Test
    void ternaryGuardsItsUntakenBranch() {
        // the dead branch's divide-by-zero must never happen
        assertEquals("42", eval("5 == 0 ? (10 / 0) : 42"));
        assertEquals("7", eval("1 == 1 ? 7 : (10 / 0)"));
    }

    @Test
    void andShortCircuits() {
        assertEquals("false", eval("false && (10 / 0 > 1)"));
        assertEquals("false", eval("true && false"));
        assertEquals("true", eval("true && true"));
    }

    @Test
    void orShortCircuits() {
        assertEquals("true", eval("true || (10 / 0 > 1)"));
        assertEquals("true", eval("false || true"));
    }

    @Test
    void normalCasesStillReturnTheRightBranch() {
        assertEquals("a", eval("3 > 2 ? \"a\" : \"b\""));
        assertEquals("b", eval("2 > 3 ? \"a\" : \"b\""));
        // nested: the outer takes true, the inner (2>3) takes false → "y"
        assertEquals("y", eval("1 > 0 ? (2 > 3 ? \"x\" : \"y\") : \"z\""));
    }

    @Test
    void untakenBranchDoesNotResolveVariables() {
        // a provider that blows up if resolve() is ever touched — the dead branch
        // references a variable, and reaching it would throw here (not the clean
        // ExpressionException a real miss would), proving no resolution occurred
        VariableProvider landmine = new VariableProvider() {
            @Override
            public Set<String> names() {
                return Set.of();
            }

            @Override
            public Optional<Value> resolve(String name) {
                throw new AssertionError("dead branch resolved variable '" + name + "'");
            }
        };
        EvalContext ctx = new EvalContext(new Random(1), landmine);
        assertEquals("7", ExpressionEvaluator.evaluate("1 == 1 ? 7 : missing", ctx).displayString());
        assertEquals("9", ExpressionEvaluator.evaluate("0 == 1 ? missing : 9", ctx).displayString());
    }
}
