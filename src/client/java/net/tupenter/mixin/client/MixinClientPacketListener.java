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
        // If config enabled AND mod is currently auto-firing, suppress ALL system feedback
        if (TupenterConfig.INSTANCE.suppressFeedback && TupenterModClient.isFiring) {
            ci.cancel();
        }
    }
}
