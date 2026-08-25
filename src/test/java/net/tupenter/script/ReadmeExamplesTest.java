package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every runnable example in README.md actually runs.
 *
 * <p>The README is the most public surface the mod has and the only large one
 * nothing generates, so it is also the one that rots quietly: a syntax change
 * lands, the registries and SCRIPTING.md follow because tests force them, and
 * the README keeps advertising the old spelling to everyone who arrives from
 * Modrinth. This closes that gap the same way BuiltinFunctionsTest closes it for
 * the help pages.
 *
 * <p>Examples that need a live world (client.*, registry tags, raycasts) can't
 * run here and are skipped by {@link #needsAWorld}; the floor in
 * {@link #atLeastThisManyRan} is what stops the filter quietly swallowing
 * everything and leaving a green test that checks nothing.
 */
class ReadmeExamplesTest {

    private static final int atLeastThisManyRan = 20;

    /** Names and forms that only resolve in game. */
    private static final List<String> WORLD_ONLY = List.of(
            "client.", "world.", "real.", "players.", "target.",
            "raycast", "blockset(", "itemset(", "effectset(", "entityset(",
            "block(", "entity(", "entities(", "simulated(", "slot(", "keys(",
            "nearest_entity(", "midpoint(");

    /** Lines that are fragments or mod-command syntax rather than script lines. */
    private static final List<String> NOT_A_SCRIPT_LINE = List.of(
            "#else", "#elseif",              // continuations of the line above them
            // resend-history prefixes: handled in MixinConnection before the
            // parser ever sees them, so the parser rightly calls them unknown
            "#stage", "#unstage",
            // /customcommand add lines are DEFINITIONS — collected below rather
            // than skipped, so the invocations that follow are real tests
            "/customfunction", "/tupenter", "/unroll", "/calc",
            "gradlew");

    private static final String DEFINE = "/customcommand add ";

    @Test
    void everyRunnableReadmeExampleRuns() throws IOException {
        Path readme = Path.of("README.md");
        assertTrue(Files.exists(readme), "expected to run from the project root");

        List<String> broken = new ArrayList<>();
        int ran = 0;
        // A fenced block is a SEQUENCE, so its lines share one variable store:
        // "#set spawn = ..." then "/tp @s $spawn$" is two lines of ONE example,
        // and checking the second in isolation would just be wrong.
        for (List<String> block : fencedCodeBlocks(Files.readString(readme))) {
            SessionVariableStore store = new SessionVariableStore();
            Map<String, AliasDefinition> aliases = new LinkedHashMap<>();
            for (String raw : block) {
                String line = stripAnnotation(raw);
                if (line.startsWith(DEFINE)) {
                    // register it, so the /sunny on the next line means something
                    try {
                        defineAlias(line, aliases);
                    } catch (RuntimeException ex) {
                        broken.add(line + "\n      -> " + ex.getMessage());
                    }
                    continue;
                }
                if (line.isEmpty() || needsAWorld(line) || notAScriptLine(line)) {
                    continue;
                }
                // A whole-line "## note" contributes nothing to run. It is only
                // a line of its own because this test reads a block line by line;
                // in the block it belongs to the statement below it.
                if (Comments.isOnlyComment(line)) {
                    continue;
                }
                if (line.startsWith("/") || line.startsWith("#")) {
                    ran++;
                    String error = parseError(line, store, aliases);
                    if (error != null) {
                        broken.add(line + "\n      -> " + error);
                    }
                } else if (line.matches("^[a-z_]+\\(.*")) {
                    ran++; // a bare expression shown on its own, e.g. list(1, 2, 3)
                    try {
                        ExpressionEvaluator.evaluate(line, new EvalContext(new Random(1)));
                    } catch (RuntimeException ex) {
                        broken.add(line + "\n      -> " + ex.getMessage());
                    }
                }
            }
        }

        if (!broken.isEmpty()) {
            fail("README examples that don't work:\n  " + String.join("\n  ", broken));
        }
        assertTrue(ran >= atLeastThisManyRan,
                "only " + ran + " README examples were checked (expected at least " + atLeastThisManyRan
                        + ") — the skip filter is probably eating them");
    }

    /** "/customcommand add name &lt;decls&gt; = body" -&gt; a registered alias. */
    private static void defineAlias(String line, Map<String, AliasDefinition> into) {
        String rest = line.substring(DEFINE.length()).strip();
        int space = rest.indexOf(' ');
        String name = space < 0 ? rest : rest.substring(0, space);
        String body = space < 0 ? "" : rest.substring(space + 1).strip();
        into.put(name.toLowerCase(java.util.Locale.ROOT), AliasDefinition.parse(body));
    }

    private static String parseError(String line, SessionVariableStore store,
                                     Map<String, AliasDefinition> aliases) {
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT,
                aliases, true, true, true, true, 100, 1000, new Random(42), store, store);
        try {
            return ScriptParser.parse(line, options).error();
        } catch (RuntimeException ex) {
            return String.valueOf(ex);
        }
    }

    private static List<List<String>> fencedCodeBlocks(String markdown) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : markdown.lines().toList()) {
            if (line.startsWith("```")) {
                if (current == null) {
                    current = new ArrayList<>();
                } else {
                    blocks.add(current);
                    current = null;
                }
            } else if (current != null) {
                current.add(line);
            }
        }
        return blocks;
    }

    /**
     * Drops the trailing commentary the README pads its examples with — an
     * arrow, or a parenthetical held off by a run of spaces. Both are prose
     * about the line, not part of it.
     */
    private static String stripAnnotation(String line) {
        String stripped = line;
        for (String marker : List.of("→", "←")) {
            int at = stripped.indexOf(marker);
            if (at >= 0) {
                stripped = stripped.substring(0, at);
            }
        }
        int parenthetical = stripped.indexOf("  (");
        if (parenthetical >= 0) {
            stripped = stripped.substring(0, parenthetical);
        }
        return stripped.strip();
    }

    private static boolean needsAWorld(String line) {
        return WORLD_ONLY.stream().anyMatch(line::contains);
    }

    private static boolean notAScriptLine(String line) {
        return NOT_A_SCRIPT_LINE.stream().anyMatch(line::startsWith);
    }
}
