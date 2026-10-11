package net.tupenter.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.tupenter.config.TupenterConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persist # directive lines across restarts, the way vanilla persists commands.
 *
 * <p>addRecentChat puts every sent line in the in-session up-arrow history, but
 * only hands lines starting with "/" to CommandHistory, which is what
 * command_history.txt is written from and what the history is reloaded from at
 * startup. A "#stage /tp ..." line therefore worked with the up arrow until the
 * game restarted and then was simply gone. Giving # lines to the same
 * CommandHistory brings them back exactly like commands, with vanilla's own
 * size cap and de-duplication.
 *
 * <p>In this version the CommandHistory lives on Minecraft (commandHistory()),
 * not on ChatComponent, so it is reached through Minecraft.getInstance().
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponentHistory {

    @Inject(method = "addRecentChat", at = @At("TAIL"))
    private void tupenter$saveDirectiveLine(String message, CallbackInfo ci) {
        if (TupenterConfig.INSTANCE.saveDirectiveHistory && message.startsWith("#")) {
            Minecraft.getInstance().commandHistory().addCommand(message);
        }
    }
}
