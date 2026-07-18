package net.tupenter.mixin.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import net.tupenter.command.ChatInputStyler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Two Tupenter upgrades to the chat bar:
 *
 * 1. Chain-aware suggestions: on a "/a && /b" line, the suggestion parse is
 *    re-rooted to the segment the cursor is in — the line is truncated at
 *    that segment's end and the parse starts at its '/', so autocomplete,
 *    error text, and suggestion ranges all stay in real-input coordinates
 *    (no offset remapping anywhere downstream).
 *
 * 2. Tupenter-aware highlighting: formatChat is taken over for chained
 *    lines, marker-bearing commands, and #directive lines
 *    (ChatInputStyler decides and styles).
 */
@Mixin(CommandSuggestions.class)
public class MixinCommandSuggestions {

    @Shadow
    @Final
    private EditBox input;

    @Unique
    private boolean tupenter$reroot;
    @Unique
    private int tupenter$cmdStart;
    @Unique
    private int tupenter$segEnd;

    @Redirect(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;getValue()Ljava/lang/String;"))
    private String tupenter$chainAwareValue(EditBox box) {
        String full = box.getValue();
        tupenter$reroot = false;
        if (!ChatInputStyler.chainRerootEnabled() || !full.startsWith("/")) {
            return full;
        }
        List<ChatInputStyler.Segment> segments = ChatInputStyler.segments(full);
        if (segments.size() < 2) {
            return full;
        }
        ChatInputStyler.Segment segment = ChatInputStyler.segmentAt(segments, box.getCursorPosition());
        // a chat/directive segment has no command tree to complete against;
        // fall back to the nearest command segment to its left (segment 1 is
        // always a command on a '/'-line)
        int index = segments.indexOf(segment);
        while (index > 0 && segment.kind() != ChatInputStyler.Kind.COMMAND) {
            segment = segments.get(--index);
        }
        if (segment.kind() != ChatInputStyler.Kind.COMMAND || segment.textStart() >= segment.end()) {
            return full;
        }
        tupenter$reroot = true;
        tupenter$cmdStart = segment.textStart();
        tupenter$segEnd = segment.end();
        return full.substring(0, segment.end());
    }

    @Redirect(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;getCursorPosition()I"))
    private int tupenter$chainAwareCursor(EditBox box) {
        int cursor = box.getCursorPosition();
        if (!tupenter$reroot) {
            return cursor;
        }
        return Math.min(Math.max(cursor, tupenter$cmdStart + 1), tupenter$segEnd);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults tupenter$chainAwareParse(CommandDispatcher dispatcher, StringReader reader, Object source) {
        if (tupenter$reroot) {
            reader.setCursor(tupenter$cmdStart + 1); // just past the segment's '/'
        }
        return dispatcher.parse(reader, source);
    }

    @Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
    private void tupenter$formatChat(String partial, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
        String full = this.input.getValue();
        if (ChatInputStyler.shouldStyle(full)) {
            cir.setReturnValue(ChatInputStyler.format(full, partial, offset));
        }
    }
}
