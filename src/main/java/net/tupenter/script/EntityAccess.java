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
        public Value readNbt(String selector, String path) {
            throw new ExpressionException("entity_nbt(...) needs a live world");
        }

        @Override
        public String typeId(String selector) {
            throw new ExpressionException("entity_type(...) needs a live world");
        }

        @Override
        public String raycastUuid(double maxDist) {
            throw new ExpressionException("entity_raycast(...) needs a live world");
        }

        @Override
        public List<String> nearbyUuids(double radius, String type) {
            throw new ExpressionException("entities(...) needs a live world");
        }

        @Override
        public String nearestUuid(double radius, String type) {
            throw new ExpressionException("nearest_entity(...) needs a live world");
        }
    };

    /** The scalar NBT value at {@code path} for the entity {@code selector} ("self"/"target"/UUID) picks, or throws. */
    Value readNbt(String selector, String path);

    /** The entity type id (e.g. "minecraft:zombie") of the entity {@code selector} picks, or throws. */
    String typeId(String selector);

    /** UUID of the entity under a ray from the eyes along look up to {@code maxDist} blocks, or "miss". */
    String raycastUuid(double maxDist);

    /** UUIDs of entities within {@code radius} blocks; {@code type} filters by id (null = any). */
    List<String> nearbyUuids(double radius, String type);

    /** UUID of the nearest entity within {@code radius} blocks ({@code type} filter, null = any), or "miss". */
    String nearestUuid(double radius, String type);
}
