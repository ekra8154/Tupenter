package net.tupenter.script;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code list(a | b | c)} literal list — items of plain TEXT, separated by
 * top-level pipes.
 *
 * <p>This is the language's second way to build a list, and the difference from
 * {@code list(a, b, c)} is the ELEMENTS, not the shape:
 *
 * <ul>
 *   <li><b>commas → values.</b> {@code list(1, 2)} holds numbers, and its
 *       arguments are expressions that get computed.</li>
 *   <li><b>pipes → text.</b> {@code list(1 | 2)} holds the strings "1" and "2",
 *       and its items are taken exactly as typed.</li>
 * </ul>
 *
 * <p>Text is the whole point of the pipe form: bare words need no quotes, which
 * is what makes {@code list(short | tall | dry)} pleasant where
 * {@code list("short", "tall", "dry")} is noise. The cost is that items are
 * never computed — that is what commas are for.
 *
 * <p>Because items are text, they are gloriously permissive:
 * {@code list(a(1) | b)} and {@code list(0,0]} | 5.0,1]})} are both fine. Parentheses,
 * commas, spaces and brackets are ordinary characters here, not syntax. Only
 * three things are special — a top-level {@code |} separates, {@code \} escapes
 * the next character, and {@code $...$} spans are carried through untouched so a
 * pipe inside a marker never splits an item. A {@code "..."} span is skipped the
 * same way, for a sharper reason: without it, list("a|b", "c") — an ordinary
 * COMMA list whose string happens to contain a pipe — would be mistaken for a
 * pipe list and torn in half. The quote characters themselves stay in the item,
 * because pipe items are raw text: that is what lets NBT keep its own strings,
 * as in list({id:"minecraft:stone"} | b).
 *
 * <p>This form used to be written as bare parentheses, and only inside a
 * {@code #foreach} header. It moved into {@code list(...)} so that a list has one
 * spelling and a parenthesis has one meaning — grouping. The splitting rules
 * came with it unchanged.
 */
public final class ListLiteral {

    private ListLiteral() {
    }

    /**
     * The index of the {@code )} closing the group that opens at
     * {@code openParen}, or -1 if it never closes.
     *
     * <p>Scans by the LITERAL rules above — escapes and {@code $} spans — so it
     * agrees with {@link #split} about where the group ends even when the
     * contents are nothing like an expression.
     */
    public static int groupEnd(String text, int openParen) {
        boolean insideSpan = false;
        boolean insideQuotes = false;
        int depth = 0;
        for (int i = openParen; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"' && !insideSpan) {
                insideQuotes = !insideQuotes;
                continue;
            }
            if (c == '$' && !insideQuotes) {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan || insideQuotes) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Does {@code [from, end)} hold a separator pipe — one that would make this
     * group a literal list rather than a parenthesised expression?
     *
     * <p>{@code ||} does not count. It is boolean or, and {@code (a || b)} has to
     * stay an expression; this is the check that keeps it one.
     */
    public static boolean hasSeparatorPipe(String text, int from, int end) {
        boolean insideSpan = false;
        boolean insideQuotes = false;
        int depth = 0;
        for (int i = from; i < end && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"' && !insideSpan) {
                insideQuotes = !insideQuotes;
                continue;
            }
            if (c == '$' && !insideQuotes) {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan || insideQuotes) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '|' && depth == 0) {
                if (i + 1 < end && text.charAt(i + 1) == '|') {
                    i++; // boolean or — skip both characters, not a separator
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** The items of a literal list, given the text BETWEEN the parentheses. */
    public static List<Value> values(String inner) {
        List<Value> values = new ArrayList<>();
        for (String item : split(inner)) {
            values.add(Value.of(item));
        }
        return values;
    }

    /**
     * Splits on top-level pipes, decoding {@code \} escapes and trimming each
     * item. {@code ||} is left in the text rather than treated as two separators,
     * matching {@link #hasSeparatorPipe}.
     *
     * @throws IllegalArgumentException when the list has no items at all
     */
    public static List<String> split(String inner) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideSpan = false;
        boolean insideQuotes = false;
        int depth = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                current.append(inner.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"' && !insideSpan) {
                insideQuotes = !insideQuotes;
                current.append(c);
                continue;
            }
            if (c == '$' && !insideQuotes) {
                insideSpan = !insideSpan;
                current.append(c);
                continue;
            }
            if (!insideSpan && !insideQuotes) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == '|' && depth == 0) {
                    if (i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                        current.append("||"); // boolean or, not two separators
                        i++;
                        continue;
                    }
                    items.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        items.add(current.toString().trim());

        if (items.size() == 1 && items.get(0).isEmpty()) {
            throw new IllegalArgumentException("a list needs at least one item, e.g. list(a | b)");
        }
        return items;
    }
}
