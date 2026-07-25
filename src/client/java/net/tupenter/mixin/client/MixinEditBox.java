package net.tupenter.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AutoBracket;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Auto-close brackets in the CHAT BAR (opt-in, {@link TupenterConfig#autoCloseBrackets}).
 * The EditBox is shared across many screens, so this only acts when it's the
 * chat screen's input AND the line is a command/#directive — where markers and
 * brackets actually matter. The Mod Menu script editor is handled by
 * ScriptEditBox itself. All the logic lives in {@link AutoBracket}; this is
 * just the widget adapter.
 */
@Mixin(EditBox.class)
public abstract class MixinEditBox {

    private static final String CHARS = "([{)]}\"$";

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void tupenter$autoBracketChar(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!tupenter$active()) {
            return;
        }
        int codepoint = event.codepoint();
        if (codepoint > Character.MAX_VALUE || CHARS.indexOf((char) codepoint) < 0) {
            return;
        }
        EditBox self = (EditBox) (Object) this;
        int cursor = self.getCursorPosition();
        int highlight = ((EditBoxAccessor) self).tupenter$highlightPos();
        AutoBracket.Edit edit = AutoBracket.onChar(self.getValue(),
                Math.min(cursor, highlight), Math.max(cursor, highlight), (char) codepoint);
        if (edit == null) {
            return;
        }
        tupenter$apply(self, edit);
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void tupenter$autoBracketBackspace(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != GLFW.GLFW_KEY_BACKSPACE || !tupenter$active()) {
            return;
        }
        EditBox self = (EditBox) (Object) this;
        int cursor = self.getCursorPosition();
        if (cursor != ((EditBoxAccessor) self).tupenter$highlightPos()) {
            return; // a selection is highlighted — let the normal delete run
        }
        AutoBracket.Edit edit = AutoBracket.onBackspace(self.getValue(), cursor);
        if (edit == null) {
            return;
        }
        tupenter$apply(self, edit);
        cir.setReturnValue(true);
    }

    private static void tupenter$apply(EditBox box, AutoBracket.Edit edit) {
        box.setValue(edit.text());
        box.setCursorPosition(edit.cursor());
        box.setHighlightPos(edit.cursor()); // collapse any selection to the caret
    }

    /** On only for the chat input, opt-in, and only on a command/#directive line. */
    private boolean tupenter$active() {
        if (!TupenterConfig.INSTANCE.autoCloseBrackets
                || !TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                || !(Minecraft.getInstance().gui.screen() instanceof ChatScreen)) {
            return false;
        }
        String line = ((EditBox) (Object) this).getValue().stripLeading();
        return line.startsWith("/") || line.startsWith("#");
    }
}
