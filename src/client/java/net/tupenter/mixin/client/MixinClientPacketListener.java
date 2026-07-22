package net.tupenter.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
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
     *
     * <p>Also reroutes a && CHAIN before Fabric sees it: if the first segment
     * is a client-side custom command (/launch ...), Fabric's dispatch grabs
     * the whole line and mis-parses the trailing && as that command's argument
     * ("Expected boolean"). Sending it as a raw command packet lets
     * MixinConnection's script parser own the line — it splits on && first, so
     * the alias expands with its optional params intact. Server-first chains
     * already take this path (Fabric declines them); this makes client-first
     * chains behave the same.
     */
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void tupenter$recordClientOnlyCommand(String command, CallbackInfo ci) {
        if (TupenterModClient.isCommandChain(command)) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.getConnection().send(new ServerboundChatCommandPacket(command));
                ci.cancel(); // Fabric never sees it; MixinConnection handles the chain
                return;
            }
        }
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
