package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * User variables written by #set. Session tier: lives until disconnect
 * (cleared on join under the same resetOnNewSession config that clears
 * history). Names are case-insensitive.
 */
public final class SessionVariableStore implements VariableProvider {
    private final Map<String, Value> values = new LinkedHashMap<>();

    public void set(String name, Value value) {
        values.put(normalize(name), value);
    }

    public void clear() {
        values.clear();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Insertion-ordered snapshot for /tupenter vars. */
    public Map<String, Value> snapshot() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public Optional<Value> resolve(String name) {
        return Optional.ofNullable(values.get(normalize(name)));
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
