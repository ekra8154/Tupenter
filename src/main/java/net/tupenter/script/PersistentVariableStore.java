package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Variables promoted to disk with /tupenter var save. Serialized as
 * "name = literal" strings (numbers bare, text quoted, booleans true/false)
 * so types round-trip through the config file.
 */
public final class PersistentVariableStore implements VariableProvider {
    private final Map<String, Value> values = new LinkedHashMap<>();

    public void set(String name, Value value) {
        if (value instanceof Value.ListValue) {
            throw new ExpressionException("Lists can't be saved as persistent variables");
        }
        values.put(name.toLowerCase(Locale.ROOT), value);
    }

    public boolean remove(String name) {
        return values.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public Map<String, Value> snapshot() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public Optional<Value> resolve(String name) {
        return Optional.ofNullable(values.get(name.toLowerCase(Locale.ROOT)));
    }

    // --- config (de)serialization ---

    /** Replaces the store contents from config definition strings. */
    public void load(List<String> definitions) {
        values.clear();
        if (definitions == null) {
            return;
        }
        for (String definition : definitions) {
            int separator = definition.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = definition.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String literal = definition.substring(separator + 1).trim();
            if (name.isEmpty() || literal.isEmpty()) {
                continue;
            }
            try {
                values.put(name, ExpressionEvaluator.evaluate(literal, new EvalContext(new Random())));
            } catch (IllegalArgumentException ignored) {
                // malformed entry — skip rather than crash the config load
            }
        }
    }

    /** Definition strings for the config file. */
    public List<String> serialize() {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + " = " + toLiteral(entry.getValue()))
                .toList();
    }

    private static String toLiteral(Value value) {
        if (value instanceof Value.StringValue string) {
            return '"' + string.value().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return value.displayString();
    }
}
