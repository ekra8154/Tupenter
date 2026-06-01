package net.tupenter.mixin.client;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.tupenter.TupenterModClient;
import net.tupenter.command.CommandMathParser;
import net.tupenter.config.TupenterConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {
    private static boolean forwardingModifiedCommand;

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void onSend(Packet<?> packet, CallbackInfo ci) {
        if (!forwardingModifiedCommand
                && TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                && TupenterConfig.INSTANCE.numberMathEnabled
                && packet instanceof ServerboundChatCommandPacket commandPacket) {
            String originalCommand = commandPacket.command();
            String rewrittenCommand = CommandMathParser.applyNumberMath(originalCommand);

            if (!rewrittenCommand.equals(originalCommand)) {
                TupenterModClient.updateLastMessage("/" + originalCommand);
                forwardingModifiedCommand = true;
                try {
                    ((Connection) (Object) this).send(new ServerboundChatCommandPacket(rewrittenCommand));
                } finally {
                    forwardingModifiedCommand = false;
                }
                ci.cancel();
                return;
            }
        }

        String content = null;
        if (packet instanceof ServerboundChatPacket) {
            content = ((ServerboundChatPacket) packet).message();
        } else if (packet instanceof ServerboundChatCommandPacket && !forwardingModifiedCommand) {
            content = "/" + ((ServerboundChatCommandPacket) packet).command();
        }

        if (content != null) {
            TupenterModClient.updateLastMessage(content);
        }
	}
}
