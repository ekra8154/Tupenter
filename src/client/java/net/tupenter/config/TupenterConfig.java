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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Where the config lives. Null means "ask Fabric", which is always the
     * answer in game; tests point it at a temp directory so the save/load round
     * trip and the migrations can be exercised for real, on actual files,
     * rather than around them. Package-private on purpose — nothing outside
     * this package can move the user's config.
     */
    static java.nio.file.Path configDirectory = null;

    /**
     * Resolved on demand, not at class load. load() already derived the path
     * this way; holding the other half in a static field meant merely touching
     * INSTANCE needed a live Fabric runtime, which put every plain-data method
     * on this class out of reach of a unit test for no benefit.
     */
    private static File configFile() {
        java.nio.file.Path directory = configDirectory != null
                ? configDirectory
                : FabricLoader.getInstance().getConfigDir();
        return directory.resolve("tupenter.json").toFile();
    }

    public static TupenterConfig INSTANCE = new TupenterConfig();

    public boolean resetOnNewSession = true;
    public int rapidResendDelay = 5;
    public int resendDelay = 0;
    public int messageDelay = 0;
    public BatchMode batchMode = BatchMode.PAUSE;
    public int historyDepth = 1;
    public int importHistoryCount = 50; // how many recent history entries "Import from History" pulls
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
    /** Auto-close/wrap brackets and $ markers while typing commands/scripts. Opt-in. */
    public boolean autoCloseBrackets = false;
    /** Ctrl+scroll in the chat bar steps through sent-message history instead of scrolling chat. */
    public boolean ctrlScrollHistory = true;
    /** Ctrl+Space in the chat bar submits the line (a mouse-hand-friendly manual send). */
    public boolean ctrlSpaceSend = true;
    public boolean lazyExecutionEnabled = true;
    /** Chat-bar typing limit. What's SENT still obeys the protocol: chat 256, commands 32766. */
    public int chatInputLength = 256;
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
    /** User-defined expression functions, each "name <params> = expression" (see CustomFunctionManager). */
    public List<String> functions = new ArrayList<>();

    // Script executor limits (docs/SCRIPTING_DESIGN.md §2)
    public int maxCommandsPerTick = 48;
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
        public String id = "";
        public String text = "";
        public boolean enabled = false;

        public WorldScript() {
        }

        public WorldScript(String text, boolean enabled) {
            this.text = text;
            this.enabled = enabled;
        }

        public WorldScript(String id, String text, boolean enabled) {
            this.id = id;
            this.text = text;
            this.enabled = enabled;
        }

        public static String newId() {
            return java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /** Read-only view; null-safe for worlds with no saved state. */
    public WorldScriptState worldState(String worldKey) {
        return worldKey == null ? null : worldScripts.get(worldKey);
    }

    public WorldScriptState worldStateOrCreate(String worldKey) {
        return worldScripts.computeIfAbsent(worldKey, key -> new WorldScriptState());
    }

    /**
     * A tick script may carry an optional leading name, matching the custom
     * command form but without params: {@code restock = /clear && …} names it
     * "restock" (see {@link net.tupenter.script.ScriptName}).
     *
     * @return the name, or "" when the text is just a body
     */
    public static String scriptName(String text) {
        return net.tupenter.script.ScriptName.name(text);
    }

    /** The runnable part — the body after {@code name =}, or the whole text when unnamed. */
    public static String scriptBody(String text) {
        return net.tupenter.script.ScriptName.body(text);
    }

    /** An armed tick script for a world, carrying a stable identity for pid mapping. */
    public record ArmedScript(boolean global, String id, String text) {
        /** Kind+id key; scope it by world (worldKey + "|" + key()) for a session pid. */
        public String key() {
            return (global ? "g:" : "w:") + id;
        }

        /** Optional leading name ("" when unnamed). */
        public String name() {
            return scriptName(text);
        }

        /** The runnable body (name prefix stripped). */
        public String body() {
            return scriptBody(text);
        }

        /** Display label: the name if present, else a body preview is up to the caller. */
        public String label() {
            String name = name();
            return name.isEmpty() ? body() : name;
        }
    }

    /** Enabled tick scripts for a world (globals armed here + world scripts), in display order. */
    public List<ArmedScript> armedScripts(String worldKey) {
        List<ArmedScript> out = new ArrayList<>();
        WorldScriptState state = worldState(worldKey);
        if (state == null) {
            return out;
        }
        for (GlobalScript script : globalScripts) {
            if (state.enabledGlobalIds.contains(script.id)) {
                out.add(new ArmedScript(true, script.id, script.text));
            }
        }
        for (WorldScript script : state.scripts) {
            if (script.enabled) {
                out.add(new ArmedScript(false, script.id, script.text));
            }
        }
        return out;
    }

    public List<String> armedScriptLines(String worldKey) {
        List<String> lines = new ArrayList<>();
        for (ArmedScript script : armedScripts(worldKey)) {
            lines.add(script.text());
        }
        return lines;
    }

    /**
     * Switches an armed tick script OFF for this world — a global drops out of
     * this world's enabled set, a world script gets {@code enabled = false}.
     *
     * @return the script's text if one was found and switched off, else null
     */
    public String disableArmedScript(String worldKey, boolean global, String id) {
        WorldScriptState state = worldState(worldKey);
        if (state == null) {
            return null;
        }
        if (global) {
            for (GlobalScript script : globalScripts) {
                if (script.id.equals(id) && state.enabledGlobalIds.remove(id)) {
                    return script.text;
                }
            }
        } else {
            for (WorldScript script : state.scripts) {
                if (script.id.equals(id) && script.enabled) {
                    script.enabled = false;
                    return script.text;
                }
            }
        }
        return null;
    }

    /** A named script for this world, whether or not it's currently armed — for enable/disable by name. */
    public record ScriptRef(boolean global, String id, String name, boolean enabled) {}

    /** Every script (global + this world's) whose name matches, case-insensitive. Unnamed scripts never match. */
    public List<ScriptRef> scriptsByName(String worldKey, String name) {
        List<ScriptRef> out = new ArrayList<>();
        WorldScriptState state = worldState(worldKey);
        for (GlobalScript script : globalScripts) {
            String scriptName = scriptName(script.text);
            if (!scriptName.isEmpty() && scriptName.equalsIgnoreCase(name)) {
                boolean enabled = state != null && state.enabledGlobalIds.contains(script.id);
                out.add(new ScriptRef(true, script.id, scriptName, enabled));
            }
        }
        if (state != null) {
            for (WorldScript script : state.scripts) {
                String scriptName = scriptName(script.text);
                if (!scriptName.isEmpty() && scriptName.equalsIgnoreCase(name)) {
                    out.add(new ScriptRef(false, script.id, scriptName, script.enabled));
                }
            }
        }
        return out;
    }

    /** All distinct names defined across global + this world's scripts — for tab completion. */
    public List<String> scriptNames(String worldKey) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (GlobalScript script : globalScripts) {
            String name = scriptName(script.text);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        WorldScriptState state = worldState(worldKey);
        if (state != null) {
            for (WorldScript script : state.scripts) {
                String name = scriptName(script.text);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** Arms/disarms a specific script by identity for this world. Returns true if the state changed. */
    public boolean setArmed(String worldKey, boolean global, String id, boolean enable) {
        WorldScriptState state = enable ? worldStateOrCreate(worldKey) : worldState(worldKey);
        if (state == null) {
            return false;
        }
        if (global) {
            if (enable) {
                return state.enabledGlobalIds.contains(id) ? false : state.enabledGlobalIds.add(id);
            }
            return state.enabledGlobalIds.remove(id);
        }
        for (WorldScript script : state.scripts) {
            if (script.id.equals(id) && script.enabled != enable) {
                script.enabled = enable;
                return true;
            }
        }
        return false;
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
        File configFile = configFile();
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                TupenterConfig loaded = GSON.fromJson(reader, TupenterConfig.class);
                if (loaded != null) {
                    // FOOTGUN: every field must be copied here BY HAND. A field
                    // that's saved but not copied loads as its default and the
                    // next save() erases it from disk (the /customfunction
                    // vanishing bug). Adding a config field? Add its copy line.
                    INSTANCE.resetOnNewSession = loaded.resetOnNewSession;
                    INSTANCE.rapidResendDelay = loaded.rapidResendDelay;
                    INSTANCE.resendDelay = Math.max(0, loaded.resendDelay);
                    INSTANCE.messageDelay = Math.max(0, loaded.messageDelay);
                    INSTANCE.batchMode = loaded.batchMode != null ? loaded.batchMode : BatchMode.PAUSE;
                    INSTANCE.historyDepth = Math.max(1, loaded.historyDepth);
                    INSTANCE.importHistoryCount = Math.max(1, Math.min(50, loaded.importHistoryCount));
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
                    INSTANCE.autoCloseBrackets = loaded.autoCloseBrackets;
                    INSTANCE.ctrlScrollHistory = loaded.ctrlScrollHistory;
                    INSTANCE.ctrlSpaceSend = loaded.ctrlSpaceSend;
                    INSTANCE.lazyExecutionEnabled = loaded.lazyExecutionEnabled;
                    INSTANCE.chatInputLength = clamp(loaded.chatInputLength, 256, 32766, 256);
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
                    INSTANCE.functions = loaded.functions != null ? loaded.functions : new ArrayList<>();
                    INSTANCE.maxCommandsPerTick = clamp(loaded.maxCommandsPerTick, 1, 512, 48);
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
            for (WorldScript script : state.scripts) {
                if (script.id == null || script.id.isEmpty()) {
                    script.id = WorldScript.newId();
                }
            }
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value == 0) {
            return fallback; // field absent from an older config file
        }
        return Math.max(min, Math.min(max, value));
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(configFile())) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
