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
| | `\|\|` | boolean OR |
| lowest | `cond ? a : b` | ternary |

Suffixes: `s` multiplies by 64 (stacks), so `$3s$` is `192`.

### Markers

`$...$` evaluates and substitutes. Write `\$` for a literal dollar
sign. Adjacent markers concatenate, which is how you build text:

```
/echo $floor(t / 60)$:$t % 60 < 10 ? "0" : ""$$t % 60$
```

In **auto-detect** math mode, bare arithmetic outside NBT braces is
also solved (`/give @s stick 32+4` gives 36). Explicit `$...$`
always works regardless of mode.

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

## Every directive

### Variables

#### `#set`

a session variable — lives until you leave the world

```
#set name = value
```

- Bare name is normal: #set x = 5. The name is x — $x$ is just x with EXPLICIT wrapping. Both the target (#set x = / #set $x$ =) and reads in expression world (#if (x > 3), x + 1, function args) take the bare name.
- $...$ is the door from command/chat text — there text is literal, so /give @s stick $x$ needs the wrapping to substitute the value (plain 'stick x' is the letter x). Same reason #repeat bodies write $i$.
- Compound forms: #set x += 1 (also -= *= /= %=) · dotted groups organize: #set hitlist.bob = "wanted".
- Session-scoped: cleared when you leave, unless kept with /tupenter var save <name> (the composed example makes a permanent home).
- Prints a notice when it sets — #silent mutes it. For a value that shouldn't outlive the line, use #local instead.

```
#set x = rand(1,10) && /give @s stick $x$ && /echo got $x$!
#set home = client.blockpos && /tupenter var save home
```

#### `#local`

the workhorse: compute ONCE, use many times, save NOTHING

```
#local name = value
```

- Line-scoped and silent: nothing written to the session, no notice printed — a tick script using #local stays stateless between runs.
- One evaluation: every later read sees the SAME value — a rand doesn't re-roll, a raycast doesn't re-cast (the composed example reads two fields off one hit).
- Bare name is normal — #local hit, then hit in the condition and hit inside entity(...); $r$ only where it substitutes into command text.
- The choosing rule: #local by default; #set only when the value must OUTLIVE the line.

```
#local r = rand(1, 5) && /give @s stick $r$ && /echo gave $r$ (same roll, both places)
#local hit = raycast_entity(30) && #if (hit != "miss") (/echo $entity(hit, "name")$ · $entity(hit, "health")$ hp)
```

#### `#setdefault`

guarantee a variable exists: create it if absent, keep it as-is if present

```
#setdefault name = value
```

- Already defined means session, saved, or live — an existing value always wins.
- A tunable knob: #setdefault seeds the value once and then leaves it, so a later #set — even one you type in chat while a tick loop is running — wins and the loop reads the NEW value on its next pass. It never stomps your change back to the default. Rule of thumb: #set what the script OWNS, #setdefault what the PLAYER tunes.
- Bare name throughout: runs reads bare in the #set arithmetic — $runs$ only where it lands in command text.
- The stateful-command idiom: first run initializes, every run advances — paste it anywhere, no separate setup line.
- Only track what the game DOESN'T tell you: for live state read the real variable — $world.frozen$, $client.held.id$, $client.riding$. A self-tracked flag drifts the moment anything else changes it (or you rejoin), and then your toggle does the opposite of what you meant.
- Create-once-across-sessions: #setdefault x = 0 && /tupenter var save x.

```
#setdefault maxy = 80 && /echo building up to $maxy$
#setdefault runs = 0 && #set runs = runs + 1 && /echo run $runs$ this session
```

### Loops

#### `#repeat`

run the body N times — $i$ counts 1..N

```
#repeat N (body)
```

- $i$ is provided — 1-based, so it reads naturally in chat output.
- Paced automatically: a body that sends or waits spreads over ticks (Max Commands Per Tick) — /tupenter running shows it, /tupenter abort stops it.

```
#repeat 5 (/say Tick $i$!)
#repeat 3 (/summon minecraft:zombie ~ ~ ~ && #wait 1s)
```

#### `#for`

count a whole-number range, inclusive — direction automatic

```
#for $x$ in a..b [step s] (body)
```

- Inclusive both ends, and 10..1 counts down without being told.
- Bounds are expressions: #for $y$ in client.blockpos.y..client.blockpos.y+10 works.

```
#for $x$ in 1..10 step 2 (/summon minecraft:zombie ~$x$ ~ ~)
#for $i$ in 0..8 (/echo hotbar $i$: $slot("hotbar." + i, "id")$)
```

#### `#foreach`

walk options you wrote, or any LIST value

```
#foreach $x$ in (a | b | c) (body) · #foreach $x$ in <list> (body)
```

- Two sources: (a | b | c) options written in place, or any list — range(1, 10), registry sets, entities(radius), client.effects.
- The set pairing is the classic: every member of a #tag, one command each.

```
#foreach $m$ in (zombie | skeleton) (/summon $m$)
#foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)
```

#### `#while`

repeat while the condition holds — re-checked each pass, runs across ticks

```
#while (condition) (body)
```

- $i$ counts iterations — don't #set your own counter.
- Pace it with #wait — the condition re-reads live state each pass, so the simple example is a regen drip that stops itself at 20 hp.
- #while (true) + /echohud is the live-HUD idiom; /tupenter abort <id> ends it.
- Runaway guard: a loop that sends or waits runs as long as it needs; only a loop that does NEITHER hits Max Loop Iterations.

```
#while (client.health < 20) (/effect give @s minecraft:regeneration 1 1 && #wait 3s)
#while (true) (/echohud &7light &f$client.light$ &7· &f$client.speed$&7 b/s && #wait 1t)
```

### Conditions

#### `#if`

branch on a condition — client-side, instant, with a real #else

```
#if (cond) (then) #elseif (cond) (…) #else (…)
```

- Conditions are expressions — the $ $ around names is optional there, and any function composes in.
- #elseif chains, #else closes — they continue an #if, they can't start a line.
- Absent-not-wrong: transient world state that's missing (no crosshair target, unloaded UUID) reads FALSE in a condition instead of erroring — that's what lets a tick script poll client.target.health bare.

