package net.tupenter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TupenterConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("tupenter.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static TupenterConfig INSTANCE = new TupenterConfig();

    public int gracePeriod = 10;
    public boolean resetOnNewSession = true;
    public int rapidResendDelay = 5;
    public int resendDelay = 0;
    public int messageDelay = 0;
    public BatchMode batchMode = BatchMode.PAUSE;
    public int historyDepth = 1;
    public ResendMode resendMode = ResendMode.PRESS_AND_HOLD;
    public ResendOrder resendOrder = ResendOrder.OLDEST_FIRST;
    public FeedbackSuppressionMode suppressFeedback = FeedbackSuppressionMode.OFF;
    public ResendFilter resendFilter = ResendFilter.BOTH;
    public boolean rememberLastValid = true;
    public boolean recordHistory = true;
    public boolean updateInToggle = false;
    public boolean enhancedCommandParsingEnabled = true;
    public boolean numberMathEnabled = true;

    public int resendAmount = 1;
    public boolean usePermanentMessage = false;
    public List<String> permanentMessages = new ArrayList<>();

    public enum ResendMode {
        PRESS_AND_HOLD,
        TOGGLE,
        OFF
    }
    
    public enum ResendOrder {
        OLDEST_FIRST,
        NEWEST_FIRST
    }

    public enum FeedbackSuppressionMode {
        OFF,
        ON,
        DYNAMIC
    }

    public enum ResendFilter {
        BOTH,
        CHAT_ONLY,
        COMMANDS_ONLY,
        PERMANENT_MESSAGES
    }

    public enum BatchMode {
        PAUSE,
        FINISH_BATCH,
        INTERRUPT
    }

    public static void load() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "tupenter.json");
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                TupenterConfig loaded = GSON.fromJson(reader, TupenterConfig.class);
                if (loaded != null) {
                    INSTANCE.resetOnNewSession = loaded.resetOnNewSession;
                    INSTANCE.gracePeriod = loaded.gracePeriod;
                    INSTANCE.rapidResendDelay = loaded.rapidResendDelay;
                    INSTANCE.resendDelay = Math.max(0, loaded.resendDelay);
                    INSTANCE.messageDelay = Math.max(0, loaded.messageDelay);
                    INSTANCE.batchMode = loaded.batchMode != null ? loaded.batchMode : BatchMode.PAUSE;
                    INSTANCE.historyDepth = Math.max(1, loaded.historyDepth);
                    INSTANCE.resendMode = loaded.resendMode != null ? loaded.resendMode : ResendMode.PRESS_AND_HOLD;
                    INSTANCE.resendOrder = loaded.resendOrder != null ? loaded.resendOrder : ResendOrder.OLDEST_FIRST;
                    INSTANCE.suppressFeedback = loaded.suppressFeedback != null ? loaded.suppressFeedback : FeedbackSuppressionMode.OFF;
                    INSTANCE.resendFilter = loaded.resendFilter != null ? loaded.resendFilter : ResendFilter.BOTH;
                    // Migrate old usePermanentMessage boolean to the dedicated filter option
                    if (loaded.usePermanentMessage && INSTANCE.resendFilter != ResendFilter.PERMANENT_MESSAGES) {
                        INSTANCE.resendFilter = ResendFilter.PERMANENT_MESSAGES;
                    }
                    INSTANCE.rememberLastValid = loaded.rememberLastValid;
                    INSTANCE.recordHistory = loaded.recordHistory;
                    INSTANCE.updateInToggle = loaded.updateInToggle;
                    INSTANCE.enhancedCommandParsingEnabled = loaded.enhancedCommandParsingEnabled;
                    INSTANCE.numberMathEnabled = loaded.numberMathEnabled;
                    INSTANCE.resendAmount = Math.max(1, loaded.resendAmount); // Ensure at least 1
                    INSTANCE.usePermanentMessage = loaded.usePermanentMessage;
                    INSTANCE.permanentMessages = loaded.permanentMessages != null ? loaded.permanentMessages : new ArrayList<>();
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
