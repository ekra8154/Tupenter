package net.tupenter.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Style;
import net.tupenter.command.ChatInputStyler;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AutoBracket;
import net.tupenter.script.UndoHistory;
import org.lwjgl.glfw.GLFW;

/**
 * MultiLineEditBox with Tupenter script smarts (via access widener):
 * - chat-style syntax colors, drawn over the base render so vanilla's
 *   cursor, selection, and scrolling stay untouched (the bitmap font has
 *   no antialiasing, so the overdraw is pixel-perfect)
 * - Enter auto-indents to the current line's leading whitespace
 * - Tab inserts two spaces instead of moving focus
 * - the chat bar's $variable / function / #directive suggestions, in a popup
 *   at the caret (Tab takes one, Up/Down pick)
 * Newlines are Mod Menu formatting only — styling maps them 1:1 to spaces,
 * so unmatched parens and unclosed markers glow red exactly like the chat bar.
 */
public class ScriptEditBox extends MultiLineEditBox {

    private static final int TEXT_COLOR = -2039584;   // vanilla default

    private final Font editorFont;
    private final boolean definition;
    private final boolean perLine; // true = each newline is a separate command (resend presets)
    private String styledFor;
    private Style[] styleCache;
    private UndoHistory undoHistory;   // lazily seeded from the box's initial value
    private boolean restoring;         // true while applying an undo/redo (don't re-record it)
    private final EditorCompletionPopup completion = new EditorCompletionPopup(new EditorCompletionPopup.Target() {
        @Override
        public String value() {
            return getValue();
        }

        @Override
        public int cursor() {
            return textField.cursor();
        }

        @Override
        public void replaceToCursor(int start, String text) {
            String value = getValue();
            int cursor = Math.min(textField.cursor(), value.length());
            replaceAndPlaceCaret(value.substring(0, start) + text + value.substring(cursor), start + text.length());
            recordUndo();
        }

        @Override
        public boolean definition() {
            return definition;
        }

        @Override
        public boolean perLine() {
            return perLine;
        }
    });

    public ScriptEditBox(Font font, int width, int height, boolean definition) {
        this(font, width, height, definition, false);
    }

    public ScriptEditBox(Font font, int width, int height, boolean definition, boolean perLine) {
        // 1.21.6 added the styling arguments (text colour, cursor colour, and the
        // background/decoration flags) to this constructor. At 1.21.5 they aren't
        // configurable — but the values we pass on newer versions ARE the vanilla
        // defaults, so the box looks the same either way. TEXT_COLOR stays: the
        // syntax overdraw below still draws with it.
        super(font, 0, 0, width, height, CommonComponents.EMPTY, CommonComponents.EMPTY);
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

    /** The suggestion popup goes on top, anchored to the caret in screen space. */
    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (!isFocused()) {
            return;
        }
        String value = getValue();
        int line = this.textField.getLineAtCursor();
        int lineStart = this.textField.getLineView(line).beginIndex();
        int cursor = Math.max(lineStart, Math.min(this.textField.cursor(), value.length()));
        int caretX = getInnerLeft() + editorFont.width(value.substring(lineStart, cursor));
        // contents are drawn shifted up by the scroll; the popup is not
        int caretY = getInnerTop() + line * editorFont.lineHeight - (int) scrollAmount();
        caretY = Math.max(getY(), Math.min(getY() + getHeight() - editorFont.lineHeight, caretY));
        completion.render(graphics, editorFont, caretX, caretY);
    }

    // the editor is always a script/expression context, so auto-bracket needs
    // no per-line gate here — just the opt-in setting. Same chars as the chat bar.
    private static final String AUTO_BRACKET_CHARS = "([{)]}\"$";

