package net.tupenter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.tupenter.config.TupenterConfig;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListListEntry;
import java.util.Optional;
import java.util.Collections;
import java.util.ArrayList;

import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Minecraft;

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
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.rapid_resend_delay"), TupenterConfig.INSTANCE.rapidResendDelay, 0, 100)
                .setDefaultValue(5)
                .setTooltip(Component.translatable("tooltip.tupenter.rapid_resend_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.rapidResendDelay = newValue)
                .build());

        general.addEntry(entryBuilder.startIntField(Component.translatable("option.tupenter.resend_delay"), TupenterConfig.INSTANCE.resendDelay)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendDelay = newValue)
                .build());

        general.addEntry(entryBuilder.startIntField(Component.translatable("option.tupenter.message_delay"), TupenterConfig.INSTANCE.messageDelay)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("tooltip.tupenter.message_delay"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.messageDelay = newValue)
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.batch_mode"), TupenterConfig.BatchMode.class, TupenterConfig.INSTANCE.batchMode)
                .setDefaultValue(TupenterConfig.BatchMode.PAUSE)
                .setTooltip(Component.translatable("tooltip.tupenter.batch_mode"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.batchMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.batch." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_mode"), TupenterConfig.ResendMode.class, TupenterConfig.INSTANCE.resendMode)
                .setDefaultValue(TupenterConfig.ResendMode.PRESS_AND_HOLD)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_mode"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendMode = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.suppress_feedback"), TupenterConfig.FeedbackSuppressionMode.class, TupenterConfig.INSTANCE.suppressFeedback)
                .setDefaultValue(TupenterConfig.FeedbackSuppressionMode.OFF)
                .setTooltip(Component.translatable("tooltip.tupenter.suppress_feedback"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.suppressFeedback = newValue)
                .setEnumNameProvider(mode -> Component.translatable("mode.tupenter.feedback." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_filter"), TupenterConfig.ResendFilter.class, TupenterConfig.INSTANCE.resendFilter)
                .setDefaultValue(TupenterConfig.ResendFilter.BOTH)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_filter"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendFilter = newValue)
                .setEnumNameProvider(mode -> Component.translatable("filter.tupenter." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.remember_last_valid"), TupenterConfig.INSTANCE.rememberLastValid)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.remember_last_valid"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.rememberLastValid = newValue)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.record_history"), TupenterConfig.INSTANCE.recordHistory)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.tupenter.record_history"))
                .setSaveConsumer(newValue -> {
                    boolean wasEnabled = TupenterConfig.INSTANCE.recordHistory;
                    TupenterConfig.INSTANCE.recordHistory = newValue;
                    // If switching ON (from OFF), clear the history
                    if (!wasEnabled && newValue) {
                        net.tupenter.TupenterModClient.messageHistory.clear();
                    }
                })
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.update_in_toggle"), TupenterConfig.INSTANCE.updateInToggle)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.update_in_toggle"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.updateInToggle = newValue)
                .build());

        general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.resend_amount"), TupenterConfig.INSTANCE.resendAmount, 1, 10)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_amount"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendAmount = newValue)
                .build());

        general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.history_depth"), TupenterConfig.INSTANCE.historyDepth, 1, 32)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("tooltip.tupenter.history_depth"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.historyDepth = newValue)
                .build());

        general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.tupenter.resend_order"), TupenterConfig.ResendOrder.class, TupenterConfig.INSTANCE.resendOrder)
                .setDefaultValue(TupenterConfig.ResendOrder.OLDEST_FIRST)
                .setTooltip(Component.translatable("tooltip.tupenter.resend_order"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.resendOrder = newValue)
                .setEnumNameProvider(mode -> Component.translatable("order.tupenter." + mode.name().toLowerCase()))
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.tupenter.use_permanent_message"), TupenterConfig.INSTANCE.usePermanentMessage)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("tooltip.tupenter.use_permanent_message"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.usePermanentMessage = newValue)
                .build());

        StringListListEntry permanentEntry = entryBuilder.startStrList(Component.translatable("option.tupenter.permanent_messages"), TupenterConfig.INSTANCE.permanentMessages)
                .setDefaultValue(new java.util.ArrayList<>())
                .setTooltip(Component.translatable("tooltip.tupenter.permanent_messages"))
                .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.permanentMessages = newValue)
                .build();

        general.addEntry(new ImportButtonEntry(Component.translatable("text.tupenter.import_history"), Component.translatable("tooltip.tupenter.import_history"), () -> {
            Minecraft.getInstance().setScreen(new ConfirmScreen(
                (confirmed) -> {
                    if (confirmed) {
                        TupenterConfig.INSTANCE.permanentMessages = new ArrayList<>(net.tupenter.TupenterModClient.messageHistory);
                        Minecraft.getInstance().setScreen(createScreen(cachedParent));
                    } else {
                        Minecraft.getInstance().setScreen(createScreen(cachedParent)); // Re-open config without saving/importing? Or just close confirm?
                        // Actually, if we cancel, we want to go back to the *current* config screen state ideally, 
                        // but since we can't easily restore the exact builder state without rebuilding, 
                        // rebuilding is the safest option to ensure consistency, though it effectively "reloads" anyway.
                        // Wait, if we use setScreen(cachedParent), we go back to the parent of the config menu (e.g. Pause Menu).
                        // If we want to return to the config menu, we have to rebuild it.
                        // Ideally checking `Minecraft.getInstance().setScreen(currentScreen)` would work if `currentScreen` was captured, 
                        // but `createScreen` returns a new screen.
                        // Let's stick to rebuilding for now as it's consistent.
                        Minecraft.getInstance().setScreen(createScreen(cachedParent)); 
                    }
                },
                Component.translatable("title.tupenter.import_confirm"),
                Component.translatable("message.tupenter.import_confirm")
            ));
        }));

        general.addEntry(permanentEntry);

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
        public Object getValue() {
            return null;
        }

        @Override
        public Optional<Object> getDefaultValue() {
            return Optional.empty();
        }

        @Override
        public void save() {}
    }
}
