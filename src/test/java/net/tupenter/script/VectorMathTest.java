package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vector-arithmetic layer: vadd/vsub/scale/mag/dist/normalize/dot/cross.
 * vec3s are "x y z" strings in COORDINATE form (they have to substitute into
 * /tp and /setblock), so these tests check the MATH and that the string
 * round-trip prints cleanly — whole numbers as "1", and values that fit a
 * coordinate exactly (0.1, 0.5) don't drift. No world needed — every case is
 * built from vec() literals.
 */
class VectorMathTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    // --------------------------------------------------------- the products

    @Test
    void dotIsTheAlignmentOfTwoVectors() {
        assertEquals("32", calc("dot(vec(1, 2, 3), vec(4, 5, 6))"), "1*4 + 2*5 + 3*6");
        assertEquals("0", calc("dot(vec(1, 0, 0), vec(0, 1, 0))"), "perpendicular");
        assertEquals("1", calc("dot(vec(1, 0, 0), vec(1, 0, 0))"), "same unit");
        assertEquals("-1", calc("dot(vec(1, 0, 0), vec(-1, 0, 0))"), "opposite");
        // the "am I facing it" sign
        assertTrue(Double.parseDouble(calc("dot(vec(0, 0, 1), vec(1, 0, 5))")) > 0, "mostly same way");
        assertTrue(Double.parseDouble(calc("dot(vec(0, 0, 1), vec(1, 0, -5))")) < 0, "mostly opposite");
    }

    @Test
    void dotUsesExactRationalArithmeticNotDoubles() {
        // 0.1/0.2/0.3 survive the coordinate round-trip exactly (1/10, 2/10, 3/10),
        // so the dot is exactly 6 — a double would land on 5.999... or 6.0000...1
        assertEquals("6", calc("dot(vec(0.1, 0.2, 0.3), vec(10, 10, 10))"), "1 + 2 + 3, no drift");
        assertEquals("32", calc("dot(vec(1, 2, 3), vec(4, 5, 6))"));
    }

    /**
     * Honest about the representation: a vec3 is a COORDINATE string, ~16
     * significant digits, so a component that isn't an exact decimal (1/3)
     * comes back rounded. This is correct — Minecraft coordinates are decimal —
     * and it's pinned so no one "fixes" it into a surprise.
     */
    @Test
    void aVecHoldsCoordinatePrecisionNotArbitraryFractions() {
        assertEquals("0.3333333333333333 0 0", calc("vec(1/3, 0, 0)"), "1/3 becomes a coordinate");
        assertEquals("0.5 0 0", calc("vec(1/2, 0, 0)"), "but exact decimals are exact");
    }

    @Test
    void crossIsPerpendicularAndOrderSensitive() {
        assertEquals("0 0 1", calc("cross(vec(1, 0, 0), vec(0, 1, 0))"), "x cross y = z");
        assertEquals("0 0 -1", calc("cross(vec(0, 1, 0), vec(1, 0, 0))"), "y cross x = -z");
        assertEquals("1 0 0", calc("cross(vec(0, 1, 0), vec(0, 0, 1))"), "y cross z = x");
        // the result is perpendicular to both inputs — dot with either is 0
        assertEquals("0", calc("dot(vec(1, 2, 3), cross(vec(1, 2, 3), vec(4, 5, 6)))"));
        assertEquals("0", calc("dot(vec(4, 5, 6), cross(vec(1, 2, 3), vec(4, 5, 6)))"));
    }

    // ------------------------------------------------------- the measures

    @Test
    void distAndMagAgreeOnPythagoreanTriples() {
        assertEquals("5", calc("dist(vec(0, 0, 0), vec(3, 4, 0))"), "3-4-5");
        assertEquals("5", calc("mag(vec(3, 4, 0))"));
        assertEquals("7", calc("mag(vec(2, 3, 6))"), "2-3-6-7 triple");
        assertEquals("0", calc("dist(vec(1, 1, 1), vec(1, 1, 1))"), "a point to itself");
        assertEquals("0", calc("mag(vec(0, 0, 0))"));
        // mag(vsub(a,b)) is the same distance dist(a,b) gives
        assertEquals(calc("dist(vec(1, 2, 3), vec(4, 6, 3))"),
                calc("mag(vsub(vec(1, 2, 3), vec(4, 6, 3)))"), "two spellings of distance");
    }

    // ------------------------------------------------------- the transforms

    @Test
    void normalizeGivesAUnitVectorInTheSameDirection() {
        assertEquals("0 0 1", calc("normalize(vec(0, 0, 5))"));
        assertEquals("0.6 0.8 0", calc("normalize(vec(3, 4, 0))"), "3/5, 4/5");
        // the result has length 1 (within float tolerance)
        assertTrue(Math.abs(Double.parseDouble(calc("mag(normalize(vec(2, -3, 6)))")) - 1.0) < 1e-9);
    }

    @Test
    void normalizeOfZeroStaysZeroInsteadOfDividingByZero() {
        assertEquals("0 0 0", calc("normalize(vec(0, 0, 0))"),
                "a still player must not fault a tick script");
    }

    @Test
    void scaleStretchesEveryComponent() {
        assertEquals("10 20 30", calc("scale(vec(1, 2, 3), 10)"));
        assertEquals("-1 -2 -3", calc("scale(vec(1, 2, 3), -1)"), "negative reverses");
        assertEquals("1 2 3", calc("scale(vec(2, 4, 6), 0.5)"));
        assertEquals("0 0 0", calc("scale(vec(9, 9, 9), 0)"));
    }

    @Test
    void addAndSubtractAreComponentWise() {
        assertEquals("11 22 33", calc("vadd(vec(1, 2, 3), vec(10, 20, 30))"));
        assertEquals("9 8 7", calc("vsub(vec(10, 10, 10), vec(1, 2, 3))"));
        assertEquals("0 0 0", calc("vsub(vec(5, 6, 7), vec(5, 6, 7))"));
    }

    @Test
    void vectorArithmeticIsExactAndPrintsCleanWholes() {
        assertEquals("1 2 3", calc("scale(vec(0.1, 0.2, 0.3), 10)"), "exact-decimal components don't drift");
        assertEquals("0.3 0 0", calc("vadd(vec(0.1, 0, 0), vec(0.2, 0, 0))"), "0.1+0.2 stays exact, not 0.30000...4");
        assertEquals("2 3 4", calc("vadd(vec(1, 2, 3), vec(1, 1, 1))"), "whole results print without decimals");
    }

    // ------------------------------------------------- the patterns users write

    /**
     * The composed forms the docs teach and the rainbow ring uses: a heading, a
     * step along it, a point moved by that step, a perpendicular basis.
     */
    @Test
    void theBuildingBlocksCompose() {
        // 5 blocks straight up from the origin
        assertEquals("0 5 0", calc("vadd(vec(0, 0, 0), scale(normalize(vec(0, 10, 0)), 5))"));
        // direction from A to B, normalized
        assertEquals("1 0 0", calc("normalize(vsub(vec(10, 0, 0), vec(3, 0, 0)))"));
        // an orthonormal-ish basis: cross of two units is the third unit
        assertEquals("1", calc("mag(cross(vec(1, 0, 0), vec(0, 1, 0)))"), "unit cross unit is a unit");
    }

    // ------------------------------------------------------------ the errors

    @Test
    void wrongArgumentCountNamesTheFunctionAndShowsTheShape() {
        assertTrue(errorFrom("dot(vec(1, 2, 3))").contains("dot(a, b)"));
        assertTrue(errorFrom("cross(vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1))").contains("cross(a, b)"));
        assertTrue(errorFrom("dist(vec(1, 2, 3))").contains("dist(a, b)"));
        assertTrue(errorFrom("scale(vec(1, 2, 3))").contains("scale(v, s)"));
        assertTrue(errorFrom("vadd(vec(1, 2, 3))").contains("vadd(a, b)"));
        assertTrue(errorFrom("mag(vec(1, 2, 3), vec(4, 5, 6))").contains("mag"));
        assertTrue(errorFrom("normalize()").contains("normalize"));
    }

    @Test
    void aNonVectorArgumentSaysWhatAVecLooksLike() {
        assertTrue(errorFrom("dot(5, vec(1, 2, 3))").contains("needs a vec3"), "a bare number isn't a vec3");
        assertTrue(errorFrom("mag(\"1 2\")").contains("isn't a vec3"), "two components isn't a vec3");
        assertTrue(errorFrom("scale(\"a b c\", 2)").contains("isn't a number"), "non-numeric components");
    }

    /** A scalar where a vec belongs, and vice versa, are both caught. */
    @Test
    void scaleWantsAVecThenANumberInThatOrder() {
        assertEquals("2 4 6", calc("scale(vec(1, 2, 3), 2)"));
        assertTrue(errorFrom("scale(2, vec(1, 2, 3))").contains("needs a vec3"), "vec first, scalar second");
    }
}
