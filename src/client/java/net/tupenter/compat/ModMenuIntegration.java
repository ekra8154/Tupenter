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

            general.addEntry(entryBuilder.startIntSlider(Component.translatable("option.tupenter.machine_gun_delay"), TupenterConfig.INSTANCE.machineGunDelay, 0, 100)
                    .setDefaultValue(5)
                    .setTooltip(Component.translatable("tooltip.tupenter.machine_gun_delay"))
                    .setSaveConsumer(newValue -> TupenterConfig.INSTANCE.machineGunDelay = newValue)
                    .build());

            builder.setSavingRunnable(TupenterConfig::save);

            return builder.build();
        };
    }
}
