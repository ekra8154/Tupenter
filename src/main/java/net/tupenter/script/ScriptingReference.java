package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete scripting reference, rendered as Markdown from the same doc
 * registries the in-game help reads. Prose explains the model; every
 * enumeration (directives, functions, parameter types, variables) is generated,
 * so the document cannot fall behind the implementation — a function without a
 * doc entry is already a compile error, and {@code ScriptingReferenceTest}
 * fails the build if the checked-in SCRIPTING.md drifts from this output.
 *
 * <p>Two outlets share this one source: the SCRIPTING.md file in the repo, and
 * {@code /tupenter reference}, which copies the same text to the clipboard.
 */
public final class ScriptingReference {

    private ScriptingReference() {
    }

    /**
     * The whole document.
     *
     * @param variables every registered variable's doc, in registration order —
     *                  passed in because the providers that own them live in the
     *                  client source set, which this package must not import
     */
    public static String render(List<VarDoc> variables) {
        StringBuilder out = new StringBuilder();
        out.append(HEADER);
        out.append(MODEL);
        out.append(STATEMENTS);
        out.append(EXPRESSIONS);
        out.append(VARIABLES_PROSE);
        out.append(CONTROL_FLOW);
        out.append(TIMING);
        out.append(CUSTOM_COMMANDS);
        out.append(CUSTOM_FUNCTIONS);
        out.append(TICK_SCRIPTS);
        out.append(OUTPUT);
        out.append(directiveTables());
        out.append(functionTables());
        out.append(paramTypeTables());
        out.append(variableTables(variables));
        out.append(GOTCHAS);
        return out.toString();
    }

    // ------------------------------------------------------------ generated

