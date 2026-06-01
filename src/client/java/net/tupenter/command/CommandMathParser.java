package net.tupenter.command;

import java.math.BigDecimal;
import net.tupenter.TupenterMod;

import java.math.BigInteger;
import java.math.RoundingMode;
import net.tupenter.config.TupenterConfig;

public final class CommandMathParser {
    private static final char MARKER = '$';

    private CommandMathParser() {
    }

    public static String applyNumberMath(String command, TupenterConfig.NumberMathMode mode) {
        if (mode == TupenterConfig.NumberMathMode.DISABLED) {
            return command;
        }

        StringBuilder result = new StringBuilder(command.length());
        int index = 0;

        while (index < command.length()) {
            int start = command.indexOf(MARKER, index);
            if (start < 0) {
                appendUnmarkedSegment(result, command.substring(index), mode);
                break;
            }

            int end = command.indexOf(MARKER, start + 1);
            if (end < 0) {
                appendUnmarkedSegment(result, command.substring(index), mode);
                break;
            }

            appendUnmarkedSegment(result, command.substring(index, start), mode);

            String markedSegment = command.substring(start, end + 1);
            String expression = command.substring(start + 1, end);
            result.append(evaluateMarkedSegment(markedSegment, expression));

            index = end + 1;
        }

        return result.toString();
    }

    private static void appendUnmarkedSegment(StringBuilder result, String segment, TupenterConfig.NumberMathMode mode) {
        if (mode == TupenterConfig.NumberMathMode.AUTO_DETECT) {
            result.append(applyAutoDetectMath(segment));
        } else {
            result.append(segment);
        }
    }

    public static int evaluateExpression(String expression) {
        return parseExpression(expression).toIntExact();
    }

    public static String evaluateExpressionAsDecimal(String expression) {
        return parseExpression(expression).toDecimalString();
    }

    private static String evaluateMarkedSegment(String originalSegment, String expression) {
        try {
            int value = evaluateExpression(expression);
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

            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '(' || current == ')' || current == 's' || current == 'S') {
                continue;
            }

            return false;
        }

        return true;
    }

    private static String applyAutoDetectMath(String input) {
        StringBuilder result = new StringBuilder(input.length());
        int braceDepth = 0;
        int candidateStart = -1;

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);

            if (braceDepth == 0 && isAllowedAutoDetectCharacter(current)) {
                if (candidateStart < 0) {
                    candidateStart = i;
                }
            } else {
                if (candidateStart >= 0) {
                    result.append(evaluateAutoDetectedSpan(input.substring(candidateStart, i)));
                    candidateStart = -1;
                }
                result.append(current);
            }

            if (current == '{') {
                braceDepth++;
            } else if (current == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            }
        }

        if (candidateStart >= 0) {
            result.append(evaluateAutoDetectedSpan(input.substring(candidateStart)));
        }

        return result.toString();
    }

    private static boolean isAllowedAutoDetectCharacter(char current) {
        return Character.isWhitespace(current)
                || Character.isDigit(current)
                || current == '+'
                || current == '-'
                || current == '*'
                || current == '/'
                || current == '('
                || current == ')'
                || current == 's'
                || current == 'S';
    }

    private static String evaluateAutoDetectedSpan(String span) {
        int leadingWhitespace = 0;
        while (leadingWhitespace < span.length() && Character.isWhitespace(span.charAt(leadingWhitespace))) {
            leadingWhitespace++;
        }

        int trailingWhitespace = span.length();
        while (trailingWhitespace > leadingWhitespace && Character.isWhitespace(span.charAt(trailingWhitespace - 1))) {
            trailingWhitespace--;
        }

        String expression = span.substring(leadingWhitespace, trailingWhitespace);
        if (!looksLikeAutoDetectExpression(expression)) {
            return span;
        }

        try {
            return span.substring(0, leadingWhitespace)
                    + evaluateExpression(expression)
                    + span.substring(trailingWhitespace);
        } catch (IllegalArgumentException ex) {
            TupenterMod.LOGGER.debug("Skipping auto-detected number math for span '{}': {}", span, ex.getMessage());
            return span;
        }
    }

    private static boolean looksLikeAutoDetectExpression(String token) {
        boolean hasDigit = false;
        boolean hasOperator = false;

        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }

            if (Character.isDigit(current)) {
                hasDigit = true;
                continue;
            }

            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '(' || current == ')') {
                hasOperator = true;
                continue;
            }

            if (current == 's' || current == 'S') {
                hasOperator = true;
                continue;
            }

            return false;
        }

        return hasDigit && hasOperator;
    }

    private static Rational parseExpression(String expression) {
        if (!containsOnlyAllowedMathCharacters(expression)) {
            throw new IllegalArgumentException("Expression contains unsupported characters");
        }

        return new Parser(expression).parse();
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
            Rational value = parsePrimary();

            while (true) {
                skipWhitespace();
                if (index < input.length() && isStackSuffix(input.charAt(index))) {
                    index++;
                    value = value.multiply(Rational.of(BigInteger.valueOf(64)));
                    continue;
                }
                return value;
            }
        }

        private Rational parsePrimary() {
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

        private boolean isStackSuffix(char current) {
            return current == 's' || current == 'S';
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

        private String toDecimalString() {
            BigDecimal numeratorDecimal = new BigDecimal(numerator);
            BigDecimal denominatorDecimal = new BigDecimal(denominator);
            BigDecimal decimal = numeratorDecimal.divide(denominatorDecimal, 16, RoundingMode.HALF_UP)
                    .stripTrailingZeros();

            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }

            return decimal.toPlainString();
        }
    }
}
