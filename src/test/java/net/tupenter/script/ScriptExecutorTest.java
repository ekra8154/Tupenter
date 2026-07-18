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
    }

    private static Script script(String... commands) {
        List<Script.SendStatement> statements = new ArrayList<>();
        for (String command : commands) {
            statements.add(new Script.SendStatement(command, Script.Kind.COMMAND, false));
        }
        return new Script(String.join(" && ", commands), statements, Script.HistoryMode.NORMAL);
    }

    private static Script silentScript(String... commands) {
        List<Script.SendStatement> statements = new ArrayList<>();
        for (String command : commands) {
            statements.add(new Script.SendStatement(command, Script.Kind.COMMAND, true));
        }
        return new Script(String.join(" && ", commands), statements, Script.HistoryMode.NORMAL);
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

        executor.submit(new Script("x", List.of(
                new Script.SendStatement("time set day", Script.Kind.COMMAND, false),
                new Script.SendStatement("Have fun!", Script.Kind.CHAT, false)
        ), Script.HistoryMode.NORMAL));

        assertEquals(List.of("/time set day", "Have fun!"), sender.sent);
    }

    @Test
    void mixedSilenceOnlyTriggersOnSilentStatements() {
        RecordingSender sender = new RecordingSender();
        ScriptExecutor executor = executor(sender, 1, 1000, 8);

        executor.submit(new Script("x", List.of(
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

        executor.submit(new Script("#set $x$ = 5", List.of(), Script.HistoryMode.NORMAL));
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
}
