package net.tupenter.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.tupenter.command.ChatSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    @org.spongepowered.asm.mixin.Shadow
    protected EditBox input;

    @org.spongepowered.asm.mixin.Shadow
    public abstract void moveInHistory(int offset);

    @org.spongepowered.asm.mixin.Shadow
    public abstract void handleChatInput(String message, boolean addToRecentChat);

    protected MixinChatScreen(Component title) {
        super(title);
    }

    /**
     * Ctrl+scroll steps through sent-message history in the chat bar instead of
     * scrolling the chat log — reusing vanilla's own moveInHistory, so it saves
     * your in-progress draft and restores it when you scroll back down to the
     * bottom (exactly like the up/down arrows). Plain scroll still scrolls chat.
     */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void tupenter$ctrlScrollHistory(double mouseX, double mouseY, double scrollX, double scrollY,
            CallbackInfoReturnable<Boolean> cir) {
        if (net.tupenter.config.TupenterConfig.INSTANCE.ctrlScrollHistory && scrollY != 0 && tupenter$ctrlHeld()) {
            moveInHistory(scrollY > 0 ? -1 : 1); // up = older, down = newer (restores the draft at the end)
            cir.setReturnValue(true);
        }
    }

    private static boolean tupenter$ctrlHeld() {
        com.mojang.blaze3d.platform.Window window = net.minecraft.client.Minecraft.getInstance().getWindow();
        return window != null && (com.mojang.blaze3d.platform.InputConstants.isKeyDown(window.getWindow(),
                        com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window.getWindow(),
                        com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL));
    }

    /**
     * Raise the typing limit past vanilla's 256 (Chat Input Length config).
     * The wire limits still hold — MixinConnection blocks over-limit FINAL
     * packets with a local error — but a long line that EVALUATES short, or
     * a client-side command of any length, now fits in the box.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void tupenter$raiseInputLimit(CallbackInfo ci) {
        int limit = net.tupenter.config.TupenterConfig.INSTANCE.chatInputLength;
        if (limit > 256) {
            this.input.setMaxLength(limit);
        }
    }

    /**
     * Raising the input box's maxLength let you TYPE past 256, but submitting
     * still ran the line through StringUtil.trimChatMessage — a hard 256 cap —
     * BEFORE dispatch, so a long client command (/customcommand add ...) was
     * silently truncated on Enter even though the whole thing fit in the box.
     * Cap at the same configured limit instead; the real wire limits still
     * hold via MixinConnection, which blocks over-256 FINAL packets with a
     * clear local error. (limit &lt;= 256 keeps exact vanilla behavior.)
     */
    @Redirect(method = "normalizeChatMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/StringUtil;trimChatMessage(Ljava/lang/String;)Ljava/lang/String;"))
    private String tupenter$trimToConfiguredLimit(String message) {
        int limit = net.tupenter.config.TupenterConfig.INSTANCE.chatInputLength;
        if (limit <= 256) {
            return net.minecraft.util.StringUtil.trimChatMessage(message); // untouched vanilla
        }
        return message.length() <= limit ? message : message.substring(0, limit);
    }

    /**
     * handleChatInput is THE path a typed line takes to the server (vanilla
     * Enter and Tupenter's own Ctrl+Space both call it). Marking the send as
     * user-driven here is what lets the history writers tell your typing apart
     * from another mod firing a command straight into sendCommand — see
     * {@link net.tupenter.TupenterModClient#isUserDrivenSend()}. The send is
     * synchronous inside this method, so HEAD/RETURN bracket it exactly.
     */
    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void tupenter$markUserSendStart(String message, boolean addToRecentChat, CallbackInfo ci) {
        net.tupenter.TupenterModClient.beginUserDrivenSend();
    }

    @Inject(method = "handleChatInput", at = @At("RETURN"))
    private void tupenter$markUserSendEnd(String message, boolean addToRecentChat, CallbackInfo ci) {
        net.tupenter.TupenterModClient.endUserDrivenSend();
    }

    // Pre-1.21.9 input: mouse and key handlers take primitives, and the
    // convenience predicates that hung off the event objects are statics on
    // Screen instead.
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void tupenter$selectionStart(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0) {
            ChatSelection.onMouseDown(this.minecraft, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            ChatSelection.onMouseDrag(this.minecraft, mouseX, mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            ChatSelection.onMouseUp();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void tupenter$copySelection(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (net.minecraft.client.gui.screens.Screen.isCopy(key) && ChatSelection.copyToClipboard(this.minecraft)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Ctrl+Space submits the chat line, exactly like Enter — a manual send you
     * can trigger without lifting your hand off the mouse. Mirrors vanilla's
     * Enter path (handleChatInput then close the screen); consuming the event
     * keeps the space out of the box. Off falls straight through to a space.
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void tupenter$ctrlSpaceSend(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (net.tupenter.config.TupenterConfig.INSTANCE.ctrlSpaceSend
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                && net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            this.handleChatInput(this.input.getValue(), true);
            this.minecraft.setScreen(null);
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
