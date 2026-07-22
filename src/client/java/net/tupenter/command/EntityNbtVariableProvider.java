package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The "parse whatever they put in there" provider: resolves ANY path under
 * client.nbt.* (your own entity data) or target.nbt.* (the entity under your
 * crosshair) by walking the entity's NBT tree at evaluation time. Nothing is
 * enumerated — coverage is whatever the client has synced. Browse paths with
 * /tupenter dump.
 */
public final class EntityNbtVariableProvider implements VariableProvider {
    private static final String CLIENT_PREFIX = "client.nbt.";
    private static final String TARGET_PREFIX = "target.nbt.";

    @Override
    public Set<String> names() {
        // dynamic — advertise just the roots so suggestions can hint at them
        return Set.of("client.nbt", "target.nbt");
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(CLIENT_PREFIX)) {
            return Optional.of(resolvePath(clientEntity(), name.substring(CLIENT_PREFIX.length()), name));
        }
        if (lower.startsWith(TARGET_PREFIX)) {
            return Optional.of(resolvePath(targetEntity(), name.substring(TARGET_PREFIX.length()), name));
        }
        return Optional.empty();
    }

    public static Entity clientEntity() {
        Entity player = Minecraft.getInstance().player;
        if (player == null) {
            throw new ExpressionException("client.nbt is only available in-game");
        }
        return player;
    }

    public static Entity targetEntity() {
        if (Minecraft.getInstance().hitResult instanceof EntityHitResult hit) {
            return hit.getEntity();
        }
        throw new ExpressionException("target.nbt: no entity under the crosshair");
    }

    public static CompoundTag snapshot(Entity entity) {
        return NbtSnapshot.of(entity);
    }

    private static Value resolvePath(Entity entity, String path, String fullName) {
        Tag tag = walk(snapshot(entity), path, fullName);
        return toValue(tag, fullName);
    }

    /**
     * Read one NBT value out of a given entity — the shared path used by both the
     * target.nbt / client.nbt variables and the entity_nbt(...) function.
     * {@code label} names the source in error messages (e.g. "entity_nbt").
     */
    public static Value read(Entity entity, String path, String label) {
        return toValue(walk(snapshot(entity), path, label), label);
    }

    /** Walks dot-separated keys; numeric segments index into lists. */
    public static Tag walk(CompoundTag root, String path, String fullName) {
        Tag current = root;
        if (path.isEmpty()) {
            return current;
        }
        for (String segment : path.split("\\.")) {
            if (current instanceof CompoundTag compound) {
                Tag next = compound.get(segment);
                if (next == null) {
                    next = compound.keySet().stream()
                            .filter(key -> key.equalsIgnoreCase(segment))
                            .findFirst()
                            .map(compound::get)
                            .orElse(null);
                }
                if (next == null) {
                    throw new ExpressionException(fullName + ": no key '" + segment + "' here — available: " + previewKeys(compound));
                }
                current = next;
            } else if (current instanceof CollectionTag list) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException ex) {
                    throw new ExpressionException(fullName + ": '" + segment + "' — lists are indexed by number (0.." + (list.size() - 1) + ")");
                }
                if (index < 0 || index >= list.size()) {
                    throw new ExpressionException(fullName + ": index " + index + " out of range (list has " + list.size() + " entries)");
                }
                current = list.get(index);
            } else {
                throw new ExpressionException(fullName + ": '" + segment + "' — this value has no children");
            }
        }
        return current;
    }

    private static Value toValue(Tag tag, String fullName) {
        if (tag instanceof NumericTag numeric) {
            return Value.ofNumber(numeric.doubleValue());
        }
        if (tag instanceof StringTag string) {
            return Value.of(string.value());
        }
        if (tag instanceof CompoundTag compound) {
            throw new ExpressionException(fullName + " is a compound — pick a field: " + previewKeys(compound));
        }
        if (tag instanceof CollectionTag list) {
            throw new ExpressionException(fullName + " is a list with " + list.size() + " entries — index it, e.g. " + fullName + ".0");
        }
        throw new ExpressionException(fullName + ": unsupported value type " + tag.getClass().getSimpleName());
    }

    private static String previewKeys(CompoundTag compound) {
        List<String> keys = new ArrayList<>(compound.keySet());
        keys.sort(String::compareTo);
        if (keys.size() > 12) {
            return String.join(", ", keys.subList(0, 12)) + ", … (/tupenter dump for all)";
        }
        return String.join(", ", keys);
    }
}
