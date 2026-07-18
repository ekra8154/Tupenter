package net.tupenter.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntSupplier;

/**
 * Tick-driven script scheduler (docs/SCRIPTING_DESIGN.md §2).
 *
 * Scripts are drained FIFO under a shared per-tick send budget. Without
 * #wait, a submitted script normally finishes within the tick it was
 * submitted in; the budget only stretches unusually large scripts across
 * ticks as kick protection. This is also where #wait will pause instances
 * later without any rearchitecting.
 */
public final class ScriptExecutor {

    /** Bridge to the Minecraft client; the executor itself stays MC-free. */
    public interface PacketSender {
        void sendCommand(String command);

        void sendChat(String message);

        /** #echo — display locally, send nothing. */
        void echo(String message);

        void error(String message);
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
     * synchronous path.
     */
    public void submit(Script script) {
        if (running.size() >= limits.maxConcurrentScripts()) {
            sender.error("Too many scripts running (" + limits.maxConcurrentScripts() + "). Use /tupenter abort or wait for them to finish.");
            return;
        }

        if (script.statements().size() > limits.maxCommandsPerScript()) {
            sender.error("Script would send " + script.statements().size() + " commands (limit " + limits.maxCommandsPerScript() + ").");
            return;
        }

        running.addLast(new Instance(script));
        drain();
    }

    /**
     * Like {@link #submit} but silently refuses instead of reporting — for
     * tick scripts, which retry next tick anyway and must never spam chat.
     *
     * @return false when a cap refused the script
     */
    public boolean trySubmit(Script script) {
        if (running.size() >= limits.maxConcurrentScripts()
                || script.statements().size() > limits.maxCommandsPerScript()) {
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
        drain();
    }

    public void abortAll() {
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

    private void drain() {
        while (!running.isEmpty() && budgetUsedThisTick < limits.maxCommandsPerTick()) {
            Instance instance = running.peekFirst();
            if (instance.isDone()) { // e.g. a script that was only #set statements
                running.pollFirst();
                continue;
            }
            Script.SendStatement statement = instance.next();
            budgetUsedThisTick++;

            if (statement.silent()) {
                silenceGraceTicks = SILENCE_GRACE_TICKS;
            }

            switch (statement.kind()) {
                case COMMAND -> sender.sendCommand(statement.content());
                case CHAT -> sender.sendChat(statement.content());
                case ECHO -> sender.echo(statement.content());
            }

            if (instance.isDone()) {
                running.pollFirst();
            }
        }
    }

    private static final class Instance {
        private final Script script;
        private int nextStatement;

        private Instance(Script script) {
            this.script = script;
        }

        private Script.SendStatement next() {
            return script.statements().get(nextStatement++);
        }

        private boolean isDone() {
            return nextStatement >= script.statements().size();
        }
    }
}
