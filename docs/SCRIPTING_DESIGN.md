# Tupenter Scripting Engine — Design Document

Status: draft v3 — 2026-07-10
(v2: dropped `#while`, dropped gamerule silent mode, `@silent`→`#silent`,
`wait`→`#wait`, dynamic variable registry + user variables, packet/burst
semantics spelled out. Mod is unpublished — no backward-compat obligations,
only self-consistency.)
(v3: `#wait` **deferred** — not in scope for now. The executor is built with it
in mind (tick-driven, script instances), but no wait directive ships until the
overlap/concurrency policy is settled. See §6.)
(v4 — 1.1.0: a literal list is spelled `list(...)` and nothing else. Bare
parentheses no longer build one, so `(...)` means grouping everywhere and
`pick` takes commas like every other function. Two separators inside `list`:
commas COMPUTE their arguments, pipes take items as literal text. Also landed:
`blockpos(...)`, an optional `name:type` on `#set`/`#local`/`#setdefault`
reusing the custom-command parameter keywords, and `##` comments.)

This document specifies "enhanced command parsing v2": the evolution of
Tupenter's alias expansion + `&&` chaining + `$...$` math into a small, capped,
client-side scripting layer. Grounded in the current code
(`CommandParsingProcessor`, `CommandMathParser`, `MixinConnection`,
`TupenterModClient` tick loop).

---

## 1. Goals and principles

1. **Two symbols, not a hundred.** The entire mental model:
   - `$...$` — *inline value*: evaluated to text, substituted into the command.
   - `#word` — *structural directive*: controls which commands get sent, how
     many times, and when. **Everything structural starts with `#`** — loops,
     conditionals, waits, silencing, variable assignment.
   - `(...)` — *grouping*, only meaningful where a directive expects it.
   Everything else is literal vanilla text.
2. **Three statement forms, no more.** A line (and each `&&` segment) is one of:
   - `/...` → a command
   - `#...` → a Tupenter directive
   - anything else → a plain chat message
   This holds in every context — typed lines, custom command bodies, tick
   scripts, and evaluated strings. The typed `/` of a command line flavors
   only its first segment. A segment that is *exactly one* `$...$` marker
   evaluates first and its string result re-dispatches by the same forms
   ($cmd$ holding "/tp ~ ~1 ~" runs the command); the whole-line `/$expr$`
   shorthand is the same rule for the entire line. Chat segments inside
   scripts evaluate explicit `$...$` markers (never auto-detect math).
3. **Never clash with vanilla syntax.** `{}` `[]` `"` selectors and NBT remain
   untouched. Parens are literal unless owned by a directive. `$` and `#` are
   effectively unused by vanilla commands.
4. **Not Turing-complete.** No `#while` (cut — see §10). Every loop is bounded
   by its own header; every script has hard caps and a kill switch.
5. **Errors surface before anything is sent, whenever possible.** Parse errors
   and unknown names are caught at Enter-press time and nothing goes out.
   Only genuinely runtime failures can interrupt a script mid-flight (§6).
6. **A script is one user command.** One Enter = one script execution. History
   stores the original typed line; resending re-executes it (randomness
   re-rolls, conditions re-check).

---

## 2. Execution model

### Target pipeline

```
[Enter pressed / resend fires]
        │
        ▼
 MixinConnection intercepts the outgoing command/chat packet
        │
        ▼
 ScriptParser.parse(line)
        │──parse error / unknown name──▶ red local message, NOTHING sent
        ▼
 ScriptExecutor.submit(new ScriptInstance(ast, context))
        ▼
 Client tick loop: executor.tick()
   - advances each running ScriptInstance
   - evaluates $...$ / conditions lazily, right before each send
   - honors #wait, per-tick send budget, loop caps
   - sends final vanilla packets via connection.sendCommand / sendChat
```

- **Interpreter, not macro-expander.** Loops are not unrolled at parse time; a
  `ScriptInstance` holds a small execution stack (node, iteration index,
  variable scope) and steps forward.
- **Lazy evaluation.** `$client.y$` in iteration 4 of a loop sees the player's
  Y *at that moment* (matters when the send budget spreads a loop over ticks).
