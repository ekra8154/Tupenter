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
 * client.nbt.* (your own entity data) or client.target.nbt.* (the entity under your
 * crosshair) by walking the entity's NBT tree at evaluation time. Nothing is
 * enumerated — coverage is whatever the client has synced. Browse paths with
 * /tupenter dump.
 */
public final class EntityNbtVariableProvider implements VariableProvider {
    private static final String CLIENT_PREFIX = "client.nbt.";
    private static final String TARGET_PREFIX = "client.target.nbt.";

    @Override
    public Set<String> names() {
        // dynamic — advertise just the roots so suggestions can hint at them
        return Set.of("client.nbt", "client.target.nbt");
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
        // MissingValueException, not ExpressionException: "nothing under the
        // crosshair" is transient world state, so an #if/#while condition reads
        // FALSE instead of aborting — that's what lets a scanning tick script
        // poll client.target.health without spamming errors.
        throw new net.tupenter.script.MissingValueException(
                "client.target: no entity under the crosshair — check $client.target.hit$ first");
    }

    public static CompoundTag snapshot(Entity entity) {
        return NbtSnapshot.of(entity);
    }

    /**
     * Tab-completion for client.nbt.* / client.target.nbt.* — the live tree, one level at
     * a time. Given what's typed so far, walks the entity's snapshot to the last
     * complete segment and offers that node's children (compound keys, or list
     * indices). Segments are echoed back in their CANONICAL case, so completing
     * "client.nbt.inv" yields "client.nbt.Inventory". Anything unresolvable (no
     * world, no crosshair entity, a bogus path) simply suggests nothing.
     */
    public static List<String> pathCompletions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        try {
            String root;
            Entity entity;
            if (lower.startsWith(CLIENT_PREFIX)) {
                root = CLIENT_PREFIX;
                entity = clientEntity();
            } else if (lower.startsWith(TARGET_PREFIX)) {
                root = TARGET_PREFIX;
                entity = targetEntity();
            } else {
                return List.of();
            }

            // everything up to the last '.' is settled; the tail is being typed
            String rest = lower.substring(root.length());
            int lastDot = rest.lastIndexOf('.');
            String walked = lastDot < 0 ? "" : rest.substring(0, lastDot);

            Tag current = snapshot(entity);
            StringBuilder canonical = new StringBuilder(root);
            if (!walked.isEmpty()) {
                for (String segment : walked.split("\\.")) {
                    if (current instanceof CompoundTag compound) {
                        String key = compound.keySet().stream()
                                .filter(k -> k.equalsIgnoreCase(segment))
                                .findFirst()
                                .orElse(null);
                        if (key == null) {
                            return List.of();
                        }
                        canonical.append(key).append('.');
                        current = compound.get(key);
                    } else if (current instanceof CollectionTag list) {
                        int index;
                        try {
                            index = Integer.parseInt(segment);
                        } catch (NumberFormatException ex) {
                            return List.of();
                        }
                        if (index < 0 || index >= list.size()) {
                            return List.of();
                        }
                        canonical.append(index).append('.');
                        current = list.get(index);
                    } else {
                        return List.of(); // a scalar has no children
                    }
                }
            }

            List<String> out = new ArrayList<>();
            if (current instanceof CompoundTag compound) {
                for (String key : new java.util.TreeSet<>(compound.keySet())) {
                    out.add(canonical + key);
                }
            } else if (current instanceof CollectionTag list) {
                for (int i = 0; i < list.size(); i++) {
                    out.add(canonical.toString() + i);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            return List.of(); // suggestions must never throw into the chat screen
        }
    }

    private static Value resolvePath(Entity entity, String path, String fullName) {
        Tag tag = walk(snapshot(entity), path, fullName);
        return toValue(tag, fullName);
    }

    /**
     * Read one NBT value out of a given entity — the shared path used by both the
     * client.nbt / client.target.nbt variables and the entity(...) function.
     * {@code label} names the source in error messages (e.g. "nbt.").
     */
    public static Value read(Entity entity, String path, String label) {
        return toValue(walk(snapshot(entity), path, label), label);
    }

    /**
     * The child ADDRESSES of an NBT node: a compound's keys (sorted, canonical
     * case) or a list's indices. Backs the {@code keys(selector, path)} function,
     * which is how the list vocabulary — len, contains, nth, #foreach — reaches
     * trees whose shape isn't known ahead of time (components, enchantments,
     * Brain.memories).
     *
     * <p>An absent path or a scalar yields an EMPTY list rather than throwing:
     * an unenchanted item genuinely has no minecraft:enchantments key, and the
     * useful answer there is "nothing", not an aborted script. Same contract as
     * the UUID finders.
     */
    public static List<String> childAddresses(Entity entity, String field) {
        String key = field.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        String path;
        if (lower.equals("nbt")) {
            path = ""; // the whole entity compound
        } else if (lower.startsWith("nbt.")) {
            path = key.substring("nbt.".length());
        } else {
            throw new ExpressionException("keys(...) reads the NBT tree — write the path as \"nbt.<path>\""
                    + " (or \"nbt\" for the whole entity), e.g. keys(\"self\", \"nbt.equipment\")");
        }

        Tag tag;
        try {
            tag = walk(snapshot(entity), path, "keys(...)");
        } catch (ExpressionException absent) {
            return List.of(); // no such path — "nothing here", not a fault
        }

        List<String> out = new ArrayList<>();
        if (tag instanceof CompoundTag compound) {
            out.addAll(new java.util.TreeSet<>(compound.keySet()));
        } else if (tag instanceof CollectionTag list) {
            for (int i = 0; i < list.size(); i++) {
                out.add(String.valueOf(i));
            }
        }
        // a scalar has no children — an empty list, for the same reason
        return List.copyOf(out);
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
