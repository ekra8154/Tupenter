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
 * <p>A comment ends at the next {@code &&} or the end of its line, whichever
 * comes first. Both terminators are needed, and for different shapes:
 *
 * <pre>
 * ## a counter that survives rejoins
 * #setdefault i = 0 &&        ## start at zero   (ends at the line)
 * #set i += 1 &&
 * /say run number $i$
 *
 * ## bottom left && /activate 1 2 3 && ## the other one && /activate 2 3 4
 * </pre>
 *
 * <p>The line ending is what lets a note sit above the statement it explains, or
 * trail one, in the multi-line editor where scripts are actually written. The
 * {@code &&} ending is what lets a one-liner carry notes BETWEEN its statements,
 * which is the only place to put them when the whole script is one line.
 *
 * <p>The price is that a comment cannot contain {@code &&}. That is the right
 * price: {@code &&} separates statements everywhere else in the language, and a
 * comment that could swallow one would be the single exception.
 *
 * <p>The {@code &&} that ends a comment is eaten with it, since it terminated a
 * note rather than separating two statements. What is left is exactly the line
 * with its notes lifted out.
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
                int end = commentEnd(text, i);
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

    /**
     * Where the comment opening at {@code from} stops: the next {@code &&} or
     * the end of the line. The scan is LITERAL - inside a comment there is no
     * such thing as a string, a marker or an escape, so those are the only two
     * sequences that mean anything.
     *
     * <p>A terminating {@code &&} is included in the span. It ended a note; it
     * was never separating two statements, and leaving it behind would strand an
     * empty segment at the front of the line.
     */
    private static int commentEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                return i + 2;
            }
        }
        return text.length();
    }

    /**
     * {@code text} with its comments removed. A newline that ended a comment
     * stays, so the lines around it still line up the way they were written; an
     * {@code &&} that ended one goes with it.
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

    /**
     * {@code text} as ONE line, with every comment still ending where it did.
     *
     * <p>Bodies are written over several lines and RUN as one — the config
     * screen collapses a row to a single line, and the tick runner collapses a
     * script before parsing it. Both did that by turning newlines into spaces,
     * which is exactly right until a comment is involved: a note that ended at
     * its newline suddenly has no newline, so it runs on to the next {@code &&}
     * and swallows the statement below it. The line still parses, so the only
     * symptom is a variable that was never assigned.
     *
     * <p>So a newline that ENDED a comment becomes {@code &&} rather than a
     * space. That terminates the note exactly where it terminated before, and
     * the empty segment it leaves behind is skipped like any other.
     *
     * <pre>
     * ## set up          →   ## set up && #set i = 0     (not "## set up #set i = 0")
     * #set i = 0
     * </pre>
     */
    public static String flatten(String text) {
        java.util.Set<Integer> endsAComment = new java.util.HashSet<>();
        for (int[] span : spans(text)) {
            if (span[1] < text.length() && isLineBreak(text.charAt(span[1]))) {
                endsAComment.add(span[1]);
            }
        }

        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            // a run of blanks holding at least one line break collapses to a
            // single separator; blanks within a line are left alone
            int j = i;
            boolean sawBreak = false;
            boolean chain = false;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                if (isLineBreak(text.charAt(j))) {
                    sawBreak = true;
                    if (endsAComment.contains(j)) {
                        chain = true;
                    }
                }
                j++;
            }
            if (!sawBreak) {
                out.append(text, i, j);
            } else {
                while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
                    out.setLength(out.length() - 1);
                }
                out.append(chain ? " && " : " ");
            }
            i = j;
        }
        return out.toString().trim();
    }

    private static boolean isLineBreak(char c) {
        return c == '\r' || c == '\n';
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
