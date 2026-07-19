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
    public boolean chatHighlightingEnabled = true;
    public boolean chatSelectionEnabled = true;
    public boolean lazyExecutionEnabled = true;
    public boolean silentDirectiveEnabled = true;
    public boolean variablesEnabled = true;
    public boolean loopsEnabled = true;
    public boolean conditionalsEnabled = true;
    public int maxLoopIterations = 100;
    public List<String> persistentVariables = new ArrayList<>();
    public boolean tickScriptsEnabled = false; // deliberately opt-in: tick scripts can spam chat hard
    /** Pre-per-world flat list; migrated into globalScripts on load. */
    @Deprecated
    public List<String> tickScripts = new ArrayList<>();
    /** Script definitions shared across worlds; ARMED per world via WorldScriptState.enabledGlobalIds. */
    public List<GlobalScript> globalScripts = new ArrayList<>();
    /** worldKey ("server:<ip>" / "world:<folder>") -> that world's script state. Unknown world = nothing runs. */
    public java.util.Map<String, WorldScriptState> worldScripts = new java.util.LinkedHashMap<>();
    /** One-time chat notice after the tickScripts -> per-world migration. */
    public boolean tickScriptsMigrationNoticePending = false;
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

    /** A tick-script definition shared across worlds; the id is what per-world enable states point at. */
    public static class GlobalScript {
        public String id = "";
        public String text = "";

        public GlobalScript() {
        }

        public GlobalScript(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public static String newId() {
            return java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /** Everything script-related that belongs to one world/server. */
    public static class WorldScriptState {
        public List<String> enabledGlobalIds = new ArrayList<>();
        public List<WorldScript> scripts = new ArrayList<>();

        public boolean isEmpty() {
            return enabledGlobalIds.isEmpty() && scripts.isEmpty();
        }
    }

    /** A tick script that exists only in one world. */
    public static class WorldScript {
        public String text = "";
        public boolean enabled = false;

        public WorldScript() {
        }

        public WorldScript(String text, boolean enabled) {
            this.text = text;
            this.enabled = enabled;
        }
    }

    /** Read-only view; null-safe for worlds with no saved state. */
    public WorldScriptState worldState(String worldKey) {
        return worldKey == null ? null : worldScripts.get(worldKey);
    }

    public WorldScriptState worldStateOrCreate(String worldKey) {
        return worldScripts.computeIfAbsent(worldKey, key -> new WorldScriptState());
    }

    /** The script lines armed for this world: enabled globals + the world's own enabled scripts. */
    public List<String> armedScriptLines(String worldKey) {
        List<String> lines = new ArrayList<>();
        WorldScriptState state = worldState(worldKey);
        if (state == null) {
            return lines;
        }
        for (GlobalScript script : globalScripts) {
            if (state.enabledGlobalIds.contains(script.id)) {
                lines.add(script.text);
            }
        }
        for (WorldScript script : state.scripts) {
            if (script.enabled) {
                lines.add(script.text);
            }
        }
        return lines;
    }

    /** Drops enable pointers to deleted globals and empty world states. */
    public void pruneWorldScriptStates() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (GlobalScript script : globalScripts) {
            ids.add(script.id);
        }
        worldScripts.values().forEach(state -> state.enabledGlobalIds.retainAll(ids));
        worldScripts.values().removeIf(WorldScriptState::isEmpty);
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
                    INSTANCE.chatHighlightingEnabled = loaded.chatHighlightingEnabled;
                    INSTANCE.chatSelectionEnabled = loaded.chatSelectionEnabled;
                    INSTANCE.lazyExecutionEnabled = loaded.lazyExecutionEnabled;
                    INSTANCE.silentDirectiveEnabled = loaded.silentDirectiveEnabled;
                    INSTANCE.variablesEnabled = loaded.variablesEnabled;
                    INSTANCE.loopsEnabled = loaded.loopsEnabled;
                    INSTANCE.conditionalsEnabled = loaded.conditionalsEnabled;
                    INSTANCE.maxLoopIterations = clamp(loaded.maxLoopIterations, 1, 100000, 100);
                    INSTANCE.persistentVariables = loaded.persistentVariables != null ? loaded.persistentVariables : new ArrayList<>();
                    INSTANCE.tickScriptsEnabled = loaded.tickScriptsEnabled;
                    INSTANCE.tickScripts = loaded.tickScripts != null ? loaded.tickScripts : new ArrayList<>();
                    INSTANCE.globalScripts = loaded.globalScripts != null ? loaded.globalScripts : new ArrayList<>();
                    INSTANCE.worldScripts = loaded.worldScripts != null ? loaded.worldScripts : new java.util.LinkedHashMap<>();
                    INSTANCE.tickScriptsMigrationNoticePending = loaded.tickScriptsMigrationNoticePending;
                    INSTANCE.numberMathMode = loaded.numberMathMode != null ? loaded.numberMathMode : NumberMathMode.AUTO_DETECT;
                    INSTANCE.aliases = loaded.aliases != null ? loaded.aliases : new ArrayList<>();
                    INSTANCE.maxCommandsPerTick = clamp(loaded.maxCommandsPerTick, 1, 512, 16);
                    INSTANCE.maxCommandsPerScript = clamp(loaded.maxCommandsPerScript, 1, 100000, 1000);
                    INSTANCE.maxConcurrentScripts = clamp(loaded.maxConcurrentScripts, 1, 64, 8);
                    INSTANCE.resendAmount = Math.max(1, loaded.resendAmount); // Ensure at least 1
                    INSTANCE.permanentMessages = loaded.permanentMessages != null ? loaded.permanentMessages : new ArrayList<>();
                    // MUST run after every field above is copied — it may
                    // save(), and a save from a half-populated INSTANCE
                    // writes defaults over the user's data (aliases were
                    // once lost to exactly this)
                    migrateTickScripts();
                }
            } catch (Exception e) {
                // deliberately NOT save() here: overwriting the file because
                // one load failed would destroy whatever is still in it
                System.err.println("Failed to load Tupenter config, using defaults for this session: " + e.getMessage());
            }
        } else {
            save();
        }
    }

    /**
     * Pre-per-world configs stored scripts as a flat list ("//"-prefixed =
     * disabled). They become global definitions armed in NO world — the safe
     * default — and a one-time notice explains where they went. Also repairs
     * missing ids and null state fields from hand-edited files.
     */
    private static void migrateTickScripts() {
        if (!INSTANCE.tickScripts.isEmpty()) {
            for (String line : INSTANCE.tickScripts) {
                String text = line.trim();
                if (text.startsWith("//")) {
                    text = text.substring(2).trim();
                }
                if (!text.isEmpty()) {
                    INSTANCE.globalScripts.add(new GlobalScript(GlobalScript.newId(), text));
                }
            }
            INSTANCE.tickScripts = new ArrayList<>();
            INSTANCE.tickScriptsMigrationNoticePending = true;
            save();
        }
        for (GlobalScript script : INSTANCE.globalScripts) {
            if (script.id == null || script.id.isEmpty()) {
                script.id = GlobalScript.newId();
            }
            if (script.text == null) {
                script.text = "";
            }
        }
        INSTANCE.worldScripts.values().removeIf(java.util.Objects::isNull);
        for (WorldScriptState state : INSTANCE.worldScripts.values()) {
            if (state.enabledGlobalIds == null) {
                state.enabledGlobalIds = new ArrayList<>();
            }
            if (state.scripts == null) {
                state.scripts = new ArrayList<>();
            }
            state.scripts.removeIf(script -> script == null || script.text == null || script.text.trim().isEmpty());
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
