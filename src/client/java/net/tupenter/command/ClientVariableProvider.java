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
        register("client.target_hit", ClientVariableProvider::targetHit);
        register("client.target_entity", ClientVariableProvider::targetEntity);

        // movement + orientation
        register("client.speed", player -> {
            net.minecraft.world.phys.Vec3 velocity = player.getDeltaMovement();
            return Value.ofNumber(round2(Math.hypot(velocity.x, velocity.z) * 20)); // horizontal blocks/sec
        });
        register("client.speed_y", player -> Value.ofNumber(round2(player.getDeltaMovement().y * 20)));
        register("client.facing", player -> Value.of(player.getDirection().getName())); // north/south/east/west
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
        register("client.slot", player -> Value.ofNumber(player.getInventory().getSelectedSlot())); // 0-8, /item-compatible
        register("client.offhand_item", player -> Value.of(
                BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString()));

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
        register("client.eye_y", player -> Value.ofNumber(round2(player.getEyeY())));

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
        register("client.vehicle", player -> {
            net.minecraft.world.entity.Entity vehicle = player.getVehicle();
            if (vehicle == null) {
                throw new ExpressionException("client.vehicle: not riding anything — check $client.riding$ first");
            }
            return Value.of(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
        });

        // held-item detail
        register("client.held_count", player -> Value.ofNumber(player.getMainHandItem().getCount()));
        register("client.offhand_count", player -> Value.ofNumber(player.getOffhandItem().getCount()));
        register("client.held_durability", player -> {
            net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
            if (!stack.isDamageableItem()) {
                throw new ExpressionException("client.held_durability: the held item has no durability");
            }
            return Value.ofNumber(stack.getMaxDamage() - stack.getDamageValue());
        });
        register("client.held_max_durability", player -> {
            net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
            if (!stack.isDamageableItem()) {
                throw new ExpressionException("client.held_max_durability: the held item has no durability");
            }
            return Value.ofNumber(stack.getMaxDamage());
        });
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

    private static Value targetBlock(LocalPlayer player) {
        // a MISS is still a BlockHitResult (holding the ray's endpoint) —
        // returning that would silently teleport people into the air, so
        // only an actual block hit counts; gate with $client.target_hit$
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit
                && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            BlockPos pos = hit.getBlockPos();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        throw new ExpressionException("client.target_block: no block under the crosshair — check $client.target_hit$ first");
    }

    /** Entity type id under the crosshair, e.g. "minecraft:zombie". */
    private static Value targetEntity(LocalPlayer player) {
        if (Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) {
            return Value.of(BuiltInRegistries.ENTITY_TYPE.getKey(hit.getEntity().getType()).toString());
        }
        throw new ExpressionException("client.target_entity: no entity under the crosshair — check $client.target_hit$ first");
    }

    /** "block", "entity", or "miss" — what the crosshair ray actually found. */
    private static Value targetHit(LocalPlayer player) {
        net.minecraft.world.phys.HitResult hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return Value.of("miss");
        }
        return Value.of(hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY ? "entity" : "block");
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
