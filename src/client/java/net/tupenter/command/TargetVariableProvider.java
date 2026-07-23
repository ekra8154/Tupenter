package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.tupenter.script.MissingValueException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code client.target.<field>} — what your crosshair found.
 *
 * <p>ENTITY fields (type, uuid, name, health, pos, nbt.&lt;path&gt;) are the same
 * vocabulary as {@code client.<field>} and {@code entity(uuid, "<field>")}, and
 * route through {@link EntityFields} — so learning one subject teaches the rest
 * and the three spellings can't disagree.
 *
 * <p>BLOCK fields are target-only and named for it: {@code block} (the id) and
 * {@code blockpos} (the position, + .x/.y/.z). {@code hit} says which kind you
 * actually found — "block", "entity", or "miss" — and the other fields error
 * when the hit is the wrong kind, so gate with it.
 */
public final class TargetVariableProvider implements VariableProvider {
    private static final String PREFIX = "client.target.";

    /** Fields only a targeted BLOCK has. */
    private static final List<String> BLOCK_FIELDS = List.of("hit", "block", "blockpos");

    @Override
    public Set<String> names() {
        return Set.of(); // dynamic — the completion hook offers the fields
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("client.target")) {
            throw new MissingValueException("client.target needs a field — hit, type, uuid, name, health, pos, "
                    + "block, blockpos, or nbt.<path> (e.g. $client.target.type$)");
        }
        if (!lower.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String field = name.substring(PREFIX.length());
        return Optional.of(switch (field.toLowerCase(Locale.ROOT)) {
            case "hit" -> Value.of(hitKind());
            case "block" -> Value.of(blockIdAt(targetBlockPos()));
            case "blockpos" -> {
                BlockPos pos = targetBlockPos();
                yield Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            case "blockpos.x" -> Value.ofNumber(targetBlockPos().getX());
            case "blockpos.y" -> Value.ofNumber(targetBlockPos().getY());
            case "blockpos.z" -> Value.ofNumber(targetBlockPos().getZ());
            // everything else is an ENTITY field — shared with client.* and entity(...)
            default -> EntityFields.read(EntityNbtVariableProvider.targetEntity(), field);
        });
    }

    /** "block", "entity", or "miss" — what the crosshair ray actually found. */
    private static String hitKind() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return "miss";
        }
        return hit.getType() == HitResult.Type.ENTITY ? "entity" : "block";
    }

    private static BlockPos targetBlockPos() {
        // a MISS is still a BlockHitResult (holding the ray's endpoint) — returning
        // that would silently point at thin air, so only a real block hit counts
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit
                && hit.getType() != HitResult.Type.MISS) {
            return hit.getBlockPos();
        }
        throw new MissingValueException("client.target.blockpos: no block under the crosshair — "
                + "check $client.target.hit$ first");
    }

    private static String blockIdAt(BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(Minecraft.getInstance().level.getBlockState(pos).getBlock()).toString();
    }

    /** Tab-completion for client.target.* — block fields plus the shared entity fields. */
    public static List<String> completions(String prefix) {
        if (!prefix.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String field : BLOCK_FIELDS) {
            out.add(PREFIX + field);
        }
        for (String field : EntityFields.FIELDS) {
            out.add(PREFIX + field);
        }
        return out;
    }
}
