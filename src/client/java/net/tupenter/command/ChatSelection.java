package net.tupenter.command;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import net.tupenter.config.TupenterConfig;
import net.tupenter.mixin.client.ChatComponentAccessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Click-drag text selection in the chat panel — the thing vanilla never
 * built because chat is immediate-mode glyphs, not text objects. Anchors are
 * held as GuiMessage.Line object references, so the selection follows its
 * lines when new messages shift everything upward, and dies naturally when
 * the lines leave history. Ctrl+C copies (newline at message ends, space at
 * soft wraps).
 *
 * Geometry mirrors ChatComponent's: the panel renders with
 * pose.scale(s, s).translate(4, 0), so local = screen/s - (4, 0), the
 * bottom line's local bottom edge is (guiHeight - 40) / s, and rows stack
 * upward in getLineHeight() steps.
 */
public final class ChatSelection {

    private static final int HIGHLIGHT_COLOR = 0x663399FF; // translucent selection blue
    private static final double DRAG_THRESHOLD = 2.0;

    private record Pos(GuiMessage.Line line, int charIndex) {
    }

    private static Pos anchor;
    private static Pos focus;
    private static boolean dragging;
    private static boolean active;
    private static double downX;
    private static double downY;

    private ChatSelection() {
    }

    public static boolean enabled() {
        return TupenterConfig.INSTANCE.chatSelectionEnabled;
    }

    public static void clear() {
        anchor = null;
        focus = null;
        dragging = false;
        active = false;
    }

    public static boolean hasSelection() {
        return active && anchor != null && focus != null;
    }

    public static void onMouseDown(Minecraft minecraft, double x, double y) {
        clear();
        if (!enabled()) {
            return;
        }
        Pos hit = hitTest(minecraft, x, y, true);
        if (hit != null) {
            anchor = hit;
            downX = x;
            downY = y;
            dragging = true;
        }
    }

    public static void onMouseDrag(Minecraft minecraft, double x, double y) {
        if (!dragging || anchor == null) {
            return;
        }
        if (!active && Math.abs(x - downX) + Math.abs(y - downY) < DRAG_THRESHOLD) {
            return; // plain clicks stay clicks (chat click-events keep working)
        }
        Pos hit = hitTest(minecraft, x, y, false);
        if (hit != null) {
            focus = hit;
            active = true;
        }
    }

    public static void onMouseUp() {
        dragging = false;
        if (!active) {
            clear();
        }
    }

