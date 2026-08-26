package net.tupenter.command;

import net.tupenter.TupenterModClient;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.ScriptExecutor;
import net.tupenter.script.ScriptParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the Mod Menu "Scripts" list as persistent loops — the walking
 * .mcfunction file. Each armed line (enabled globals + this world's enabled
 * scripts) runs as ONE long-lived lazy coroutine:
 *
 *   #while (1 > 0) ( &lt;body&gt; && #wait 1t )
 *
 * The implicit {@code #wait 1t} paces it at one body-evaluation per tick (the
 * old "every tick" cadence) while yielding so it never spins. Because it's a
 * single lazy instance rather than a fresh eager parse each tick:
 *
 * - {@code #wait} inside the body composes naturally (the loop resumes after
 *   it), and {@code $markers$} after a wait re-evaluate against fresh state
 * - {@code #while}/state persist across iterations
 * - no id churn (one instance, id 0, exempt from the concurrency cap)
 *
 * This runner reconciles each tick: start a loop for any newly-armed line,
 * stop one whose line was disarmed / the world changed. A line whose parse
 * fails, or whose loop dies at runtime, is reported once and left faulted until
 * it's edited (its text changes) or disarmed — so a broken script can't respin
 * an error every tick. Tick loops never touch resend history.
 */
public final class TickScriptRunner {
    private final Set<String> faulted = new HashSet<>();  // bodies that errored — don't respin
    private final Set<String> active = new HashSet<>();   // bodies we've started a loop for

    /** Full reset — for a world/session change, where the executor is also cleared (abortAll). */
    public void reset() {
        faulted.clear();
        active.clear();
    }

    /**
     * Give edited scripts a fresh chance WITHOUT forgetting what's running.
     * Clearing {@code active} too (as {@link #reset} does) would orphan the
     * still-running loop of any script whose body just changed: the reconciler
     * would no longer know to abort the OLD body, so it would keep looping
     * alongside the new one. Keeping {@code active} lets the normal desired-vs-
     * active diff stop the old body and start the new — while a cleared
     * {@code faulted} lets a previously-broken (now-edited) script retry.
     */
    public void clearFaults() {
        faulted.clear();
    }

    public void tick(ScriptExecutor executor) {
        boolean on = TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                && TupenterConfig.INSTANCE.tickScriptsEnabled;

        // What SHOULD be looping right now (empty when the master is off).
        Set<String> desired = new LinkedHashSet<>();
        if (on) {
            for (String line : TupenterConfig.INSTANCE.armedScriptLines(TupenterModClient.currentWorldKey())) {
                // newlines are Mod Menu formatting only — a script runs as one line
                String collapsed = net.tupenter.script.Comments.flatten(line);
                if (collapsed.isEmpty() || collapsed.startsWith("//")) {
                    continue;
                }
                // "restock = /clear" runs "/clear"; the name is a label, not code
                String body = TupenterConfig.scriptBody(collapsed);
                if (!body.isEmpty()) {
                    desired.add(body);
                }
            }
        }

        ScriptParser.Options options = TupenterModClient.parserOptions().withLazyExecution(true);
        for (String body : desired) {
            if (faulted.contains(body)) {
                continue;
            }
            if (active.contains(body)) {
                if (executor.isRunningSource(body)) {
                    continue; // healthy, still looping
                }
                // it was running but the loop died (runtime error already
                // reported by the executor) — fault it, don't respin the error
                faulted.add(body);
                active.remove(body);
                continue;
            }
            startLoop(executor, body, options);
        }

        // Stop loops that are no longer armed here (disarmed, or world changed).
        for (String body : new ArrayList<>(active)) {
            if (!desired.contains(body)) {
                executor.abortSource(body);
                active.remove(body);
            }
        }
        // A disarmed/edited script clears its fault, so re-enabling retries it.
        faulted.retainAll(desired);
    }

    private void startLoop(ScriptExecutor executor, String body, ScriptParser.Options options) {
        String loop = "#while (1 > 0) (" + body + " && #wait 1t)";
        // parse the wrapped loop, but label the script with the raw body so the
        // running list, abort-by-source and error messages all read cleanly
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(loop, body, options);
        if (result.error() != null) {
            faulted.add(body);
            TupenterModClient.sendEnhancedParsingError("Tick script disabled until edited — " + result.error()
                    + " (script: " + preview(body) + ")");
            return;
        }
        if (executor.trySubmit(result.script())) {
            active.add(body);
        }
        // trySubmit only refuses on a cap it no longer applies to tick loops, so
        // this practically always starts; if not, we simply retry next tick.
    }

    private static String preview(String line) {
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }
}
