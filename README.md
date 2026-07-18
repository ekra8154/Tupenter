# Tupenter

A client-side Fabric mod for Minecraft 1.21.10 that supercharges the chat bar:
resend messages and commands with a keybind, and script them with chaining,
math, variables, loops, and conditionals — all running on your client, sending
plain vanilla commands to the server.

## The resender

Tupenter records what you send and replays it on a keybind (default `R`):

- **Press & Hold** for rapid fire, or **Toggle** for hands-free.
- Batch the last N messages, in either order, with per-message and per-batch
  delays, preset message lists, and command-feedback suppression.
- Everything lives in Mod Menu → Tupenter, with a keybind to open the config.

## The scripting layer

Three things to learn:

| Syntax | Meaning |
|---|---|
| `$...$` | an **inline value**, evaluated before sending |
| `#word` | a **Tupenter directive** (never sent to the server) |
| `(...)` | **grouping**, only after a directive — literal everywhere else |

A line is a command (`/...`), a directive (`#...`), or plain chat.

### Chaining

```
/time set day && /weather clear
```

### Expressions — `$...$`

```
/give @s stick $32+5$                          → 37
/give @s diamond $3s$                          → 192   (s = stacks of 64)
/give @s stick $rand(1, 64)$                   → random amount, inclusive
/summon $pick(zombie | skeleton | creeper)$    → random choice (fully literal options)
/tp @s ~ ~$client.y > 60 ? 10 : 0$ ~           → conditional value
/say $client.health < 5 ? "help!" : "fine"$    → strings, comparisons
```

Exact rational math (no float drift), `int(...)`/`float(...)` casts,
`true`/`false`, `&&`/`||`/`!` in conditions. Write `\$` for a literal dollar
sign. **Auto-detect mode** also solves bare math like `32+4` outside NBT
braces. A bad `$...$` expression shows a local error and sends nothing.

### Variables

```
#set $spawn$ = "100 64 -200"
/tp @s $spawn$
#local $x$ = rand(1, 10) && /give @s stick $x$ && /say I got $x$!
```

- `#set` = session (clears on join, configurable; echoes a notice);
  `#local` = this line only, silent. `/tupenter vars` lists everything.
- `/tupenter var save <name>` promotes one to the config file forever;
  `/tupenter var delete <name>` removes it.
- Live client state: `$client.x$` `$client.y$` `$client.z$` (`bx/by/bz` for
  block coords), `yaw` `pitch` `health` `food` `air` `name` `dimension`
  `held_item` `target_block` — and `$world.difficulty$` `time` `day`
  `raining` `thundering` `moon_phase`.
- **Everything else** via raw NBT paths: `$client.nbt.Pos.1$`,
  `$client.nbt.Inventory.0.id$`, `$target.nbt.Health$` (entity under your
  crosshair). Browse paths with `/tupenter dump [client|target] [path]`.

### Loops and conditionals

```
#repeat 5 (/say Tick $i$!)
#for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~)
#foreach $mob$ in (zombie | skeleton | creeper) (/summon $mob$ ~ ~ ~)
#foreach $n$ in range(1, 10) (/give @s stick $n$)
#if ($client.y$ > 60) (/say high!) #elseif ($client.y$ > 30) (/say mid) #else (/say low)
```

Groups nest and can contain `&&` chains. Every loop is capped
(`Max Loop Iterations`, default 100) and every script is bounded
(`Max Commands Per Script`, default 1000; sends are throttled to
`Max Commands Per Tick`, default 16, as kick protection). `/tupenter abort`
stops everything.

### Silence, privacy, local output

```
#silent /time set day && /weather clear          (whole line)
#repeat 5 (#silent (/give @s stick) && /say hi)  (just part of it)
#norecord /msg friend secret                     (kept out of resend history)
#echo my y is $client.y$                         (shown only to you, sends nothing)
```

`#silent` hides command feedback on your screen (and `#set` notices) — the
server and other players are unaffected. Works in chat, as `/#silent ...`,
and inside custom command bodies.

### Custom commands

```
/customcommand add sunny /time set day && /weather clear
/sunny

/customcommand add smite <target:player> /execute at $target$ run summon lightning_bolt
/smite Steve

/customcommand add waves <count:int> <mob:word> #repeat $count$ (/summon $mob$ ~ ~ ~)
/waves 3 zombie
```

Typed parameters get real autocomplete prompts, bind as `$name$` or
`$1$..$n$`, and commands added via `/customcommand` register immediately —
no relaunch. Bodies can contain anything: chains, expressions, directives,
other custom commands (recursion capped at 50 expansions).

Available types: `int`, `float`, `string` (the default — a word or
`"anything quoted"`), `word`, `text` (greedy, must be last), `player`,
`selector`, a comma list like `<dim:to_overworld,to_nether>`, plus:

| Type | Input | Binds |
|------|-------|-------|
| `pos` | whole `x y z`, `~` ok, targeted-block tab-complete | `$p$` = `"x y z"` + `$p.x$ $p.y$ $p.z$` |
| `vec3` | decimal `x y z`, `~` ok | same shape as `pos` |
| `column_pos` | whole `x z`, `~` ok | `$c$` + `$c.x$ $c.z$` |
| `rotation` | `yaw pitch`, `~` ok | `$r$` + `$r.yaw$ $r.pitch$` |
| `angle` | one yaw, `~` ok | a number |
| `time` | `10t` / `1.5s` / `3d` or plain ticks | ticks as a number |
| `dimension` | dimension id, tab-completed | the id string |
| `color` | one of the 16 chat colors, tab-completed | the color name |
| `id` | any namespaced id | the id string |
| `item` | item id + optional `[components]`, full registry tab-complete | verbatim text |
| `block` | block id + optional `[states]`, full registry tab-complete | verbatim text |

### Tick scripts — the walking mcfunction file

Mod Menu → Tupenter → **Scripts**: one-line scripts that run **every tick**
while the master toggle is on. They never touch resend history, `#set`
notices are muted, and a broken script errors once and is skipped until
edited. Prefix a line with `//` to disable it. `/tupenter abort` is the
panic switch (it also flips the master toggle off).

```
#if ($client.nbt.Health$ < 6) (/give @s totem_of_undying)
#if ($client.y$ > $maxy$) (/tp @s ~ $maxy$ ~)      ← update $maxy$ live with #set
```

**Fair warning**: an unguarded command in a tick script fires 20×/second —
on a multiplayer server that is chat-spam machinery. Guard with `#if`, or
play where `sendCommandFeedback` is off.

### Local calculator

`/calc <expr>` (or `/$ expr $`) evaluates anything the expression engine
supports and prints the result without sending anything.

## Reference

- `/tupenter help` — in-game quick reference
- `/tupenter abort` — stop all running scripts
- `/tupenter vars`, `/tupenter var save|delete <name>` — variable management
- Every feature has an on/off toggle in Mod Menu → Tupenter → Scripting.

## Development

```
gradlew build      # builds the mod + runs the unit test suite
gradlew test       # parser/evaluator/executor tests (125+)
gradlew runClient  # dev-launch the client
```

Design notes live in [docs/SCRIPTING_DESIGN.md](docs/SCRIPTING_DESIGN.md).
