package net.tupenter.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.tupenter.command.ChatSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Click-drag chat selection (ChatSelection): press starts a potential
 * selection, drag past a small threshold activates it, Ctrl+C copies.
 * Plain clicks fall through untouched so chat click-events keep working.
 * mouseDragged/mouseReleased are added overrides — vanilla ChatScreen
 * doesn't define them, so super (EditBox drag selection etc.) still runs.
 */
@Mixin(ChatScreen.class)
public abstract class MixinChatScreen extends Screen {

    protected MixinChatScreen(Component title) {
        super(title);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void tupenter$selectionStart(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 0) {
            ChatSelection.onMouseDown(this.minecraft, event.x(), event.y());
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0) {
            ChatSelection.onMouseDrag(this.minecraft, event.x(), event.y());
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            ChatSelection.onMouseUp();
        }
        return super.mouseReleased(event);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void tupenter$copySelection(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.isCopy() && ChatSelection.copyToClipboard(this.minecraft)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void tupenter$selectionOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatSelection.render(graphics, this.minecraft);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void tupenter$clearSelection(CallbackInfo ci) {
        ChatSelection.clear();
    }
}
