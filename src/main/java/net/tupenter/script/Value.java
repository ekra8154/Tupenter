package net.tupenter.script;

/** A typed expression result (docs/SCRIPTING_DESIGN.md §5.1). */
public sealed interface Value {

    static Value of(boolean value) {
        return new BoolValue(value);
    }

    static Value of(String value) {
        return new StringValue(value);
    }

    static Value ofNumber(long value) {
        return new NumberValue(Rational.of(value));
    }

    /** Parses a plain decimal string, e.g. "64" or "12.5". */
    static Value ofNumber(String decimal) {
        return new NumberValue(Rational.parse(decimal));
    }

    static Value ofNumber(double value) {
        return new NumberValue(Rational.parse(java.math.BigDecimal.valueOf(value).toPlainString()));
    }

    /** Text inserted into a command. Booleans refuse — they belong in conditions. */
    String substitutionString();

    /** Text shown to the user (e.g. /calc output). Booleans allowed here. */
    default String displayString() {
        return substitutionString();
    }

    /**
     * Text when the value is a custom command's ARGUMENT (not free command text).
     * Same as {@link #substitutionString()} except a list flattens to its members
     * space-separated instead of erroring: a set-valued argument then round-trips,
     * because blockset/itemset/... read a space-separated string as a whole set —
     * /sphere ~ ~ ~ 5 $blockset("minecraft:stone", #wool)$. Free command text keeps
     * the strict behavior, so /give @s $blockset(#logs)$ still errors helpfully.
     */
    default String argumentString() {
        return substitutionString();
    }

    record NumberValue(Rational value) implements Value {
        @Override
        public String substitutionString() {
            return value.toCommandString();
        }
    }

    record StringValue(String value) implements Value {
        @Override
        public String substitutionString() {
            return value;
        }
    }

    record BoolValue(boolean value) implements Value {
        @Override
        public String substitutionString() {
            throw new ExpressionException("A true/false value can't be inserted into a command — use it in a condition, e.g. $" + value + " ? a : b$");
        }

        @Override
        public String displayString() {
            return String.valueOf(value);
        }
    }

    /** Produced by range(...); consumable only by #foreach. */
    record ListValue(java.util.List<Value> values) implements Value {
        @Override
        public String substitutionString() {
            throw new ExpressionException("A list can't be inserted into a command — loop over it with #foreach");
        }

        /**
         * As a command ARGUMENT a list flattens to its members joined by commas —
         * comma, not space, so the whole set stays ONE argument token and the
         * params after it still parse. blockset/itemset/... split it back apart.
         */
        @Override
        public String argumentString() {
            StringBuilder builder = new StringBuilder();
            for (Value value : values) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(value.argumentString());
            }
            return builder.toString();
        }

        @Override
        public String displayString() {
            StringBuilder builder = new StringBuilder("(");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) builder.append(" | ");
                builder.append(values.get(i).displayString());
            }
            return builder.append(")").toString();
        }
    }
}
