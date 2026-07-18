package net.tupenter.script;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Expression engine v2 (docs/SCRIPTING_DESIGN.md §5): exact-rational math plus
 * strings, booleans, comparisons, ternaries, and functions (int, float, rand,
 * pick). Serves $...$ markers, /calc, and — in later phases — directive
 * conditions.
 *
 * Precedence, loosest first:
 *   cond ? a : b   →   ||   →   &&   →   ! (unary)   →   == != < <= > >=
 *   →   + -   →   * / and implicit multiplication   →   unary -, 's' stack
 *   suffix (×64)   →   literals, parens, functions
 *
 * Note: both ternary branches are evaluated (values, not lazy AST). Revisit
 * when variables land in Phase 3.
 */
final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    static Value evaluate(String expression, EvalContext context) {
        Parser parser = new Parser(expression, context);
        Value value = parser.parseTernary();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new ExpressionException("Unexpected '" + parser.rest() + "'");
        }
        return value;
    }

    private static final class Parser {
        private final String input;
        private final EvalContext context;
        private int index;

        private Parser(String input, EvalContext context) {
            this.input = input;
            this.context = context;
        }

        private Value parseTernary() {
            Value condition = parseOr();
            skipWhitespace();
            if (!atEnd() && peek() == '?') {
                index++;
                Value whenTrue = parseTernary();
                skipWhitespace();
                if (atEnd() || peek() != ':') {
                    throw new ExpressionException("Expected ':' in condition ? a : b");
                }
                index++;
                Value whenFalse = parseTernary();
                return asBool(condition, "before '?'") ? whenTrue : whenFalse;
            }
            return condition;
        }

        private Value parseOr() {
            Value value = parseAnd();
            while (true) {
                skipWhitespace();
                if (matches("||")) {
                    index += 2;
                    Value right = parseAnd();
                    value = new Value.BoolValue(asBool(value, "left of ||") | asBool(right, "right of ||"));
                    continue;
                }
                return value;
            }
        }

        private Value parseAnd() {
            Value value = parseComparison();
            while (true) {
                skipWhitespace();
                if (matches("&&")) {
                    index += 2;
                    Value right = parseComparison();
                    value = new Value.BoolValue(asBool(value, "left of &&") & asBool(right, "right of &&"));
                    continue;
                }
                return value;
            }
        }

        private Value parseComparison() {
            Value left = parseAdditive();
            skipWhitespace();

            String op = null;
            for (String candidate : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (matches(candidate)) {
                    op = candidate;
                    break;
                }
            }
            if (op == null) {
                return left;
            }
            index += op.length();
            Value right = parseAdditive();
            return new Value.BoolValue(compare(left, op, right));
        }

        private boolean compare(Value left, String op, Value right) {
            if (left instanceof Value.NumberValue l && right instanceof Value.NumberValue r) {
                int cmp = l.value().compareTo(r.value());
                return switch (op) {
                    case "==" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    default -> throw new ExpressionException("Unknown comparison: " + op);
                };
            }

            if (left instanceof Value.StringValue l && right instanceof Value.StringValue r) {
                return switch (op) {
                    case "==" -> l.value().equals(r.value());
                    case "!=" -> !l.value().equals(r.value());
                    default -> throw new ExpressionException("'" + op + "' only compares numbers; use == or != for text");
                };
            }

            if (left instanceof Value.BoolValue l && right instanceof Value.BoolValue r) {
                return switch (op) {
                    case "==" -> l.value() == r.value();
                    case "!=" -> l.value() != r.value();
                    default -> throw new ExpressionException("'" + op + "' only compares numbers");
                };
            }

            throw new ExpressionException("Cannot compare " + typeName(left) + " with " + typeName(right));
        }

        private Value parseAdditive() {
            Value value = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    return value;
                }

                char operator = peek();
                if (operator == '+') {
                    index++;
                    Value right = parseMultiplicative();
                    value = addOrConcat(value, right);
                    continue;
                }

                if (operator == '-') {
                    index++;
                    Value right = parseMultiplicative();
                    value = new Value.NumberValue(asNumber(value, "left of -").subtract(asNumber(right, "right of -")));
                    continue;
                }

                return value;
            }
        }

        private Value addOrConcat(Value left, Value right) {
            if (left instanceof Value.StringValue || right instanceof Value.StringValue) {
                return new Value.StringValue(left.displayString() + right.displayString());
            }
            return new Value.NumberValue(asNumber(left, "left of +").add(asNumber(right, "right of +")));
        }

        private Value parseMultiplicative() {
            Value value = parseUnary();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    return value;
                }

                char operator = peek();
                if (operator == '*' || operator == '/') {
                    // "||" is handled a level up; a lone '/' here is division
                    index++;
                    Value right = parseUnary();
                    Rational l = asNumber(value, "left of " + operator);
                    Rational r = asNumber(right, "right of " + operator);
                    value = new Value.NumberValue(operator == '*' ? l.multiply(r) : l.divide(r));
                    continue;
                }

                if (startsImplicitMultiplication()) {
                    Value right = parseUnary();
                    value = new Value.NumberValue(asNumber(value, "left of implicit multiplication")
                            .multiply(asNumber(right, "right of implicit multiplication")));
                    continue;
                }

                return value;
            }
        }

        private boolean startsImplicitMultiplication() {
            if (atEnd()) {
                return false;
            }
            char current = peek();
            return Character.isDigit(current)
                    || current == '.'
                    || current == '('
                    || Character.isLetter(current);
        }

        private Value parseUnary() {
            skipWhitespace();
            if (atEnd()) {
                throw new ExpressionException("Unexpected end of expression");
            }

            char current = peek();
            if (current == '-') {
                index++;
                Value value = parseUnary();
                return new Value.NumberValue(asNumber(value, "after unary -").negate());
            }

            if (current == '!' && !matches("!=")) {
                index++;
                Value value = parseUnary();
                return new Value.BoolValue(!asBool(value, "after !"));
            }

            return parsePostfix();
        }

        private Value parsePostfix() {
            Value value = parsePrimary();
            while (true) {
                skipWhitespace();
                if (!atEnd() && (peek() == 's' || peek() == 'S')) {
                    index++;
                    value = new Value.NumberValue(asNumber(value, "before stack suffix 's'").multiply(Rational.of(64)));
                    continue;
                }
                return value;
            }
        }

        private Value parsePrimary() {
            skipWhitespace();
            if (atEnd()) {
                throw new ExpressionException("Unexpected end of expression");
            }

            char current = peek();
            if (current == '(') {
                index++;
                Value value = parseTernary();
                skipWhitespace();
                if (atEnd() || peek() != ')') {
                    throw new ExpressionException("Missing closing parenthesis");
                }
                index++;
                return value;
            }

            if (current == '"') {
                return parseStringLiteral();
            }

            if (current == '$') {
                // $name$ variable reference — same look as everywhere else in
                // Tupenter. $1$..$n$ are positional custom-command parameters.
                index++;
                String name = peekIsDigit() ? parseDigits() : parseIdentifier();
                skipWhitespace();
                if (atEnd() || peek() != '$') {
                    throw new ExpressionException("Expected closing $ after variable name '" + name + "'");
                }
                index++;
                return resolveVariable(name);
            }

            if (Character.isLetter(current)) {
                return parseFunctionCall();
            }

            return parseNumber();
        }

        private Value parseStringLiteral() {
            index++; // opening quote
            StringBuilder builder = new StringBuilder();
            while (!atEnd()) {
                char current = input.charAt(index);
                if (current == '\\' && index + 1 < input.length()) {
                    builder.append(input.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == '"') {
                    index++;
                    return new Value.StringValue(builder.toString());
                }
                builder.append(current);
                index++;
            }
            throw new ExpressionException("Unterminated string — missing closing \"");
        }

        private Value parseFunctionCall() {
            String identifier = parseIdentifier();
            if (identifier.equalsIgnoreCase("true")) {
                return new Value.BoolValue(true);
            }
            if (identifier.equalsIgnoreCase("false")) {
                return new Value.BoolValue(false);
            }
            skipWhitespace();
            if (atEnd() || peek() != '(') {
                // bare identifier → variable reference
                return resolveVariable(identifier);
            }
            index++; // consume '('

            if (identifier.equalsIgnoreCase("pick")) {
                return parsePick();
            }

            List<Value> args = parseFunctionArgs();
            return switch (identifier.toLowerCase()) {
                case "int" -> new Value.NumberValue(asNumber(single(args, "int"), "int(...)").truncate());
                case "float" -> new Value.NumberValue(asNumber(single(args, "float"), "float(...)"));
                case "abs" -> new Value.NumberValue(asNumber(single(args, "abs"), "abs(...)").abs());
                case "floor" -> new Value.NumberValue(asNumber(single(args, "floor"), "floor(...)").floor());
                case "ceil" -> new Value.NumberValue(asNumber(single(args, "ceil"), "ceil(...)").ceil());
                case "round" -> new Value.NumberValue(asNumber(single(args, "round"), "round(...)").round());
                case "min" -> minMax("min", args, true);
                case "max" -> minMax("max", args, false);
                case "len" -> len(args);
                case "randf" -> randf(args);
                // trig takes DEGREES — Minecraft rotations (client.yaw/pitch) are degrees
                case "sin" -> mathFunction("sin", args, degrees -> Math.sin(Math.toRadians(degrees)));
                case "cos" -> mathFunction("cos", args, degrees -> Math.cos(Math.toRadians(degrees)));
                case "tan" -> mathFunction("tan", args, degrees -> Math.tan(Math.toRadians(degrees)));
                case "sqrt" -> sqrt(args);
                case "rand" -> rand(args);
                case "range" -> range(args);
                default -> throw new ExpressionException("Unknown function: " + identifier);
            };
        }

        private Value minMax(String name, List<Value> args, boolean min) {
            if (args.isEmpty()) {
                throw new ExpressionException(name + "(...) needs at least one number");
            }
            Rational best = asNumber(args.get(0), name + "(...)");
            for (int i = 1; i < args.size(); i++) {
                Rational candidate = asNumber(args.get(i), name + "(...)");
                if (min ? candidate.compareTo(best) < 0 : candidate.compareTo(best) > 0) {
                    best = candidate;
                }
            }
            return new Value.NumberValue(best);
        }

        private Value len(List<Value> args) {
            Value value = single(args, "len");
            if (value instanceof Value.ListValue list) {
                return Value.ofNumber(list.values().size());
            }
            if (value instanceof Value.StringValue string) {
                return Value.ofNumber(string.value().length());
            }
            throw new ExpressionException("len(...) takes a list or text");
        }

        private Value randf(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("randf(min, max) takes exactly two arguments");
            }
            double min = asNumber(args.get(0), "randf min").doubleValue();
            double max = asNumber(args.get(1), "randf max").doubleValue();
            if (min > max) {
                throw new ExpressionException("randf(min, max): min is greater than max");
            }
            return new Value.NumberValue(Rational.fromDouble(min + context.random().nextDouble() * (max - min)));
        }

        private Value mathFunction(String name, List<Value> args, java.util.function.DoubleUnaryOperator operation) {
            double input = asNumber(single(args, name), name + "(...)").doubleValue();
            return new Value.NumberValue(Rational.fromDouble(operation.applyAsDouble(input)));
        }

        private Value sqrt(List<Value> args) {
            Rational value = asNumber(single(args, "sqrt"), "sqrt(...)");
            if (value.signum() < 0) {
                throw new ExpressionException("sqrt of a negative number");
            }
            return new Value.NumberValue(Rational.fromDouble(Math.sqrt(value.doubleValue())));
        }

        /** range(start, stop[, step]) — inclusive whole-number list for #foreach. */
        private Value range(List<Value> args) {
            if (args.size() != 2 && args.size() != 3) {
                throw new ExpressionException("range(start, stop) or range(start, stop, step)");
            }
            BigInteger start = asNumber(args.get(0), "range start").wholeValue();
            BigInteger stop = asNumber(args.get(1), "range stop").wholeValue();
            BigInteger step;
            if (args.size() == 3) {
                step = asNumber(args.get(2), "range step").wholeValue();
                if (step.signum() == 0) {
                    throw new ExpressionException("range step can't be 0");
                }
                if (start.compareTo(stop) != 0 && step.signum() != stop.subtract(start).signum()) {
                    throw new ExpressionException("range step goes the wrong way (start " + start + ", stop " + stop + ", step " + step + ")");
                }
            } else {
                step = start.compareTo(stop) <= 0 ? BigInteger.ONE : BigInteger.valueOf(-1);
            }

            List<Value> values = new ArrayList<>();
            BigInteger current = start;
            while (step.signum() > 0 ? current.compareTo(stop) <= 0 : current.compareTo(stop) >= 0) {
                values.add(new Value.NumberValue(Rational.of(current)));
                if (values.size() > 100000) {
                    throw new ExpressionException("range is too large");
                }
                current = current.add(step);
            }
            return new Value.ListValue(values);
        }

        /** Args already past '('; consumes through the closing ')'. */
        private List<Value> parseFunctionArgs() {
            List<Value> args = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ')') {
                index++;
                return args;
            }

            while (true) {
                args.add(parseTernary());
                skipWhitespace();
                if (atEnd()) {
                    throw new ExpressionException("Missing closing parenthesis");
                }
                char current = peek();
                if (current == ',') {
                    index++;
                    continue;
                }
                if (current == ')') {
                    index++;
                    return args;
                }
                throw new ExpressionException("Expected ',' or ')' in function arguments");
            }
        }

        private static Value single(List<Value> args, String name) {
            if (args.size() != 1) {
                throw new ExpressionException(name + "(...) takes exactly one argument");
            }
            return args.get(0);
        }

        private Value rand(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("rand(min, max) takes exactly two arguments");
            }
            BigInteger min = asNumber(args.get(0), "rand min").wholeValue();
            BigInteger max = asNumber(args.get(1), "rand max").wholeValue();
            if (min.compareTo(max) > 0) {
                throw new ExpressionException("rand(min, max): min is greater than max");
            }

            BigInteger range = max.subtract(min).add(BigInteger.ONE);
            long rangeLong;
            try {
                rangeLong = range.longValueExact();
            } catch (ArithmeticException ex) {
                throw new ExpressionException("rand(min, max): range is too large");
            }

            BigInteger result = min.add(BigInteger.valueOf(context.random().nextLong(rangeLong)));
            return new Value.NumberValue(Rational.of(result));
        }

        /**
         * pick(a | b | c) — options are literal text, split on top-level '|'.
         * Escapes: \| \( \) \\ \$ produce the literal character. Parentheses
         * inside options must balance (or be escaped).
         */
        private Value parsePick() {
            List<String> options = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 1;

            while (true) {
                if (atEnd()) {
                    throw new ExpressionException("pick(...) is missing its closing parenthesis");
                }
                char c = input.charAt(index);

                if (c == '\\' && index + 1 < input.length()) {
                    current.append(input.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        index++;
                        break;
                    }
                } else if (c == '|' && depth == 1) {
                    options.add(current.toString().trim());
                    current.setLength(0);
                    index++;
                    continue;
                }

                current.append(c);
                index++;
            }
            options.add(current.toString().trim());

            if (options.size() == 1 && options.get(0).isEmpty()) {
                throw new ExpressionException("pick(...) needs at least one option, e.g. pick(a | b)");
            }

            return new Value.StringValue(options.get(context.random().nextInt(options.size())));
        }

        private String parseIdentifier() {
            int start = index;
            if (!atEnd() && Character.isLetter(input.charAt(index))) {
                index++;
                while (!atEnd() && isIdentifierPart(input.charAt(index))) {
                    index++;
                }
            }
            if (start == index) {
                throw new ExpressionException("Expected a value");
            }
            return input.substring(start, index);
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '.';
        }

        private boolean peekIsDigit() {
            return !atEnd() && Character.isDigit(peek());
        }

        private String parseDigits() {
            int start = index;
            while (!atEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            return input.substring(start, index);
        }

        private Value resolveVariable(String name) {
            return context.variables().resolve(name)
                    .orElseThrow(() -> new ExpressionException("Unknown variable '" + name + "'" + suggestFor(name)));
        }

        private String suggestFor(String name) {
            String best = null;
            int bestDistance = 3; // suggest only within edit distance 2
            List<String> candidates = new ArrayList<>(context.variables().names());
            candidates.addAll(List.of("rand", "pick", "int", "float", "true", "false"));
            for (String candidate : candidates) {
                int distance = editDistance(name.toLowerCase(), candidate.toLowerCase());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best != null ? " — did you mean '" + best + "'?" : "";
        }

        private static int editDistance(String a, String b) {
            int[] previous = new int[b.length() + 1];
            int[] current = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) {
                previous[j] = j;
            }
            for (int i = 1; i <= a.length(); i++) {
                current[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                    current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[b.length()];
        }

        private Value parseNumber() {
            skipWhitespace();
            int start = index;

            while (!atEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }

            boolean hasDigitsBeforeDecimal = index > start;
            boolean hasDecimalPoint = !atEnd() && input.charAt(index) == '.';

            if (hasDecimalPoint) {
                index++;
                int decimalStart = index;
                while (!atEnd() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
                if (!hasDigitsBeforeDecimal && decimalStart == index) {
                    throw new ExpressionException("Expected a number");
                }
            } else if (!hasDigitsBeforeDecimal) {
                if (atEnd()) {
                    throw new ExpressionException("Unexpected end of expression");
                }
                throw new ExpressionException("Unexpected '" + peek() + "'");
            }

            return new Value.NumberValue(Rational.parse(input.substring(start, index)));
        }

        // --- type coercion helpers ---

        private static Rational asNumber(Value value, String where) {
            if (value instanceof Value.NumberValue number) {
                return number.value();
            }
            throw new ExpressionException("Expected a number " + where + ", got " + typeName(value));
        }

        private static boolean asBool(Value value, String where) {
            if (value instanceof Value.BoolValue bool) {
                return bool.value();
            }
            throw new ExpressionException("Expected true/false " + where + ", got " + typeName(value));
        }

        private static String typeName(Value value) {
            if (value instanceof Value.NumberValue) return "a number";
            if (value instanceof Value.StringValue) return "text";
            return "true/false";
        }

        // --- scanning helpers ---

        private boolean matches(String token) {
            return input.regionMatches(index, token, 0, token.length());
        }

        private char peek() {
            return input.charAt(index);
        }

        private boolean atEnd() {
            return index >= input.length();
        }

        private String rest() {
            String rest = input.substring(index);
            return rest.length() > 20 ? rest.substring(0, 20) + "…" : rest;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }
    }
}
