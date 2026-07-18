package net.tupenter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.tupenter.script.NumberMathMode;

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
    public boolean recordHistory = true;
    public boolean updateInToggle = false;
    public boolean enhancedCommandParsingEnabled = true;
    public boolean commandChainingEnabled = true;
    public boolean silentDirectiveEnabled = true;
    public boolean variablesEnabled = true;
    public boolean loopsEnabled = true;
    public boolean conditionalsEnabled = true;
    public int maxLoopIterations = 100;
    public List<String> persistentVariables = new ArrayList<>();
    public boolean tickScriptsEnabled = false; // deliberately opt-in: tick scripts can spam chat hard
    public List<String> tickScripts = new ArrayList<>();
    public NumberMathMode numberMathMode = NumberMathMode.AUTO_DETECT;
    public List<String> aliases = new ArrayList<>();

    // Script executor limits (docs/SCRIPTING_DESIGN.md §2)
    public int maxCommandsPerTick = 16;
    public int maxCommandsPerScript = 1000;
    public int maxConcurrentScripts = 8;

    public int resendAmount = 1;
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
                    INSTANCE.rapidResendDelay = loaded.rapidResendDelay;
                    INSTANCE.resendDelay = Math.max(0, loaded.resendDelay);
                    INSTANCE.messageDelay = Math.max(0, loaded.messageDelay);
                    INSTANCE.batchMode = loaded.batchMode != null ? loaded.batchMode : BatchMode.PAUSE;
                    INSTANCE.historyDepth = Math.max(1, loaded.historyDepth);
                    INSTANCE.resendMode = loaded.resendMode != null ? loaded.resendMode : ResendMode.PRESS_AND_HOLD;
                    INSTANCE.resendOrder = loaded.resendOrder != null ? loaded.resendOrder : ResendOrder.OLDEST_FIRST;
                    INSTANCE.suppressFeedback = loaded.suppressFeedback != null ? loaded.suppressFeedback : FeedbackSuppressionMode.OFF;
                    INSTANCE.resendFilter = loaded.resendFilter != null ? loaded.resendFilter : ResendFilter.BOTH;
                    INSTANCE.recordHistory = loaded.recordHistory;
                    INSTANCE.updateInToggle = loaded.updateInToggle;
                    INSTANCE.enhancedCommandParsingEnabled = loaded.enhancedCommandParsingEnabled;
                    INSTANCE.commandChainingEnabled = loaded.commandChainingEnabled;
                    INSTANCE.silentDirectiveEnabled = loaded.silentDirectiveEnabled;
                    INSTANCE.variablesEnabled = loaded.variablesEnabled;
                    INSTANCE.loopsEnabled = loaded.loopsEnabled;
                    INSTANCE.conditionalsEnabled = loaded.conditionalsEnabled;
                    INSTANCE.maxLoopIterations = clamp(loaded.maxLoopIterations, 1, 100000, 100);
                    INSTANCE.persistentVariables = loaded.persistentVariables != null ? loaded.persistentVariables : new ArrayList<>();
                    INSTANCE.tickScriptsEnabled = loaded.tickScriptsEnabled;
                    INSTANCE.tickScripts = loaded.tickScripts != null ? loaded.tickScripts : new ArrayList<>();
                    INSTANCE.numberMathMode = loaded.numberMathMode != null ? loaded.numberMathMode : NumberMathMode.AUTO_DETECT;
                    INSTANCE.aliases = loaded.aliases != null ? loaded.aliases : new ArrayList<>();
                    INSTANCE.maxCommandsPerTick = clamp(loaded.maxCommandsPerTick, 1, 512, 16);
                    INSTANCE.maxCommandsPerScript = clamp(loaded.maxCommandsPerScript, 1, 100000, 1000);
                    INSTANCE.maxConcurrentScripts = clamp(loaded.maxConcurrentScripts, 1, 64, 8);
                    INSTANCE.resendAmount = Math.max(1, loaded.resendAmount); // Ensure at least 1
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

    private static int clamp(int value, int min, int max, int fallback) {
        if (value == 0) {
            return fallback; // field absent from an older config file
        }
        return Math.max(min, Math.min(max, value));
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
