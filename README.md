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

*A tour follows. For the complete reference — every directive, function,
parameter type and variable — see **[SCRIPTING.md](SCRIPTING.md)**, or run
`/tupenter reference` in game to copy it to your clipboard.*

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

### `#wait` — pause mid-line

```
/attribute @s minecraft:jump_strength base set 30 && #wait 2t && /tp @s $client.target.blockpos$
```

`#wait 10t / 1.5s / 3d` (ticks/seconds/days, or bare ticks) pauses the
script without freezing anything else. Scripts run **lazily**: each
statement's `$...$` markers evaluate *when it runs*, so the `/tp` above
reads your target block *after* the jump boost landed — not at Enter-press.
Re-running a line cancels its still-running previous instance (resend =
restart, not stack); different lines run concurrently; `/tupenter abort`
stops everything.

### Expressions — `$...$`

```
/give @s stick $32+5$                          → 37
/give @s diamond $3s$                          → 192   (s = stacks of 64)
/give @s stick $rand(1, 64)$                   → random amount, inclusive
/summon $pick("zombie" | "skeleton" | "creeper")$    → random choice (options are expressions; quote literal text)
/tp @s ~ ~$client.pos.y > 60 ? 10 : 0$ ~           → conditional value
/say $client.health < 5 ? "help!" : "fine"$    → strings, comparisons
```

Exact rational math (no float drift), `int(...)`/`float(...)` casts,
`true`/`false`, `&&`/`||`/`!` in conditions. Write `\$` for a literal dollar
sign. **Auto-detect mode** also solves bare math like `32+4` outside NBT
braces. A bad `$...$` expression shows a local error and sends nothing.

World reads: `block(x, y, z)` (or `block("x y z")`) returns the block id at
a position, read from **your client's copy of the world** — no server round
trip, no delay, so `#if`/`#else` handle "is that block air?" instantly
(this is `/execute if block` folded into the expression world). Loaded
chunks only. `$client.target.hit$` ("block"/"entity"/"miss") tells you
whether the crosshair actually found something; `$client.target.blockpos$` now
errors on a miss instead of quietly returning the ray's endpoint.

```
#if ($client.target.hit$ == "block") (/tp @s $client.target.blockpos$)
#else (/echo &cnothing in range)
```

Registry sets: `blockset(#minecraft:logs)` / `itemset(#c:ores)` /
`effectset(#...)` / `entityset(#minecraft:skeletons)` resolve a tag to its
member list through the live
connection's registries — no quotes needed, and typing `#` inside the
parens tab-completes the available tags for that registry (Fabric `c:`
convention tags included). With **no argument** you get the *entire*
registry. Either way it's a plain list, so `rand(list)`, `len(list)`,
`nth(list, i)`, and `#foreach` all apply:

```
/setblock ~ ~-1 ~ $rand(blockset(#minecraft:logs))$
/effect give @s $rand(effectset())$ 30 1
#foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)
```