    /** @return true when a selection was copied (the key event is consumed) */
    public static boolean copyToClipboard(Minecraft minecraft) {
        if (!hasSelection()) {
            return false;
        }
        String text = selectedText(minecraft);
        if (text.isEmpty()) {
            return false;
        }
        minecraft.keyboardHandler.setClipboard(text);
        return true;
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    private static Pos hitTest(Minecraft minecraft, double screenX, double screenY, boolean strict) {
        ChatComponent chat = minecraft.gui.getChat();
        ChatComponentAccessor access = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = access.tupenter$trimmedMessages();
        if (lines.isEmpty()) {
            return null;
        }

        double scale = chat.getScale();
        int lineHeight = access.tupenter$lineHeight();
        double localX = screenX / scale - 4.0;
        // rows counted UP from the bottom edge at guiHeight - 40, in line units
        double rowsUp = (minecraft.getWindow().getGuiScaledHeight() - screenY - 40.0) / (scale * lineHeight);

        int visible = Math.min(lines.size() - access.tupenter$scrollPos(), chat.getLinesPerPage());
        if (visible <= 0) {
            return null;
        }
        int row = (int) Math.floor(rowsUp);
        if (strict) {
            if (row < 0 || row >= visible) {
                return null;
            }
            int width = (int) Math.floor(chat.getWidth() / scale);
            if (localX < -4 || localX > width) {
                return null;
            }
        }
        row = Math.max(0, Math.min(visible - 1, row));
        int index = row + access.tupenter$scrollPos();
        if (index < 0 || index >= lines.size()) {
            return null;
        }

        GuiMessage.Line line = lines.get(index);
        return new Pos(line, charIndexAt(minecraft.font, line.content(), localX));
    }

    private static List<Float> perCharWidths(Font font, FormattedCharSequence sequence) {
        List<Float> widths = new ArrayList<>();
        sequence.accept((index, style, codePoint) -> {
            widths.add((float) font.width(
                    FormattedCharSequence.forward(new String(Character.toChars(codePoint)), style)));
            return true;
        });
        return widths;
    }

    /** Midpoint rule, like every text editor: click on the right half of a char selects past it. */
    private static int charIndexAt(Font font, FormattedCharSequence sequence, double localX) {
        double accumulated = 0;
        int i = 0;
        for (float width : perCharWidths(font, sequence)) {
            if (localX < accumulated + width / 2) {
                return i;
            }
            accumulated += width;
            i++;
        }
        return i;
    }

    private static float widthUpTo(Font font, FormattedCharSequence sequence, int charIndex) {
        float total = 0;
        int i = 0;
        for (float width : perCharWidths(font, sequence)) {
            if (i++ >= charIndex) {
                break;
            }
            total += width;
        }
        return total;
    }

    private static String plainText(FormattedCharSequence sequence) {
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    /** Normalized selection: start = the OLDER end (higher trimmed index). Null when a line left history. */
    private record Range(int startIndex, int startChar, int endIndex, int endChar) {
    }

    private static Range range(List<GuiMessage.Line> lines) {
        int anchorIndex = indexOfIdentity(lines, anchor.line());
        int focusIndex = indexOfIdentity(lines, focus.line());
        if (anchorIndex < 0 || focusIndex < 0) {
            return null;
        }
        if (anchorIndex == focusIndex) {
            return new Range(anchorIndex, Math.min(anchor.charIndex(), focus.charIndex()),
                    focusIndex, Math.max(anchor.charIndex(), focus.charIndex()));
        }
        return anchorIndex > focusIndex
                ? new Range(anchorIndex, anchor.charIndex(), focusIndex, focus.charIndex())
                : new Range(focusIndex, focus.charIndex(), anchorIndex, anchor.charIndex());
    }

    private static int indexOfIdentity(List<GuiMessage.Line> lines, GuiMessage.Line target) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Text runs oldest-to-newest = highest trimmed index down to lowest.
     * The start position's char is where the drag began on the OLDER line,
     * so the copied slice is start line from startChar, whole lines between,
     * end line up to endChar.
     */
    private static String selectedText(Minecraft minecraft) {
        ChatComponentAccessor access = (ChatComponentAccessor) minecraft.gui.getChat();
        List<GuiMessage.Line> lines = access.tupenter$trimmedMessages();
        Range range = range(lines);
        if (range == null) {
            clear();
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (int index = range.startIndex(); index >= range.endIndex(); index--) {
            GuiMessage.Line line = lines.get(index);
            String text = plainText(line.content());
            int from = index == range.startIndex() ? Math.min(range.startChar(), text.length()) : 0;
            int to = index == range.endIndex() ? Math.min(range.endChar(), text.length()) : text.length();
            if (from < to) {
                out.append(text, from, to);
            }
            if (index != range.endIndex()) {
                out.append(line.endOfEntry() ? "\n" : " ");
            }
        }
        return out.toString();
    }

    // =====================================================================
    // Overlay
    // =====================================================================

    /** Draws the selection highlight; called after ChatScreen rendered the chat. */
    public static void render(GuiGraphics graphics, Minecraft minecraft) {
        if (!hasSelection()) {
            return;
        }
        ChatComponent chat = minecraft.gui.getChat();
        ChatComponentAccessor access = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = access.tupenter$trimmedMessages();
        Range range = range(lines);
        if (range == null) {
            clear();
            return;
        }

        double scale = chat.getScale();
        int lineHeight = access.tupenter$lineHeight();
        int scroll = access.tupenter$scrollPos();
        int visible = Math.min(lines.size() - scroll, chat.getLinesPerPage());
        int chatBottom = (int) Math.floor((graphics.guiHeight() - 40) / scale);

        // 1.21.6 swapped GuiGraphics.pose() from the 3D PoseStack to a 2D
        // Matrix3x2fStack. Same transform, older spelling: push/popPose, and the
        // scale/translate calls take a z component (identity here — this is flat
        // GUI space).
        graphics.pose().pushPose();
        graphics.pose().scale((float) scale, (float) scale, 1.0f);
        graphics.pose().translate(4.0f, 0.0f, 0.0f);

        for (int row = 0; row < visible; row++) {
            int index = row + scroll;
            if (index < range.endIndex() || index > range.startIndex()) {
                continue;
            }
            GuiMessage.Line line = lines.get(index);
            FormattedCharSequence content = line.content();
            int x1 = index == range.startIndex()
                    ? Math.round(widthUpTo(minecraft.font, content, range.startChar())) : 0;
            int x2 = index == range.endIndex()
                    ? Math.round(widthUpTo(minecraft.font, content, range.endChar()))
                    : minecraft.font.width(content);
            if (x2 > x1) {
                int bottom = chatBottom - row * lineHeight;
                graphics.fill(x1, bottom - lineHeight, x2, bottom, HIGHLIGHT_COLOR);
            }
        }

        graphics.pose().popPose();
    }
}