    // Pre-1.21.9 input: primitives instead of CharacterEvent/KeyEvent/
    // MouseButtonEvent, and the modifier predicates come from Screen.
    @Override
    public boolean charTyped(char codepoint, int modifiers) {
        ensureHistory();
        if (autoBracketEnabled() && AUTO_BRACKET_CHARS.indexOf(codepoint) >= 0) {
            int cursor = this.textField.cursor();
            int selStart = cursor;
            int selEnd = cursor;
            if (this.textField.hasSelection()) {
                MultilineTextField.StringView sel = this.textField.getSelected();
                selStart = sel.beginIndex();
                selEnd = sel.endIndex();
            }
            AutoBracket.Edit edit = AutoBracket.onChar(getValue(), selStart, selEnd, codepoint);
            if (edit != null) {
                applyEdit(edit);
                recordUndo();
                completion.onEdited();
                return true;
            }
        }
        boolean handled = super.charTyped(codepoint, modifiers);
        if (handled) {
            recordUndo();
            completion.onEdited();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        ensureHistory();
        if (completion.keyPressed(key)) {
            return true; // Tab took a suggestion, or Up/Down moved through them
        }
        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();
        if (ctrl && !shift && key == GLFW.GLFW_KEY_Z) {
            applyRestore(undoHistory.undo());
            return true;
        }
        if (ctrl && (key == GLFW.GLFW_KEY_Y || (shift && key == GLFW.GLFW_KEY_Z))) {
            applyRestore(undoHistory.redo());
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            this.textField.insertText("  ");
            recordUndo();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            this.textField.insertText("\n" + currentLineIndent());
            recordUndo();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && autoBracketEnabled() && !this.textField.hasSelection()) {
            AutoBracket.Edit edit = AutoBracket.onBackspace(getValue(), this.textField.cursor());
            if (edit != null) {
                applyEdit(edit);
                recordUndo();
                completion.onEdited();
                return true;
            }
        }
        boolean handled = super.keyPressed(key, scancode, modifiers);
        if (handled) {
            recordUndo(); // typing, delete, paste, cut, select-all-then-type, …
            if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
                completion.onEdited(); // shortening the word re-filters, as in chat
            }
        }
        return handled;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        recordUndo(); // a click that moves the caret ends the current undo group
        completion.dismiss();
        return handled;
    }

    /**
     * The wheel belongs to the page unless you are editing this box. Vanilla
     * consumes every scroll while hovered, so each expanded editor the cursor
     * crossed ate a notch (even one with only its padding left to scroll),
     * and a page with a few of them scrolled in fits and felt slow. Cloth
     * also offers the wheel to every visible row, not just the hovered one,
     * which made it worse.
     *
     * <p>So an unfocused box never takes it, and a focused one gives it back at
     * its top and bottom, so scrolling past the end of what you're editing
     * carries on down the page.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isFocused()) {
            return false;
        }
        boolean atTop = scrollAmount() <= 0.0;
        boolean atBottom = scrollAmount() >= maxScrollAmount();
        if ((scrollY > 0 && atTop) || (scrollY < 0 && atBottom)) {
            return false; // nothing left to scroll here — pass it to the page
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean autoBracketEnabled() {
        return TupenterConfig.INSTANCE.autoCloseBrackets
                && TupenterConfig.INSTANCE.enhancedCommandParsingEnabled;
    }

    private void applyEdit(AutoBracket.Edit edit) {
        replaceAndPlaceCaret(edit.text(), edit.cursor());
    }

    /**
     * Swap in new text and put a bare caret (no selection) at {@code cursor}.
     *
     * <p>The text field sets its "selecting" flag from Shift on every key press,
     * and seekCursor only collapses the selection when that flag is off. The
     * keys that reach here mostly hold Shift: ( { " $ for auto-brackets, and
     * Ctrl+Shift+Z for redo. So the caret moved but the selection end stayed
     * where setValue left it, at the end of the text, and the next character
     * you typed replaced everything after the caret. Clearing the flag first
     * makes the move a plain caret placement. Vanilla sets it again on the
     * next key press, so nothing is lost.
     */
    private void replaceAndPlaceCaret(String text, int cursor) {
        this.textField.setValue(text);
        this.textField.setSelecting(false);
        this.textField.seekCursor(Whence.ABSOLUTE, cursor);
    }

    /** Seed the undo history from the box's value the first time it's touched. */
    private void ensureHistory() {
        if (undoHistory == null) {
            undoHistory = new UndoHistory(getValue(), this.textField.cursor());
        }
    }

    /** Feed the current state to the history — skipped while we're applying an undo. */
    private void recordUndo() {
        if (!restoring && undoHistory != null) {
            undoHistory.record(getValue(), this.textField.cursor(), System.currentTimeMillis());
        }
    }

    private void applyRestore(UndoHistory.State state) {
        if (state == null) {
            return; // nothing to undo/redo
        }
        restoring = true;
        try {
            replaceAndPlaceCaret(state.text(), state.cursor());
        } finally {
            restoring = false;
        }
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
