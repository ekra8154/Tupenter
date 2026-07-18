package net.tupenter.script;

import java.util.List;

/**
 * A parsed script: the original typed line plus the statements it resolves to.
 *
 * @param history how the resend history treats this line: NORMAL follows the
 *                recording toggle + filter, SKIP (#norecord) never records,
 *                FORCE (#record) records even when recording is off
 */
public record Script(String originalLine, List<SendStatement> statements, HistoryMode history) {

    public enum HistoryMode {
        NORMAL,
        SKIP,
        FORCE
    }

    /**
     * One outgoing statement. Content never includes the leading slash.
     *
     * @param silent true when this statement is inside a #silent scope —
     *               server feedback around its send is suppressed locally
     */
    public record SendStatement(String content, Kind kind, boolean silent) {

        public SendStatement(String content, Kind kind) {
            this(content, kind, false);
        }

        public boolean isCommand() {
            return kind == Kind.COMMAND;
        }
    }

    public enum Kind {
        COMMAND, // sent as a command packet
        CHAT     // sent as a chat packet
    }
}
