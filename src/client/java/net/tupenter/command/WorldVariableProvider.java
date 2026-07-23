package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared world/environment state as read-only variables. Everything here is
 * data the client already has synced — no server queries.
 */
public final class WorldVariableProvider implements VariableProvider {
    private final Map<String, Function<ClientLevel, Value>> variables = new LinkedHashMap<>();

    public WorldVariableProvider() {
        register("world.difficulty", level -> Value.of(level.getDifficulty().getKey()));
        register("world.time", level -> Value.ofNumber(level.getDayTime() % 24000L)); // time of day in ticks
        register("world.day", level -> Value.ofNumber(level.getDayTime() / 24000L));
        register("world.raining", level -> Value.of(level.isRaining()));
        register("world.thundering", level -> Value.of(level.isThundering()));
        register("world.moon_phase", level -> Value.ofNumber(level.getMoonPhase()));
        register("world.dimension", level -> Value.of(level.dimension().location().toString()));
        register("world.spawn", level -> {
            net.minecraft.core.BlockPos pos = level.getLevelData().getRespawnData().pos();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        });
        register("world.spawn.x", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getX()));
        register("world.spawn.y", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getY()));
        register("world.spawn.z", level -> Value.ofNumber(level.getLevelData().getRespawnData().pos().getZ()));
        register("world.time_total", level -> Value.ofNumber(level.getGameTime())); // total ticks, never wraps
        register("world.min_y", level -> Value.ofNumber(level.getMinY()));
        register("world.max_y", level -> Value.ofNumber(level.getMaxY()));
        // tick state — the CONFIGURED rate + freeze, synced from the server (exact,
        // not a measured/estimated TPS): what /tick rate|freeze|step set.
        register("world.tickrate", level -> Value.ofNumber(level.tickRateManager().tickrate())); // e.g. 20
        register("world.frozen", level -> Value.of(level.tickRateManager().isFrozen())); // /tick freeze active
        register("world.stepping", level -> Value.of(level.tickRateManager().isSteppingForward())); // mid /tick step
        // the per-world scripts identity key: "server:<address>" / "world:<folder>"
        register("world.key", level -> {
            String key = net.tupenter.TupenterModClient.currentWorldKey();
            if (key == null) {
                throw new ExpressionException("world.key is only available in-game");
            }
            return Value.of(key);
        });
    }

    private void register(String name, Function<ClientLevel, Value> reader) {
        variables.put(name, reader);
    }

    @Override
    public Set<String> names() {
        return variables.keySet();
    }

    @Override
    public Optional<Value> resolve(String name) {
        Function<ClientLevel, Value> reader = variables.get(name.toLowerCase(Locale.ROOT));
        if (reader == null) {
            return Optional.empty();
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new ExpressionException(name + " is only available in-game");
        }
        return Optional.of(reader.apply(level));
    }
}
