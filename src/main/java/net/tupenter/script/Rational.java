package net.tupenter.script;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Exact rational number — Tupenter math never accumulates float drift. */
public final class Rational implements Comparable<Rational> {
    static final Rational ZERO = Rational.of(BigInteger.ZERO);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private Rational(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ExpressionException("Division by zero");
        }

        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }

        BigInteger gcd = numerator.gcd(denominator);
        if (gcd.signum() != 0) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        this.numerator = numerator;
        this.denominator = denominator;
    }

    static Rational of(BigInteger value) {
        return new Rational(value, BigInteger.ONE);
    }

    static Rational of(long value) {
        return of(BigInteger.valueOf(value));
    }

    /** Exactness ends where trig begins — shortest decimal repr of the double. */
    static Rational fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new ExpressionException("Result is not a finite number");
        }
        return parse(BigDecimal.valueOf(value).toPlainString());
    }

    double doubleValue() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL64).doubleValue();
    }

    Rational abs() {
        return numerator.signum() < 0 ? negate() : this;
    }

    int signum() {
        return numerator.signum();
    }

    Rational add(Rational other) {
        return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator)
        );
    }

    Rational subtract(Rational other) {
        return new Rational(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator)
        );
    }

    Rational multiply(Rational other) {
        return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    Rational divide(Rational other) {
        if (other.numerator.signum() == 0) {
            throw new ExpressionException("Division by zero");
        }
        return new Rational(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    Rational negate() {
        return new Rational(numerator.negate(), denominator);
    }

    Rational truncate() {
        return Rational.of(numerator.divide(denominator));
    }

    Rational floor() {
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        BigInteger quotient = quotientAndRemainder[0];
        if (numerator.signum() < 0 && quotientAndRemainder[1].signum() != 0) {
            quotient = quotient.subtract(BigInteger.ONE);
        }
        return Rational.of(quotient);
    }

    Rational ceil() {
        return negate().floor().negate();
    }

    Rational round() {
        return add(new Rational(BigInteger.ONE, BigInteger.TWO)).floor();
    }

    boolean isWhole() {
        return denominator.equals(BigInteger.ONE);
    }

    BigInteger wholeValue() {
        if (!isWhole()) {
            throw new ExpressionException("Expected a whole number, got " + toCommandString());
        }
        return numerator;
    }

    int toIntExact() {
        BigInteger truncated = numerator.divide(denominator);
        if (truncated.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || truncated.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ExpressionException("Result out of int range");
        }
        return truncated.intValue();
    }

    String toCommandString() {
        BigDecimal numeratorDecimal = new BigDecimal(numerator);
        BigDecimal denominatorDecimal = new BigDecimal(denominator);
        BigDecimal decimal = numeratorDecimal.divide(denominatorDecimal, 16, RoundingMode.HALF_UP)
                .stripTrailingZeros();

        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }

        return decimal.toPlainString();
    }

    static Rational parse(String token) {
        int decimalIndex = token.indexOf('.');
        if (decimalIndex < 0) {
            return Rational.of(new BigInteger(token));
        }

        String wholePart = token.substring(0, decimalIndex);
        String fractionalPart = token.substring(decimalIndex + 1);
        String digits = wholePart + fractionalPart;
        if (digits.isEmpty()) {
            throw new ExpressionException("Expected a number");
        }

        BigInteger numerator = new BigInteger(digits);
        BigInteger denominator = BigInteger.TEN.pow(fractionalPart.length());
        return new Rational(numerator, denominator);
    }

    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Rational other
                && numerator.equals(other.numerator)
                && denominator.equals(other.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    @Override
    public String toString() {
        return toCommandString();
    }
}
