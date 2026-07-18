package net.tupenter.script;

import java.util.Iterator;
import java.util.List;

/**
 * A parsed script: the original typed line plus the statements it resolves to.
 *
 * Two backings (docs/SCRIPTING_DESIGN.md §2.5):
 * - list: everything already evaluated (single plain statements, /unroll dry
 *   runs, tick scripts, tests) — {@link #statements()} is available.
 * - lazy: a {@link StatementSource} that evaluates each statement the moment
 *   the executor pulls it, which is what makes {@code #wait} meaningful —
 *   state reads after a wait see the world as it is then, not as it was at
 *   Enter-press.
 */
public final class Script {

    public enum HistoryMode {
        NORMAL,
        SKIP,
        FORCE
    }

    public enum Kind {
        COMMAND, // sent as a command packet
        CHAT,    // sent as a chat packet
        WAIT,    // pause this script for waitTicks client ticks
        NOTICE   // local info line (e.g. a #set notice), shown when executed
    }

    /**
     * One outgoing statement. Content never includes the leading slash.
     *
     * @param silent true when this statement is inside a #silent scope —
     *               server feedback around its send is suppressed locally
     */
    public record SendStatement(String content, Kind kind, boolean silent, int waitTicks) {

        public SendStatement(String content, Kind kind) {
            this(content, kind, false, 0);
        }

        public SendStatement(String content, Kind kind, boolean silent) {
            this(content, kind, silent, 0);
        }

        public static SendStatement waitFor(int ticks) {
            return new SendStatement("", Kind.WAIT, false, ticks);
        }

        public static SendStatement notice(String text) {
            return new SendStatement(text, Kind.NOTICE, false, 0);
        }

        public boolean isCommand() {
            return kind == Kind.COMMAND;
        }
    }

    /** Pull-driven statement stream; lazy scripts evaluate inside next(). */
    public interface StatementSource {
        /**
         * @return the next statement, or null when the script is finished
         * @throws RuntimeError when evaluation fails mid-script
         */
        SendStatement next();

        /**
         * Cheap completion hint the executor may check without pulling (a
         * pull evaluates, so it isn't free for lazy scripts). List-backed
         * sources know immediately; lazy ones only after next() saw the end.
         */
        default boolean drained() {
            return false;
        }

        /** Stop the script and release its resources (idempotent). */
        default void close() {
        }
    }

    /** A lazy script failed while evaluating — sends made so far stay sent. */
    public static final class RuntimeError extends RuntimeException {
        public RuntimeError(String message) {
            super(message);
        }
    }

    private final String originalLine;
    private final HistoryMode history;
    private final List<SendStatement> statements; // null when lazy
    private final StatementSource lazySource;     // null when list-backed

    private Script(String originalLine, List<SendStatement> statements, StatementSource lazySource, HistoryMode history) {
        this.originalLine = originalLine;
        this.statements = statements;
        this.lazySource = lazySource;
        this.history = history;
    }

    public static Script ofStatements(String originalLine, List<SendStatement> statements, HistoryMode history) {
        return new Script(originalLine, statements, null, history);
    }

    public static Script lazy(String originalLine, StatementSource source, HistoryMode history) {
        return new Script(originalLine, null, source, history);
    }

    public String originalLine() {
        return originalLine;
    }

    public HistoryMode history() {
        return history;
    }

    public boolean isLazy() {
        return lazySource != null;
    }

    /** The pre-evaluated statement list; only list-backed scripts have one. */
    public List<SendStatement> statements() {
        if (statements == null) {
            throw new IllegalStateException("Lazy scripts have no statement list — pull from source()");
        }
        return statements;
    }

    /** The executor's pull handle. List-backed scripts get a fresh iterator per call. */
    public StatementSource source() {
        if (lazySource != null) {
            return lazySource;
        }
        Iterator<SendStatement> iterator = statements.iterator();
        return new StatementSource() {
            @Override
            public SendStatement next() {
                return iterator.hasNext() ? iterator.next() : null;
            }

            @Override
            public boolean drained() {
                return !iterator.hasNext();
            }
        };
    }
}
