package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptExecutorTest {

    private static final class RecordingSender implements ScriptExecutor.PacketSender {
        final List<String> sent = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override
        public void sendCommand(String command) {
            sent.add("/" + command);
        }

        @Override
        public void sendChat(String message) {
            sent.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        @Override
        public void info(String message) {
            infos.add(message);
        }
    }

    private static Script script(String... commands) {
        List<Script.SendStatement> statements = new ArrayList<>();
        for (String command : commands) {
            statements.add(new Script.SendStatement(command, Script.Kind.COMMAND, false));
        }
        return Script.ofStatements(String.join(" && ", commands), statements, Script.HistoryMode.NORMAL);
    }

    private static Script silentScript(String... commands) {
        List<Script.SendStatement> statements = new ArrayList<>();
        for (String command : commands) {
            statements.add(new Script.SendStatement(command, Script.Kind.COMMAND, true));
        }
        return Script.ofStatements(String.join(" && ", commands), statements, Script.HistoryMode.NORMAL);
    }

    private static ScriptExecutor executor(RecordingSender sender, int perTick, int perScript, int concurrent) {
        return new ScriptExecutor(sender, ScriptExecutor.limits(() -> perTick, () -> perScript, () -> concurrent));
    }

    @Test
    void smallScriptDrainsImmediatelyOnSubmit() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(script("a", "b", "c"));

        assertEquals(List.of("/a", "/b", "/c"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void perTickBudgetSpreadsLargeScripts() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 2, 1000, 8);

        executor.submit(script("a", "b", "c", "d", "e"));
        assertEquals(2, sender.sent.size());

        executor.tick();
        assertEquals(4, sender.sent.size());

        executor.tick();
        assertEquals(5, sender.sent.size());
        assertTrue(executor.isIdle());
    }

    @Test
    void chatStatementsGoThroughSendChat() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(Script.ofStatements("x", List.of(
                new Script.SendStatement("time set day", Script.Kind.COMMAND, false),
                new Script.SendStatement("Have fun!", Script.Kind.CHAT, false)
        ), Script.HistoryMode.NORMAL));

        assertEquals(List.of("/time set day", "Have fun!"), sender.sent);
    }

    @Test
    void mixedSilenceOnlyTriggersOnSilentStatements() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(Script.ofStatements("x", List.of(
                new Script.SendStatement("give @s stick", Script.Kind.COMMAND, true),
                new Script.SendStatement("say here", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL));

        assertTrue(executor.isSilenceActive(), "grace opens on the silent send");
        for (int i = 0; i < 11; i++) {
            executor.tick(); // drains the non-silent send, then grace expires
        }
        assertFalse(executor.isSilenceActive());
        assertEquals(List.of("/give @s stick", "/say here"), sender.sent);
    }

    @Test
    void oversizedScriptIsRejectedWholesale() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 2, 8);

        executor.submit(script("a", "b", "c"));

        assertTrue(sender.sent.isEmpty());
        assertEquals(1, sender.errors.size());
        assertTrue(executor.isIdle());
    }

    @Test
    void concurrencyCapRejectsExcessScripts() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 1);

        executor.submit(script("a", "b")); // budget 1 → "a" sent, script still running
        executor.submit(script("c"));      // cap 1 → rejected

        assertEquals(List.of("/a"), sender.sent);
        assertEquals(1, sender.errors.size());

        executor.tick(); // finishes first script
        assertEquals(List.of("/a", "/b"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void scriptsDrainFifo() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(script("a", "b"));
        executor.submit(script("c"));

        executor.tick();
        executor.tick();

        assertEquals(List.of("/a", "/b", "/c"), sender.sent);
    }

    @Test
    void abortAllStopsEverything() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(script("a", "b", "c"));
        executor.abortAll();
        executor.tick();

        assertEquals(List.of("/a"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void silenceWindowCoversRunAndGrace() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(silentScript("a", "b"));
        assertTrue(executor.isSilenceActive(), "active while silent script runs");

        executor.tick(); // sends "b", script done, grace window starts
        assertTrue(executor.isSilenceActive(), "grace window after completion");

        for (int i = 0; i < 10; i++) {
            executor.tick();
        }
        assertFalse(executor.isSilenceActive(), "grace window expired");
    }

    @Test
    void nonSilentScriptsDoNotSilence() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(script("a"));
        assertFalse(executor.isSilenceActive());
    }

    @Test
    void emptyScriptCompletesQuietly() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(Script.ofStatements("#set $x$ = 5", List.of(), Script.HistoryMode.NORMAL));
        executor.tick();

        assertTrue(sender.sent.isEmpty());
        assertTrue(sender.errors.isEmpty());
        assertTrue(executor.isIdle());
    }

    @Test
    void trySubmitRefusesQuietlyAtTheCap() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 1);

        assertTrue(executor.trySubmit(script("a", "b"))); // budget 1 → still running
        assertFalse(executor.trySubmit(script("c")));     // cap hit — no error message

        assertTrue(sender.errors.isEmpty(), "quiet refusal must not report");
        assertEquals(List.of("/a"), sender.sent);
    }

    @Test
    void abortClearsSilence() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(silentScript("a", "b"));
        executor.abortAll();
        assertFalse(executor.isSilenceActive());
    }

    // --- #wait + lazy execution ---

    @Test
    void waitParksAScriptWithoutBlockingOthers() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(Script.ofStatements("w", List.of(
                new Script.SendStatement("first", Script.Kind.COMMAND, false),
                Script.SendStatement.waitFor(2),
                new Script.SendStatement("after", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL));
        executor.submit(script("other"));

        // "other" runs even though the first script is parked at the wait
        assertEquals(List.of("/first", "/other"), sender.sent);

        executor.tick(); // wait 2 -> 1
        assertEquals(List.of("/first", "/other"), sender.sent);
        executor.tick(); // wait 1 -> 0, "after" sends
        assertEquals(List.of("/first", "/other", "/after"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void resubmittingTheSameLineRestartsInsteadOfStacking() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(Script.ofStatements("line", List.of(
                new Script.SendStatement("a", Script.Kind.COMMAND, false),
                Script.SendStatement.waitFor(100),
                new Script.SendStatement("never", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL));
        executor.submit(Script.ofStatements("line", List.of(
                new Script.SendStatement("b", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL));

        executor.tick();
        // the parked first instance was cancelled — "never" never sends
        assertEquals(List.of("/a", "/b"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void trySubmitDedupesARunningSameSourceScript() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        Script first = Script.ofStatements("tickline", List.of(
                new Script.SendStatement("a", Script.Kind.COMMAND, false),
                Script.SendStatement.waitFor(5),
                new Script.SendStatement("b", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL);
        assertTrue(executor.trySubmit(first));
        assertTrue(executor.trySubmit(Script.ofStatements("tickline", List.of(
                new Script.SendStatement("dup", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL)), "same source counts as success, not a new instance");

        assertEquals(List.of("/a"), sender.sent);
        assertEquals(1, executor.runningCount());
    }

    @Test
    void noticesAreReportedNotSent() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        executor.submit(Script.ofStatements("n", List.of(
                Script.SendStatement.notice("$x$ = 5"),
                new Script.SendStatement("a", Script.Kind.COMMAND, false)
        ), Script.HistoryMode.NORMAL));

        assertEquals(List.of("$x$ = 5"), sender.infos);
        assertEquals(List.of("/a"), sender.sent);
    }

    // --- lazy scripts end-to-end through the parser ---

    private static ScriptParser.Options lazyOptions(SessionVariableStore store) {
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, java.util.Map.of(),
                true, true, true, true, 100, 1000, new java.util.Random(42), store, store)
                .withLazyExecution(true);
    }

    @Test
    void lazyScriptsEvaluateMarkersAtPullTimeNotSubmitTime() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("y", Value.ofNumber(1));
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8); // budget 1: one statement per tick

        ScriptParser.ParseResult result = ScriptParser.parse("say $y$ && /say $y$", lazyOptions(store));
        assertTrue(result.changed());
        assertTrue(result.script().isLazy());

        executor.submit(result.script()); // budget 1 → only the first statement evaluates+sends
        store.set("y", Value.ofNumber(2)); // world changed between ticks
        executor.tick();

        assertEquals(List.of("/say 1", "/say 2"), sender.sent, "second statement saw the NEW value");
        executor.tick(); // the end-of-script pull needed a fresh budget window
        assertTrue(executor.isIdle());
    }

    @Test
    void lazyWaitDefersEvaluationAcrossTheWait() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("y", Value.ofNumber(1));
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse("say before && #wait 2t && /say $y$", lazyOptions(store));
        executor.submit(result.script());
        assertEquals(List.of("/say before"), sender.sent);

        store.set("y", Value.ofNumber(99));
        executor.tick();
        executor.tick();

        assertEquals(List.of("/say before", "/say 99"), sender.sent, "post-wait marker read the post-wait value");
        assertTrue(executor.isIdle());
    }

    @Test
    void lazyRuntimeErrorsStopJustThatScript() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse(
                "say ok && /say $nosuchvar$", lazyOptions(new SessionVariableStore()));
        executor.submit(result.script());

        assertEquals(List.of("/say ok"), sender.sent, "sends before the error stay sent");
        assertEquals(1, sender.errors.size());
        assertTrue(executor.isIdle());
    }

    @Test
    void lazySetCommitsToSessionWhenTheScriptFinishes() {
        SessionVariableStore store = new SessionVariableStore();
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse("#set $x$ = 5 && /say $x$", lazyOptions(store));
        executor.submit(result.script());

        assertEquals(List.of("/say 5"), sender.sent);
        assertEquals(List.of("$x$ = 5"), sender.infos, "the #set notice arrived through the stream");
        assertEquals("5", store.resolve("x").orElseThrow().displayString());
        assertTrue(executor.isIdle());
    }

    @Test
    void lazyYieldingLoopRunsPastTheIterationCap() {
        // maxLoopIterations = 100, but a loop that SENDS each iteration paces
        // itself across ticks and isn't bounded by that cap
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse("#repeat 150 (/say $i$)", lazyOptions(new SessionVariableStore()));
        assertTrue(result.script().isLazy());
        executor.submit(result.script());
        for (int i = 0; i < 40 && !executor.isIdle(); i++) {
            executor.tick();
        }
        assertTrue(executor.isIdle(), "loop finished");
        assertEquals(150, sender.sent.size(), "all 150 iterations ran despite the 100 cap");
        assertTrue(sender.errors.isEmpty());
    }

    @Test
    void lazyWhileLoopsUntilConditionFalse() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("n", Value.ofNumber(3));
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse(
                "#while ($n$ > 0) (/say $n$ && #set $n$ = $n$ - 1)", lazyOptions(store));
        executor.submit(result.script());
        for (int i = 0; i < 20 && !executor.isIdle(); i++) {
            executor.tick();
        }
        assertEquals(List.of("/say 3", "/say 2", "/say 1"), sender.sent);
        assertTrue(executor.isIdle());
    }

    @Test
    void lazyNonYieldingLoopTripsTheGuard() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        // a while-true whose body neither sends nor waits would spin — the guard stops it
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#while (1 > 0) (#local $x$ = 1)", lazyOptions(new SessionVariableStore()));
        executor.submit(result.script());
        for (int i = 0; i < 5 && !executor.isIdle(); i++) {
            executor.tick();
        }
        assertEquals(1, sender.errors.size(), "runaway guard aborted it");
        assertTrue(sender.errors.get(0).contains("without sending or waiting"), sender.errors.toString());
        assertTrue(executor.isIdle());
    }

    @Test
    void trivialLinesStayEagerEvenWithLazyEnabled() {
        ScriptParser.ParseResult result = ScriptParser.parse("say hello", lazyOptions(new SessionVariableStore()));
        assertFalse(result.changed(), "a plain single command passes through untouched");
    }

    @Test
    void abortInterruptsAParkedLazyScript() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 16, 1000, 8);

        ScriptParser.ParseResult result = ScriptParser.parse(
                "say a && #wait 100t && /say b", lazyOptions(new SessionVariableStore()));
        executor.submit(result.script());
        assertEquals(List.of("/say a"), sender.sent);

        executor.abortAll();
        for (int i = 0; i < 105; i++) {
            executor.tick();
        }
        assertEquals(List.of("/say a"), sender.sent);
        assertTrue(executor.isIdle());
    }
}
