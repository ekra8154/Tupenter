package net.tupenter.script;

/**
 * Backs entity_nbt(selector, path) — reads a value out of an entity's synced
 * NBT the same way the target.nbt / client.nbt variables do, but for an entity
 * chosen at runtime. MC-free so the evaluator stays unit-testable; the client
 * backs it with ClientLevel.getEntity(uuid) plus the "self"/"target" shortcuts.
 *
 * <p>{@code selector} is "self" (your player), "target" (the crosshair entity),
 * or a UUID string; {@code path} is a dot-walk into the NBT tree, numeric
 * segments indexing lists (e.g. "Pos.1", "ArmorItems.3.id"). A missing entity,
 * bad path, or non-scalar leaf throws {@link ExpressionException} with a message
 * that says what went wrong — mirroring the variable-form NBT accessors.
 */
public interface NbtReader {
    NbtReader NONE = (selector, path) -> {
        throw new ExpressionException("entity_nbt(...) needs a live world");
    };

    /** The scalar value at {@code path} for the entity {@code selector} picks, or throws. */
    Value read(String selector, String path);
}
