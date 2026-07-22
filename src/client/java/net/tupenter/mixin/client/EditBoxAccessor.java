package net.tupenter.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the selection anchor so auto-bracket can tell a caret from a selection. */
@Mixin(EditBox.class)
public interface EditBoxAccessor {

    @Accessor("highlightPos")
    int tupenter$highlightPos();
}
