package net.tupenter.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites expressions inside a command before it is sent.
 *
 * Two tiers (docs/SCRIPTING_DESIGN.md §1, §3):
 * - Explicit $...$ markers: full {@link ExpressionEvaluator} syntax. The user
 *   opted in, so failures are HARD errors — the script is rejected and
 *   nothing is sent. A literal dollar sign is written {@code \$}.
 * - Auto-detected bare spans (AUTO_DETECT mode only): legacy numeric-only
 *   grammar, soft-fail — an unparseable span is sent unchanged. Skips
 *   anything inside {...} NBT braces.
 */
public final class MathEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tupenter");
    private static final char MARKER = '$';

    private MathEvaluator() {
    }

    public static String applyNumberMath(String command, NumberMathMode mode, EvalContext context) {
        if (mode == NumberMathMode.DISABLED) {
            return command;
        }

        StringBuilder result = new StringBuilder(command.length());
        int index = 0;

        while (index < command.length()) {
            int start = indexOfUnescapedMarker(command, index);
            if (start < 0) {
                appendUnmarkedSegment(result, command.substring(index), mode);
                break;
            }

            int end = indexOfUnescapedMarker(command, start + 1);
            if (end < 0) {
                appendUnmarkedSegment(result, command.substring(index), mode);
                break;
            }

            appendUnmarkedSegment(result, command.substring(index, start), mode);

            String markedSegment = command.substring(start, end + 1);
            String expression = command.substring(start + 1, end);
            result.append(evaluateMarkedSegment(markedSegment, expression, context));

            index = end + 1;
        }

        return result.toString();
    }

    private static int indexOfUnescapedMarker(String command, int from) {
        for (int i = from; i < command.length(); i++) {
            char current = command.charAt(i);
            if (current == '\\') {
                i++; // skip the escaped character
                continue;
            }
            if (current == MARKER) {
                return i;
            }
        }
        return -1;
    }

    private static void appendUnmarkedSegment(StringBuilder result, String segment, NumberMathMode mode) {
        String processed = mode == NumberMathMode.AUTO_DETECT ? applyAutoDetectMath(segment) : segment;
        result.append(unescapeDollars(processed));
    }

    private static String unescapeDollars(String text) {
        return text.replace("\\$", "$");
    }

    private static String evaluateMarkedSegment(String originalSegment, String expression, EvalContext context) {
        try {
            // "$1$" style positional parameters: pure-digit content is a
            // variable reference when one is bound, else a number literal
            String trimmed = expression.trim();
            if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
                var bound = context.variables().resolve(trimmed);
                if (bound.isPresent()) {
                    return bound.get().substitutionString();
                }
            }
            return ExpressionEvaluator.evaluate(expression, context).substitutionString();
        } catch (IllegalArgumentException ex) {
            throw new ExpressionException("Invalid expression " + originalSegment + " — " + ex.getMessage()
                    + " (write \\$ for a literal dollar sign)");
        }
    }

    /** Legacy numeric-only evaluation, used by the auto-detect path. */
    public static int evaluateExpression(String expression) {
        return parseNumericExpression(expression).toIntExact();
    }

    /** Legacy numeric-only evaluation, used by the auto-detect path. */
    public static String evaluateExpressionAsCommandValue(String expression) {
        return parseNumericExpression(expression).toCommandString();
    }

    /** Full expression engine, for /calc and the /$ ... $ local calculator. */
    public static String evaluateForDisplay(String expression, EvalContext context) {
        return ExpressionEvaluator.evaluate(expression, context).displayString();
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
            LOGGER.debug("Skipping auto-detected number math for span '{}': {}", span, ex.getMessage());
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

    private static Rational parseNumericExpression(String expression) {
        if (!containsOnlyAllowedMathCharacters(expression)) {
            throw new ExpressionException("Expression contains unsupported characters");
        }

        return new NumericParser(expression).parse();
    }

    /** The original numeric-only grammar, kept for auto-detect spans. */
    private static final class NumericParser {
        private final String input;
        private int index;

        private NumericParser(String input) {
            this.input = input;
        }

        private Rational parse() {
            Rational value = parseExpression();
            skipWhitespace();
            if (index != input.length()) {
                throw new ExpressionException("Unexpected trailing characters");
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
                    value = value.multiply(Rational.of(64));
                    continue;
                }
                return value;
            }
        }

        private Rational parsePrimary() {
            skipWhitespace();
            if (index >= input.length()) {
                throw new ExpressionException("Unexpected end of expression");
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
                    throw new ExpressionException("Missing closing parenthesis");
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
                throw new ExpressionException("Expected '(' after function name");
            }

            index++;
            Rational value = parseExpression();
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != ')') {
                throw new ExpressionException("Missing closing parenthesis");
            }
            index++;

            return switch (identifier.toLowerCase()) {
                case "int" -> value.truncate();
                case "float" -> value;
                default -> throw new ExpressionException("Unsupported function: " + identifier);
            };
        }

        private String parseIdentifier() {
            int start = index;
            while (index < input.length() && Character.isLetter(input.charAt(index))) {
                index++;
            }

            if (start == index) {
                throw new ExpressionException("Expected a function name");
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
                    throw new ExpressionException("Expected a number");
                }
            } else if (!hasDigitsBeforeDecimal) {
                throw new ExpressionException("Expected a number");
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
}