- **One executor.** The resend system's `messageDelay`/`resendDelay` pacing and
  script execution live in the same tick-driven scheduler. This is also where
  a future `#wait` slots in without rearchitecting.
- **Concurrency.** Without `#wait`, a script normally finishes within one tick;
  only the per-tick send budget can stretch one across ticks, so overlap is
  rare. `maxConcurrentScripts` still caps pathological cases; excess
  submissions are rejected with a local error. The full overlap policy for
  *manual* re-entry (concurrent vs queue vs reject vs restart) is parked with
  `#wait`.
- **Resend gating (decided).** The resend system fires one script execution per
  cycle and does not fire the next cycle until the current instance has
  finished draining (plus `resendDelay`). This generalizes the existing
  `delayTimer` batch-completion pattern with the script as the unit, and it
  means hold/toggle mode never stacks instances of a budget-stretched script.
  For scripts that fit the per-tick budget (the common case), resend behavior
  is tick-for-tick identical to today's.

### Limits (all configurable)

| Limit | Default | On breach |
|---|---|---|
| `maxLoopIterations` (per loop) | 100 | abort script + red error |
| `maxCommandsPerScript` | 1000 | abort script + red error |
| `maxCommandsPerTick` (global) | 16 | remainder deferred to next tick |
| `maxConcurrentScripts` | 8 | reject new script + red error |
| `maxScriptLifetime` | 5 min | abort script + red error |
| alias expansion cap (existing) | 50 | kept as-is |

Kill switch: `/tupenter abort` — stops all running scripts immediately.

---

## 3. Packet and timing semantics

**One vanilla command = one packet, always.** The server has no concept of
`&&`; a chain is *inherently* N packets. What Tupenter controls is *when* they
go out. The unit of intuition is the **burst**: consecutive sends in the same
tick, back-to-back.

| You type | What goes out |
|---|---|
| `/time set day && /clear @s` | 2 packets, same tick, back-to-back (one burst) |
| `#repeat 3 (/say hi)` | 3 packets, one burst |
| `#repeat 200 (/say hi)` | 200 packets, automatically spread ≥13 ticks by the 16/tick budget |
| *(future)* `/a && #wait 5s && /b` | burst of 1, script sleeps in the executor, burst of 1 |

Without `#wait`, everything is one burst; only the per-tick budget can split
it, purely as kick/lag protection. When `#wait` lands later, it becomes the
explicit burst separator ("one burst up until there's a wait").

**Where errors surface** (three tiers):

1. **Parse errors** — bad directive syntax, unmatched `$` or `(...)`, malformed
   expression. Caught at Enter. Nothing sent. Red message with position.
2. **Static name errors** — unknown function (`$randm(1,5)$`), unknown variable
   (`$client.helth$`). The registry (§5) is known at parse time, so these are
   also caught at Enter with nothing sent. This is most typos.
