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
 * resolver. The dist() definition here is byte-for-byte the one from the
 * in-game bug report.
 */
class UserFunctionsTest {

    /** The user's exact definition: /customfunction add dist <a:vec3> <b:vec3> = sqrt(...). */
    private static final Map<String, AliasDefinition> FUNCTIONS = Map.of(
            "dist", AliasDefinition.parse("<a:vec3> <b:vec3> = sqrt((a.x-b.x)^2 + (a.y-b.y)^2 + (a.z-b.z)^2)"),
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
        assertEquals("5", echo("$dist(\"3 4 0\", \"0 0 0\")$"));
    }

    @Test
    void distOfDottedVariableAndQuotedLiteral() {
        // the in-game repro: /echo $dist(client.pos, "0 0 0")$
        assertEquals("5", echo("$dist(client.pos, \"0 0 0\")$"));
    }

    @Test
    void distAcceptsCommaSeparatedVecLiterals() {
        assertEquals("5", echo("$dist(\"3,4,0\", \"0 0 0\")$"));
    }

    @Test
    void distWithVecBuilder() {
        assertEquals("5", echo("$dist(vec(3, 4, 0), vec(0, 0, 0))$"));
    }

    @Test
    void wrongArityIsAClearError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("dist(\"3 4 0\")", ctx()));
        assertTrue(ex.getMessage().contains("takes 2 arguments, got 1"), ex.getMessage());
    }

    @Test
    void nonVecArgIsAClearError() {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("dist(\"oops\", \"0 0 0\")", ctx()));
        assertTrue(ex.getMessage().contains("isn't a"), ex.getMessage());
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
