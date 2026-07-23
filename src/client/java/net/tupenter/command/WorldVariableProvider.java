package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VarDoc;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared world/environment state as read-only variables. Everything here is
 * data the client already has synced — no server queries. Registration
 * requires a blurb, so the help pages render straight from here.
 */
public final class WorldVariableProvider implements VariableProvider {

    private record Entry(String blurb, Function<ClientLevel, Value> reader) {
    }

    private final Map<String, Entry> variables = new LinkedHashMap<>();

    public WorldVariableProvider() {
        register("world.difficulty", "the difficulty setting", level -> Value.of(level.getDifficulty().getKey()));
        register("world.time", "time of day in ticks, 0-23999", level -> Value.ofNumber(level.getDayTime() % 24000L));
        register("world.day", "the day count", level -> Value.ofNumber(level.getDayTime() / 24000L));
        register("world.raining", "raining?", level -> Value.of(level.isRaining()));
        register("world.thundering", "thundering?", level -> Value.of(level.isThundering()));
        register("world.moon_phase", "the moon phase, 0-7 (0 = full)", level -> Value.ofNumber(level.getMoonPhase()));
        register("world.dimension", "this world's dimension id", level -> Value.of(level.dimension().location().toString()));
        register("world.spawn", "the world spawn — \"x y z\" (+ .x/.y/.z)", level -> {
            net.minecraft.core.BlockPos pos = level.getLevelData().getRespawnData().pos();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        });
        register("world.spawn.x", "the x of world.spawn", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getX()));
        register("world.spawn.y", "the y of world.spawn", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getY()));
        register("world.spawn.z", "the z of world.spawn", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getZ()));
        register("world.time_total", "total world ticks — never wraps", level -> Value.ofNumber(level.getGameTime()));
        register("world.min_y", "the lowest buildable y", level -> Value.ofNumber(level.getMinY()));
        register("world.max_y", "the highest buildable y", level -> Value.ofNumber(level.getMaxY()));
        // tick state — the CONFIGURED rate + freeze, synced from the server (exact,
        // not a measured/estimated TPS): what /tick rate|freeze|step set.
        register("world.tickrate", "the configured /tick rate (usually 20)", level -> Value.ofNumber(level.tickRateManager().tickrate()));
        register("world.frozen", "is /tick freeze on?", level -> Value.of(level.tickRateManager().isFrozen()));
        register("world.stepping", "mid /tick step?", level -> Value.of(level.tickRateManager().isSteppingForward()));
        // the per-world scripts identity key: "server:<address>" / "world:<folder>"
        register("world.key", "this world's scripts identity key (server:<ip> / world:<folder>)", level -> {
            String key = net.tupenter.TupenterModClient.currentWorldKey();
            if (key == null) {
                throw new ExpressionException("world.key is only available in-game");
            }
            return Value.of(key);
        });
    }

    private void register(String name, String blurb, Function<ClientLevel, Value> reader) {
        variables.put(name, new Entry(blurb, reader));
    }

    /** Every variable's doc, in registration order — the world subject page renders from this. */
    public List<VarDoc> docs() {
        List<VarDoc> docs = new ArrayList<>();
        variables.forEach((name, entry) -> docs.add(new VarDoc(name, "World", entry.blurb())));
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
        if (entry == null) {
            return Optional.empty();
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new ExpressionException(name + " is only available in-game");
        }
        return Optional.of(entry.reader().apply(level));
    }
}
