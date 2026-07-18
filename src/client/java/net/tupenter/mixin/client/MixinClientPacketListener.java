package net.tupenter.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.tupenter.TupenterModClient;
import net.tupenter.config.TupenterConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
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
