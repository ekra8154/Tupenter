package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Live client state as read-only variables (docs/SCRIPTING_DESIGN.md §5.3).
 * Adding one is a single registration line — the parser picks it up for
 * resolution and error suggestions automatically.
 */
public final class ClientVariableProvider implements VariableProvider {
    private final Map<String, Function<LocalPlayer, Value>> variables = new LinkedHashMap<>();

    public ClientVariableProvider() {
        register("client.x", player -> Value.ofNumber(player.getX()));
        register("client.y", player -> Value.ofNumber(player.getY()));
        register("client.z", player -> Value.ofNumber(player.getZ()));
        register("client.bx", player -> Value.ofNumber(player.blockPosition().getX()));
        register("client.by", player -> Value.ofNumber(player.blockPosition().getY()));
        register("client.bz", player -> Value.ofNumber(player.blockPosition().getZ()));
        register("client.yaw", player -> Value.ofNumber(player.getYRot()));
        register("client.pitch", player -> Value.ofNumber(player.getXRot()));
        register("client.health", player -> Value.ofNumber(player.getHealth()));
        register("client.food", player -> Value.ofNumber(player.getFoodData().getFoodLevel()));
        register("client.air", player -> Value.ofNumber(player.getAirSupply()));
        register("client.name", player -> Value.of(player.getName().getString()));
        register("client.dimension", player -> Value.of(player.level().dimension().location().toString()));
        register("client.held_item", player -> Value.of(BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString()));
        register("client.pos", player -> {
            BlockPos pos = player.blockPosition();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        });
        register("client.target_block", ClientVariableProvider::targetBlock);
    }

    private void register(String name, Function<LocalPlayer, Value> reader) {
        variables.put(name, reader);
    }

    private static Value targetBlock(LocalPlayer player) {
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        throw new ExpressionException("client.target_block: no block under the crosshair");
    }

    @Override
    public Set<String> names() {
        return variables.keySet();
    }

    @Override
    public Optional<Value> resolve(String name) {
        Function<LocalPlayer, Value> reader = variables.get(name.toLowerCase(java.util.Locale.ROOT));
        if (reader == null) {
            return Optional.empty();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new ExpressionException(name + " is only available in-game");
        }
        return Optional.of(reader.apply(player));
    }
}