(Quoted tags still work; quote them when a tag sits right before a
ternary's `:`.) A **concrete id** makes a one-element set —
`blockset("stone")` is `[minecraft:stone]` — so a block-or-blockset
parameter feeds the same functions either way, and `contains(list, v)`
tests membership: `contains(blockset(#minecraft:logs), block(client.target.blockpos))`.

`nth(list, i)` (0-based) plus the `%` operator (floored modulo) make lists
cyclable — a custom command that steps through the wool colors, one block
per run:

```
woolstep = #set $i$ = $i$ + 1 && /setblock ~ ~-1 ~ $nth(blockset("#minecraft:wool"), i % len(blockset("#minecraft:wool")))$
```

(`#set $i$ = 0` once to start it; `/tupenter var save i` keeps the counter
across sessions.)

**Vectors.** A `"x y z"` string *is* a vec3, so anything positional composes:
`vadd` / `vsub` / `scale` / `mag` / `dist` / `normalize` / `dot` / `cross`,
plus `vec(x, y, z)` to build one and `component(v, "x")` to read an axis.
`client.look` is your aim as a unit vector and `client.motion` is your
velocity, which is all the trigonometry most scripts need:

```
/summon arrow $vadd(client.eye_pos, scale(client.look, 1.5))$    → just in front of your eyes
/echo $round(dist(client.pos, spawn))$ blocks from spawn
```

**Entities and rays.** `raycast(dist)` returns where your look hits (or
`"miss"`), `raycast_entity(dist)` what it hits; `entities(radius[, type])`
lists nearby UUIDs and `nearest_entity(radius[, type])` finds the closest;
`entity(uuid, field[, fallback])` reads one — `type`, `name`, `health`,
`pos`, `nbt.<path>`. The fallback is what keeps a tick script from faulting
when something wanders out of range.

**Is it loaded, is it running?** `block(x, y, z)` reads your client's copy
of the world (a chunk you've never received reads as
`"minecraft:void_air"`), while `simulated(x, y, z)` answers a different
question: is the *server* ticking entities there? That's what governs item
despawn, mob spawning and crop growth, and it's often a shorter radius than
what you can see.

### Variables

```
#set spawn = "100 64 -200"
/tp @s $spawn$
#set i += 1                      (compound assignment: += -= *= /= %=)
#local x = rand(1, 10) && /give @s stick $x$ && /say I got $x$!
```

- `#set` = session (clears on join, configurable; echoes a notice);
  `#local` = this line only, silent. `/tupenter vars` lists everything.
  The `$` around the name is optional, and the right side is already an
  expression — `#set i = i + 1` and `#set i = $i + 1$` both work
  (`$...$` always evaluates its inside, everywhere).
- `/tupenter var save <name>` promotes one to the config file forever;
  `/tupenter var delete <name>` removes it.
- Live client state: `$client.pos$` (+ `.x/.y/.z`), `blockpos`, `eye_pos`,
  `look`, `yaw` `pitch` `facing` `health` `food` `air` `name` `dimension` —
  plus environment (`biome`, `light`/`light_block`/`light_sky`,
  `chunk_x`/`chunk_z`), movement (`motion`, `speed`, `speed_xz`, `on_ground`,
  `sneaking`, `sprinting`, `swimming`, `flying`, `gliding`,
  `fall_distance`), stats (`max_health`, `absorption`, `armor`,
  `saturation`, `xp_level`, `xp_progress`), hazards (`in_water`,
  `underwater`, `in_lava`, `on_fire`), `effects` (a list — `#foreach` it),
  `riding`, and session (`gamemode`, `ping`, `fps`, `uuid`,
  `selected_slot`). Computed subjects hang off their own roots:
  `client.target.*` (`hit`, `blockpos`, `block`, `type`, `name`, `health`,
  `uuid`), `client.held.*` / `client.offhand.*` / `client.slot.<n>.*`,
  and `client.vehicle.*`. World state is `$world.difficulty$` `time`
  `time_total` `day` `raining` `thundering` `moon_phase` `spawn` `min_y`
  `max_y` `frozen` `tickrate` `key`; wall-clock is `$real.timestamp$`
  `hour` `minute` `second` `day_of_week`. `/tupenter vars` lists every one
  live, and `/tupenter help <name>` documents it.
- **Events are one-tick edges** you poll, since a script *is* a loop:
  `$client.just_died$`, `$client.just_respawned$`, `$world.just_joined$`,
  and `$client.keypress.<key>$` each read true for exactly the tick their
  transition happens.
- **Everything else** via raw NBT paths: `$client.nbt.Pos.1$`,
  `$client.nbt.Inventory.0.id$`, `$target.nbt.Health$` (entity under your
  crosshair). Browse paths with `/tupenter dump [client|target] [path]`.

### Loops and conditionals

```
#repeat 5 (/say Tick $i$!)
#for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~)
#foreach $mob$ in (zombie | skeleton | creeper) (/summon $mob$ ~ ~ ~)
#foreach $n$ in range(1, 10) (/give @s stick $n$)
#if ($client.pos.y$ > 60) (/say high!) #elseif ($client.pos.y$ > 30) (/say mid) #else (/say low)
```

Groups nest and can contain `&&` chains. Every loop is capped
(`Max Loop Iterations`, default 100) and every script is bounded
(`Max Commands Per Script`, default 1000; sends are throttled to
`Max Commands Per Tick`, default 48, as kick protection). `/tupenter abort`
stops everything.

### Chat-bar highlighting

The chat input understands Tupenter while you type (toggle: *Chat Input
Highlighting*): every `&&` segment is styled by its statement form — commands
get vanilla-style per-argument colors from their own parse, `#directives`
turn gold with dimmed group parens, bare chat goes yellow — `$...$` markers
are aqua, and `&&` separators gold. Autocomplete is chain-aware too:
`/time set day && /weather cl<tab>` completes the *second* command, and
commands containing `$...$` markers no longer light up as errors.

### Selectable chat

Click and drag across chat messages (chat open) to select text — something
vanilla never had — then **Ctrl+C** to copy. Selections span messages
(newlines at message boundaries, spaces at soft wraps), follow their lines
as new messages push things up, and plain clicks still fire chat
click-events. Toggle: *Selectable Chat Text*.

### Silence, privacy, local output

```
#silent /time set day && /weather clear          (whole line)
#repeat 5 (#silent (/give @s stick) && /say hi)  (just part of it)
#norecord /msg friend secret                     (kept out of resend history)
/echo &amy y is &e$client.pos.y$                     (shown only to you, sends nothing; &-codes color it)
#unstage 2                                       (drop the newest 2 resend-history entries)
```

`#silent` hides command feedback on your screen (and `#set` notices) — the
server and other players are unaffected. Works in chat, as `/#silent ...`,
and inside custom command bodies.

### Custom commands

```
/customcommand add sunny = /time set day && /weather clear
/sunny

/customcommand add smite <target:player> = /execute at $target$ run summon lightning_bolt
/smite Steve

/customcommand add waves <count:int> <mob:word> = #repeat $count$ (/summon $mob$ ~ ~ ~)
/waves 3 zombie
```

Typed parameters get real autocomplete prompts, bind as `$name$` or
`$1$..$n$`, and commands added via `/customcommand` register immediately —
no relaunch. Bodies can contain anything: chains, expressions, directives,
other custom commands (recursion capped at 50 expansions). Edit with
`/customcommand update <name> <body>` (signature changes re-register the
autocomplete tree too); `add` refuses names that already exist and offers a
clickable "update it instead".

Append `=default` to make a parameter optional: `<r:int=5>`,
`<p:pos=~ ~ ~>`. Defaults may hold `$...$` expressions, evaluated when the
param is omitted (earlier params are visible). Strictly-typed optionals can
even be skipped mid-command — with `<p:pos=~ ~ ~> <dim:to_overworld,to_nether=...>`,
`/portal`, `/portal 64 64 64`, and `/portal to_nether` all work. Loose
types (`string`/`word`/`text`) always grab the next argument, so put
optional loose params last.

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
| `itemset` | item id **or** `#item_tag`, tab-completes both | verbatim text |
| `blockset` | block id **or** `#block_tag` + optional `[states]`, tab-completes both | verbatim text |
| `entity` | entity type id, `/summon`-style registry tab-complete | verbatim text |
| `bool` | `true`/`false`, tab-completed | a boolean — for `#if` and ternaries |

`blockset`/`itemset` mirror vanilla's block/item *predicate* arguments (the
filter in `/fill ... replace <filter>`), so they're the right type when the
body forwards to a command that accepts tags.

