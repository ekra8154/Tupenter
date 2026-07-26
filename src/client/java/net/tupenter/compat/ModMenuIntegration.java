package net.tupenter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

public class ModMenuIntegration implements ModMenuApi {
    private static Screen cachedParent;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModMenuIntegration::createScreen;
    }

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
        // SCRIPTS TAB — tick scripts, armed PER WORLD. Two sections:
        // Global (shared definitions, per-world On/Off) and This world
        // (definitions that exist only here). Unconfigured world = nothing runs.
        // =====================================================================

        scripts.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.tick_scripts_enabled"), TupenterConfig.INSTANCE.tickScriptsEnabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.tick_scripts_enabled"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.tickScriptsEnabled = newValue)
                .build());

        if (!TupenterConfig.INSTANCE.tickScriptsEnabled) {
            // clears up "I aborted but scripts still show ✔" — arming is preserved, the master just gates it
            scripts.addEntry(entryBuilder.startTextDescription(
                    Component.translatable("text.tupenter.tick_master_off")).build());
        }

        scriptsWorldKey = TupenterModClient.currentWorldKey();
        scripts.addEntry(entryBuilder.startTextDescription(scriptsWorldKey != null
                ? Component.translatable("text.tupenter.scripts_world", scriptsWorldKey)
                : Component.translatable("text.tupenter.scripts_no_world")).build());
        scripts.addEntry(entryBuilder.startTextDescription(Component.translatable("tooltip.tupenter.tick_scripts")).build());

        TupenterConfig.WorldScriptState worldState = TupenterConfig.INSTANCE.worldState(scriptsWorldKey);

        // --- Global scripts: [On/Off for THIS world] [wrap] [text] [✕] ---
        globalScriptRows.clear();
        List<AbstractConfigListEntry> globalEntries = new ArrayList<>();
        for (TupenterConfig.GlobalScript definition : TupenterConfig.INSTANCE.globalScripts) {
            boolean enabledHere = worldState != null && worldState.enabledGlobalIds.contains(definition.id);
            GlobalScriptEntry row = new GlobalScriptEntry(definition.id, definition.text, enabledHere, scriptsWorldKey == null);
            globalScriptRows.add(row);
            globalEntries.add(row);
        }
        globalEntries.add(new ImportButtonEntry(
                Component.translatable("text.tupenter.add_script"),
                Component.translatable("tooltip.tupenter.add_script"),
                () -> {
                    commitScriptEdits();
                    TupenterConfig.INSTANCE.globalScripts.add(new TupenterConfig.GlobalScript(
                            TupenterConfig.GlobalScript.newId(), "/echo new script"));
                    reopenScreen(TAB_SCRIPTS);
                }
        ));
        scripts.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.global_scripts"), globalEntries)
                .setExpanded(true)
                .setTooltip(Component.translatable("tooltip.tupenter.global_scripts"))
                .build());

        // --- This world's scripts: visible set swaps with the world ---
        worldScriptRows.clear();
        List<AbstractConfigListEntry> worldEntries = new ArrayList<>();
        if (scriptsWorldKey == null) {
            worldEntries.add(entryBuilder.startTextDescription(Component.translatable("text.tupenter.world_scripts_no_world")).build());
        } else {
            if (worldState != null) {
                for (TupenterConfig.WorldScript script : worldState.scripts) {
                    WorldScriptEntry row = new WorldScriptEntry(script.id, script.text, script.enabled);
                    worldScriptRows.add(row);
                    worldEntries.add(row);
                }
            }
            worldEntries.add(new ImportButtonEntry(
                    Component.translatable("text.tupenter.add_world_script"),
                    Component.translatable("tooltip.tupenter.add_world_script"),
                    () -> {
                        commitScriptEdits();
                        TupenterConfig.INSTANCE.worldStateOrCreate(scriptsWorldKey).scripts
                                .add(new TupenterConfig.WorldScript(TupenterConfig.WorldScript.newId(), "/echo new script", false));
                        reopenScreen(TAB_SCRIPTS);
                    }
            ));
        }
        scripts.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.world_scripts"), worldEntries)
                .setExpanded(true)
                .setTooltip(Component.translatable("tooltip.tupenter.world_scripts"))
                .build());

        java.util.Set<String> namesBeforeEdit = new java.util.HashSet<>(CommandAliasManager.getAliasMap().keySet());
        builder.setSavingRunnable(() -> {
            commitScriptEdits();
            TupenterConfig.INSTANCE.pruneWorldScriptStates();
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

    private static final List<GlobalScriptEntry> globalScriptRows = new ArrayList<>();
    private static final List<WorldScriptEntry> worldScriptRows = new ArrayList<>();
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
     * Pushes the current script rows into the live config: global definitions,
     * plus — when the screen was opened in a world — that world's enable set
     * and its own script list. Called on save and before any row-list rebuild.
     */
    private static void commitScriptEdits() {
        List<TupenterConfig.GlobalScript> globals = new ArrayList<>();
        for (GlobalScriptEntry row : globalScriptRows) {
            if (row.deleted || row.text().isEmpty()) {
                continue;
            }
            globals.add(new TupenterConfig.GlobalScript(row.id, row.text()));
        }
        TupenterConfig.INSTANCE.globalScripts = globals;

        if (scriptsWorldKey == null) {
            return; // arming is locked without a world; nothing per-world to write
        }
        TupenterConfig.WorldScriptState state = TupenterConfig.INSTANCE.worldStateOrCreate(scriptsWorldKey);
        List<String> enabledIds = new ArrayList<>();
        for (GlobalScriptEntry row : globalScriptRows) {
            if (!row.deleted && !row.text().isEmpty() && row.enabled) {
                enabledIds.add(row.id);
            }
        }
        state.enabledGlobalIds = enabledIds;

        List<TupenterConfig.WorldScript> worldScripts = new ArrayList<>();
        for (WorldScriptEntry row : worldScriptRows) {
            if (!row.deleted && !row.text().isEmpty()) {
                String id = row.id == null || row.id.isEmpty() ? TupenterConfig.WorldScript.newId() : row.id;
                worldScripts.add(new TupenterConfig.WorldScript(id, row.text(), row.enabled));
            }
        }
        state.scripts = worldScripts;
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
            this.expanded = text.contains("\n");

            this.singleBox = new net.minecraft.client.gui.components.EditBox(
                    Minecraft.getInstance().font, 0, 0, 200, 18, Component.empty()) {
                @Override
                public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
                    // a multi-line paste into the collapsed box would lose its
                    // newlines — expand and paste into the real editor instead
                    if (event.isPaste()) {
                        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                        if (clip.indexOf('\n') >= 0 || clip.indexOf('\r') >= 0) {
                            pasteMultiline(clip);
                            return true;
                        }
                    }
                    return super.keyPressed(event);
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
            return text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
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
                multiBox = new ScriptEditBox(Minecraft.getInstance().font, width, BOX_HEIGHT_EXPANDED, definitionRow());
                multiBox.setCharacterLimit(4000);
                multiBox.setValue(value);
                multiValue = value;
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

        /** Widgets rendered before the wrap button (e.g. the scripts On/Off toggle). */
        List<net.minecraft.client.gui.components.AbstractWidget> leadingWidgets() {
            return List.of();
        }

        /**
         * The entry list never tells OTHER rows to unfocus, so clicking a
         * second box used to leave two blinking cursors. Any click anywhere
         * on a row first blurs every editor box, then normal handling
         * focuses the one actually hit.
         */
        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            blurAllEditorBoxes();
            return super.mouseClicked(event, doubleClick);
        }

        void blurBoxes() {
            singleBox.setFocused(false);
            if (multiBox != null) {
                multiBox.setFocused(false);
            }
        }

        @Override
        public int getItemHeight() {
            return expanded ? BOX_HEIGHT_EXPANDED + 6 : 24;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            int cursor = x;
            for (net.minecraft.client.gui.components.AbstractWidget widget : leadingWidgets()) {
                widget.setX(cursor);
                widget.setY(y);
                widget.render(graphics, mouseX, mouseY, partialTick);
                cursor += widget.getWidth() + 4;
            }

            wrapButton.setX(cursor);
            wrapButton.setY(y);
            wrapButton.render(graphics, mouseX, mouseY, partialTick);
            cursor += 24;

            int boxWidth = entryWidth - (cursor - x) - 24;
            if (expanded) {
                net.minecraft.client.gui.components.MultiLineEditBox box = ensureMultiBox(boxWidth);
                box.setX(cursor);
                box.setY(y);
                box.render(graphics, mouseX, mouseY, partialTick);
            } else {
                singleBox.setX(cursor);
                singleBox.setY(y + 1);
                singleBox.setWidth(boxWidth);
                singleBox.render(graphics, mouseX, mouseY, partialTick);
            }

            deleteButton.setX(x + entryWidth - 20);
            deleteButton.setY(y);
            deleteButton.render(graphics, mouseX, mouseY, partialTick);
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

        void blurBox() {
            if (box != null) {
                box.setFocused(false);
            }
        }

        @Override
        public int getItemHeight() {
            return BOX_HEIGHT + 6;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            ScriptEditBox b = ensureBox(entryWidth);
            b.setX(x);
            b.setY(y);
            b.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            blurAllEditorBoxes();
            return super.mouseClicked(event, doubleClick);
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

    // getOrCreateCategory order in createScreen: general, scripting, aliases, scripts
    private static final int TAB_ALIASES = 2;
    private static final int TAB_SCRIPTS = 3;

    /** Exactly one editor cursor at a time, across every row and both tabs. */
    private static void blurAllEditorBoxes() {
        for (WrapRowEntry row : commandRows) {
            row.blurBoxes();
        }
        for (WrapRowEntry row : globalScriptRows) {
            row.blurBoxes();
        }
        for (WrapRowEntry row : worldScriptRows) {
            row.blurBoxes();
        }
        if (presetBox != null) {
            presetBox.blurBox();
        }
    }

    /** Rebuild the config screen WITHOUT losing the tab or the scroll position. */
    private static void reopenScreen(int tabIndex) {
        double scroll = 0;
        if (Minecraft.getInstance().screen instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen old
                && old.listWidget != null) {
            scroll = old.listWidget.getScroll();
        }
        Screen screen = createScreen(cachedParent);
        if (screen instanceof me.shedaniel.clothconfig2.gui.AbstractConfigScreen cloth) {
            cloth.selectedCategoryIndex = tabIndex;
        }
        Minecraft.getInstance().setScreen(screen); // init() runs synchronously in here
        if (screen instanceof me.shedaniel.clothconfig2.gui.ClothConfigScreen cloth) {
            // capYPosition clamps to getMaxScroll(), which is 0 until the list
            // has laid out on its first render — restoring now would snap us to
            // the top. Defer to the next main-thread pass, after that frame.
            double target = scroll;
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == cloth && cloth.listWidget != null) {
                    cloth.listWidget.capYPosition(target); // clamps if the list shrank
                }
            });
        }
    }

    /**
     * A tick-script row: [On/Off] [wrap] [edit box] [✕]. The On/Off arms the
     * script for the world the screen was opened in; outside a world the
     * toggle locks ("—") — text edits still work, arming doesn't.
     */
    private abstract static class ScriptRowEntry extends WrapRowEntry {
        private final Button toggleButton;
        private final Button sendsWarning;
        private final boolean initialEnabled;
        private final boolean toggleLocked;
        boolean enabled;

        ScriptRowEntry(String text, boolean enabled, boolean toggleLocked) {
            super(text, Component.translatable("tooltip.tupenter.delete_script"), () -> {
                commitScriptEdits();
                reopenScreen(TAB_SCRIPTS);
            });
            this.initialEnabled = enabled;
            this.enabled = enabled;
            this.toggleLocked = toggleLocked;

            this.toggleButton = Button.builder(toggleLabel(), button -> {
                        this.enabled = !this.enabled;
                        button.setMessage(toggleLabel());
                    })
                    .bounds(0, 0, 40, 20)
                    .tooltip(Tooltip.create(Component.translatable(
                            toggleLocked ? "tooltip.tupenter.tick_script_toggle_locked" : "tooltip.tupenter.tick_script_toggle")))
                    .build();
            this.toggleButton.active = !toggleLocked;

            // Shown only when the body contains a command that leaves the client.
            // Deliberately no "safe" counterpart: the scan can prove a script
            // sends, but never that it doesn't — a $...$ expression can produce a
            // command name at run time. A reassuring badge would be a promise we
            // can't keep, so the absence of this one means nothing was found.
            this.sendsWarning = Button.builder(
                            Component.literal("!").withStyle(net.minecraft.ChatFormatting.GOLD), button -> { })
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.script_sends_to_server")))
                    .build();
        }

        private Component toggleLabel() {
            if (toggleLocked) {
                return Component.literal("—").withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
            }
            return enabled
                    ? Component.literal("On").withStyle(net.minecraft.ChatFormatting.GREEN)
                    : Component.literal("Off").withStyle(net.minecraft.ChatFormatting.RED);
        }

        // Recomputed from the live text rather than the initial value, so the
        // warning appears and disappears as you type rather than after a reopen.
        @Override
        List<net.minecraft.client.gui.components.AbstractWidget> leadingWidgets() {
            return net.tupenter.command.ServerTrafficScan.sendsToServer(text())
                    ? List.of(toggleButton, sendsWarning)
                    : List.of(toggleButton);
        }

        @Override
        public boolean isEdited() {
            return super.isEdited() || enabled != initialEnabled;
        }
    }

    /** Shared definition; the toggle is this world's arming state for it. */
    private static class GlobalScriptEntry extends ScriptRowEntry {
        final String id;

        GlobalScriptEntry(String id, String text, boolean enabledHere, boolean toggleLocked) {
            super(text, enabledHere, toggleLocked);
            this.id = id;
        }
    }

    /** A script that exists only in the world the screen was opened in. */
    private static class WorldScriptEntry extends ScriptRowEntry {
        final String id; // stable identity; "" for a not-yet-saved new row

        WorldScriptEntry(String id, String text, boolean enabled) {
            super(text, enabled, false);
            this.id = id;
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
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            this.button.setX(x + (entryWidth / 2) - (this.button.getWidth() / 2));
            this.button.setY(y);
            this.button.render(graphics, mouseX, mouseY, partialTick);
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
