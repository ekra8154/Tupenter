package net.tupenter.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.IntSupplier;

/**
 * Tick-driven script scheduler (docs/SCRIPTING_DESIGN.md §2, §2.5).
 *
 * Scripts drain FIFO under a shared per-tick send budget; a script that hits
 * a {@code #wait} parks for that many ticks WITHOUT blocking the scripts
 * behind it. Lazy scripts evaluate each statement as it's pulled — that's
 * the whole point of #wait — and a runtime evaluation error stops just that
 * script (sends already made stay sent).
 *
 * Overlap policy: re-submitting the SAME source line (rapid-fire resend)
 * cancels the still-running previous instance — restart, not stack.
 * Different lines run concurrently up to the concurrency cap.
 * {@link #trySubmit} (tick scripts) instead treats a running same-source
 * instance as success, so a waiting tick script isn't stacked every tick.
 */
public final class ScriptExecutor {

    /** Bridge to the Minecraft client; the executor itself stays MC-free. */
    public interface PacketSender {
        void sendCommand(String command);

        void sendChat(String message);

        void error(String message);

        /** Local info line (e.g. a #set notice from a lazy script). */
        void info(String message);
    }

    public interface Limits {
        int maxCommandsPerTick();

        int maxCommandsPerScript();

        int maxConcurrentScripts();
    }

    /** Convenience for wiring limits from config fields. */
    public static Limits limits(IntSupplier perTick, IntSupplier perScript, IntSupplier concurrent) {
        return new Limits() {
            @Override
            public int maxCommandsPerTick() {
                return Math.max(1, perTick.getAsInt());
            }

            @Override
            public int maxCommandsPerScript() {
                return Math.max(1, perScript.getAsInt());
            }

            @Override
            public int maxConcurrentScripts() {
                return Math.max(1, concurrent.getAsInt());
            }
        };
    }

    /** Ticks the silence window stays open after a #silent script's last send,
     * so feedback that arrives a moment later is still suppressed. */
    private static final int SILENCE_GRACE_TICKS = 10;

    private final PacketSender sender;
    private final Limits limits;
    private final Deque<Instance> running = new ArrayDeque<>();
    private int budgetUsedThisTick;
    private int silenceGraceTicks;

    public ScriptExecutor(PacketSender sender, Limits limits) {
        this.sender = sender;
        this.limits = limits;
    }

    /**
     * Queues a script and immediately drains as much of it as this tick's
     * remaining budget allows, so small scripts behave exactly like the old
     * synchronous path. A running instance of the same source line is
     * cancelled first (restart, not stack).
     */
    public void submit(Script script) {
        cancelSameSource(script.originalLine());

        if (running.size() >= limits.maxConcurrentScripts()) {
            sender.error("Too many scripts running (" + limits.maxConcurrentScripts() + "). Use /tupenter abort or wait for them to finish.");
            return;
        }

        if (!script.isLazy() && script.statements().size() > limits.maxCommandsPerScript()) {
            sender.error("Script would send " + script.statements().size() + " commands (limit " + limits.maxCommandsPerScript() + ").");
            return;
        }

        running.addLast(new Instance(script));
        drain();
    }

    /**
     * Like {@link #submit} but silently refuses instead of reporting — for
     * tick scripts, which retry next tick anyway and must never spam chat.
     * A same-source instance still running (e.g. parked at a #wait) counts
     * as success, so waiting tick scripts don't stack.
     *
     * @return false when a cap refused the script
     */
    public boolean trySubmit(Script script) {
        for (Instance instance : running) {
            if (instance.script.originalLine().equals(script.originalLine())) {
                script.source().close();
                return true; // already running — resubmitting would stack it
            }
        }
        if (running.size() >= limits.maxConcurrentScripts()
                || (!script.isLazy() && script.statements().size() > limits.maxCommandsPerScript())) {
            return false;
        }
        running.addLast(new Instance(script));
        drain();
        return true;
    }

    /** Called once per client tick. */
    public void tick() {
        budgetUsedThisTick = 0;
        if (silenceGraceTicks > 0) {
            silenceGraceTicks--;
        }
        for (Instance instance : running) {
            if (instance.waitTicks > 0) {
                instance.waitTicks--;
            }
        }
        drain();
    }

    public void abortAll() {
        running.forEach(Instance::close);
        running.clear();
        silenceGraceTicks = 0;
    }

    /**
     * True shortly after a #silent statement was sent — the window in which
     * its server feedback arrives and gets suppressed.
     */
    public boolean isSilenceActive() {
        return silenceGraceTicks > 0;
    }

    public boolean isIdle() {
        return running.isEmpty();
    }

    public int runningCount() {
        return running.size();
    }

    private void cancelSameSource(String originalLine) {
        for (Iterator<Instance> iterator = running.iterator(); iterator.hasNext(); ) {
            Instance instance = iterator.next();
            if (instance.script.originalLine().equals(originalLine)) {
                instance.close();
                iterator.remove();
            }
        }
    }

    private void drain() {
        // waits and notices are free, but bound total pulls so a
        // pathological script can't spin the loop forever within one tick
        int pullsLeft = limits.maxCommandsPerTick() * 8 + 32;

        for (Iterator<Instance> iterator = running.iterator(); iterator.hasNext(); ) {
            Instance instance = iterator.next();

            while (instance.waitTicks == 0
                    && budgetUsedThisTick < limits.maxCommandsPerTick()
                    && pullsLeft-- > 0) {
                Script.SendStatement statement;
                try {
                    statement = instance.next();
                } catch (Script.RuntimeError error) {
                    sender.error("Script stopped: " + error.getMessage()
                            + " (line: " + preview(instance.script.originalLine()) + ")");
                    instance.close();
                    iterator.remove();
                    break;
                }

                if (statement == null) {
                    instance.close();
                    iterator.remove();
                    break;
                }

                switch (statement.kind()) {
                    case COMMAND, CHAT -> {
                        budgetUsedThisTick++;
                        if (statement.silent()) {
                            silenceGraceTicks = SILENCE_GRACE_TICKS;
                        }
                        if (statement.kind() == Script.Kind.COMMAND) {
                            sender.sendCommand(statement.content());
                        } else {
                            sender.sendChat(statement.content());
                        }
                    }
                    case WAIT -> instance.waitTicks = statement.waitTicks();
                    case NOTICE -> sender.info(statement.content());
                }

                // completion is detectable without a pull for list-backed
                // scripts — don't let the budget strand a finished script
                if (instance.drained()) {
                    instance.close();
                    iterator.remove();
                    break;
                }
            }

            if (budgetUsedThisTick >= limits.maxCommandsPerTick() || pullsLeft <= 0) {
                break;
            }
        }
    }

    private static String preview(String line) {
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }

    private static final class Instance {
        private final Script script;
        private final Script.StatementSource source;
        private int waitTicks;

        private Instance(Script script) {
            this.script = script;
            this.source = script.source();
        }

        private Script.SendStatement next() {
            return source.next();
        }

        private boolean drained() {
            return source.drained();
        }

        private void close() {
            source.close();
        }
    }
}
