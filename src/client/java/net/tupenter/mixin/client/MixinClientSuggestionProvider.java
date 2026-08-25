package net.tupenter.mixin.client;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.tupenter.command.ChainSuggestionScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * Ask-the-server suggestions on a chained line — see {@link ChainSuggestionScope}
 * for why they need special handling when nothing else does.
 *
 * <p>Both halves of the round trip are here: the request goes out carrying only
 * the segment the cursor is in, and the reply is moved back by the same offset
 * before anything downstream sees it.
 */
@Mixin(ClientSuggestionProvider.class)
public abstract class MixinClientSuggestionProvider {

    @Shadow
    private int pendingSuggestionsId;

    /**
     * The id is already the outgoing request's by the time this runs — vanilla
     * increments it before building the packet — so the offset can be filed
     * against it here and found again when the answer arrives.
     */
    @Redirect(method = "customSuggestion", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/context/CommandContext;getInput()Ljava/lang/String;"))
    private String tupenter$sendOnlyTheSegment(CommandContext<?> context) {
        return ChainSuggestionScope.outgoingText(context.getInput(), this.pendingSuggestionsId);
    }

    /**
     * Redirecting the completion rather than the argument, because the id this
     * answer belongs to is only available as the field: vanilla has already
     * checked it against the packet's by the time control reaches here, and does
     * not clear it until afterwards.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "completeCustomSuggestions", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private boolean tupenter$realign(CompletableFuture future, Object suggestions) {
        return future.complete(
                ChainSuggestionScope.realign(this.pendingSuggestionsId, (Suggestions) suggestions));
    }
}
