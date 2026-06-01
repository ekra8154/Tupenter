package net.tupenter.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.tupenter.TupenterModClient;
import net.tupenter.command.CommandAliasManager;
import net.tupenter.command.CommandParsingProcessor;
import net.tupenter.config.TupenterConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {
    private static boolean forwardingEnhancedCommand;

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void onSend(Packet<?> packet, CallbackInfo ci) {
        if (!forwardingEnhancedCommand && packet instanceof ServerboundChatCommandPacket commandPacket) {
            String originalCommand = commandPacket.command();
            if (TupenterModClient.handleLocalCalcAlias(originalCommand)) {
                ci.cancel();
                return;
            }

            if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled) {
                return;
            }

            CommandParsingProcessor.Result result = CommandParsingProcessor.process(
                    originalCommand,
                    TupenterConfig.INSTANCE.commandChainingEnabled,
                    TupenterConfig.INSTANCE.numberMathMode,
                    CommandAliasManager.getAliasMap()
            );

            if (result.aliasExpansionLimitExceeded()) {
                TupenterModClient.sendEnhancedParsingError("Alias expansion limit reached (" + result.aliasExpansionLimit() + "). Possible recursive alias loop.");
                ci.cancel();
                return;
            }

            if (result.changed()) {
                TupenterModClient.updateLastMessage("/" + originalCommand);
                forwardingEnhancedCommand = true;
                try {
                    for (CommandParsingProcessor.OutgoingSegment rewrittenCommand : result.commands()) {
                        if (rewrittenCommand.isCommand()) {
                            ((Connection) (Object) this).send(new ServerboundChatCommandPacket(rewrittenCommand.content()));
                        } else if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.connection.sendChat(rewrittenCommand.content());
                        }
                    }
                } finally {
                    forwardingEnhancedCommand = false;
                }
                ci.cancel();
                return;
            }
        }

        String content = null;
        if (packet instanceof ServerboundChatPacket && !forwardingEnhancedCommand) {
            content = ((ServerboundChatPacket) packet).message();
        } else if (packet instanceof ServerboundChatCommandPacket && !forwardingEnhancedCommand) {
            content = "/" + ((ServerboundChatCommandPacket) packet).command();
        }

        if (content != null) {
            TupenterModClient.updateLastMessage(content);
        }
	}
}
