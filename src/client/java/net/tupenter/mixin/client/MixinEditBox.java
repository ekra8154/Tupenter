package net.tupenter.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
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

    // Pre-1.21.9 input: raw primitives instead of CharacterEvent/KeyEvent.
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void tupenter$autoBracketChar(char codepoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (!tupenter$active()) {
            return;
        }
        if (CHARS.indexOf(codepoint) < 0) {
            return;
        }
        EditBox self = (EditBox) (Object) this;
        int cursor = self.getCursorPosition();
        int highlight = ((EditBoxAccessor) self).tupenter$highlightPos();
        AutoBracket.Edit edit = AutoBracket.onChar(self.getValue(),
                Math.min(cursor, highlight), Math.max(cursor, highlight), codepoint);
        if (edit == null) {
            return;
        }
        tupenter$apply(self, edit);
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void tupenter$autoBracketBackspace(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (key != GLFW.GLFW_KEY_BACKSPACE || !tupenter$active()) {
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
                || !(Minecraft.getInstance().screen instanceof ChatScreen)) {
            return false;
        }
        String line = ((EditBox) (Object) this).getValue().stripLeading();
        return line.startsWith("/") || line.startsWith("#");
    }
}
