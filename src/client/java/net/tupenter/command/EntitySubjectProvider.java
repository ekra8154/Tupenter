package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
 * Entity SUBJECTS picked by live state, each behind a gate:
 *
 * <ul>
 *   <li>{@code client.target.<field>} — what your crosshair found ({@code client.target.hit})</li>
 *   <li>{@code client.vehicle.<field>} — what you're riding ({@code client.riding})</li>
 * </ul>
 *
 * <p>Both speak the shared field vocabulary via {@link EntityFields}, the same
 * one {@code client.<field>} and {@code entity(uuid, "<field>")} use — so
 * swapping subject changes only the subject. An absent subject raises
 * {@link MissingValueException}, which an #if/#while condition reads as false,
 * so a polling script skips quietly instead of erroring.
 *
 * <p>The target also has BLOCK fields, named for it: {@code block} (the id) and
 * {@code blockpos} (the position, + .x/.y/.z). Those error on an entity hit, and
 * the entity fields error on a block hit — {@code hit} says which you have.
 */
public final class EntitySubjectProvider implements VariableProvider {
    private static final String TARGET = "client.target.";
    private static final String VEHICLE = "client.vehicle.";

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
            throw new MissingValueException("client.target needs a field — hit, block, blockpos, type, uuid, "
                    + "name, health, pos, or nbt.<path> (e.g. $client.target.type$)");
        }
        if (lower.equals("client.vehicle")) {
            throw new MissingValueException("client.vehicle needs a field — type, uuid, name, health, pos, "
                    + "blockpos, or nbt.<path> (e.g. $client.vehicle.type$); check $client.riding$ first");
        }
        if (lower.startsWith(VEHICLE)) {
            return Optional.of(EntityFields.read(vehicle(), name.substring(VEHICLE.length())));
        }
        if (!lower.startsWith(TARGET)) {
            return Optional.empty();
        }
        String field = name.substring(TARGET.length());
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

    /** The entity you're riding, or absent — {@code client.riding} is the gate. */
    private static Entity vehicle() {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        Entity vehicle = player == null ? null : player.getVehicle();
        if (vehicle == null) {
            throw new MissingValueException("client.vehicle: not riding anything — check $client.riding$ first");
        }
        return vehicle;
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

    /** Tab-completion for client.target.* / client.vehicle.* */
    public static List<String> completions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (lower.startsWith(VEHICLE)) {
            for (String field : EntityFields.FIELDS) {
                out.add(VEHICLE + field);
            }
            return out;
        }
        if (!lower.startsWith(TARGET)) {
            return List.of();
        }
        for (String field : BLOCK_FIELDS) {
            out.add(TARGET + field);
        }
        for (String field : EntityFields.FIELDS) {
            out.add(TARGET + field);
        }
        return out;
    }
}
