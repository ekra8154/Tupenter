package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * THE entity field reader. One field vocabulary, three subjects:
 *
 * <ul>
 *   <li>{@code client.<field>} — you</li>
 *   <li>{@code client.target.<field>} — the entity under your crosshair</li>
 *   <li>{@code entity(uuid, "<field>")} — any loaded entity</li>
 * </ul>
 *
 * <p>All three route here, so learning one subject teaches the others and the
 * three spellings cannot drift apart — the way client.held_durability and
 * client.slot.*.durability once disagreed by having two implementations.
 *
 * <p>Direct fields are OUR api: lowercase, normalized, and stable across
 * Minecraft versions. {@code nbt.<path>} is the escape hatch — Mojang's save
 * format verbatim, their spelling, their version churn. A field only earns a
 * direct name when the raw path can't serve: it isn't in NBT at all (type), its
 * NBT form is unusable (uuid is an int array), or it shields version churn.
 */
public final class EntityFields {
    /** Fields valid for any entity — offered by tab-complete on every subject. */
    public static final List<String> FIELDS =
            List.of("type", "uuid", "name", "health", "pos", "blockpos", "nbt.");

    private static final String NBT = "nbt.";

    private EntityFields() {
    }

    /** "self" / "target" / a UUID string → the entity, or a clear error. */
    public static Entity resolve(String selector) {
        String key = selector.trim();
        if (key.equalsIgnoreCase("self")) {
            return EntityNbtVariableProvider.clientEntity();
        }
        if (key.equalsIgnoreCase("target")) {
            return EntityNbtVariableProvider.targetEntity();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw new ExpressionException("entity selector '" + selector
                    + "' isn't \"self\", \"target\", or a valid UUID");
        }
        ClientLevel level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(uuid);
        if (entity == null) {
            throw new ExpressionException("no entity with UUID " + uuid
                    + " is loaded on the client (out of range, or not synced)");
        }
        return entity;
    }

    /** One field of one entity. {@code nbt.<path>} falls through to the raw tree. */
    public static Value read(Entity entity, String field) {
        String key = field.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith(NBT)) {
            return EntityNbtVariableProvider.read(entity, key.substring(NBT.length()), "nbt." );
        }
        return switch (lower) {
            case "type" -> Value.of(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            case "uuid" -> Value.of(entity.getStringUUID());
            case "name" -> Value.of(entity.getName().getString());
            case "health" -> {
                if (!(entity instanceof LivingEntity living)) {
                    throw new ExpressionException("health: " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                            + " isn't a living entity");
                }
                yield Value.ofNumber(living.getHealth());
            }
            case "pos" -> Value.of(round2(entity.getX()) + " " + round2(entity.getY()) + " " + round2(entity.getZ()));
            case "pos.x" -> Value.ofNumber(entity.getX());
            case "pos.y" -> Value.ofNumber(entity.getY());
            case "pos.z" -> Value.ofNumber(entity.getZ());
            case "blockpos" -> {
                BlockPos pos = entity.blockPosition();
                yield Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            case "blockpos.x" -> Value.ofNumber(entity.blockPosition().getX());
            case "blockpos.y" -> Value.ofNumber(entity.blockPosition().getY());
            case "blockpos.z" -> Value.ofNumber(entity.blockPosition().getZ());
            default -> throw new ExpressionException("unknown entity field '" + field + "' — use "
                    + String.join(", ", FIELDS) + "<path> (e.g. nbt.Health)");
        };
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
