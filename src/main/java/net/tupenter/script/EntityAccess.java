package net.tupenter.script;

import java.util.List;

/**
 * The script language's window onto entities in the synced client world:
 * reading an entity's NBT, and finding entities' UUIDs (so they can be fed back
 * into {@link #readNbt}). MC-free so the evaluator stays unit-testable; the
 * client backs it with the crosshair, ProjectileUtil, and ClientLevel lookups.
 *
 * <p>Everything is client-synced only (roughly render distance). UUID finders
 * return the "miss" sentinel / an empty list rather than throwing, so they gate
 * with a plain {@code == "miss"} or {@code len(...) == 0}; readNbt throws on a
 * missing entity or bad path, mirroring the target.nbt / client.nbt variables.
 */
public interface EntityAccess {
    EntityAccess NONE = new EntityAccess() {
        @Override
        public Value entityField(String selector, String field) {
            throw new ExpressionException("entity(...) needs a live world");
        }

        @Override
        public String raycastUuid(double maxDist) {
            throw new ExpressionException("raycast_entity(...) needs a live world");
        }

        @Override
        public List<String> nearbyUuids(double radius, String type) {
            throw new ExpressionException("entities(...) needs a live world");
        }

        @Override
        public String nearestUuid(double radius, String type) {
            throw new ExpressionException("nearest_entity(...) needs a live world");
        }

        @Override
        public Value slotField(String slot, String field) {
            throw new ExpressionException("slot(...) needs a live world");
        }
    };

    /**
     * One field of the entity {@code selector} picks ("self", "target", or a UUID) —
     * the computed-subject counterpart to the {@code client.<field>} and
     * {@code client.target.<field>} variables, sharing their field vocabulary:
     * type, uuid, name, health, pos, blockpos, and {@code nbt.<path>} for the raw
     * tree. Swapping subject then changes only the subject, not the shape.
     */
    Value entityField(String selector, String field);

    /**
     * One field of one of YOUR slots — the computed-address counterpart to the
     * {@code client.slot.<slot>.<field>} variable, exactly as
     * {@link #readNbt} is to {@code client.nbt.<path>}. Both halves are
     * arguments so a slot can be built at run time: {@code slot("inventory." + i, "id")}.
     *
     * <p>{@code slot} uses /item replace names — "hotbar.0"-"hotbar.8",
     * "inventory.0"-"inventory.26" (0-8 is the top row), "armor.head/chest/legs/feet",
     * "weapon.mainhand", "weapon.offhand". Reads the live slot directly, unlike the
     * NBT view, whose Inventory list is COMPACTED (empty slots omitted) so indices
     * don't line up. An empty slot yields "empty"/0 rather than an error.
     */
    Value slotField(String slot, String field);

    /** UUID of the entity under a ray from the eyes along look up to {@code maxDist} blocks, or "miss". */
    String raycastUuid(double maxDist);

    /** UUIDs of entities within {@code radius} blocks; {@code type} filters by id (null = any). */
    List<String> nearbyUuids(double radius, String type);

    /** UUID of the nearest entity within {@code radius} blocks ({@code type} filter, null = any), or "miss". */
    String nearestUuid(double radius, String type);
}
