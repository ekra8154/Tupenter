package net.tupenter.script;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ## } comments — a note in a script that never runs.
 *
 * <p>Every sigil a comment would normally use was already taken: {@code /}
 * starts a command, {@code #} starts a directive, and {@code //} belongs to
 * WorldEdit, whose {@code //set} and {@code //copy} pass straight through this
 * mod on a great many servers. {@code ##} was the one spelling that was free
 * everywhere it matters and still reads like a comment.
 *
 * <p>Two rules, and both of them exist to keep {@code ##} usable as ordinary
 * text everywhere else:
 *
 * <ul>
 *   <li><b>Position.</b> A comment begins only where a statement could — at the
 *       start of a line, or immediately after an {@code &&}. Anywhere else the
 *       characters are content, so {@code /say ## hi} still says "## hi".</li>
 *   <li><b>A space.</b> {@code ## note} is a comment; {@code ##hashtag} is not.
 *       This is what keeps a chat line like "##1 winner" sendable.</li>
 * </ul>
 *
 * <p>A comment runs to the end of its LINE rather than to the next {@code &&},
 * because script bodies are written in a multi-line box and that is where
 * comments earn their keep. It also makes the trailing form work: the {@code &&}
 * before a comment has already done the separating, so the next line continues
 * the chain without one of its own.
 *
 * <pre>
 * ## a counter that survives rejoins
 * #setdefault i = 0 &&        ## start at zero
 * #set i += 1 &&
 * /say run number $i$
 * </pre>
 *
 * <p>Comments are removed before anything else looks at the text, so they are
 * invisible to the parser rather than being a statement it has to skip. That is
 * the difference between this and a {@code #note} directive, which would have to
 * be chained with {@code &&} like every other statement.
 *
 * <p>The scan carries {@code "..."} strings, {@code $...$} markers and
 * {@code \}-escapes through untouched, the same way {@link ListLiteral} does —
 * a {@code ##} inside any of those is content no matter where it sits.
 */
public final class Comments {

    /** The two characters that open a comment. */
    public static final String MARK = "##";

    private Comments() {
    }

    /**
     * Every comment in {@code text}, as {@code {start, end}} pairs. {@code end}
     * is exclusive and never includes the terminating newline, so removing a
     * span leaves the line structure around it intact.
     */
    public static List<int[]> spans(String text) {
        List<int[]> spans = new ArrayList<>();
        boolean insideSpan = false;
        boolean insideQuotes = false;
        boolean contentOnLine = false; // anything non-blank since the last newline
        boolean afterChain = false;    // the last non-blank thing was an &&

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\\') {
                contentOnLine = true;
                afterChain = false;
                i++; // whatever follows a backslash is content, never syntax
                continue;
            }
            if (c == '\n' || c == '\r') {
                contentOnLine = false;
                continue; // afterChain survives: "&&" at the end of a line still chains
            }
            if (c == '"' && !insideSpan) {
                insideQuotes = !insideQuotes;
                contentOnLine = true;
                afterChain = false;
                continue;
            }
            if (c == '$' && !insideQuotes) {
                insideSpan = !insideSpan;
                contentOnLine = true;
                afterChain = false;
                continue;
            }
            if (insideSpan || insideQuotes) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue; // blanks change neither flag
            }

            if (opensCommentAt(text, i) && (!contentOnLine || afterChain)) {
                int end = lineEnd(text, i);
                spans.add(new int[]{i, end});
                i = end - 1;
                afterChain = false;
                continue;
            }

            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                contentOnLine = true;
                afterChain = true;
                i++;
                continue;
            }
            contentOnLine = true;
            afterChain = false;
        }
        return spans;
    }

    /**
     * Is a comment's {@code ##} at {@code i}? Position is the caller's business;
     * this is only the spelling — two hashes, then a blank or the end of the line.
     */
    private static boolean opensCommentAt(String text, int i) {
        if (!text.startsWith(MARK, i)) {
            return false;
        }
        int after = i + MARK.length();
        return after >= text.length() || Character.isWhitespace(text.charAt(after));
    }

    private static int lineEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return text.length();
    }

    /**
     * {@code text} with its comments removed. The newline that ended each
     * comment stays, so the lines around it still line up the way they were
     * written.
     */
    public static String strip(String text) {
        List<int[]> spans = spans(text);
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        for (int[] span : spans) {
            out.append(text, at, span[0]);
            at = span[1];
        }
        out.append(text, at, text.length());
        return out.toString();
    }

    /** True when everything in {@code text} is comment or blank space. */
    public static boolean isOnlyComment(String text) {
        return !text.isBlank() && strip(text).isBlank();
    }

    /** The message for a line that turned out to have nothing left to run. */
    public static String nothingToRunMessage() {
        return "That line is only a comment — ## marks a note, so there's nothing left to run";
    }
}
