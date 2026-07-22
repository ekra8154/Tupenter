package net.tupenter.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Style;
import net.tupenter.command.ChatInputStyler;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AutoBracket;
import org.lwjgl.glfw.GLFW;

/**
 * MultiLineEditBox with Tupenter script smarts (via access widener):
 * - chat-style syntax colors, drawn over the base render so vanilla's
 *   cursor, selection, and scrolling stay untouched (the bitmap font has
 *   no antialiasing, so the overdraw is pixel-perfect)
 * - Enter auto-indents to the current line's leading whitespace
 * - Tab inserts two spaces instead of moving focus
 * Newlines are Mod Menu formatting only — styling maps them 1:1 to spaces,
 * so unmatched parens and unclosed markers glow red exactly like the chat bar.
 */
public class ScriptEditBox extends MultiLineEditBox {

    private static final int TEXT_COLOR = -2039584;   // vanilla defaults
    private static final int CURSOR_COLOR = -3092272;

    private final Font editorFont;
    private final boolean definition;
    private final boolean perLine; // true = each newline is a separate command (resend presets)
    private String styledFor;
    private Style[] styleCache;

    public ScriptEditBox(Font font, int width, int height, boolean definition) {
        this(font, width, height, definition, false);
    }

    public ScriptEditBox(Font font, int width, int height, boolean definition, boolean perLine) {
        super(font, 0, 0, width, height, CommonComponents.EMPTY, CommonComponents.EMPTY,
                TEXT_COLOR, true, CURSOR_COLOR, true, true);
        this.editorFont = font;
        this.definition = definition;
        this.perLine = perLine;
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderContents(graphics, mouseX, mouseY, partialTick);

        String value = getValue();
        if (value.isEmpty()) {
            return;
        }
        if (!value.equals(styledFor)) {
            styleCache = perLine
                    ? ChatInputStyler.editorStylesPerLine(value)
                    : ChatInputStyler.editorStyles(value, definition);
            styledFor = value;
        }

        int y = getInnerTop();
        for (MultilineTextField.StringView line : this.textField.iterateLines()) {
            if (withinContentAreaTopBottom(y, y + editorFont.lineHeight)) {
                graphics.drawString(editorFont,
                        ChatInputStyler.sequence(value, styleCache, line.beginIndex(), line.endIndex()),
                        getInnerLeft(), y, TEXT_COLOR, true);
            }
            y += editorFont.lineHeight;
        }
    }

    // the editor is always a script/expression context, so auto-bracket needs
    // no per-line gate here — just the opt-in setting. Same chars as the chat bar.
    private static final String AUTO_BRACKET_CHARS = "([{)]}\"$";

    @Override
    public boolean charTyped(CharacterEvent event) {
        int codepoint = event.codepoint();
        if (autoBracketEnabled() && codepoint <= Character.MAX_VALUE
                && AUTO_BRACKET_CHARS.indexOf((char) codepoint) >= 0) {
            int cursor = this.textField.cursor();
            int selStart = cursor;
            int selEnd = cursor;
            if (this.textField.hasSelection()) {
                MultilineTextField.StringView sel = this.textField.getSelected();
                selStart = sel.beginIndex();
                selEnd = sel.endIndex();
            }
            AutoBracket.Edit edit = AutoBracket.onChar(getValue(), selStart, selEnd, (char) codepoint);
            if (edit != null) {
                applyEdit(edit);
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            this.textField.insertText("  ");
            return true;
        }
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            this.textField.insertText("\n" + currentLineIndent());
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE && autoBracketEnabled() && !this.textField.hasSelection()) {
            AutoBracket.Edit edit = AutoBracket.onBackspace(getValue(), this.textField.cursor());
            if (edit != null) {
                applyEdit(edit);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private static boolean autoBracketEnabled() {
        return TupenterConfig.INSTANCE.autoCloseBrackets
                && TupenterConfig.INSTANCE.enhancedCommandParsingEnabled;
    }

    private void applyEdit(AutoBracket.Edit edit) {
        this.textField.setValue(edit.text());
        this.textField.seekCursor(Whence.ABSOLUTE, edit.cursor());
    }

    private String currentLineIndent() {
        String value = getValue();
        int cursor = Math.min(this.textField.cursor(), value.length());
        int lineStart = value.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int i = lineStart;
        while (i < cursor && value.charAt(i) == ' ') {
            i++;
        }
        return value.substring(lineStart, i);
    }
}
