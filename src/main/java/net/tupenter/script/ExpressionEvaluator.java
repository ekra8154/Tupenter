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
                if (operator == '*' || operator == '/' || operator == '%') {
                    // "||" is handled a level up; a lone '/' here is division
                    index++;
                    Value right = parseUnary();
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
                        return resolveVariable(digits);
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
                case "nth" -> nth(args);
                case "contains" -> contains(args);
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

        /** contains(list, value) — membership by == semantics; mixed-type elements just don't match. */
        private Value contains(List<Value> args) {
            if (args.size() != 2) {
                throw new ExpressionException("contains(list, value) takes a list and a value");
            }
            if (!(args.get(0) instanceof Value.ListValue list)) {
                throw new ExpressionException("contains(list, value): the first argument must be a list, e.g. contains(blockset(#minecraft:logs), block(client.target_block))");
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
            String tag = null; // null = every entry in the registry
            boolean explicitTag = false;
            if (!args.isEmpty()) {
                Value arg = single(args, name);
                if (!(arg instanceof Value.StringValue string)) {
                    throw new ExpressionException(name + "(...) takes a #tag or a concrete id — " + name + "(#minecraft:logs) — or no argument for the whole registry");
                }
                tag = string.value().trim();
                if (tag.startsWith("#")) {
                    explicitTag = true;
                    tag = tag.substring(1);
                }
                if (tag.isEmpty()) {
                    throw new ExpressionException(name + "(...) needs a tag id, e.g. " + name + "(#minecraft:logs) — or no argument for the whole registry");
                }
            }
            List<String> ids = context.tags().resolve(kind, tag);
            if (ids == null) {
                throw new ExpressionException(name + "(...) needs a live world to look up the registry");
            }
            if (ids.isEmpty() && tag != null && !explicitTag) {
                // not a tag — a concrete id makes a one-element set, so
                // "block OR blockset" params feed the same functions
                String canonical = context.tags().lookup(kind, tag);
                if (canonical != null) {
                    ids = List.of(canonical);
                }
            }
            if (ids.isEmpty()) {
                throw new ExpressionException("Unknown " + kind.name().toLowerCase(java.util.Locale.ROOT)
                        + (explicitTag ? " tag: #" + tag : " tag or id: " + tag));
            }
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
         * and pos params bind: block(client.target_block) == "minecraft:air".
         */
        private Value blockAt(List<Value> args) {
            long[] position = new long[3];
            if (args.size() == 1 && args.get(0) instanceof Value.StringValue string) {
                String[] parts = string.value().trim().split("\\s+");
                if (parts.length != 3) {
                    throw new ExpressionException("block(\"x y z\") needs three coordinates, got \"" + string.value() + "\"");
                }
                for (int i = 0; i < 3; i++) {
                    try {
                        position[i] = Rational.parse(parts[i]).floor().wholeValue().longValueExact();
                    } catch (IllegalArgumentException | ArithmeticException ex) {
                        throw new ExpressionException("block(...): bad coordinate '" + parts[i] + "'");
                    }
                }
            } else if (args.size() == 3) {
                for (int i = 0; i < 3; i++) {
                    try {
                        position[i] = asNumber(args.get(i), "block(...)").floor().wholeValue().longValueExact();
                    } catch (ArithmeticException ex) {
                        throw new ExpressionException("block(...): coordinate out of range");
                    }
                }
            } else {
                throw new ExpressionException("block(x, y, z) or block(\"x y z\") — e.g. block(client.target_block)");
            }

            String id = context.blocks().blockAt(position[0], position[1], position[2]);
            if (id == null) {
                throw new ExpressionException("block(...): that position isn't loaded (or there's no world)");
            }
            return Value.of(id);
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
                    return options.get(context.random().nextInt(options.size()));
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
            candidates.addAll(List.of("rand", "pick", "int", "float", "true", "false", "itemset", "blockset", "effectset", "entityset", "block", "nth", "contains"));
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
