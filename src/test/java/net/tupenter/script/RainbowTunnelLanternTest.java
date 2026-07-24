package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RainbowTunnelLanternTest {

    private String eval(boolean lantern, int i, int half, int rt) {
        VariableProvider vars = new VariableProvider() {
            public Set<String> names() { return Set.of("lantern", "i", "half", "rt"); }
            public Optional<Value> resolve(String n) {
                return switch (n) {
                    case "lantern" -> Optional.of(Value.of(lantern));
                    case "i" -> Optional.of(Value.ofNumber(i));
                    case "half" -> Optional.of(Value.ofNumber(half));
                    case "rt" -> Optional.of(Value.ofNumber(rt));
                    default -> Optional.empty();
                };
            }
        };
        EvalContext ctx = new EvalContext(new Random(1), vars, TagResolver.NONE, BlockReader.NONE);
        // the else-branch (nth(blockset(...))) is unchanged from the flight-tested
        // ring; the NEW part is the ternary condition, so stub the else as "wool"
        String expr = "lantern && (i == 0 || i == half) ? \"minecraft:glowstone\" : \"wool\"";
        return ExpressionEvaluator.evaluate(expr, ctx).displayString();
    }

    @Test
    void theFoldedLanternTernaryPicksGlowstoneOnlyAtTheLanternPoints() {
        // lantern tick: points 0 and half are glowstone, others are wool
        assertEquals("minecraft:glowstone", eval(true, 0, 17, 0));
        assertEquals("minecraft:glowstone", eval(true, 17, 17, 0));
        assertEquals("wool", eval(true, 1, 17, 0)); // a non-lantern point stays wool
        // non-lantern tick: even points 0/half are wool
        assertEquals("wool", eval(false, 0, 17, 0));
        assertEquals("wool", eval(false, 17, 17, 0));
    }
}
