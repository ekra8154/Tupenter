package net.tupenter.mixin.client;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.tupenter.TupenterModClient;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.ScriptParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void onSend(Packet<?> packet, CallbackInfo ci) {
        if (!TupenterModClient.isForwardingScriptSend()) {
            // Commands travel in two packet types: plain, and signed (for
            // commands with signable chat args like /say and /msg). Both are
            // script input; rewritten output is re-sent via sendCommand,
            // which re-signs as needed.
            String command = null;
            if (packet instanceof ServerboundChatCommandPacket plain) {
                command = plain.command();
            } else if (packet instanceof ServerboundChatCommandSignedPacket signed) {
                command = signed.command();
            }
            if (command != null && handleOutgoingCommand(command, ci)) {
                return;
            }

            // Chat lines are only inspected when they start with a known
            // directive word (e.g. "#silent /time set day").
            if (TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                    && packet instanceof ServerboundChatPacket chatPacket
                    && chatPacket.message().startsWith("#")) {
                if (TupenterModClient.handleStagePrefix(chatPacket.message(), false)
                        || TupenterModClient.handleUnstagePrefix(chatPacket.message())) {
                    ci.cancel();
                    return;
                }
                ScriptParser.ParseResult result = ScriptParser.parseChatLine(chatPacket.message(), TupenterModClient.parserOptions());
                if (applyResult(result, chatPacket.message(), ci)) {
                    return;
                }
            }
        }

        String content = null;
        if (!TupenterModClient.isForwardingScriptSend()) {
            if (packet instanceof ServerboundChatPacket chatPacket) {
                content = chatPacket.message();
            } else if (packet instanceof ServerboundChatCommandPacket commandPacket) {
                content = "/" + commandPacket.command();
            } else if (packet instanceof ServerboundChatCommandSignedPacket signedPacket) {
                content = "/" + signedPacket.command();
            }
        }

        if (content != null) {
            TupenterModClient.updateLastMessage(content);
        }
	}

    /** @return true when the packet was handled (cancelled) */
    private boolean handleOutgoingCommand(String originalCommand, CallbackInfo ci) {
        if (TupenterModClient.handleLocalCalcAlias(originalCommand)) {
            ci.cancel();
            return true;
        }

        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled) {
            return false;
        }

        if (TupenterModClient.handleStagePrefix(originalCommand, true)
                || TupenterModClient.handleUnstagePrefix(originalCommand)) {
            ci.cancel();
            return true;
        }

        ScriptParser.ParseResult result = ScriptParser.parse(originalCommand, TupenterModClient.parserOptions());
        return applyResult(result, "/" + originalCommand, ci);
    }

    /** @return true when the packet was handled (cancelled) */
    private boolean applyResult(ScriptParser.ParseResult result, String historyLine, CallbackInfo ci) {
        if (result.error() != null) {
            TupenterModClient.sendEnhancedParsingError(result.error());
            ci.cancel();
            return true;
        }

        if (result.changed()) {
            switch (result.script().history()) {
                case NORMAL -> TupenterModClient.updateLastMessage(historyLine);
                case FORCE -> TupenterModClient.forceAddToHistory(historyLine);
                case SKIP -> { }
            }
            result.notices().forEach(TupenterModClient::sendEnhancedParsingInfo);
            TupenterModClient.submitScript(result.script());
            ci.cancel();
            return true;
        }
        return false;
    }
}
