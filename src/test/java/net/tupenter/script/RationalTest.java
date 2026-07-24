package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number type the whole language is built on. Its happy path is covered
 * everywhere (every arithmetic test in the suite runs through it); what wasn't
 * covered were the edges — the errors a user can actually reach by typing
 * something reasonable, and the normalization that makes "exact" mean anything.
 */
class RationalTest {

    private static String calc(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1))).displayString();
    }

    private static String errorFrom(String expression) {
        return assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)))).getMessage();
    }

    // ------------------------------------------------------- reachable errors

    /** /calc 1/0 is a thing a user types. It has to say so, not produce Infinity. */
    @Test
    void divisionByZeroIsNamed() {
        assertEquals("Division by zero", errorFrom("1/0"));
        assertEquals("Division by zero", errorFrom("1 % 0"));
        assertEquals("Division by zero", errorFrom("0/0"));
        assertEquals("Division by zero", errorFrom("5 / (3 - 3)"));
    }

    @Test
    void aNonFiniteResultIsRefusedRatherThanStored() {
        String message = assertThrows(ExpressionException.class,
                () -> Rational.fromDouble(Double.POSITIVE_INFINITY)).getMessage();
        assertEquals("Result is not a finite number", message);
        assertThrows(ExpressionException.class, () -> Rational.fromDouble(Double.NaN));
    }

    /**
     * Loop counts and list indices go through toIntExact, so a number too big
     * to be one has to say that rather than wrap around to a negative.
     */
    @Test
    void anOutOfRangeIntSaysSoInsteadOfWrapping() {
        Rational tooBig = Rational.of(new BigInteger("99999999999"));
        assertEquals("Result out of int range",
                assertThrows(ExpressionException.class, tooBig::toIntExact).getMessage());
        Rational tooSmall = Rational.of(new BigInteger("-99999999999"));
        assertThrows(ExpressionException.class, tooSmall::toIntExact);

        // the boundaries themselves are fine
        assertEquals(Integer.MAX_VALUE, Rational.of(Integer.MAX_VALUE).toIntExact());
        assertEquals(Integer.MIN_VALUE, Rational.of(Integer.MIN_VALUE).toIntExact());
    }

    @Test
    void toIntExactTruncatesTowardZero() {
        assertEquals(3, Rational.parse("3.9").toIntExact());
        assertEquals(-3, Rational.parse("-3.9").toIntExact());
    }

    @Test
    void aWholeNumberIsRequiredWhereOneIsMeant() {
        String message = assertThrows(ExpressionException.class, () -> Rational.parse("2.5").wholeValue()).getMessage();
        assertTrue(message.contains("Expected a whole number"), message);
        assertTrue(message.contains("2.5"), "the error shows the value that wasn't whole: " + message);
        assertEquals(BigInteger.TWO, Rational.parse("2").wholeValue());
    }

    @Test
    void aBareDecimalPointIsNotANumber() {
        assertEquals("Expected a number", assertThrows(ExpressionException.class, () -> Rational.parse(".")).getMessage());
    }

    // ------------------------------------------------------- normalization

    /**
     * "Exact" only means something if equal values are the same value. Every
     * rational is stored reduced with a positive denominator, so 2/4 IS 1/2 and
     * a negative sign always lives on the numerator.
     */
    @Test
    void equalValuesAreTheSameValueHoweverTheyWereWritten() {
        Rational half = Rational.parse("1").divide(Rational.of(2));
        assertEquals(half, Rational.parse("2").divide(Rational.of(4)));
        assertEquals(half, Rational.parse("0.5"));
        assertEquals(half.hashCode(), Rational.parse("0.5").hashCode());

        // one bucket in a hash set, not three
        assertEquals(1, new java.util.HashSet<>(
                java.util.List.of(half, Rational.parse("2").divide(Rational.of(4)), Rational.parse("0.5"))).size());
        assertNotEquals(half, Rational.of(2));
        assertNotEquals(half, "0.5");
    }

    @Test
    void aNegativeDenominatorNormalizesOntoTheNumerator() {
        Rational fromNegativeDenominator = Rational.of(1).divide(Rational.of(-3));
        assertEquals(Rational.of(-1).divide(Rational.of(3)), fromNegativeDenominator);
        assertEquals("-0.3333333333333333", fromNegativeDenominator.toCommandString());
        assertEquals("-1/3", fromNegativeDenominator.toExactString());
        assertEquals(-1, fromNegativeDenominator.signum());

        // and two negatives cancel rather than stacking
        assertEquals(Rational.of(1).divide(Rational.of(3)), Rational.of(-1).divide(Rational.of(-3)));
    }

    @Test
    void zeroIsZeroHoweverItArrives() {
        assertEquals(Rational.of(0), Rational.parse("0.0"));
        assertEquals(Rational.of(0), Rational.of(0).divide(Rational.of(-7)));
        assertEquals("0", Rational.of(0).toExactString());
        assertEquals(0, Rational.of(0).signum());
    }

    // ------------------------------------------------------------- exactness

    /**
     * The claim the docs make in so many words: no float drift, ever. A double
     * would have failed the first of these.
     */
    @Test
    void arithmeticDoesNotDrift() {
        assertEquals("1", calc("1/3 * 3"));
        assertEquals("1", calc("0.1 + 0.2 + 0.7"));
        assertEquals("0", calc("(0.1 + 0.2) - 0.3"));
        assertEquals("1", calc("1/7 * 7"));
        assertEquals("100", calc("(1/3 + 1/6 + 1/2) * 100"));
    }

    /** toExactString is what persistence writes; toCommandString is what a server sees. */
    @Test
    void theTwoStringFormsSayDifferentThings() {
        Rational third = Rational.of(1).divide(Rational.of(3));
        assertEquals("1/3", third.toExactString(), "storage keeps the fraction");
        assertEquals("0.3333333333333333", third.toCommandString(), "a command needs a decimal a server accepts");

        Rational whole = Rational.of(42);
        assertEquals("42", whole.toExactString(), "a whole number is not written as 42/1");
        assertEquals("42", whole.toCommandString());
    }
}
