package net.tupenter.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.tupenter.TupenterModClient;
import net.tupenter.config.TupenterConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// priority 900: the sendCommand hook must observe the line before Fabric's
// client-command handler cancels it for client-only commands
@Mixin(value = ClientPacketListener.class, priority = 900)
public class MixinClientPacketListener {

    /**
     * Client-only commands (/tupenter, /customcommand, /echo, other mods')
     * never become packets — Fabric executes and swallows them — so the
     * packet mixin can't record them. Catch them here so anything sent by
     * pressing Enter lands in resend history (toggle and filter still apply).
     */
    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void tupenter$recordClientOnlyCommand(String command, CallbackInfo ci) {
        TupenterModClient.recordIfClientOnlyCommand(command);
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void onHandleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        // #silent scripts suppress feedback for their whole run (plus grace)
        boolean shouldSuppress = TupenterModClient.isScriptSilenceActive();

        if (!shouldSuppress && TupenterModClient.isFiring) {
            shouldSuppress = switch (TupenterConfig.INSTANCE.suppressFeedback) {
                case ON -> true;
                case DYNAMIC -> TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE;
                case OFF -> false;
            };
        }

        if (shouldSuppress) {
            ci.cancel();
        }
    }
}
