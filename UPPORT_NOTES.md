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

Four branches is the floor. Every adjacent pair breaks on something the mod
actually touches, so no two of these ranges can share a jar — each boundary
below was confirmed by compiling, not inferred.

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

The 1.21.9-1.21.10 branch pins the *top* of its range; swap those four values to
build the other member.

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
- **1.21.10 → 1.21.11**: Mojang's registry rename pass. `ResourceLocation` →
  `Identifier`, `ResourceLocationArgument` → `IdentifierArgument`,
  `ResourceKey.location()` → `identifier()`. Note `TagKey.location()` was **not**
  renamed — a blanket replace breaks the tag resolver. `Level.getMoonPhase()`
  removed (moon moved to a data-driven timeline with a `MoonPhase` enum that has
  no current-phase lookup). `ChatComponent.getScale/getWidth` went public →
  private.
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
`MultiLineEditBox.<init>` access-widener descriptor. This matters because those
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

| Target | Errors | Files | New breaks |
|---|---|---|---|
| 1.21.9 | 0 | 0 | none — already merged into the 1.21.9-1.21.10 branch |
| 1.21.6–1.21.8 | 26 | 6 | input rework |
| 1.21.5 | 36 | 9 | the above, plus three more |

1.21.6 and 1.21.8 produce **identical** error sets, so those three versions are
one range.

- **1.21.9 → 1.21.8**: `KeyEvent`/`MouseButtonEvent`/`CharacterEvent` don't exist
  (12 signatures, 4 of them mixin injection points); `KeyMapping.Category` → the
  old String category; `InputConstants.isKeyDown` takes a `long` not a `Window`;
  `LevelData.getRespawnData()` missing (that's `world.spawn` and `.x/.y/.z`);
  `EditBox.addFormatter` → the older `setFormatter`.
- **1.21.6 → 1.21.5**: `graphics.pose()` is a `PoseStack`, not a
  `Matrix3x2fStack` — `pushMatrix/popMatrix` → `pushPose/popPose` with different
  arity, which the chat-selection highlight depends on. `TagValueOutput` doesn't
  exist, so entity NBT reads go back to the old save API — that's `entity(...)`
  and `client.nbt.*`, real behaviour rather than a signature swap. The Fabric
  client command API returns a different node type. And the `MultiLineEditBox`
  constructor takes 7 arguments instead of 12 (and is already public), so the
  access-widener entry becomes invalid and `ScriptEditBox` loses the parameters
  that set its text and cursor colours.

The fragile part came back clean: `ChatComponent`'s private fields, `ChatScreen`'s
hooks and all four `@Redirect` sites inside `updateCommandInfo` are intact all
the way down to 1.21.5. The only structural casualty is that constructor.

## Open items

- `mod_version` is `1.0.0` on all four branches.
- Only the 1.21.10 build has been published. The others are built but unreleased;
  jars are collected in `tupenter-latest-builds/`.