```
#if (client.pos.y > 60) (/say high) #elseif (client.pos.y > 30) (/say mid) #else (/say low)
#if (client.target.hit == "block" && client.target.block == "minecraft:diamond_ore") (/echo &bfound it)
```

### Timing

#### `#wait`

pause the script mid-line — everything else keeps running

```
#wait 10t / 1.5s / 2m / 3d [realtime]
```

- Units: t = ticks, s = seconds, m = minutes, d = days · max 72000t (one real-time hour) · works in chains, groups, loops, and custom command bodies.
- Lazy evaluation: $...$ after a #wait reads state at RESUME time — /attribute ... && #wait 2t && /tp @s $client.target.blockpos$ sees the world after the boost landed.
- Two clocks: default counts WORLD ticks (sprints under /tick sprint, halts under /tick freeze and pause); add realtime for wall-clock no matter the TPS.

```
/say ready && #wait 3s && /say GO
#wait 5m realtime && /echohud &ecows are ready to be fed!
```

### Functions

#### `#return`

set a function's value and unwind — function bodies only

```
#return <expression>
```

- A function's value is your #return — or the last expression when no #return fires (both examples use the trailing-expression fallback as the "else").
- Function bodies only: anywhere else it errors by design — the word is reserved so the error can say why.

```
/customfunction add clamp <x:float> = #if (x > 100) (#return 100) && #if (x < 0) (#return 0) && x
/customfunction add sign <x:float> = #if (x > 0) (#return 1) && #if (x < 0) (#return -1) && 0
```

### Prefixes & output

#### `#silent`

hide command feedback — the whole line, or just one group

```
#silent <line> · #silent (group)
```

- Two shapes: prefix the LINE, or wrap one (group) mid-chain — the composed example mutes the give but not the say.
- Also mutes #set notices. Prefixes combine: #norecord #silent /say hi.

```
#silent /time set day
#silent (/give @s minecraft:stick 64) && /say restocked
```

#### `#norecord`

run the line but keep it OUT of resend history

```
#norecord <line>
```

- For one-offs you don't want the resend key (R) to repeat.
- Bake it into a command: start a custom command's BODY with it and the command decides for itself — /customcommand add oneshot = #norecord /kill @e[type=item] — so you never type the prefix. Applies to a bare invocation of that command.

```
#norecord /spawnpoint
#norecord #silent /gamemode creative
```

#### `#record`

the inverse: record even when message tracking is OFF (bypasses the filter)

```
#record <line>
```

- The opt-in when tracking is disabled but THIS line should be resendable.
- Bake it into a command: a body starting with #record makes a command you plan to repeat always resendable — /customcommand add nextwave = #record /summon minecraft:zombie ~ ~ ~ — no prefix to remember.

```
#record /tp @s ~ ~10 ~
#record #silent /fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 minecraft:glass
```

#### `#stage`

put the line INTO resend history WITHOUT running it

```
#stage <line>
```

- Load the resend key: press R when you actually want it — an escape hatch, a panic teleport, a prepared burst.

```
#stage /tp @s 0 100 0
#stage #repeat 10 (/summon minecraft:zombie ~ ~ ~)
```

#### `#unstage`

remove the newest n entries (default 1) from resend history

```
#unstage [n]
```

- Reports what the next resend is after removing, so you always know what R will do.

```
#unstage
#unstage 3
```

#### `#chat`

send a normal chat message that EVALUATES its $...$

```
#chat <message>
```

- The opt-in: plain chat leaves $...$ literal on purpose — #chat is how a real, recorded chat message gets evaluation.

```
#chat my coords are $client.pos$
#chat rolled a $rand(1, 20)$!
```

#### `#pid`

run a line under an id YOU pick — name it for /tupenter running and abort

```
#pid N <line> · #pid N replace <line>
```

- Stable ids: /tupenter abort 7 always means THAT loop, whatever else is running.
- Refuses a live id unless you add replace — the composed example swaps the running HUD for a new one in place.

```
#pid 7 #while (true) (/echohud &f$client.speed$&7 b/s && #wait 2t)
#pid 7 replace #while (true) (/echohud &f$client.light$&7 light && #wait 2t)
```

## Every built-in function

Functions are called inside `$...$` (or anywhere an expression is expected, such as a `#if` condition or the right side of `#set`).

### Math

#### `int(x)`

the whole part of x — truncates toward zero

- int(3.9) = 3 and int(-3.9) = -3 — the decimal part is dropped, no rounding.
- Not floor: floor always goes DOWN, so they differ on negatives: floor(-3.9) = -4, int(-3.9) = -3.
- Takes text too: like float, it's an explicit conversion — int("42") is 42, so a substr or an NBT string tag can become a whole number.

```
/calc int(3.9)
/echo $int(client.health / 2)$ full hearts left
```

#### `float(x)`

x as a number — turns numeric text into math material