    private static String directiveTables() {
        StringBuilder out = new StringBuilder("## Every directive\n\n");
        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            groups.computeIfAbsent(doc.group().label(), g -> new StringBuilder())
                    .append("#### `").append(doc.canonical()).append("`\n\n")
                    .append(doc.blurb()).append("\n\n")
                    .append("```\n").append(doc.signature()).append("\n```\n\n")
                    .append(details(doc.detail()))
                    .append(examples(doc.exampleSimple(), doc.exampleComposed()));
        }
        groups.forEach((group, body) -> out.append("### ").append(group).append("\n\n").append(body));
        return out.toString();
    }

    private static String functionTables() {
        StringBuilder out = new StringBuilder("## Every built-in function\n\n"
                + "Functions are called inside `$...$` (or anywhere an expression is "
                + "expected, such as a `#if` condition or the right side of `#set`).\n\n");
        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (BuiltinFunctions.Doc doc : BuiltinFunctions.ALL) {
            groups.computeIfAbsent(doc.group().label(), g -> new StringBuilder())
                    .append("#### `").append(doc.signature()).append("`\n\n")
                    .append(doc.blurb()).append("\n\n")
                    .append(details(doc.detail()))
                    .append(examples(doc.exampleSimple(), doc.exampleComposed()));
        }
        groups.forEach((group, body) -> out.append("### ").append(group).append("\n\n").append(body));
        return out.toString();
    }

    private static String paramTypeTables() {
        StringBuilder out = new StringBuilder("## Every parameter type\n\n"
                + "Used in custom-command and custom-function signatures as "
                + "`<name:type>`, optionally with `=default`.\n\n");
        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            StringBuilder body = groups.computeIfAbsent(doc.group(), g -> new StringBuilder());
            body.append("#### `").append(doc.keyword()).append('`');
            if (!doc.synonyms().isEmpty()) {
                body.append(" (also `").append(String.join("`, `", doc.synonyms())).append("`)");
            }
            body.append("\n\n").append(doc.blurb()).append("\n\n")
                    .append(details(doc.detail()));
            if (!doc.example().isBlank()) {
                body.append("```\n").append(strip(doc.example())).append("\n```\n\n");
            }
        }
        groups.forEach((group, body) -> out.append("### ").append(group).append("\n\n").append(body));
        return out.toString();
    }

    private static String variableTables(List<VarDoc> variables) {
        StringBuilder out = new StringBuilder("## Every variable\n\n"
                + "Variables are read inside `$...$` in command text, and by bare name "
                + "inside expressions (`#if (client.health < 5)`). All are read-only "
                + "except the ones you make with `#set`/`#local`/`#setdefault`.\n\n");

        Map<String, StringBuilder> byCategory = new LinkedHashMap<>();
        for (VarDoc doc : variables) {
            if (doc.name().matches(".*\\.[xyz]$") && doc.name().chars().filter(c -> c == '.').count() > 1) {
                continue; // .x/.y/.z components ride on their vec's entry
            }
            byCategory.computeIfAbsent(doc.category(), c -> new StringBuilder())
                    .append("| `").append(doc.name()).append("` | ").append(escapePipes(doc.blurb())).append(" |\n");
        }
        byCategory.forEach((category, rows) -> out.append("### ").append(category).append("\n\n")
                .append("| Variable | Meaning |\n|---|---|\n").append(rows).append('\n'));

        out.append("### Computed subjects\n\n")
                .append("These roots resolve members at read time rather than from a fixed list:\n\n");
        for (SubjectDocs.Subject subject : SubjectDocs.ALL) {
            if (subject.enumerated()) {
                continue;
            }
            out.append("#### `").append(subject.name()).append(".*`\n\n")
                    .append(subject.blurb()).append("\n\n");
            if (subject.gate() != null) {
                out.append("Available when: ").append(strip(subject.gate())).append("\n\n");
            }
            out.append(details(subject.detail()))
                    .append(examples(subject.exampleSimple(), subject.exampleComposed()));
        }
        return out.toString();
    }

    // ------------------------------------------------------------- helpers

    private static String details(List<String> detail) {
        if (detail.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : detail) {
            out.append("- ").append(strip(line)).append('\n');
        }
        return out.append('\n').toString();
    }

    private static String examples(String simple, String composed) {
        StringBuilder out = new StringBuilder("```\n");
        out.append(strip(simple)).append('\n');
        if (composed != null && !composed.isBlank() && !composed.equals(simple)) {
            out.append(strip(composed)).append('\n');
        }
        return out.append("```\n\n").toString();
    }

    /** Drop the §-colour codes the in-game surfaces use; Markdown wants plain text. */
    static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    private static String escapePipes(String text) {
        return strip(text).replace("|", "\\|");
    }

    // -------------------------------------------------------------- prose

    private static final String HEADER = """
            # Tupenter scripting reference

            Everything the scripting layer can do, in one file. Tupenter runs
            entirely on your client: it expands what you type into ordinary
            vanilla commands and sends those, so nothing here requires a
            server-side mod. What it *can* do is still bounded by your
            permissions — `/setblock` needs cheats or op, exactly as if you had
            typed it.

            The enumerations below (directives, functions, parameter types,
            variables) are generated from the same registries the in-game help
            reads, so they are always complete and current for this build.
            `/tupenter reference` copies this document to your clipboard.

            """;

    private static final String MODEL = """
            ## The model

            A line you send is a **chain of statements** separated by `&&`. Each
            statement independently decides what it is:

            | Starts with | It is | Example |
            |---|---|---|
            | `/` | a command | `/time set day` |
            | `#` | a directive (never sent to the server) | `#wait 2s` |
            | anything else | plain chat | `hello` |

            Three pieces of syntax carry all the meaning:

            | Syntax | Meaning |
            |---|---|
            | `$...$` | an **expression** — evaluated, and its value substituted |
            | `#word` | a **directive** — an instruction to Tupenter itself |
            | `(...)` | **grouping** — only after a directive; literal everywhere else |

            ### Two worlds

            The single most useful idea: text lives in one of two worlds.

            - **Command world** is literal. `/say hello` sends the word `hello`.
              `$...$` is the door into the other world.
            - **Expression world** is code. `client.health` is a variable read,
              `"hello"` is a string literal, `+` adds.

            So quotes say *what a thing is*, and position says *what it becomes*:

            ```
            /say hello              → sends "hello" (command world, literal)
            /say $hello$            → error: no variable named hello
            /say $"hello"$          → sends "hello" (expression world, a string)
            #if (hello == "x")      → reads a variable named hello
            ```

            Inside a directive's condition or a `#set` right-hand side you are
            *already* in expression world, so bare names are variable reads and
            `$...$` is unnecessary (though harmless — it always evaluates its
            inside, everywhere).

            """;

    private static final String STATEMENTS = """
            ## Chaining and grouping

            `&&` separates statements and runs them in order:

            ```
            /time set day && /weather clear && say ready
            ```

            Parentheses group a directive's body, and groups nest and may
            contain their own `&&` chains:

            ```
            #if (client.health < 6) (/echo &clow health && /effect give @s regeneration 5 1)
            ```

            A statement that is exactly one `$...$` marker re-dispatches its
            result by form — if the value is a string starting with `/`, it runs
            as a command:

            ```
            #set cmd = "/tp @s ~ ~10 ~" && $cmd$
            ```

            """;

    private static final String EXPRESSIONS = """
            ## Expressions

            ### Values

            | Kind | Examples | Notes |
            |---|---|---|
            | number | `5`, `-2.5`, `1/3` | exact rationals — no floating-point drift |
            | string | `"hello"`, `"minecraft:stone"` | double quotes only |
            | boolean | `true`, `false` | |
            | vec3 | `"10 64 -20"`, `vec(1, 0, 0)` | a string of three numbers *is* a vec3 |
            | list | `blockset(#minecraft:wool)`, `range(1, 5)` | index with `nth(list, i)` |

            Numbers stay exact: `1/3` is a third, not `0.333…`, and stays exact
            through arithmetic. Values only round when something demands a whole
            number (a block coordinate) or you ask with `round`/`floor`/`ceil`.

            ### Operators

            | Precedence | Operators | Notes |
            |---|---|---|
            | highest | `(...)`, function calls, `.field` | |
            | | `^` | power |
            | | unary `-`, `!` | |
            | | `*`, `/`, `%` | `%` is floored modulo — `-1 % 16` is `15` |
            | | `+`, `-` | `+` also concatenates when either side is a string |
            | | `<` `<=` `>` `>=` | |
            | | `==` `!=` | |
            | | `&&` | boolean AND — **only inside `(...)`**, see Gotchas |
            | | `\\|\\|` | boolean OR |
            | lowest | `cond ? a : b` | ternary |

            Suffixes: `s` multiplies by 64 (stacks), so `$3s$` is `192`.

            ### Markers

            `$...$` evaluates and substitutes. Write `\\$` for a literal dollar
            sign. Adjacent markers concatenate, which is how you build text:

            ```
            /echo $floor(t / 60)$:$t % 60 < 10 ? "0" : ""$$t % 60$
            ```

            In **auto-detect** math mode, bare arithmetic outside NBT braces is
            also solved (`/give @s stick 32+4` gives 36). Explicit `$...$`
            always works regardless of mode.

            """;

    private static final String VARIABLES_PROSE = """
            ## Variables you make

            | Directive | Lifetime | Prints a notice |
            |---|---|---|
            | `#local name = value` | this line only | no |
            | `#set name = value` | the session | yes (muted in tick scripts) |
            | `#setdefault name = value` | the session, **created only if absent** | yes |

            - `#local` is the workhorse: compute once, use many times, save
              nothing. A `#local r = rand(1, 5)` keeps the *same* roll everywhere
              it is read on that line.
            - `#set` persists for the session (cleared on join, configurable).
              `/tupenter var save <name>` promotes one to the config file
              permanently; `/tupenter var delete <name>` removes it.
            - `#setdefault` guarantees a variable exists: it creates it if absent
              and **leaves it alone if present**. That makes it the tunable-knob
              directive — a script seeds `#setdefault radius = 5` once, and a
              later `#set radius = 8` (even typed in chat while the script is
              running) wins, with the script reading the new value on its next
              pass.
            - Compound assignment works: `+= -= *= /= %=`.
            - The `$` around the name is optional: `#set i = i + 1` and
              `#set $i$ = $i + 1$` are the same.

            `/tupenter vars` lists everything currently set, live.

            """;

    private static final String CONTROL_FLOW = """
            ## Control flow

            ```
            #repeat 5 (/say tick $i$)                       $i$ counts 1..5
            #for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~)
            #foreach $m$ in (zombie | skeleton) (/summon $m$ ~ ~ ~)
            #foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)
            #while (client.health < 20) (/effect give @s regeneration 1 1 && #wait 3s)
            #if (cond) (...) #elseif (cond) (...) #else (...)
            ```

            Loops are capped by **Max Loop Iterations** (default 100). A loop
            that *sends* each iteration paces itself across ticks instead and is
            not bound by that cap. `#while` requires lazy execution (on by
            default).

            """;

    private static final String TIMING = """
            ## Timing and lazy execution

            `#wait 10t` / `1.5s` / `2m` / `3d` (ticks, seconds, minutes, MC days;
            a bare number is ticks) pauses a script without freezing the game.
            Append `realtime` for wall-clock instead of game time — a gametime
            wait speeds up under `/tick sprint` and stops under `/tick freeze`.

            Scripts run **lazily**: each statement's markers evaluate *when that
            statement runs*, not when you pressed Enter. So a read after a
            `#wait` sees the world as it is then:

            ```
            /attribute @s minecraft:jump_strength base set 30 && #wait 2t && /tp @s $client.target.blockpos$
            ```

            Re-running a line cancels its own still-running instance (resend =
            restart, not stack). Different lines run concurrently.
            `/tupenter abort` stops everything; `/tupenter running` lists what is
            active.

            """;

    private static final String CUSTOM_COMMANDS = """
            ## Custom commands

            ```
            /customcommand add <name> <signature> = <body>
            ```

            A custom command is a real command with real autocomplete. The stored
            form is `name <params> "description" = body`:

            ```
            /customcommand add blink <maxdistance:int=100> "teleport to where you're looking" = #silent #local hit = raycast(maxdistance) && #if (hit == "miss") (/tp @s ^ ^ ^$maxdistance$) #else (/tp @s $hit$)
            ```

            - Parameters are `<name:type>`, optionally `=default`. A parameter
              with a default is optional. See *Every parameter type* below.
            - Tuple types bind components too: `<p:pos>` gives the body `p` and
              `p.x`/`p.y`/`p.z`.
            - The `"description"` is shown on missing arguments and in
              `/customcommand <name>`.
            - Names may not shadow a Tupenter command or a built-in function.
              Shadowing a *vanilla* command warns but is allowed.

            Manage them with `/customcommand list|update|remove`, or in
            Mod Menu → Tupenter → Commands.

            """;

    private static final String CUSTOM_FUNCTIONS = """
            ## Custom functions

            ```
            /customfunction add <name> <signature> = <expression>
            ```

            A custom function computes a **value** you call inside `$...$`,
            alongside the built-ins:

            ```
            /customfunction add midpoint <a:vec3> <b:vec3> = scale(vadd(a, b), 0.5)
            /tp @s $midpoint(client.pos, spawn)$
            ```

            - A body may be one expression, or a statement block using `#set`,
              `#local`, `#setdefault`, `#if`, `#for`, `#foreach`, `#while` and
              `#return`. The value is your `#return`, or the last expression.
            - `#set` inside a function is function-local — it never touches the
              session.
            - Functions may recurse (bounded by the loop cap).
            - A body can never send a command or `#wait`; that is what
              `/customcommand` is for.
            - Names may not shadow a built-in function.

            """;

    private static final String TICK_SCRIPTS = """
            ## Tick scripts

            Mod Menu → Tupenter → **Scripts** holds lines that run every tick.
            Each armed line is wrapped in a loop — literally:

            ```
            #while (true) (YOUR LINE && #wait 1t)
            ```

            That single fact explains their behaviour:

            - It is **one long-running script**, not a fresh parse each tick, so
              `#set`/`#setdefault` values persist across ticks (a counter keeps
              counting) and `#wait` composes naturally.
            - Session variables written by a tick loop are committed every tick,
              so they show up in `/tupenter vars` and can be retuned live.
            - `#set` notices are muted (they would print 20×/second), and tick
              scripts never touch resend history.
            - A body can only send **Max Commands Per Tick** commands (default
              48); a body that emits more spills across ticks.
            - An error reports once and pauses that script until it is edited or
              re-enabled.

            **Arming is per world.** Global scripts are shared definitions you
            arm world-by-world; This World's scripts exist only where you made
            them. A world you never configured runs nothing.

            Give a script a name by starting it with `name =`, then toggle it
            from chat with `/tupenter scripts enable|disable <name>`.

            ### Events are edges

            There is no callback system — a script *is* a loop, so an "event" is
            a one-tick boolean you test:

            ```
            #if (client.just_died) (/echo died at $client.blockpos$)
            #if (client.keypress.g) (/effect give @s night_vision infinite 0 true)
            ```

            `client.just_died`, `client.just_respawned`, `world.just_joined` and
            `client.keypress.<key>` each read true for exactly the tick their
            transition happens.

            """;

    private static final String OUTPUT = """
            ## Output, silence and privacy

            | | |
            |---|---|
            | `/echo <text>` | print to your own chat; nothing is sent |
            | `/echohud <text>` | print above the hotbar; re-sending updates in place |
            | `/calc <expr>` | evaluate and print |
            | `/unroll <line>` | dry-run: show what a line expands to, sending nothing |

            `&`-codes colour `/echo` and `/echohud` output (`&c` red, `&a` green,
            `&7` grey, `&r` reset).

            | Prefix | Effect |
            |---|---|
            | `#silent` | suppress server feedback for the line |
            | `#silent (a && b)` | …or for just part of it |
            | `#norecord` | keep the line out of resend history |
            | `#record` | force it into history |

            """;

    private static final String GOTCHAS = """
            ## Gotchas

            ### `&&` inside an assignment is statement chaining

            The one that bites hardest. `&&` separates statements, so a logical
            `&&` on the right of `#set`/`#local` splits the line instead:

            ```
            #local ok = a() && b()      ← WRONG: two statements; b() is sent as chat
            ```

            Parenthesising the right side does not help. Keep `&&` out of an
            assignment's right side — restructure so the assignment is a single
            expression, or nest `#if`s. Inside a `#if (...)` condition the
            parentheses protect it, so `&&`/`||` there are perfectly fine.

            ### `block()` and unloaded chunks

            `block(x, y, z)` reads your client's copy of the world. A chunk your
            client has never received reads as `"minecraft:void_air"` rather
            than erroring, so test for that when a position may be far away.
            Whether the *server* is ticking a position — what actually governs
            item despawn, mob spawning and crop growth — is a different question,
            answered by `simulated(x, y, z)`.

            ### Fallbacks keep tick scripts alive

            `entity(uuid, field)` throws if the entity is gone. In a loop, pass a
            fallback: `entity(uuid, "pos", client.pos)`. The same applies to any
            read that can fail — an error pauses the whole script.

            ### Guard anything that sends

            An unguarded command in a tick script fires 20×/second. Gate it with
            `#if`, an edge variable, or a `#wait`.

            ### Tick scripts and multiplayer

            Everything Tupenter sends is a command you could have typed. On a
            server without cheats, `/setblock`-style examples simply fail — the
            scripting still works, but stick to what your permissions allow.

            """;
}
