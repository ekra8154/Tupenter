package net.tupenter.compat;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.tupenter.TupenterModClient;
import net.tupenter.command.CommandAliasManager;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.NumberMathMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The config screen itself, built with Cloth Config.
 *
 * <p>Deliberately knows nothing about Mod Menu — {@link ModMenuIntegration} is a
 * shim that hands Mod Menu a factory pointing here. That split is load-bearing,
 * not tidiness: both mods are only <em>suggested</em>, and the JVM resolves a
 * class's superinterfaces when it loads the class, so anything implementing
 * ModMenuApi is unloadable without Mod Menu installed. Keeping the screen out of
 * that class is what lets /tupenter menu and the Open Config keybind work with
 * Cloth alone. Reach it through {@link ConfigScreenAccess}, which checks first.
 */
public class TupenterConfigScreen {
    private static Screen cachedParent;

    public static Screen createScreen(Screen parent) {
        cachedParent = parent;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("title.tupenter.config"));

        ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.tupenter.general"));
        ConfigCategory scripting = builder.getOrCreateCategory(Component.translatable("category.tupenter.scripting"));
        ConfigCategory aliases = builder.getOrCreateCategory(Component.translatable("category.tupenter.aliases"));
        ConfigCategory scripts = builder.getOrCreateCategory(Component.translatable("category.tupenter.scripts"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // =====================================================================
        // TOP-LEVEL SETTINGS
        // =====================================================================

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_mode"), TupenterConfig.ResendMode.class, TupenterConfig.INSTANCE.resendMode)
                .setDefaultValue(TupenterConfig.ResendMode.PRESS_AND_HOLD)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_mode"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_filter"), TupenterConfig.ResendFilter.class, TupenterConfig.INSTANCE.resendFilter)
                .setDefaultValue(TupenterConfig.ResendFilter.BOTH)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_filter"))
                .setSaveConsumer(newValue -> {
                    boolean wasHistoryBased = TupenterConfig.INSTANCE.resendFilter != TupenterConfig.ResendFilter.PERMANENT_MESSAGES;
                    boolean isNowHistoryBased = newValue != TupenterConfig.ResendFilter.PERMANENT_MESSAGES;
                    // Clear history only when switching between history-based filter types
                    if (wasHistoryBased && isNowHistoryBased && newValue != TupenterConfig.INSTANCE.resendFilter) {
                        TupenterModClient.messageHistory.clear();
                    }
                    TupenterConfig.INSTANCE.resendFilter = newValue;
                })
                .setEnumNameProvider(mode -> Component.translatable("filter.tupenter." + mode.name().toLowerCase()))
                .build());

        // =====================================================================
        // SUB-CATEGORY: Tupenter Behavior
        // =====================================================================

        AbstractConfigListEntry<?> resetOnNewSessionEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.reset_on_new_session"), TupenterConfig.INSTANCE.resetOnNewSession)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.reset_on_new_session"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resetOnNewSession = newValue)
                .build();

        AbstractConfigListEntry<?> rapidResendDelayEntry = entryBuilder.startIntSlider(Component.translatable("option.tupenter.rapid_resend_delay"), TupenterConfig.INSTANCE.rapidResendDelay, 0, 100)
                .setDefaultValue(5)
                .setTooltip(Component.translatable("tooltip.tupenter.rapid_resend_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.rapidResendDelay = newValue)
                .build();

        AbstractConfigListEntry<?> messageDelayEntry = entryBuilder.startIntField(Component.translatable("option.tupenter.message_delay"), TupenterConfig.INSTANCE.messageDelay)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("tooltip.tupenter.message_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.messageDelay = newValue)
                .build();

        AbstractConfigListEntry<?> suppressFeedbackEntry = entryBuilder.startEnumSelector(Component.translatable("option.tupenter.suppress_feedback"), TupenterConfig.FeedbackSuppressionMode.class, TupenterConfig.INSTANCE.suppressFeedback)
                .setDefaultValue(TupenterConfig.FeedbackSuppressionMode.OFF)
                .setTooltip(Component.translatable("tooltip.tupenter.suppress_feedback"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.suppressFeedback = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.feedback." + mode.name().toLowerCase()))
                .build();

        // "Pause message tracking while toggled" is the inverse of updateInToggle
        AbstractConfigListEntry<?> pauseTrackingEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.pause_tracking"), !TupenterConfig.INSTANCE.updateInToggle)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.pause_tracking"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.updateInToggle = !newValue)
                .build();

        general.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.behavior"),
                List.of(resetOnNewSessionEntry, rapidResendDelayEntry, messageDelayEntry, suppressFeedbackEntry, pauseTrackingEntry))
                .setExpanded(false)
                .build());

        // =====================================================================
        // SUB-CATEGORY: Advanced Settings
        // =====================================================================

        AbstractConfigListEntry<?> historyDepthEntry = entryBuilder.startIntSlider(Component.translatable("option.tupenter.history_depth"), TupenterConfig.INSTANCE.historyDepth, 1, 32)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("tooltip.tupenter.history_depth"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.historyDepth = newValue)
                .build();

        AbstractConfigListEntry<?> resendDelayEntry = entryBuilder.startIntField(Component.translatable("option.tupenter.resend_delay"), TupenterConfig.INSTANCE.resendDelay)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendDelay = newValue)
                .build();

        AbstractConfigListEntry<?> batchModeEntry = entryBuilder.startEnumSelector(Component.translatable("option.tupenter.batch_mode"), TupenterConfig.BatchMode.class, TupenterConfig.INSTANCE.batchMode)
                .setDefaultValue(TupenterConfig.BatchMode.PAUSE)
                .setTooltip(Component.translatable("tooltip.tupenter.batch_mode"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.batchMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.batch." + mode.name().toLowerCase()))
                .build();

        AbstractConfigListEntry<?> resendAmountEntry = entryBuilder.startIntSlider(Component.translatable("option.tupenter.resend_amount"), TupenterConfig.INSTANCE.resendAmount, 1, 10)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_amount"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendAmount = newValue)
                .build();

        AbstractConfigListEntry<?> resendOrderEntry = entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_order"), TupenterConfig.ResendOrder.class, TupenterConfig.INSTANCE.resendOrder)
                .setDefaultValue(TupenterConfig.ResendOrder.OLDEST_FIRST)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_order"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendOrder = newValue)
                .setEnumNameProvider(mode -> Component.translatable("order.tupenter." + mode.name().toLowerCase()))
                .build();

        // --- Preset Commands (Macros) subfolder ---
        // How many of the most-recent history entries Import pulls (history caps at 50).
        importCountEntry = entryBuilder.startIntSlider(
                        Component.translatable("option.tupenter.import_count"),
                        Math.max(1, Math.min(50, TupenterConfig.INSTANCE.importHistoryCount)), 1, 50)
                .setDefaultValue(50)
                .setTooltip(Component.translatable("tooltip.tupenter.import_count"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.importHistoryCount = newValue)
                .build();

        // Imports straight into the box (no screen reload) — so there's nothing
        // to discard and no confirm needed; nothing persists until Done anyway.
        AbstractConfigListEntry<?> importButtonEntry = new ImportButtonEntry(
                Component.translatable("text.tupenter.import_history"),
                Component.translatable("tooltip.tupenter.import_history"),
                () -> {
                    if (presetBox != null) {
                        presetBox.importText(joinPresets(recentHistory()));
                    }
                }
        );

        // One tall editor for the whole preset macro — one command per line,
        // syntax-highlighted; blank lines are ignored (and separate for reading).
        presetBox = new PresetBoxEntry(joinPresets(TupenterConfig.INSTANCE.permanentMessages));
        AbstractConfigListEntry<?> presetHintEntry = entryBuilder
                .startTextDescription(Component.translatable("text.tupenter.preset_hint"))
                .build();

        AbstractConfigListEntry<?> enhancedCommandParsingEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.enhanced_command_parsing"), TupenterConfig.INSTANCE.enhancedCommandParsingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.enhanced_command_parsing"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.enhancedCommandParsingEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> chatHighlightingEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.chat_highlighting"), TupenterConfig.INSTANCE.chatHighlightingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.chat_highlighting"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.chatHighlightingEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> numberMathEntry = entryBuilder.startEnumSelector(Component.translatable("option.tupenter.number_math"), NumberMathMode.class, TupenterConfig.INSTANCE.numberMathMode)
                .setDefaultValue(NumberMathMode.AUTO_DETECT)
                .setTooltip(Component.translatable("tooltip.tupenter.number_math"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.numberMathMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.number_math." + mode.name().toLowerCase()))
                .build();

        AbstractConfigListEntry<?> maxLoopIterationsEntry = entryBuilder.startIntField(Component.translatable("option.tupenter.max_loop_iterations"), TupenterConfig.INSTANCE.maxLoopIterations)
                .setDefaultValue(100)
                .setMin(1)
                .setTooltip(Component.translatable("tooltip.tupenter.max_loop_iterations"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.maxLoopIterations = newValue)
                .build();

        AbstractConfigListEntry<?> maxCommandsPerTickEntry = entryBuilder.startIntSlider(Component.translatable("option.tupenter.max_commands_per_tick"), TupenterConfig.INSTANCE.maxCommandsPerTick, 1, 128)
                .setDefaultValue(16)
                .setTooltip(Component.translatable("tooltip.tupenter.max_commands_per_tick"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.maxCommandsPerTick = newValue)
                .build();

        AbstractConfigListEntry<?> maxCommandsPerScriptEntry = entryBuilder.startIntField(Component.translatable("option.tupenter.max_commands_per_script"), TupenterConfig.INSTANCE.maxCommandsPerScript)
                .setDefaultValue(1000)
                .setMin(1)
                .setTooltip(Component.translatable("tooltip.tupenter.max_commands_per_script"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.maxCommandsPerScript = newValue)
                .build();

        AbstractConfigListEntry<?> maxConcurrentScriptsEntry = entryBuilder.startIntSlider(Component.translatable("option.tupenter.max_concurrent_scripts"), TupenterConfig.INSTANCE.maxConcurrentScripts, 1, 64)
                .setDefaultValue(8)
                .setTooltip(Component.translatable("tooltip.tupenter.max_concurrent_scripts"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.maxConcurrentScripts = newValue)
                .build();

        general.addEntry(entryBuilder.startSubCategory(
                Component.translatable("subcategory.tupenter.preset_commands"),
                List.of(importCountEntry, importButtonEntry, presetHintEntry, presetBox))
                .setExpanded(false)
                .build());

        // Enhanced Command Parsing is the master switch; the per-feature gates
        // (chaining, silent, variables, loops, conditionals, lazy) were folded
        // into it — they interdepend, so a partial subset never made sense.
        scripting.addEntry(enhancedCommandParsingEntry);
        scripting.addEntry(chatHighlightingEntry);
        scripting.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.auto_close_brackets"), TupenterConfig.INSTANCE.autoCloseBrackets)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.auto_close_brackets"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.autoCloseBrackets = newValue)
                .build());
        scripting.addEntry(numberMathEntry);
        scripting.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.script_limits"),
                List.of(maxCommandsPerTickEntry, maxCommandsPerScriptEntry, maxConcurrentScriptsEntry, maxLoopIterationsEntry))
                .setExpanded(false)
                .build());
        // =====================================================================
        // CUSTOM COMMANDS TAB — one row per definition: [wrap] [name = body] [✕]
        // =====================================================================

        aliases.addEntry(entryBuilder.startTextDescription(Component.translatable("tooltip.tupenter.aliases")).build());

        commandRows.clear();
        for (String definition : CommandAliasManager.getAliasDefinitions()) {
            if (definition.trim().isEmpty()) {
                continue;
            }
            CommandRowEntry row = new CommandRowEntry(definition);
            commandRows.add(row);
            aliases.addEntry(row);
        }

        aliases.addEntry(new ImportButtonEntry(
                Component.translatable("text.tupenter.add_command"),
                Component.translatable("tooltip.tupenter.add_command"),
                () -> {
                    List<String> updated = collectCommandRows();
                    updated.add("newcommand = /echo edit me");
                    TupenterConfig.INSTANCE.aliases = updated;
                    reopenScreen(TAB_ALIASES);
                }
        ));

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.chat_selection"), TupenterConfig.INSTANCE.chatSelectionEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.chat_selection"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.chatSelectionEnabled = newValue)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.ctrl_scroll_history"), TupenterConfig.INSTANCE.ctrlScrollHistory)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.ctrl_scroll_history"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.ctrlScrollHistory = newValue)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.save_directive_history"), TupenterConfig.INSTANCE.saveDirectiveHistory)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.save_directive_history"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.saveDirectiveHistory = newValue)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.ctrl_space_send"), TupenterConfig.INSTANCE.ctrlSpaceSend)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.ctrl_space_send"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.ctrlSpaceSend = newValue)
                .build());

        general.addEntry(entryBuilder.startIntField(Component.translatable("option.tupenter.chat_input_length"), TupenterConfig.INSTANCE.chatInputLength)
                .setDefaultValue(256)
                .setMin(256)
                .setMax(32766)
                .setTooltip(Component.translatable("tooltip.tupenter.chat_input_length"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.chatInputLength = newValue)
                .build());

        general.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.advanced"),
                List.of(historyDepthEntry, resendDelayEntry, batchModeEntry, resendAmountEntry, resendOrderEntry))
                .setExpanded(false)
                .build());

        // =====================================================================
        // SCRIPTS TAB: tick scripts, armed PER WORLD, in one list. Each row is
        // either Global (a shared definition with a per-world On/Off) or World
        // (exists only here); its scope button moves it between the two.
        // Unconfigured world = nothing runs.
        // =====================================================================

        tickMasterEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.tick_scripts_enabled"), TupenterConfig.INSTANCE.tickScriptsEnabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.tick_scripts_enabled"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.tickScriptsEnabled = newValue)
                .build();
        scripts.addEntry(tickMasterEntry);

        if (!TupenterConfig.INSTANCE.tickScriptsEnabled) {
            // clears up "I aborted but scripts still show ✔" — arming is preserved, the master just gates it
            scripts.addEntry(entryBuilder.startTextDescription(
                    Component.translatable("text.tupenter.tick_master_off")).build());
        }

        scriptsWorldKey = TupenterModClient.currentWorldKey();
        scripts.addEntry(entryBuilder.startTextDescription(scriptsWorldKey != null
                ? Component.translatable("text.tupenter.scripts_world", scriptsWorldKey)
                : Component.translatable("text.tupenter.scripts_no_world")).build());
        scripts.addEntry(buildScriptsHelp(entryBuilder));

        TupenterConfig.WorldScriptState worldState = TupenterConfig.INSTANCE.worldState(scriptsWorldKey);

        // --- [On/Off for THIS world] [Global/World] [wrap] [text] [✕] ---
        // Global rows first, then this world's own (none outside a world).
        scriptRows.clear();
        for (TupenterConfig.GlobalScript definition : TupenterConfig.INSTANCE.globalScripts) {
            boolean enabledHere = worldState != null && worldState.enabledGlobalIds.contains(definition.id);
            ScriptRowEntry row = new ScriptRowEntry(definition.id, definition.text, true, enabledHere, scriptsWorldKey == null);
            scriptRows.add(row);
            scripts.addEntry(row);
        }
        if (worldState != null) {
            for (TupenterConfig.WorldScript script : worldState.scripts) {
                ScriptRowEntry row = new ScriptRowEntry(script.id, script.text, false, script.enabled, false);
                scriptRows.add(row);
                scripts.addEntry(row);
            }
        }
        scripts.addEntry(new ImportButtonEntry(
                Component.translatable("text.tupenter.add_script"),
                Component.translatable("tooltip.tupenter.add_script"),
                () -> {
                    commitScriptEdits();
                    TupenterConfig.INSTANCE.globalScripts.add(new TupenterConfig.GlobalScript(
                            TupenterConfig.GlobalScript.newId(), "/echo new script"));
                    reopenScreen(TAB_SCRIPTS);
                }
        ));

        java.util.Set<String> namesBeforeEdit = new java.util.HashSet<>(CommandAliasManager.getAliasMap().keySet());
        builder.setSavingRunnable(() -> {
            commitScriptEdits();
            TupenterConfig.INSTANCE.aliases = collectCommandRows();
            if (presetBox != null) {
                TupenterConfig.INSTANCE.permanentMessages = splitPresets(presetBox.text());
            }
            TupenterConfig.save();
            TupenterModClient.resetTickScriptFaults(); // edited scripts get a fresh chance

            // keep the live Brigadier trees in sync with the edited list
            java.util.Map<String, net.tupenter.script.AliasDefinition> after = CommandAliasManager.getAliasMap();
            for (String old : namesBeforeEdit) {
                if (!after.containsKey(old)) {
                    net.tupenter.command.ClientCommandRegistrar.unregisterDynamic(old);
                }
            }
            after.forEach(net.tupenter.command.ClientCommandRegistrar::registerDynamic);
        });

        return builder.build();
    }

    /**
     * Wrap-toggle state carried across a rebuild, keyed by the row's text.
     *
     * <p>Without this, {@code expanded} is re-derived from "does the text contain
     * a newline" every time the screen is rebuilt — so a row you opened by hand
     * closes again, and a multi-line row you closed springs back open. Adding one
     * command silently re-laid-out the whole list.
     *
     * <p>It also broke the scroll restore, which is how it was found: an expanded
     * row is 62px against a collapsed row's 18, so a few rows changing state moves
     * the content height enough to land the restore a quarter-screen off. Getting
     * the state right is what makes the position right.
     *
     * <p>Keyed by text because that is exactly what the row is rebuilt FROM —
     * collectCommandRows() writes row.text() to the config and the constructor
     * reads it straight back. A deque per key so two rows with identical text
     * still get their own answers, in order.
     */
    /** A row's view state, carried across a rebuild: wrapped or not, and the editor height. */
    private record CarriedRow(boolean expanded, int height) {
    }

    private static final java.util.Map<String, java.util.ArrayDeque<CarriedRow>> carriedExpansion =
            new java.util.HashMap<>();

    /** Snapshot every open row's state, for the rebuild that is about to happen. */
    private static void carryExpansion() {
        carriedExpansion.clear();
        for (List<? extends WrapRowEntry> rows : List.of(commandRows, scriptRows)) {
            for (WrapRowEntry row : rows) {
                if (!row.deleted) {
                    carriedExpansion.computeIfAbsent(row.text(), key -> new java.util.ArrayDeque<>())
                            .add(new CarriedRow(row.expanded, row.expandedHeight));
                }
            }
        }
    }

    /** The carried state for a row about to be built from {@code text}, or null. */
    private static CarriedRow takeCarriedExpansion(String text) {
        java.util.ArrayDeque<CarriedRow> queued = carriedExpansion.get(text);
        return queued == null ? null : queued.poll();
    }

    private static final List<ScriptRowEntry> scriptRows = new ArrayList<>();
    /** The Scripts tab's master switch, read live so flipping it dims the rows at once. */
    private static me.shedaniel.clothconfig2.gui.entries.BooleanListEntry tickMasterEntry;
    private static final List<CommandRowEntry> commandRows = new ArrayList<>();
    private static PresetBoxEntry presetBox; // the resend preset/macro editor
    private static me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry importCountEntry; // live value for Import

    /** The most-recent N history entries, N from the (live) Import-count slider. */
    private static List<String> recentHistory() {
        List<String> history = TupenterModClient.messageHistory;
        int n = importCountEntry != null ? importCountEntry.getValue() : TupenterConfig.INSTANCE.importHistoryCount;
        int count = Math.min(Math.max(1, n), history.size());
        return new ArrayList<>(history.subList(history.size() - count, history.size()));
    }

    /** Preset list -> editor text: one command per line, blank line between each for readability. */
    private static String joinPresets(List<String> presets) {
        List<String> lines = new ArrayList<>();
        for (String preset : presets) {
            if (preset != null && !preset.trim().isEmpty()) {
                lines.add(preset.trim());
            }
        }
        return String.join("\n\n", lines);
    }

    /** Editor text -> preset list: each non-blank line is one command. */
    private static List<String> splitPresets(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
    /** World the Scripts tab was built for (null = opened outside a world). */
    private static String scriptsWorldKey;

    /**
     * Pushes the current script rows into the live config, each into the store
     * its scope button says: global definitions, plus (when the screen was
     * opened in a world) that world's enable set and its own script list.
     * Called on save and before any row-list rebuild.
     *
     * <p>A script keeps its id when it changes scope, so a running loop and
     * its pid carry straight over. The prune at the end is what finishes a
     * Global to World move: the id is no longer a global, so every other
     * world's enable pointer to it goes, and moving it back later can't
     * silently re-arm it over there.
     */
    private static void commitScriptEdits() {
        List<TupenterConfig.GlobalScript> globals = new ArrayList<>();
        List<String> enabledIds = new ArrayList<>();
        List<TupenterConfig.WorldScript> worldScripts = new ArrayList<>();
        for (ScriptRowEntry row : scriptRows) {
            if (row.deleted || row.text().isEmpty()) {
                continue;
            }
            String id = row.id == null || row.id.isEmpty() ? TupenterConfig.GlobalScript.newId() : row.id;
            if (row.global || scriptsWorldKey == null) {
                globals.add(new TupenterConfig.GlobalScript(id, row.text()));
                if (row.enabled) {
                    enabledIds.add(id);
                }
            } else {
                worldScripts.add(new TupenterConfig.WorldScript(id, row.text(), row.enabled));
            }
        }
        TupenterConfig.INSTANCE.globalScripts = globals;

        // without a world, arming is locked and there is nothing per-world to write
        if (scriptsWorldKey != null) {
            TupenterConfig.WorldScriptState state = TupenterConfig.INSTANCE.worldStateOrCreate(scriptsWorldKey);
            state.enabledGlobalIds = enabledIds;
            state.scripts = worldScripts;
        }
        TupenterConfig.INSTANCE.pruneWorldScriptStates();
    }

    private static List<String> collectCommandRows() {
        List<String> lines = new ArrayList<>();
        for (CommandRowEntry row : commandRows) {
            if (row.deleted) {
                continue;
            }
            String text = row.text();
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        return lines;
    }

    /**
     * A row with [leading widgets] [wrap toggle] [text box] [✕]. The wrap
     * toggle swaps a single-line box for a wrapping multi-line editor.
     * Newlines are preserved: collapsing shows a joined view, but text()
     * returns the formatted value unless the joined view was actually edited.
     */
    private abstract static class WrapRowEntry extends AbstractConfigListEntry<String> {
        private static final int BOX_HEIGHT_EXPANDED = 62;
        private static final int BOX_HEIGHT_MIN = 30;
        private static final int BOX_HEIGHT_MAX = 400;
        /** The grip's hit area reaches this far up/left of the corner, and a bit below it. */
        private static final int GRIP_SIZE = 8;
        private static final int GRIP_BELOW = 5;

        /** The expanded editor's height; the bottom-right grip drags it. */
        int expandedHeight;
        private boolean resizing;
        private double resizeStartY;
        private int resizeStartHeight;
        private final Button wrapButton;
        private final Button deleteButton;
        private final net.minecraft.client.gui.components.EditBox singleBox;
        private net.minecraft.client.gui.components.MultiLineEditBox multiBox; // built lazily at the rendered width
        private String multiValue;      // formatting-preserving source of truth
        private String collapsedMirror; // what the single-line box was last given
        private final String initialText;
        private boolean expanded;
        boolean deleted;

        WrapRowEntry(String text, Component deleteTooltip, Runnable onDelete) {
            super(Component.empty(), false);
            this.initialText = text.trim();
            this.multiValue = text;
            // Carried across a rebuild when there is one; the newline default is
            // only for a row being seen for the first time.
            CarriedRow carried = takeCarriedExpansion(text);
            this.expanded = carried != null ? carried.expanded() : text.contains("\n");
            this.expandedHeight = carried != null ? carried.height() : BOX_HEIGHT_EXPANDED;

            this.singleBox = new net.minecraft.client.gui.components.EditBox(
                    Minecraft.getInstance().font, 0, 0, 200, 18, Component.empty()) {
                // the same suggestions as the expanded editor and the chat bar
                private final EditorCompletionPopup completion = new EditorCompletionPopup(new EditorCompletionPopup.Target() {
                    @Override
                    public String value() {
                        return getValue();
                    }

                    @Override
                    public int cursor() {
                        return getCursorPosition();
                    }

                    @Override
                    public void replaceToCursor(int start, String text) {
                        String value = getValue();
                        int cursor = Math.min(getCursorPosition(), value.length());
                        setValue(value.substring(0, start) + text + value.substring(cursor));
                        moveCursorTo(start + text.length(), false);
                    }

                    @Override
                    public boolean definition() {
                        return definitionRow();
                    }

                    @Override
                    public boolean perLine() {
                        return false;
                    }
                });

                @Override
                public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
                    if (completion.keyPressed(event)) {
                        return true;
                    }
                    // a multi-line paste into the collapsed box would lose its
                    // newlines — expand and paste into the real editor instead
                    if (event.isPaste()) {
                        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                        if (clip.indexOf('\n') >= 0 || clip.indexOf('\r') >= 0) {
                            pasteMultiline(clip);
                            return true;
                        }
                    }
                    boolean handled = super.keyPressed(event);
                    if (handled && (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE
                            || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE)) {
                        completion.onEdited();
                    }
                    return handled;
                }

                @Override
                public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
                    boolean handled = super.charTyped(event);
                    if (handled) {
                        completion.onEdited();
                    }
                    return handled;
                }

                @Override
                public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
                    super.onClick(event, doubleClick);
                    completion.dismiss();
                }

                @Override
                public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
                    super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
                    if (isFocused()) {
                        completion.render(graphics, Minecraft.getInstance().font,
                                getScreenX(getCursorPosition()), getY() + (getHeight() - 8) / 2);
                    }
                }
            };
            this.singleBox.setMaxLength(4000);
            String joined = singleLine(text);
            this.singleBox.setValue(joined);
            this.singleBox.moveCursorToStart(false); // show the start, not the tail
            this.collapsedMirror = joined;
            this.singleBox.addFormatter(this::styleCollapsed);

            this.wrapButton = Button.builder(wrapLabel(), button -> toggleWrap())
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.wrap_toggle")))
                    .build();

            this.deleteButton = Button.builder(Component.literal("✕").withStyle(net.minecraft.ChatFormatting.RED), button -> {
                        this.deleted = true;
                        onDelete.run();
                    })
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(deleteTooltip))
                    .build();
        }

        static String singleLine(String text) {
            return net.tupenter.script.Comments.flatten(text);
        }

        private Component wrapLabel() {
            return Component.literal(expanded ? "▾" : "▸");
        }

        private void toggleWrap() {
            if (expanded) {
                multiValue = currentMultiValue(); // keep the formatting for the next expand
                String joined = singleLine(multiValue);
                singleBox.setValue(joined);
                singleBox.moveCursorToStart(false);
                collapsedMirror = joined;
            } else {
                String current = singleBox.getValue();
                if (!current.equals(collapsedMirror)) {
                    multiValue = current; // edited while collapsed — content wins, formatting resets
                }
                if (multiBox != null) {
                    multiBox.setValue(multiValue);
                }
            }
            expanded = !expanded;
            wrapButton.setMessage(wrapLabel());
        }

        private String currentMultiValue() {
            return multiBox != null ? multiBox.getValue() : multiValue;
        }

        /** True for custom-command rows: their text is "name <params> = body". */
        boolean definitionRow() {
            return false;
        }

        /** Chat-style colors for the collapsed single-line view. */
        private net.minecraft.util.FormattedCharSequence styleCollapsed(String partial, int offset) {
            String value = singleBox.getValue();
            if (!value.equals(collapsedStyledFor)) {
                collapsedStyles = net.tupenter.command.ChatInputStyler.editorStyles(value, definitionRow());
                collapsedStyledFor = value;
            }
            return net.tupenter.command.ChatInputStyler.sequence(value, collapsedStyles, offset, offset + partial.length());
        }

        private String collapsedStyledFor;
        private net.minecraft.network.chat.Style[] collapsedStyles;

        /** A newline-bearing paste: splice at the cursor and jump to the expanded editor. */
        private void pasteMultiline(String clip) {
            String current = singleBox.getValue();
            int cursor = Math.max(0, Math.min(singleBox.getCursorPosition(), current.length()));
            multiValue = current.substring(0, cursor) + clip + current.substring(cursor);
            expanded = true;
            wrapButton.setMessage(wrapLabel());
            if (multiBox != null) {
                multiBox.setValue(multiValue);
            }
        }

        /** The multi-line editor wraps at build width, so rebuild when the row width changes. */
        private net.minecraft.client.gui.components.MultiLineEditBox ensureMultiBox(int width) {
            if (multiBox == null || multiBox.getWidth() != width) {
                String value = currentMultiValue();
                multiBox = new ScriptEditBox(Minecraft.getInstance().font, width, expandedHeight, definitionRow());
                multiBox.setCharacterLimit(4000);
                multiBox.setValue(value);
                multiValue = value;
            } else if (multiBox.getHeight() != expandedHeight) {
                // height changes in place: a rebuild would drop the caret and scroll
                multiBox.setHeight(expandedHeight);
            }
            return multiBox;
        }

        /** Newline-preserving value, whichever view is active. */
        String text() {
            if (expanded) {
                return currentMultiValue().trim();
            }
            String current = singleBox.getValue();
            return (current.equals(collapsedMirror) ? multiValue : current).trim();
        }

        /** Drawn over the editor after it renders, in either view. Nothing by default. */
        void decorateEditor(GuiGraphicsExtractor graphics, net.minecraft.client.gui.components.AbstractWidget box,
                            int mouseX, int mouseY) {
        }

        /** Widgets rendered before the wrap button (e.g. the scripts On/Off toggle). */
        List<net.minecraft.client.gui.components.AbstractWidget> leadingWidgets() {
            return List.of();
        }

        /**
         * The entry list never tells OTHER rows to unfocus, so clicking a
         * second box used to leave two blinking cursors. Vanilla handles the
         * click first (focusing whatever it hit), then every OTHER row is
         * cleared, which leaves exactly one live cursor.
         *
         * <p>Deliberately no hit-testing: an earlier version blurred by mouse
         * position and skipped the box under the cursor, but a hit test that
         * missed by a pixel stranded the very box being clicked. The parent's
         * setFocused(child) returns early when that child is already its
         * focused one, so nothing undid the blur, and the caret stayed hidden
         * until you clicked a different box. Rows with leading widgets (the
         * scripts tab, whose box shifts as the On/Off and ! buttons come and
         * go) hit that constantly. Never touching the clicked row's boxes
         * removes the failure mode rather than narrowing it.
         */
        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            // The grip claims the click before the editor sees it, so grabbing
            // the corner never moves the caret or starts a selection.
            if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                    && overGrip(event.x(), event.y())) {
                resizing = true;
                resizeStartY = event.y();
                resizeStartHeight = expandedHeight;
                blurEditorBoxesExcept(this);
                return true;
            }
            boolean handled = super.mouseClicked(event, doubleClick);
            blurEditorBoxesExcept(this);
            return handled;
        }

        @Override
        public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
            if (resizing) {
                int height = resizeStartHeight + (int) Math.round(event.y() - resizeStartY);
                expandedHeight = Math.max(BOX_HEIGHT_MIN, Math.min(BOX_HEIGHT_MAX, height));
                if (multiBox != null) {
                    multiBox.setHeight(expandedHeight);
                }
                return true;
            }
            return super.mouseDragged(event, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
            if (resizing) {
                resizing = false;
                return true;
            }
            return super.mouseReleased(event);
        }

        /** Over the expanded editor's bottom-right grip (with a little slack below it). */
        private boolean overGrip(double mouseX, double mouseY) {
            if (!expanded || multiBox == null) {
                return false;
            }
            int right = multiBox.getX() + multiBox.getWidth();
            int bottom = multiBox.getY() + multiBox.getHeight();
            return mouseX >= right - GRIP_SIZE && mouseX < right + 1
                    && mouseY >= bottom - GRIP_SIZE && mouseY < bottom + GRIP_BELOW;
        }

        /** The box this row had when it was last told to unfocus; see setFocused(boolean). */
        private net.minecraft.client.gui.components.events.GuiEventListener focusBeforeBlur;

        /**
         * Survive Cloth's subcategory re-focusing us. The script rows used to
         * live inside a Cloth subcategory, and its setFocused(child) has no
         * "already focused" check: clicking a row it already has focused calls
         * row.setFocused(false) then row.setFocused(true). For a container,
         * false means "drop your focused child" and true does nothing at all,
         * so the second click on a script box left the row with NO focused
         * child: no caret, SDL text input stopped, keystrokes going nowhere,
         * and every further click repeating it. Custom-command rows sit
         * directly in the list, whose setFocused does have the check, which is
         * why only the scripts tab broke.
         *
         * <p>So remember the child on false, and hand it back when true arrives
         * with nothing focused. A genuine move to another row only ever sends
         * false, so this row stays blurred then.
         */
        @Override
        public void setFocused(boolean focused) {
            if (focused) {
                if (getFocused() == null && focusBeforeBlur != null) {
                    setFocused(focusBeforeBlur);
                }
                focusBeforeBlur = null;
            } else {
                focusBeforeBlur = getFocused();
                setFocused((net.minecraft.client.gui.components.events.GuiEventListener) null);
            }
        }

        /**
         * Give up focus and any highlight in this row's boxes, THROUGH the row.
         * Calling box.setFocused(false) directly left the row still believing
         * that box was its focused child, so the next click on it hit the
         * row's "already focused" early return and never called
         * setFocused(true). On 26.3 that is doubly fatal: setFocused is also
         * what starts and stops SDL text input, so the box stayed caretless
         * AND deaf to typing until focus went somewhere else and came back.
         */
        void blurBoxes() {
            if (getFocused() != null) {
                setFocused(null);
            }
            blurStray(singleBox);
            dropSelection(singleBox);
            if (multiBox != null) {
                blurStray(multiBox);
                dropSelection(multiBox);
            }
        }

        @Override
        public int getItemHeight() {
            return expanded ? expandedHeight + EDITOR_COUNTER_ROOM : 24;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            int cursor = x;
            for (net.minecraft.client.gui.components.AbstractWidget widget : leadingWidgets()) {
                widget.setX(cursor);
                widget.setY(y);
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
                cursor += widget.getWidth() + 4;
            }

            wrapButton.setX(cursor);
            wrapButton.setY(y);
            wrapButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
            cursor += 24;

            int boxWidth = entryWidth - (cursor - x) - 24;
            if (expanded) {
                net.minecraft.client.gui.components.MultiLineEditBox box = ensureMultiBox(boxWidth);
                box.setX(cursor);
                box.setY(y);
                box.extractRenderState(graphics, mouseX, mouseY, partialTick);
                drawResizeGrip(graphics, box, resizing || overGrip(mouseX, mouseY));
                decorateEditor(graphics, box, mouseX, mouseY);
            } else {
                singleBox.setX(cursor);
                singleBox.setY(y + 1);
                singleBox.setWidth(boxWidth);
                singleBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
                decorateEditor(graphics, singleBox, mouseX, mouseY);
            }

            deleteButton.setX(x + entryWidth - 20);
            deleteButton.setY(y);
            deleteButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isEdited() {
            return deleted || !text().equals(initialText);
        }

        private List<net.minecraft.client.gui.components.AbstractWidget> allWidgets() {
            List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>(leadingWidgets());
            widgets.add(wrapButton);
            widgets.add(expanded && multiBox != null ? multiBox : singleBox);
            widgets.add(deleteButton);
            return widgets;
        }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return allWidgets();
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return allWidgets();
        }

        @Override
        public String getValue() {
            return text();
        }

        @Override
        public Optional<String> getDefaultValue() {
            return Optional.empty();
        }

        @Override
        public void save() {
        }
    }

    /**
     * The resend preset/macro list as ONE tall editor — one command per line
     * (blank lines ignored), per-line syntax highlighting. Unlike the Scripts
     * tab, here a newline SEPARATES commands.
     */
    private static class PresetBoxEntry extends AbstractConfigListEntry<String> {
        private static final int BOX_HEIGHT = 120;
        private final String initialText;
        private ScriptEditBox box; // built lazily at the rendered width
        private String value;

        PresetBoxEntry(String text) {
            super(Component.empty(), false);
            this.initialText = text;
            this.value = text;
        }

        private ScriptEditBox ensureBox(int width) {
            if (box == null || box.getWidth() != width) {
                String current = box != null ? box.getValue() : value;
                box = new ScriptEditBox(Minecraft.getInstance().font, width, BOX_HEIGHT, false, true);
                box.setCharacterLimit(16000);
                box.setValue(current);
                value = current;
            }
            return box;
        }

        String text() {
            return box != null ? box.getValue() : value;
        }

        /** Replace the editor contents in place (used by Import from History). */
        void importText(String text) {
            this.value = text;
            if (box != null) {
                box.setValue(text);
            }
        }

        /** Same as WrapRowEntry.blurBoxes: unfocus through the row, not the box. */
        void blurBox() {
            if (getFocused() != null) {
                setFocused(null);
            }
            if (box != null) {
                blurStray(box);
                dropSelection(box);
            }
        }

        @Override
        public int getItemHeight() {
            return BOX_HEIGHT + EDITOR_COUNTER_ROOM;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            ScriptEditBox b = ensureBox(entryWidth);
            b.setX(x);
            b.setY(y);
            b.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            boolean handled = super.mouseClicked(event, doubleClick);
            blurEditorBoxesExcept(this);
            return handled;
        }

        private List<net.minecraft.client.gui.components.AbstractWidget> allWidgets() {
            List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
            if (box != null) {
                widgets.add(box);
            }
            return widgets;
        }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return allWidgets();
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return allWidgets();
        }

        @Override
        public boolean isEdited() {
            return !text().equals(initialText);
        }

        @Override
        public String getValue() {
            return text();
        }

        @Override
        public Optional<String> getDefaultValue() {
            return Optional.empty();
        }

        @Override
        public void save() {
        }
    }

    /** One custom command as a row: wrap toggle, definition box, delete. */
    private static class CommandRowEntry extends WrapRowEntry {
        CommandRowEntry(String definition) {
            super(definition, Component.translatable("tooltip.tupenter.delete_command"), () -> {
                TupenterConfig.INSTANCE.aliases = collectCommandRows();
                reopenScreen(TAB_ALIASES);
            });
        }

        @Override
        boolean definitionRow() {
            return true;
        }
    }

    // Tab indices are ConfigScreenAccess.TAB_*, and they are the
    // getOrCreateCategory order in createScreen: general, scripting, aliases,
    // scripts. They live over there because the command that names them must
    // not drag Cloth onto the classpath just to read an int.
    private static final int TAB_ALIASES = ConfigScreenAccess.TAB_ALIASES;
    private static final int TAB_SCRIPTS = ConfigScreenAccess.TAB_SCRIPTS;

    /**
     * Collapse a box's selection to its cursor.
     *
     * <p>Losing focus does NOT clear a selection — an unfocused box goes on
     * drawing its highlighted range quite happily — so starting a selection in a
     * second box left the first one still looking selected, as if two places were
     * live at once.
     */
    /**
     * The textarea-style resize grip: two short diagonal strokes tucked into the
     * editor's bottom-right corner, brighter while hovered or being dragged.
     */
    private static void drawResizeGrip(GuiGraphicsExtractor graphics,
                                       net.minecraft.client.gui.components.AbstractWidget box, boolean active) {
        int right = box.getX() + box.getWidth() - 2;
        int bottom = box.getY() + box.getHeight() - 2;
        int color = active ? 0xFFFFFFFF : 0xFF8B8B8B;
        for (int i = 0; i < 6; i++) {          // long stroke
            graphics.fill(right - i, bottom - 5 + i, right - i + 1, bottom - 4 + i, color);
        }
        for (int i = 0; i < 3; i++) {          // short stroke, nearer the corner
            graphics.fill(right - i, bottom - 2 + i, right - i + 1, bottom - 1 + i, color);
        }
    }

    private static void dropSelection(net.minecraft.client.gui.components.EditBox box) {
        box.setHighlightPos(box.getCursorPosition());
    }

    /** Same, for the multi-line editors: seekCursor syncs selectCursor when not selecting. */
    private static void dropSelection(net.minecraft.client.gui.components.MultiLineEditBox box) {
        net.minecraft.client.gui.components.MultilineTextField field = box.textField;
        field.setSelecting(false);
        field.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, field.cursor());
    }

    /**
     * Exactly one editor cursor at a time, across every row and both tabs. The
     * row that was clicked keeps whatever vanilla just focused; every other one
     * is cleared. See WrapRowEntry.mouseClicked for why the clicked row is left
     * strictly alone.
     */
    /**
     * A box that is still focused after its row let go of it (a row that never
     * tracked it, or an entry base class that clears the reference without
     * unfocusing the child). Only touches a box that really is focused, so an
     * idle box never sends a stray "stop text input".
     */
    /**
     * Space a multi-line editor's row leaves below the box. Vanilla draws the
     * character counter ("624/4000") 4px under the box in a 9px font, so the
     * old 6px gap let the next row cover most of it.
     */
    private static final int EDITOR_COUNTER_ROOM = 16;

    private static void blurStray(net.minecraft.client.gui.components.AbstractWidget box) {
        if (box.isFocused()) {
            box.setFocused(false);
        }
    }

    private static void blurEditorBoxesExcept(Object clickedRow) {
        for (WrapRowEntry row : commandRows) {
            if (row != clickedRow) {
                row.blurBoxes();
            }
        }
        for (WrapRowEntry row : scriptRows) {
            if (row != clickedRow) {
                row.blurBoxes();
            }
        }
        if (presetBox != null && presetBox != clickedRow) {
            presetBox.blurBox();
        }
    }

    /**
     * Open the screen fresh on {@code tabIndex}. The counterpart to
     * {@link #reopenScreen(int)}, which rebuilds an ALREADY-open screen and puts
     * the scroll back; this is an entry point, so it starts at the top.
     *
     * <p>Package-private on purpose: everything outside goes through
     * {@link ConfigScreenAccess}, which checks Cloth is installed first.
     */
    static void openAtTab(int tabIndex) {
        carriedExpansion.clear(); // a fresh open starts from the newline default
        Minecraft client = Minecraft.getInstance();
        Screen screen = createScreen(client.gui.screen());
        if (screen instanceof me.shedaniel.clothconfig2.gui.AbstractConfigScreen cloth) {
            cloth.selectedCategoryIndex = tabIndex;
        }
        client.gui.setScreen(screen);
    }

    /** Rebuild the config screen WITHOUT losing the tab or the scroll position. */
    private static void reopenScreen(int tabIndex) {
        double scroll = 0;
        if (Minecraft.getInstance().gui.screen() instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen old
                && old.listWidget != null) {
            scroll = old.listWidget.getScroll();
        }
        carryExpansion(); // before createScreen clears the row lists
        Screen screen;
        rebuilding = true;
        try {
            screen = createScreen(cachedParent);
        } finally {
            rebuilding = false;
        }
        if (screen instanceof me.shedaniel.clothconfig2.gui.AbstractConfigScreen cloth) {
            cloth.selectedCategoryIndex = tabIndex;
        }
        Minecraft.getInstance().gui.setScreen(screen); // Gui.setScreen calls Screen.init synchronously
        if (screen instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen cloth) {
            restoreScroll(cloth, scroll);
            // ...and again on the next few ticks, because this first attempt can
            // be clamped against a list that has not finished measuring itself.
            pendingScroll = scroll;
            pendingRestoreTicks = 3;
        }
    }

    /**
     * Put the list back where it was, clamped if it shrank. No-op if the screen
     * moved on.
     *
     * <p>The tickList() is the whole fix, and it is not optional. capYPosition
     * clamps to getMaxScroll(), which sums the heights of
     * <em>visibleChildren()</em> — a list Cloth caches in a field and refreshes
     * ONLY from tickList(). Straight after init() that cache is still empty, so
     * getMaxScroll() reads 0 and the clamp silently pins any target to the top.
     * Deferring the call doesn't help either: Minecraft.runTick drains the
     * execute queue before it ticks the screen, so a queued restore lands just
     * as early. Ticking the list ourselves populates the cache first, and then
     * the clamp has real content to measure against.
     */
    private static void restoreScroll(me.shedaniel.clothconfig2.gui.ClothConfigScreen cloth, double target) {
        if (Minecraft.getInstance().gui.screen() == cloth && cloth.listWidget != null) {
            cloth.listWidget.tickList();
            cloth.listWidget.capYPosition(target);
        }
    }

    private static double pendingScroll;
    private static int pendingRestoreTicks;

    /**
     * Re-apply the scroll for a few ticks after a rebuild, until it sticks.
     *
     * <p>The first attempt can land short through no fault of its own. An
     * expanded row builds its multi-line editor LAZILY, at the width it is first
     * rendered at, so until that render it reports a stub height: the list
     * measures 62px shorter than it will be, getMaxScroll() comes out 62 too
     * small, and capYPosition clamps an otherwise-valid target down to it.
     *
     * <p>Which is exactly why this only ever showed at the BOTTOM of the list.
     * From the middle the target is under the ceiling either way and nothing
     * clamps; scrolled to the end, it was cut by up to a full expanded row — a
     * quarter of the viewport, which is what got reported.
     *
     * <p>Stops the moment the value sticks, so it is a frame or two of
     * correction and never a fight with someone who is scrolling.
     */
    // ---- "About tick scripts": collapsible, shown expanded only the first time ----

    private static me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry scriptsHelp;
    /** Expanded state as last built or last seen, to notice the user toggling it. */
    private static boolean scriptsHelpShown;
    /** This session's state, carried across in-screen rebuilds; null on a fresh open. */
    private static Boolean scriptsHelpSession;
    /** True while reopenScreen rebuilds an already-open screen. */
    private static boolean rebuilding;

    /**
     * The long tick-script explainer, folded into its own subcategory so it
     * stops being a wall of text above every script.
     *
     * <p>It opens expanded the very first time anyone opens the tab, and from
     * then on starts collapsed unless the user expanded it last time. The
     * first view writes "collapsed" straight away, so simply reading it once
     * is enough; toggling is picked up by {@link #tickScriptsHelpState()}.
     */
    private static AbstractConfigListEntry<?> buildScriptsHelp(ConfigEntryBuilder entryBuilder) {
        if (!rebuilding) {
            scriptsHelpSession = null; // a fresh open goes by the saved state
        }
        TupenterConfig config = TupenterConfig.INSTANCE;
        boolean expanded = scriptsHelpSession != null ? scriptsHelpSession : config.scriptsHelpState != 2;
        if (config.scriptsHelpState == 0) {
            config.scriptsHelpState = 2;
            TupenterConfig.save();
        }
        List<AbstractConfigListEntry> body = new ArrayList<>();
        body.add(entryBuilder.startTextDescription(Component.translatable("tooltip.tupenter.tick_scripts")).build());
        scriptsHelp = entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.about_scripts"), body)
                .setExpanded(expanded)
                .build();
        scriptsHelpShown = expanded;
        return scriptsHelp;
    }

    /**
     * Save the help section's state the moment it is toggled, so it sticks
     * whether the screen is closed with Done or Escape. Called every client
     * tick; does nothing unless that screen is open and the state changed.
     */
    public static void tickScriptsHelpState() {
        if (scriptsHelp == null
                || !(Minecraft.getInstance().gui.screen() instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen)) {
            return;
        }
        boolean expanded = scriptsHelp.isExpanded();
        if (expanded != scriptsHelpShown) {
            scriptsHelpShown = expanded;
            scriptsHelpSession = expanded;
            TupenterConfig.INSTANCE.scriptsHelpState = expanded ? 1 : 2;
            TupenterConfig.save();
        }
    }

    public static void tickPendingRestore() {
        if (pendingRestoreTicks <= 0) {
            return;
        }
        pendingRestoreTicks--;
        if (!(Minecraft.getInstance().gui.screen() instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen cloth)
                || cloth.listWidget == null) {
            pendingRestoreTicks = 0;
            return;
        }
        restoreScroll(cloth, pendingScroll);
        if (Math.abs(cloth.listWidget.getScroll() - pendingScroll) < 0.5) {
            pendingRestoreTicks = 0; // it took — stop touching it
        }
    }

    /**
     * A tick-script row: [On/Off] [Global/World] [wrap] [edit box] [✕]. The
     * On/Off arms the script for the world the screen was opened in; outside a
     * world both buttons lock (text edits still work, arming and moving don't).
     *
     * <p>The scope button only flips {@link #global}; commitScriptEdits does the
     * actual move between the two stores, so nothing changes until save or the
     * next rebuild, and the row stays where it is until then.
     */
    private static class ScriptRowEntry extends WrapRowEntry {
        /** Darkens the editor of a script that won't run (about 55% toward black). */
        private static final int DIM_OVERLAY = 0x8C000000;

        /** How long a "Sure?" waits for its second click. */
        private static final long SCOPE_CONFIRM_MS = 3000;

        final String id;
        private final Button toggleButton;
        private final Button scopeButton;
        private final boolean initialEnabled;
        private final boolean initialGlobal;
        private final boolean toggleLocked;
        boolean enabled;
        boolean global;
        /** While a Global to World move waits for its confirming click: when it gives up. 0 = not asking. */
        private long confirmUntil;

        ScriptRowEntry(String id, String text, boolean global, boolean enabled, boolean toggleLocked) {
            super(text, Component.translatable("tooltip.tupenter.delete_script"), () -> {
                commitScriptEdits();
                reopenScreen(TAB_SCRIPTS);
            });
            this.id = id;
            this.initialEnabled = enabled;
            this.enabled = enabled;
            this.initialGlobal = global;
            this.global = global;
            this.toggleLocked = toggleLocked;

            this.toggleButton = Button.builder(toggleLabel(), button -> {
                        this.enabled = !this.enabled;
                        button.setMessage(toggleLabel());
                    })
                    .bounds(0, 0, 24, 20) // just fits "Off"
                    .tooltip(Tooltip.create(Component.translatable(
                            toggleLocked ? "tooltip.tupenter.tick_script_toggle_locked" : "tooltip.tupenter.tick_script_toggle")))
                    .build();
            this.toggleButton.active = !toggleLocked;

            this.scopeButton = Button.builder(Component.empty(), button -> clickScope())
                    .bounds(0, 0, 35, 20) // just fits "Global"
                    .build();
            this.scopeButton.active = !toggleLocked; // no world: everything is Global
            refreshScope();
        }

        /**
         * World to Global just happens: other worlds gain a disarmed row, which
         * costs nothing. Global to World deletes the script from every other
         * world, so when it is armed in any of them the first click only asks
         * ("Sure?") and a second click within a few seconds does it.
         */
        private void clickScope() {
            if (global && confirmUntil == 0 && armedInOtherWorlds() > 0) {
                confirmUntil = System.currentTimeMillis() + SCOPE_CONFIRM_MS;
            } else {
                confirmUntil = 0;
                global = !global;
            }
            refreshScope();
        }

        /** Worlds other than this one that have this script switched on. */
        private int armedInOtherWorlds() {
            int count = 0;
            for (java.util.Map.Entry<String, TupenterConfig.WorldScriptState> entry
                    : TupenterConfig.INSTANCE.worldScripts.entrySet()) {
                if (!entry.getKey().equals(scriptsWorldKey) && entry.getValue().enabledGlobalIds.contains(id)) {
                    count++;
                }
            }
            return count;
        }

        private void refreshScope() {
            if (confirmUntil != 0) {
                scopeButton.setMessage(Component.translatable("text.tupenter.script_scope_confirm")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                scopeButton.setTooltip(Tooltip.create(Component.translatable(
                        "tooltip.tupenter.script_scope_confirm", armedInOtherWorlds())));
                return;
            }
            scopeButton.setMessage(global
                    ? Component.translatable("text.tupenter.script_scope_global").withStyle(net.minecraft.ChatFormatting.AQUA)
                    : Component.translatable("text.tupenter.script_scope_world").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
            scopeButton.setTooltip(Tooltip.create(Component.translatable(toggleLocked
                    ? "tooltip.tupenter.script_scope_locked"
                    : global ? "tooltip.tupenter.script_scope_global" : "tooltip.tupenter.script_scope_world")));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            if (confirmUntil != 0 && System.currentTimeMillis() > confirmUntil) {
                confirmUntil = 0; // the "Sure?" went unanswered: back to plain Global
                refreshScope();
            }
            toggleButton.setMessage(toggleLabel()); // follows the master switch live
            super.extractRenderState(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, partialTick);
        }

        private Component toggleLabel() {
            if (toggleLocked) {
                return Component.literal("—").withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
            }
            if (!enabled) {
                return Component.literal("Off").withStyle(net.minecraft.ChatFormatting.RED);
            }
            // armed but held back by the master switch: still "On", just not lit
            return Component.literal("On").withStyle(masterOn()
                    ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.DARK_GREEN);
        }

        /** The master switch as currently shown on screen, saved or not. */
        private static boolean masterOn() {
            return tickMasterEntry != null ? tickMasterEntry.getValue() : TupenterConfig.INSTANCE.tickScriptsEnabled;
        }

        /** Would this script actually run in this world, going by what the screen shows now? */
        private boolean runs() {
            return enabled && !toggleLocked && masterOn();
        }

        @Override
        List<net.minecraft.client.gui.components.AbstractWidget> leadingWidgets() {
            return List.of(toggleButton, scopeButton);
        }

        /**
         * A small gold "!" in the editor's top-right corner when the script sends
         * a command to the server, with the explanation as a hover tooltip.
         *
         * <p>It used to be a read-only button beside the toggle, which cost a
         * button's width and shoved the editor sideways every time the warning
         * appeared or vanished as you typed. Drawn inside the box, nothing moves.
         *
         * <p>Recomputed from the live text, so it tracks what you type. And
         * deliberately no "safe" counterpart: the scan can prove a script sends,
         * but never that it doesn't (a $...$ expression can produce a command
         * name at run time), so the absence of the mark means nothing was found.
         */
        @Override
        void decorateEditor(GuiGraphicsExtractor graphics, net.minecraft.client.gui.components.AbstractWidget box,
                            int mouseX, int mouseY) {
            drawSendsBadge(graphics, box, mouseX, mouseY);
            // A script that won't run here (switched Off, or the master switch
            // is off) gets its editor dimmed, so what is live stands out at a
            // glance. Not while you're typing in it: editing reads normally.
            if (!runs() && !box.isFocused()) {
                graphics.fill(box.getX(), box.getY(), box.getX() + box.getWidth(), box.getY() + box.getHeight(), DIM_OVERLAY);
            }
        }

        private void drawSendsBadge(GuiGraphicsExtractor graphics, net.minecraft.client.gui.components.AbstractWidget box,
                                    int mouseX, int mouseY) {
            if (!net.tupenter.command.ServerTrafficScan.sendsToServer(text())) {
                return;
            }
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            int glyphX = box.getX() + box.getWidth() - 5 - font.width("!");
            int glyphY = box.getHeight() > 20 ? box.getY() + 4 : box.getY() + (box.getHeight() - 8) / 2;
            graphics.text(font, "!", glyphX, glyphY, 0xFFFFAA00, true);
            if (mouseX >= glyphX - 2 && mouseX <= glyphX + font.width("!") + 2
                    && mouseY >= glyphY - 2 && mouseY <= glyphY + 10) {
                graphics.setTooltipForNextFrame(font.split(
                        Component.translatable("tooltip.tupenter.script_sends_to_server"), 220), mouseX, mouseY);
            }
        }

        @Override
        public boolean isEdited() {
            return super.isEdited() || enabled != initialEnabled || global != initialGlobal;
        }
    }

    private static class ImportButtonEntry extends AbstractConfigListEntry<Object> {
        private final Button button;

        public ImportButtonEntry(Component text, Component tooltip, Runnable onClick) {
            super(Component.empty(), false);
            this.button = Button.builder(text, b -> onClick.run())
                    .bounds(0, 0, 150, 20)
                    .tooltip(Tooltip.create(tooltip))
                    .build();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            this.button.setX(x + (entryWidth / 2) - (this.button.getWidth() / 2));
            this.button.setY(y);
            this.button.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return Collections.singletonList(button);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return Collections.singletonList(button);
        }

        @Override
        public Object getValue() { return null; }

        @Override
        public Optional<Object> getDefaultValue() { return Optional.empty(); }

        @Override
        public void save() {}
    }
}
