package net.tupenter.compat;

/**
 * Carrying an edit made in a row's collapsed one-line box back onto its
 * formatted, multi-line text.
 *
 * <p>The collapsed box shows the text flattened (Comments.flatten): every run
 * of blanks holding a line break becomes one space, and nothing else changes,
 * so the two line up character for character once whitespace is set aside.
 * That is enough to replay an edit exactly: find what changed in the flat
 * text, find the same spot in the formatted text, and splice it in there,
 * keeping every line break and indent the edit didn't touch. Before this, an
 * edit made while collapsed threw the formatting away on the next expand.
 */
final class CollapsedText {

    private CollapsedText() {
    }

    /**
     * {@code raw} with the edit that turned {@code flatBefore} into
     * {@code flatAfter} applied, or null when the two texts don't line up
     * (flatten also joins a ## comment to the next line with &&, which has no
     * counterpart in the raw text) and the edit can't be placed exactly.
     */
    static String applyEdit(String raw, String flatBefore, String flatAfter) {
        if (flatBefore.equals(flatAfter)) {
            return raw;
        }
        int prefix = 0;
        int limit = Math.min(flatBefore.length(), flatAfter.length());
        while (prefix < limit && flatBefore.charAt(prefix) == flatAfter.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && flatBefore.charAt(flatBefore.length() - 1 - suffix) == flatAfter.charAt(flatAfter.length() - 1 - suffix)) {
            suffix++;
        }
        int from = rawIndex(raw, flatBefore, prefix);
        int to = rawIndex(raw, flatBefore, flatBefore.length() - suffix);
        if (from < 0 || to < from) {
            return null;
        }
        return raw.substring(0, from) + flatAfter.substring(prefix, flatAfter.length() - suffix) + raw.substring(to);
    }

    /**
     * The index in {@code raw} that index {@code flatIndex} of {@code flat}
     * stands for, or -1 when they don't line up. Just after a character, it is
     * just after that same character; just after a space, it is just before
     * the next character, so text typed at the start of a flattened line lands
     * after the line break, not before it.
     */
    static int rawIndex(String raw, String flat, int flatIndex) {
        if (!stripBlanks(raw).equals(stripBlanks(flat))) {
            return -1;
        }
        flatIndex = Math.max(0, Math.min(flatIndex, flat.length()));
        int count = 0; // characters (not blanks) before flatIndex
        for (int i = 0; i < flatIndex; i++) {
            if (!Character.isWhitespace(flat.charAt(i))) {
                count++;
            }
        }
        boolean afterBlank = flatIndex > 0 && Character.isWhitespace(flat.charAt(flatIndex - 1));
        int seen = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isWhitespace(raw.charAt(i))) {
                continue;
            }
            if (seen == count) {
                // the next character: right before it after a blank, otherwise
                // right after the previous one
                return afterBlank ? i : endOfPrevious(raw, i);
            }
            seen++;
        }
        return afterBlank || count == 0 ? raw.length() : endOfPrevious(raw, raw.length());
    }

    /** Just past the last non-blank character before {@code index} (0 if none). */
    private static int endOfPrevious(String raw, int index) {
        int i = index;
        while (i > 0 && Character.isWhitespace(raw.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static String stripBlanks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                out.append(text.charAt(i));
            }
        }
        return out.toString();
    }
}
