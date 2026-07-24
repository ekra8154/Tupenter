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
 * Short-circuit: ternary {@code ?:}, {@code &&}, and {@code ||} evaluate only
 * the taken side. The untaken side is dry-parsed in "skip" mode (see the
 * {@code skipping} field) — index still advances exactly per the grammar, but
 * no call/resolve/arithmetic fires. That's what stops a recursive call in a
 * dead branch (infinite recursion) and lets guards like {@code x != 0 ? 10/x : 0}
 * avoid dividing by zero.
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

    /**
     * Every built-in function name. Used to decide {@code f(x)} (a call) versus
     * {@code v (x)} (implicit multiplication by a parenthesized group) — a
     * builtin always wins. Derived from the {@link BuiltinFunctions} registry,
     * which must stay in step with the dispatch switch; BuiltinFunctionsTest
     * asserts they agree.
     */
    private static final java.util.Set<String> BUILTIN_FUNCTIONS = BuiltinFunctions.NAMES;

    private static final class Parser {
        /** A dead branch's stand-in: never inspected in skip mode, never leaks to eval mode. */
        private static final Value SKIP = Value.ofNumber(0);

        private final String input;
        private final EvalContext context;
        private int index;
        /** When true, parse (advance index) but perform NO call/resolve/arithmetic — dry-parse a dead branch. */
        private boolean skipping;
        /**
         * How many ternary THEN-branches we're inside. ':' is an identifier
         * character (namespaced NBT keys: components.minecraft:damage) — except
         * here, where it's the separator waiting to close {@code cond ? a : b}.
         */
        private int ternaryThenDepth;

        private Parser(String input, EvalContext context) {
            this.input = input;
            this.context = context;
        }

        /** Parses {@code production}, forcing skip mode when {@code skip} (already-skipping is sticky). Restores after. */
        private Value parseSkippable(boolean skip, java.util.function.Supplier<Value> production) {
            boolean saved = skipping;
            skipping = skipping || skip;
            try {
                return production.get();
            } finally {
                skipping = saved; // restore so nested short-circuits don't leak their mode outward
            }
        }

        private Value parseTernary() {
            Value condition = parseOr();
            skipWhitespace();
            if (!atEnd() && peek() == '?') {
                index++;
                // short-circuit: only the taken branch evaluates; the other is
                // dry-parsed so a recursive call there never fires and a guard
                // like x != 0 ? 10/x : 0 doesn't divide by zero.
                boolean takeTrue = !skipping && asBool(condition, "before '?'");
                Value whenTrue;
                ternaryThenDepth++; // the ':' ahead closes this branch — not part of a name
                try {
                    whenTrue = parseSkippable(!takeTrue, this::parseTernary);
                } finally {
                    ternaryThenDepth--;
                }
                skipWhitespace();
                if (atEnd() || peek() != ':') {
                    throw new ExpressionException("Expected ':' in condition ? a : b");
                }
                index++;
                Value whenFalse = parseSkippable(takeTrue, this::parseTernary);
                return skipping ? SKIP : (takeTrue ? whenTrue : whenFalse);
            }
            return condition;
        }

        private Value parseOr() {
            Value value = parseAnd();
            while (true) {
                skipWhitespace();
                if (matches("||")) {
                    index += 2;
                    // short-circuit: a true left makes the right operand dead — dry-parse it
                    boolean leftTrue = !skipping && asBool(value, "left of ||");
                    Value right = parseSkippable(leftTrue, this::parseAnd);
                    value = skipping ? SKIP
                            : new Value.BoolValue(leftTrue || asBool(right, "right of ||"));
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
                    // short-circuit: a false left makes the right operand dead — dry-parse it
                    boolean leftFalse = !skipping && !asBool(value, "left of &&");
                    Value right = parseSkippable(leftFalse, this::parseComparison);
                    value = skipping ? SKIP
                            : new Value.BoolValue(!leftFalse && asBool(right, "right of &&"));
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
            return skipping ? SKIP : new Value.BoolValue(compare(left, op, right));
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
                    default -> throw new ExpressionException(
                            "'" + op + "' only compares numbers; use == or != for true/false");
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
                    value = skipping ? SKIP : addOrConcat(value, right);
                    continue;
                }

                if (operator == '-') {
                    index++;
                    Value right = parseMultiplicative();
                    value = skipping ? SKIP
                            : new Value.NumberValue(asNumber(value, "left of -").subtract(asNumber(right, "right of -")));
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
                if (operator == '*' || operator == '/' || operator == '%') {
                    // "||" is handled a level up; a lone '/' here is division
                    index++;
                    Value right = parseUnary();
                    if (skipping) { // dead: no divide/coerce
                        value = SKIP;
                        continue;
                    }
                    Rational l = asNumber(value, "left of " + operator);
                    Rational r = asNumber(right, "right of " + operator);
                    value = new Value.NumberValue(switch (operator) {
                        case '*' -> l.multiply(r);
                        case '/' -> l.divide(r);
                        // floored modulo (like scoreboard %=): the result
                        // follows the divisor's sign, so cycling never
                        // steps out of range even with negative inputs
                        default -> l.subtract(r.multiply(l.divide(r).floor()));
                    });
                    continue;
                }

                if (startsImplicitMultiplication()) {
                    Value right = parseUnary();
                    value = skipping ? SKIP
                            : new Value.NumberValue(asNumber(value, "left of implicit multiplication")
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
                return skipping ? SKIP : new Value.NumberValue(asNumber(value, "after unary -").negate());
            }

            if (current == '!' && !matches("!=")) {
                index++;
                Value value = parseUnary();
                return skipping ? SKIP : new Value.BoolValue(!asBool(value, "after !"));
            }

            return parsePower();
        }

        /** {@code base ^ exponent} — power, binds tighter than * / , right-associative (2^3^2 = 2^512... = 2^9). */
        private Value parsePower() {
            Value base = parsePostfix();
            skipWhitespace();
            if (!atEnd() && peek() == '^') {
                index++;
                Value exponent = parseUnary(); // right-assoc, and lets the exponent be negative
                return skipping ? SKIP
                        : new Value.NumberValue(power(asNumber(base, "base of ^"), asNumber(exponent, "exponent of ^")));
            }
            return base;
        }

        /** Exact for a whole exponent (repeated multiply); falls back to double for a fractional one. */
        private static Rational power(Rational base, Rational exponent) {
            if (exponent.isWhole()) {
                java.math.BigInteger n = exponent.wholeValue();
                if (n.abs().compareTo(java.math.BigInteger.valueOf(1024)) > 0) {
                    throw new ExpressionException("^ exponent is too large (max 1024)");
                }
                int e = n.intValue();
                Rational result = Rational.of(1);
                for (int k = 0; k < Math.abs(e); k++) {
                    result = result.multiply(base);
                }
                if (e < 0) {
                    if (base.equals(Rational.ZERO)) {
                        throw new ExpressionException("0 raised to a negative power");
                    }
                    result = Rational.of(1).divide(result);
                }
                return result;
            }
            return Rational.fromDouble(Math.pow(base.doubleValue(), exponent.doubleValue()));
        }

        private Value parsePostfix() {
            Value value = parsePrimary();
            while (true) {
                skipWhitespace();
                if (!atEnd() && (peek() == 's' || peek() == 'S')) {
                    index++;
                    value = skipping ? SKIP
                            : new Value.NumberValue(asNumber(value, "before stack suffix 's'").multiply(Rational.of(64)));
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
                // $...$ evaluates its inside — the SAME rule as command-world
                // markers, so $i+ 1$ works on a #set right-hand side too. In
                // an expression it's just explicit wrapping. One carve-out,
                // also shared with command markers: digits-only content
                // ($1$..$n$) is a positional-parameter reference.
                index++;
                if (peekIsDigit()) {
                    int save = index;
                    String digits = parseDigits();
                    skipWhitespace();
                    if (!atEnd() && peek() == '$') {
                        index++;
                        return skipping ? SKIP : resolveVariable(digits);
                    }
                    index = save; // the digits started an expression: $1+2$
                }
                Value value = parseTernary();
                skipWhitespace();
                if (atEnd() || peek() != '$') {
                    throw new ExpressionException("Missing closing $");
                }
                index++;
                return value;
            }

            if (current == '#') {
                return parseTagLiteral();
            }

            if (Character.isLetter(current)) {
                return parseFunctionCall();
            }

            return parseNumber();
        }

        /**
         * #minecraft:wool — a tag literal, no quotes needed. It's just a
         * string value starting with '#', which is exactly what
         * blockset/itemset/effectset accept: blockset(#minecraft:wool).
         * (In a ternary, a ':' directly after a tag reads as part of the
         * tag — quote the tag there.)
         */
        private Value parseTagLiteral() {
            int start = index;
            index++; // '#'
            while (!atEnd()) {
                char c = peek();
                if (Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '/' || c == '.') {
                    index++;
                } else {
                    break;
                }
            }
            if (index == start + 1) {
                throw new ExpressionException("Expected a tag id after '#', e.g. #minecraft:wool");
            }
            return new Value.StringValue(input.substring(start, index));
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
                return skipping ? SKIP : resolveVariable(identifier);
            }
            // An identifier followed by '(' is USUALLY a call — but the language
            // also has implicit multiplication, so "x (2)" is x*2 when x is a
            // variable and no function x exists. Deciding here (before the '(' is
            // consumed) is what keeps a loop variable named x/y/z from being
            // swallowed by a same-named function. The check is a name lookup in
            // both eval and skip mode, so the index advances identically.
            if (!BUILTIN_FUNCTIONS.contains(identifier.toLowerCase(java.util.Locale.ROOT))
                    && !context.functions().defines(identifier)
                    && context.variables().resolve(identifier).isPresent()) {
                return skipping ? SKIP : resolveVariable(identifier);
            }
            index++; // consume '('

            if (identifier.equalsIgnoreCase("pick")) {
                return parsePick();
            }

            List<Value> args = parseFunctionArgs();
            if (skipping) {
                // dead call: skip the builtin math AND the user-function call —
                // the latter is what stops a recursive call in an untaken branch
                return SKIP;
            }
            return switch (identifier.toLowerCase()) {
                case "int" -> new Value.NumberValue(asNumberOrParse(single(args, "int"), "int(...)").truncate());
                case "float" -> new Value.NumberValue(asNumberOrParse(single(args, "float"), "float(...)"));
                case "abs" -> new Value.NumberValue(asNumber(single(args, "abs"), "abs(...)").abs());
                case "floor" -> new Value.NumberValue(asNumber(single(args, "floor"), "floor(...)").floor());
                case "ceil" -> new Value.NumberValue(asNumber(single(args, "ceil"), "ceil(...)").ceil());
                case "round" -> new Value.NumberValue(asNumber(single(args, "round"), "round(...)").round());
                case "min" -> minMax("min", args, true);
                case "max" -> minMax("max", args, false);
                case "len" -> len(args);
                case "nth" -> nth(args);
                case "contains" -> contains(args);
                case "indexof" -> indexof(args);
                case "trim" -> new Value.StringValue(single(args, "trim").displayString().trim());
                case "upper" -> new Value.StringValue(single(args, "upper").displayString().toUpperCase(java.util.Locale.ROOT));
                case "lower" -> new Value.StringValue(single(args, "lower").displayString().toLowerCase(java.util.Locale.ROOT));
                case "substr" -> substr(args);
                case "replace" -> replace(args);
                case "randf" -> randf(args);
                // trig takes DEGREES — Minecraft rotations (client.yaw/pitch) are degrees
                case "sin" -> mathFunction("sin", args, degrees -> Math.sin(Math.toRadians(degrees)));
                case "cos" -> mathFunction("cos", args, degrees -> Math.cos(Math.toRadians(degrees)));
                case "tan" -> mathFunction("tan", args, degrees -> Math.tan(Math.toRadians(degrees)));
                case "sqrt" -> sqrt(args);
                case "rand" -> rand(args);
                case "range" -> range(args);
                case "itemset" -> tagMembers("itemset", TagResolver.TagKind.ITEM, args);
                case "blockset" -> tagMembers("blockset", TagResolver.TagKind.BLOCK, args);
                case "effectset" -> tagMembers("effectset", TagResolver.TagKind.EFFECT, args);
                case "entityset" -> tagMembers("entityset", TagResolver.TagKind.ENTITY, args);
                case "block" -> blockAt(args);
                case "simulated" -> simulatedAt(args);
                case "vec" -> vec(args);
                case "component" -> component(args);
                case "vadd" -> vecArith(args, "vadd", true);
                case "vsub" -> vecArith(args, "vsub", false);
                case "scale" -> scale(args);
                case "mag" -> mag(args);
                case "dist" -> dist(args);
                case "normalize" -> normalize(args);
                case "dot" -> dot(args);
                case "cross" -> cross(args);
                case "raycast" -> raycast(args);
                case "raycast_block" -> raycastBlock(args);
                case "entity" -> entityField(args);
                case "keys" -> nbtKeys(args);
                case "slot" -> slotField(args);
                case "raycast_entity" -> entityRaycast(args);
                case "entities" -> entitiesWithin(args);
                case "nearest_entity" -> nearestEntity(args);
                default -> {
                    // not a built-in — try a user-defined /customfunction
                    Value userValue = context.functions().call(identifier, args, context);
                    if (userValue == null) {
                        throw new ExpressionException(BuiltinFunctions.unknownFunctionMessage(identifier));
                    }
                    yield userValue;
                }
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

        /** contains(list, value) — membership by == semantics; mixed-type elements just don't match. */
        private Value contains(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("contains(list, value) takes a list and a value");
            }
            if (!(args.get(0) instanceof Value.ListValue list)) {
                throw new ExpressionException("contains(list, value): the first argument must be a list, e.g. contains(blockset(#minecraft:logs), block(client.target.blockpos))");
            }
            for (Value element : list.values()) {
                try {
                    if (compare(element, "==", args.get(1))) {
                        return new Value.BoolValue(true);
                    }
                } catch (ExpressionException incomparable) {
                    // a differently-typed element is simply not a match
                }
            }
            return new Value.BoolValue(false);
        }

        /** indexof(list, value) — 0-based position of the first == match, or -1. The inverse of nth. */
        private Value indexof(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("indexof(list, value) takes a list and a value");
            }
            if (!(args.get(0) instanceof Value.ListValue list)) {
                throw new ExpressionException("indexof(list, value): the first argument must be a list, e.g. indexof(blockset(#minecraft:wool), block(client.target.blockpos))");
            }
            List<Value> values = list.values();
            for (int i = 0; i < values.size(); i++) {
                try {
                    if (compare(values.get(i), "==", args.get(1))) {
                        return new Value.NumberValue(Rational.of(i));
                    }
                } catch (ExpressionException incomparable) {
                    // a differently-typed element is simply not a match
                }
            }
            return new Value.NumberValue(Rational.of(-1));
        }

        /** substr(text, start[, count]) — 0-based, clamped to the string; omit count for "to the end". */
        private Value substr(List<Value> args) {
            if (args.size() < 2 || args.size() > 3) {
                throw new ExpressionException("substr(text, start[, count]) takes a 0-based start and an optional length");
            }
            String s = args.get(0).displayString();
            int len = s.length();
            long startL;
            try {
                startL = asNumber(args.get(1), "substr start").wholeValue().longValueExact();
            } catch (ArithmeticException ex) {
                throw new ExpressionException("substr start is out of range");
            }
            int start = (int) Math.max(0, Math.min(startL, len));
            int end = len;
            if (args.size() == 3) {
                long countL;
                try {
                    countL = asNumber(args.get(2), "substr count").wholeValue().longValueExact();
                } catch (ArithmeticException ex) {
                    throw new ExpressionException("substr count is out of range");
                }
                if (countL < 0) {
                    throw new ExpressionException("substr count can't be negative");
                }
                end = (int) Math.min((long) start + countL, len);
            }
            return new Value.StringValue(s.substring(start, end));
        }

        /** replace(text, find, replacement) — replaces every occurrence (literal, not regex). */
        private Value replace(List<Value> args) {
            if (args.size() != 3) {
                throw new ExpressionException("replace(text, find, replacement) takes three arguments");
            }
            String find = args.get(1).displayString();
            if (find.isEmpty()) {
                throw new ExpressionException("replace(...): the text to find can't be empty");
            }
            return new Value.StringValue(args.get(0).displayString().replace(find, args.get(2).displayString()));
        }

        /** nth(list, i) — 0-based element access; pairs with % for cycling. */
        private Value nth(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("nth(list, index) takes a list and a 0-based index");
            }
            if (!(args.get(0) instanceof Value.ListValue list)) {
                throw new ExpressionException("nth(list, index): the first argument must be a list, e.g. nth(blockset(\"#minecraft:wool\"), 0)");
            }
            long index;
            try {
                index = asNumber(args.get(1), "nth index").wholeValue().longValueExact();
            } catch (ArithmeticException ex) {
                throw new ExpressionException("nth index is out of range");
            }
            if (index < 0 || index >= list.values().size()) {
                throw new ExpressionException("nth index " + index + " is out of range (list has "
                        + list.values().size() + " elements, 0-based — wrap with % len(list))");
            }
            return list.values().get((int) index);
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
            // rand(list) — uniform pick, composes with blockset()/itemset()/range()
            if (args.size() == 1) {
                if (!(args.get(0) instanceof Value.ListValue list)) {
                    throw new ExpressionException("rand(x) with one argument takes a list, e.g. rand(blockset(\"#minecraft:logs\"))");
                }
                if (list.values().isEmpty()) {
                    throw new ExpressionException("rand(list): the list is empty");
                }
                return list.values().get(context.random().nextInt(list.values().size()));
            }
            if (args.size() != 2) {
                throw new ExpressionException("rand(min, max) or rand(list)");
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
         * itemset("#minecraft:logs") / blockset("#c:ores") /
         * effectset("#...") — the tag's member ids as a list (leading #
         * optional). With NO argument, the entire registry:
         * rand(effectset()) is a random effect. Registry lookup comes from
         * the EvalContext's TagResolver, so this needs a live world.
         */
        private Value tagMembers(String name, TagResolver.TagKind kind, List<Value> args) {
            // no args → the whole registry
            if (args.isEmpty()) {
                List<String> ids = context.tags().resolve(kind, null);
                if (ids == null) {
                    throw new ExpressionException(name + "(...) needs a live world to look up the registry");
                }
                return idListValue(ids);
            }
            // one or more members, each a #tag or a concrete id — unioned into an
            // ad-hoc set (dedup, first-seen order): blockset("oak_planks", "#minecraft:logs")
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
            for (Value arg : args) {
                if (!(arg instanceof Value.StringValue string)) {
                    throw new ExpressionException(name + "(...) takes #tags and/or concrete ids — "
                            + name + "(\"oak_planks\", \"#minecraft:logs\") — or no argument for the whole registry");
                }
                // each argument may itself be a list of members separated by spaces or
                // commas, so a SINGLE scalar string carries a whole set. That's what
                // lets a custom command's <set:blockset> param hold more than one:
                // /sphere ~ ~ ~ 5 $blockset("minecraft:stone", #wool)$ — the list
                // flattens to "a,b,c" as one argument token and splits back here.
                String trimmed = string.value().trim();
                if (trimmed.isEmpty()) {
                    throw new ExpressionException(name + "(...) needs a tag or id, e.g. " + name + "(#minecraft:logs)");
                }
                for (String token : splitMembers(trimmed)) {
                    union.addAll(resolveMember(name, kind, token));
                }
            }
            return idListValue(union);
        }

        /**
         * Splits a set string into members on spaces and/or commas, ignoring
         * separators inside [block states] and {nbt} so
         * "oak_log[axis=y],stone" stays two members, not four.
         */
        private static List<String> splitMembers(String text) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                } else if (depth <= 0 && (Character.isWhitespace(c) || c == ',')) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) {
                out.add(current.toString());
            }
            return out;
        }

        /** One {@code blockset}/etc. member — a "#tag" expands to its members, a bare/concrete id resolves to itself. */
        private List<String> resolveMember(String name, TagResolver.TagKind kind, String raw) {
            boolean explicitTag = raw.startsWith("#");
            String tag = explicitTag ? raw.substring(1) : raw;
            if (tag.isEmpty()) {
                throw new ExpressionException(name + "(...) needs a tag or id, e.g. " + name + "(#minecraft:logs)");
            }
            if (!explicitTag) {
                // BARE name: a concrete id of that name WINS over a same-named
                // tag. minecraft:ice (a block) and #minecraft:ice (the ice
                // family) both exist, so a bare "ice" must mean the single
                // block — use "#ice" for the family. Only when there's no
                // concrete id (e.g. "logs") does a bare name fall back to a tag.
                String canonical = context.tags().lookup(kind, tag);
                if (canonical != null) {
                    return List.of(canonical);
                }
            }
            List<String> ids = context.tags().resolve(kind, tag);
            if (ids == null) {
                throw new ExpressionException(name + "(...) needs a live world to look up the registry");
            }
            if (ids.isEmpty()) {
                throw new ExpressionException("Unknown " + kind.name().toLowerCase(java.util.Locale.ROOT)
                        + (explicitTag ? " tag: #" + tag : " tag or id: " + tag));
            }
            return ids;
        }

        private static Value idListValue(java.util.Collection<String> ids) {
            List<Value> values = new ArrayList<>(ids.size());
            for (String id : ids) {
                values.add(Value.of(id));
            }
            return new Value.ListValue(values);
        }

        /**
         * block(x, y, z) or block("x y z") — the block id at a position,
         * read from the CLIENT's copy of the world (no server round trip,
         * no delay — this is /execute if block folded into the expression
         * world, where #if/#else handle it naturally). Coordinates floor to
         * block positions; the string form accepts what client.target_block
         * and pos params bind: block(client.target.blockpos) == "minecraft:air".
         */
        private Value blockAt(List<Value> args) {
            long[] position = blockPosArgs(args, "block");
            String id = context.blocks().blockAt(position[0], position[1], position[2]);
            if (id == null) {
                throw new ExpressionException("block(...): can't read there — no world, or a chunk the client doesn't have");
            }
            return Value.of(id);
        }

        /**
         * simulated(x, y, z) or simulated("x y z") — true if the server is
         * ticking entities at that position (its chunk is within the server's
         * simulation distance of you), false otherwise, and never errors. This
         * is what actually governs item despawn / mob spawning, unlike block(...)
         * which reflects only what your client has received. On a shared server
         * it sees only YOUR simulation, not a distant teammate's.
         */
        private Value simulatedAt(List<Value> args) {
            long[] position = blockPosArgs(args, "simulated");
            Boolean simulated = context.blocks().simulated(position[0], position[1], position[2]);
            return Value.of(simulated != null && simulated);
        }

        /** The shared x/y/z parsing for block(...) / simulated(...): three numbers or one vec3, floored. */
        private long[] blockPosArgs(List<Value> args, String fn) {
            long[] position = new long[3];
            if (args.size() == 1 && args.get(0) instanceof Value.StringValue string) {
                String[] parts = Coords.split(string.value());
                if (parts.length != 3) {
                    throw new ExpressionException(fn + "(\"x y z\") needs three coordinates, got \"" + string.value() + "\"");
                }
                for (int i = 0; i < 3; i++) {
                    try {
                        position[i] = Rational.parse(parts[i]).floor().wholeValue().longValueExact();
                    } catch (IllegalArgumentException | ArithmeticException ex) {
                        throw new ExpressionException(fn + "(...): bad coordinate '" + parts[i] + "'");
                    }
                }
            } else if (args.size() == 3) {
                for (int i = 0; i < 3; i++) {
                    try {
                        position[i] = asNumber(args.get(i), fn + "(...)").floor().wholeValue().longValueExact();
                    } catch (ArithmeticException ex) {
                        throw new ExpressionException(fn + "(...): coordinate out of range");
                    }
                }
            } else {
                throw new ExpressionException(fn + "(x, y, z) or " + fn + "(\"x y z\") — e.g. " + fn + "(client.target.blockpos)");
            }
            return position;
        }

        /** vec(x, y, z) — a vec3 value ("x y z"), the clean way to spell a literal position. */
        private Value vec(List<Value> args) {
            if (args.size() != 3) {
                throw new ExpressionException("vec(x, y, z) takes three numbers, e.g. vec(0, 64, 0)");
            }
            return new Value.StringValue(
                    new Value.NumberValue(asNumber(args.get(0), "vec(...)")).displayString() + " "
                            + new Value.NumberValue(asNumber(args.get(1), "vec(...)")).displayString() + " "
                            + new Value.NumberValue(asNumber(args.get(2), "vec(...)")).displayString());
        }

        /**
         * component(v, axis) — pull one component out of a vec3, so any COMPUTED
         * vec can be indexed: component(raycast(500), "y"), component(vec(1,2,3), "x").
         * The spelled-out counterpart is the dotted variable client.pos.x — same
         * split as client.slot.&lt;slot&gt;.&lt;field&gt; vs slot(slot, field).
         *
         * <p>This replaced three builtins named x/y/z. Single letters were not just
         * ugly: the language has implicit multiplication, and parseFunctionCall
         * takes the function branch as soon as an identifier is followed by '(',
         * so a bound variable named x (the canonical loop name — see the randomfill
         * and circle recipes) turned "x (2)" into a call instead of x*2.
         *
         * <p>Keeps the exact Rational (no lossy double). A non-vec — e.g. the "miss"
         * sentinel — errors clearly; gate a raycast with == "miss" first.
         */
        private Value component(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("component(v, axis) takes a vec3 and \"x\", \"y\", or \"z\" — "
                        + "e.g. component(client.pos, \"y\")");
            }
            String axis = args.get(1).displayString().trim().toLowerCase(java.util.Locale.ROOT);
            int slot = switch (axis) {
                case "x" -> 0;
                case "y" -> 1;
                case "z" -> 2;
                default -> throw new ExpressionException("component(v, axis): axis must be \"x\", \"y\", or \"z\", got '"
                        + args.get(1).displayString() + "'");
            };
            if (!(args.get(0) instanceof Value.StringValue string)) {
                throw new ExpressionException("component(v, axis) takes a vec3, e.g. component(client.pos, \"x\")");
            }
            String[] parts = Coords.split(string.value());
            if (parts.length != 3) {
                throw new ExpressionException("component(v, axis) expected a vec3 with three components, got \""
                        + string.value() + "\"");
            }
            try {
                return new Value.NumberValue(Rational.parse(parts[slot]));
            } catch (IllegalArgumentException ex) {
                throw new ExpressionException("component(v, axis): '" + parts[slot] + "' isn't a number");
            }
        }

        // ---- vector arithmetic ----
        // vec3s are "x y z" strings (Coords.split in, displayString out). The
        // dot/dist/mag family returns exact-where-it-can Rationals; the vec-valued
        // ones round to the command form on output, which is far finer than block
        // precision. sqrt is irrational, so dist/mag/normalize go through double.

        /** Parse a vec3 argument into its three components, with a {@code where}-labelled error. */
        private Rational[] asVec3(Value value, String where) {
            if (!(value instanceof Value.StringValue string)) {
                throw new ExpressionException(where + " needs a vec3 like vec(0, 64, 0) or client.pos");
            }
            String[] parts = Coords.split(string.value());
            if (parts.length != 3) {
                throw new ExpressionException(where + ": \"" + string.value() + "\" isn't a vec3 (needs three numbers)");
            }
            Rational[] out = new Rational[3];
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Rational.parse(parts[i]);
                } catch (IllegalArgumentException notNumeric) {
                    throw new ExpressionException(where + ": \"" + parts[i] + "\" isn't a number");
                }
            }
            return out;
        }

        /** Build a vec3 value from three components (same "x y z" form vec() emits). */
        private static Value vec3(Rational x, Rational y, Rational z) {
            return new Value.StringValue(new Value.NumberValue(x).displayString() + " "
                    + new Value.NumberValue(y).displayString() + " "
                    + new Value.NumberValue(z).displayString());
        }

        private static double magnitude(Rational[] v) {
            double x = v[0].doubleValue();
            double y = v[1].doubleValue();
            double z = v[2].doubleValue();
            return Math.sqrt(x * x + y * y + z * z);
        }

        /** vadd/vsub(a, b) — component-wise add or subtract. */
        private Value vecArith(List<Value> args, String name, boolean add) {
            if (args.size() != 2) {
                throw new ExpressionException(name + "(a, b) takes two vec3s, e.g. " + name + "(client.pos, client.look)");
            }
            Rational[] a = asVec3(args.get(0), name + "(a, b)");
            Rational[] b = asVec3(args.get(1), name + "(a, b)");
            return add
                    ? vec3(a[0].add(b[0]), a[1].add(b[1]), a[2].add(b[2]))
                    : vec3(a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2]));
        }

        /** scale(v, s) — every component times the scalar s. */
        private Value scale(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("scale(v, s) takes a vec3 and a number, e.g. scale(client.look, 5)");
            }
            Rational[] v = asVec3(args.get(0), "scale(v, s)");
            Rational s = asNumber(args.get(1), "scale(v, s)");
            return vec3(v[0].multiply(s), v[1].multiply(s), v[2].multiply(s));
        }

        /** mag(v) — the length of a vector. */
        private Value mag(List<Value> args) {
            return new Value.NumberValue(Rational.fromDouble(magnitude(asVec3(single(args, "mag"), "mag(v)"))));
        }

        /** dist(a, b) — the straight-line distance between two points. */
        private Value dist(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("dist(a, b) takes two vec3s, e.g. dist(client.pos, world.spawn)");
            }
            Rational[] a = asVec3(args.get(0), "dist(a, b)");
            Rational[] b = asVec3(args.get(1), "dist(a, b)");
            double sum = 0;
            for (int i = 0; i < 3; i++) {
                double d = a[i].subtract(b[i]).doubleValue();
                sum += d * d;
            }
            return new Value.NumberValue(Rational.fromDouble(Math.sqrt(sum)));
        }

        /** normalize(v) — v scaled to length 1; a zero vector stays zero rather than dividing by zero. */
        private Value normalize(List<Value> args) {
            Rational[] v = asVec3(single(args, "normalize"), "normalize(v)");
            double m = magnitude(v);
            if (m == 0) {
                return vec3(Rational.of(0), Rational.of(0), Rational.of(0));
            }
            Rational len = Rational.fromDouble(m);
            return vec3(v[0].divide(len), v[1].divide(len), v[2].divide(len));
        }

        /** dot(a, b) — the scalar a.x*b.x + a.y*b.y + a.z*b.z (exact). */
        private Value dot(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("dot(a, b) takes two vec3s, e.g. dot(client.look, vec(0, 1, 0))");
            }
            Rational[] a = asVec3(args.get(0), "dot(a, b)");
            Rational[] b = asVec3(args.get(1), "dot(a, b)");
            return new Value.NumberValue(
                    a[0].multiply(b[0]).add(a[1].multiply(b[1])).add(a[2].multiply(b[2])));
        }

        /** cross(a, b) — the vector perpendicular to both (exact). */
        private Value cross(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("cross(a, b) takes two vec3s, e.g. cross(client.look, vec(0, 1, 0))");
            }
            Rational[] a = asVec3(args.get(0), "cross(a, b)");
            Rational[] b = asVec3(args.get(1), "cross(a, b)");
            return vec3(
                    a[1].multiply(b[2]).subtract(a[2].multiply(b[1])),
                    a[2].multiply(b[0]).subtract(a[0].multiply(b[2])),
                    a[0].multiply(b[1]).subtract(a[1].multiply(b[0])));
        }

        /**
         * raycast(dist) — cast from the player's eyes along their look, up to
         * dist blocks. raycast(origin, dir, dist) — a general ray (dir is
         * normalized). Both return the hit block's "x y z" (integer coords),
         * or the sentinel "miss" — a miss never throws, so it composes with a
         * plain == "miss" gate. Uses the client's Level.clip (collision
         * raycast: hits collidable blocks like your crosshair, sub-block
         * accurate, passes through grass/fluids).
         */
        private Value raycast(List<Value> args) {
            String pos;
            if (args.size() == 1) {
                pos = context.raycaster().fromPlayer(asNumber(args.get(0), "raycast(dist)").doubleValue());
            } else if (args.size() == 3) {
                double[] origin = coords3(args.get(0), "raycast origin");
                double[] dir = coords3(args.get(1), "raycast direction");
                pos = context.raycaster().cast(origin[0], origin[1], origin[2],
                        dir[0], dir[1], dir[2], asNumber(args.get(2), "raycast(...) distance").doubleValue());
            } else {
                throw new ExpressionException("raycast(dist) or raycast(origin, dir, dist)");
            }
            return Value.of(pos != null ? pos : "miss");
        }

        /**
         * raycast_block(dist) — like raycast(dist) but yields the BLOCK ID at
         * the hit (e.g. "minecraft:grass_block") instead of its position, or
         * "miss". Reads the id through the same BlockReader as block(...).
         */
        private Value raycastBlock(List<Value> args) {
            if (args.size() != 1) {
                throw new ExpressionException("raycast_block(dist) takes one distance");
            }
            String pos = context.raycaster().fromPlayer(asNumber(args.get(0), "raycast_block(dist)").doubleValue());
            if (pos == null) {
                return Value.of("miss");
            }
            String[] parts = Coords.split(pos);
            long[] block = new long[3];
            for (int i = 0; i < 3; i++) {
                block[i] = Rational.parse(parts[i]).floor().wholeValue().longValueExact();
            }
            String id = context.blocks().blockAt(block[0], block[1], block[2]);
            return Value.of(id != null ? id : "miss");
        }

        /**
         * entity(selector, field) — one field of one entity, with BOTH halves as
         * arguments so the SUBJECT can be computed: entity(raycast_entity(30), "health").
         * The spelled-out counterparts are the variables client.&lt;field&gt; (you) and
         * client.target.&lt;field&gt; (your crosshair) — one field vocabulary, three
         * subjects, so swapping subject changes only the subject.
         *
         * <p>field is type, uuid, name, health, pos, blockpos, or nbt.&lt;path&gt; for the
         * raw tree. A third argument is a FALLBACK for an absent field: NBT omits
         * defaulted values (an UNDAMAGED item has no "minecraft:damage"), so a read
         * meaning "0 if it isn't there" needs this to not fault a tick script.
         */
        private Value entityField(List<Value> args) {
            if (args.size() != 2 && args.size() != 3) {
                throw new ExpressionException("entity(selector, field) or entity(selector, field, fallback) — "
                        + "a selector (\"self\", \"target\", or a UUID) and a field, "
                        + "e.g. entity(\"target\", \"health\") or entity(uuid, \"nbt.Health\", 0)");
            }
            String selector = args.get(0).displayString();
            String field = args.get(1).displayString();
            if (args.size() == 3) {
                try {
                    return context.entities().entityField(selector, field);
                } catch (ExpressionException missing) {
                    return args.get(2);
                }
            }
            return context.entities().entityField(selector, field);
        }

        /**
         * keys(selector, path) — the child ADDRESSES of an NBT node: a compound's
         * keys, a list's indices. The bridge from the NBT tree into the list
         * vocabulary (len, contains, nth, #foreach), for compounds keyed by id
         * whose members can't be known ahead of time — item components,
         * enchantments, a mob's Brain.memories — and the only way to loop an NBT
         * list at all.
         *
         * <p>Addresses, not values: feed one back through entity(...) to read it.
         * Absent paths yield an empty list, so len/contains answer instead of
         * aborting a tick script.
         */
        private Value nbtKeys(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("keys(selector, path) takes a selector (\"self\", \"target\", or a UUID) "
                        + "and an NBT path, e.g. keys(\"self\", \"nbt.equipment.chest.components\")");
            }
            List<Value> out = new ArrayList<>();
            for (String key : context.entities().nbtKeys(args.get(0).displayString(), args.get(1).displayString())) {
                out.add(Value.of(key));
            }
            return new Value.ListValue(List.copyOf(out));
        }

        /**
         * slot(slot, field) — one field of one of your slots, with BOTH halves as
         * arguments so the slot can be computed: slot("inventory." + i, "id").
         * The spelled-out counterpart is the variable client.slot.&lt;slot&gt;.&lt;field&gt;
         * — same split as client.&lt;field&gt; vs entity(selector, field).
         */
        private Value slotField(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("slot(slot, field) takes an /item replace slot name and a field — "
                        + "e.g. slot(\"armor.chest\", \"durability\") or slot(\"inventory.\" + i, \"id\")");
            }
            return context.entities().slotField(args.get(0).displayString(), args.get(1).displayString());
        }

        /**
         * raycast_entity(dist) — cast from the player's eyes along their look up
         * to dist blocks and yield the UUID of the first entity hit, or the
         * "miss" sentinel (so it gates with == "miss", like raycast). Feed the
         * UUID straight to entity: entity(raycast_entity(30), "health").
         */
        private Value entityRaycast(List<Value> args) {
            double dist = asNumber(single(args, "raycast_entity"), "raycast_entity(dist)").doubleValue();
            return Value.of(context.entities().raycastUuid(dist));
        }

        /**
         * entities(radius) / entities(radius, type) — a LIST of the UUIDs of
         * entities within radius blocks, optionally only those of one type
         * ("minecraft:zombie"). Empty when nothing matches, so it composes with
         * len(...) and #foreach: #foreach $e$ in entities(8, "minecraft:zombie").
         */
        private Value entitiesWithin(List<Value> args) {
            if (args.isEmpty() || args.size() > 2) {
                throw new ExpressionException("entities(radius) or entities(radius, type), "
                        + "e.g. entities(8) or entities(8, \"minecraft:zombie\")");
            }
            double radius = asNumber(args.get(0), "entities radius").doubleValue();
            String type = args.size() == 2 ? args.get(1).displayString() : null;
            List<Value> out = new ArrayList<>();
            for (String uuid : context.entities().nearbyUuids(radius, type)) {
                out.add(Value.of(uuid));
            }
            return new Value.ListValue(List.copyOf(out));
        }

        /**
         * nearest_entity(radius) / nearest_entity(radius, type) — the UUID of the
         * closest entity within radius blocks (optionally of one type), or the
         * "miss" sentinel when none match.
         */
        private Value nearestEntity(List<Value> args) {
            if (args.isEmpty() || args.size() > 2) {
                throw new ExpressionException("nearest_entity(radius) or nearest_entity(radius, type)");
            }
            double radius = asNumber(args.get(0), "nearest_entity radius").doubleValue();
            String type = args.size() == 2 ? args.get(1).displayString() : null;
            return Value.of(context.entities().nearestUuid(radius, type));
        }

        /** Reads a vec3 arg as three doubles — a "x y z" string or vec(...) result. */
        private double[] coords3(Value value, String where) {
            if (!(value instanceof Value.StringValue string)) {
                throw new ExpressionException(where + " must be a vec3, e.g. \"0 64 0\" or vec(0, 64, 0)");
            }
            String[] parts = Coords.split(string.value());
            if (parts.length != 3) {
                throw new ExpressionException(where + " needs three coordinates, got \"" + string.value() + "\"");
            }
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Rational.parse(parts[i]).doubleValue();
                } catch (IllegalArgumentException ex) {
                    throw new ExpressionException(where + ": bad coordinate '" + parts[i] + "'");
                }
            }
            return out;
        }

        /**
         * pick(a | b | c) — options are full expressions separated by
         * top-level '|' ('||' is still boolean or inside an option), so
         * picks nest and compute: pick(rand(1,5) | client.y | pick(1 | 2)).
         * Quote literal text: pick("say hi" | "say nah"). Like ternary,
         * every option evaluates; one result is returned at random.
         */
        private Value parsePick() {
            List<Value> options = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ')') {
                // otherwise the empty option falls into parseTernary and comes
                // back as "Unexpected ')'", which says nothing about pick
                throw new ExpressionException("pick(...) needs at least one option, e.g. pick(\"hi\" | \"bye\")");
            }
            while (true) {
                options.add(parseTernary());
                skipWhitespace();
                if (atEnd()) {
                    throw new ExpressionException("pick(...) is missing its closing parenthesis");
                }
                char c = peek();
                if (c == '|') {
                    index++;
                    continue;
                }
                if (c == ')') {
                    index++;
                    // dead pick: no random draw; options were still dry-parsed to advance
                    return skipping ? SKIP : options.get(context.random().nextInt(options.size()));
                }
                throw new ExpressionException("pick(...): expected '|' or ')' but found '" + c + "'");
            }
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

        /**
         * ':' belongs to a name because modern NBT keys are namespaced ids —
         * client.nbt.equipment.chest.components.minecraft:enchantments.minecraft:mending
         * is ONE address, and both the dump browser and tab-completion hand it to
         * you spelled exactly like that. The sole competing use of ':' is the
         * ternary separator, and the parser knows when it's expecting one.
         */
        private boolean isIdentifierPart(char c) {
            if (c == ':') {
                return ternaryThenDepth == 0;
            }
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
            candidates.addAll(List.of("rand", "pick", "int", "float", "true", "false", "itemset", "blockset", "effectset", "entityset", "block", "nth", "contains", "indexof", "trim", "upper", "lower", "substr", "replace", "raycast", "raycast_block"));
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

        /**
         * int(...) and float(...) are the EXPLICIT conversions, so they alone
         * also accept numeric TEXT — substr results and NBT string tags have no
         * other route into math. Everywhere else asNumber stays strict, which is
         * what keeps {@code "3" + 1} a concatenation instead of a silent coercion.
         */
        private static Rational asNumberOrParse(Value value, String where) {
            if (value instanceof Value.StringValue text) {
                try {
                    return Rational.parse(text.value().trim());
                } catch (IllegalArgumentException notNumeric) {
                    throw new ExpressionException(where + ": \"" + text.value() + "\" isn't a number");
                }
            }
            return asNumber(value, where);
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
