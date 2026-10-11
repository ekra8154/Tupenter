package net.tupenter.command;

import net.tupenter.TupenterModClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Tupenter completes at a cursor: variables and functions inside a
 * $...$ marker or a directive's header expression, registry tags after '#',
 * the :type of an assignment, and directive words.
 *
 * <p>Shared by the chat bar (MixinCommandSuggestions) and the config
 * screen's editor boxes, so both offer exactly the same things.
 */
public final class ExpressionCompletion {

    private ExpressionCompletion() {
    }

    /**
     * @param start       where the token being completed begins (what a pick replaces, up to the cursor)
     * @param suggestions candidates that start with the typed token, case-insensitive; may be empty
     */
    public record Result(int start, List<String> suggestions) {
    }

    /**
     * Completions for one chat-style line. Null when the cursor is in none of
     * Tupenter's zones (plain command text, which Brigadier owns).
     */
    public static Result forLine(String text, int cursor) {
        int tokenStart = ChatInputStyler.markerTokenStart(text, cursor);
        List<String> candidates;
        if (tokenStart >= 0) {
            // inside a $...$ marker: variables/functions, or tags after '#'
            String prefix = text.substring(tokenStart, cursor).toLowerCase(Locale.ROOT);
            candidates = prefix.startsWith("#")
                    ? TupenterModClient.tagCompletions(ChatInputStyler.enclosingCallName(text, tokenStart))
                    : TupenterModClient.expressionCompletions(prefix);
        } else if ((tokenStart = ChatInputStyler.headerExprTokenStart(text, cursor)) >= 0) {
            // a scanner directive's header (#foreach ... in itemset(#|), #if $x$>|),
            // or an assignment's right-hand side: both are implicit expression
            // zones, completed like a marker
            String prefix = text.substring(tokenStart, cursor).toLowerCase(Locale.ROOT);
            if (prefix.startsWith("#")) {
                candidates = TupenterModClient.tagCompletions(ChatInputStyler.enclosingCallName(text, tokenStart));
            } else {
                // a coordinate-typed assignment gets the position forms FIRST:
                // #local c:blockpos = | offers the block you're looking at
                candidates = new ArrayList<>(TupenterModClient.positionForms(text, cursor, prefix));
                candidates.addAll(TupenterModClient.expressionCompletions(prefix));
            }
        } else if ((tokenStart = ChatInputStyler.assignTypeTokenStart(text, cursor)) >= 0) {
            // the :type of an assignment (#local c:bloc|): the same keywords a
            // custom command's <name:type> parameters take
            candidates = net.tupenter.script.ParamTypeDocs.keywords();
        } else {
            // typing a directive word (#norec... / after-')' #elseif)
            tokenStart = ChatInputStyler.directiveTokenStart(text, cursor);
            if (tokenStart < 0) {
                return null;
            }
            candidates = ChatInputStyler.directiveWordSuggestions(text, tokenStart);
        }
        String prefix = text.substring(tokenStart, cursor).toLowerCase(Locale.ROOT);
        List<String> matching = new ArrayList<>();
        for (String name : candidates) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix) && !matching.contains(name)) {
                matching.add(name);
            }
        }
        return new Result(tokenStart, matching);
    }

    /**
     * Completions for a config editor's text, which differs from a chat line in
     * three ways: newlines are only formatting (a script is one runtime line,
     * so they flatten 1:1 to spaces, as the editor's styling does), a
     * definition row ("name &lt;params&gt; = body") only completes inside its
     * body, and the resend-preset editor is one independent command per line.
     */
    public static Result forEditor(String raw, int cursor, boolean definition, boolean perLine) {
        cursor = Math.max(0, Math.min(cursor, raw.length()));
        if (perLine) {
            int lineStart = raw.lastIndexOf('\n', cursor - 1) + 1;
            int lineEnd = raw.indexOf('\n', cursor);
            String line = raw.substring(lineStart, lineEnd < 0 ? raw.length() : lineEnd);
            // like the chat bar: markers only mean something on / and # lines
            String lead = line.stripLeading();
            if (!lead.startsWith("/") && !lead.startsWith("#")) {
                return null;
            }
            return offset(forLine(line, cursor - lineStart), lineStart);
        }
        String flat = raw.replace('\r', ' ').replace('\n', ' ');
        if (definition) {
            int separator = net.tupenter.script.AliasDefinition.signatureSeparator(flat);
            if (separator >= 0) {
                if (cursor <= separator) {
                    return null; // still in the name and parameters
                }
                return offset(forLine(flat.substring(separator + 1), cursor - separator - 1), separator + 1);
            }
        }
        return forLine(flat, cursor);
    }

    private static Result offset(Result result, int by) {
        return result == null ? null : new Result(result.start() + by, result.suggestions());
    }
}
