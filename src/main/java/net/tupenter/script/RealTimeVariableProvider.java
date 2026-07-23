package net.tupenter.script;

import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Real-world clock variables — not Minecraft data, just script-useful.
 * Registration requires a blurb; the help pages render straight from here.
 */
public final class RealTimeVariableProvider implements VariableProvider {

    private record Entry(String blurb, Function<LocalDateTime, Value> reader) {
    }

    private final Map<String, Entry> variables = new LinkedHashMap<>();

    public RealTimeVariableProvider() {
        register("real.hour", "the hour, 0-23", now -> Value.ofNumber(now.getHour()));
        register("real.minute", "the minute, 0-59", now -> Value.ofNumber(now.getMinute()));
        register("real.second", "the second, 0-59", now -> Value.ofNumber(now.getSecond()));
        register("real.day", "day of the month", now -> Value.ofNumber(now.getDayOfMonth()));
        register("real.month", "the month, 1-12", now -> Value.ofNumber(now.getMonthValue()));
        register("real.year", "the year", now -> Value.ofNumber(now.getYear()));
        register("real.day_of_week", "monday..sunday, lowercase", now -> Value.of(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT)));
        register("real.timestamp", "unix time in seconds", now -> Value.ofNumber(System.currentTimeMillis() / 1000L));
    }

    private void register(String name, String blurb, Function<LocalDateTime, Value> reader) {
        variables.put(name, new Entry(blurb, reader));
    }

    /** Every variable's doc, in registration order — the real subject page renders from this. */
    public List<VarDoc> docs() {
        List<VarDoc> docs = new ArrayList<>();
        variables.forEach((name, entry) -> docs.add(new VarDoc(name, "Real time", entry.blurb())));
        return docs;
    }

    @Override
    public String describe(String name) {
        Entry entry = variables.get(name.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.blurb();
    }

    @Override
    public Set<String> names() {
        return variables.keySet();
    }

    @Override
    public Optional<Value> resolve(String name) {
        Entry entry = variables.get(name.toLowerCase(Locale.ROOT));
        return entry == null ? Optional.empty() : Optional.of(entry.reader().apply(LocalDateTime.now()));
    }
}
