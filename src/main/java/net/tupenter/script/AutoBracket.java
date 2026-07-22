package net.tupenter.script;

/**
 * The decision logic for auto-closing brackets in an edit box — pure string
 * work, no Minecraft, so it unit-tests. The client adapters (chat bar's
 * EditBox, the Mod Menu ScriptEditBox) read their widget's text + selection,
 * call this, and apply the returned {@link Edit}.
 *
 * <p>Four behaviors, the ones that make auto-pairing feel like an editor
 * instead of a nuisance:
 * <ul>
 *   <li><b>auto-close</b> — type {@code (} on an empty caret → {@code ()} with
 *       the caret between; {@code $} → {@code $$}</li>
 *   <li><b>wrap selection</b> — select text, type {@code (} → {@code (text)}</li>
 *   <li><b>skip over</b> — type {@code )} when the next char is already {@code )}
 *       → step past it instead of inserting a second</li>
 *   <li><b>delete pair</b> — backspace inside an empty {@code (|)} → delete both</li>
 * </ul>
 *
 * <p>Auto-close is suppressed inside a {@code "quoted string"} (a literal zone —
 * a {@code $} there wouldn't evaluate, a {@code (} is just text). Wrapping a
 * selection is always allowed: selecting and pressing a bracket is unambiguous
 * intent. WHERE this runs (only {@code /} and {@code #} lines in the chat bar,
 * always in the script editor) is the caller's decision, not this class's.
 */
public final class AutoBracket {
    private AutoBracket() {
    }

    /** The result of a keystroke: the whole new text and where the caret lands. */
    public record Edit(String text, int cursor) {
    }

    /** The closing partner of an opener/symmetric char, or 0 if it isn't one. */
    private static char closerFor(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"' -> '"';
            case '$' -> '$';
            default -> 0;
        };
    }

    private static boolean isCloser(char c) {
        return c == ')' || c == ']' || c == '}' || c == '"' || c == '$';
    }

    /**
     * Decide what typing {@code typed} does. {@code selStart <= selEnd} describe
     * the current selection (equal = just a caret). Returns the edit to apply,
     * or null to let the keystroke type normally.
     */
    public static Edit onChar(String text, int selStart, int selEnd, char typed) {
        char close = closerFor(typed);
        boolean hasSelection = selStart != selEnd;

        // wrap: selecting text and pressing a bracket wraps it (always allowed)
        if (hasSelection && close != 0) {
            String wrapped = text.substring(0, selStart) + typed
                    + text.substring(selStart, selEnd) + close + text.substring(selEnd);
            return new Edit(wrapped, selEnd + 2); // caret just past the inserted close
        }
        if (hasSelection) {
            return null; // any other char replaces the selection the normal way
        }

        int cursor = selStart;
        // skip over an existing closing char sitting right after the caret
        if (isCloser(typed) && cursor < text.length() && text.charAt(cursor) == typed) {
            return new Edit(text, cursor + 1);
        }
        // auto-close — but not inside a quoted string (a literal zone)
        if (close != 0 && !inQuotedString(text, cursor)) {
            String inserted = text.substring(0, cursor) + typed + close + text.substring(cursor);
            return new Edit(inserted, cursor + 1); // caret between the pair
        }
        return null;
    }

    /**
     * Backspace with the caret between an empty auto-pair ({@code (|)},
     * {@code $|$}, …) deletes both. Returns null for any other backspace, so
     * the widget's normal delete runs.
     */
    public static Edit onBackspace(String text, int cursor) {
        if (cursor <= 0 || cursor >= text.length()) {
            return null;
        }
        char before = text.charAt(cursor - 1);
        char after = text.charAt(cursor);
        if (closerFor(before) != 0 && closerFor(before) == after) {
            String edited = text.substring(0, cursor - 1) + text.substring(cursor + 1);
            return new Edit(edited, cursor - 1);
        }
        return null;
    }

    /**
     * Is the caret inside a {@code "…"} string? Counts unescaped double-quotes
     * from the start of the caret's line — an odd count means inside. Per-line
     * so a stray quote on one line of the editor doesn't poison the next.
     */
    private static boolean inQuotedString(String text, int cursor) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        boolean inside = false;
        for (int i = lineStart; i < cursor; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped char
            } else if (c == '"') {
                inside = !inside;
            }
        }
        return inside;
    }
}
