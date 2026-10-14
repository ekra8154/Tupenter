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
 * - multiple carets: Alt+click adds (or removes) one, Ctrl+Alt+Up/Down adds one
 *   on the line above/below, Ctrl+D selects the word and then each next
 *   occurrence; every edit and move then happens at all of them (MultiCaret)
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
    private UndoHistory undoHistory;   // lazily seeded from the box's initial value
    private boolean restoring;         // true while applying an undo/redo (don't re-record it)
    /** Carets besides the text field's own (the primary). Empty in the usual one-caret case. */
    private final java.util.List<MultiCaret.Caret> extraCarets = new java.util.ArrayList<>();
    /** True while an edit runs once per caret: no undo steps or popup updates per run. */
    private boolean batching;
    private final MultiCaret.Editor caretEditor = new MultiCaret.Editor() {
        @Override
        public String text() {
            return getValue();
        }

        @Override
        public MultiCaret.Caret caret() {
            return readCaret();
        }

        @Override
        public void setCaret(MultiCaret.Caret caret) {
            placeSelection(caret);
        }
    };
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
        if (isFocused() && !extraCarets.isEmpty()) {
            drawExtraCarets(graphics, value);
        }
    }

    /**
     * The extra carets and their selections, drawn the way vanilla draws its
     * own (a 1px bar, the same highlight), but steady rather than blinking so
     * you can always see where every one of them is.
     */
    private void drawExtraCarets(GuiGraphics graphics, String value) {
        java.util.List<MultilineTextField.StringView> lines = new java.util.ArrayList<>();
        for (MultilineTextField.StringView line : this.textField.iterateLines()) {
            lines.add(line);
        }
        int left = getInnerLeft();
        int right = getX() + getWidth() - innerPadding();
        for (MultiCaret.Caret caret : extraCarets) {
            int y = getInnerTop();
            boolean caretDrawn = false;
            for (int i = 0; i < lines.size(); i++, y += editorFont.lineHeight) {
                MultilineTextField.StringView line = lines.get(i);
                int begin = line.beginIndex();
                int end = line.endIndex();
                if (!withinContentAreaTopBottom(y, y + editorFont.lineHeight)) {
                    continue;
                }
                if (caret.hasSelection() && caret.start() <= end && caret.end() >= begin) {
                    int from = Math.max(caret.start(), begin);
                    int x1 = left + editorFont.width(value.substring(begin, Math.min(from, end)));
                    int x2 = caret.end() > end ? right : left + editorFont.width(value.substring(begin, caret.end()));
                    if (x2 > x1) {
                        graphics.textHighlight(x1, y, x2, y + editorFont.lineHeight);
                    }
                }
                // a position at a wrap point belongs to the next line, as vanilla's caret does
                boolean onThisLine = caret.pos() >= begin && caret.pos() <= end
                        && (caret.pos() < end || i == lines.size() - 1 || lines.get(i + 1).beginIndex() > end);
                if (!caretDrawn && onThisLine) {
                    int x = left + editorFont.width(value.substring(begin, caret.pos()));
                    graphics.fill(x, y - 1, x + 1, y + editorFont.lineHeight, CURSOR_COLOR);
                    caretDrawn = true;
                }
            }
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
        if (!extraCarets.isEmpty()) {
            boolean[] handled = {false};
            runAtEachCaret(rank -> handled[0] |= typeOne(codepoint, modifiers));
            return handled[0];
        }
        boolean handled = typeOne(codepoint, modifiers);
        if (handled) {
            recordUndo();
            completion.onEdited();
        }
        return handled;
    }

    /** One character at the (single) caret: auto-bracket pairs, else vanilla. */
    private boolean typeOne(char codepoint, int modifiers) {
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
                return true;
            }
        }
        return super.charTyped(codepoint, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        ensureHistory();
        if (caretCommand(key)) {
            return true;
        }
        if (!extraCarets.isEmpty()) {
            boolean undoOrRedo = Screen.hasControlDown() && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y);
            if (undoOrRedo || Screen.isSelectAll(key)) {
                clearExtraCarets(); // back to one caret, then as usual below
            } else if (Screen.isCopy(key)) {
                copyAll();
                return true;
            } else if (Screen.isCut(key)) {
                copyAll();
                runAtEachCaret(rank -> {
                    if (this.textField.hasSelection()) {
                        this.textField.insertText("");
                    }
                });
                return true;
            } else if (Screen.isPaste(key)) {
                pasteAll();
                return true;
            } else {
                boolean[] handled = {false};
                runAtEachCaret(rank -> handled[0] |= keyOne(key, scancode, modifiers));
                return handled[0];
            }
        }
        if (completion.keyPressed(key)) {
            return true; // Tab took a suggestion, or Up/Down moved through them
        }
        if (Screen.hasControlDown() && !Screen.hasShiftDown() && key == GLFW.GLFW_KEY_Z) {
            applyRestore(undoHistory.undo());
            return true;
        }
        if (Screen.hasControlDown() && (key == GLFW.GLFW_KEY_Y
                || (Screen.hasShiftDown() && key == GLFW.GLFW_KEY_Z))) {
            applyRestore(undoHistory.redo());
            return true;
        }
        boolean handled = keyOne(key, scancode, modifiers);
        if (handled) {
            recordUndo(); // typing, delete, paste, cut, select-all-then-type, …
            if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
                completion.onEdited(); // shortening the word re-filters, as in chat
            }
        }
        return handled;
    }

    /** One key at the (single) caret: Tab, Enter with indent, pair-aware Backspace, else vanilla. */
    private boolean keyOne(int key, int scancode, int modifiers) {
        if (key == InputConstants.KEY_TAB) {
            this.textField.insertText("  ");
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            this.textField.insertText("\n" + currentLineIndent());
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && autoBracketEnabled() && !this.textField.hasSelection()) {
            AutoBracket.Edit edit = AutoBracket.onBackspace(getValue(), this.textField.cursor());
            if (edit != null) {
                applyEdit(edit);
                return true;
            }
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    // ---- multiple carets ----

    /**
     * The keys that add carets. Ctrl+Alt+Up/Down: a caret on the line above
     * the topmost (below the bottommost). Ctrl+D: select the word at the caret,
     * then on each press the next occurrence of the selection too.
     */
    private boolean caretCommand(int key) {
        if (!Screen.hasControlDown()) {
            return false;
        }
        if (Screen.hasAltDown() && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            addCaretOnNextLine(key == InputConstants.KEY_UP);
            return true;
        }
        if (!Screen.hasAltDown() && !Screen.hasShiftDown() && key == GLFW.GLFW_KEY_D) {
            addNextOccurrence();
            return true;
        }
        return false;
    }

    private void addCaretOnNextLine(boolean up) {
        java.util.List<MultiCaret.Caret> all = allCarets();
        MultiCaret.Caret primary = all.get(0);
        MultiCaret.Caret edge = all.get(0);
        for (MultiCaret.Caret caret : all) {
            if (up ? caret.pos() < edge.pos() : caret.pos() > edge.pos()) {
                edge = caret;
            }
        }
        placeSelection(MultiCaret.Caret.at(edge.pos()));
        this.textField.seekCursorLine(up ? -1 : 1); // vanilla's own line step, keeping the column
        int added = this.textField.cursor();
        if (added == edge.pos()) {
            placeSelection(primary); // already on the first/last line
            return;
        }
        all.add(0, MultiCaret.Caret.at(added)); // the new caret leads
        setCarets(all, 0);
        recordUndo();
    }

    private void addNextOccurrence() {
        MultiCaret.Caret primary = readCaret();
        if (!primary.hasSelection()) {
            selectWordAtCursor(); // first press: just the word
            recordUndo();
            return;
        }
        String value = getValue();
        String needle = value.substring(primary.start(), primary.end());
        java.util.List<MultiCaret.Caret> all = allCarets();
        int after = 0;
        for (MultiCaret.Caret caret : all) {
            after = Math.max(after, caret.end());
        }
        int found = value.indexOf(needle, after);
        if (found < 0) {
            found = value.indexOf(needle); // wrap around to the top
        }
        for (MultiCaret.Caret caret : all) {
            if (caret.start() == found) {
                return; // every occurrence already has a caret
            }
        }
        if (found < 0) {
            return;
        }
        all.add(0, new MultiCaret.Caret(found + needle.length(), found));
        setCarets(all, 0);
        recordUndo();
    }

    /** Alt+click adds a caret where you click, or removes the one that's there. */
    private boolean altClick(double mouseX, double mouseY) {
        java.util.List<MultiCaret.Caret> all = allCarets();
        this.textField.setSelecting(false);
        this.textField.seekCursorToPoint(mouseX - getX() - innerPadding(),
                mouseY - getY() - innerPadding() + scrollAmount());
        int clicked = this.textField.cursor();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).hasSelection() && all.get(i).pos() == clicked) {
                if (all.size() > 1) {
                    all.remove(i);
                    setCarets(all, 0);
                } else {
                    placeSelection(all.get(0));
                }
                recordUndo();
                return true;
            }
        }
        all.add(0, MultiCaret.Caret.at(clicked));
        setCarets(all, 0);
        recordUndo();
        return true;
    }

    /**
     * Run {@code edit} at every caret (see MultiCaret), then make the result
     * the editor's carets, as ONE undo step.
     */
    private void runAtEachCaret(java.util.function.IntConsumer edit) {
        java.util.List<MultiCaret.Caret> all = allCarets();
        MultiCaret.Result result;
        batching = true;
        try {
            result = MultiCaret.applyAtEach(caretEditor, all, 0, edit);
        } finally {
            batching = false;
        }
        setCarets(result.carets(), result.primary());
        recordUndo();
    }

    /** Copy every selection, in text order, one per line (what a multi-caret paste splits again). */
    private void copyAll() {
        java.util.List<MultiCaret.Caret> all = new java.util.ArrayList<>(allCarets());
        all.sort(java.util.Comparator.comparingInt(MultiCaret.Caret::start));
        String value = getValue();
        java.util.List<String> pieces = new java.util.ArrayList<>();
        for (MultiCaret.Caret caret : all) {
            if (caret.hasSelection()) {
                pieces.add(value.substring(caret.start(), caret.end()));
            }
        }
        if (!pieces.isEmpty()) {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", pieces));
        }
    }

    /** Paste at every caret; with one clipboard line per caret, each caret gets its own line. */
    private void pasteAll() {
        String clip = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard()
                .replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = clip.split("\n", -1);
        boolean oneEach = lines.length == 1 + extraCarets.size() && lines.length > 1;
        runAtEachCaret(rank -> this.textField.insertText(oneEach ? lines[rank] : clip));
    }

    /** The primary caret first, then the extra ones. */
    private java.util.List<MultiCaret.Caret> allCarets() {
        java.util.List<MultiCaret.Caret> all = new java.util.ArrayList<>();
        all.add(readCaret());
        all.addAll(extraCarets);
        return all;
    }

    /** Make {@code carets} the editor's, {@code primary} becoming the text field's own caret. */
    private void setCarets(java.util.List<MultiCaret.Caret> carets, int primary) {
        MultiCaret.Result merged = MultiCaret.merge(carets, primary, getValue().length());
        extraCarets.clear();
        for (int i = 0; i < merged.carets().size(); i++) {
            if (i != merged.primary()) {
                extraCarets.add(merged.carets().get(i));
            }
        }
        placeSelection(merged.carets().get(merged.primary()));
        completion.dismiss(); // suggestions are a one-caret thing
    }

    private void clearExtraCarets() {
        extraCarets.clear();
    }

    /** Select the word at the caret, as the text field's own selectWordAtCursor does from 1.21.9. */
    private void selectWordAtCursor() {
        MultilineTextField.StringView word = this.textField.getPreviousWord();
        this.textField.setSelecting(false);
        this.textField.seekCursor(Whence.ABSOLUTE, word.beginIndex());
        this.textField.setSelecting(true);
        this.textField.seekCursor(Whence.ABSOLUTE, word.endIndex());
        this.textField.setSelecting(false);
    }

    /** The text field's caret and selection as a Caret (the anchor is the far end of the selection). */
    private MultiCaret.Caret readCaret() {
        int pos = this.textField.cursor();
        if (!this.textField.hasSelection()) {
            return MultiCaret.Caret.at(pos);
        }
        MultilineTextField.StringView selected = this.textField.getSelected();
        int anchor = pos == selected.beginIndex() ? selected.endIndex() : selected.beginIndex();
        return new MultiCaret.Caret(pos, anchor);
    }

    /** Put the text field's caret (and selection) exactly where {@code caret} says. */
    private void placeSelection(MultiCaret.Caret caret) {
        this.textField.setSelecting(false);
        this.textField.seekCursor(Whence.ABSOLUTE, caret.anchor());
        if (caret.hasSelection()) {
            this.textField.setSelecting(true);
            this.textField.seekCursor(Whence.ABSOLUTE, caret.pos());
        }
        this.textField.setSelecting(false);
    }

    /** Text replaced from outside (a rebuild, the collapsed box) leaves just the one caret. */
    @Override
    public void setValue(String value) {
        if (!batching) {
            clearExtraCarets();
        }
        super.setValue(value);
    }

    @Override
    public void setValue(String value, boolean allowOverflow) {
        if (!batching) {
            clearExtraCarets();
        }
        super.setValue(value, allowOverflow);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && Screen.hasAltDown()
                && isMouseOver(mouseX, mouseY)) {
            return altClick(mouseX, mouseY);
        }
        clearExtraCarets(); // a plain click is back to one caret
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

    /** Put a bare caret at {@code index}: for this editor taking over mid-edit from the collapsed box. */
    public void placeCaret(int index) {
        clearExtraCarets();
        replaceAndPlaceCaret(getValue(), Math.max(0, Math.min(index, getValue().length())));
    }

    /** Seed the undo history from the box's value the first time it's touched. */
    private void ensureHistory() {
        if (undoHistory == null) {
            undoHistory = new UndoHistory(getValue(), caretsForHistory());
        }
    }

    /** Every caret and selection, the primary first, as the undo history keeps them. */
    private java.util.List<UndoHistory.Caret> caretsForHistory() {
        java.util.List<UndoHistory.Caret> out = new java.util.ArrayList<>();
        for (MultiCaret.Caret caret : allCarets()) {
            out.add(new UndoHistory.Caret(caret.pos(), caret.anchor()));
        }
        return out;
    }

    /** Feed the current state to the history — skipped while we're applying an undo. */
    private void recordUndo() {
        if (!restoring && !batching && undoHistory != null) {
            undoHistory.record(getValue(), caretsForHistory(), System.currentTimeMillis());
        }
    }

    private void applyRestore(UndoHistory.State state) {
        if (state == null) {
            return; // nothing to undo/redo
        }
        clearExtraCarets();
        restoring = true;
        try {
            replaceAndPlaceCaret(state.text(), state.cursor());
            // then every caret and selection the state had, the primary first
            java.util.List<MultiCaret.Caret> carets = new java.util.ArrayList<>();
            for (UndoHistory.Caret caret : state.carets()) {
                carets.add(new MultiCaret.Caret(caret.pos(), caret.anchor()));
            }
            setCarets(carets, 0);
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
