package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.tupenter.script.EntityAccess;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The client-side {@link EntityAccess}: NBT reads plus UUID finders, all off the
 * synced client world (~render distance). readNbt reuses {@link EntityNbtVariableProvider}
 * (the same walk the target.nbt / client.nbt variables use); the finders return
 * the "miss" sentinel / an empty list so scripts gate them, never crash on nothing.
 */
public final class EntityAccessImpl implements EntityAccess {

    @Override
    public Value readNbt(String selector, String path) {
        return EntityNbtVariableProvider.read(resolveEntity(selector), path, "entity_nbt");
    }

    @Override
    public String typeId(String selector) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(resolveEntity(selector).getType()).toString();
    }

    @Override
    public String slotItem(String slot) {
        net.minecraft.world.item.ItemStack stack = stackAt(slot);
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public int slotCount(String slot) {
        return stackAt(slot).getCount();
    }

    @Override
    public int slotDurability(String slot) {
        net.minecraft.world.item.ItemStack stack = stackAt(slot);
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0;
    }

    /**
     * The live stack in one of the player's slots, addressed by /item replace
     * name. Parsed with vanilla's own SlotArgument so the vocabulary is exactly
     * the one those commands accept, then read through Entity.getSlot — which
     * reaches armor and offhand too, not just the inventory array.
     */
    public static net.minecraft.world.item.ItemStack stackAt(String slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new ExpressionException("slot_item(...) is only available in-game");
        }
        int id;
        try {
            id = new net.minecraft.commands.arguments.SlotArgument()
                    .parse(new com.mojang.brigadier.StringReader(slot.trim()));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            throw new ExpressionException("unknown slot '" + slot + "' — use /item replace names: "
                    + "hotbar.0-8, inventory.0-26, armor.head/chest/legs/feet, weapon.mainhand, weapon.offhand");
        }
        return player.getSlot(id).get();
    }

    /** "self" / "target" / a UUID → the entity, or a clear error. */
    private static Entity resolveEntity(String selector) {
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

    @Override
    public String raycastUuid(double maxDist) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().level == null) {
            return "miss";
        }
        Vec3 eye = player.getEyePosition();
        Vec3 to = eye.add(player.getViewVector(1.0F).scale(maxDist));
        AABB search = player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(maxDist)).inflate(1.0);
        Predicate<Entity> filter = e -> e != player && e.isPickable() && !e.isSpectator();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, to, search, filter, maxDist * maxDist);
        return hit == null ? "miss" : hit.getEntity().getStringUUID();
    }

    @Override
    public List<String> nearbyUuids(double radius, String type) {
        List<String> out = new ArrayList<>();
        for (Entity e : nearby(radius, type)) {
            out.add(e.getStringUUID());
        }
        return out;
    }

    @Override
    public String nearestUuid(double radius, String type) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return "miss";
        }
        Entity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Entity e : nearby(radius, type)) {
            double d = e.distanceToSqr(player);
            if (d < bestSqr) {
                bestSqr = d;
                best = e;
            }
        }
        return best == null ? "miss" : best.getStringUUID();
    }

    /** Entities within {@code radius} blocks of the player (spherical), optional type filter. */
    private static List<Entity> nearby(double radius, String type) {
        LocalPlayer player = Minecraft.getInstance().player;
        ClientLevel level = Minecraft.getInstance().level;
        if (player == null || level == null) {
            return List.of();
        }
        String wantType = canonicalType(type);
        double radiusSqr = radius * radius;
        AABB box = player.getBoundingBox().inflate(radius);
        List<Entity> hits = new ArrayList<>();
        for (Entity e : level.getEntities(player, box, e -> !e.isSpectator())) {
            if (e.distanceToSqr(player) > radiusSqr) {
                continue; // box is a cube; keep it a true sphere
            }
            if (wantType != null && !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(wantType)) {
                continue;
            }
            hits.add(e);
        }
        return hits;
    }

    /** Normalize a user type filter ("zombie" or "minecraft:zombie") to a canonical id, or null for "any". */
    private static String canonicalType(String type) {
        if (type == null) {
            return null;
        }
        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null) {
            throw new ExpressionException("entities/nearest_entity: '" + type + "' isn't a valid entity id");
        }
        return id.toString().toLowerCase(Locale.ROOT);
    }
}
