package net.tupenter.command;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Serializes an entity's client-side state to an NBT tree.
 *
 * <p>1.21.6 introduced the ValueOutput abstraction, where saving goes through a
 * TagValueOutput carrying a ProblemReporter. At 1.21.5 saving still writes
 * straight into a CompoundTag you hand it, so there is nothing to build a result
 * from — the tag is the result.
 */
final class NbtSnapshot {
    private NbtSnapshot() {
    }

    static CompoundTag of(Entity entity) {
        return entity.saveWithoutId(new CompoundTag());
    }
}
