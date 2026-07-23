package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.MissingValueException;
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
        // Two coordinate SPACES, one shape each — matching Mojang's
        // position() -> Vec3 (precise) and blockPosition() -> BlockPos (whole).
        // Components hang off the vec, so there's no parallel scalar vocabulary.
        register("client.pos", player -> Value.of(
                round2(player.getX()) + " " + round2(player.getY()) + " " + round2(player.getZ())));
        register("client.pos.x", player -> Value.ofNumber(player.getX()));
        register("client.pos.y", player -> Value.ofNumber(player.getY()));
        register("client.pos.z", player -> Value.ofNumber(player.getZ()));
        register("client.blockpos", player -> {
            BlockPos pos = player.blockPosition();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        });
        register("client.blockpos.x", player -> Value.ofNumber(player.blockPosition().getX()));
        register("client.blockpos.y", player -> Value.ofNumber(player.blockPosition().getY()));
        register("client.blockpos.z", player -> Value.ofNumber(player.blockPosition().getZ()));
        register("client.yaw", player -> Value.ofNumber(player.getYRot()));
        register("client.pitch", player -> Value.ofNumber(player.getXRot()));
        register("client.health", player -> Value.ofNumber(player.getHealth()));
        register("client.food", player -> Value.ofNumber(player.getFoodData().getFoodLevel()));
        register("client.air", player -> Value.ofNumber(player.getAirSupply()));
        register("client.name", player -> Value.of(player.getName().getString()));
        register("client.dimension", player -> Value.of(player.level().dimension().location().toString()));

        // movement + orientation
        register("client.speed", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.ofNumber(round2(v.length() * 20)); // full 3D blocks/sec
        });
        register("client.speed_xz", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.ofNumber(round2(Math.hypot(v.x, v.z) * 20)); // horizontal blocks/sec
        });
        // (no speed_y — that's motion.y)
        register("client.motion", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.of(round2(v.x * 20) + " " + round2(v.y * 20) + " " + round2(v.z * 20)); // vec3, blocks/sec
        });
        register("client.motion.x", player -> Value.ofNumber(round2(player.getDeltaMovement().x * 20)));
        register("client.motion.y", player -> Value.ofNumber(round2(player.getDeltaMovement().y * 20)));
        register("client.motion.z", player -> Value.ofNumber(round2(player.getDeltaMovement().z * 20)));
        register("client.facing", player -> Value.of(player.getDirection().getName())); // north/south/east/west
        register("client.eye_pos", player -> {
            net.minecraft.world.phys.Vec3 e = player.getEyePosition();
            return Value.of(round2(e.x) + " " + round2(e.y) + " " + round2(e.z)); // precise eye vec3 — a ray origin
        });
        register("client.look", player -> {
            net.minecraft.world.phys.Vec3 v = player.getLookAngle();
            return Value.of(round2(v.x) + " " + round2(v.y) + " " + round2(v.z)); // unit look direction — a ray dir
        });
        // Full-precision components — vec strings can't be indexed in an expression, so
        // expose x/y/z directly for math (e.g. Motion:[look.x*speed, ...]). Not rounded:
        // rounding is a display convenience; math wants the exact unit vector.
        register("client.look.x", player -> Value.ofNumber(player.getLookAngle().x));
        register("client.look.y", player -> Value.ofNumber(player.getLookAngle().y));
        register("client.look.z", player -> Value.ofNumber(player.getLookAngle().z));
        register("client.eye_pos.x", player -> Value.ofNumber(player.getEyePosition().x));
        register("client.eye_pos.y", player -> Value.ofNumber(player.getEyePosition().y));
        register("client.eye_pos.z", player -> Value.ofNumber(player.getEyePosition().z));
        register("client.on_ground", player -> Value.of(player.onGround()));
        register("client.sneaking", player -> Value.of(player.isCrouching()));
        register("client.sprinting", player -> Value.of(player.isSprinting()));
        register("client.swimming", player -> Value.of(player.isSwimming()));
        register("client.flying", player -> Value.of(player.getAbilities().flying));
        register("client.gliding", player -> Value.of(player.isFallFlying())); // elytra

        // position context
        register("client.biome", player -> player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> Value.of(key.location().toString()))
                .orElseThrow(() -> new ExpressionException("client.biome: unregistered biome")));
        register("client.light", player -> Value.ofNumber(player.level().getMaxLocalRawBrightness(player.blockPosition())));
        register("client.light_block", player -> Value.ofNumber(
                player.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, player.blockPosition())));
        register("client.light_sky", player -> Value.ofNumber(
                player.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, player.blockPosition())));
        register("client.chunk_x", player -> Value.ofNumber(player.chunkPosition().x));
        register("client.chunk_z", player -> Value.ofNumber(player.chunkPosition().z));

        // stats
        register("client.max_health", player -> Value.ofNumber(player.getMaxHealth()));
        register("client.absorption", player -> Value.ofNumber(player.getAbsorptionAmount()));
        register("client.armor", player -> Value.ofNumber(player.getArmorValue()));
        register("client.saturation", player -> Value.ofNumber(player.getFoodData().getSaturationLevel()));
        register("client.xp_level", player -> Value.ofNumber(player.experienceLevel));
        register("client.xp_progress", player -> Value.ofNumber(round2(player.experienceProgress))); // 0..1

        // inventory
        // which hotbar slot is SELECTED (0-8). Named ...selected_slot because a bare
        // "client.slot" read like a namespace root — that's now slot CONTENTS,
        // client.slot.<slot>.<field> (see SlotVariableProvider).
        register("client.selected_slot", player -> Value.ofNumber(player.getInventory().getSelectedSlot()));

        // connection/session
        register("client.gamemode", player -> Value.of(playerInfo(player).getGameMode().getName()));
        register("client.ping", player -> Value.ofNumber(playerInfo(player).getLatency()));
        register("client.fps", player -> Value.ofNumber(Minecraft.getInstance().getFps()));
        register("client.uuid", player -> Value.of(player.getStringUUID()));
        // NBT-flavored UUID for Owner-style fields: {Owner:[I;a,b,c,d]}
        register("client.uuid_nbt", player -> {
            int[] parts = net.minecraft.core.UUIDUtil.uuidToIntArray(player.getUUID());
            return Value.of("[I;" + parts[0] + "," + parts[1] + "," + parts[2] + "," + parts[3] + "]");
        });

        // hazards + surroundings
        register("client.in_water", player -> Value.of(player.isInWater()));
        register("client.underwater", player -> Value.of(player.isUnderWater())); // eyes submerged
        register("client.in_lava", player -> Value.of(player.isInLava()));
        register("client.on_fire", player -> Value.of(player.isOnFire()));
        register("client.fall_distance", player -> Value.ofNumber(round2(player.fallDistance)));

        // effects — a list, so len(), rand(), and #foreach all compose
        register("client.effects", player -> {
            java.util.List<Value> ids = new java.util.ArrayList<>();
            for (net.minecraft.world.effect.MobEffectInstance effect : player.getActiveEffects()) {
                effect.getEffect().unwrapKey().ifPresent(key -> ids.add(Value.of(key.location().toString())));
            }
            return new Value.ListValue(java.util.List.copyOf(ids));
        });

        // riding
        register("client.riding", player -> Value.of(player.getVehicle() != null));

        // 0 (not an error) for a non-damageable item — the same answer
        // client.slot.<slot>.durability gives, so the two never disagree.
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static net.minecraft.client.multiplayer.PlayerInfo playerInfo(LocalPlayer player) {
        net.minecraft.client.multiplayer.PlayerInfo info = player.connection.getPlayerInfo(player.getUUID());
        if (info == null) {
            throw new ExpressionException("player info isn't synced yet — try again in a moment");
        }
        return info;
    }

    private void register(String name, Function<LocalPlayer, Value> reader) {
        variables.put(name, reader);
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
