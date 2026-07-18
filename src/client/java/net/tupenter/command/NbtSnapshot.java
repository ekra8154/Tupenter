package net.tupenter.command;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueOutput;

/** Serializes an entity's client-side state to an NBT tree. */
final class NbtSnapshot {
    private NbtSnapshot() {
    }

    static CompoundTag of(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }
}
