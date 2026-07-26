package net.tupenter.command;

/**
 * Does this script body contain a command that leaves the client?
 *
 * <p>Static and evaluation-free: it never runs the script, so it works in the
 * config screen with no world loaded. It walks the raw text looking for a
 * command word at a position where a statement can begin — the start, just
 * after a top-level {@code &&}, or just after a {@code (} that opens a group.
 * Anything that isn't one of Tupenter's own client-side commands is assumed to
 * reach the server.
 *
 * <p><b>Sound in one direction only.</b> A true result means a server-bound
 * command really is in there. A false result means <em>none was found</em> — not
 * that the script is safe. A {@code $...$} expression can produce a command name
 * at run time, and this cannot see that. The warning it drives is worded to
 * match: it flags what it finds and never claims the absence of a finding is a
 * clean bill of health.
 *
 * <p>Custom commands count as server-bound. {@code /blink} is not one of
 * Tupenter's commands, and its body is {@code /tp} — so treating an unknown
 * command word as sending is both simpler and errs the safe way. A custom
 * command whose body happens to be purely local is a false positive, which the
 * "might" in the warning covers.
 */
public final class ServerTrafficScan {

    private ServerTrafficScan() {
    }

    /** True when a command that would reach the server appears anywhere in the body. */
    public static boolean sendsToServer(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        boolean inQuote = false;
        boolean atStatementStart = true;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);

            if (inQuote) {
                if (c == '\\') {
                    i++; // an escaped character is never a closing quote
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
                atStatementStart = false;
                continue;
            }
            // '&&' ends a statement; '(' opens a group or a condition. A condition
            // can never begin with '/', so treating both alike is safe here.
            if (c == '&' && i + 1 < body.length() && body.charAt(i + 1) == '&') {
                atStatementStart = true;
                i++;
                continue;
            }
            if (c == '(') {
                atStatementStart = true;
                continue;
            }
            if (c == ')' || Character.isWhitespace(c)) {
                continue; // whitespace does not end the run-up to a statement
            }
            if (atStatementStart) {
                if (c == '/' && !CommandAliasManager.MOD_COMMANDS.contains(commandWordAt(body, i + 1))) {
                    return true;
                }
                atStatementStart = false;
            }
        }
        return false;
    }

    /** The command word starting at {@code from}, lowercased, or "" if there isn't one. */
    private static String commandWordAt(String text, int from) {
        int end = from;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':') {
                end++;
            } else {
                break;
            }
        }
        return text.substring(from, end).toLowerCase(java.util.Locale.ROOT);
    }
}
