package net.tupenter.command;

import net.tupenter.TupenterMod;

import java.math.BigInteger;

public final class CommandMathParser {
    private static final char MARKER = '$';

    private CommandMathParser() {
    }

    public static String applyNumberMath(String command) {
        StringBuilder result = new StringBuilder(command.length());
        int index = 0;

        while (index < command.length()) {
            int start = command.indexOf(MARKER, index);
            if (start < 0) {
                result.append(command, index, command.length());
                break;
            }

            int end = command.indexOf(MARKER, start + 1);
            if (end < 0) {
                result.append(command, index, command.length());
                break;
            }

            result.append(command, index, start);

            String markedSegment = command.substring(start, end + 1);
            String expression = command.substring(start + 1, end);
            result.append(evaluateMarkedSegment(markedSegment, expression));

            index = end + 1;
        }

        return result.toString();
    }

    private static String evaluateMarkedSegment(String originalSegment, String expression) {
        if (!containsOnlyAllowedMathCharacters(expression)) {
            return originalSegment;
        }

        try {
            int value = new Parser(expression).parse().toIntExact();
            return Integer.toString(value);
        } catch (IllegalArgumentException ex) {
            TupenterMod.LOGGER.debug("Skipping number math for segment '{}': {}", originalSegment, ex.getMessage());
            return originalSegment;
        }
    }

    private static boolean containsOnlyAllowedMathCharacters(String expression) {
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (Character.isWhitespace(current) || Character.isDigit(current)) {
                continue;
            }

            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '(' || current == ')') {
                continue;
            }

            return false;
        }

        return true;
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Rational parse() {
            Rational value = parseExpression();
            skipWhitespace();
            if (index != input.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters");
            }
            return value;
        }

        private Rational parseExpression() {
            Rational value = parseTerm();

            while (true) {
                skipWhitespace();
                if (index >= input.length()) {
                    return value;
                }

                char operator = input.charAt(index);
                if (operator != '+' && operator != '-') {
                    return value;
                }

                index++;
                Rational right = parseTerm();
                value = operator == '+' ? value.add(right) : value.subtract(right);
            }
        }

        private Rational parseTerm() {
            Rational value = parseFactor();

            while (true) {
                skipWhitespace();
                if (index >= input.length()) {
                    return value;
                }

                char operator = input.charAt(index);
                if (operator != '*' && operator != '/') {
                    return value;
                }

                index++;
                Rational right = parseFactor();
                value = operator == '*' ? value.multiply(right) : value.divide(right);
            }
        }

        private Rational parseFactor() {
            skipWhitespace();
            if (index >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            char current = input.charAt(index);
            if (current == '-') {
                index++;
                return parseFactor().negate();
            }

            if (current == '(') {
                index++;
                Rational value = parseExpression();
                skipWhitespace();
                if (index >= input.length() || input.charAt(index) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                index++;
                return value;
            }

            return parseNumber();
        }

        private Rational parseNumber() {
            skipWhitespace();
            int start = index;

            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }

            if (start == index) {
                throw new IllegalArgumentException("Expected a number");
            }

            return Rational.of(new BigInteger(input.substring(start, index)));
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }
    }

    private static final class Rational {
        private final BigInteger numerator;
        private final BigInteger denominator;

        private Rational(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) {
                throw new IllegalArgumentException("Division by zero");
            }

            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }

            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        private static Rational of(BigInteger value) {
            return new Rational(value, BigInteger.ONE);
        }

        private Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator)
            );
        }

        private Rational subtract(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator)
            );
        }

        private Rational multiply(Rational other) {
            return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        private Rational divide(Rational other) {
            if (other.numerator.signum() == 0) {
                throw new IllegalArgumentException("Division by zero");
            }
            return new Rational(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        private Rational negate() {
            return new Rational(numerator.negate(), denominator);
        }

        private int toIntExact() {
            BigInteger truncated = numerator.divide(denominator);
            if (truncated.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || truncated.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("Result out of int range");
            }
            return truncated.intValue();
        }
    }
}
