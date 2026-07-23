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

        @Override
        public String slotItem(String slot) {
            throw new ExpressionException("slot_item(...) needs a live world");
        }

        @Override
        public int slotCount(String slot) {
            throw new ExpressionException("slot_count(...) needs a live world");
        }

        @Override
        public int slotDurability(String slot) {
            throw new ExpressionException("slot_durability(...) needs a live world");
        }
    };

    /** The scalar NBT value at {@code path} for the entity {@code selector} ("self"/"target"/UUID) picks, or throws. */
    Value readNbt(String selector, String path);

    /** The entity type id (e.g. "minecraft:zombie") of the entity {@code selector} picks, or throws. */
    String typeId(String selector);

    /**
     * Item id in one of YOUR slots, or "empty". {@code slot} uses the same names
     * as /item replace — "hotbar.0"-"hotbar.8", "inventory.0"-"inventory.26"
     * (0-8 is the top row), "armor.head/chest/legs/feet", "weapon.mainhand",
     * "weapon.offhand". Reads the live slot directly, unlike the NBT view, whose
     * Inventory list is COMPACTED (empty slots omitted) so indices don't line up.
     */
    String slotItem(String slot);

    /** Stack size in that slot; 0 when empty. */
    int slotCount(String slot);

    /** Durability REMAINING in that slot; 0 when empty or the item has no durability. */
    int slotDurability(String slot);

    /** UUID of the entity under a ray from the eyes along look up to {@code maxDist} blocks, or "miss". */
    String raycastUuid(double maxDist);

    /** UUIDs of entities within {@code radius} blocks; {@code type} filters by id (null = any). */
    List<String> nearbyUuids(double radius, String type);

    /** UUID of the nearest entity within {@code radius} blocks ({@code type} filter, null = any), or "miss". */
    String nearestUuid(double radius, String type);
}
