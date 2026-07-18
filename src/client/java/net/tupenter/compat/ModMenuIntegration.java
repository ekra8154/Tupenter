package net.tupenter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
        AbstractConfigListEntry<?> importButtonEntry = new ImportButtonEntry(
                Component.translatable("text.tupenter.import_history"),
                Component.translatable("tooltip.tupenter.import_history"),
                () -> Minecraft.getInstance().setScreen(new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                TupenterConfig.INSTANCE.permanentMessages = new ArrayList<>(TupenterModClient.messageHistory);
                            }
                            Minecraft.getInstance().setScreen(createScreen(cachedParent));
                        },
                        Component.translatable("title.tupenter.import_confirm"),
                        Component.translatable("message.tupenter.import_confirm")
                ))
        );

        StringListListEntry permanentMessagesEntry = entryBuilder.startStrList(Component.translatable("option.tupenter.permanent_messages"), TupenterConfig.INSTANCE.permanentMessages)
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Component.translatable("tooltip.tupenter.permanent_messages"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.permanentMessages = newValue)
                .build();

        AbstractConfigListEntry<?> enhancedCommandParsingEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.enhanced_command_parsing"), TupenterConfig.INSTANCE.enhancedCommandParsingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.enhanced_command_parsing"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.enhancedCommandParsingEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> commandChainingEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.command_chaining"), TupenterConfig.INSTANCE.commandChainingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.command_chaining"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.commandChainingEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> numberMathEntry = entryBuilder.startEnumSelector(Component.translatable("option.tupenter.number_math"), NumberMathMode.class, TupenterConfig.INSTANCE.numberMathMode)
                .setDefaultValue(NumberMathMode.AUTO_DETECT)
                .setTooltip(Component.translatable("tooltip.tupenter.number_math"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.numberMathMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.number_math." + mode.name().toLowerCase()))
                .build();

        AbstractConfigListEntry<?> silentDirectiveEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.silent_directive"), TupenterConfig.INSTANCE.silentDirectiveEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.silent_directive"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.silentDirectiveEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> variablesEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.variables"), TupenterConfig.INSTANCE.variablesEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.variables"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.variablesEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> loopsEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.loops"), TupenterConfig.INSTANCE.loopsEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.loops"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.loopsEnabled = newValue)
                .build();

        AbstractConfigListEntry<?> conditionalsEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.conditionals"), TupenterConfig.INSTANCE.conditionalsEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.conditionals"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.conditionalsEnabled = newValue)
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
                List.of(importButtonEntry, permanentMessagesEntry))
                .setExpanded(false)
                .build());

        scripting.addEntry(enhancedCommandParsingEntry);
        scripting.addEntry(commandChainingEntry);
        scripting.addEntry(numberMathEntry);
        scripting.addEntry(silentDirectiveEntry);
        scripting.addEntry(variablesEntry);
        scripting.addEntry(loopsEntry);
        scripting.addEntry(conditionalsEntry);
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
                    Minecraft.getInstance().setScreen(createScreen(cachedParent));
                }
        ));

        general.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.advanced"),
                List.of(historyDepthEntry, resendDelayEntry, batchModeEntry, resendAmountEntry, resendOrderEntry))
                .setExpanded(false)
                .build());

        // =====================================================================
        // SCRIPTS TAB — tick scripts (the walking mcfunction file)
        // =====================================================================

        scripts.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.tick_scripts_enabled"), TupenterConfig.INSTANCE.tickScriptsEnabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.tick_scripts_enabled"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.tickScriptsEnabled = newValue)
                .build());

        scripts.addEntry(entryBuilder.startTextDescription(Component.translatable("tooltip.tupenter.tick_scripts")).build());

        // One row per script: [On/Off] [editable text] [✕]
        tickScriptRows.clear();
        for (String line : TupenterConfig.INSTANCE.tickScripts) {
            String stripped = stripDisabledPrefix(line);
            if (stripped.isEmpty()) {
                continue;
            }
            TickScriptEntry row = new TickScriptEntry(stripped, !line.trim().startsWith("//"));
            tickScriptRows.add(row);
            scripts.addEntry(row);
        }

        scripts.addEntry(new ImportButtonEntry(
                Component.translatable("text.tupenter.add_script"),
                Component.translatable("tooltip.tupenter.add_script"),
                () -> {
                    List<String> updated = collectTickScriptRows();
                    updated.add("// /echo new script");
                    TupenterConfig.INSTANCE.tickScripts = updated;
                    Minecraft.getInstance().setScreen(createScreen(cachedParent));
                }
        ));

        java.util.Set<String> namesBeforeEdit = new java.util.HashSet<>(CommandAliasManager.getAliasMap().keySet());
        builder.setSavingRunnable(() -> {
            TupenterConfig.INSTANCE.tickScripts = collectTickScriptRows();
            TupenterConfig.INSTANCE.aliases = collectCommandRows();
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

    private static final List<TickScriptEntry> tickScriptRows = new ArrayList<>();
    private static final List<CommandRowEntry> commandRows = new ArrayList<>();

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
     * One custom command as a row: wrap toggle, definition box, delete button.
     * Expanded rows use a wrapping multi-line editor; newlines are stored as
     * formatting but the command always runs as a single line.
     */
    private static class CommandRowEntry extends AbstractConfigListEntry<String> {
        private static final int BOX_HEIGHT_EXPANDED = 62;
        private final Button wrapButton;
        private final Button deleteButton;
        private final net.minecraft.client.gui.components.EditBox singleBox;
        private net.minecraft.client.gui.components.MultiLineEditBox multiBox; // built lazily at the rendered width
        private String multiValue; // survives width-rebuilds of multiBox
        private final String initialText;
        private boolean expanded;
        private boolean deleted;

        CommandRowEntry(String definition) {
            super(Component.empty(), false);
            this.initialText = definition.trim();
            this.expanded = definition.contains("\n");
            this.multiValue = definition;

            this.singleBox = new net.minecraft.client.gui.components.EditBox(
                    Minecraft.getInstance().font, 0, 0, 200, 18, Component.empty());
            this.singleBox.setMaxLength(4000);
            if (!expanded) {
                this.singleBox.setValue(definition);
            }

            this.wrapButton = Button.builder(wrapLabel(), button -> toggleWrap())
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.wrap_toggle")))
                    .build();

            this.deleteButton = Button.builder(Component.literal("✕").withStyle(net.minecraft.ChatFormatting.RED), button -> {
                        this.deleted = true;
                        TupenterConfig.INSTANCE.aliases = collectCommandRows();
                        Minecraft.getInstance().setScreen(createScreen(cachedParent));
                    })
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.delete_command")))
                    .build();
        }

        private Component wrapLabel() {
            return Component.literal(expanded ? "▾" : "▸");
        }

        private void toggleWrap() {
            if (expanded) {
                singleBox.setValue(currentMultiValue().replaceAll("\\s*[\\r\\n]+\\s*", " ").trim());
            } else {
                multiValue = singleBox.getValue();
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

        /** The multi-line editor wraps at build width, so rebuild when the row width changes. */
        private net.minecraft.client.gui.components.MultiLineEditBox ensureMultiBox(int width) {
            if (multiBox == null || multiBox.getWidth() != width) {
                String value = currentMultiValue();
                multiBox = net.minecraft.client.gui.components.MultiLineEditBox.builder()
                        .build(Minecraft.getInstance().font, width, BOX_HEIGHT_EXPANDED, Component.empty());
                multiBox.setCharacterLimit(4000);
                multiBox.setValue(value);
                multiValue = value;
            }
            return multiBox;
        }

        String text() {
            return (expanded ? currentMultiValue() : singleBox.getValue()).trim();
        }

        @Override
        public int getItemHeight() {
            return expanded ? BOX_HEIGHT_EXPANDED + 6 : 24;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            wrapButton.setX(x);
            wrapButton.setY(y);
            wrapButton.render(graphics, mouseX, mouseY, partialTick);

            int boxWidth = entryWidth - 24 - 24;
            if (expanded) {
                net.minecraft.client.gui.components.MultiLineEditBox box = ensureMultiBox(boxWidth);
                box.setX(x + 24);
                box.setY(y);
                box.render(graphics, mouseX, mouseY, partialTick);
            } else {
                singleBox.setX(x + 24);
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

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return List.of(wrapButton, expanded && multiBox != null ? multiBox : singleBox, deleteButton);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return List.of(wrapButton, expanded && multiBox != null ? multiBox : singleBox, deleteButton);
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

    private static List<String> collectTickScriptRows() {
        List<String> lines = new ArrayList<>();
        for (TickScriptEntry row : tickScriptRows) {
            if (row.deleted) {
                continue;
            }
            String text = row.text();
            if (text.isEmpty()) {
                continue;
            }
            lines.add(row.enabled ? text : "// " + text);
        }
        return lines;
    }

    private static String stripDisabledPrefix(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") ? trimmed.substring(2).trim() : trimmed;
    }

    /** One tick script as a single row: toggle button, edit box, delete button. */
    private static class TickScriptEntry extends AbstractConfigListEntry<String> {
        private final Button toggleButton;
        private final Button deleteButton;
        private final net.minecraft.client.gui.components.EditBox textBox;
        private final String initialText;
        private final boolean initialEnabled;
        private boolean enabled;
        private boolean deleted;

        TickScriptEntry(String text, boolean enabled) {
            super(Component.empty(), false);
            this.initialText = text;
            this.initialEnabled = enabled;
            this.enabled = enabled;

            this.toggleButton = Button.builder(toggleLabel(), button -> {
                        this.enabled = !this.enabled;
                        button.setMessage(toggleLabel());
                    })
                    .bounds(0, 0, 40, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.tick_script_toggle")))
                    .build();

            this.deleteButton = Button.builder(Component.literal("✕").withStyle(net.minecraft.ChatFormatting.RED), button -> {
                        this.deleted = true;
                        TupenterConfig.INSTANCE.tickScripts = collectTickScriptRows();
                        Minecraft.getInstance().setScreen(createScreen(cachedParent));
                    })
                    .bounds(0, 0, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.tupenter.delete_script")))
                    .build();

            this.textBox = new net.minecraft.client.gui.components.EditBox(
                    Minecraft.getInstance().font, 0, 0, 200, 18, Component.empty());
            this.textBox.setMaxLength(1000);
            this.textBox.setValue(text);
        }

        private Component toggleLabel() {
            return enabled
                    ? Component.literal("On").withStyle(net.minecraft.ChatFormatting.GREEN)
                    : Component.literal("Off").withStyle(net.minecraft.ChatFormatting.RED);
        }

        String text() {
            return textBox.getValue().trim();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick) {
            toggleButton.setX(x);
            toggleButton.setY(y);
            toggleButton.render(graphics, mouseX, mouseY, partialTick);

            textBox.setX(x + 44);
            textBox.setY(y + 1);
            textBox.setWidth(entryWidth - 44 - 24);
            textBox.render(graphics, mouseX, mouseY, partialTick);

            deleteButton.setX(x + entryWidth - 20);
            deleteButton.setY(y);
            deleteButton.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isEdited() {
            return deleted || enabled != initialEnabled || !text().equals(initialText);
        }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return List.of(toggleButton, textBox, deleteButton);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return List.of(toggleButton, textBox, deleteButton);
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
