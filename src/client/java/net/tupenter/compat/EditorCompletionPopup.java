package net.tupenter.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.tupenter.command.ExpressionCompletion;
import net.tupenter.config.TupenterConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat bar's suggestions for the config screen's editor boxes: Tupenter's
 * own ($client.po| offering client.pos) inside expressions and directives, and
 * vanilla command names and arguments everywhere else (see CommandCompletion).
 * One per box; the box feeds it edits and keys and asks it to draw.
 *
 * <p>It only appears after you type, and moving the caret any other way
 * (arrows, Home/End, a click, Enter) puts it away until you type again. In a
 * multi-line editor Up/Down mostly mean "go to the line above", so the list
 * must not grab them just because the caret happens to rest inside a $...$.
 * While it is showing, Tab puts the highlighted entry in and further Tabs
 * cycle through the rest (Shift+Tab back), Up/Down pick, and typing anything
 * commits, exactly as in chat; otherwise Tab does what the box always did.
 *
 * <p>Drawn as a deferred tooltip anchored under (or over) the caret, so it
 * lands on top of the rows below instead of being painted over by them, and
 * is not clipped to the list.
 */
final class EditorCompletionPopup {

    /** How a box exposes its text to the popup. */
    interface Target {
        String value();

        int cursor();

        /** Replace [start, cursor) with {@code text} and leave the caret after it. */
        void replaceToCursor(int start, String text);

        boolean definition();

        boolean perLine();
    }

    private static final int MAX_ROWS = 10;

    private final Target target;
    private boolean active;
    private int selected;
    private String computedFor;
    private ExpressionCompletion.Result result;
    /**
     * While Tab is cycling: the list as it was when cycling began, and where
     * each pick goes. Null when not cycling. Snapshotted because every pick
     * changes the text, and recomputing for "minecraft:acacia_button" would
     * leave nothing but that one entry to cycle through.
     */
    private List<String> cycleList;
    private int cycleStart;

    EditorCompletionPopup(Target target) {
        this.target = target;
    }

    /** Text was typed or deleted: show (or refresh) the list from the top. */
    void onEdited() {
        active = true;
        selected = 0;
        cycleList = null; // typing commits the pick and asks again
    }

    void dismiss() {
        active = false;
        cycleList = null;
    }

    boolean showing() {
        if (!active || !enabled()) {
            return false;
        }
        if (cycleList != null) {
            return true;
        }
        List<String> list = suggestions();
        if (list.isEmpty()) {
            return false;
        }
        // nothing left to add: the one candidate is exactly what's typed
        String typed = typedToken();
        return !(list.size() == 1 && list.get(0).equalsIgnoreCase(typed));
    }

    /**
     * Keys while the list is up. Returns true when consumed. Keys that move
     * the caret without typing put the list away and are NOT consumed.
     */
    boolean keyPressed(KeyEvent event) {
        if (!showing()) {
            return false;
        }
        int key = event.key();
        List<String> list = cycleList != null ? cycleList : suggestions();
        if (key == InputConstants.KEY_TAB) {
            // as in chat: the first Tab puts the highlighted entry in, each
            // further Tab (Shift+Tab backwards) swaps it for the next, and
            // typing anything commits whatever is in
            if (cycleList == null) {
                cycleList = List.copyOf(list);
                cycleStart = result.start();
                selected = Math.min(selected, list.size() - 1);
            } else {
                selected = Math.floorMod(selected + (event.hasShiftDown() ? -1 : 1), cycleList.size());
            }
            target.replaceToCursor(cycleStart, cycleList.get(selected));
            return true;
        }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            selected = Math.floorMod(selected + (key == InputConstants.KEY_UP ? -1 : 1), list.size());
            if (cycleList != null) {
                target.replaceToCursor(cycleStart, cycleList.get(selected)); // already cycling: swap it in
            }
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT
                || key == InputConstants.KEY_HOME || key == InputConstants.KEY_END
                || key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN
                || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER
                || key == InputConstants.KEY_ESCAPE) {
            dismiss(); // the caret is going somewhere on purpose
        }
        return false;
    }

    /** Draw the list if it is showing, anchored to the caret at (caretX, caretY). */
    void render(GuiGraphics graphics, Font font, int caretX, int caretY) {
        if (!showing()) {
            return;
        }
        List<String> list = cycleList != null ? cycleList : suggestions();
        selected = Math.min(selected, list.size() - 1);
        int first = Math.max(0, Math.min(selected - MAX_ROWS / 2, list.size() - MAX_ROWS));
        int last = Math.min(list.size(), first + MAX_ROWS);
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (first > 0) {
            lines.add(Component.literal("↑ " + first + " more").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        for (int i = first; i < last; i++) {
            lines.add(Component.literal(list.get(i))
                    .withStyle(i == selected ? ChatFormatting.YELLOW : ChatFormatting.GRAY)
                    .getVisualOrderText());
        }
        if (last < list.size()) {
            lines.add(Component.literal("↓ " + (list.size() - last) + " more").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        ScreenRectangle caret = new ScreenRectangle(caretX, caretY, 1, font.lineHeight);
        // focused = true: wins over a hovered button's tooltip set later in the frame
        graphics.setTooltipForNextFrame(font, lines, new BelowOrAboveWidgetTooltipPositioner(caret), caretX, caretY, true);
    }

    private List<String> suggestions() {
        String value = target.value();
        int cursor = target.cursor();
        String key = cursor + ":" + value;
        if (!key.equals(computedFor)) {
            computedFor = key;
            result = ExpressionCompletion.forEditor(value, cursor, target.definition(), target.perLine());
            if (result == null) {
                requestCommandCompletion(value, cursor, key);
            }
        }
        return result == null ? List.of() : result.suggestions();
    }

    /**
     * Outside Tupenter's own zones, ask Brigadier about the command under the
     * cursor. The answer can arrive later (an argument whose suggestions come
     * from the server), so it lands on the game thread and is only used if the
     * text and cursor are still the ones it was asked about.
     */
    private void requestCommandCompletion(String value, int cursor, String key) {
        java.util.concurrent.CompletableFuture<ExpressionCompletion.Result> future =
                net.tupenter.command.CommandCompletion.forEditor(value, cursor, target.definition(), target.perLine());
        if (future == null) {
            return;
        }
        future.thenAcceptAsync(answer -> {
            if (key.equals(computedFor)) {
                result = answer;
            }
        }, net.minecraft.client.Minecraft.getInstance());
    }

    private String typedToken() {
        String value = target.value();
        int cursor = Math.min(target.cursor(), value.length());
        int start = Math.max(0, Math.min(result.start(), cursor));
        return value.substring(start, cursor);
    }

    private static boolean enabled() {
        return TupenterConfig.INSTANCE.enhancedCommandParsingEnabled;
    }
}
