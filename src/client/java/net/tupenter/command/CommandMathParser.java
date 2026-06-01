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

    public static String evaluateExpressionAsCommandValue(String expression) {
        return parseExpression(expression).toCommandString();
    }

    private static String evaluateMarkedSegment(String originalSegment, String expression) {
        try {
            return evaluateExpressionAsCommandValue(expression);
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

            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '(' || current == ')' || current == '.' || current == ',' || current == 's' || current == 'S') {
                continue;
            }

            if (Character.isLetter(current)) {
                continue;
            }

            return false;
        }

        return true;
    }

    private static String applyAutoDetectMath(String input) {
        StringBuilder result = new StringBuilder(input.length());
        int braceDepth = 0;
        int index = 0;

        while (index < input.length()) {
            char current = input.charAt(index);
            if (current == '{') {
                braceDepth++;
                result.append(current);
                index++;
                continue;
            }

            if (current == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                result.append(current);
                index++;
                continue;
            }

            if (braceDepth == 0 && isAutoDetectSpanStart(input, index)) {
                int end = findAutoDetectSpanEnd(input, index);
                result.append(evaluateAutoDetectedSpan(input.substring(index, end)));
                index = end;
                continue;
            }

            result.append(current);
            index++;
        }

        return result.toString();
    }

    private static boolean isAutoDetectSpanStart(String input, int index) {
        char current = input.charAt(index);
        if (Character.isWhitespace(current)) {
            return false;
        }

        if (Character.isDigit(current) || current == '.' || current == '(') {
            return true;
        }

        if ((current == '+' || current == '-') && hasAutoDetectValueAhead(input, index + 1)) {
            return true;
        }

        return matchesFunctionStart(input, index, "int") || matchesFunctionStart(input, index, "float");
    }

    private static int findAutoDetectSpanEnd(String input, int start) {
        int index = start;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)
                    || Character.isDigit(current)
                    || current == '+'
                    || current == '-'
                    || current == '*'
                    || current == '/'
                    || current == '('
                    || current == ')'
                    || current == '.'
                    || current == ','
                    || current == 's'
                    || current == 'S') {
                index++;
                continue;
            }

            if (matchesFunctionStart(input, index, "int")) {
                index += 3;
                continue;
            }

            if (matchesFunctionStart(input, index, "float")) {
                index += 5;
                continue;
            }

            break;
        }

        return index;
    }

    private static boolean hasAutoDetectValueAhead(String input, int index) {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }

        if (index >= input.length()) {
            return false;
        }

        char current = input.charAt(index);
        return Character.isDigit(current)
                || current == '.'
                || current == '('
                || matchesFunctionStart(input, index, "int")
                || matchesFunctionStart(input, index, "float");
    }

    private static boolean matchesFunctionStart(String input, int index, String functionName) {
        if (!input.regionMatches(true, index, functionName, 0, functionName.length())) {
            return false;
        }

        int next = index + functionName.length();
        while (next < input.length() && Character.isWhitespace(input.charAt(next))) {
            next++;
        }

        return next < input.length() && input.charAt(next) == '(';
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
                    + evaluateExpressionAsCommandValue(expression)
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

            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '(' || current == ')' || current == '.') {
                hasOperator = true;
                continue;
            }

            if (current == 's' || current == 'S') {
                hasOperator = true;
                continue;
            }

            if (current == ',') {
                hasOperator = true;
                continue;
            }

            if (Character.isLetter(current)) {
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
                if (operator == '*' || operator == '/') {
                    index++;
                    Rational right = parseFactor();
                    value = operator == '*' ? value.multiply(right) : value.divide(right);
                    continue;
                }

                if (!startsImplicitMultiplication()) {
                    return value;
                }

                Rational right = parseFactor();
                value = value.multiply(right);
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

            if (Character.isLetter(current)) {
                return parseFunctionCall();
            }

            return parseNumber();
        }

        private Rational parseFunctionCall() {
            String identifier = parseIdentifier();
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != '(') {
                throw new IllegalArgumentException("Expected '(' after function name");
            }

            index++;
            Rational value = parseExpression();
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != ')') {
                throw new IllegalArgumentException("Missing closing parenthesis");
            }
            index++;

            return switch (identifier.toLowerCase()) {
                case "int" -> value.truncate();
                case "float" -> value;
                default -> throw new IllegalArgumentException("Unsupported function: " + identifier);
            };
        }

        private String parseIdentifier() {
            int start = index;
            while (index < input.length() && Character.isLetter(input.charAt(index))) {
                index++;
            }

            if (start == index) {
                throw new IllegalArgumentException("Expected a function name");
            }

            return input.substring(start, index);
        }

        private boolean isStackSuffix(char current) {
            return current == 's' || current == 'S';
        }

        private boolean startsImplicitMultiplication() {
            if (index >= input.length()) {
                return false;
            }

            char current = input.charAt(index);
            return Character.isDigit(current)
                    || current == '.'
                    || current == '('
                    || Character.isLetter(current);
        }

        private Rational parseNumber() {
            skipWhitespace();
            int start = index;

            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }

            boolean hasDigitsBeforeDecimal = index > start;
            boolean hasDecimalPoint = index < input.length() && input.charAt(index) == '.';

            if (hasDecimalPoint) {
                index++;
                int decimalStart = index;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }

                if (!hasDigitsBeforeDecimal && decimalStart == index) {
                    throw new IllegalArgumentException("Expected a number");
                }
            } else if (!hasDigitsBeforeDecimal) {
                throw new IllegalArgumentException("Expected a number");
            }

            String token = input.substring(start, index);
            return Rational.parse(token);
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

        private Rational truncate() {
            return Rational.of(numerator.divide(denominator));
        }

        private int toIntExact() {
            BigInteger truncated = numerator.divide(denominator);
            if (truncated.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || truncated.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("Result out of int range");
            }
            return truncated.intValue();
        }

        private String toCommandString() {
            BigDecimal numeratorDecimal = new BigDecimal(numerator);
            BigDecimal denominatorDecimal = new BigDecimal(denominator);
            BigDecimal decimal = numeratorDecimal.divide(denominatorDecimal, 16, RoundingMode.HALF_UP)
                    .stripTrailingZeros();

            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }

            return decimal.toPlainString();
        }

        private static Rational parse(String token) {
            int decimalIndex = token.indexOf('.');
            if (decimalIndex < 0) {
                return Rational.of(new BigInteger(token));
            }

            String wholePart = token.substring(0, decimalIndex);
            String fractionalPart = token.substring(decimalIndex + 1);
            String digits = wholePart + fractionalPart;
            if (digits.isEmpty()) {
                throw new IllegalArgumentException("Expected a number");
            }

            BigInteger numerator = new BigInteger(digits);
            BigInteger denominator = BigInteger.TEN.pow(fractionalPart.length());
            return new Rational(numerator, denominator);
        }
    }
}
