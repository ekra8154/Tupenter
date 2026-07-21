package net.tupenter.script;

/**
 * The optional leading name on a tick script — the custom-command form without
 * params: {@code restock = /clear && #wait 1s} names the script "restock" and
 * runs "/clear && #wait 1s". A name is a bare identifier
 * ({@code [A-Za-z_][A-Za-z0-9_]*}) before the first {@code =}; anything else
 * (a command, a directive, a body that merely contains {@code =}) is all body.
 */
public final class ScriptName {
    private ScriptName() {
    }

    /** The leading name, or "" when the text is just a body. */
    public static String name(String text) {
        String s = text.strip();
        int eq = s.indexOf('=');
        if (eq <= 0) {
            return "";
        }
        String head = s.substring(0, eq).strip();
        return isIdentifier(head) ? head : "";
    }

    /** The runnable part — everything after {@code name =}, or the whole text when unnamed. */
    public static String body(String text) {
        String s = text.strip();
        if (name(text).isEmpty()) {
            return s;
        }
        return s.substring(s.indexOf('=') + 1).strip();
    }

    private static boolean isIdentifier(String head) {
        if (head.isEmpty() || !(Character.isLetter(head.charAt(0)) || head.charAt(0) == '_')) {
            return false;
        }
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }
}
