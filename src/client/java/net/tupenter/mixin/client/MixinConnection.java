package net.tupenter.mixin.client;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.tupenter.TupenterModClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void onSend(Packet<?> packet, CallbackInfo ci) {
        String content = null;
        if (packet instanceof ServerboundChatPacket) {
            content = ((ServerboundChatPacket) packet).message();
        } else if (packet instanceof ServerboundChatCommandPacket) {
            content = "/" + ((ServerboundChatCommandPacket) packet).command();
        }

        if (content != null) {
            TupenterModClient.updateLastMessage(content);
        }
	}
}
