package net.tupenter.command;

import net.tupenter.TupenterModClient;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.Script;
import net.tupenter.script.ScriptExecutor;
import net.tupenter.script.ScriptParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the Mod Menu "Scripts" list every client tick — the walking
 * .mcfunction file. Semantics:
 *
 * - one line per script; lines starting with // are disabled
 * - a script is submitted each tick unless the concurrency cap refuses it
 *   (quietly — it just tries again next tick)
 * - tick scripts never touch resend history, and their #set notices are
 *   never displayed
 * - a script whose parse fails reports the error ONCE and is skipped until
 *   the list changes or you rejoin — no per-tick error spam
 */
public final class TickScriptRunner {
    private final Set<String> faulted = new HashSet<>();

    public void reset() {
        faulted.clear();
    }

    public void tick(ScriptExecutor executor) {
        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled || !TupenterConfig.INSTANCE.tickScriptsEnabled) {
            return;
        }

        List<String> scripts = TupenterConfig.INSTANCE.tickScripts;
        if (scripts.isEmpty()) {
            return;
        }

        ScriptParser.Options options = TupenterModClient.parserOptions();
        for (String line : scripts) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || faulted.contains(trimmed)) {
                continue;
            }

            String command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
            ScriptParser.ParseResult result = ScriptParser.parse(command, options);

            if (result.error() != null) {
                faulted.add(trimmed);
                TupenterModClient.sendEnhancedParsingError("Tick script disabled until edited — " + result.error()
                        + " (script: " + preview(trimmed) + ")");
                continue;
            }

            Script script = result.changed()
                    ? result.script()
                    : new Script(trimmed, List.of(new Script.SendStatement(command, Script.Kind.COMMAND, false)), Script.HistoryMode.SKIP);

            executor.trySubmit(script); // quiet refusal — retry next tick
        }
    }

    private static String preview(String line) {
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }
}
