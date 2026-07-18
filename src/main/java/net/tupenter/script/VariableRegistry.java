package net.tupenter.script;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolution chain over multiple {@link VariableProvider}s, first match wins.
 * Order (docs/SCRIPTING_DESIGN.md §5.4): script scope → session → persistent
 * → live providers (client.*, ...).
 */
public final class VariableRegistry implements VariableProvider {
    private final List<VariableProvider> providers = new ArrayList<>();

    public void register(VariableProvider provider) {
        providers.add(provider);
    }

    @Override
    public Set<String> names() {
        Set<String> names = new HashSet<>();
        for (VariableProvider provider : providers) {
            names.addAll(provider.names());
        }
        return names;
    }

    @Override
    public Optional<Value> resolve(String name) {
        for (VariableProvider provider : providers) {
            Optional<Value> value = provider.resolve(name);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }
}
