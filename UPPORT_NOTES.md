# Tupenter — 1.21.10 → 26.2 up-port notes

Up-ported from the released `version/1.21.10` baseline. `main` (here, `master`)
tracks the newest Minecraft; every older range is a git worktree beside it.

Each branch's `minecraft_support_range` in `gradle.properties` is the single
source of truth for what that jar serves: it names the jar and it fills the
`minecraft` dependency in `fabric.mod.json`, so a build cannot ship claiming a
range it wasn't compiled for.

`gradlew` honours `JAVA_HOME` — **JDK 21 for the 1.21.x branches, JDK 25 for the
26.x branches**. The build will not resolve otherwise.

## Worktrees / ranges

| Worktree folder | Branch | MC versions | Java |
|---|---|---|---|
| tupenter | master | 26.2.x | 25 |
| tupenter-26.1.x | version/26.1.x | 26.1.x | 25 |
| tupenter-1.21.11 | version/1.21.11 | 1.21.11 | 21 |
| tupenter-1.21.9-1.21.10 | version/1.21.9-1.21.10 | 1.21.9, 1.21.10 | 21 |
| tupenter-1.21.6-1.21.8 | version/1.21.6-1.21.8 | 1.21.6, 1.21.7, 1.21.8 | 21 |
| tupenter-1.21.5 | version/1.21.5 | 1.21.5 | 21 |

Six branches covering nine Minecraft versions. Every adjacent pair breaks on
something the mod actually touches, so no two of these ranges can share a jar —
each boundary below was confirmed by compiling, not inferred.

**Don't take another mod's branch layout as evidence about this one.**
effortless-crafting splits 1.21.9 from 1.21.10; Tupenter doesn't need to,
because the two things that moved at that boundary (`WorldRenderEvents`, the
client recipe-sync API) are both unused here. Its boundaries track its surface.
The only way to know where Tupenter's are is to build it.

The 1.21.9 merge was verified by building the same source against both versions
and comparing the remapped jars: all 157 class files byte-identical, mixin config
identical, only the declared range differs. Mods ship remapped to intermediary,
so identical class bytes mean identical intermediary references.

## Per-version dependency matrix (gradle.properties)

| MC | loader | loom | Gradle | fabric_version | modmenu | cloth_config |
|----|--------|------|--------|----------------|---------|--------------|
| 26.2    | 0.19.3 | 1.17.12       | 9.6.0 | 0.152.2+26.2   | 20.0.0-beta.3   | 26.2.155  |
| 26.1.2  | 0.19.2 | 1.16.2        | 9.4.1 | 0.149.0+26.1.2 | 18.0.0-alpha.8  | 26.1.154  |
| 1.21.11 | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.141.3+1.21.11| 17.0.0          | 21.11.153 |
| 1.21.10 | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.138.4+1.21.10| 16.0.0-rc.1     | 20.0.149  |
| 1.21.9  | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.134.1+1.21.9 | 16.0.1          | 20.0.149  |
| 1.21.8  | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.136.1+1.21.8 | 15.0.2          | 19.0.147  |
| 1.21.7  | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.129.0+1.21.7 | 15.0.2          | 19.0.147  |
| 1.21.6  | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.128.2+1.21.6 | 15.0.2          | 19.0.147  |
| 1.21.5  | 0.18.2 | 1.14-SNAPSHOT | 9.2.1 | 0.128.2+1.21.5 | 14.0.2          | 18.0.145  |

Range branches pin the *top* of their range; swap those four values to build
another member.

`fabric.mod.json` declares a loader *floor*, not the exact build version, so the
26.2 jar says `>=0.19.2` while building against 0.19.3.

`minecraft_support_range` is a filename label AND the source of the Fabric
version predicate, which are different grammars: `1.21.9-1.21.10` names the jar,
while `build.gradle` derives `>=1.21.9 <=1.21.10` for `fabric.mod.json`. Exact
versions and x-ranges are already valid predicates and pass through untouched.
Never hand-write the predicate — one property, or the jar can lie about itself.

## Compatibility boundaries discovered (why the ranges are where they are)

- **1.21.10 → 1.21.9**: nothing. The source compiles unchanged and the remapped
  jars are byte-identical, so one jar serves both.
