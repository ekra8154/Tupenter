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
import net.tupenter.config.TupenterConfig;

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
        ConfigCategory commandParsing = builder.getOrCreateCategory(Component.translatable("category.tupenter.command_parsing"));
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

        AbstractConfigListEntry<?> numberMathEntry = entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.number_math"), TupenterConfig.INSTANCE.numberMathEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.number_math"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.numberMathEnabled = newValue)
                .build();

        general.addEntry(entryBuilder.startSubCategory(
                Component.translatable("subcategory.tupenter.preset_commands"),
                List.of(importButtonEntry, permanentMessagesEntry))
                .setExpanded(false)
                .build());

        commandParsing.addEntry(enhancedCommandParsingEntry);
        commandParsing.addEntry(commandChainingEntry);
        commandParsing.addEntry(numberMathEntry);

        general.addEntry(entryBuilder.startSubCategory(Component.translatable("subcategory.tupenter.advanced"),
                List.of(historyDepthEntry, resendDelayEntry, batchModeEntry, resendAmountEntry, resendOrderEntry))
                .setExpanded(false)
                .build());

        builder.setSavingRunnable(TupenterConfig::save);

        return builder.build();
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
