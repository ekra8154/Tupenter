package net.tupenter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.tupenter.config.TupenterConfig;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.translatable("title.tupenter.config"));

            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.tupenter.general"));
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.grace_period"), TupenterConfig.INSTANCE.gracePeriod, 0, 100)
                    .setDefaultValue(10)
                    .setTooltip(Component.translatable("tooltip.tupenter.grace_period"))
                    .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.gracePeriod = newValue)
                    .build());

            general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.rapid_resend_delay"), TupenterConfig.INSTANCE.rapidResendDelay, 0, 100)
                    .setDefaultValue(5)
                    .setTooltip(Component.translatable("tooltip.tupenter.rapid_resend_delay"))
                    .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.rapidResendDelay = newValue)
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

            builder.setSavingRunnable(TupenterConfig::save);

            return builder.build();
        };
    }
}