3. **Runtime errors** — division by zero from computed values,
   `$client.target_block$` with no block in reach, rand with min > max where
   the bounds came from variables. These abort the script *at that point*;
   earlier packets have already gone out (unavoidable — they're on the server).
   Red message: what failed and after how many commands.

Consequence for resend: since history stores the original line and every
execution is parse-checked the same way, resending a script behaves exactly
like retyping it — same burst boundaries, fresh rolls, fresh variable reads.

---

## 4. Syntax

### 4.1 Grammar

```
line        := sequence
sequence    := statement ("&&" statement)*
statement   := directive | command | chat
directive   := "#repeat"  expr             group
             | "#for"     var "in" range   group
             | "#foreach" var "in" list    group
             | "#if"      "(" expr ")"     group ["#else" group]
             | "#silent"  [group]                   ; see §6
             | "#set"     var "=" expr              ; see §5.3
group       := "(" sequence ")"
range       := expr ".." expr ["step" expr]
list        := "(" item ("|" item)* ")"    ; literal items, "\|" escapes
command     := "/" raw text with $...$ substitution at send time
chat        := raw text with $...$ substitution at send time
var         := "$" name "$"
```

### 4.2 Tokenizer shielding rules (precedence of interpretation)

1. `$...$` spans are **opaque**: `&&`, `(`, `)`, `|`, `#` inside them belong to
   the expression, never to the splitter.
2. `(` opens a **structural group only where the parser expects one** (after a
   directive header, or as a `#foreach` list). Structural parens nest and
   shield `&&` and `|`. Anywhere else parens are literal (`/say (hi)` untouched).
3. `&&` splits only at depth 0 (outside `$...$` and structural parens).
4. `|` separates items only inside `pick(...)` (where options are
   expressions — quote literal text) and `#foreach` lists (`\|` escapes a
   literal pipe there). Elsewhere `|` is literal.
5. `#word` is a directive only in statement position (line start or right
   after `&&` / inside a group). A `#` mid-text is literal.
6. A stray unmatched `$` in a line containing no other Tupenter syntax is
   literal text; if the line does use script syntax, it's a parse error.

---

## 5. Expressions and variables

One expression engine serves inline `$...$`, directive conditions, and
directive arguments. `CommandMathParser`'s exact-rational core is the seed.

### 5.1 Types

- **number** — exact rational, printed as plain decimal (current behavior).
- **string** — quoted `"..."` in expressions; produced by string variables and
  `pick(...)`.
- **boolean** — from comparisons; valid only in conditions/ternaries.
  Substituting a bare boolean into a command is an error (catches mistakes).
- **list** — from `range(...)` / foreach lists; only consumable by `#foreach`
  and `pick`.

### 5.2 Operators (loosest first)

| Operators | Meaning |
|---|---|
| `cond ? a : b` | ternary |
| `\|\|`, `&&` | boolean or/and (safe: `$...$` spans are opaque to the splitter) |
| `!` | not |
| `==` `!=` `<` `<=` `>` `>=` | comparison |
| `+` `-` | arithmetic; `+` concatenates if either side is a string |
| `*` `/`, implicit multiplication | existing |
| unary `-`, `s` stack suffix (×64) | existing |
| literals, parens, functions, variables | |

`$...$` unification (2026-07-18): inside expression world, `$...$` wraps a
FULL expression (it used to accept only a bare name), so the command-world
intuition "$...$ evaluates its inside" holds everywhere — `#set i = $i+ 1$`
works. Digits-only content (`$1$`) stays a positional-parameter reference,
the same carve-out command markers use. `#set`/`#local` accept bare names
(the `$` decoration is optional) and compound assignment `+= -= *= /= %=`.

Functions, phase 1: `int` `float` (existing), `rand(min,max)` (integer,
inclusive), `pick(a, b, c)` (options are full expressions, so picks nest
and compute; quote literal text — `pick("say hi", "say nah")`),
`range(start, stop[, step])` (inclusive), `list(a | b | c)` / `list(1, 2, 3)`,
`blockpos(...)`. Phase 2: `floor` `ceil` `round`
`abs` `min` `max` `randf` `len`.

### 2.5 Lazy execution and `#wait`

The original engine evaluated everything at Enter-press ("eager unroll").
That made `#wait` meaningless — a state read after a wait would still see
the pre-wait world (the /blink failure). The fix is **send-time
evaluation**, implemented without rewriting the walker:

- The unchanged Walker runs on a **virtual thread** (`LazyWalk`) with a
  strict request/response rendezvous (two SynchronousQueues). The walker
  blocks before computing each statement until the executor pulls, so there
  is **no lookahead**: a statement's `$...$` markers evaluate at the moment
  it is sent. From the render thread's perspective the walk is synchronous —
  it waits on the handoff while the walker computes one statement.
- `#wait 10t | 1.5s | 3d` (or bare ticks, capped at 72000 = one hour) emits
  a WAIT stream item; the executor parks that script for N ticks without
  blocking others. Works anywhere a statement works — chains, groups,
  loops, custom command bodies.
- **What stays eager**: trivial lines (single plain statement — byte-
  identical fast path), `/unroll` dry runs (which label waits and note that
  the dry run baked later values), tick scripts (they re-parse every tick
  anyway, and eager parse errors feed the report-once fault tracking), and
  the whole test suite's eager options.
- **Overlap policy**: submitting a line cancels a still-running instance of
  the *same source line* (rapid-fire resend = restart, not stack);
  different lines run concurrently under `maxConcurrentScripts`;
  `/tupenter abort` interrupts everything, including parked walkers.
  Tick-script resubmission treats a running same-source instance as success
  (a waiting tick script doesn't stack 20×/s).
- **Error timing**: statement-scan (structure) errors still surface at
  Enter; evaluation errors surface when the failing statement is reached,
  and sends already made stay sent — like a dying mcfunction.
- `#set` notices travel the stream as NOTICE items; session commits happen
  when the script finishes.
- Escape hatch: the Lazy Execution toggle restores full eager evaluation
  (#wait then still delays, but with Enter-press values).
- Known edge: `#record`/`#norecord` written *inside an alias body* no
  longer retro-applies under lazy execution (history is recorded at
  submit); put the prefix on the typed line instead.

Tag sets: `blockset("#tag")` / `itemset("#tag")` resolve a registry tag to
its member-id list via a `TagResolver` hook on `EvalContext` (the client
backs it with the connection's synced registries; the script package stays
MC-free and tests stub it). `rand(list)` picks a uniform member, so
`rand(blockset("#minecraft:logs"))` composes, as does `#foreach ... in
blockset(...)`. Unavailable resolver (no live world) and unknown tags are
loud errors, not empty picks.

### 5.3 The variable registry (nothing hardcoded in the parser)

The parser knows only "this token is a variable reference." What names exist
and what they resolve to lives in a **registry** the evaluator queries:

```java
public interface VariableProvider {
    Set<String> names();                       // for parse-time validation + tab-complete
    Value resolve(String name, EvalContext ctx); // called lazily at send time
}

VariableRegistry
 ├─ ScopeStack            // innermost first: loop vars, custom-command params
 ├─ SessionVariableStore  // user vars from #set
 ├─ ClientVariableProvider// "client.*" — a Map<String, Function<Minecraft,Value>>
 └─ (future providers: server.*, config.*, ...)
```

Adding `$client.biome$` is one line of registration data — no parser change,
and it automatically shows up in parse-time validation, error suggestions
("unknown variable client.helth — did you mean client.health?"), and future
tab-completion.

Initial `client.*` set: `x y z` (exact), `bx by bz` (block ints), `yaw pitch
health food air name dimension held_item target_block` (target_block = "x y z"
of the crosshair block; runtime error if none in reach).

### 5.4 User-defined variables: `#set` and persistence

```
#set $spawn$ = "100 64 -200"
/tp @s $spawn$
#set $count$ = $count$ + 1        (self-reference allowed; error if unset)
```

Persistence tiers:

| Tier | Lifetime | How |
|---|---|---|
| **Script** | one execution | loop vars, custom-command params — automatic |
| **Session** | until disconnect | `#set` — the default; cleared on JOIN following the same `resetOnNewSession` config that clears history |
| **Persistent** | forever (config file) | explicit opt-in: `/tupenter var save <name>` promotes a session var to the config file (stored like aliases); `/tupenter var list / delete` to manage |

Rationale: `#set` inside scripts stays lightweight and consequence-free;
persistence is a deliberate act so the config file doesn't silently accumulate
every scratch variable. (Open question 4 if a different split feels better.)

Name resolution order: script scope → session → persistent → providers.
Writing (`#set`) always targets the session store unless the name is a script
scope var (loop vars are read-only; error).

---

## 6. Directives

### `#repeat N (body)`
N evaluated once at loop entry. Implicit `$i$` = 1..N (1-based).
`/weather clear && #repeat 5 (/time add 100 && /say Tick $i$)`

### `#for $x$ in a..b [step c] (body)`
Inclusive bounds, default step 1, negative step allowed when a > b.
`#for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~)`

### `#foreach $x$ in <list> (body)`
The header is just an expression that has to produce a list — `list(a | b | c)`
written in place, `range(...)`, a registry set, or a variable holding one. The
loop variable's `$...$` is optional in the header (it is a declaration); reading
it in the body is an ordinary marker and is not.
`#foreach $mob$ in list(zombie | skeleton | creeper) (/summon $mob$ ~ ~ ~)`

### `#if (cond) (body) [#else (body)]`
Gatekeeper, evaluated when execution reaches it. Inline value selection is the
ternary's job: `/tp @s ~ ~$client.y > 60 ? 10 : 0$ ~`

### `#silent` / `#silent (body)`
Local suppression only (reuses the `handleSystemChat` machinery, scoped to this
script's lifetime — no permissions, your screen only). The gamerule-sandwich
approach is rejected (races, needs OP, global side effects).
- Bare `#silent` at line start: silences the whole script.
- `#silent (body)`: silences just that group — composes like any directive.

### Deferred: `#wait <duration>`
Syntax reserved (`20t` / `1.5s` / `500ms`, statement position), but not shipping
yet. It's the one directive that makes scripts overlap themselves in time,
which drags in the whole overlap-policy question (concurrent vs queue vs
reject vs restart; serial resend integration; shared `#set` interleaving;
`#silent` window overlap). The executor is shaped so `#wait` is a small,
additive node when we decide to do it.

### Removed: `#while`
Cut from the design. Realistic conditions almost never resolve before the
iteration cap, so it's a foot-gun with little payoff. `#for`/`#repeat` +
live-evaluated `#if` inside the body cover the practical cases.
The interpreter architecture supports adding it later if a real use case shows up.

---

## 7. Parameterized custom commands

```
/customcommand add smite <target:player> = /execute at $target$ run summon lightning_bolt
/customcommand add panic <level:int> <type:word> = #repeat $level$ (/summon $type$ ~ ~ ~)
```

- Types: `int`, `float`, `string` (default — word or quoted), `word`, `text`
  (greedy, last only), `player` (word + online-player suggestions),
  `selector`, `<name:a,b,c>` choice lists, coordinate tuples (`pos`, `vec3`,
  `column_pos`, `rotation`, `angle` — `~` resolved at parse time against
  `client.*`, tuples also bind per-component `$p.x$` etc.), `time`
  (`10t/1.5s/3d` → ticks), `dimension`, `color`, `id`, and registry-backed
  `item` / `block` (need a `CommandBuildContext`; built per-connection, so
  their trees fall back to plain strings only if no registries exist).
- Body references params by name (`$target$`) or position (`$1$`).
- No declared params → current behavior (extra args appended verbatim).
- `<name:type=default>` makes a param optional. Defaults may hold `$...$`
  expressions, evaluated at invoke time with earlier params in scope.
  Binding is try-parse: a strictly-typed optional that doesn't match the
  next token is skipped (default bound) and the token falls through to the
  next param; the Brigadier tree grows matching skip-branches. Loose types
  (string/word/text) always consume, so optional loose params belong last.
- Each alias gets a real Brigadier tree → vanilla autocomplete.
- **Dynamic re-registration** on add/update/remove — kills the "relaunch
  for autocomplete" limitation. `add` refuses existing names (clickable
  "update instead" suggestion); `update` refuses missing ones.

---

## 8. Config

Under the existing `enhancedCommandParsingEnabled` master switch:

- `scriptDirectivesEnabled` (bool, default true)
- `maxLoopIterations`, `maxCommandsPerScript`, `maxCommandsPerTick`,
  `maxConcurrentScripts` (defaults per §2)
- `persistentVariables` (list, managed via `/tupenter var`)
- Existing `commandChainingEnabled`, `numberMathMode` unchanged.
- Cleanup (no published users — just delete): `gracePeriod`,
  `rememberLastValid`, `numberMathEnabled` legacy shim.

---

## 9. Architecture

New package `net.tupenter.script`:

| Class | Role |
|---|---|
| `ScriptLexer` | tokenizes per §4.2 shielding rules |
| `ScriptParser` | AST: `SequenceNode`, `CommandNode`, `ChatNode`, `RepeatNode`, `ForNode`, `ForeachNode`, `IfNode`, `WaitNode`, `SilentNode`, `SetNode` |
| `ExpressionEvaluator` | v2 engine (grown from `CommandMathParser`, keeps `Rational`) |
| `VariableRegistry` / `VariableProvider` | §5.3; `ClientVariableProvider` is the only MC-coupled part |
| `EvalContext` | scope stack, RNG, registry handle |
| `ScriptInstance` | one running script: AST + execution stack + counters |
| `ScriptExecutor` | tick scheduler: send budget, waits, caps, abort, silent windows |

Changed:
- `MixinConnection` → intercept, parse, submit, cancel. No synchronous sends.
- `TupenterModClient` → tick calls `executor.tick()`; resend queue submits
  through the executor (unified pacing).
- `CommandParsingProcessor` → dissolves into `ScriptParser` (alias expansion
  becomes a parse-time pre-pass, same 50-cap).
- `CommandMathParser` → auto-detect path unchanged (numeric-only, soft-fail);
  marked path delegates to `ExpressionEvaluator`.

Testing: lexer/parser/evaluator are pure Java behind `EvalContext` → JUnit +
Gradle test source set. First tests in the project; the shielding rules are
the highest-regression-risk logic and get locked in during Phase 0.

---

## 10. Build order

- **Phase 0 — Foundation.** ✅ DONE. AST + executor replace the string pipeline
  at feature parity (aliases, `&&`, both math modes, soft-fail rules). JUnit
  harness. No new user-visible features.
- **Phase 1 — `#silent` + `/tupenter abort`.** ✅ DONE. Line-prefix form only
  (`#silent (...)` group form comes with structural parens in Phase 4).
  Chat packets starting with a known directive word are now also intercepted.
  (`#wait` deferred — see §6.)
- **Phase 2 — Expression engine v2.** ✅ DONE. Strings, comparisons, ternary,
  true/false literals, `rand`, `pick`, `\$` escaping; hard-fail for `$...$`
  markers with parse-time reporting; `/calc` upgraded. (Note: expressions are
  values, not lazy ASTs — both ternary branches evaluate; revisit with
  variables in Phase 3.)
- **Phase 3 — Variable registry + `#set` + session vars.** ✅ DONE. Variables
  are bare identifiers (or `$wrapped$`) in expressions; `#set` works as a
  statement anywhere in a chain, evaluated in statement order at parse time,
  with commit-to-session only when the whole line parses; unknown names get
  did-you-mean suggestions; `/tupenter vars` lists the session. (`client.*`
  yet to come — the registry ships with just user vars.)
- **Phase 4 — `#repeat` + `(...)` grouping + `$i$`.** ✅ DONE.
- **Phase 5 — `#if` / `#else`.** ✅ DONE.
- **Phase 6 — `#foreach`, `range()`, `#for`.** ✅ DONE.
- **Phase 7 — `client.*` provider + persistent variables + `/tupenter var`.** ✅ DONE.
- **Phase 8 — Parameterized custom commands + Brigadier + dynamic re-registration.** ✅ DONE.
  (Positional `$1$..$n$: pure-digit marker content resolves as a variable when
  one is bound, else as a number literal.)
- **Phase 9 — Docs.** ✅ DONE. README rewrite + `/tupenter help`.

**Implementation note (phases 4-6):** loops and conditionals UNROLL EAGERLY at
parse time rather than interpreting lazily — caps are enforced during unroll,
so an oversized loop is rejected before anything sends, and a whole line still
parses-or-fails atomically (including #set rollback). Without #wait this is
observably identical to lazy evaluation except for client.* variables read
during a budget-stretched drain. The switch to a lazy interpreter happens
with #wait. `#silent (group)` scoped form also remains future work.

---

## 11. Open questions

1. `$i$` base: 1-based (recommended) vs 0-based.
2. `..` / `range` inclusivity: inclusive both ends (recommended) vs exclusive stop.
3. Boolean ops spelling: `&&`/`||` (recommended — spans are opaque) vs `and`/`or`.
4. Variable persistence split: `#set` = session + explicit `/tupenter var save`
   (recommended), or should `#set` write straight to disk?
5. Resend replays the original script — re-rolls randomness, re-reads
   variables (recommended, matches "history stores what you typed") — or the
   resolved output of the last run?
