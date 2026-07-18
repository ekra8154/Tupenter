package net.tupenter.script;

import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Real-world clock variables — not Minecraft data, just script-useful. */
public final class RealTimeVariableProvider implements VariableProvider {
    private final Map<String, Function<LocalDateTime, Value>> variables = new LinkedHashMap<>();

    public RealTimeVariableProvider() {
        register("real.hour", now -> Value.ofNumber(now.getHour()));
        register("real.minute", now -> Value.ofNumber(now.getMinute()));
        register("real.second", now -> Value.ofNumber(now.getSecond()));
        register("real.day", now -> Value.ofNumber(now.getDayOfMonth()));
        register("real.month", now -> Value.ofNumber(now.getMonthValue()));
        register("real.year", now -> Value.ofNumber(now.getYear()));
        register("real.day_of_week", now -> Value.of(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT)));
        register("real.timestamp", now -> Value.ofNumber(System.currentTimeMillis() / 1000L));
    }

    private void register(String name, Function<LocalDateTime, Value> reader) {
        variables.put(name, reader);
    }

    @Override
    public Set<String> names() {
        return variables.keySet();
    }

    @Override
    public Optional<Value> resolve(String name) {
        Function<LocalDateTime, Value> reader = variables.get(name.toLowerCase(Locale.ROOT));
        return reader == null ? Optional.empty() : Optional.of(reader.apply(LocalDateTime.now()));
    }
}
