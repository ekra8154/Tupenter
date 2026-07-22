package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** raycast(...) / raycast_block(...) — driven with a stub Raycaster + BlockReader (no live world). */
class RaycastTest {

    /** Always hits (10, 64, -3), recording that a cast reached it. */
    private static final Raycaster HIT = new Raycaster() {
        @Override
        public String fromPlayer(double maxDist) {
            return "10 64 -3";
        }

        @Override
        public String cast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
            return "10 64 -3";
        }
    };

    /** Always misses (null from both), like an empty sky or no world. */
    private static final Raycaster MISS = Raycaster.NONE;

    private static final BlockReader BLOCKS = (x, y, z) ->
            (x == 10 && y == 64 && z == -3) ? "minecraft:grass_block" : "minecraft:stone";

    private static EvalContext ctx(Raycaster raycaster) {
        return new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE, BLOCKS,
                FunctionResolver.NONE, raycaster);
    }

    private static String eval(String expression, Raycaster raycaster) {
        return ExpressionEvaluator.evaluate(expression, ctx(raycaster)).displayString();
    }

    @Test
    void fromPlayerReturnsHitPosition() {
        assertEquals("10 64 -3", eval("raycast(20)", HIT));
    }

    @Test
    void fromPlayerMissIsTheSentinel() {
        assertEquals("miss", eval("raycast(20)", MISS));
    }

    @Test
    void threeArgFormCastsAndReturnsPosition() {
        assertEquals("10 64 -3", eval("raycast(\"0 64 0\", \"1 0 0\", 30)", HIT));
        // a vec(...) origin/dir works too
        assertEquals("10 64 -3", eval("raycast(vec(0, 64, 0), vec(1, 0, 0), 30)", HIT));
    }

    @Test
    void threeArgFormMissIsTheSentinel() {
        assertEquals("miss", eval("raycast(\"0 64 0\", \"1 0 0\", 30)", MISS));
    }

    @Test
    void raycastBlockReadsTheBlockAtTheHit() {
        assertEquals("minecraft:grass_block", eval("raycast_block(20)", HIT));
    }

    @Test
    void raycastBlockMissIsTheSentinel() {
        assertEquals("miss", eval("raycast_block(20)", MISS));
    }

    @Test
    void wrongArgCountsError() {
        assertThrows(ExpressionException.class, () -> eval("raycast()", HIT));
        assertThrows(ExpressionException.class, () -> eval("raycast(1, 2)", HIT));
        assertThrows(ExpressionException.class, () -> eval("raycast_block(1, 2)", HIT));
    }
}
