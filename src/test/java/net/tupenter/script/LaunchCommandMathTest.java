package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchCommandMathTest {

    private String eval(String expr, String look, String eye, double speed) {
        VariableProvider vars = new VariableProvider() {
            public Set<String> names() { return Set.of("client.look", "client.eye_pos", "speed"); }
            public Optional<Value> resolve(String n) {
                return switch (n.toLowerCase(java.util.Locale.ROOT)) {
                    case "client.look" -> Optional.of(Value.of(look));
                    case "client.eye_pos" -> Optional.of(Value.of(eye));
                    case "speed" -> Optional.of(Value.ofNumber((long) speed));
                    default -> Optional.empty();
                };
            }
        };
        EvalContext ctx = new EvalContext(new Random(1), vars, TagResolver.NONE, BlockReader.NONE);
        return ExpressionEvaluator.evaluate(expr, ctx).displayString();
    }

    @Test
    void theVectorLaunchExpressionsReplaceTheTrig() {
        // spawn point: eye + 1.5 in look direction
        assertEquals("11.5 64 20",
                eval("vadd(client.eye_pos, scale(client.look, 1.5))", "1 0 0", "10 64 20", 5));
        // motion NBT string: scale look by speed, componentwise, with d suffixes
        String ms = "component(scale(client.look, speed), \"x\") + \"d,\" "
                + "+ component(scale(client.look, speed), \"y\") + \"d,\" "
                + "+ component(scale(client.look, speed), \"z\") + \"d\"";
        assertEquals("5d,0d,0d", eval(ms, "1 0 0", "10 64 20", 5));
        assertEquals("0d,10d,0d", eval(ms, "0 1 0", "0 64 0", 10));
    }
}
