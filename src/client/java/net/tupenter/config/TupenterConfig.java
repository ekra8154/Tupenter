package net.tupenter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class TupenterConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("tupenter.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static TupenterConfig INSTANCE = new TupenterConfig();

    public int gracePeriod = 10;
    public int rapidResendDelay = 5;
    public ResendMode resendMode = ResendMode.PRESS_AND_HOLD;
    public FeedbackSuppressionMode suppressFeedback = FeedbackSuppressionMode.OFF;
    public ResendFilter resendFilter = ResendFilter.BOTH;
    public boolean rememberLastValid = true;
    public boolean updateInToggle = false;
    public int resendAmount = 1;

    public enum ResendMode {
        PRESS_AND_HOLD,
        TOGGLE,
        OFF
    }

    public enum FeedbackSuppressionMode {
        OFF,
        ON,
        DYNAMIC
    }

    public enum ResendFilter {
        BOTH,
        CHAT_ONLY,
        COMMANDS_ONLY
    }

    public static void load() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "tupenter.json");
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                TupenterConfig loaded = GSON.fromJson(reader, TupenterConfig.class);
                if (loaded != null) {
                    INSTANCE.gracePeriod = loaded.gracePeriod;
                    INSTANCE.rapidResendDelay = loaded.rapidResendDelay;
                    INSTANCE.resendMode = loaded.resendMode != null ? loaded.resendMode : ResendMode.PRESS_AND_HOLD;
                    INSTANCE.suppressFeedback = loaded.suppressFeedback != null ? loaded.suppressFeedback : FeedbackSuppressionMode.OFF;
                    INSTANCE.resendFilter = loaded.resendFilter != null ? loaded.resendFilter : ResendFilter.BOTH;
                    INSTANCE.rememberLastValid = loaded.rememberLastValid;
                    INSTANCE.updateInToggle = loaded.updateInToggle;
                    INSTANCE.resendAmount = Math.max(1, loaded.resendAmount); // Ensure at least 1
                }
            } catch (Exception e) {
                System.err.println("Failed to load config, resetting to defaults: " + e.getMessage());
                save();
            }
        } else {
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