- **1.21.9 → 1.21.8**: the **input rework**. `KeyEvent`/`MouseButtonEvent`/
  `CharacterEvent` don't exist below 1.21.9, so handlers take primitives again
  and the predicates that hung off the events become statics on `Screen`
  (`isCopy`, `isPaste`, `hasControlDown`, `hasShiftDown`). Twelve signatures,
  four of them mixin injection points. Also `KeyMapping.Category` → a String
  category (same translation key, so the lang file is unchanged),
  `InputConstants.isKeyDown` takes the raw GLFW handle rather than the `Window`,
  `LevelData.getRespawnData()` → `getSpawnPos()`, `EditBox.addFormatter` →
  `setFormatter`, and `GameProfile.name()` → `getName()` (it became a record in
  1.21.9's authlib).
- **1.21.10 → 1.21.11**: Mojang's registry rename pass. `ResourceLocation` →
  `Identifier`, `ResourceLocationArgument` → `IdentifierArgument`,
  `ResourceKey.location()` → `identifier()`. Note `TagKey.location()` was **not**
  renamed — a blanket replace breaks the tag resolver. `Level.getMoonPhase()`
  removed (moon moved to a data-driven timeline with a `MoonPhase` enum that has
  no current-phase lookup). `ChatComponent.getScale/getWidth` went public →
  private.
- **1.21.6 → 1.21.5**: `GuiGraphics.pose()` is a `PoseStack`, not the 2D
  `Matrix3x2fStack` — `pushMatrix/popMatrix` → `pushPose/popPose`, and
  `scale`/`translate` regain a z component. Entity NBT predates the `ValueOutput`
  abstraction: `saveWithoutId(CompoundTag)` writes into the tag and returns it,
  so `TagValueOutput`/`ProblemReporter` disappear. `MultiLineEditBox`'s
  constructor takes 7 arguments instead of 12 and is already public, so the
  styling arguments go (they were the vanilla defaults anyway) **and the access
  widener drops its `<init>` entry** — leaving it in would name a descriptor that
  doesn't exist. `ClientPacketListener.getCommands()` is parameterised with
  `SharedSuggestionProvider`; 1.21.6 narrowed it to `ClientSuggestionProvider`.
- **1.21.11 → 26.1**: the toolchain and GUI rewrite. Java 21 → 25; loom 1.16
  makes Mojang mappings the default, which drops `modImplementation` entirely and
  requires the access widener to declare `official` rather than `named`. GUI goes
  from immediate-mode to render-state extraction: `GuiGraphics` →
  `GuiGraphicsExtractor`, `Screen.render` → `extractRenderState`,
  `AbstractWidget.render` → `extractRenderState`,
  `MultiLineEditBox.renderContents` → `extractContents`, `drawString` → `text`.
  `GuiMessage` moves to `client.multiplayer.chat`. `Level.getDayTime()` → the
  `WorldClock` system. Fabric API renames `ClientCommandManager` →
  `ClientCommands`, `KeyBindingHelper` → `KeyMappingHelper` (new
  `fabric-key-mapping-api-v1`), `HudRenderCallback` → `HudElementRegistry`.
- **26.1 → 26.2**: the `Gui` god-object splits. Screen/overlay management stays on
  `Gui`; the HUD half becomes `net.minecraft.client.gui.Hud`, reached as
  `gui.hud`. So `Minecraft.screen` → `gui.screen()`, `Minecraft.setScreen` →
  `gui.setScreen()`, `Gui.getChat` → `gui.hud.getChat()`,
  `Gui.setOverlayMessage` → `gui.hud.setOverlayMessage()`, and `Options.hideGui`
  → `gui.hud.isHidden()`. `ColorArgument` → `TeamColorArgument`.

## What was verified vs. what needs manual in-world testing

Verified automatically on **every** range: the full build (600+ tests, both
JaCoCo coverage gates, and the SCRIPTING.md doc-drift guard), plus `runClient`
reaching the title screen with `tupenter` loaded and **no mixin-apply or
injection failures**.

Separately, every mixin target was **javap-checked against each version's jar** —
all `@Inject`/`@Accessor`/`@Invoker` members, the four `@Redirect` INVOKE call
sites *inside* `CommandSuggestions.updateCommandInfo`, and the
`MultiLineEditBox.<init>` access-widener descriptor (which the 1.21.5 branch
deliberately no longer widens — loom's own `validateAccessWidener` covers it
there). This matters because those
are runtime failures, not compile errors: a stale `@Redirect` builds fine and
then hard-crashes the game at launch.

`runClient` only reaches the main menu, so **please manual-test in-world on each
range**. Specific things to check:

- **All ranges**: chat-bar syntax highlighting and chain-aware autocomplete (the
  four `@Redirect`s in `updateCommandInfo` drive both), click-drag chat selection
  and Ctrl+C, the Mod Menu script editors, and the running-scripts HUD panel.
- **1.21.11 and up**: `world.moon_phase`. Vanilla's helper is gone and the value
  is now computed locally; it should still read 0–7 with 0 = full moon.
- **26.x**: everything that draws. The GUI rewrite touched `ChatSelection`'s
  highlight, `ScriptEditBox`'s syntax overdraw, all three Mod Menu list entries,
  and the HUD panel. These compile and the mixins apply, but pixel placement was
  not verified.
- **1.21.5**: entity NBT — `entity(...)` and `client.nbt.*` — since that path
  changed rather than just its signature. Also the chat-selection highlight
  (the transform was rewritten) and the Mod Menu script editor's appearance.
- **1.21.6–1.21.8**: everything input-driven, since that whole layer was
  rewritten by hand — chat click-drag selection and Ctrl+C, Ctrl+Space send,
  Ctrl+scroll history, auto-close brackets in the chat bar, and the script
  editor's Tab/Enter/undo/redo handling. Also check the keybind category shows
  as "Tupenter" in Controls, and that `world.spawn` reads correctly.
- **26.x**: `world.time` / `world.day`, which now read `getDefaultClockTime()`
  (the per-dimension clock) rather than the old single `dayTime` field. Vanilla
  syncs one value across dimensions, so this should be identical — worth
  confirming in the Nether.
- **26.2**: custom commands with a `color` parameter, now backed by
  `TeamColorArgument`. Completion should still offer the named colours.

## Downport survey (measured, not estimated)

Compiled the 1.21.10 source against each older version in a throwaway worktree.
Nothing below is built yet — this is scope, recorded so it doesn't have to be
rediscovered.

## How far down this could go

1.21.5 is the current floor, and it was chosen rather than forced — nothing
below it has been probed. The measured survey that produced these branches
stopped there.

The fragile part held all the way down: `ChatComponent`'s private fields,
`ChatScreen`'s hooks and all four `@Redirect` sites inside `updateCommandInfo`
are intact across every version from 1.21.5 to 26.2. The only structural
casualty in the whole descent was the `MultiLineEditBox` constructor.

Per effortless-crafting's notes the next boundaries are the inventory equipment
refactor at 1.21.4 and the recipe-display system at 1.21.2 — but that is a
*hypothesis about a different mod*, not a finding about this one. Tupenter reads
no recipes, and its slot access may or may not care. Compile against 1.21.4 to
find out.

## Backporting a cycle to the version branches

The first backport cherry-picked commits one at a time. That stops scaling the
moment a cycle MOVES code between files — the 1.1.0 cycle splits
`ModMenuIntegration` into three, so a sequential pick means settling that rename
twenty-odd times against intermediate states that get thrown away.

What works instead, and what the 1.1.0 backport used on all five branches:

1. Take master's copy of every file the cycle touched.
2. Re-apply **that branch's own** adaptation — the `git diff <last-shared-commit>
   <branch>` for those same files — on top, with `git apply -3`.

Git then works out which adaptation hunks still apply and hands back a conflict
only where the cycle rewrote the very lines the adaptation touches. On every
branch that was the same two places: the Open Config keybind (now routed through
`ConfigScreenAccess`) and the config screen's scroll restore (rewritten
wholesale). Both keep master's logic, with the renames re-applied to it.

Where a file's content MOVED, re-aim its patch at the new path — the config
screen's adaptation is a diff against `ModMenuIntegration.java` and has to be
sed'd onto `TupenterConfigScreen.java` before it will apply.

**Do not `git checkout master -- src/main` wholesale.** `src/main/resources`
is not all version-independent: `fabric.mod.json` is pinned per branch and
`tupenter.accesswidener` names a different mapping namespace below 26.x
(`named` vs `official`). Clobbering the widener fails the build at *configure*
time with "Namespace mismatch, expected named got official", which reads like a
toolchain problem and isn't. Only `assets/.../lang/en_us.json` comes from
master.

**Do not `git merge master`** into a version branch. The merge base is the
up-port commit the branch forked from, so master's side carries the NEXT
version's API rewrite as a change the branch never made — the merge takes it
silently. Cherry-pick or patch; never merge.

Build the branches one at a time. Concurrent Gradle runs share a daemon and
fight over `build/test-results`, which fails the `test` task with an undeletable
`output.bin` — a lock, not a test failure.

## Open items

- All six branches are at `mod_version` 1.1.0.
- Only the 1.21.10 build has been published. The others are built but unreleased;
  jars are collected in `../latest release/`.
