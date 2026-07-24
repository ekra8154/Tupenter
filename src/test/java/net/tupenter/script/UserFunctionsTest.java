package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the REAL user-function call machinery ({@link UserFunctions}) —
 * the code the client's /customfunction resolver runs — not a stand-in
 * resolver. The mydist() definition here is byte-for-byte the one from the
 * in-game bug report.
 */
class UserFunctionsTest {

    /** The user's exact definition: /customfunction add mydist <a:vec3> <b:vec3> = sqrt(...). */
    private static final Map<String, AliasDefinition> FUNCTIONS = Map.of(
            "mydist", AliasDefinition.parse("<a:vec3> <b:vec3> = sqrt((a.x-b.x)^2 + (a.y-b.y)^2 + (a.z-b.z)^2)"),
            "bad", AliasDefinition.parse("= nosuchfn(1)"));

    private static final VariableProvider CLIENT_POS = new VariableProvider() {
        @Override
        public Set<String> names() {
            return Set.of("client.pos");
        }

        @Override
        public Optional<Value> resolve(String n) {
            return n.equalsIgnoreCase("client.pos") ? Optional.of(Value.of("3 4 0")) : Optional.empty();
        }
    };

    private static EvalContext ctx() {
        return new EvalContext(new Random(1), CLIENT_POS, TagResolver.NONE, BlockReader.NONE,
                UserFunctions.resolver(FUNCTIONS));
    }

    private static String echo(String line) {
        // the same path /echo takes: find $...$ markers, evaluate, substitute
        return MathEvaluator.applyNumberMath(line, NumberMathMode.EXPLICIT_ONLY, ctx());
    }

    @Test
    void distOfTwoQuotedLiterals() {
        assertEquals("5", echo("$mydist(\"3 4 0\", \"0 0 0\")$"));
    }

    @Test
    void distOfDottedVariableAndQuotedLiteral() {
        // the in-game repro: /echo $mydist(client.pos, "0 0 0")$
        assertEquals("5", echo("$mydist(client.pos, \"0 0 0\")$"));
    }

    @Test
    void distWithExplicitlyWrappedVariableArg() {
        // /echo $mydist($client.pos$, "0 0 0")$ — the inner $...$ is explicit
        // wrapping; the marker scan must extend past it, not close at it
        assertEquals("5", echo("$mydist($client.pos$, \"0 0 0\")$"));
    }

    @Test
    void distAcceptsCommaSeparatedVecLiterals() {
        assertEquals("5", echo("$mydist(\"3,4,0\", \"0 0 0\")$"));
    }

    @Test
    void distWithVecBuilder() {
        assertEquals("5", echo("$mydist(vec(3, 4, 0), vec(0, 0, 0))$"));
    }

    @Test
    void wrongArityIsAClearError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("mydist(\"3 4 0\")", ctx()));
        assertTrue(ex.getMessage().contains("takes 2 arguments, got 1"), ex.getMessage());
    }

    @Test
    void nonVecArgIsAClearError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("mydist(\"oops\", \"0 0 0\")", ctx()));
        assertTrue(ex.getMessage().contains("isn't a"), ex.getMessage());
    }

    /**
     * The tuple param types bind accessors as well as the value: a &lt;p:pos&gt;
     * gives the body p AND p.x/p.y/p.z. Each type binds a different set of
     * axes, and passing something with the wrong number of parts says how many
     * it wanted and shows both ways to write one.
     */
    @Test
    void eachTupleTypeBindsItsOwnAxes() {
        Map<String, AliasDefinition> tuples = Map.of(
                "col", AliasDefinition.parse("<c:column_pos> = c.x + c.z"),
                "aim", AliasDefinition.parse("<r:rotation> = r.yaw + r.pitch"),
                "high", AliasDefinition.parse("<p:blockpos> = p.y"),
                "plain", AliasDefinition.parse("<n:int> = n + 1"));
        EvalContext context = new EvalContext(new java.util.Random(1), VariableProvider.EMPTY,
                TagResolver.NONE, BlockReader.NONE, UserFunctions.resolver(tuples));

        assertEquals("30", ExpressionEvaluator.evaluate("col(\"10 20\")", context).displayString());
        assertEquals("105", ExpressionEvaluator.evaluate("aim(\"90 15\")", context).displayString());
        assertEquals("64", ExpressionEvaluator.evaluate("high(\"0 64 0\")", context).displayString());
        // a non-tuple type binds as-is, with no accessors involved
        assertEquals("6", ExpressionEvaluator.evaluate("plain(5)", context).displayString());

        String wrongParts = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("col(\"1 2 3\")", context)).getMessage();
        assertTrue(wrongParts.contains("2-part"), wrongParts);
        assertTrue(wrongParts.contains("needs 2 numbers"), wrongParts);
    }

    /** The arity message reads naturally at one argument as well as many. */
    @Test
    void theArityMessageIsGrammaticalForASingleArgument() {
        Map<String, AliasDefinition> one = Map.of("twice", AliasDefinition.parse("<n:int> = n * 2"));
        EvalContext context = new EvalContext(new java.util.Random(1), VariableProvider.EMPTY,
                TagResolver.NONE, BlockReader.NONE, UserFunctions.resolver(one));
        String message = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("twice(1, 2)", context)).getMessage();
        assertTrue(message.contains("takes 1 argument, got 2"), message);
    }

    @Test
    void bodyFailuresAreAttributedToTheFunction() {
        // a body that can't evaluate must say WHICH function's body failed,
        // not blame the caller's outer $...$ marker
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("bad()", ctx()));
        assertTrue(ex.getMessage().startsWith("in bad() body — "), ex.getMessage());
    }
}
