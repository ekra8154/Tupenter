package net.tupenter.script;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Turns a typed line into a {@link Script}.
 *
 * Pipeline (docs/SCRIPTING_DESIGN.md §2-§6): the #silent prefix, then a
 * directive-aware statement scan (structural parens exist only after
 * #repeat/#if/#foreach/#for headers — parens anywhere else are literal text),
 * then an eager unroll walk: aliases expand (capped, with typed parameter
 * binding), loops unroll under the iteration cap, conditions evaluate, #set
 * writes a script-scope overlay, and $...$ markers are evaluated per emitted
 * command. The output is a flat list of sends.
 *
 * Evaluation timing (§2.5): with lazy execution (the default), any
 * non-trivial line walks lazily — the same Walker runs as a coroutine
 * (LazyWalk) and each statement's markers evaluate when the executor pulls
 * it, which is what makes #wait meaningful. Trivial lines, /unroll dry
 * runs, tick scripts, and tests keep the eager collect-everything walk.
 *
 * Two entry points, matching the two ways a line leaves the client:
 * - {@link #parse}: command packets ("/..." lines, slash already stripped)
 * - {@link #parseChatLine}: chat packets — only inspected when they start
 *   with a known directive word; ordinary chat like "#1 victory" passes
 *   through untouched.
 */
public final class ScriptParser {
    public static final int MAX_ALIAS_EXPANSIONS = 50;
    // must exceed MAX_ALIAS_EXPANSIONS so a recursive alias reports the
    // alias-loop error rather than the generic nesting error
    private static final int MAX_NESTING_DEPTH = 64;

    private static final String SILENT = "#silent";
    private static final String NORECORD = "#norecord";
    private static final String RECORD = "#record";
    /** #chat (#c): a no-op chat prefix — evaluate the line's $...$ and send it as a normal, recorded chat message. */
    private static final String CHAT = "#chat";
    /** Prefix shorthands, expanded to canonical form before parsing. #st/#ust (stage/unstage) are handled client-side. */
    private static final Map<String, String> PREFIX_SHORTHANDS = Map.of(
            "#s", SILENT, "#nr", NORECORD, "#r", RECORD, "#c", CHAT);
    private static final String SET = "#set";
    private static final String SETDEFAULT = "#setdefault";
    private static final String LOCAL = "#local";
    private static final String WAIT = "#wait";
    /** Longest single #wait; loops are bounded separately by the iteration cap. */
    public static final int MAX_WAIT_TICKS = 72000; // one real-time hour
    /** Directives handled by the statement scanner (own a (...) group). */
    private static final Set<String> SCANNER_DIRECTIVES = Set.of("#repeat", "#if", "#while", "#foreach", "#for", "#silent");
    private static final Set<String> STATEMENT_DIRECTIVES = Set.of(SET, SETDEFAULT, LOCAL, WAIT);
    /** #return: sets a function's value and unwinds — only valid inside a function body. */
    private static final String RETURN = "#return";
    /** Keywords whose presence at the head of a top-level segment marks a function body as a STATEMENT body. */
    private static final Set<String> FUNCTION_STATEMENT_KEYWORDS = Set.of(
            SET, LOCAL, SETDEFAULT, "#for", "#foreach", "#while", "#if", RETURN);
    /**
     * #set/#local can't shadow builtin vocabulary. Derived from the
     * BuiltinFunctions registry (this was a third hand-copied function list,
     * already missing component/vec/slot/entity and friends) plus the boolean
     * literals — the same reservation customfunction names live under.
     */
    private static final Set<String> RESERVED_VARIABLE_NAMES = buildReservedNames();

    private static Set<String> buildReservedNames() {
        Set<String> names = new java.util.HashSet<>(BuiltinFunctions.NAMES);
        names.add("true");
        names.add("false");
        return Set.copyOf(names);
    }

    private ScriptParser() {
    }

    public record Options(
            boolean chainingEnabled,
            NumberMathMode mathMode,
            Map<String, AliasDefinition> aliases,
            boolean silentDirectiveEnabled,
            boolean variablesEnabled,
            boolean loopsEnabled,
            boolean conditionalsEnabled,
            int maxLoopIterations,
            int maxCommandsPerScript,
            Random random,
            VariableProvider variables,
            SessionVariableStore sessionVariables,
            TagResolver tags,
            BlockReader blocks,
            FunctionResolver functions,
            Raycaster raycaster,
            EntityAccess entities,
            boolean lazyExecution
    ) {
        /** Convenience without world lookups (tests, contexts with no live world). */
        public Options(boolean chainingEnabled, NumberMathMode mathMode, Map<String, AliasDefinition> aliases,
                       boolean silentDirectiveEnabled, boolean variablesEnabled, boolean loopsEnabled,
                       boolean conditionalsEnabled, int maxLoopIterations, int maxCommandsPerScript,
                       Random random, VariableProvider variables, SessionVariableStore sessionVariables) {
            this(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled, loopsEnabled,
                    conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables, sessionVariables,
                    TagResolver.NONE, BlockReader.NONE, FunctionResolver.NONE, Raycaster.NONE, EntityAccess.NONE, false);
        }

        /** Convenience with tag lookup but no block reader and eager execution. */
        public Options(boolean chainingEnabled, NumberMathMode mathMode, Map<String, AliasDefinition> aliases,
                       boolean silentDirectiveEnabled, boolean variablesEnabled, boolean loopsEnabled,
                       boolean conditionalsEnabled, int maxLoopIterations, int maxCommandsPerScript,
                       Random random, VariableProvider variables, SessionVariableStore sessionVariables,
                       TagResolver tags) {
            this(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled, loopsEnabled,
                    conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables, sessionVariables,
                    tags, BlockReader.NONE, FunctionResolver.NONE, Raycaster.NONE, EntityAccess.NONE, false);
        }

        /** Full world wiring but Raycaster.NONE/EntityAccess.NONE — keeps existing 15-arg call sites working. */
        public Options(boolean chainingEnabled, NumberMathMode mathMode, Map<String, AliasDefinition> aliases,
                       boolean silentDirectiveEnabled, boolean variablesEnabled, boolean loopsEnabled,
                       boolean conditionalsEnabled, int maxLoopIterations, int maxCommandsPerScript,
                       Random random, VariableProvider variables, SessionVariableStore sessionVariables,
                       TagResolver tags, BlockReader blocks, FunctionResolver functions, boolean lazyExecution) {
            this(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled, loopsEnabled,
                    conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables, sessionVariables,
                    tags, blocks, functions, Raycaster.NONE, EntityAccess.NONE, lazyExecution);
        }

        public Options withLazyExecution(boolean lazy) {
            return new Options(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled,
                    loopsEnabled, conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables,
                    sessionVariables, tags, blocks, functions, raycaster, entities, lazy);
        }

        public Options withSessionVariables(SessionVariableStore store) {
            return new Options(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled,
                    loopsEnabled, conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables,
                    store, tags, blocks, functions, raycaster, entities, lazyExecution);
        }

        public Options withFunctions(FunctionResolver resolver) {
            return new Options(chainingEnabled, mathMode, aliases, silentDirectiveEnabled, variablesEnabled,
                    loopsEnabled, conditionalsEnabled, maxLoopIterations, maxCommandsPerScript, random, variables,
                    sessionVariables, tags, blocks, resolver, raycaster, entities, lazyExecution);
        }
    }

    /** Parses a command-packet line (leading slash already stripped by vanilla). */
    public static ParseResult parse(String command, Options options) {
        LinePrefixes prefixes;
        try {
            prefixes = stripLinePrefixes(command, options);
        } catch (ParseAbort abort) {
            return ParseResult.error(abort.getMessage());
        }

        String work = prefixes.rest();
        if (work.isEmpty()) {
            return ParseResult.error("That prefix needs a command to run, e.g. #silent /time set day");
        }

        if (work.startsWith("#")) {
            String word = firstWord(work).toLowerCase(Locale.ROOT);
            if (!isKnownStatementWord(word)) {
                return ParseResult.error("Unknown directive " + firstWord(work));
            }
        } else if (!work.startsWith("/") && !prefixes.chat()) {
            work = "/" + work; // #chat keeps it chat; otherwise a bare command word gets its slash
        }

        return parseSequence(work, command, prefixes.silent(), prefixes.history(), options, prefixes.chat());
    }

    /**
     * Parses a chat-packet line. Returns an unchanged result (let the chat
     * through) unless the line starts with a known directive word.
     */
    public static ParseResult parseChatLine(String message, Options options) {
        String trimmed = message.trim();
        if (!trimmed.startsWith("#")) {
            return ParseResult.unchanged(message);
        }
        if (!isKnownStatementWord(firstWord(trimmed).toLowerCase(Locale.ROOT))) {
            return ParseResult.unchanged(message); // "#1 victory" stays chat
        }

        LinePrefixes prefixes;
        try {
            prefixes = stripLinePrefixes(trimmed, options);
        } catch (ParseAbort abort) {
            return ParseResult.error(abort.getMessage());
        }

        String work = prefixes.rest();
        if (work.isEmpty()) {
            return ParseResult.error("That prefix needs a command to run, e.g. #silent /time set day");
        }
        if (work.startsWith("#") && !isKnownStatementWord(firstWord(work).toLowerCase(Locale.ROOT))) {
            return ParseResult.error("Unknown directive " + firstWord(work));
        }

        return parseSequence(work, message, prefixes.silent(), prefixes.history(), options, prefixes.chat());
    }

    /**
     * Parses script-generated text (e.g. the string a /$...$ line evaluated
     * to) with alias-body semantics: each statement picks its own form —
     * "/..." command, "#..." directive, bare text chat. Unlike parse(),
     * chained bare segments are NOT forced to commands, and the result is
     * always a runnable script. {@code originalLine} is what history records.
     */
    public static ParseResult parseGeneratedLine(String line, String originalLine, Options options) {
        LinePrefixes prefixes;
        try {
            prefixes = stripLinePrefixes(line, options);
        } catch (ParseAbort abort) {
            return ParseResult.error(abort.getMessage());
        }

        String work = prefixes.rest();
        if (work.isEmpty()) {
            return ParseResult.error("The expression evaluated to nothing runnable");
        }
        if (work.startsWith("#") && !isKnownStatementWord(firstWord(work).toLowerCase(Locale.ROOT))) {
            return ParseResult.error("Unknown directive " + firstWord(work));
        }

        return parseSequence(work, originalLine, prefixes.silent(), prefixes.history(), options, true);
    }

    /** True when a chat line starts with a word Tupenter treats as a directive ("#silent /...", "#repeat ..."). */
    public static boolean isDirectiveLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("#") && isKnownStatementWord(firstWord(trimmed).toLowerCase(Locale.ROOT));
    }

    /**
     * Every canonical directive word the PARSER recognizes (leading # included).
     * Public so the parser's vocabulary is enumerable — DirectiveDocs documents
     * against exactly this set, and DirectiveDocsTest holds the two together.
     * (#stage/#unstage/#pid are client-side directives and deliberately absent;
     * #elseif/#else are continuations of #if, not statement heads.)
     */
    public static java.util.Set<String> knownDirectiveWords() {
        java.util.Set<String> words = new java.util.TreeSet<>();
        words.addAll(STATEMENT_DIRECTIVES);
        words.addAll(SCANNER_DIRECTIVES);
        words.add(SILENT);
        words.add(NORECORD);
        words.add(RECORD);
        words.add(CHAT);
        words.add(RETURN); // reserved everywhere so its "function-only" error can surface
        return words;
    }

    private static boolean isKnownStatementWord(String word) {
        String canon = PREFIX_SHORTHANDS.getOrDefault(word, word);
        return knownDirectiveWords().contains(canon);
    }

    /** Expands a leading run of shorthand prefix words (#s #nr #r) to canonical form; other text is untouched. */
    private static String expandShorthands(String line) {
        int lead = 0;
        while (lead < line.length() && Character.isWhitespace(line.charAt(lead))) {
            lead++;
        }
        StringBuilder out = new StringBuilder(line.substring(0, lead));
        String rest = line.substring(lead);
        while (rest.startsWith("#")) {
            String word = firstWord(rest);
            String canon = PREFIX_SHORTHANDS.get(word.toLowerCase(Locale.ROOT));
            if (canon == null) {
                break;
            }
            out.append(canon);
            rest = rest.substring(word.length());
            int ws = 0;
            while (ws < rest.length() && Character.isWhitespace(rest.charAt(ws))) {
                ws++;
            }
            out.append(rest, 0, ws);
            rest = rest.substring(ws);
        }
        return out.append(rest).toString();
    }

    private record LinePrefixes(String rest, boolean silent, Script.HistoryMode history, boolean chat) {
    }

    /**
     * Consumes leading line modifiers (#silent, #norecord, #record) in any
     * order; for #norecord/#record the last one wins. A #silent followed by
     * '(' is the group form and stays for the scanner.
     */
    private static LinePrefixes stripLinePrefixes(String text, Options options) {
        String work = expandShorthands(text.trim());
        boolean silent = false;
        boolean chat = false;
        Script.HistoryMode history = Script.HistoryMode.NORMAL;

        while (work.startsWith("#")) {
            String word = firstWord(work).toLowerCase(Locale.ROOT);
            if (word.equals(SILENT)) {
                String after = work.substring(SILENT.length()).trim();
                if (after.startsWith("(")) {
                    break; // #silent (...) — statement form, scanner's job
                }
                requireSilentEnabled(options);
                silent = true;
                work = after;
            } else if (word.equals(NORECORD)) {
                history = Script.HistoryMode.SKIP;
                work = work.substring(NORECORD.length()).trim();
            } else if (word.equals(RECORD)) {
                history = Script.HistoryMode.FORCE;
                work = work.substring(RECORD.length()).trim();
            } else if (word.equals(CHAT)) {
                chat = true; // no side effect — just evaluate and send as chat
                work = work.substring(CHAT.length()).trim();
            } else {
                break;
            }
        }
        return new LinePrefixes(work, silent, history, chat);
    }

    private static void requireSilentEnabled(Options options) {
        if (!options.silentDirectiveEnabled()) {
            throw new ParseAbort("#silent is disabled in the Tupenter config (Scripting tab).");
        }
    }

    private static ParseResult parseSequence(String input, String originalLine, boolean silent, Script.HistoryMode history, Options options) {
        return parseSequence(input, originalLine, silent, history, options, false);
    }

    private static ParseResult parseSequence(String input, String originalLine, boolean silent, Script.HistoryMode history, Options options, boolean alwaysScript) {
        // Lazy path (§2.5): anything non-trivial defers evaluation to run
        // time — each statement's markers evaluate when the executor pulls
        // it, which is what gives #wait meaning. Trivial lines (one plain
        // statement, no markers/aliases) keep the eager path below so they
        // behave byte-identically to before.
        if (options.lazyExecution() && (silent || history != Script.HistoryMode.NORMAL || alwaysScript
                || needsLazyWalk(input, options))) {
            return ParseResult.script(
                    Script.lazy(originalLine, new LazyWalk(input, silent, options), history), List.of());
        }

        Walker walker = new Walker(options);
        walker.silentDepth = silent ? 1 : 0;
        walker.history = history;

        try {
            walker.processStatements(input);
        } catch (ParseAbort abort) {
            return ParseResult.error(abort.getMessage());
        }

        if (silent || walker.history != Script.HistoryMode.NORMAL || alwaysScript) {
            walker.changed = true; // these lines always need the executor path
        }

        if (!walker.changed) {
            return ParseResult.unchanged(originalLine);
        }

        // whole line parsed cleanly — commit #set writes to the session
        // (#local names stay script-only)
        walker.commitSession();

        return ParseResult.script(Script.ofStatements(originalLine, walker.sends, walker.history), walker.notices);
    }

    /**
     * Does this line need run-time (lazy) evaluation? True for chains,
     * directives, markers, and alias invocations. A scan failure returns
     * false so the eager walker produces its usual error message.
     */
    private static boolean needsLazyWalk(String input, Options options) {
        List<Stmt> statements;
        try {
            statements = scanStatements(input, options.chainingEnabled());
        } catch (ParseAbort abort) {
            return false;
        }
        if (statements.size() > 1) {
            return true;
        }
        if (statements.isEmpty() || statements.get(0) instanceof DirectiveStmt) {
            return true;
        }
        String text = ((RawStmt) statements.get(0)).text().trim();
        if (text.startsWith("#")) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                return true;
            }
        }
        return isAlias(text, options.aliases());
    }

    /**
     * The lazy coroutine: the ordinary Walker runs on a virtual thread, but
     * a strict request/response rendezvous means it only ever computes the
     * statement being pulled — no lookahead, so evaluation happens exactly
     * at send time. Interrupting the thread (close/abort) unwinds it.
     */
    private static final class LazyWalk implements Script.StatementSource {
        private static final Object TOKEN = new Object();
        private static final Object END = new Object();

        private record ErrorBox(String message) {
        }

        private static final class WalkerInterrupted extends RuntimeException {
        }

        private final String input;
        private final boolean silent;
        private final Options options;
        private final java.util.concurrent.SynchronousQueue<Object> requests = new java.util.concurrent.SynchronousQueue<>();
        private final java.util.concurrent.SynchronousQueue<Object> results = new java.util.concurrent.SynchronousQueue<>();
        private Thread thread;
        private boolean finished;

        private LazyWalk(String input, boolean silent, Options options) {
            this.input = input;
            this.silent = silent;
            this.options = options;
        }

        @Override
        public Script.SendStatement next() {
            if (finished) {
                return null;
            }
            if (thread == null) {
                start();
            }
            try {
                requests.put(TOKEN);
                Object item = results.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                if (item == null) {
                    close();
                    throw new Script.RuntimeError("script evaluation timed out");
                }
                if (item == END) {
                    finished = true;
                    return null;
                }
                if (item instanceof ErrorBox error) {
                    finished = true;
                    return abortWith(error.message());
                }
                return (Script.SendStatement) item;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                close();
                throw new Script.RuntimeError("script interrupted");
            }
        }

        private Script.SendStatement abortWith(String message) {
            throw new Script.RuntimeError(message);
        }

        @Override
        public boolean drained() {
            return finished;
        }

        @Override
        public void close() {
            finished = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void start() {
            thread = Thread.ofVirtual().name("tupenter-script").start(() -> {
                try {
                    requests.take(); // nothing evaluates before the first pull
                    Walker walker = new Walker(options, this::deliver);
                    walker.silentDepth = silent ? 1 : 0;
                    walker.processStatements(input);
                    walker.commitSession();
                    results.put(END);
                } catch (ParseAbort abort) {
                    quietPut(new ErrorBox(abort.getMessage()));
                } catch (InterruptedException | WalkerInterrupted stopped) {
                    // closed/aborted — just exit
                } catch (RuntimeException unexpected) {
                    quietPut(new ErrorBox("script failed: " + unexpected.getMessage()));
                }
            });
        }

        private void quietPut(Object item) {
            try {
                results.put(item);
            } catch (InterruptedException ignored) {
            }
        }

        /** Walker-side handoff: deliver one statement, then block until the next pull. */
        private void deliver(Script.SendStatement statement) {
            try {
                results.put(statement);
                requests.take();
            } catch (InterruptedException ex) {
                throw new WalkerInterrupted();
            }
        }
    }

    // =====================================================================
    // User-function bodies (statement mode)
    // =====================================================================

    /**
     * True when a custom-function body is a STATEMENT body (loops, locals,
     * #return, ...) rather than a single expression. Detection: split the body
     * into top-level segments (same &&-aware scan the Walker uses) and check
     * whether any segment leads with a statement keyword. A tag literal like
     * {@code #minecraft:wool} is not a keyword, so it stays an expression. A
     * scan failure falls back to expression mode — the evaluator will report it.
     */
    static boolean isStatementBody(String body) {
        List<Stmt> statements;
        try {
            statements = scanStatements(body, true);
        } catch (ParseAbort abort) {
            return false;
        }
        for (Stmt statement : statements) {
            String head = statement instanceof DirectiveStmt directive
                    ? directive.word()
                    : firstWord(((RawStmt) statement).text().trim()).toLowerCase(Locale.ROOT);
            if (FUNCTION_STATEMENT_KEYWORDS.contains(head)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a statement-body function through the Walker in function mode and
     * returns its computed value. Function bodies never send: bare segments are
     * evaluated as expressions (last one is the tentative result), #set/#local
     * write only to the call's local scope, and #return unwinds immediately. A
     * {@link ParseAbort} inside becomes an {@link ExpressionException} so the
     * failure surfaces to the caller like any other expression error.
     */
    static Value evaluateFunctionBody(String body, Options functionOptions) {
        Walker walker = new Walker(functionOptions);
        walker.functionMode = true;
        Value result;
        try {
            walker.processStatements(body);
            result = walker.functionResult;
        } catch (ReturnSignal signal) {
            return signal.value();
        } catch (ParseAbort abort) {
            throw new ExpressionException(abort.getMessage());
        }
        if (result == null) {
            throw new ExpressionException(
                    "this function produced no value — end its body with an expression or a #return");
        }
        return result;
    }

    /** Control-flow unwind for #return — carries the value out of any enclosing loops/ifs. */
    private static final class ReturnSignal extends RuntimeException {
        private final transient Value value;

        private ReturnSignal(Value value) {
            super(null, null, false, false); // control flow, not an error — no message/stacktrace
            this.value = value;
        }

        private Value value() {
            return value;
        }
    }

    // =====================================================================
    // The unroll walker
    // =====================================================================

    private static final class Walker {
        private final Options options;
        private final List<Script.SendStatement> sends = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        /** Non-null in lazy mode: statements (and inline notices) stream here instead of the lists. */
        private final java.util.function.Consumer<Script.SendStatement> sink;
        private int sendCount;
        private final Map<String, Value> scriptScope = new LinkedHashMap<>();
        private final Set<String> localNames = new HashSet<>();
        private final Deque<Map<String, Value>> scopes = new ArrayDeque<>();
        private final EvalContext context;
        private final VariableProvider lookup; // scopes + scriptScope + session/persistent/live — for #setdefault's absence check
        private boolean changed;
        /** Function mode: bodies compute a value (no sends); #set is local, #return/#wait behave differently. */
        private boolean functionMode;
        /** The tentative return value in function mode — the last bare-expression segment wins. */
        private Value functionResult;
        private int silentDepth;
        private Script.HistoryMode history = Script.HistoryMode.NORMAL;
        private int aliasExpansions;
        private int depth;

        private Walker(Options options) {
            this(options, null);
        }

        private Walker(Options options, java.util.function.Consumer<Script.SendStatement> sink) {
            this.sink = sink;
            this.options = options;
            this.lookup = new VariableProvider() {
                @Override
                public Set<String> names() {
                    Set<String> names = new HashSet<>(options.variables().names());
                    names.addAll(scriptScope.keySet());
                    for (Map<String, Value> scope : scopes) {
                        names.addAll(scope.keySet());
                    }
                    return names;
                }

                @Override
                public Optional<Value> resolve(String name) {
                    String key = name.toLowerCase(Locale.ROOT);
                    for (Map<String, Value> scope : scopes) { // innermost first
                        Value value = scope.get(key);
                        if (value != null) {
                            return Optional.of(value);
                        }
                    }
                    Value fromSet = scriptScope.get(key);
                    if (fromSet != null) {
                        return Optional.of(fromSet);
                    }
                    return options.variables().resolve(name);
                }
            };
            this.context = new EvalContext(options.random(), lookup, options.tags(), options.blocks(), options.functions(), options.raycaster(), options.entities());
        }

        private void processStatements(String text) {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new ParseAbort("Scripts can't nest deeper than " + MAX_NESTING_DEPTH + " levels");
            }
            try {
                List<Stmt> statements = scanStatements(text, options.chainingEnabled());
                if (statements.size() > 1) {
                    changed = true;
                }
                for (Stmt statement : statements) {
                    if (statement instanceof DirectiveStmt directive) {
                        processDirective(directive);
                    } else {
                        processRaw(((RawStmt) statement).text());
                    }
                }
            } finally {
                depth--;
            }
        }

        private void processRaw(String text) {
            Script.SendStatement normalized = normalizeSegment(text);
            if (normalized == null) {
                return; // empty segment (e.g. "a && && b") — skip it
            }
            String content = normalized.content();

            if (functionMode) {
                processFunctionSegment(content, normalized.isCommand());
                return;
            }

            if (content.startsWith("#")) {
                String word = firstWord(content).toLowerCase(Locale.ROOT);
                if (word.equals(SET)) {
                    handleSet(content, SET, true, false);
                    return;
                }
                if (word.equals(SETDEFAULT)) {
                    handleSet(content, SETDEFAULT, true, true); // set only if the name isn't defined yet
                    return;
                }
                if (word.equals(LOCAL)) {
                    handleSet(content, LOCAL, false, false);
                    return;
                }
                if (word.equals(WAIT)) {
                    handleWait(content);
                    return;
                }
                if (word.equals(RETURN)) {
                    throw new ParseAbort("#return only works inside a custom function body");
                }
                if (word.equals(SILENT) || word.equals(NORECORD)) {
                    throw new ParseAbort(word + " goes at the start of the line" + (word.equals(SILENT) ? ", or wrap statements: #silent (/cmd && /cmd)" : ""));
                }
                throw new ParseAbort("Unknown directive " + firstWord(content));
            }

            boolean isCommand = normalized.isCommand();

            // a chat statement that is exactly one $...$ marker re-dispatches
            // its string result by statement form, like /$expr$ does for the
            // whole line — $cmd$ holding "/tp ~ ~1 ~" runs as a command
            if (!isCommand && options.mathMode() != NumberMathMode.DISABLED) {
                String inner = wholeMarkerInner(content);
                if (inner != null) {
                    Value value;
                    try {
                        value = ExpressionEvaluator.evaluate(inner, context);
                    } catch (ExpressionException ex) {
                        throw new ParseAbort(ex.getMessage());
                    }
                    changed = true;
                    if (value instanceof Value.StringValue string) {
                        processStatements(string.value());
                        return;
                    }
                    try {
                        emitSend(value.substitutionString(), false);
                    } catch (ExpressionException ex) {
                        throw new ParseAbort(ex.getMessage());
                    }
                    return;
                }
            }

            if (isAlias(content, options.aliases())) {
                processAliasInvocation(content);
            } else {
                emitSend(content, isCommand);
            }
        }

        /**
         * A top-level segment of a function body. Directives write function-local
         * state or unwind; a bare segment is an expression whose value becomes the
         * tentative result (last wins). Nothing is ever sent — a command is an error.
         */
        private void processFunctionSegment(String content, boolean isCommand) {
            if (content.startsWith("#")) {
                String word = firstWord(content).toLowerCase(Locale.ROOT);
                switch (word) {
                    // #set behaves like #local here: function-local, never committed, no "$x$ =" notice
                    case SET, LOCAL -> handleSet(content, word.equals(SET) ? SET : LOCAL, false, false);
                    case SETDEFAULT -> handleSet(content, SETDEFAULT, false, true);
                    case RETURN -> {
                        String expr = content.substring(RETURN.length()).trim();
                        if (expr.isEmpty()) {
                            throw new ParseAbort("#return needs a value, e.g. #return vec(x, y, z)");
                        }
                        throw new ReturnSignal(evalFunctionExpression(expr));
                    }
                    case WAIT -> throw new ParseAbort("a function can't #wait — it computes a value synchronously");
                    case SILENT, NORECORD, RECORD, "#stage", "#unstage", "#chat" ->
                            throw new ParseAbort(word + " isn't meaningful in a function — its body computes a value");
                    default -> throw new ParseAbort("Unknown directive " + firstWord(content));
                }
                return;
            }
            if (isCommand) {
                throw new ParseAbort("a function can't run commands — its body computes a value");
            }
            functionResult = evalFunctionExpression(content);
        }

        private Value evalFunctionExpression(String expression) {
            try {
                return ExpressionEvaluator.evaluate(expression, context);
            } catch (ExpressionException ex) {
                throw new ParseAbort(ex.getMessage());
            }
        }

        private void emitSend(String content, boolean isCommand) {
            String rewritten = content;
            if (options.mathMode() != NumberMathMode.DISABLED) {
                try {
                    // chat evaluates explicit $...$ only — never auto-detect math
                    rewritten = MathEvaluator.applyNumberMath(content,
                            isCommand ? options.mathMode() : NumberMathMode.EXPLICIT_ONLY, context);
                } catch (ExpressionException ex) {
                    throw new ParseAbort(ex.getMessage());
                }
            }
            if (!rewritten.equals(content)) {
                changed = true;
            }
            boolean silent = silentDepth > 0;
            if (silent) {
                changed = true;
            }
            sendCount++;
            // Eager scripts collect every send up front, so cap the total. Lazy
            // scripts pace themselves across ticks (Max Commands Per Tick) and
            // yield each send, so a big /randomfill runs as long as it needs —
            // same reasoning as the relaxed loop cap and the submit() exemption.
            if (sink == null && sendCount > options.maxCommandsPerScript()) {
                throw new ParseAbort("Script would send more than " + options.maxCommandsPerScript()
                        + " commands (Max Commands Per Script in the config)");
            }
            emit(new Script.SendStatement(rewritten, isCommand ? Script.Kind.COMMAND : Script.Kind.CHAT, silent));
        }

        /** Every statement leaves the walker through here: sink when lazy, list when eager. */
        private long emitCount; // statements handed off so far — a proxy for "did the loop yield"

        private void emit(Script.SendStatement statement) {
            emitCount++;
            if (sink != null) {
                sink.accept(statement);
            } else {
                sends.add(statement);
            }
        }

        private void notice(String text) {
            if (sink != null) {
                sink.accept(Script.SendStatement.notice(text));
            } else {
                notices.add(text);
            }
        }

        /** Commits #set writes to the session store (#local names stay script-only). */
        private void commitSession() {
            if (options.sessionVariables() != null) {
                scriptScope.forEach((name, value) -> {
                    if (!localNames.contains(name)) {
                        options.sessionVariables().set(name, value);
                    }
                });
            }
        }

        /**
         * #wait 10t / 1.5s / 3d (or bare ticks) — pauses the script. Only
         * meaningful with lazy execution (later statements then evaluate
         * after the wait); with eager execution the delay still happens but
         * markers were already evaluated at Enter-press.
         */
        private void handleWait(String content) {
            String header = content.substring(WAIT.length()).trim();
            if (header.isEmpty()) {
                throw new ParseAbort("#wait needs a duration, e.g. #wait 10t / 0.5s / 2m (ticks/seconds/minutes/days)");
            }
            if (options.mathMode() != NumberMathMode.DISABLED) {
                try {
                    header = MathEvaluator.applyNumberMath(header, NumberMathMode.EXPLICIT_ONLY, context);
                } catch (ExpressionException ex) {
                    throw new ParseAbort("#wait: " + ex.getMessage());
                }
            }
            // optional trailing mode: realtime (wall-clock) or gametime (client ticks, default)
            boolean realtime = false;
            String durationText = header.trim();
            String[] parts = durationText.split("\\s+");
            if (parts.length == 2) {
                String mode = parts[1].toLowerCase(Locale.ROOT);
                if (mode.equals("realtime") || mode.equals("real")) {
                    realtime = true;
                } else if (!mode.equals("gametime") && !mode.equals("game")) {
                    throw new ParseAbort("#wait mode must be 'realtime' or 'gametime', got '" + parts[1] + "'");
                }
                durationText = parts[0];
            } else if (parts.length > 2) {
                throw new ParseAbort("#wait takes a duration and optional mode, e.g. #wait 5m realtime");
            }
            long ticks = parseWaitTicks(durationText);
            if (ticks > MAX_WAIT_TICKS) {
                throw new ParseAbort("#wait is capped at " + MAX_WAIT_TICKS + " ticks (one hour)");
            }
            changed = true;
            if (ticks > 0) {
                emit(Script.SendStatement.waitFor((int) ticks, realtime));
            }
        }

        private static long parseWaitTicks(String token) {
            long unit = 1;
            String text = token;
            if (!text.isEmpty()) {
                char suffix = Character.toLowerCase(text.charAt(text.length() - 1));
                if (suffix == 't' || suffix == 's' || suffix == 'm' || suffix == 'd') {
                    unit = switch (suffix) {
                        case 't' -> 1;
                        case 's' -> 20;
                        case 'm' -> 1200;  // 60 seconds
                        default -> 24000;  // 'd' — one MC day
                    };
                    text = text.substring(0, text.length() - 1);
                }
            }
            Rational amount;
            try {
                amount = Rational.parse(text);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                throw new ParseAbort("#wait needs a duration like 10t, 1.5s, 2m, or 3d — got '" + token + "'");
            }
            if (amount.signum() < 0) {
                throw new ParseAbort("#wait can't be negative");
            }
            try {
                return amount.multiply(Rational.of(unit)).round().wholeValue().longValueExact();
            } catch (ArithmeticException ex) {
                throw new ParseAbort("#wait duration is too large");
            }
        }

        // --- #set / #local ---

        private void handleSet(String content, String directive, boolean commitToSession, boolean onlyIfAbsent) {
            if (!options.variablesEnabled()) {
                throw new ParseAbort("Variables (" + directive + ") are disabled in the Tupenter config (Scripting tab).");
            }
            SetVar setVar = parseSetDirective(content, directive);

            for (Map<String, Value> scope : scopes) {
                if (scope.containsKey(setVar.name())) {
                    throw new ParseAbort("$" + setVar.name() + "$ is a loop variable or parameter here — it is read-only");
                }
            }

            // #setdefault: leave an already-defined name (this script's #sets, the
            // session store, a saved persistent var, or a live read) untouched —
            // idempotent init that survives across runs AND sessions.
            if (onlyIfAbsent && isAlreadyDefined(setVar.name())) {
                changed = true;
                return;
            }

            Value value;
            try {
                value = ExpressionEvaluator.evaluate(setVar.expression(), context);
            } catch (IllegalArgumentException ex) {
                throw new ParseAbort(directive + " $" + setVar.name() + "$ — " + ex.getMessage());
            }
            scriptScope.put(setVar.name(), value);
            if (commitToSession) {
                localNames.remove(setVar.name()); // an explicit #set/#setdefault wins over an earlier #local
                if (silentDepth == 0) {
                    notice("$" + setVar.name() + "$ = " + value.displayString());
                }
            } else {
                localNames.add(setVar.name());
            }
            changed = true;
        }

        /** True if the name already has a value anywhere — this script's #sets, the session
         * store, a saved persistent var, or a live read. A live var that errors when absent
         * (e.g. client.target.blockpos with no target) counts as unset. */
        private boolean isAlreadyDefined(String name) {
            try {
                return lookup.resolve(name).isPresent();
            } catch (RuntimeException absentLiveRead) {
                return false;
            }
        }

        // --- aliases ---

        private void processAliasInvocation(String content) {
            int separator = findFirstWhitespace(content);
            String name = (separator >= 0 ? content.substring(0, separator) : content).toLowerCase(Locale.ROOT);
            String remainder = separator >= 0 ? content.substring(separator).trim() : "";

            AliasDefinition definition = options.aliases().get(name);
            aliasExpansions++;
            if (aliasExpansions > MAX_ALIAS_EXPANSIONS) {
                throw new ParseAbort("Alias expansion limit reached (" + MAX_ALIAS_EXPANSIONS + "). Possible recursive alias loop.");
            }

            String body = definition.body().trim();
            Map<String, Value> bindings = new HashMap<>();

            if (definition.params().isEmpty()) {
                if (!remainder.isEmpty()) {
                    body = body + " " + remainder;
                }
            } else {
                bindParams(name, definition, remainder, bindings);
            }

            changed = true;

            // alias bodies may carry line-prefix modifiers; #silent scopes to
            // this body's statements, #norecord applies to the whole line
            int silentPushes = 0;
            while (body.startsWith("#")) {
                String word = firstWord(body).toLowerCase(Locale.ROOT);
                if (word.equals(SILENT)) {
                    String after = body.substring(SILENT.length()).trim();
                    if (after.startsWith("(")) {
                        break; // group form — scanner's job
                    }
                    requireSilentEnabled(options);
                    silentPushes++;
                    body = after;
                } else if (word.equals(NORECORD)) {
                    history = Script.HistoryMode.SKIP;
                    body = body.substring(NORECORD.length()).trim();
                } else if (word.equals(RECORD)) {
                    history = Script.HistoryMode.FORCE;
                    body = body.substring(RECORD.length()).trim();
                } else {
                    break;
                }
            }
            if (body.isEmpty()) {
                throw new ParseAbort("Custom command body is empty after its #-prefixes");
            }

            scopes.push(bindings);
            silentDepth += silentPushes;
            try {
                processStatements(body);
            } finally {
                silentDepth -= silentPushes;
                scopes.pop();
            }
        }

        private void bindParams(String aliasName, AliasDefinition definition, String remainder, Map<String, Value> bindings) {
            String usage = "Usage: /" + aliasName + " " + definition.declarationPrefix().trim();

            // arguments evaluate before they bind, so /cube $rand(1,15)$ can
            // feed an int param and outer-scope variables can pass through
            String rest;
            try {
                // asArguments: a list-valued argument flattens to space-separated
                // members, so $blockset("minecraft:stone", #wool)$ can feed a
                // <set:blockset> param (blockset() reads that back as a set)
                rest = MathEvaluator.applyNumberMath(remainder, NumberMathMode.EXPLICIT_ONLY, context, true);
            } catch (IllegalArgumentException ex) {
                throw new ParseAbort("/" + aliasName + " arguments: " + ex.getMessage());
            }

            List<AliasDefinition.Param> params = definition.params();
            for (int i = 0; i < params.size(); i++) {
                AliasDefinition.Param param = params.get(i);
                rest = rest.trim();

                if (rest.isEmpty()) {
                    if (param.optional()) {
                        bindDefault(param, i, bindings, usage);
                        continue;
                    }
                    throw new ParseAbort("Missing argument <" + param.name() + ">. " + usage);
                }

                if (!param.optional()) {
                    rest = bindOne(param, i, rest, bindings, usage);
                    continue;
                }

                // optional with input present: if the input doesn't parse as this
                // type, fall back to the default and let the next param claim it
                Map<String, Value> attempt = new HashMap<>();
                try {
                    String after = bindOne(param, i, rest, attempt, usage);
                    bindings.putAll(attempt);
                    rest = after;
                } catch (ParseAbort ex) {
                    bindDefault(param, i, bindings, usage);
                }
            }

            if (!rest.trim().isEmpty()) {
                throw new ParseAbort("Too many arguments: '" + rest.trim() + "'. " + usage);
            }
        }

        /** Binds one param from the front of {@code rest}; returns the remaining argument text. */
        private String bindOne(AliasDefinition.Param param, int index, String rest, Map<String, Value> bindings, String usage) {
            switch (param.type()) {
                // NOTE: the ~ base coordinates are resolved BY NAME through the
                // VariableProvider at runtime, so these strings must track the
                // client.pos / client.blockpos variables — a mismatch compiles
                // fine and only fails in-game as "Unknown variable".
                case POS -> {
                    return bindCoordinateParam(param, index, rest, bindings, usage,
                            new String[]{"x", "y", "z"},
                            new String[]{"client.pos.x", "client.pos.y", "client.pos.z"}, false);
                }
                case BLOCKPOS -> {
                    return bindCoordinateParam(param, index, rest, bindings, usage,
                            new String[]{"x", "y", "z"},
                            new String[]{"client.blockpos.x", "client.blockpos.y", "client.blockpos.z"}, true);
                }
                case COLUMN_POS -> {
                    return bindCoordinateParam(param, index, rest, bindings, usage,
                            new String[]{"x", "z"}, new String[]{"client.blockpos.x", "client.blockpos.z"}, true);
                }
                case ROTATION -> {
                    return bindCoordinateParam(param, index, rest, bindings, usage,
                            new String[]{"yaw", "pitch"}, new String[]{"client.yaw", "client.pitch"}, false);
                }
                case ANGLE -> {
                    // single yaw; binds as a number so $a + 90$ works
                    ArgToken angleToken = readArgToken(rest);
                    Value angle = new Value.NumberValue(resolveCoordinate(param, angleToken.value(), "client.yaw", false));
                    bindings.put(param.name(), angle);
                    bindings.put(String.valueOf(index + 1), angle);
                    return rest.substring(angleToken.consumed());
                }
                default -> {
                    String token;
                    if (param.type() == AliasDefinition.ParamType.TEXT) {
                        token = rest;
                        rest = "";
                    } else {
                        ArgToken argToken = readArgToken(rest);
                        token = argToken.value();
                        rest = rest.substring(argToken.consumed());
                    }
                    Value value = parseParamValue(param, token, usage);
                    bindings.put(param.name(), value);
                    bindings.put(String.valueOf(index + 1), value);
                    return rest;
                }
            }
        }

        /**
         * Binds an omitted optional param: $...$ in the default is evaluated
         * first (earlier params of the same command are visible), then the
         * result is bound exactly as if the user had typed it — so a pos
         * default of "~ ~ ~" resolves to the current position.
         */
        private void bindDefault(AliasDefinition.Param param, int index, Map<String, Value> bindings, String usage) {
            String text = param.defaultValue();
            scopes.push(bindings);
            try {
                text = MathEvaluator.applyNumberMath(text, NumberMathMode.EXPLICIT_ONLY, context);
            } catch (IllegalArgumentException ex) {
                throw new ParseAbort("<" + param.name() + "> default: " + ex.getMessage());
            } finally {
                scopes.pop();
            }
            String leftover = bindOne(param, index, text.trim(), bindings, usage);
            if (!leftover.trim().isEmpty()) {
                throw new ParseAbort("<" + param.name() + "> default has extra text: '" + leftover.trim() + "'. " + usage);
            }
        }

        /**
         * A coordinate-tuple param (pos, vec3, column_pos, rotation) consumes
         * one token per component. ~ resolves against the matching client
         * variable (via the variable registry, so this stays MC-free and
         * testable); ~offset adds, and whole-number tuples floor like vanilla
         * block positions. Binds $name$ = the joined tuple plus one
         * $name.component$ number per component.
         *
         * @return the remaining argument text
         */
        private String bindCoordinateParam(AliasDefinition.Param param, int index, String rest, Map<String, Value> bindings,
                                           String usage, String[] components, String[] baseVariables, boolean whole) {
            Rational[] resolved = new Rational[components.length];

            for (int c = 0; c < components.length; c++) {
                rest = rest.trim();
                if (rest.isEmpty()) {
                    throw new ParseAbort("<" + param.name() + "> needs " + components.length + " coordinates ("
                            + String.join(" ", components) + ", ~ allowed). " + usage);
                }
                ArgToken token = readArgToken(rest);
                rest = rest.substring(token.consumed());
                resolved[c] = resolveCoordinate(param, token.value(), baseVariables[c], whole);
            }

            StringBuilder joined = new StringBuilder();
            for (int c = 0; c < components.length; c++) {
                Value component = new Value.NumberValue(resolved[c]);
                bindings.put(param.name() + "." + components[c], component);
                if (c > 0) {
                    joined.append(' ');
                }
                joined.append(component.substitutionString());
            }
            Value tuple = Value.of(joined.toString());
            bindings.put(param.name(), tuple);
            bindings.put(String.valueOf(index + 1), tuple);
            return rest;
        }

        private Rational resolveCoordinate(AliasDefinition.Param param, String token, String baseVariable, boolean whole) {
            if (token.startsWith("^")) {
                throw new ParseAbort("<" + param.name() + ">: ^ caret coordinates aren't supported yet — use ~ or absolute numbers");
            }

            if (token.startsWith("~")) {
                Rational base;
                try {
                    Value baseValue = options.variables().resolve(baseVariable)
                            .orElseThrow(() -> new ParseAbort("<" + param.name() + ">: ~ needs your position, which is only available in-game"));
                    if (!(baseValue instanceof Value.NumberValue number)) {
                        throw new ParseAbort("<" + param.name() + ">: " + baseVariable + " is not a number");
                    }
                    base = number.value();
                } catch (ExpressionException ex) {
                    throw new ParseAbort("<" + param.name() + ">: " + ex.getMessage());
                }

                Rational offset = Rational.ZERO;
                if (token.length() > 1) {
                    try {
                        offset = Rational.parse(token.substring(1));
                    } catch (IllegalArgumentException | ArithmeticException ex) {
                        throw new ParseAbort("<" + param.name() + ">: bad coordinate '" + token + "'");
                    }
                }

                Rational result = base.add(offset);
                if (!whole) {
                    return result;
                }
                try {
                    return Rational.of(result.floor().wholeValue().longValueExact());
                } catch (ArithmeticException ex) {
                    throw new ParseAbort("<" + param.name() + ">: coordinate out of range");
                }
            }

            if (whole) {
                try {
                    return Rational.of(new BigInteger(token).longValueExact());
                } catch (NumberFormatException | ArithmeticException ex) {
                    throw new ParseAbort("<" + param.name() + "> coordinates must be whole numbers or ~, got '" + token + "'");
                }
            }
            try {
                return Rational.parse(token);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                throw new ParseAbort("<" + param.name() + "> coordinates must be numbers or ~, got '" + token + "'");
            }
        }

        private record ArgToken(String value, int consumed) {
        }

        /**
         * Reads one argument token: either "quoted anything" (quotes stripped,
         * \" and \\ escapes) or a bare token — where whitespace inside [...]
         * brackets or "..." spans doesn't split, so raw selectors like
         * {@code @e[name="a b"]} stay one argument.
         */
        private static ArgToken readArgToken(String rest) {
            if (rest.startsWith("\"")) {
                StringBuilder value = new StringBuilder();
                int i = 1;
                while (i < rest.length()) {
                    char c = rest.charAt(i);
                    if (c == '\\' && i + 1 < rest.length()) {
                        value.append(rest.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        return new ArgToken(value.toString(), i + 1);
                    }
                    value.append(c);
                    i++;
                }
                throw new ParseAbort("Unclosed quote in argument: " + rest);
            }

            int depth = 0;
            boolean insideQuotes = false;
            int i = 0;
            while (i < rest.length()) {
                char c = rest.charAt(i);
                if (c == '\\' && i + 1 < rest.length()) {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    insideQuotes = !insideQuotes;
                } else if (!insideQuotes) {
                    if (c == '[' || c == '{') depth++;
                    else if (c == ']' || c == '}') depth = Math.max(0, depth - 1);
                    else if (depth == 0 && Character.isWhitespace(c)) break;
                }
                i++;
            }
            return new ArgToken(rest.substring(0, i), i);
        }

        private static Value parseParamValue(AliasDefinition.Param param, String token, String usage) {
            switch (param.type()) {
                case INT -> {
                    try {
                        return new Value.NumberValue(Rational.of(new BigInteger(token)));
                    } catch (NumberFormatException ex) {
                        throw new ParseAbort("<" + param.name() + "> must be a whole number, got '" + token + "'. " + usage);
                    }
                }
                case FLOAT -> {
                    try {
                        return Value.ofNumber(token);
                    } catch (IllegalArgumentException | ArithmeticException ex) {
                        throw new ParseAbort("<" + param.name() + "> must be a number, got '" + token + "'. " + usage);
                    }
                }
                case CHOICE -> {
                    for (String option : param.options()) {
                        if (option.equalsIgnoreCase(token)) {
                            return Value.of(option);
                        }
                    }
                    throw new ParseAbort("<" + param.name() + "> must be one of: " + String.join(", ", param.options()) + " — got '" + token + "'. " + usage);
                }
                case TIME -> {
                    return Value.ofNumber(parseTimeTicks(param, token, usage));
                }
                case COLOR -> {
                    String color = token.toLowerCase(Locale.ROOT);
                    if (!CHAT_COLORS.contains(color)) {
                        throw new ParseAbort("<" + param.name() + "> must be a chat color (" + String.join(", ", CHAT_COLORS) + "), got '" + token + "'. " + usage);
                    }
                    return Value.of(color);
                }
                case BOOL -> {
                    if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("false")) {
                        return new Value.BoolValue(token.equalsIgnoreCase("true"));
                    }
                    throw new ParseAbort("<" + param.name() + "> must be true or false, got '" + token + "'. " + usage);
                }
                case ENTITY, ID, ITEM, BLOCK, DIMENSION -> {
                    // concrete resource ids: give a bare id the implied minecraft:
                    // namespace, so a script's /launch fireball binds
                    // "minecraft:fireball" and compares equal to the tab-completed
                    // form (vanilla treats them the same). NOT the set/predicate
                    // types (itemset/blockset): those carry #tags and "any"-style
                    // sentinels, and blockset()/itemset() canonicalize concrete ids
                    // themselves. Any [state]/{nbt} suffix is preserved.
                    return Value.of(canonicalizeId(token));
                }
                default -> {
                    return Value.of(token);
                }
            }
        }

        /**
         * Adds the default {@code minecraft:} namespace to a bare resource id.
         * Leaves it alone when it already has a namespace, isn't a plain
         * lowercase id (e.g. a $marker$ that slipped through), or is empty.
         * A leading {@code #} (tag) and any {@code [state]}/{@code {nbt}} suffix
         * are kept: {@code #logs[axis=y]} -> {@code #minecraft:logs[axis=y]}.
         */
        private static String canonicalizeId(String token) {
            int cut = token.length();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c == '[' || c == '{') {
                    cut = i;
                    break;
                }
            }
            String id = token.substring(0, cut);
            String suffix = token.substring(cut);
            boolean tag = id.startsWith("#");
            String core = tag ? id.substring(1) : id;
            if (core.isEmpty() || core.indexOf(':') >= 0 || !core.matches("[a-z0-9_.\\-/]+")) {
                return token; // already namespaced, empty, or not a plain id
            }
            return (tag ? "#" : "") + "minecraft:" + core + suffix;
        }

        private static final List<String> CHAT_COLORS = List.of(
                "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

        /** Vanilla time syntax: number + optional t (ticks), s (seconds ×20), or d (days ×24000); rounds to whole ticks. */
        private static long parseTimeTicks(AliasDefinition.Param param, String token, String usage) {
            long unit = 1;
            String text = token;
            if (!text.isEmpty()) {
                char suffix = Character.toLowerCase(text.charAt(text.length() - 1));
                if (suffix == 't' || suffix == 's' || suffix == 'm' || suffix == 'd') {
                    unit = switch (suffix) {
                        case 't' -> 1;
                        case 's' -> 20;
                        case 'm' -> 1200;  // 60 seconds
                        default -> 24000;  // 'd' — one MC day
                    };
                    text = text.substring(0, text.length() - 1);
                }
            }
            Rational amount;
            try {
                amount = Rational.parse(text);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                throw new ParseAbort("<" + param.name() + "> must be a duration like 10t, 1.5s, 2m, or 3d (ticks/seconds/minutes/days), got '" + token + "'. " + usage);
            }
            if (amount.signum() < 0) {
                throw new ParseAbort("<" + param.name() + "> can't be a negative duration. " + usage);
            }
            try {
                return amount.multiply(Rational.of(unit)).round().wholeValue().longValueExact();
            } catch (ArithmeticException ex) {
                throw new ParseAbort("<" + param.name() + "> is too large. " + usage);
            }
        }

        // --- structural directives ---

        private void processDirective(DirectiveStmt directive) {
            changed = true;
            switch (directive.word()) {
                case "#repeat" -> processRepeat(directive);
                case "#if" -> processIf(directive);
                case "#while" -> processWhile(directive);
                case "#for" -> processFor(directive);
                case "#foreach" -> processForeach(directive);
                case "#silent" -> processSilentGroup(directive);
                default -> throw new ParseAbort("Unknown directive " + directive.word());
            }
        }

        private void processSilentGroup(DirectiveStmt directive) {
            requireSilentEnabled(options);
            silentDepth++;
            try {
                processStatements(directive.group());
            } finally {
                silentDepth--;
            }
        }

        private void requireLoops(String word) {
            if (!options.loopsEnabled()) {
                throw new ParseAbort("Loops (" + word + ") are disabled in the Tupenter config (Scripting tab).");
            }
        }

        private void processRepeat(DirectiveStmt directive) {
            requireLoops("#repeat");
            if (directive.header().isEmpty()) {
                throw new ParseAbort("#repeat needs a count, e.g. #repeat 5 (/say hi)");
            }
            long count = evalWholeNumber(directive.header(), "#repeat count");
            if (count < 0) {
                throw new ParseAbort("#repeat count can't be negative (got " + count + ")");
            }
            if (sink == null) {
                checkIterations(count, "#repeat"); // eager can't self-pace — bound it up front
            }
            LoopGuard guard = new LoopGuard("#repeat");
            for (long i = 1; i <= count; i++) {
                runScoped(Map.of("i", Value.ofNumber(i)), directive.group());
                guard.step();
            }
        }

        /**
         * #while (condition) (body) — re-checks the condition each iteration and
         * runs while it's true. Only in the lazy path (it spans ticks); a body
         * that sends or waits each iteration paces itself and may run
         * indefinitely, while the LoopGuard stops a body that never yields.
         */
        private void processWhile(DirectiveStmt directive) {
            requireLoops("#while");
            // A function #while runs eagerly (it computes synchronously); the LoopGuard
            // bounds it since nothing is emitted. Outside a function it spans ticks, so
            // it needs lazy execution.
            if (sink == null && !functionMode) {
                throw new ParseAbort("#while needs Lazy Execution enabled (Scripting tab) — it runs across ticks.");
            }
            if (directive.header().isEmpty()) {
                throw new ParseAbort("#while needs a (condition), e.g. #while ($client.health$ < 20) (/effect give @s regeneration 1 1 && #wait 3s)");
            }
            LoopGuard guard = new LoopGuard("#while");
            for (long i = 1; ; i++) {
                Value condition = evalCondition(directive.header(), "#while condition");
                if (!(condition instanceof Value.BoolValue bool)) {
                    throw new ParseAbort("#while condition must be true/false, e.g. #while ($client.y$ < 100) (...)");
                }
                if (!bool.value()) {
                    return;
                }
                runScoped(Map.of("i", Value.ofNumber(i)), directive.group());
                guard.step();
            }
        }

        private void processIf(DirectiveStmt directive) {
            if (!options.conditionalsEnabled()) {
                throw new ParseAbort("Conditionals (#if) are disabled in the Tupenter config (Scripting tab).");
            }
            Value condition = evalCondition(directive.header(), "#if condition");
            if (!(condition instanceof Value.BoolValue bool)) {
                throw new ParseAbort("#if condition must be true/false, e.g. #if ($client.y$ > 60) (...)");
            }
            if (bool.value()) {
                processStatements(directive.group());
            } else if (directive.elseGroup() != null) {
                processStatements(directive.elseGroup());
            }
        }

        private void processFor(DirectiveStmt directive) {
            requireLoops("#for");
            ForHeader header = parseForHeader(directive.header());

            BigInteger a = BigInteger.valueOf(evalWholeNumber(header.from(), "#for start"));
            BigInteger b = BigInteger.valueOf(evalWholeNumber(header.to(), "#for stop"));
            BigInteger step;
            if (header.step() != null) {
                step = BigInteger.valueOf(evalWholeNumber(header.step(), "#for step"));
                if (step.signum() == 0) {
                    throw new ParseAbort("#for step can't be 0");
                }
                if (a.compareTo(b) != 0 && step.signum() != b.subtract(a).signum()) {
                    throw new ParseAbort("#for step goes the wrong way (from " + a + " to " + b + " step " + step + ")");
                }
            } else {
                step = a.compareTo(b) <= 0 ? BigInteger.ONE : BigInteger.valueOf(-1);
            }

            long iterations = b.subtract(a).divide(step).longValue() + 1;
            if (sink == null) {
                checkIterations(iterations, "#for");
            }
            LoopGuard guard = new LoopGuard("#for");
            BigInteger current = a;
            while (step.signum() > 0 ? current.compareTo(b) <= 0 : current.compareTo(b) >= 0) {
                runScoped(Map.of(header.var(), new Value.NumberValue(Rational.of(current))), directive.group());
                current = current.add(step);
                guard.step();
            }
        }

        private void processForeach(DirectiveStmt directive) {
            requireLoops("#foreach");
            ForeachHeader header = parseForeachHeader(directive.header());

            List<Value> items;
            if (header.literalList() != null) {
                items = new ArrayList<>();
                for (String item : splitListItems(header.literalList())) {
                    items.add(Value.of(item));
                }
            } else {
                Value listValue = evalExpression(header.listExpression(), "#foreach list");
                if (!(listValue instanceof Value.ListValue list)) {
                    throw new ParseAbort("#foreach needs a list: (a | b | c) or range(1, 10)");
                }
                items = list.values();
            }

            if (sink == null) {
                checkIterations(items.size(), "#foreach");
            }
            LoopGuard guard = new LoopGuard("#foreach");
            for (Value item : items) {
                runScoped(Map.of(header.var(), item), directive.group());
                guard.step();
            }
        }

        private void runScoped(Map<String, Value> bindings, String body) {
            scopes.push(bindings);
            try {
                processStatements(body);
            } finally {
                scopes.pop();
            }
        }

        private void checkIterations(long count, String word) {
            if (count > options.maxLoopIterations()) {
                throw new ParseAbort(word + " would run " + count + " times — the loop limit is "
                        + options.maxLoopIterations() + " (Max Loop Iterations in the config)");
            }
        }

        /**
         * Runaway guard for a lazy loop: a loop paces itself across ticks as
         * long as it yields — every command, chat, or #wait hands control back
         * to the executor. So the only hazard is a stretch of iterations that
         * emit nothing (they'd spin the coroutine), which we cap at
         * maxLoopIterations consecutive. Yielding loops (e.g. /randomfill's
         * setblocks, a #while poll) run unbounded, spread across ticks.
         */
        private final class LoopGuard {
            private final String word;
            private long lastEmit = emitCount;
            private long stalled;

            LoopGuard(String word) {
                this.word = word;
            }

            void step() {
                if (emitCount != lastEmit) {
                    lastEmit = emitCount;
                    stalled = 0;
                } else if (++stalled > options.maxLoopIterations()) {
                    // In a function the "add a #wait" advice is nonsense — it computes synchronously.
                    if (functionMode) {
                        throw new ParseAbort("a function loop ran past Max Loop Iterations ("
                                + options.maxLoopIterations() + ") — raise the limit or shrink the loop.");
                    }
                    throw new ParseAbort(word + " ran " + stalled + " iterations without sending or waiting"
                            + " — add a #wait (or a command) so it can spread across ticks, or shrink the loop."
                            + " (Runaway guard = Max Loop Iterations in the config.)");
                }
            }
        }

        private Value evalExpression(String expression, String where) {
            if (expression == null || expression.trim().isEmpty()) {
                throw new ParseAbort(where + " is missing");
            }
            try {
                return ExpressionEvaluator.evaluate(expression, context);
            } catch (IllegalArgumentException ex) {
                throw new ParseAbort(where + ": " + ex.getMessage());
            }
        }

        /**
         * Like {@link #evalExpression} but for an #if/#while condition: a value
         * that is merely absent right now ({@link MissingValueException} — no
         * target block, not riding, ...) makes the condition false instead of
         * aborting the script. Real errors (typos, unknown vars) still abort.
         * MissingValueException extends IllegalArgumentException, so its catch
         * must come first.
         */
        private Value evalCondition(String expression, String where) {
            if (expression == null || expression.trim().isEmpty()) {
                throw new ParseAbort(where + " is missing");
            }
            try {
                return ExpressionEvaluator.evaluate(expression, context);
            } catch (MissingValueException absent) {
                return Value.of(false);
            } catch (IllegalArgumentException ex) {
                throw new ParseAbort(where + ": " + ex.getMessage());
            }
        }

        private long evalWholeNumber(String expression, String where) {
            Value value = evalExpression(expression, where);
            if (!(value instanceof Value.NumberValue number)) {
                throw new ParseAbort(where + " must be a number");
            }
            try {
                return number.value().wholeValue().longValueExact();
            } catch (ArithmeticException ex) {
                throw new ParseAbort(where + " is too large");
            } catch (ExpressionException ex) {
                throw new ParseAbort(where + " must be a whole number, got " + number.value().toCommandString());
            }
        }
    }

    // =====================================================================
    // Statement scanning (structural parens only after directive words)
    // =====================================================================

    private sealed interface Stmt permits RawStmt, DirectiveStmt {
    }

    private record RawStmt(String text) implements Stmt {
    }

    private record DirectiveStmt(String word, String header, String group, String elseGroup) implements Stmt {
    }

    private static List<Stmt> scanStatements(String text, boolean chainingEnabled) {
        List<Stmt> statements = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos >= text.length()) {
                break;
            }

            int wordStart = text.charAt(pos) == '/' ? pos + 1 : pos;
            String word = readWord(text, wordStart).toLowerCase(Locale.ROOT);

            if (SCANNER_DIRECTIVES.contains(word)) {
                int end = findStatementEnd(text, pos, true, chainingEnabled);
                statements.add(parseDirectiveStmt(word, text.substring(wordStart, end)));
                pos = advancePastSeparator(text, end);
            } else {
                int end = findStatementEnd(text, pos, false, chainingEnabled);
                statements.add(new RawStmt(text.substring(pos, end)));
                pos = advancePastSeparator(text, end);
            }
        }

        return statements;
    }

    private static String readWord(String text, int from) {
        int end = from;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '(') {
            end++;
        }
        return text.substring(from, end);
    }

    /**
     * Finds the end of a statement: the next top-level && (or end of text).
     * $...$ spans are opaque; \-escapes never delimit; parens count only for
     * directive statements.
     */
    private static int findStatementEnd(String text, int from, boolean structuralParens, boolean chainingEnabled) {
        boolean insideSpan = false;
        int parenDepth = 0;

        for (int i = from; i < text.length() - 1; i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (current == '$') {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan) {
                continue;
            }
            if (structuralParens) {
                if (current == '(') {
                    parenDepth++;
                    continue;
                }
                if (current == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                    continue;
                }
            }
            if (chainingEnabled && parenDepth == 0 && current == '&' && text.charAt(i + 1) == '&') {
                return i;
            }
        }
        return text.length();
    }

    private static int advancePastSeparator(String text, int end) {
        if (end < text.length() && text.startsWith("&&", end)) {
            return end + 2;
        }
        return end;
    }

    /** stmtText starts with the directive word itself. */
    private static DirectiveStmt parseDirectiveStmt(String word, String stmtText) {
        String rest = stmtText.substring(word.length());

        if (word.equals("#if")) {
            Cursor cursor = new Cursor(rest);
            String condition = cursor.readGroup("#if needs a (condition), e.g. #if ($x$ > 5) (/say big)");
            String body = cursor.readGroup("#if needs a (body) after the condition, e.g. #if ($x$ > 5) (/say big)");
            String elseBody = null;
            cursor.skipWhitespace();
            if (!cursor.atEnd()) {
                String next = readWord(cursor.text, cursor.pos).toLowerCase(Locale.ROOT);
                if (next.equals("#elseif")) {
                    // sugar: the else-branch is a synthetic nested #if, so
                    // chains of any length reuse this same parse
                    String chained = "#if" + cursor.text.substring(cursor.pos + next.length());
                    return new DirectiveStmt(word, condition, body, chained);
                }
                if (!next.equals("#else")) {
                    throw new ParseAbort("Unexpected text after #if body: '" + cursor.remaining() + "'");
                }
                cursor.pos += next.length();
                elseBody = cursor.readGroup("#else needs a (body), e.g. #if (...) (...) #else (/say small)");
                cursor.skipWhitespace();
                if (!cursor.atEnd()) {
                    throw new ParseAbort("Unexpected text after #else body: '" + cursor.remaining() + "'");
                }
            }
            return new DirectiveStmt(word, condition, body, elseBody);
        }

        if (word.equals(SILENT)) {
            List<int[]> silentGroups = findTopLevelGroups(rest);
            if (silentGroups.isEmpty()) {
                throw new ParseAbort("#silent without (...) only works at the start of the line — mid-chain, wrap statements: #silent (/give @s stick)");
            }
            int[] group = silentGroups.get(0);
            if (!rest.substring(0, group[0]).trim().isEmpty()
                    || silentGroups.size() > 1
                    || !rest.substring(group[1] + 1).trim().isEmpty()) {
                throw new ParseAbort("#silent takes just one (...) group, e.g. #silent (/give @s stick && /say done)");
            }
            return new DirectiveStmt(word, "", rest.substring(group[0] + 1, group[1]), null);
        }

        // #repeat / #for / #foreach: the LAST top-level (...) group is the
        // body; everything between the word and it is the header. (Headers may
        // themselves contain parens, e.g. #repeat (2+3) (...) or range(1,5).)
        List<int[]> groups = findTopLevelGroups(rest);
        if (groups.isEmpty()) {
            throw new ParseAbort(word + " needs a (...) body, e.g. " + directiveExample(word));
        }
        int[] last = groups.get(groups.size() - 1);
        if (!rest.substring(last[1] + 1).trim().isEmpty()) {
            throw new ParseAbort("Unexpected text after " + word + " body: '" + rest.substring(last[1] + 1).trim() + "'");
        }
        String header = rest.substring(0, last[0]).trim();
        String body = rest.substring(last[0] + 1, last[1]);
        return new DirectiveStmt(word, header, body, null);
    }

    private static String directiveExample(String word) {
        return switch (word) {
            case "#repeat" -> "#repeat 5 (/say hi)";
            case "#for" -> "#for $x$ in 1..10 (/summon zombie ~$x$ ~ ~)";
            case "#foreach" -> "#foreach $mob$ in (zombie | skeleton) (/summon $mob$)";
            case "#while" -> "#while ($client.health$ < 20) (/effect give @s regeneration 1 1 && #wait 3s)";
            default -> word + " (...)";
        };
    }

    /** [openIndex, closeIndex] pairs of depth-0 parens, $-span and escape aware. */
    private static List<int[]> findTopLevelGroups(String text) {
        List<int[]> groups = new ArrayList<>();
        boolean insideSpan = false;
        int depth = 0;
        int openIndex = -1;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (current == '$') {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan) {
                continue;
            }
            if (current == '(') {
                if (depth == 0) {
                    openIndex = i;
                }
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    groups.add(new int[]{openIndex, i});
                } else if (depth < 0) {
                    throw new ParseAbort("Unbalanced parentheses: unexpected ')'");
                }
            }
        }
        if (depth > 0) {
            throw new ParseAbort("Missing closing parenthesis");
        }
        return groups;
    }

    private static final class Cursor {
        private final String text;
        private int pos;

        private Cursor(String text) {
            this.text = text;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean atEnd() {
            return pos >= text.length();
        }

        private String remaining() {
            String rest = text.substring(pos).trim();
            return rest.length() > 30 ? rest.substring(0, 30) + "…" : rest;
        }

        /** Reads a balanced (...) group, $-span and escape aware. */
        private String readGroup(String missingMessage) {
            skipWhitespace();
            if (atEnd() || text.charAt(pos) != '(') {
                throw new ParseAbort(missingMessage);
            }
            boolean insideSpan = false;
            int depth = 0;
            int start = pos + 1;
            for (int i = pos; i < text.length(); i++) {
                char current = text.charAt(i);
                if (current == '\\') {
                    i++;
                    continue;
                }
                if (current == '$') {
                    insideSpan = !insideSpan;
                    continue;
                }
                if (insideSpan) {
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                    if (depth == 0) {
                        pos = i + 1;
                        return text.substring(start, i);
                    }
                }
            }
            throw new ParseAbort("Missing closing parenthesis");
        }
    }

    // =====================================================================
    // Directive header parsing
    // =====================================================================

    private record ForHeader(String var, String from, String to, String step) {
    }

    private static ForHeader parseForHeader(String header) {
        String syntax = "#for syntax: #for $x$ in 1..10 step 2 (...)";
        VarToken var = parseVarToken(header, syntax);
        String rest = var.rest().trim();

        if (!rest.toLowerCase(Locale.ROOT).startsWith("in") || (rest.length() > 2 && !Character.isWhitespace(rest.charAt(2)))) {
            throw new ParseAbort(syntax);
        }
        rest = rest.substring(2).trim();

        int dots = findRangeDots(rest);
        if (dots < 0) {
            throw new ParseAbort("#for needs a range like 1..10. " + syntax);
        }
        String from = rest.substring(0, dots).trim();
        String tail = rest.substring(dots + 2).trim();

        String to = tail;
        String step = null;
        int stepIndex = findTopLevelWord(tail, "step");
        if (stepIndex >= 0) {
            to = tail.substring(0, stepIndex).trim();
            step = tail.substring(stepIndex + "step".length()).trim();
            if (step.isEmpty()) {
                throw new ParseAbort("#for step needs a value. " + syntax);
            }
        }
        if (from.isEmpty() || to.isEmpty()) {
            throw new ParseAbort("#for needs a range like 1..10. " + syntax);
        }
        return new ForHeader(var.name(), from, to, step);
    }

    private record ForeachHeader(String var, String literalList, String listExpression) {
    }

    private static ForeachHeader parseForeachHeader(String header) {
        String syntax = "#foreach syntax: #foreach $x$ in (a | b | c) (...) or #foreach $x$ in range(1, 10) (...)";
        VarToken var = parseVarToken(header, syntax);
        String rest = var.rest().trim();

        if (!rest.toLowerCase(Locale.ROOT).startsWith("in") || (rest.length() > 2 && !Character.isWhitespace(rest.charAt(2)) && rest.charAt(2) != '(')) {
            throw new ParseAbort(syntax);
        }
        rest = rest.substring(2).trim();
        if (rest.isEmpty()) {
            throw new ParseAbort(syntax);
        }

        if (rest.startsWith("(")) {
            if (!rest.endsWith(")")) {
                throw new ParseAbort("#foreach list is missing its closing parenthesis");
            }
            return new ForeachHeader(var.name(), rest.substring(1, rest.length() - 1), null);
        }
        return new ForeachHeader(var.name(), null, rest);
    }

    private record VarToken(String name, String rest) {
    }

    private static VarToken parseVarToken(String text, String syntax) {
        String trimmed = text.trim();
        boolean wrapped = trimmed.startsWith("$");
        if (wrapped) {
            trimmed = trimmed.substring(1);
        }

        int end = 0;
        while (end < trimmed.length() && (Character.isLetterOrDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '_')) {
            end++;
        }
        String name = trimmed.substring(0, end).toLowerCase(Locale.ROOT);
        String rest = trimmed.substring(end);

        if (wrapped) {
            if (!rest.startsWith("$")) {
                throw new ParseAbort(syntax);
            }
            rest = rest.substring(1);
        }

        validateVariableName(name);
        return new VarToken(name, rest);
    }

    /** Namespaces owned by built-in providers — user variables can't shadow them. */
    private static final Set<String> BUILTIN_NAMESPACES = Set.of("client", "world", "players", "real", "target");

    private static void validateVariableName(String name) {
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            throw new ParseAbort("Variable names must start with a letter");
        }
        if (RESERVED_VARIABLE_NAMES.contains(name)) {
            throw new ParseAbort("'" + name + "' is reserved and can't be used as a variable name");
        }
    }

    /**
     * #set/#local names may be dotted for grouping ($hitlist.bob$), but not
     * inside a built-in namespace, with empty segments, or at a group root.
     */
    private static void validateSetVariableName(String name) {
        validateVariableName(name);
        if (!name.contains(".")) {
            return;
        }
        if (name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            throw new ParseAbort("Variable names can't start/end with a dot or contain '..'");
        }
        String namespace = name.substring(0, name.indexOf('.'));
        if (BUILTIN_NAMESPACES.contains(namespace)) {
            throw new ParseAbort("$" + namespace + ".*$ is a built-in group — pick another name, e.g. $my" + namespace + "." + name.substring(name.indexOf('.') + 1) + "$");
        }
    }

    /** First ".." that isn't part of an identifier's single dots, outside parens. */
    private static int findRangeDots(String text) {
        boolean insideSpan = false;
        int depth = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (current == '$') {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan) {
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')') depth--;
            else if (depth == 0 && current == '.' && text.charAt(i + 1) == '.'
                    && (i + 2 >= text.length() || text.charAt(i + 2) != '.')
                    && (i == 0 || text.charAt(i - 1) != '.')) {
                return i;
            }
        }
        return -1;
    }

    /** Index of a standalone word at depth 0, or -1. */
    private static int findTopLevelWord(String text, String word) {
        boolean insideSpan = false;
        int depth = 0;
        for (int i = 0; i <= text.length() - word.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (current == '$') {
                insideSpan = !insideSpan;
                continue;
            }
            if (insideSpan) {
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')') depth--;
            else if (depth == 0
                    && text.regionMatches(true, i, word, 0, word.length())
                    && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))
                    && (i + word.length() >= text.length() || Character.isWhitespace(text.charAt(i + word.length())))) {
                return i;
            }
        }
        return -1;
    }

    /** Splits a literal foreach list on top-level '|', decoding \-escapes. */
    private static List<String> splitListItems(String inner) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideSpan = false;
        int depth = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                current.append(inner.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '$') {
                insideSpan = !insideSpan;
                current.append(c);
                continue;
            }
            if (!insideSpan) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == '|' && depth == 0) {
                    items.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        items.add(current.toString().trim());

        if (items.size() == 1 && items.get(0).isEmpty()) {
            throw new ParseAbort("#foreach needs at least one list item, e.g. (a | b)");
        }
        return items;
    }

    // =====================================================================
    // #set
    // =====================================================================

    private record SetVar(String name, String expression) {
    }

    /** Parses "#set $name$ = expression" (the $ around the name is optional). */
    private static SetVar parseSetDirective(String content, String directive) {
        String rest = content.substring(directive.length()).trim();

        String syntax = directive + " syntax: " + directive + " name = expression (also name += expression; $ around the name is optional)";

        boolean wrapped = rest.startsWith("$");
        if (wrapped) {
            rest = rest.substring(1);
        }

        int nameEnd = 0;
        while (nameEnd < rest.length() && (Character.isLetterOrDigit(rest.charAt(nameEnd)) || rest.charAt(nameEnd) == '_' || rest.charAt(nameEnd) == '.')) {
            nameEnd++;
        }
        String name = rest.substring(0, nameEnd).toLowerCase(Locale.ROOT);
        rest = rest.substring(nameEnd).trim();

        if (wrapped) {
            if (!rest.startsWith("$")) {
                throw new ParseAbort(syntax);
            }
            rest = rest.substring(1).trim();
        }

        if (name.isEmpty()) {
            throw new ParseAbort(syntax);
        }
        validateSetVariableName(name);

        // compound assignment: #set i += 1 (also -= *= /= %=) rewrites to
        // name op (rhs) — the natural spelling for counters
        String compound = null;
        if (rest.length() >= 2 && rest.charAt(1) == '=' && "+-*/%".indexOf(rest.charAt(0)) >= 0) {
            compound = rest.substring(0, 1);
            rest = "=" + rest.substring(2);
        }

        if (!rest.startsWith("=")) {
            throw new ParseAbort(syntax);
        }
        String expression = rest.substring(1).trim();
        if (expression.isEmpty()) {
            throw new ParseAbort(directive + " $" + name + "$ needs a value, e.g. " + directive + " $" + name + "$ = 5");
        }
        if (compound != null) {
            expression = name + " " + compound + " (" + expression + ")";
        }

        return new SetVar(name, expression);
    }

    // =====================================================================
    // Shared helpers
    // =====================================================================

    private static boolean isAlias(String command, Map<String, AliasDefinition> aliases) {
        String normalizedCommand = stripLeadingSlash(command.trim());
        int separator = findFirstWhitespace(normalizedCommand);
        String aliasName = separator >= 0 ? normalizedCommand.substring(0, separator) : normalizedCommand;
        return aliases.containsKey(aliasName.toLowerCase(Locale.ROOT));
    }

    /** The inner text when {@code content} is exactly one $...$ span ({@code \} escapes), else null. */
    private static String wholeMarkerInner(String content) {
        if (content.length() < 2 || content.charAt(0) != '$') {
            return null;
        }
        for (int i = 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                return i == content.length() - 1 ? content.substring(1, i) : null;
            }
        }
        return null;
    }

    private static Script.SendStatement normalizeSegment(String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        boolean command = false;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            command = true;
        }

        return new Script.SendStatement(trimmed, command ? Script.Kind.COMMAND : Script.Kind.CHAT);
    }

    private static String firstWord(String text) {
        int separator = findFirstWhitespace(text);
        return separator >= 0 ? text.substring(0, separator) : text;
    }

    private static int findFirstWhitespace(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (Character.isWhitespace(command.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripLeadingSlash(String command) {
        if (command.startsWith("/")) {
            return command.substring(1).trim();
        }
        return command;
    }

    private static final class ParseAbort extends RuntimeException {
        private ParseAbort(String message) {
            super(message);
        }
    }

    /**
     * Outcome of a parse. Exactly one of these shapes:
     * - error != null: report it locally, send nothing
     * - script == null: line is untouched Tupenter-wise — let the original packet through
     * - script != null: cancel the original packet and run the script;
     *   notices (e.g. "$x$ = 5" confirmations) are shown to the user
     */
    public record ParseResult(Script script, String error, List<String> notices) {
        public static ParseResult script(Script script, List<String> notices) {
            return new ParseResult(script, null, List.copyOf(notices));
        }

        public static ParseResult unchanged(String originalLine) {
            return new ParseResult(null, null, List.of());
        }

        public static ParseResult error(String message) {
            return new ParseResult(null, message, List.of());
        }

        public boolean changed() {
            return script != null;
        }
    }
}