### Tick scripts — the walking mcfunction file

Mod Menu → Tupenter → **Scripts**: one-line scripts that run **every tick**
while the master toggle is on. They never touch resend history, `#set`
notices are muted, and a broken script errors once and is skipped until
edited. `/tupenter abort` is the panic switch (it also flips the master
toggle off).

**Arming is per world.** The tab has two sections: **Global Scripts** are
shared definitions whose On/Off toggle arms them *for the world you're in*
(write nukeOnDeath once, arm it only where it's funny), and **This World's
Scripts** belong to the current world/server alone — switch worlds and the
section shows a different list. A world you never configured runs *nothing*,
so joining your survival server with op is safe by default. `/tupenter
scripts` shows what's armed where you stand.

```
#if ($client.nbt.Health$ < 6) (/give @s totem_of_undying)
#if ($client.pos.y$ > $maxy$) (/tp @s ~ $maxy$ ~)      ← update $maxy$ live with #set
```

Each armed line is wrapped in a loop — literally
`#while (true) (YOUR LINE && #wait 1t)` — so it's *one* long-running script,
not a fresh parse each tick. That's why `#set`/`#setdefault` values persist
across ticks (a counter keeps counting) and `#wait` composes naturally. Use
`#setdefault` for knobs you want to retune live: it creates the variable if
absent and leaves it alone if not, so a later `#set radius = 8` typed in
chat wins and the loop reads the new value on its next pass.

**Fair warning**: an unguarded command in a tick script fires 20×/second —
on a multiplayer server that is chat-spam machinery. Guard with `#if`, or
play where `sendCommandFeedback` is off.

### Batteries included

A fresh install ships with working examples so nothing starts empty — five
custom commands, ready to use:

| Command | What it does |
|---|---|
| `/blink [maxdistance]` | teleport to where you're looking, stopping at walls |
| `/ironkit` | a full set of iron gear |
| `/portalcalc [pos] [dim]` | convert coordinates between the Nether and Overworld |
| `/tickfreeze` | toggle `/tick freeze` |
| `/launch <entity> [speed] [no_gravity]` | hurl an entity where you're looking |

…and five tick scripts, all **disabled by default** — flip one on in
Mod Menu → Scripts:

| Script | What it does |
|---|---|
| `rainbowTunnel` | press `]` and fly: a spiralling wool tunnel follows your motion, lit by glowstone (every block is placed with `keep`, so it never overwrites a build) |
| `itemDespawnTimer` | marks where you died and counts down your items' 5 minutes — pausing whenever that chunk isn't simulated |
| `creeperAlert` | warns once, with the distance, when a creeper gets within 8 blocks (survival only) |
| `elytraWarning` | a durability heads-up before your elytra gives out |
| `restockReminder` | villagers restock at dawn |

They double as documentation: read them to see vectors, events, timers, and
edge-detection in practice.

### Local calculator

`/calc <expr>` evaluates anything the expression engine supports and prints
the result without sending anything. The `/$ expr $` shorthand is top-down:
numbers, booleans, and lists print locally like `/calc`, but a **string**
result runs as a fresh line using the three statement forms — `"/..."` is a
command, `"#..."` a directive, anything else plain chat. So
`/$pick("hi" | "bye")$` chats one of them, and
`/$pick("/tp ~ ~1 ~" | "/tp ~ ~-1 ~")$` teleports. (The `/` in `/$...$`
just marks the line as script.) Resending re-rolls — history keeps the
original `/$...$` form. pick options are expressions, so picks nest:
`/$pick(pick("say hi" | "say yo") | "say nah")$`.

### Custom functions

`/customfunction add <name> <params> = <expression>` defines a value function
you call inside `$...$`, alongside the built-ins:

```
/customfunction add midpoint <a:vec3> <b:vec3> = scale(vadd(a, b), 0.5)
/tp @s $midpoint(client.pos, spawn)$
```

Parameters are typed like custom-command ones (a `<p:vec3>` also binds
`p.x`/`p.y`/`p.z`), bodies may use `#if`/`#for`/`#while`/`#return` for real
algorithms, and functions may recurse. A body computes a value — it can
never send a command, which is what `/customcommand` is for.

## Reference

**[SCRIPTING.md](SCRIPTING.md) is the complete reference** — the model, every
directive, every built-in function, every parameter type, every variable, and
the gotchas, in one file. It is generated from the same registries the in-game
help reads, so it is never out of date. `/tupenter reference` copies the whole
thing to your clipboard.

- `/tupenter help` — in-game quick reference (every command, directive,
  function and variable documents itself, with runnable examples)
- `/unroll <line>` — dry-run debugger: prints what a line unrolls to,
  color-coded by kind (command/chat/echo), without sending anything
- `/tupenter abort` — stop all running scripts
- `/tupenter vars`, `/tupenter var save|delete <name>` — variable management
- Every feature has an on/off toggle in Mod Menu → Tupenter → Scripting.

## Development

```
gradlew build      # builds the mod + runs the unit test suite
gradlew test       # parser/evaluator/executor tests (600+)
gradlew runClient  # dev-launch the client
```

The scripting core (`net.tupenter.script`) is deliberately free of any
Minecraft import, so the language is testable as plain Java — the suite
covers it to 90% branches, and the help screens are generated from doc
registries that the tests check against the real implementation, so
documentation can't drift from behaviour without failing the build.

Design notes live in [docs/SCRIPTING_DESIGN.md](docs/SCRIPTING_DESIGN.md).

## License

[MIT](LICENSE) — use it, fork it, ship it in a modpack; just keep the
copyright notice.