- Text to number: substr results and NBT STRING tags are text — float("3.5") is the NUMBER 3.5, ready for * and <. (Numeric NBT tags already come back as numbers; this is for the ones that don't.)
- The only coercion in the language: int and float are the explicit conversions, so everywhere ELSE text stays text — which is why "3" + 1 concatenates instead of quietly adding.
- Errors loudly on non-numeric text, so a bad read fails at the source, not three math steps later.

```
/calc float("3.5") * 2
/calc float(substr("level_42", 6)) + 1
```

#### `abs(x)`

distance from zero: abs(-5) = 5

- Differences: abs(a - b) is "how far apart" without caring which is bigger.

```
/calc abs(-5)
/echo $abs(client.pos.y - 63)$ blocks from sea level
```

#### `floor(x)`

round DOWN to a whole number (toward -infinity)

- floor(2.9) = 2 and floor(-2.1) = -3 — always down, which is exactly how block coordinates work.
- Self-parity: floor(client.pos.y) equals client.blockpos.y — block positions are floored, not truncated.

```
/calc floor(-2.1)
/echo you're in chunk $floor(client.blockpos.x / 16)$, $floor(client.blockpos.z / 16)$
```

#### `ceil(x)`

round UP to a whole number (toward +infinity)

- "How many containers": ceil(items / 64) is the stack count that FITS everything — the classic use.

```
/calc ceil(2.1)
/echo $ceil(200 / 64)$ stacks hold 200 items
```

#### `round(x)`

the nearest whole number

- Display-friendly: echo positions and health without decimal noise. When the DIRECTION matters, use floor or ceil instead.

```
/calc round(2.6)
/echo $round(client.pos.x)$ $round(client.pos.y)$ $round(client.pos.z)$
```

#### `min(a, b, ...)`

the smallest of any number of values

- Any count: min(a, b, c, d) works — not just pairs.
- Clamp: max(low, min(high, x)) pins x into [low, high] — min and max nest into a clamp.

```
/calc min(3, 7)
/echo weakest stat: $min(client.health, client.food)$
```

#### `max(a, b, ...)`

the largest of any number of values

- Any count: max(a, b, c, d) works — not just pairs.
- Clamp: max(low, min(high, x)) pins x into [low, high] — the composed example clamps 120 to 100.

```
/calc max(3, 7)
/calc max(0, min(100, 120))
```

#### `sqrt(x)`

square root (a negative input errors)

- Distance: sqrt(dx^2 + dy^2 + dz^2) — ^ binds tighter than *, so the distance formula reads naturally.

```
/calc sqrt(9)
/echo $round(sqrt(client.pos.x^2 + client.pos.z^2))$ blocks from x=0 z=0
```

#### `sin(deg)`

sine, in DEGREES (Minecraft rotations are degrees)

- Degrees, not radians: client.yaw and client.pitch feed straight in.
- Circles: x = r*cos(a), z = r*sin(a) walks a circle as a sweeps 0..359 — the composed example draws one in particles.

```
/calc sin(90)
#for $a$ in 0..359 step 30 (/particle minecraft:flame ~$5 * cos(a)$ ~1 ~$5 * sin(a)$)
```

#### `cos(deg)`

cosine, in DEGREES (Minecraft rotations are degrees)

- Degrees, not radians: client.yaw and client.pitch feed straight in.
- The composed example rebuilds client.look.x by hand — how yaw and pitch become a look vector.

```
/calc cos(0)
/echo $-sin(client.yaw) * cos(client.pitch)$ equals $client.look.x$
```

#### `tan(deg)`

tangent, in DEGREES

- Look-ahead: looking DOWN at pitch p, level ground sits about 1.62/tan(p) blocks ahead — eye height over slope.

```
/calc tan(45)
/echo ground hit ~$round(1.62 / tan(client.pitch))$ blocks ahead (while looking down)
```

### Text

#### `len(x)`

length of text, or element count of a list

- Dual: len("creeper") = 7 · len(blockset(#minecraft:wool)) = 16 — one function, both kinds.

```
/calc len("minecraft")
/echo $len(entities(16))$ entities within 16 blocks
```

#### `trim(text)`

strip whitespace from both ends

- Edges only: inner spaces stay — trim("  a b  ") is "a b".

```
/calc trim("  hi  ")
/calc len(trim("  hi  "))
```

#### `upper(text)`

ALL CAPS

- For display — for case-insensitive COMPARISONS, lower both sides instead (see lower).

```
/calc upper("stop")
/echo $upper(replace(client.dimension, "minecraft:", ""))$!
```

#### `lower(text)`

all lowercase

- Case-insensitive compare: lower(a) == lower(b) matches regardless of capitalization.

```
/calc lower("STOP")
/calc lower("Zombie") == "zombie"
```

#### `substr(text, start[, count])`

part of a text — 0-based start, clamped; omit count for "to the end"

- Clamped, not fussy: a start or count past the end just gives what's there — no error.
- Namespace tip: vanilla ids are "minecraft:" + 10 characters in, so substr(id, 10) strips the default namespace (replace(...) is the general tool).

```
/calc substr("minecraft:oak_log", 10)
/calc substr("creeper", len("creeper") - 3)
```

#### `replace(text, find, new)`

swap every occurrence of find — literal text, not a pattern

- All occurrences, plain-text matching. The text to find can't be empty.

```
/calc replace("oak_log", "oak", "birch")
/echo holding $replace(client.held.id, "minecraft:", "")$
```

### Random

#### `rand(min, max) · rand(list)`

a random whole number (inclusive both ends), or one element of a list

- Inclusive: rand(1, 64) can yield both 1 and 64.
- rand(list): a uniform pick from ANY list — registry sets, range(...), client.effects.
- rand vs pick: rand samples a range or one list; pick chooses between options YOU wrote out.
- Fresh every resend: history keeps the original $...$, so a resend re-rolls.

```
/echo $rand(1, 64)$
/give @s $rand(itemset(#minecraft:planks))$ 16
```

#### `randf(min, max)`

a random decimal in [min, max)

- Half-open: min can come out, max can't — the standard convention. Use rand for whole numbers.

```
/echo $randf(0, 1)$
/tp @s ~$randf(-0.5, 0.5)$ ~ ~$randf(-0.5, 0.5)$
```

#### `pick(a | b | c)`

one of the options you wrote, chosen at random

- Options are full expressions separated by a single top-level | (|| is still boolean-or) — they nest and compute: pick(rand(1,5) | client.pos.y).
- Quote literal text: pick("say hi" | "say nah").
- pick vs rand: a set is ONE value, so pick(entityset(...)) is one option holding a whole list — rand(entityset(...)) is how you draw one member.

```
/echo $pick("heads" | "tails")$
/summon $pick("zombie" | "skeleton" | rand(entityset(#minecraft:skeletons)))$
```

### Lists

#### `range(start, stop[, step])`

an inclusive whole-number list; step optional, direction automatic

- Inclusive both ends: range(1, 5) = 1 2 3 4 5 · range(10, 0, -2) counts down by 2.
- No step? direction is inferred — range(5, 1) already counts down.
- Built for #foreach — and rand(range(0, 100, 10)) is a random multiple of 10.

```
/calc range(1, 5)
#foreach $y$ in range(60, 100, 10) (/setblock ~ $y$ ~ minecraft:glass)
```

#### `nth(list, i)`

element i of a list, 0-based

- 0-based: nth(list, 0) is the FIRST element; out of range errors and tells you the size.
- Cycling: nth(list, i % len(list)) walks a list forever as i grows — the composed example steps through the wools, one per resend.

```
/calc nth(range(10, 20), 0)
#setdefault $i$ = 0 && /setblock ~ ~-1 ~ $nth(blockset(#minecraft:wool), i % 16)$ && #set $i$ = i + 1
```

#### `contains(list, value)`

is value in the list? — membership by ==

- Membership by ==: numbers match numbers, text matches text; a differently-typed element simply doesn't match.

```
/calc contains(range(1, 5), 3)
#if (contains(blockset(#minecraft:logs), client.target.block)) (/echo that's a log)
```

#### `indexof(list, value)`

0-based position of the first == match, or -1

- The inverse of nth: nth(list, indexof(list, v)) gets v back; -1 means "not there" — gate with it.
- The composed example numbers the wool you're aiming at (and -1 for anything that isn't wool).

```
/calc indexof(range(10, 20), 15)
/calc indexof(blockset(#minecraft:wool), client.target.block)
```

### Registry sets

#### `blockset(members...)`

block ids from tags and/or ids — one deduped list

- A tag's members as a list: blockset(#minecraft:logs) — typing # inside the parens TAB-COMPLETES the tags.
- A concrete id is a one-element set (blockset("stone")), and several members UNION — tags and ids mix freely, deduped, first-seen order.
- No argument = the whole registry: every block in the game.
- Draw ONE with rand(blockset(...)) — not pick(...), which treats the whole set as a single option.
- One string also works — members split on spaces or commas — which is how a set survives the trip through a custom-command argument.
- Needs a live world: ids and tags come from the server you're on.

```
/calc blockset(#minecraft:logs)
#foreach $b$ in blockset("oak_planks", #minecraft:wool) (/give @s $b$)
```

#### `itemset(members...)`

item ids from tags and/or ids — one deduped list

- A tag's members as a list: itemset(#minecraft:planks) — typing # inside the parens TAB-COMPLETES the tags.
- A concrete id is a one-element set (itemset("stick")), and several members UNION — tags and ids mix freely, deduped, first-seen order.
- No argument = the whole registry: every item in the game.
- Draw ONE with rand(itemset(...)) — not pick(...), which treats the whole set as a single option.
- One string also works — members split on spaces or commas — which is how a set survives the trip through a custom-command argument.
- Needs a live world: ids and tags come from the server you're on.

```
/calc itemset(#minecraft:planks)
/give @s $rand(itemset(#c:ores))$ 8
```

#### `effectset(members...)`

effect ids from tags and/or ids — one deduped list

- A tag's members as a list: effectset(#...) — typing # inside the parens TAB-COMPLETES the tags.
- A concrete id is a one-element set (effectset("speed")), and several members UNION — tags and ids mix freely, deduped, first-seen order.
- No argument = the whole registry: every status effect — rand(effectset()) is a mystery potion.
- Draw ONE with rand(effectset(...)) — not pick(...), which treats the whole set as a single option.
- One string also works — members split on spaces or commas — which is how a set survives the trip through a custom-command argument.
- Needs a live world: ids and tags come from the server you're on.

```
/calc len(effectset())
/effect give @s $rand(effectset())$ 30 1
```

#### `entityset(members...)`

entity type ids from tags and/or ids — one deduped list

- A tag's members as a list: entityset(#minecraft:skeletons) — typing # inside the parens TAB-COMPLETES the tags.
- A concrete id is a one-element set (entityset("minecraft:zombie")), and several members UNION — tags and ids mix freely, deduped, first-seen order.
- No argument = the whole registry: every entity type in the game.
- Draw ONE with rand(entityset(...)) — not pick(...), which treats the whole set as a single option.
- One string also works — members split on spaces or commas — which is how a set survives the trip through a custom-command argument.
- Needs a live world: ids and tags come from the server you're on.

```
/calc entityset(#minecraft:skeletons)
/summon $rand(entityset(#minecraft:skeletons))$ ~ ~ ~
```

### Vectors

#### `vec(x, y, z)`

three numbers as one vec3 value ("x y z")

- The literal-position spelling: vec(0, 64, 0) beats "0 64 0" because the components get to be EXPRESSIONS: vec(x, 64, z).
- Feeds anything that takes a vec3: raycast origins and directions, block(...), component(...).

```
/calc vec(0, 64, 0)
/echo ground below: $raycast(client.eye_pos, vec(0, -1, 0), 100)$
```

#### `component(v, axis)`

one component of a vec3 — axis is "x", "y", or "z"

- For COMPUTED vecs: a spelled-out address has a dotted form (client.pos.x) — component is for RESULTS: component(raycast(100), "y").
- Exact: keeps the full-precision number, no rounding.
- Gate sentinels: "miss" isn't a vec3 — check == "miss" before pulling components off a raycast.

```
/calc component(vec(1, 2, 3), "y")
/echo aim height: $component(raycast(100), "y")$
```

#### `vadd(a, b)`

two vec3s added component-wise

- Point plus offset: vadd(position, offset) moves a point — the offset is usually scale(direction, distance).
- The inverse is vsub. Together with scale they build any vector expression.

```
/calc vadd(vec(1, 2, 3), vec(10, 20, 30))
/particle minecraft:flame $vadd(client.eye_pos, scale(client.look, 2))$
```

#### `vsub(a, b)`

b subtracted from a, component-wise

- Direction from a to b: vsub(target, here) points from here to target — normalize it for a heading.
- Displacement: mag(vsub(a, b)) is another way to spell dist(a, b).

```
/calc vsub(vec(10, 10, 10), vec(1, 2, 3))
/echo heading to it: $normalize(vsub(client.target.blockpos, client.blockpos))$
```

#### `scale(v, s)`

every component times the number s

- Heading into a step: scale(direction, distance) turns a unit heading into an actual move.
- Negative flips it: scale(v, -1) is the reverse vector.

```
/calc scale(vec(1, 2, 3), 10)
/tp @s $vadd(client.pos, scale(client.look, 5))$
```

#### `mag(v)`

the length of a vector

- Length from the origin: mag(v) is dist(v, vec(0, 0, 0)) — the size of a displacement or velocity.
- Not for points: the gap between two POSITIONS is dist(a, b).

```
/calc mag(vec(3, 4, 0))
/echo moving $mag(client.motion)$ blocks/tick
```

#### `dist(a, b)`

straight-line distance between two points

- The everyday one: how far apart two vec3s are — no more sqrt((a.x-b.x)^2 + ...) by hand.
- Points, not directions: for the length of a motion or displacement vector, that's mag(v).

```
/calc dist(vec(0, 0, 0), vec(3, 4, 0))
/echo $dist(client.pos, world.spawn)$ blocks from spawn
```

#### `normalize(v)`

the same direction, scaled to length 1

- A pure direction: strips the length, keeps the heading — the building block for "N blocks that way".
- Zero stays zero: normalize(vec(0, 0, 0)) is "0 0 0", not an error, so a still player never faults a tick script.

```
/calc normalize(vec(0, 0, 5))
/tp @s $vadd(client.pos, scale(normalize(client.look), 3))$
```

#### `dot(a, b)`

the dot product — a·b, a single number

- How aligned two vectors are: positive = same-ish way, 0 = perpendicular, negative = opposite.
- The "am I facing it" test: dot(look, directionToThing) > 0 means it's ahead of you.
- Exact: keeps full precision (no sqrt involved).

```
/calc dot(vec(1, 0, 0), vec(1, 0, 0))
#if (dot(client.look, vsub(client.target.blockpos, client.blockpos)) > 0) (/echo it's in front of me)
```

#### `cross(a, b)`

the cross product — a vector perpendicular to both

- A sideways vector: perpendicular to both inputs — the way to build a plane around a direction (the rainbow-ring trick).
- Order matters: cross(a, b) points opposite cross(b, a).
- Exact: full precision, no rounding.

```
/calc cross(vec(1, 0, 0), vec(0, 1, 0))
/setblock $vadd(client.blockpos, cross(client.look, vec(0, 1, 0)))$ minecraft:torch
```

### World

#### `block(x, y, z) · block("x y z")`

the block id at a position, from your client's synced world

- Both spellings: three numbers, or one vec3 — block(client.target.blockpos) reads what you're aiming at.
- Decimals floor (block coordinates always do). A chunk your client hasn't received reads as "minecraft:void_air" — test for that to tell "out of view" from a real block.
- Id only: no states or NBT — "minecraft:oak_stairs" whichever way it faces.
- No round trip: this is /execute if block folded into the expression world, with a real #else.

```
/calc block(0, 64, 0)
#if (block(client.blockpos) == "minecraft:water") (/echo swimming in it)
```

#### `simulated(x, y, z) · simulated("x y z")`

is the server ticking entities there? true / false — never errors

- What despawn/spawning obey: a chunk ticks entities (items despawn, mobs spawn, crops grow) only within the server's SIMULATION distance of you — often smaller than render, so a chunk you can still SEE may be frozen.
- Only your seat: it uses the server's simulation distance (the F3 value) and YOUR position. On a shared server it can't see a distant teammate who is also keeping the chunk ticking.

```
/calc simulated(client.blockpos)
#if (!simulated(grave)) (/echo your items are frozen — that chunk isn't simulated)
```

#### `raycast(dist) · raycast(origin, dir, dist)`

where your look (or any ray) hits — "x y z", or "miss"

- One argument: cast from your eyes along your look, up to dist blocks — your crosshair as a value, past normal reach.
- Three arguments: any ray — origin and dir are vec3s (dir is normalized for you): raycast(client.eye_pos, client.look, 60) is the long-hand of raycast(60).
- Hits what your crosshair hits: collidable blocks, sub-block accurate; passes through grass and fluids.
- "miss" never throws — gate with == "miss".

```
/echo aiming at $raycast(100)$
/particle minecraft:flame $raycast(30)$
```

#### `raycast_block(dist)`

the block ID your look hits, or "miss"

- raycast tells you WHERE, raycast_block tells you WHAT — same cast, different answer.
- Spelled-out sibling: client.target.block is the crosshair's block id at normal reach; raycast_block takes any distance.

```
/echo looking at $raycast_block(100)$
#if (raycast_block(6) == "minecraft:diamond_ore") (/echo &bdiamonds!)
```

### Entities

#### `entity(selector, field[, fallback])`

one field of one entity — the subject as a value

- Selector: "self", "target", or a UUID — from raycast_entity, entities, nearest_entity, or client.target.uuid.
- Fields: type, uuid, name, health, pos, blockpos, nbt.<path> — the SAME vocabulary as client.<field> and client.target.<field>. This is the computed-subject spelling of that vocabulary.
- Fallback: a third argument replaces ANY failed read — absent NBT (an undamaged item has no minecraft:damage), no target, a UUID that left render distance. That's what keeps a tick script from faulting.

```
/echo $entity("target", "health")$ hp
/echo nearest: $entity(nearest_entity(16), "type")$
```

#### `keys(selector, path)`

the child ADDRESSES of an NBT node — a compound's keys, a list's indices

- Addresses, not values: a compound gives its KEYS (sorted), a list gives its indices. Feed one back through entity(...) to read it: entity("self", "nbt.Inventory." + i + ".id").
- Absent = empty: a path that isn't there yields an EMPTY list, not an error — an unenchanted item has no minecraft:enchantments key, and the useful answer is "none". So len() is 0 and contains() is false, safe in a tick script.
- What it unlocks: compounds keyed by ID, where you can't know the members in advance — item components, enchantments, a mob's nbt.Brain.memories. It's also the only way to loop an NBT LIST: #foreach $i$ in keys("self", "nbt.Inventory").
- Path form is entity(...)'s: "nbt.<path>", or just "nbt" for the whole entity. Browse the tree with /tupenter dump.

```
/echo $len(keys("self", "nbt.equipment.chest.components.minecraft:enchantments"))$ enchantments
#if (contains(keys("self", "nbt.equipment.chest.components.minecraft:enchantments"), "minecraft:mending")) (/echo &bmending)
```

#### `raycast_entity(dist)`

the UUID of the entity your look hits, or "miss"

- Feed it to entity(...): entity(raycast_entity(30), "health") — aim-at-anything, past normal reach.
- Gate: == "miss" when nothing is on the line (or use entity's fallback, like the composed example).

```
/echo $raycast_entity(30)$
/echo $entity(raycast_entity(30), "name", "nothing")$ in the crosshair
```

#### `entities(radius[, type])`

UUIDs of everything within radius blocks — a list

- Spherical, centered on you, read from the client's synced world (~render distance).
- type filters: "zombie" or "minecraft:zombie". No matches = an empty list, so len(...) and #foreach compose instead of erroring.

```
/echo $len(entities(16))$ nearby
#foreach $e$ in entities(8, "minecraft:zombie") (/echo zombie at $entity(e, "blockpos")$)
```

#### `nearest_entity(radius[, type])`

the closest entity's UUID within radius, or "miss"

- entities(...) finds ALL, nearest_entity finds ONE — same radius and type rules, plus the "miss" sentinel to gate on.

```
/echo $nearest_entity(16)$
/echo closest drop: $entity(nearest_entity(16, "minecraft:item"), "pos", "none")$
```

### Slots

#### `slot(slot, field)`

one field of one of your slots — the slot as a value

- Slot names are /item replace names: hotbar.0-8, inventory.0-26 (0-8 is the TOP row), armor.head/chest/legs/feet, weapon.mainhand/offhand.
- Fields: id, count, durability, max_durability. An empty slot reads "empty" and 0 — never an error.
- Dotted counterpart: a spelled-out slot reads better as client.slot.<slot>.<field> (it tab-completes); slot(...) is for COMPUTED slots, like the loop in the composed example. client.held.* / client.offhand.* are the hand shorthands. All one reader — the spellings can't disagree.

```
/echo $slot("armor.chest", "durability")$
#for $i$ in 0..8 (/echo hotbar $i$: $slot("hotbar." + i, "id")$)
```

## Every parameter type

Used in custom-command and custom-function signatures as `<name:type>`, optionally with `=default`.

### Text

#### `string`

a word, or "anything quoted" — what a bare <name> means

- The default: <msg> and <msg:string> are the same thing.
- Quotes let anything through — spaces, selectors: /announce "back in 5". Unquoted, it grabs one word.
- Loose: it accepts any next token, so an OPTIONAL string can't be skipped mid-command — strict types can (see /customcommand help optionals).

```
/customcommand add announce <msg> = #chat [!] $msg$
```

#### `word`

one strict token: letters, digits, _-.+ only

- Strict on purpose: no quotes, no selectors, no spaces — when you want a clean identifier and nothing else.
- Need @-selectors? use <n:selector>. Need spaces? <n:string> quoted, or <n:text> for the rest of the line.

```
/customcommand add label <tag:word> = /echo tagged: $tag$
```

#### `text`

the rest of the line, spaces and all — must be last

- Greedy: everything after the earlier params is yours, no quotes needed.
- Must be last — nothing can come after it, and an optional text can never be skipped.

```
/customcommand add shout <what:text> = #chat $what$!!!
```

### Numbers

#### `int`

a whole number

- Validated at the prompt: /waves abc zombie errors before anything runs.
- Strictly typed, so an optional <r:int=5> can be skipped mid-command (see /customcommand help optionals).

```
/customcommand add waves <count:int> <mob:entity> = #repeat $count$ (/summon $mob$ ~ ~ ~)
```

#### `float`

a decimal number

- Accepts whole numbers too — int is for when a decimal would be WRONG, float for when it's welcome.

```
/customcommand add slowmo <rate:float=5> = /tick rate $rate$
```

#### `time`

a duration — 10t / 1.5s / 2m / 3d — binds as TICKS

- Units: t = ticks, s = seconds, m = minutes, d = days; a plain number is ticks.
- Binds the tick count (1.5s → 30), so it feeds #wait and math directly.

```
/customcommand add later <delay:time> <cmd:text> = #wait $delay$ && $cmd$
```

### Positions & rotation

#### `pos` (also `vec3`)

a PRECISE position: three decimal coords, ~ works

- Binds a tuple: $p$ = "x y z" joined, plus $p.x$ $p.y$ $p.z$ for math.
- pos is precise, blockpos is whole — the same split as client.pos / client.blockpos.
- vec3 is a synonym — the honest keyword when the value is a direction, not a place.

```
/customcommand add mark <p:pos=~ ~ ~> = /particle minecraft:flame $p$
```

#### `blockpos` (also `block_pos`)

a block position: three WHOLE coords, ~ and targeted-block tab-complete

- Binds a tuple: $at$ = "x y z" joined, plus $at.x$ $at.y$ $at.z$.
- Tab-complete offers the block you're looking at — same as vanilla /setblock.

```
/customcommand add drill <at:blockpos> = /setblock $at$ minecraft:air
```

#### `column_pos` (also `column`)

an x z column: two whole coords, ~ works

- Binds a tuple: $c$ = "x z" joined, plus $c.x$ $c.z$ — no y, that's the point.

```
/customcommand add chunkof <c:column_pos=~ ~> = /echo chunk $floor(c.x / 16)$ $floor(c.z / 16)$
```

#### `rotation`

yaw pitch: two decimals, ~ works

- Binds a tuple: $r$ = "yaw pitch" joined, plus $r.yaw$ $r.pitch$.

```
/customcommand add face <r:rotation> = /tp @s ~ ~ ~ $r$
```

#### `angle`

one yaw angle, ~ works — binds a plain number

- A number, not a tuple — ~180 means "my yaw plus 180", the about-face.

```
/customcommand add spin <a:angle=~180> = /tp @s ~ ~ ~ $a$ ~
```

### Players & entities

#### `player`

a player name, tab-completed from who's online

- A NAME, not a selector — for @-selectors use <n:selector>.

```
/customcommand add greet <who:player> "wave at someone" = /me waves at $who$
```

#### `selector`

an @-selector, validated and tab-completed — no quotes needed

- Full selector syntax: @e[type=!player,limit=1] parses as ONE argument, brackets and all.
- Binds the selector text — the SERVER resolves it when your body's command runs.

```
/customcommand add zap <t:selector> = /execute at $t$ run summon minecraft:lightning_bolt
```

#### `entity`

an entity type id, /summon-style tab-complete

- The registry backs the tab-complete — every entity type the server knows.

```
/customcommand add spawn3 <mob:entity> = #repeat 3 (/summon $mob$ ~ ~ ~)
```

### Ids & sets

#### `id` (also `resource`)

any namespaced id: minecraft:stone, mymod:thing

- The general form — when item/block/entity are too specific. No registry check, just id syntax.

```
/customcommand add noted <x:id> = /echo noted: $x$
```

#### `item`

an item id + optional [components], registry tab-complete

- What /give takes — [components] ride along intact.

```
/customcommand add gimme <it:item> <n:int=1> = /give @s $it$ $n$
```

#### `block`

a block id + optional [state], registry tab-complete

- What /setblock takes — [state] rides along intact.

```
/customcommand add carpet <b:block> = /fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 $b$
```

#### `itemset`

an item id OR a #item_tag — pairs with itemset(...)

- One param, both shapes: /lucky diamond and /lucky #minecraft:planks both work — itemset($pool$) reads either back as a set.
- Tab-completes items AND #tags.

```
/customcommand add lucky <pool:itemset> = /give @s $rand(itemset(pool))$
```

#### `blockset`

a block id OR a #block_tag — pairs with blockset(...)

- One param, both shapes: a concrete block or a whole #tag — blockset($of$) reads either back as a set.
- Tab-completes blocks AND #tags.

```
/customcommand add sampler <of:blockset> = #foreach $b$ in blockset(of) (/give @s $b$)
```

#### `dimension`

a dimension id, tab-completed from the worlds the client knows

- What /execute in takes — minecraft:overworld, the_nether, the_end, plus modded.

```
/customcommand add visit <d:dimension> = /execute in $d$ run tp @s 0 100 0
```

#### `color`

one of the 16 chat colors, tab-completed

- Binds the color NAME (red, aqua, ...) — vanilla's /team modify color vocabulary.

```
/customcommand add team <c:color> = /echo joining the $c$ team
```

### Fixed choices

#### `choice`

your own fixed list — written as the options themselves

- No keyword: ANY comma list after the colon is a choice — <dim:to_overworld,to_nether> — tab-completed, anything else rejected.
- Strictly typed, so an optional choice can be skipped mid-command; a sentinel option like =any makes "omitted" mean something (see /customcommand help optionals).

```
/customcommand add mode <m:on,off> = /echo mode is $m$
```

#### `bool` (also `boolean`)

true/false, tab-completed — binds a real boolean for #if and ternaries

- A real boolean, not text — #if ($on$) works directly, no == "true" needed.
- Strictly typed, so <g:bool=false> is skippable mid-command: /launch snowball true.

```
/customcommand add nv <on:bool=true> = #if ($on$) (/effect give @s night_vision infinite 0 true) #else (/effect clear @s night_vision)
```

## Every variable

Variables are read inside `$...$` in command text, and by bare name inside expressions (`#if (client.health < 5)`). All are read-only except the ones you make with `#set`/`#local`/`#setdefault`.

### Position

| Variable | Meaning |
|---|---|
| `client.pos` | where you are, precise — a vec3 "x y z" (+ .x/.y/.z) |
| `client.blockpos` | the block you're in — whole coords (+ .x/.y/.z) |
| `client.yaw` | horizontal facing, degrees |
| `client.pitch` | vertical look angle, degrees — down is positive |
| `client.facing` | the compass direction you face: north/south/east/west |
| `client.eye_pos` | your eyes' precise vec3 — a ray origin (+ .x/.y/.z) |
| `client.look` | your unit look direction — a ray dir (+ .x/.y/.z) |
| `client.dimension` | the dimension you're in |

### Environment

| Variable | Meaning |
|---|---|
| `client.biome` | the biome at your feet |
| `client.light` | combined light level where you stand, 0-15 |
| `client.light_block` | block-light only (torches, lava, ...) |
| `client.light_sky` | sky-light only (before darkness and weather) |
| `client.chunk_x` | your chunk's x |
| `client.chunk_z` | your chunk's z |

### Movement

| Variable | Meaning |
|---|---|
| `client.speed` | how fast you're moving, blocks/sec (full 3D) |
| `client.speed_xz` | horizontal speed, blocks/sec |
| `client.motion` | your velocity vec3, blocks/sec (+ .x/.y/.z) |
| `client.on_ground` | standing on something? |
| `client.sneaking` | sneaking? |
| `client.sprinting` | sprinting? |
| `client.swimming` | in the swimming pose? |
| `client.flying` | creative flight active? |
| `client.gliding` | elytra deployed? |
| `client.fall_distance` | blocks fallen so far |
| `client.riding` | in/on a vehicle? — the gate for client.vehicle.* |

### Vitals & stats

| Variable | Meaning |
|---|---|
| `client.health` | your health — 20 is full hearts |
| `client.max_health` | your health cap |
| `client.absorption` | yellow absorption hearts |
| `client.food` | the hunger bar, 0-20 |
| `client.saturation` | hidden saturation behind the hunger bar |
| `client.air` | air supply while submerged — 300 is full |
| `client.armor` | armor points, 0-20 |
| `client.xp_level` | your experience level |
| `client.xp_progress` | progress to the next level, 0..1 |
| `client.effects` | your active effect ids — a LIST for #foreach |

### Hazards

| Variable | Meaning |
|---|---|
| `client.in_water` | touching water? |
| `client.underwater` | eyes submerged? |
| `client.in_lava` | touching lava? |
| `client.on_fire` | burning? |

### Session

| Variable | Meaning |
|---|---|
| `client.name` | your player name |
| `client.gamemode` | your game mode |
| `client.ping` | your latency, ms |
| `client.fps` | frames per second |
| `client.uuid` | your UUID, dashed text |
| `client.uuid_nbt` | your UUID as an NBT int-array — for Owner-style fields |
| `client.selected_slot` | which hotbar slot is selected, 0-8 |

### World

| Variable | Meaning |
|---|---|
| `world.difficulty` | the difficulty setting |
| `world.time` | time of day in ticks, 0-23999 |
| `world.day` | the day count |
| `world.raining` | raining? |
| `world.thundering` | thundering? |
| `world.moon_phase` | the moon phase, 0-7 (0 = full) |
| `world.dimension` | this world's dimension id |
| `world.spawn` | the world spawn — "x y z" (+ .x/.y/.z) |
| `world.time_total` | total world ticks — never wraps |
| `world.min_y` | the lowest buildable y |
| `world.max_y` | the highest buildable y |
| `world.tickrate` | the configured /tick rate (usually 20) |
| `world.frozen` | is /tick freeze on? |
| `world.stepping` | mid /tick step? |
| `world.key` | this world's scripts identity key (server:<ip> / world:<folder>) |

### Events

| Variable | Meaning |
|---|---|
| `client.just_died` | true for the one tick your health hits 0 — the death edge |
| `client.just_respawned` | true the tick you come back alive after the death screen |
| `world.just_joined` | true the first pass after you enter a world/server — the join edge |

### Players

| Variable | Meaning |
|---|---|
| `players.count` | how many players are online |
| `players.list` | their names — a LIST for #foreach |

### Real time

| Variable | Meaning |
|---|---|
| `real.hour` | the hour, 0-23 |
| `real.minute` | the minute, 0-59 |
| `real.second` | the second, 0-59 |
| `real.day` | day of the month |
| `real.month` | the month, 1-12 |
| `real.year` | the year |
| `real.day_of_week` | monday..sunday, lowercase |
| `real.timestamp` | unix time in seconds |

### Computed subjects

These roots resolve members at read time rather than from a fixed list:

#### `client.target.*`

whatever's under your crosshair — block or entity

Available when: client.target.hit — "block", "entity", or "miss"

- Block fields: blockpos (+ .x/.y/.z, where it is) · block (what it is).
- Entity fields: type, uuid, name, health, pos, blockpos, nbt.<path> — the same vocabulary as client.<field> and entity(uuid, ...).
- Wrong-kind reads error in value position, but read FALSE in an #if/#while condition — a tick script can poll client.target.health bare, no gate needed.
- Normal reach only — for aim-at-anything distance, raycast_entity(dist) + entity(...).

```
/echo aiming at $client.target.block$
#if (client.target.hit == "entity") (/echo $client.target.name$: $client.target.health$ hp)
```

#### `client.vehicle.*`

what you're riding — the full entity vocabulary

Available when: client.riding

- Fields: type, uuid, name, health, pos, blockpos, nbt.<path> — identical to client.target.* and entity(uuid, ...).
- Not riding? value reads error loudly; conditions read false — same absent-not-wrong rule as the crosshair.

```
/echo riding $client.vehicle.type$
#if (client.riding && client.vehicle.health < 10) (/echohud &cyour mount is hurt!)
```

#### `client.held.*`

your main hand — id, count, durability, max_durability

- A named shorthand for client.slot.weapon.mainhand — same reader, so the two spellings can't disagree.
- Empty hand: id = "empty", numbers = 0 — never an error. Non-damageable items read durability 0.

```
/echo holding $client.held.id$
#if (client.held.max_durability > 0 && client.held.durability < 50) (/echohud &c$client.held.id$ is about to break!)
```

#### `client.offhand.*`

your off hand — id, count, durability, max_durability

- A named shorthand for client.slot.weapon.offhand — same reader as client.held.*.
- Empty hand: id = "empty", numbers = 0 — never an error.

```
/echo offhand: $client.offhand.id$
#if (client.offhand.id != "minecraft:totem_of_undying") (/echohud &egrab a totem!)
```

#### `client.slot.*`

any of your slots, by /item replace name

- Address: client.slot.<slot>.<field> — slot names are hotbar.0-8, inventory.0-26 (0-8 is the TOP row), armor.head/chest/legs/feet/body, weapon.mainhand/offhand; fields are id, count, durability, max_durability. Both halves tab-complete.
- Computed slot? that's the function twin: slot(slot, field) — the composed example walks the hotbar.
- Empty slot: id = "empty", numbers = 0 — never an error.

```
/echo chest: $client.slot.armor.chest.durability$
#for $i$ in 0..8 (/echo hotbar $i$: $slot("hotbar." + i, "id")$)
```

#### `client.nbt.*`

your entity's raw NBT tree — Mojang's spelling, any path

- The escape hatch: everything the client has synced, addressed by dotted path — numeric segments index lists. client.target.nbt.<path> reads the crosshair entity the same way.
- Capitalization signals ownership: nbt.Health is Mojang's field in Mojang's casing; the lowercase direct fields (client.health) are the mod's stable API.
- Tab-completes the LIVE tree one level at a time · browse it all with /tupenter dump.
- Absent paths error: NBT omits defaulted values (an undamaged item has no minecraft:damage) — the entity(sel, path, FALLBACK) form is the safe read, like the composed example.
- Not client.nbt.Inventory: that's the SAVE format — a compacted list of non-empty stacks, so Inventory.0 is "my first stack", not slot 0. Use client.slot.* for slots.

```
/echo $client.nbt.Health$
/echo $entity("self", "nbt.equipment.chest.components.minecraft:damage", 0)$
```

#### `client.key.*`

keyboard state, held RIGHT NOW — a tick script IS a keybind

- Address: client.key.<name> — a BIND name first (jump, sneak, attack, hotbar.1 — follows your controls and modded binds), else a physical key (g, space, f6).
- Arrows: up_arrow/down_arrow/left_arrow/right_arrow — bare left/right are the strafe binds.
- All false while a screen is open (chat, inventory, menus) — keys mean gameplay keys.
- Held vs pressed: this is "is it down"; the one-shot edge is client.keypress.<name>.

```
/echo jump held: $client.key.jump$
#if (client.key.sneak && client.key.sprint) (/echohud &7both held)
```

#### `client.keypress.*`

the single tick a key goes DOWN — the one-shot edge

- True for exactly one tick per press — the natural trigger for a tick-script action (the composed example is a blink-to-crosshair on G).
- Same names as client.key.<name> — binds first, physical keys second; the rest of the story is on /tupenter help client.key.

```
#if (client.keypress.g) (/echo G!)
#if (client.keypress.g) (/tp @s $client.target.blockpos$)
```

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

