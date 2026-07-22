package net.tupenter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.tupenter.command.CommandAliasManager;
import net.tupenter.command.CustomFunctionManager;
import net.tupenter.config.TupenterConfig;
import net.tupenter.command.ClientCommandRegistrar;
import net.tupenter.command.ClientVariableProvider;
import net.tupenter.command.KeyStateProvider;
import net.tupenter.command.EntityNbtVariableProvider;
import net.tupenter.command.PlayersVariableProvider;
import net.tupenter.command.TickScriptRunner;
import net.tupenter.command.WorldVariableProvider;
import net.tupenter.script.RealTimeVariableProvider;
import net.tupenter.script.AliasDefinition;
import net.tupenter.script.EvalContext;
import net.tupenter.script.MathEvaluator;
import net.tupenter.script.PersistentVariableStore;
import net.tupenter.script.Script;
import net.tupenter.script.ScriptExecutor;
import net.tupenter.script.ScriptParser;
import net.tupenter.script.SessionVariableStore;
import net.tupenter.script.Value;
import net.tupenter.script.VariableRegistry;

import java.util.Map;
import java.util.Random;
import net.tupenter.compat.ModMenuIntegration;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TupenterModClient implements ClientModInitializer {
	public static String lastMessage = "";
	private static KeyMapping resendKey;
	private static KeyMapping configKey;
    private static KeyMapping recordHistoryKey;
    public static boolean isFiring = false; // Public for Mixin access
    private static long lastChatCloseTime = 0;
    private static boolean isToggledOn = false;
    private static int keyHoldTicks = 0;

    // Queue System
    public static final Queue<String> pendingQueue = new LinkedList<>();
    public static int delayTimer = 0;

    // Script execution (docs/SCRIPTING_DESIGN.md §2)
    private static boolean forwardingScriptSend = false;
    private static final ScriptExecutor SCRIPT_EXECUTOR = new ScriptExecutor(
            new ScriptExecutor.PacketSender() {
                @Override
                public void sendCommand(String command) {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) return;
                    if (tryExecuteClientCommand(command)) {
                        return; // e.g. an alias body containing /calc or /tupenter abort
                    }
                    forwardingScriptSend = true;
                    try {
                        client.player.connection.sendCommand(command);
                    } finally {
                        forwardingScriptSend = false;
                    }
                }

                @Override
                public void sendChat(String message) {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) return;
                    forwardingScriptSend = true;
                    try {
                        client.player.connection.sendChat(message);
                    } finally {
                        forwardingScriptSend = false;
                    }
                }

                @Override
                public void error(String message) {
                    sendLocalCalcError(Component.literal(message));
                }

                @Override
                public void info(String message) {
                    sendEnhancedParsingInfo(message);
                }
            },
            ScriptExecutor.limits(
                    () -> TupenterConfig.INSTANCE.maxCommandsPerTick,
                    () -> TupenterConfig.INSTANCE.maxCommandsPerScript,
                    () -> TupenterConfig.INSTANCE.maxConcurrentScripts
            )
    );

    public static boolean isForwardingScriptSend() {
        return forwardingScriptSend;
    }

    /**
     * Client-side commands (/tupenter, /calc, /echo, custom commands) exist
     * only in the client dispatcher — sending them as packets makes the
     * SERVER error. Anything that re-sends a stored command string (resend
     * key, scripts) must try the client dispatcher first.
     *
     * @return true when the command was executed client-side
     */
    public static boolean tryExecuteClientCommand(String command) {
        var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
        Minecraft client = Minecraft.getInstance();
        if (dispatcher == null || client.getConnection() == null) {
            return false;
        }
        String root = command.trim();
        int space = root.indexOf(' ');
        if (space >= 0) {
            root = root.substring(0, space);
        }
        if (dispatcher.getRoot().getChild(root) == null) {
            return false;
        }

        try {
            dispatcher.execute(command.trim(), (FabricClientCommandSource) client.getConnection().getSuggestionsProvider());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            sendLocalCalcError(Component.literal(ex.getMessage()));
        }
        return true;
    }

    /**
     * A command line Tupenter's chain parser should own — it has a top-level
     * {@code &&}. Such a line must NOT hit the client dispatcher (Brigadier
     * chokes on the {@code &&}); it goes through sendCommand's reroute to
     * MixinConnection instead.
     */
    public static boolean isCommandChain(String commandWithoutSlash) {
        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                || !TupenterConfig.INSTANCE.commandChainingEnabled) {
            return false;
        }
        // /customcommand, /customfunction, /unroll carry a whole body as their
        // tail — a && there is body content, not a top-level chain (else adding
        // a function/command whose body has && evaluates its markers early).
        if (net.tupenter.command.ChatInputStyler.carriesEmbeddedLine("/" + commandWithoutSlash)) {
            return false;
        }
        return net.tupenter.command.ChatInputStyler.segments("/" + commandWithoutSlash).size() > 1;
    }

    /** Sends a stored line the way the resend system and scripts need it sent. */
    public static void dispatchStoredCommand(Minecraft client, String commandWithoutSlash) {
        // A && chain must reach the script parser, not the client dispatcher —
        // otherwise Brigadier parses the && as an argument (the /launch resend
        // bug). sendCommand reroutes chains for us.
        if (!isCommandChain(commandWithoutSlash) && tryExecuteClientCommand(commandWithoutSlash)) {
            return;
        }
        client.player.connection.sendCommand(commandWithoutSlash);
    }

    public static void submitScript(Script script) {
        SCRIPT_EXECUTOR.submit(script);
    }

    public static boolean isScriptSilenceActive() {
        return SCRIPT_EXECUTOR.isSilenceActive();
    }

    private static final Random SCRIPT_RANDOM = new Random();

    // Variable system (docs/SCRIPTING_DESIGN.md §5.3-5.4). Resolution order:
    // session (#set) → persistent (/tupenter var save) → live client state.
    public static final SessionVariableStore SESSION_VARIABLES = new SessionVariableStore();
    public static final PersistentVariableStore PERSISTENT_VARIABLES = new PersistentVariableStore();
    private static final VariableRegistry VARIABLE_REGISTRY = new VariableRegistry();

    private static final ClientVariableProvider CLIENT_VARIABLES = new ClientVariableProvider();
    private static final KeyStateProvider KEY_STATES = new KeyStateProvider();
    private static final WorldVariableProvider WORLD_VARIABLES = new WorldVariableProvider();
    private static final PlayersVariableProvider PLAYERS_VARIABLES = new PlayersVariableProvider();
    private static final RealTimeVariableProvider REAL_VARIABLES = new RealTimeVariableProvider();

    static {
        VARIABLE_REGISTRY.register(SESSION_VARIABLES);
        VARIABLE_REGISTRY.register(PERSISTENT_VARIABLES);
        VARIABLE_REGISTRY.register(CLIENT_VARIABLES);
        VARIABLE_REGISTRY.register(KEY_STATES);
        VARIABLE_REGISTRY.register(WORLD_VARIABLES);
        VARIABLE_REGISTRY.register(PLAYERS_VARIABLES);
        VARIABLE_REGISTRY.register(REAL_VARIABLES);
        VARIABLE_REGISTRY.register(new EntityNbtVariableProvider());
    }

    private static final TickScriptRunner TICK_SCRIPTS = new TickScriptRunner();

    public static void resetTickScriptFaults() {
        TICK_SCRIPTS.clearFaults();
    }

    /** Everything worth tab-completing inside a $...$ marker. */
    public static java.util.List<String> expressionCompletions() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        names.addAll(VARIABLE_REGISTRY.names());
        names.addAll(SESSION_VARIABLES.names());
        names.add("client.nbt.");
        names.add("target.nbt.");
        names.addAll(java.util.List.of(
                "rand", "randf", "pick", "range", "len", "nth", "indexof", "int", "float",
                "abs", "floor", "ceil", "round", "min", "max", "sqrt", "sin", "cos", "tan",
                "blockset", "itemset", "effectset", "entityset", "block", "contains", "true", "false",
                "trim", "upper", "lower", "substr", "replace", "vec", "x", "y", "z", "raycast", "raycast_block",
                "entity_nbt", "entity_type", "entity_raycast", "entities", "nearest_entity"));
        names.addAll(CustomFunctionManager.getFunctionMap().keySet()); // user functions tab-complete too
        return new java.util.ArrayList<>(names);
    }

    /**
     * Like ChatInputStyler.maskMarkers, but each complete $...$ is replaced by
     * a SAME-LENGTH mask whose token count matches the marker's evaluated
     * value — so a position marker like $client.target_block$ (three coords)
     * masks to three numeric tokens and a command's later arguments still
     * parse and complete (/setblock $client.target_block$ mine|). Positions
     * stay 1:1 with the real input; an eval failure (no world, bad expr) falls
     * back to a single zero-blob, the old behavior.
     */
    public static String smartMaskMarkers(String text) {
        StringBuilder out = null;
        net.tupenter.script.EvalContext ctx = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c != '$') {
                i++;
                continue;
            }
            // balance-aware: the runtime's own rule for where this marker ends,
            // so masking never splits $dist($client.pos$, ...)$ at the inner $
            int close = MathEvaluator.indexOfMarkerEnd(text, i);
            if (close < 0) {
                break; // unclosed marker — the variable-suggestion path owns the cursor there
            }
            if (out == null) {
                out = new StringBuilder(text);
            }
            int tokens = 1;
            try {
                if (ctx == null) {
                    ctx = new net.tupenter.script.EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER, BLOCK_READER, CustomFunctionManager.resolver(), RAYCASTER, ENTITY_ACCESS);
                }
                String value = MathEvaluator.evaluateValue(text.substring(i + 1, close), ctx).substitutionString();
                tokens = tokenCount(value);
            } catch (RuntimeException ignored) {
                // eval failed (e.g. target_block off-crosshair while chat is open) —
                // fall back to the known arity of a position variable, else one blob
                tokens = positionalArity(text.substring(i + 1, close));
            }
            writeTokenMask(out, i, close - i + 1, tokens);
            i = close + 1;
        }
        return out == null ? text : out.toString();
    }

    private static int tokenCount(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? 1 : trimmed.split("\\s+").length;
    }

    /** Variables that resolve to a 3-coordinate position — used to size a mask when the marker can't currently evaluate. */
    private static final java.util.Set<String> POSITIONAL_VARS = java.util.Set.of(
            "client.pos", "client.target_block", "client.motion");

    private static int positionalArity(String inner) {
        return POSITIONAL_VARS.contains(inner.trim().toLowerCase(java.util.Locale.ROOT)) ? 3 : 1;
    }

    /** Fills [start, start+len) with {@code tokens} runs of '0' joined by single spaces — length preserved. */
    private static void writeTokenMask(StringBuilder out, int start, int len, int tokens) {
        int spaces = Math.max(0, tokens - 1);
        if (tokens < 1 || len - spaces < tokens) {
            for (int k = 0; k < len; k++) {
                out.setCharAt(start + k, '0');
            }
            return;
        }
        int zeros = len - spaces;
        int base = zeros / tokens;
        int extra = zeros % tokens;
        int pos = start;
        for (int t = 0; t < tokens; t++) {
            int count = base + (t < extra ? 1 : 0);
            for (int k = 0; k < count; k++) {
                out.setCharAt(pos++, '0');
            }
            if (t < tokens - 1) {
                out.setCharAt(pos++, ' ');
            }
        }
    }

    /**
     * Tag ids ("#minecraft:wool") for completion inside a set function —
     * scoped to the function's registry when known, all three otherwise.
     */
    public static java.util.List<String> tagCompletions(String functionName) {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return java.util.List.of();
        }
        java.util.TreeSet<String> tags = new java.util.TreeSet<>();
        String function = functionName == null ? "" : functionName;
        if (function.equals("blockset") || function.isEmpty()) {
            collectTags(connection, net.minecraft.core.registries.Registries.BLOCK, tags);
        }
        if (function.equals("itemset") || function.isEmpty()) {
            collectTags(connection, net.minecraft.core.registries.Registries.ITEM, tags);
        }
        if (function.equals("effectset") || function.isEmpty()) {
            collectTags(connection, net.minecraft.core.registries.Registries.MOB_EFFECT, tags);
        }
        if (function.equals("entityset") || function.isEmpty()) {
            collectTags(connection, net.minecraft.core.registries.Registries.ENTITY_TYPE, tags);
        }
        return new java.util.ArrayList<>(tags);
    }

    private static <T> void collectTags(net.minecraft.client.multiplayer.ClientPacketListener connection,
                                        net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey,
                                        java.util.Set<String> out) {
        connection.registryAccess().lookupOrThrow(registryKey).getTags()
                .forEach(named -> out.add("#" + named.key().location()));
    }

    /** Names to check /customcommand bodies against at save time. */
    public static java.util.Set<String> knownDottedVariableNames() {
        java.util.Set<String> names = new java.util.HashSet<>(CLIENT_VARIABLES.names());
        names.addAll(WORLD_VARIABLES.names());
        names.addAll(PLAYERS_VARIABLES.names());
        names.addAll(REAL_VARIABLES.names());
        return names;
    }

    /**
     * Identity of the world you're standing in, for per-world script states:
     * "server:<address>" on multiplayer, "world:<level folder>" in
     * singleplayer, null when not in a world.
     */
    public static String currentWorldKey() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return null;
        }
        net.minecraft.client.multiplayer.ServerData server = client.getCurrentServer();
        if (server != null) {
            return "server:" + server.ip.toLowerCase(java.util.Locale.ROOT);
        }
        net.minecraft.client.server.IntegratedServer integrated = client.getSingleplayerServer();
        if (integrated != null) {
            java.nio.file.Path root = integrated.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize();
            java.nio.file.Path folder = root.getFileName();
            return "world:" + (folder != null ? folder.toString() : "unknown");
        }
        return null;
    }

    /** /tupenter scripts — what would run right here, right now. */
    /** /tupenter abort — panic stop: kills every running script + resend queue and turns the tick master off. */
    private static int runAbortAllCommand(CommandContext<FabricClientCommandSource> context) {
        int aborted = SCRIPT_EXECUTOR.runningCount();
        SCRIPT_EXECUTOR.abortAll();
        pendingQueue.clear();
        delayTimer = 0;
        // panic switch: aborting while tick scripts keep resubmitting every tick would be futile
        if (TupenterConfig.INSTANCE.tickScriptsEnabled) {
            TupenterConfig.INSTANCE.tickScriptsEnabled = false;
            TupenterConfig.save();
            context.getSource().sendFeedback(Component.literal(
                    "Tick scripts disabled (re-enable in Mod Menu → Tupenter → Scripts).")
                    .withStyle(ChatFormatting.YELLOW));
        }
        context.getSource().sendFeedback(Component.literal(
                aborted > 0 ? "Aborted " + aborted + " running script(s)." : "Nothing to abort.")
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    /**
     * /tupenter abort <id> — stop one thing by its /tupenter running id. A
     * running instance is killed; an armed tick script's pid is switched OFF
     * (its Mod Menu toggle), not killed-and-rearmed. Leaves the master alone.
     */
    private static int runAbortOneCommand(CommandContext<FabricClientCommandSource> context) {
        int id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id");
        if (SCRIPT_EXECUTOR.abort(id)) {
            context.getSource().sendFeedback(Component.literal("Aborted script " + id + ".").withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        String tickResult = switchOffTickPid(id);
        context.getSource().sendFeedback(Component.literal(tickResult != null
                ? tickResult
                : "No running script or tick script " + id + " — see /tupenter running.")
                .withStyle(tickResult != null ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return 1;
    }

    /**
     * Session pids for armed tick scripts, keyed by "worldKey|kind:id". Drawn
     * from the executor's id space (via reservePid) so an abort number is never
     * ambiguous, and stable per script for the session — even across
     * enable/disable — because the key is the script's own id.
     */
    private static final java.util.Map<String, Integer> TICK_PIDS = new java.util.HashMap<>();

    private static int tickPidFor(String worldKey, TupenterConfig.ArmedScript script) {
        return TICK_PIDS.computeIfAbsent(worldKey + "|" + script.key(), k -> SCRIPT_EXECUTOR.reservePid());
    }

    /** A tick-script row for the running views: its pid, display label, and scope. */
    private record ArmedRow(int pid, String display, boolean global) {}

    /** Armed tick scripts for the current world with their session pids (empty when master off). */
    private static java.util.List<ArmedRow> armedRows() {
        TupenterConfig config = TupenterConfig.INSTANCE;
        String worldKey = currentWorldKey();
        if (!config.tickScriptsEnabled || worldKey == null) {
            return java.util.List.of();
        }
        java.util.List<ArmedRow> rows = new java.util.ArrayList<>();
        for (TupenterConfig.ArmedScript script : config.armedScripts(worldKey)) {
            String display = script.name().isEmpty() ? previewLine(script.body()) : script.name();
            rows.add(new ArmedRow(tickPidFor(worldKey, script), display, script.global()));
        }
        return rows;
    }

    /** If id is an armed tick script's pid for this world, switch it off. Returns a status line, or null. */
    private static String switchOffTickPid(int id) {
        String worldKey = currentWorldKey();
        if (worldKey == null) {
            return null;
        }
        String prefix = worldKey + "|";
        for (java.util.Map.Entry<String, Integer> entry : TICK_PIDS.entrySet()) {
            if (entry.getValue() != id || !entry.getKey().startsWith(prefix)) {
                continue;
            }
            String rest = entry.getKey().substring(prefix.length()); // "g:<id>" or "w:<id>"
            boolean global = rest.startsWith("g:");
            String scriptId = rest.substring(2);
            String text = TupenterConfig.INSTANCE.disableArmedScript(worldKey, global, scriptId);
            if (text == null) {
                return null; // pid known but already off — fall through to "no such id"
            }
            TupenterConfig.save();
            // TickScriptRunner reconciles next tick: its loop is no longer armed,
            // so it gets stopped. (No direct abort here — the running instance is
            // keyed by the newline-collapsed body, which the runner owns.)
            return "Tick script " + id + " (" + (global ? "global" : "world")
                    + ") switched OFF. Re-enable in Mod Menu → Scripts.";
        }
        return null;
    }

    /** /tupenter running — what's active now: armed tick scripts (every tick) + ad-hoc/parked instances. */
    private static int runRunningCommand(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        TupenterConfig config = TupenterConfig.INSTANCE;
        String key = currentWorldKey();
        java.util.List<String> configured = key != null ? config.armedScriptLines(key) : java.util.List.of();
        java.util.List<ArmedRow> armed = armedRows();
        java.util.List<ScriptExecutor.RunningInfo> instances = SCRIPT_EXECUTOR.runningInfos();

        if (armed.isEmpty() && instances.isEmpty()) {
            boolean offButConfigured = !config.tickScriptsEnabled && !configured.isEmpty();
            source.sendFeedback(Component.literal(offButConfigured
                    ? "Nothing running — " + configured.size() + " tick script(s) armed but the master toggle is OFF (/tupenter scripts enable)."
                    : "Nothing active — no tick scripts armed here, nothing running.").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        if (!armed.isEmpty()) {
            source.sendFeedback(Component.literal(
                    "Tick scripts (" + armed.size() + ") — every tick; [abort] switches one OFF:").withStyle(ChatFormatting.AQUA));
            for (ArmedRow row : armed) {
                MutableComponent line = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("id " + row.pid() + "  " + row.display()
                                + (row.global() ? "  (global)" : "")).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" [abort]").withStyle(abortLinkStyle(row.pid())));
                source.sendFeedback(line);
            }
        }
        if (!instances.isEmpty()) {
            source.sendFeedback(Component.literal(
                    "Running now (" + instances.size() + ") — click [abort] or /tupenter abort <id>; /tupenter abort stops all:").withStyle(ChatFormatting.AQUA));
            for (ScriptExecutor.RunningInfo info : instances) {
                MutableComponent row = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(info.line()).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" [abort]").withStyle(abortLinkStyle(info.id())));
                source.sendFeedback(row);
            }
        }
        return 1;
    }

    /** Red, clickable "[abort]" that runs /tupenter abort &lt;id&gt; when clicked. */
    private static Style abortLinkStyle(int id) {
        return Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent.RunCommand("/tupenter abort " + id))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Stop script " + id)));
    }

    private static String previewLine(String line) {
        return line.length() > 50 ? line.substring(0, 50) + "…" : line;
    }

    /** When true, the running-scripts list is drawn as an on-screen HUD panel (bypasses chat). */
    private static boolean runningHudVisible;

    /** /tupenter running hud — toggle the always-on-screen running list (survives chat spam). */
    private static int runRunningHudToggle(CommandContext<FabricClientCommandSource> context) {
        runningHudVisible = !runningHudVisible;
        context.getSource().sendFeedback(Component.literal(runningHudVisible
                ? "Running HUD on — the list now shows on screen (run again to hide)."
                : "Running HUD off.").withStyle(runningHudVisible ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return 1;
    }

    private static final int HUD_AQUA = 0xFF55FFFF;   // header
    private static final int HUD_GOLD = 0xFFFFD24A;   // armed tick scripts (fire every tick)
    private static final int HUD_WHITE = 0xFFE0E0E0;  // running instances
    private static final int HUD_GRAY = 0xFF9AA0A6;   // notes

    /** Draws the running-scripts panel top-left while the HUD is toggled on. */
    private static void renderRunningHud(GuiGraphics graphics, DeltaTracker tickCounter) {
        if (!runningHudVisible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        Font font = mc.font;

        // Two independent sources, exactly like /tupenter running:
        //   armed tick scripts (Mod Menu → Scripts) fire every tick and finish
        //   in-tick, so they're rarely a live instance — count them separately.
        //   Each carries its tick pid so the HUD number matches /tupenter abort.
        TupenterConfig config = TupenterConfig.INSTANCE;
        String key = currentWorldKey();
        java.util.List<String> configured = key != null ? config.armedScriptLines(key) : java.util.List.of();
        java.util.List<ArmedRow> armed = armedRows();
        java.util.List<ScriptExecutor.RunningInfo> infos = SCRIPT_EXECUTOR.runningInfos();

        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();

        texts.add("Tupenter — " + armed.size() + " tick · " + infos.size() + " running");
        colors.add(HUD_AQUA);

        for (ArmedRow row : armed) {
            texts.add("id " + row.pid() + "  " + row.display() + (row.global() ? "  (global)" : ""));
            colors.add(HUD_GOLD);
        }
        for (ScriptExecutor.RunningInfo info : infos) {
            texts.add(info.line());
            colors.add(HUD_WHITE);
        }
        if (armed.isEmpty() && infos.isEmpty()) {
            // master off but scripts configured is the confusing case — say so
            boolean offButConfigured = !config.tickScriptsEnabled && !configured.isEmpty();
            texts.add(offButConfigured
                    ? "(tick master OFF — " + configured.size() + " armed, not firing)"
                    : "(nothing armed or running)");
            colors.add(HUD_GRAY);
        }

        int pad = 3;
        int lineH = font.lineHeight + 1;
        int width = 0;
        for (String line : texts) {
            width = Math.max(width, font.width(line));
        }
        int x = 4;
        int y = 4;
        int boxW = width + pad * 2;
        int boxH = texts.size() * lineH + pad * 2 - 1;
        graphics.fill(x, y, x + boxW, y + boxH, 0xA0000000); // translucent black backdrop

        int ty = y + pad;
        for (int i = 0; i < texts.size(); i++) {
            graphics.drawString(font, texts.get(i), x + pad, ty, colors.get(i));
            ty += lineH;
        }
    }

    private static int runScriptsStatusCommand(CommandContext<FabricClientCommandSource> context) {
        TupenterConfig config = TupenterConfig.INSTANCE;
        String key = currentWorldKey();
        TupenterConfig.WorldScriptState state = config.worldState(key);

        context.getSource().sendFeedback(Component.literal(
                "Tick scripts: master " + (config.tickScriptsEnabled ? "ON" : "OFF")
                + " · world: " + (key != null ? key : "none")).withStyle(ChatFormatting.AQUA));

        if (config.globalScripts.isEmpty()) {
            context.getSource().sendFeedback(Component.literal(" (no global scripts defined)").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (TupenterConfig.GlobalScript script : config.globalScripts) {
            boolean armed = state != null && state.enabledGlobalIds.contains(script.id);
            context.getSource().sendFeedback(Component.literal(
                    (armed ? " ✔ " : " ✘ ") + scriptListLabel(script.text) + " §8(global)")
                    .withStyle(armed ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        }
        if (state != null) {
            for (TupenterConfig.WorldScript script : state.scripts) {
                context.getSource().sendFeedback(Component.literal(
                        (script.enabled ? " ✔ " : " ✘ ") + scriptListLabel(script.text) + " §8(this world)")
                        .withStyle(script.enabled ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
            }
        }
        if (key != null && !config.scriptNames(key).isEmpty()) {
            context.getSource().sendFeedback(Component.literal(
                    "Toggle a named one: /tupenter scripts enable|disable <name> (no name = master on/off) · stop a running one: /tupenter abort <id|name>")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        int armedCount = key == null ? 0 : config.armedScriptLines(key).size();
        String verdict;
        if (key == null) {
            verdict = "Not in a world — nothing runs.";
        } else if (!config.tickScriptsEnabled) {
            verdict = armedCount + " armed here, but the master switch is OFF. /tupenter scripts enable";
        } else {
            verdict = armedCount + " running here, 20x/second." + (armedCount == 0 ? " This world is safe." : "");
        }
        context.getSource().sendFeedback(Component.literal(verdict).withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    /** /tupenter scripts enable|disable (no name) — the master switch (per-script arming stays put). */
    private static int runScriptsMasterCommand(CommandContext<FabricClientCommandSource> context, boolean on) {
        TupenterConfig.INSTANCE.tickScriptsEnabled = on;
        TupenterConfig.save();
        String key = currentWorldKey();
        int armed = key == null ? 0 : TupenterConfig.INSTANCE.armedScriptLines(key).size();
        context.getSource().sendFeedback(Component.literal(on
                ? "Tick scripts ON — " + armed + " armed in this world now running (per-script arming unchanged)."
                : "Tick scripts OFF — all paused (still armed; /tupenter scripts enable resumes them).").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static String scriptPreview(String line) {
        String single = line.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        return single.length() > 50 ? single.substring(0, 50) + "…" : single;
    }

    /** "restock §8= /clear …" for a named script, else just the body preview. */
    private static String scriptListLabel(String text) {
        String name = TupenterConfig.scriptName(text);
        if (name.isEmpty()) {
            return scriptPreview(text);
        }
        return "§f" + name + "§r §8= " + scriptPreview(TupenterConfig.scriptBody(text));
    }

    /** /tupenter scripts enable|disable &lt;name&gt; — arms/disarms named tick script(s) for this world. */
    private static int runSetArmedByName(CommandContext<FabricClientCommandSource> context, boolean enable) {
        return applyArmedByName(context, enable, enable ? "Enabled" : "Disabled");
    }

    /** /tupenter abort &lt;name&gt; — switch a named tick script OFF (same as disable, under the abort verb). */
    private static int runAbortByName(CommandContext<FabricClientCommandSource> context) {
        return applyArmedByName(context, false, "Aborted");
    }

    private static int applyArmedByName(CommandContext<FabricClientCommandSource> context, boolean enable, String verb) {
        String name = StringArgumentType.getString(context, "name");
        String worldKey = currentWorldKey();
        if (worldKey == null) {
            context.getSource().sendFeedback(Component.literal("Not in a world — nothing to arm.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        java.util.List<TupenterConfig.ScriptRef> matches = TupenterConfig.INSTANCE.scriptsByName(worldKey, name);
        if (matches.isEmpty()) {
            context.getSource().sendFeedback(Component.literal(
                    "No tick script named '" + name + "' here — /tupenter scripts lists names, /tupenter running lists ids (only named scripts toggle by name).")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        int changed = 0;
        for (TupenterConfig.ScriptRef ref : matches) {
            if (TupenterConfig.INSTANCE.setArmed(worldKey, ref.global(), ref.id(), enable)) {
                changed++;
            }
        }
        if (changed > 0) {
            TupenterConfig.save();
        }
        long globals = matches.stream().filter(TupenterConfig.ScriptRef::global).count();
        String scope = scopeLabel(globals, matches.size() - globals);
        if (changed == 0) {
            context.getSource().sendFeedback(Component.literal(
                    "'" + name + "' is already " + (enable ? "enabled" : "off") + " (" + scope + ").")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        String note = enable && !TupenterConfig.INSTANCE.tickScriptsEnabled
                ? " Master is OFF — /tupenter scripts enable to run it." : "";
        context.getSource().sendFeedback(Component.literal(
                verb + " '" + name + "' (" + scope + ")." + note)
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static String scopeLabel(long globals, long world) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (globals > 0) {
            parts.add(globals + " global");
        }
        if (world > 0) {
            parts.add(world + " this-world");
        }
        return String.join(", ", parts);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestScriptNames(
            CommandContext<FabricClientCommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return net.minecraft.commands.SharedSuggestionProvider.suggest(
                TupenterConfig.INSTANCE.scriptNames(currentWorldKey()), builder);
    }

    public static ScriptParser.Options parserOptions() {
        // The individual feature gates (chaining, silent, variables, loops,
        // conditionals, lazy) were collapsed into the master Enhanced Command
        // Parsing toggle — they interdepend, so a partial subset never made
        // sense. When the master is on (the only time this parser runs), they
        // are all on. The config fields survive (ignored) for back-compat.
        return new ScriptParser.Options(
                true, // command chaining
                TupenterConfig.INSTANCE.numberMathMode,
                CommandAliasManager.getAliasMap(),
                true, // silent directive
                true, // variables
                true, // loops
                true, // conditionals
                TupenterConfig.INSTANCE.maxLoopIterations,
                TupenterConfig.INSTANCE.maxCommandsPerScript,
                SCRIPT_RANDOM,
                VARIABLE_REGISTRY,
                SESSION_VARIABLES,
                TAG_RESOLVER,
                BLOCK_READER,
                CustomFunctionManager.resolver(), // user-defined /customfunctions
                RAYCASTER,
                ENTITY_ACCESS,
                true // lazy execution
        );
    }

    /**
     * Backs block(x, y, z) with the client's synced world — the reason
     * block conditions need no server round trip. Null (a clear error, not
     * a guess) when there's no world or the chunk isn't loaded.
     */
    public static final net.tupenter.script.BlockReader BLOCK_READER = (x, y, z) -> {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null || x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return null;
        }
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos((int) x, (int) y, (int) z);
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()).toString();
    };

    /**
     * Backs raycast(...)/raycast_block(...) with the vanilla crosshair hit
     * test: Level.clip with OUTLINE blocks + NONE fluids, so it hits the same
     * collidable blocks your crosshair does (sub-block accurate, passes through
     * grass/flowers/water). Null (→ the "miss" sentinel) on a miss or no world.
     */
    public static final net.tupenter.script.Raycaster RAYCASTER = new net.tupenter.script.Raycaster() {
        @Override
        public String fromPlayer(double maxDist) {
            net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            if (player == null || level == null) {
                return null;
            }
            // getViewVector(1.0) is already a unit look vector; eye pos is the ray origin
            return clip(level, player.getEyePosition(), player.getViewVector(1.0F), maxDist, player);
        }

        @Override
        public String cast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return null;
            }
            net.minecraft.world.phys.Vec3 dir = new net.minecraft.world.phys.Vec3(dx, dy, dz).normalize();
            return clip(level, new net.minecraft.world.phys.Vec3(ox, oy, oz), dir, maxDist, Minecraft.getInstance().player);
        }

        // from + dir*maxDist, then Level.clip. dir must be unit length. entity may be null (cast without a player).
        private String clip(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 from,
                            net.minecraft.world.phys.Vec3 dir, double maxDist, net.minecraft.world.entity.Entity entity) {
            net.minecraft.world.phys.Vec3 to = from.add(dir.scale(maxDist));
            net.minecraft.world.phys.shapes.CollisionContext collision = entity != null
                    ? net.minecraft.world.phys.shapes.CollisionContext.of(entity)
                    : net.minecraft.world.phys.shapes.CollisionContext.empty();
            net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, collision));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                return null;
            }
            net.minecraft.core.BlockPos pos = hit.getBlockPos();
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    };

    /**
     * Backs entity_nbt / entity_raycast / entities / nearest_entity off the
     * synced client world (~render distance). "self"/"target"/UUID selectors for
     * NBT; ProjectileUtil + ClientLevel lookups for the UUID finders. See
     * {@link net.tupenter.command.EntityAccessImpl}.
     */
    public static final net.tupenter.script.EntityAccess ENTITY_ACCESS = new net.tupenter.command.EntityAccessImpl();

    /**
     * Backs blockset/itemset/effectset with the connection's synced
     * registries: a "#tag" resolves to its members, a null tag enumerates
     * the whole registry (rand(effectset()) = a random effect). Null when
     * not in-game; an unknown tag resolves to an empty list.
     */
    public static final net.tupenter.script.TagResolver TAG_RESOLVER = new net.tupenter.script.TagResolver() {
        @Override
        public java.util.List<String> resolve(TagKind kind, String tagId) {
            net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection == null) {
                return null;
            }
            return registryIds(connection, registryKey(kind), tagId);
        }

        @Override
        public String lookup(TagKind kind, String id) {
            net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (connection == null || location == null) {
                return null;
            }
            return connection.registryAccess().lookupOrThrow(registryKey(kind)).containsKey(location)
                    ? location.toString() : null;
        }
    };

    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey(
            net.tupenter.script.TagResolver.TagKind kind) {
        return (net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>) (net.minecraft.resources.ResourceKey<?>) switch (kind) {
            case ITEM -> net.minecraft.core.registries.Registries.ITEM;
            case BLOCK -> net.minecraft.core.registries.Registries.BLOCK;
            case EFFECT -> net.minecraft.core.registries.Registries.MOB_EFFECT;
            case ENTITY -> net.minecraft.core.registries.Registries.ENTITY_TYPE;
        };
    }

    private static <T> java.util.List<String> registryIds(
            net.minecraft.client.multiplayer.ClientPacketListener connection,
            net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey,
            String tagId) {
        net.minecraft.core.Registry<T> registry = connection.registryAccess().lookupOrThrow(registryKey);
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (tagId == null) {
            for (net.minecraft.resources.ResourceLocation id : registry.keySet()) {
                ids.add(id.toString());
            }
            return ids;
        }
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(tagId);
        if (location == null) {
            return ids;
        }
        for (var holder : registry.getTagOrEmpty(net.minecraft.tags.TagKey.create(registryKey, location))) {
            holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()));
        }
        return ids;
    }

    public static void savePersistentVariables() {
        TupenterConfig.INSTANCE.persistentVariables = PERSISTENT_VARIABLES.serialize();
        TupenterConfig.save();
    }

    public static void sendEnhancedParsingInfo(String message) {
        sendLocalCalcFeedback(Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    /**
     * #stage prefix: records the rest of the line as the most recent resend
     * history entry WITHOUT executing it. Deliberately bypasses the
     * recordHistory toggle and resend filter — staging is explicit intent.
     * Evaluation happens at fire time, so staged $rand(...)$ re-rolls per resend.
     *
     * @return true when the line was a #stage line (packet must be cancelled)
     */
    /** Matched length of {@code longForm} or its {@code shortForm} at the head of {@code trimmed} (word boundary required), or -1. */
    private static int prefixWordLen(String trimmed, String longForm, String shortForm) {
        for (String form : new String[]{longForm, shortForm}) {
            if (trimmed.regionMatches(true, 0, form, 0, form.length())
                    && (trimmed.length() == form.length() || Character.isWhitespace(trimmed.charAt(form.length())))) {
                return form.length();
            }
        }
        return -1;
    }

    public static boolean handleStagePrefix(String line, boolean commandOrigin) {
        String trimmed = line.trim();
        int wordLen = prefixWordLen(trimmed, "#stage", "#st");
        if (wordLen < 0) {
            return false;
        }

        String staged = trimmed.substring(wordLen).trim();
        if (staged.isEmpty()) {
            sendEnhancedParsingError("#stage needs a line to stage, e.g. #stage /kill @e[type=fireball]");
            return true;
        }
        if (commandOrigin && !staged.startsWith("/") && !staged.startsWith("#")) {
            staged = "/" + staged;
        }

        forceAddToHistory(staged);
        sendEnhancedParsingInfo("Staged: " + staged);
        return true;
    }

    /**
     * #unstage [count] — removes the newest [count] (default 1) entries from
     * resend history and reports what the next resend now is. The #unstage
     * line itself is never recorded.
     *
     * @return true when the line was an #unstage line (packet must be cancelled)
     */
    public static boolean handleUnstagePrefix(String line) {
        String trimmed = line.trim();
        int wordLen = prefixWordLen(trimmed, "#unstage", "#ust");
        if (wordLen < 0) {
            return false;
        }

        String argument = trimmed.substring(wordLen).trim();
        int count = 1;
        if (!argument.isEmpty()) {
            try {
                count = Integer.parseInt(argument);
            } catch (NumberFormatException ex) {
                sendEnhancedParsingError("#unstage takes a count, e.g. #unstage 3");
                return true;
            }
            if (count < 1) {
                sendEnhancedParsingError("#unstage count must be at least 1");
                return true;
            }
        }

        int removed = 0;
        while (removed < count && !messageHistory.isEmpty()) {
            messageHistory.remove(messageHistory.size() - 1);
            removed++;
        }

        String next;
        if (messageHistory.isEmpty()) {
            next = "nothing — history is empty";
        } else {
            next = messageHistory.get(messageHistory.size() - 1);
            if (next.length() > 60) {
                next = next.substring(0, 60) + "…";
            }
        }
        sendEnhancedParsingInfo("Unstaged " + removed + " — next resend: " + next);
        return true;
    }

    /**
     * #pid — run a script under a caller-chosen id ("PID"). Forms:
     *   #pid N &lt;line&gt;           — start pid N; refuses if N is already running
     *   #pid N replace &lt;line&gt;   — (re)start pid N with &lt;line&gt;, killing any live one
     *
     * Management verbs (#pid N abort, #pid list) were intentionally NOT kept —
     * /tupenter abort &lt;id&gt; and /tupenter running already cover them. The old
     * handlers are commented out below in case we ever want them back.
     *
     * @return true when the line was a #pid line (packet must be cancelled)
     */
    public static boolean handlePidPrefix(String line) {
        String trimmed = line.trim();
        int wordLen = prefixWordLen(trimmed, "#pid", "#pid");
        if (wordLen < 0) {
            return false;
        }
        String rest = trimmed.substring(wordLen).trim();
        if (rest.isEmpty()) {
            sendEnhancedParsingError("#pid needs an id and a line — e.g. #pid 3 /say hi  (add 'replace' to restart a live pid)");
            return true;
        }
        // #pid list — removed; kept for reference:
        //   if (rest.equalsIgnoreCase("list")) { pidList(); return true; }
        if (rest.equalsIgnoreCase("list")) {
            sendEnhancedParsingError("#pid list isn't a thing — use /tupenter running (it has clickable [abort] rows).");
            return true;
        }

        int sp = rest.indexOf(' ');
        String idToken = sp < 0 ? rest : rest.substring(0, sp);
        int id;
        try {
            id = Integer.parseInt(idToken);
        } catch (NumberFormatException ex) {
            sendEnhancedParsingError("#pid wants a number — got '" + idToken + "'. Try #pid 3 /say hi.");
            return true;
        }
        if (id < 1) {
            sendEnhancedParsingError("#pid ids start at 1.");
            return true;
        }
        String after = sp < 0 ? "" : rest.substring(sp + 1).trim();

        // #pid N abort — removed; kept for reference:
        //   if (after.equalsIgnoreCase("abort")) {
        //       boolean aborted = SCRIPT_EXECUTOR.abort(id);
        //       sendEnhancedParsingInfo/Error(...); return true;
        //   }
        if (after.equalsIgnoreCase("abort")) {
            sendEnhancedParsingError("#pid " + id + " abort isn't a thing — use /tupenter abort " + id + ".");
            return true;
        }

        boolean replace = false;
        String body = after;
        if (after.regionMatches(true, 0, "replace", 0, 7)
                && (after.length() == 7 || Character.isWhitespace(after.charAt(7)))) {
            replace = true;
            body = after.substring(7).trim();
        }
        if (body.isEmpty()) {
            sendEnhancedParsingError(replace
                    ? "#pid " + id + " replace needs a line to run, e.g. #pid " + id + " replace /randomfill …"
                    : "#pid " + id + " needs a line to run, e.g. #pid " + id + " /say hi  (add 'replace' to restart a live pid)");
            return true;
        }
        runOnPid(id, replace, body, trimmed);
        return true;
    }

    /** Parses a #pid body and starts it under the given id, reporting the outcome. */
    private static void runOnPid(int id, boolean replace, String body, String originalPidLine) {
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(body, body, parserOptions());
        if (result.error() != null) {
            sendEnhancedParsingError(result.error());
            return;
        }
        if (!result.changed()) {
            sendEnhancedParsingError("#pid " + id + ": nothing runnable in '" + body + "'.");
            return;
        }
        ScriptExecutor.PidResult outcome = SCRIPT_EXECUTOR.submitPid(result.script(), id, replace);
        switch (outcome) {
            case STARTED, REPLACED -> {
                updateLastMessage(originalPidLine); // ↑ resend re-runs it on the same pid
                result.notices().forEach(TupenterModClient::sendEnhancedParsingInfo);
                sendEnhancedParsingInfo(outcome == ScriptExecutor.PidResult.REPLACED
                        ? "Replaced pid " + id + " (restarted)."
                        : "Started pid " + id + ".");
            }
            case REFUSED_RUNNING -> sendEnhancedParsingError("pid " + id + " is already running — add 'replace' to restart it (#pid "
                    + id + " replace …) or stop it with /tupenter abort " + id + ".");
            case REJECTED -> sendEnhancedParsingError("pid " + id
                    + " couldn't start — concurrency or per-script limit hit (see /tupenter).");
        }
    }

    /* #pid list — control verb removed (see handlePidPrefix); /tupenter running
       covers it with the same clickable [abort] rows. Kept here in case we
       bring the directive-side listing back.
    private static void pidList() {
        java.util.List<ScriptExecutor.RunningInfo> infos = SCRIPT_EXECUTOR.runningInfos();
        if (infos.isEmpty()) {
            sendEnhancedParsingInfo("No scripts running.");
            return;
        }
        sendEnhancedParsingInfo("Running (" + infos.size() + ") — click [abort]:");
        for (ScriptExecutor.RunningInfo info : infos) {
            MutableComponent row = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(info.line()).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" [abort]").withStyle(abortLinkStyle(info.id())));
            sendLocalCalcFeedback(row);
        }
    }
    */

    /**
     * Records a typed command that Fabric's client-command handler will
     * swallow before it can become a packet (/tupenter, /echo, ...). Custom
     * command aliases are skipped — they DO reach the packet path, which
     * records them with #norecord respected.
     */
    public static void recordIfClientOnlyCommand(String command) {
        int separator = command.indexOf(' ');
        String first = (separator >= 0 ? command.substring(0, separator) : command).toLowerCase(java.util.Locale.ROOT);
        if (first.isEmpty() || CommandAliasManager.hasAlias(first)) {
            return;
        }
        var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
        if (dispatcher == null || dispatcher.getRoot().getChild(first) == null) {
            return; // a normal server command — the packet path records it
        }
        updateLastMessage("/" + command);
    }

    /** Adds to resend history bypassing the recording toggle and filter (#stage, #record). */
    public static void forceAddToHistory(String msg) {
        if (messageHistory.isEmpty() || !messageHistory.get(messageHistory.size() - 1).equals(msg)) {
            messageHistory.add(msg);
            if (messageHistory.size() > 50) {
                messageHistory.remove(0);
            }
        }
    }

    // History tracking
    public static final List<String> messageHistory = new ArrayList<>();
    public static List<String> lockedBatch = new ArrayList<>(); // For toggle mode locking

    public static void updateLastMessage(String msg) {
        if (!TupenterConfig.INSTANCE.recordHistory) return;
        if (msg == null || msg.trim().isEmpty()) return;

        // Apply Resend Filter at recording time
        boolean isCommand = msg.startsWith("/");
        switch (TupenterConfig.INSTANCE.resendFilter) {
            case CHAT_ONLY:
                if (isCommand) return;
                break;
            case COMMANDS_ONLY:
                if (!isCommand) return;
                break;
            case BOTH:
            default:
                break;
        }
        
        // Avoid duplicates at top of stack (optional, but good UX)
        if (!messageHistory.isEmpty() && messageHistory.get(messageHistory.size() - 1).equals(msg)) return;

        messageHistory.add(msg);
        if (messageHistory.size() > 50) {
            messageHistory.remove(0);
        }
    }

	@Override
	public void onInitializeClient() {
		KeyMapping.Category tupenterCategory = new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath("tupenter", "general"));

		resendKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.resend",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			tupenterCategory
		));

		configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.config",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			tupenterCategory
		));

		recordHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.toggle_recording",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			tupenterCategory
		));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                {
                    dispatcher.register(literal("calc")
                            .then(argument("expression", StringArgumentType.greedyString())
                                    .executes(TupenterModClient::runCalcCommand))
                            .then(literal("int")
                                    .then(argument("expression", StringArgumentType.greedyString())
                                            .executes(context -> runCalcCommand(context, "int(" + StringArgumentType.getString(context, "expression") + ")"))))
                            .then(literal("float")
                                    .then(argument("expression", StringArgumentType.greedyString())
                                            .executes(context -> runCalcCommand(context, "float(" + StringArgumentType.getString(context, "expression") + ")")))));

                    dispatcher.register(literal("tupenter")
                            .then(literal("abort")
                                    .executes(TupenterModClient::runAbortAllCommand)
                                    .then(argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                            .executes(TupenterModClient::runAbortOneCommand))
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests(TupenterModClient::suggestScriptNames)
                                            .executes(TupenterModClient::runAbortByName)))
                            .then(literal("running")
                                    .executes(TupenterModClient::runRunningCommand)
                                    .then(literal("hud").executes(TupenterModClient::runRunningHudToggle)))
                            .then(literal("scripts")
                                    .executes(TupenterModClient::runScriptsStatusCommand)
                                    .then(literal("enable")
                                            .executes(context -> runScriptsMasterCommand(context, true)) // no name = master ON (all)
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests(TupenterModClient::suggestScriptNames)
                                                    .executes(context -> runSetArmedByName(context, true))))
                                    .then(literal("disable")
                                            .executes(context -> runScriptsMasterCommand(context, false)) // no name = master OFF (all)
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests(TupenterModClient::suggestScriptNames)
                                                    .executes(context -> runSetArmedByName(context, false)))))
                            .then(literal("vars")
                                    .executes(context -> runVarsCommand(context, null))
                                    .then(argument("group", StringArgumentType.word())
                                            .suggests((context, suggestionsBuilder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(variableGroups(), suggestionsBuilder))
                                            .executes(context -> runVarsCommand(context, StringArgumentType.getString(context, "group")))))
                            .then(literal("dump")
                                    .executes(context -> runDumpCommand(context, "client", ""))
                                    .then(argument("target", StringArgumentType.word())
                                            .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"client", "target"}, b))
                                            .executes(context -> runDumpCommand(context, StringArgumentType.getString(context, "target"), ""))
                                            .then(argument("path", StringArgumentType.greedyString())
                                                    .executes(context -> runDumpCommand(context, StringArgumentType.getString(context, "target"), StringArgumentType.getString(context, "path"))))))
                            .then(literal("var")
                                    .then(literal("save")
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(SESSION_VARIABLES.names(), builder))
                                                    .executes(TupenterModClient::runVarSaveCommand)))
                                    .then(literal("delete")
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(PERSISTENT_VARIABLES.names(), builder))
                                                    .executes(TupenterModClient::runVarDeleteCommand))))
                            .then(literal("help")
                                    .executes(context -> runHelpCommand(context, "index"))
                                    .then(literal("expressions")
                                            .executes(context -> runHelpCommand(context, "expressions"))
                                            .then(argument("topic", StringArgumentType.word())
                                                    .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                            new String[]{"math", "text", "logic", "random", "lists", "world"}, b))
                                                    .executes(context -> runExpressionsHelp(context, StringArgumentType.getString(context, "topic")))))
                                    .then(literal("variables").executes(context -> runHelpCommand(context, "variables")))
                                    .then(literal("flow").executes(context -> runHelpCommand(context, "flow")))
                                    .then(literal("prefixes").executes(context -> runHelpCommand(context, "prefixes")))
                                    .then(literal("scripts").executes(context -> runHelpCommand(context, "scripts")))
                                    .then(literal("commands").executes(context -> runCommandHelp(context, "all")))
                                    .then(literal("command")
                                            .executes(context -> runCommandHelp(context, "all"))
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                            new String[]{"all", "tupenter", "customcommand", "echo", "echohud", "calc", "unroll"}, b))
                                                    .executes(context -> runCommandHelp(context, StringArgumentType.getString(context, "name")))))));

                    dispatcher.register(literal("echo")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(TupenterModClient::runEchoCommand)));

                    dispatcher.register(literal("echohud")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(TupenterModClient::runEchoHudCommand)));

                    dispatcher.register(literal("unroll")
                            .then(argument("line", StringArgumentType.greedyString())
                                    .executes(TupenterModClient::runUnrollCommand)));

                    dispatcher.register(literal("customcommand")
                            .then(literal("add")
                                    .then(argument("name", StringArgumentType.word())
                                            .executes(context -> runAliasPrefillCommand(context, true))
                                            .then(argument("command", StringArgumentType.greedyString())
                                                    .executes(TupenterModClient::runAliasAddCommand))))
                            .then(literal("update")
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                    CommandAliasManager.getAliasMap().keySet(), b))
                                            .executes(context -> runAliasPrefillCommand(context, false))
                                            .then(argument("command", StringArgumentType.greedyString())
                                                    .executes(TupenterModClient::runAliasUpdateCommand))))
                            .then(literal("remove")
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                    CommandAliasManager.getAliasMap().keySet(), b))
                                            .executes(TupenterModClient::runAliasRemoveCommand)))
                            .then(literal("list")
                                    .executes(context -> runAliasListCommand(context, false))
                                    .then(literal("verbose")
                                            .executes(context -> runAliasListCommand(context, true))))
                            .then(literal("help")
                                    .executes(TupenterModClient::runCustomCommandHelp))
                            .then(argument("name", StringArgumentType.word())
                                    .suggests((context, suggestionsBuilder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                            CommandAliasManager.getAliasMap().keySet(), suggestionsBuilder))
                                    .executes(TupenterModClient::runAliasDetailCommand)));

                    dispatcher.register(literal("customfunction")
                            .then(literal("add")
                                    .then(argument("name", StringArgumentType.word())
                                            .executes(context -> runFunctionPrefillCommand(context, true))
                                            .then(argument("body", StringArgumentType.greedyString())
                                                    .executes(context -> runFunctionSaveCommand(context, true)))))
                            .then(literal("update")
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests(TupenterModClient::suggestFunctionNames)
                                            .executes(context -> runFunctionPrefillCommand(context, false))
                                            .then(argument("body", StringArgumentType.greedyString())
                                                    .executes(context -> runFunctionSaveCommand(context, false)))))
                            .then(literal("remove")
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests(TupenterModClient::suggestFunctionNames)
                                            .executes(TupenterModClient::runFunctionRemoveCommand)))
                            .then(literal("list")
                                    .executes(TupenterModClient::runFunctionListCommand))
                            .then(literal("help")
                                    .executes(TupenterModClient::runCustomFunctionHelp)));

                    for (Map.Entry<String, AliasDefinition> alias : CommandAliasManager.getAliasMap().entrySet()) {
                        dispatcher.register(ClientCommandRegistrar.buildAliasNode(alias.getKey(), alias.getValue(), registryAccess));
                    }
                });

        // Register Session Reset
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (TupenterConfig.INSTANCE.resetOnNewSession) {
                messageHistory.clear();
                pendingQueue.clear();
                delayTimer = 0;
                SESSION_VARIABLES.clear();
            }
            SCRIPT_EXECUTOR.abortAll();
            TICK_SCRIPTS.reset();

            if (TupenterConfig.INSTANCE.tickScriptsMigrationNoticePending) {
                TupenterConfig.INSTANCE.tickScriptsMigrationNoticePending = false;
                TupenterConfig.save();
                sendEnhancedParsingInfo("Tupenter: tick scripts are now armed per world — your scripts moved to the "
                        + "Global section (all off everywhere, the safe default). Flip them on for this world in "
                        + "Mod Menu → Tupenter → Scripts, or check /tupenter scripts.");
            }
        });

		// Load Config
		TupenterConfig.load();
		PERSISTENT_VARIABLES.load(TupenterConfig.INSTANCE.persistentVariables);

		HudRenderCallback.EVENT.register(TupenterModClient::renderRunningHud);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Drain any scripts still holding statements (budget-stretched ones).
            // Feed the WORLD game-time so gametime #waits track /tick sprint,
            // /tick freeze, and world pause rather than client render ticks.
            SCRIPT_EXECUTOR.tick(client.level != null ? client.level.getGameTime() : 0L);

            // Mod Menu "Scripts" list — runs every tick while enabled
            TICK_SCRIPTS.tick(SCRIPT_EXECUTOR);

            // Snapshot key states AFTER scripts polled them this tick, so
            // client.keypress.* sees "down now vs. down last tick" and an edge
            // fires exactly once per press.
            KEY_STATES.tick();

            // =========================================================
            // 0. GLOBAL INPUT HANDLING (Always runs)
            // =========================================================
            
            // Handle Config
            while (configKey.consumeClick()) {
                client.setScreen(ModMenuIntegration.createScreen(client.screen));
            }
            
            // Handle History Recording Toggle
            while (recordHistoryKey.consumeClick()) {
                TupenterConfig.INSTANCE.recordHistory = !TupenterConfig.INSTANCE.recordHistory;
                TupenterConfig.save();
                
                if (TupenterConfig.INSTANCE.recordHistory) {
                    messageHistory.clear();
                }
                
                Component status = TupenterConfig.INSTANCE.recordHistory
                    ? Component.translatable("tupenter.recording.on").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("tupenter.recording.off").withStyle(ChatFormatting.RED);
                 
                Component msg = Component.translatable("tupenter.recording.prefix")
                    .withStyle(ChatFormatting.WHITE)
                    .append(status);
                    
                client.player.displayClientMessage(msg, true);
            }

            // =========================================================
            // 1. STATE MANAGEMENT (Toggling)
            // =========================================================
            
            // Logic to calculate batch (needed for locking on toggle)
            // We lazily calculate this only if we trigger a toggle ON event to save perf?
            // Actually, we need it if we are engaging.
            // Let's keep logic cleaner: Toggle State Logic first.

            if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                 while (resendKey.consumeClick()) {
                    isToggledOn = !isToggledOn;
                    
                    if (isToggledOn) {
                        // Toggled ON: Lock current batch snapshot if pauseTracking is enabled
                        if (!TupenterConfig.INSTANCE.updateInToggle) {
                            List<String> currentCandidates = new ArrayList<>();
                            int depth = Math.max(1, TupenterConfig.INSTANCE.historyDepth);
                            int collected = 0;
                            for (int i = messageHistory.size() - 1; i >= 0 && collected < depth; i--) {
                                String candidate = messageHistory.get(i);
                                boolean isCmd = candidate.startsWith("/");
                                boolean allowed = switch (TupenterConfig.INSTANCE.resendFilter) {
                                    case CHAT_ONLY -> !isCmd;
                                    case COMMANDS_ONLY -> isCmd;
                                    default -> true;
                                };
                                if (allowed) {
                                    currentCandidates.add(candidate);
                                    collected++;
                                }
                            }
                            if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.OLDEST_FIRST) {
                                Collections.reverse(currentCandidates);
                            }
                            lockedBatch = new ArrayList<>(currentCandidates);
                        }
                    }

                    // Notification
                    Component status = isToggledOn 
                        ? Component.translatable("tupenter.toggle.on").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("tupenter.toggle.off").withStyle(ChatFormatting.RED);
                    
                    Component msg = Component.translatable("tupenter.toggle.prefix")
                        .withStyle(ChatFormatting.WHITE)
                        .append(status);
                    client.player.displayClientMessage(msg, true);
                 }
            } else if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                 // For Hold mode, we rely on isDown() later
                 isToggledOn = false;
                 // Consume stray clicks to prevent buffer buildup
                 while (resendKey.consumeClick()) { }
            } else {
                 isToggledOn = false;
                 while (resendKey.consumeClick()) { }
            }

            // Update Mixin State
            // Note: For toggle, isFiring is roughly isToggledOn (or isDown for hold)
            boolean wasFiring = isFiring;

            if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                isFiring = resendKey.isDown();
            } else {
                isFiring = isToggledOn;
            }
            
            // Check for Stop and optional Abort
            if (wasFiring && !isFiring) {
                if (TupenterConfig.INSTANCE.batchMode == TupenterConfig.BatchMode.INTERRUPT) {
                    pendingQueue.clear();
                    delayTimer = 0;
                }
            }


            // =========================================================
            // 2. DELAY MANAGEMENT
            // =========================================================
            // Grace Period
            if (client.screen instanceof ChatScreen) {
                lastChatCloseTime = 0;
            } else if (lastChatCloseTime == 0 && client.screen == null) {
                lastChatCloseTime = System.currentTimeMillis();
            }
            boolean gracePeriodActive = (System.currentTimeMillis() - lastChatCloseTime) < 500L;

            if (gracePeriodActive && !isToggledOn && TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                 // Block firing during grace period
                 keyHoldTicks = 0;
                 return;
            }
            
            // Active Delay Timer
            if (delayTimer > 0) {
                delayTimer--;
                return; // Busy waiting, but Input was handled above!
            }


            // =========================================================
            // 3. TRIGGER / BATCH POPULATION
            // =========================================================
            // Only populate if queue is empty
            
            if (pendingQueue.isEmpty()) {
                boolean shouldTrigger = false;
                
                if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                    shouldTrigger = isToggledOn;
                } else if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                    shouldTrigger = resendKey.isDown();
                }

                if (shouldTrigger) {
                    keyHoldTicks++;

                    boolean fireNow = false;
                    if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                        fireNow = true;
                    } else {
                        // Hold/Permanent Mode Pacing
                        if (keyHoldTicks == 1) {
                            fireNow = true;
                        } else if (keyHoldTicks > TupenterConfig.INSTANCE.rapidResendDelay) {
                            fireNow = true;
                        }
                    }

                    if (fireNow) {
                        List<String> batch = new ArrayList<>();

                        if (TupenterConfig.INSTANCE.resendFilter == TupenterConfig.ResendFilter.PERMANENT_MESSAGES) {
                            List<String> permBatch = new ArrayList<>();
                            for (String msg : TupenterConfig.INSTANCE.permanentMessages) {
                                if (msg != null && !msg.trim().isEmpty()) permBatch.add(msg);
                            }
                            if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.NEWEST_FIRST) {
                                Collections.reverse(permBatch);
                            }
                            batch.addAll(permBatch);
                        } else {
                             // Determine source
                             List<String> source = new ArrayList<>();
                             if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE && !TupenterConfig.INSTANCE.updateInToggle && !lockedBatch.isEmpty()) {
                                 source = lockedBatch;
                             } else {
                                  // Fresh calculation (copied logic from above for consistency/fallback)
                                  // --- REPLICATED SEARCH LOGIC START ---
                                     int depth = Math.max(1, TupenterConfig.INSTANCE.historyDepth);
                                     int collected = 0;
                                     for (int i = messageHistory.size() - 1; i >= 0 && collected < depth; i--) {
                                          String candidate = messageHistory.get(i);
                                          boolean isCommand = candidate.startsWith("/");
                                          boolean allowed = true;
                                          switch (TupenterConfig.INSTANCE.resendFilter) {
                                             case CHAT_ONLY: if (isCommand) allowed = false; break;
                                             case COMMANDS_ONLY: if (!isCommand) allowed = false; break;
                                             case BOTH: default: allowed = true; break;
                                          }
                                          if (allowed) {
                                              source.add(candidate);
                                              collected++;
                                          }
                                     }
                                     if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.OLDEST_FIRST) {
                                         Collections.reverse(source);
                                     }
                                  // --- REPLICATED SEARCH LOGIC END ---
                             }
                             batch.addAll(source);
                        }

                        if (!batch.isEmpty()) {
                            int count = Math.max(1, TupenterConfig.INSTANCE.resendAmount);
                            for (int i=0; i<count; i++) {
                                pendingQueue.addAll(batch);
                            }
                        }
                    }
                } else {
                    keyHoldTicks = 0;
                }
            }


            // =========================================================
            // 4. PROCESS QUEUE
            // =========================================================
            
            if (!pendingQueue.isEmpty()) {
                // If we are not firing, we only continue if the mode is FINISH_BATCH
                // (PAUSE does nothing here, effectively pausing naturally. INTERRUPT cleared queue above.)
                if (!isFiring && TupenterConfig.INSTANCE.batchMode != TupenterConfig.BatchMode.FINISH_BATCH) {
                     return;
                }

                while (!pendingQueue.isEmpty()) {
                    String msg = pendingQueue.poll();
                    if (msg.startsWith("/")) {
                        dispatchStoredCommand(client, msg.substring(1));
                    } else {
                         client.player.connection.sendChat(msg);
                    }

                    if (!pendingQueue.isEmpty()) {
                        if (TupenterConfig.INSTANCE.messageDelay > 0) {
                            delayTimer = TupenterConfig.INSTANCE.messageDelay;
                            return; 
                        }
                    } else {
                        // Batch Finished
                        delayTimer = TupenterConfig.INSTANCE.resendDelay;
                        if (TupenterConfig.INSTANCE.messageDelay > 0) {
                             delayTimer = Math.max(TupenterConfig.INSTANCE.messageDelay, TupenterConfig.INSTANCE.resendDelay);
                        }
                        return;
                    }
                }
            }
        });
	}

    private static int runCalcCommand(CommandContext<FabricClientCommandSource> context) {
        String input = StringArgumentType.getString(context, "expression").trim();
        return runCalcCommand(context, input);
    }

    private static int runCalcCommand(CommandContext<FabricClientCommandSource> context, String expression) {
        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled) {
            context.getSource().sendError(Component.literal("Enhanced command parsing is disabled."));
            return 0;
        }

        return evaluateLocalCalcExpression(unwrapOptionalMarkers(expression.trim()), context.getSource()::sendFeedback, context.getSource()::sendError);
    }

    /**
     * A body with unbalanced parens or an unclosed marker is usually a
     * definition the CHAT INPUT BOX'S typing cap truncated mid-paste — warn
     * right at save time instead of erroring mid-run later. That cap is the
     * Chat Input Length setting (vanilla 256 by default, raisable to 32766);
     * it's a UI limit on the edit box, NOT a send limit — these commands are
     * client-only and never reach the server.
     */
    private static void warnUnbalancedDefinition(CommandContext<FabricClientCommandSource> context, String command) {
        int depth = 0;
        boolean marker = false;
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                marker = !marker;
            } else if (!marker) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                }
            }
        }
        if (depth == 0 && !marker) {
            return;
        }
        String what = depth != 0
                ? Math.abs(depth) + " unclosed " + (depth > 0 ? "'('" : "')'")
                : "an unclosed $...$ marker";
        int limit = TupenterConfig.INSTANCE.chatInputLength; // the edit box's typing cap
        String hint = command.length() >= limit - 26
                ? " The chat box only accepts " + limit + " typed characters, so this was likely cut off mid-paste"
                        + " (it's a UI limit, not a send limit — these never go to the server). Raise it in Mod Menu"
                        + " → Tupenter → Chat Input Length, or edit long commands in Custom Commands (no cap there)."
                : "";
        context.getSource().sendFeedback(Component.literal(
                "⚠ Saved, but the body has " + what + " — it will error when run." + hint)
                .withStyle(ChatFormatting.YELLOW));
    }

    private static int runAliasAddCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");

        String normalized = CommandAliasManager.normalizeName(name);
        if (CommandAliasManager.hasAlias(normalized)) {
            context.getSource().sendFeedback(Component.literal("/" + normalized + " already exists. ")
                    .withStyle(ChatFormatting.RED)
                    .append(suggestLink("[update it instead]", "/customcommand update " + normalized + " " + command)));
            return 0;
        }

        return saveAlias(context, name, command, true);
    }

    private static int runAliasUpdateCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");

        String normalized = CommandAliasManager.normalizeName(name);
        if (!CommandAliasManager.hasAlias(normalized)) {
            context.getSource().sendFeedback(Component.literal("/" + normalized + " doesn't exist. ")
                    .withStyle(ChatFormatting.RED)
                    .append(suggestLink("[add it instead]", "/customcommand add " + normalized + " " + command)));
            return 0;
        }

        return saveAlias(context, name, command, false);
    }

    /** Shared tail of add/update: persist, (re-)register the Brigadier tree, report. */
    private static int saveAlias(CommandContext<FabricClientCommandSource> context, String name, String command, boolean isNew) {
        try {
            String savedName = isNew ? CommandAliasManager.addAlias(name, command) : CommandAliasManager.updateAlias(name, command);
            AliasDefinition definition = CommandAliasManager.getAliasMap().get(savedName);
            if (definition != null) {
                ClientCommandRegistrar.registerDynamic(savedName, definition);
            }
            context.getSource().sendFeedback(Component.literal(
                    (isNew ? "Saved custom command /" : "Updated custom command /") + savedName + " — available now.").withStyle(ChatFormatting.GREEN));
            warnUnknownNamespacedVariables(context, command);
            warnUnbalancedDefinition(context, command);
            return 1;
        } catch (IllegalArgumentException ex) {
            context.getSource().sendError(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    /**
     * /unroll <line> — dry-run debugger: parses and unrolls the line exactly
     * like running it (statement forms, aliases, loops, markers), prints the
     * resulting statements color-coded, and sends nothing. #set values are
     * NOT committed to the session.
     */
    private static int runUnrollCommand(CommandContext<FabricClientCommandSource> context) {
        String line = StringArgumentType.getString(context, "line").trim();
        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled) {
            context.getSource().sendError(Component.literal("Enhanced command parsing is disabled."));
            return 0;
        }

        // dry run — eager walk, #set writes stay out of the session
        ScriptParser.Options options = parserOptions().withSessionVariables(null).withLazyExecution(false);

        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(line, line, options);
        if (result.error() != null) {
            context.getSource().sendError(Component.literal("Unroll stopped: " + result.error()));
            return 0;
        }

        java.util.List<Script.SendStatement> statements = result.script().statements();
        String historyNote = switch (result.script().history()) {
            case SKIP -> " · history: skipped";
            case FORCE -> " · history: forced";
            default -> "";
        };
        context.getSource().sendFeedback(Component.literal(
                "Unrolls to " + statements.size() + " statement" + (statements.size() == 1 ? "" : "s")
                + historyNote + " — nothing sent:").withStyle(ChatFormatting.AQUA));
        result.notices().forEach(notice ->
                context.getSource().sendFeedback(Component.literal(" · " + notice).withStyle(ChatFormatting.YELLOW)));

        int shown = 0;
        for (Script.SendStatement statement : statements) {
            if (shown >= 30) {
                context.getSource().sendFeedback(Component.literal(
                        " … and " + (statements.size() - shown) + " more").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            shown++;
            ChatFormatting color = switch (statement.kind()) {
                case COMMAND -> ChatFormatting.AQUA;
                case CHAT -> ChatFormatting.YELLOW;
                case WAIT -> ChatFormatting.LIGHT_PURPLE;
                case NOTICE -> ChatFormatting.GRAY;
            };
            String label = switch (statement.kind()) {
                case COMMAND -> "/" + statement.content();
                case CHAT -> "chat: " + statement.content();
                case WAIT -> "wait " + statement.waitTicks() + "t (later statements evaluate then — this dry run baked them now)";
                case NOTICE -> "note: " + statement.content();
            };
            net.minecraft.network.chat.MutableComponent entry = Component.literal(" " + shown + ". ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(label).withStyle(color));
            if (statement.silent()) {
                entry.append(Component.literal("  (silent)").withStyle(ChatFormatting.DARK_GRAY));
            }
            context.getSource().sendFeedback(entry);
        }
        return 1;
    }

    /** /customcommand add|update <name> with no body: offer the existing definition for editing. */
    private static int runAliasPrefillCommand(CommandContext<FabricClientCommandSource> context, boolean fromAdd) {
        String normalized = CommandAliasManager.normalizeName(StringArgumentType.getString(context, "name"));
        String raw = CommandAliasManager.getRawCommand(normalized);
        if (raw == null) {
            if (fromAdd) {
                context.getSource().sendError(Component.literal("/customcommand add needs a body: /customcommand add " + normalized + " <body> — see /customcommand help"));
            } else {
                context.getSource().sendError(Component.literal("/" + normalized + " doesn't exist — create it with /customcommand add " + normalized + " <body>"));
            }
            return 0;
        }

        String suggested = "/customcommand update " + normalized + " " + raw;
        net.minecraft.network.chat.MutableComponent message = fromAdd
                ? Component.literal("/" + normalized + " already exists. ").withStyle(ChatFormatting.RED)
                        .append(suggestLink("[edit it]", suggested))
                : Component.literal("Edit /" + normalized + ": ").withStyle(ChatFormatting.YELLOW)
                        .append(suggestLink("[put it in your chat bar]", suggested));
        if (suggested.length() > TupenterConfig.INSTANCE.chatInputLength) {
            message.append(Component.literal(" (over " + TupenterConfig.INSTANCE.chatInputLength
                    + " chars — the chat bar will cut it off; raise Chat Input Length or edit in Mod Menu)")
                    .withStyle(ChatFormatting.GRAY));
        }
        context.getSource().sendFeedback(message);
        return 1;
    }

    /** Clickable chat link that puts the given command into the chat bar. */
    private static Component suggestLink(String label, String suggestedCommand) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand(suggestedCommand))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Click to put this in your chat bar"))));
    }

    /**
     * Save-time sanity check: namespaced variables (client.*, world.*,
     * target.*) either exist now or never will, so a typo like
     * $world.difficolty$ can be flagged immediately — unlike plain names,
     * which may legitimately be #set later.
     */
    private static void warnUnknownNamespacedVariables(CommandContext<FabricClientCommandSource> context, String body) {
        java.util.Set<String> known = knownDottedVariableNames();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(client|world|target)\\.[A-Za-z0-9_.]+")
                .matcher(body);
        java.util.Set<String> flagged = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String candidate = matcher.group().toLowerCase(java.util.Locale.ROOT);
            if (candidate.startsWith("client.nbt.") || candidate.startsWith("target.nbt.")) {
                continue; // dynamic paths — checked at run time
            }
            if (!known.contains(candidate)) {
                flagged.add(candidate);
            }
        }
        if (!flagged.isEmpty()) {
            context.getSource().sendFeedback(Component.literal(
                    "Heads up: unknown variable" + (flagged.size() == 1 ? "" : "s") + " " +
                    String.join(", ", flagged.stream().map(v -> "$" + v + "$").toList()) +
                    " — the command will error when run. See /tupenter vars.").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static int runAliasRemoveCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        if (CommandAliasManager.removeAlias(name)) {
            ClientCommandRegistrar.unregisterDynamic(CommandAliasManager.normalizeName(name));
            context.getSource().sendFeedback(Component.literal("Removed custom command /" + CommandAliasManager.normalizeName(name)).withStyle(ChatFormatting.GREEN));
            return 1;
        }

        context.getSource().sendError(Component.literal("Custom command not found: /" + CommandAliasManager.normalizeName(name)));
        return 0;
    }

    private static int runFunctionSaveCommand(CommandContext<FabricClientCommandSource> context, boolean add) {
        String name = StringArgumentType.getString(context, "name");
        String body = StringArgumentType.getString(context, "body");
        try {
            String saved = add ? CustomFunctionManager.addFunction(name, body)
                    : CustomFunctionManager.updateFunction(name, body);
            context.getSource().sendFeedback(Component.literal((add ? "Added" : "Updated") + " function "
                    + saved + "() — use it in an expression: $" + saved + "()$").withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (IllegalArgumentException ex) {
            context.getSource().sendError(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    /** /customfunction add|update <name> with no body: offer the existing definition for editing. */
    private static int runFunctionPrefillCommand(CommandContext<FabricClientCommandSource> context, boolean fromAdd) {
        String normalized = CommandAliasManager.normalizeName(StringArgumentType.getString(context, "name"));
        String stored = CustomFunctionManager.getStoredDefinition(normalized);
        if (stored == null) {
            if (fromAdd) {
                context.getSource().sendError(Component.literal("/customfunction add needs a body: /customfunction add "
                        + normalized + " <params...> = <expression> — see /customfunction help"));
            } else {
                context.getSource().sendError(Component.literal(normalized + "() doesn't exist — create it with /customfunction add "
                        + normalized + " ... — see /customfunction help"));
            }
            return 0;
        }

        String suggested = "/customfunction update " + stored;
        net.minecraft.network.chat.MutableComponent message = fromAdd
                ? Component.literal(normalized + "() already exists. ").withStyle(ChatFormatting.RED)
                        .append(suggestLink("[edit it]", suggested))
                : Component.literal("Edit " + normalized + "(): ").withStyle(ChatFormatting.YELLOW)
                        .append(suggestLink("[put it in your chat bar]", suggested));
        if (suggested.length() > TupenterConfig.INSTANCE.chatInputLength) {
            message.append(Component.literal(" (over " + TupenterConfig.INSTANCE.chatInputLength
                    + " chars — the chat bar will cut it off; raise Chat Input Length or edit in Mod Menu)")
                    .withStyle(ChatFormatting.GRAY));
        }
        context.getSource().sendFeedback(message);
        return 1;
    }

    private static int runFunctionRemoveCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        if (CustomFunctionManager.removeFunction(name)) {
            context.getSource().sendFeedback(Component.literal("Removed function " + CommandAliasManager.normalizeName(name) + "()").withStyle(ChatFormatting.GREEN));
            return 1;
        }
        context.getSource().sendError(Component.literal("No function named " + CommandAliasManager.normalizeName(name) + " — /customfunction list"));
        return 0;
    }

    private static int runFunctionListCommand(CommandContext<FabricClientCommandSource> context) {
        java.util.Map<String, AliasDefinition> functions = CustomFunctionManager.getFunctionMap();
        if (functions.isEmpty()) {
            context.getSource().sendFeedback(Component.literal(
                    "No custom functions. Add one: /customfunction add lightlevel client.light — then use $lightlevel()$").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        context.getSource().sendFeedback(Component.literal("Custom functions (" + functions.size() + ") — call as $name(...)$ in expressions:").withStyle(ChatFormatting.AQUA));
        for (java.util.Map.Entry<String, AliasDefinition> entry : functions.entrySet()) {
            String decls = entry.getValue().declarationPrefix().trim();
            String sig = entry.getKey() + (decls.isEmpty() ? "()" : " " + decls);
            net.minecraft.network.chat.MutableComponent row = Component.literal(
                    " • §f" + sig + "§r §8= " + previewLine(entry.getValue().body())).withStyle(ChatFormatting.GRAY);
            String stored = CustomFunctionManager.getStoredDefinition(entry.getKey());
            if (stored != null) {
                row.append(" ").append(suggestLink("[edit]", "/customfunction update " + stored));
            }
            context.getSource().sendFeedback(row);
        }
        return 1;
    }

    /** /customfunction help — the full guide, including how to pass coordinates. */
    private static int runCustomFunctionHelp(CommandContext<FabricClientCommandSource> context) {
        String[] lines = {
                "§bCustom functions — your own min()/sqrt()-style functions for $...$ expressions:",
                "§7Create:§r /customfunction add <name> <params...> = <expression>  ·  Edit:§r update  ·  Remove:§r remove <name>  ·  List:§r list",
                "§7The body returns a value§r — no commands or chat (that's /customcommand). Simplest is one EXPRESSION: /customfunction add dist <a:vec3> <b:vec3> = sqrt((a.x-b.x)^2 + (a.y-b.y)^2 + (a.z-b.z)^2)",
                "§7It can also be a STATEMENT body§r for real algorithms: #set (function-local), #for/#foreach/#while, #if, and #return. The value is your #return, or the last expression if you don't. Loops are pure compute (no #wait, no commands), capped by Max Loop Iterations.",
                "§7Raytrace example:§r /customfunction add rayhit <p:vec3> <d:vec3> <n:int> = #set $x$=p.x && #set $y$=p.y && #set $z$=p.z && #for $i$ in 1..n (#if (block(x,y,z) != \"minecraft:air\") (#return vec(x,y,z)) && #set $x$=x+d.x && #set $y$=y+d.y && #set $z$=z+d.z) && #return \"miss\"  —  then $rayhit(client.pos, \"0 -1 0\", 30)$",
                "§7Note:§r a body is a statement block only if it uses one of those directives up top; otherwise it's a single expression, so a boolean like <s> x>=0 && x<=100 stays logical-AND. In a statement body, a trailing logical && must be parenthesized. #while already gives you $i$ as its counter — don't #set your own.",
                "§7Call it with parens inside any expression§r, alongside min or sqrt: /echo $dist(client.pos, \"0 64 0\")$ — args are full expressions themselves, and tab-complete works.",
                "§7Passing coordinates:§r \"0 64 0\" (or \"0,64,0\") is a LITERAL — everything inside quotes stays as-is, $ included. To COMPUTE components use vec(x, y, z): each slot is its own expression — $dist(vec(client.x/2, 39+12, 1), \"0 0 0\")$. A vec3 variable like client.pos passes straight through, no quotes needed.",
                "§7Reading a vec back apart:§r x(v)/y(v)/z(v) pull one component out of ANY vec3 — a variable, a vec(...), a raycast, a function result: x(client.pos) · y(raycast(500)) · z(client.look). Exact precision, so the math stays sharp. On a miss (\"miss\") it errors — gate with == \"miss\" first.",
                "§7Param types§r are the custom-command ones: <a:vec3>/<a:pos> bind $a$ plus a.x/a.y/a.z · <n:int>/<n:float> numbers · bare <s> a word or \"quoted text\". Inside the body params are just variables: a.x - b.x.",
                "§7add/update with a name but no body§r puts the existing definition in your chat bar for editing (so does [edit] in list).",
                "§7Functions can call functions§r — including yours — with recursion capped at depth 32.",
        };
        for (String line : lines) {
            context.getSource().sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestFunctionNames(
            CommandContext<FabricClientCommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return net.minecraft.commands.SharedSuggestionProvider.suggest(CustomFunctionManager.getFunctionMap().keySet(), builder);
    }

    /** Group names for /tupenter vars <group> suggestions: built-in + user-made. */
    private static java.util.Set<String> variableGroups() {
        java.util.Set<String> groups = new java.util.TreeSet<>();
        for (String name : knownDottedVariableNames()) {
            groups.add(name.substring(0, name.indexOf('.')));
        }
        for (String name : SESSION_VARIABLES.names()) {
            if (name.contains(".")) groups.add(name.substring(0, name.indexOf('.')));
        }
        for (String name : PERSISTENT_VARIABLES.names()) {
            if (name.contains(".")) groups.add(name.substring(0, name.indexOf('.')));
        }
        return groups;
    }

    private static int runVarsCommand(CommandContext<FabricClientCommandSource> context, String group) {
        if (group != null) {
            String prefix = group.toLowerCase(java.util.Locale.ROOT) + ".";
            java.util.Set<String> names = new java.util.TreeSet<>();
            knownDottedVariableNames().stream().filter(n -> n.startsWith(prefix)).forEach(names::add);
            SESSION_VARIABLES.names().stream().filter(n -> n.startsWith(prefix)).forEach(names::add);
            PERSISTENT_VARIABLES.names().stream().filter(n -> n.startsWith(prefix)).forEach(names::add);

            if (names.isEmpty()) {
                context.getSource().sendError(Component.literal("No variables in group '" + group + "'. Groups: " + String.join(", ", variableGroups())));
                return 0;
            }
            context.getSource().sendFeedback(Component.literal("$" + group + ".*$ variables:").withStyle(ChatFormatting.AQUA));
            for (String name : names) {
                String value;
                try {
                    value = VARIABLE_REGISTRY.resolve(name).map(Value::displayString).orElse("—");
                } catch (IllegalArgumentException ex) {
                    value = "—";
                }
                context.getSource().sendFeedback(Component.literal(" $" + name + "$ = " + value));
            }
            return 1;
        }

        Map<String, Value> vars = SESSION_VARIABLES.snapshot();
        if (vars.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("No session variables set. Use #set $name$ = value.").withStyle(ChatFormatting.YELLOW));
        } else {
            context.getSource().sendFeedback(Component.literal("Session variables:").withStyle(ChatFormatting.AQUA));
            vars.forEach((name, value) ->
                    context.getSource().sendFeedback(Component.literal(" $" + name + "$ = " + value.displayString())));
        }
        Map<String, Value> persistent = PERSISTENT_VARIABLES.snapshot();
        if (!persistent.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("Persistent variables:").withStyle(ChatFormatting.AQUA));
            persistent.forEach((name, value) ->
                    context.getSource().sendFeedback(Component.literal(" $" + name + "$ = " + value.displayString())));
        }

        java.util.Map<String, Integer> groupCounts = new java.util.TreeMap<>();
        for (String name : knownDottedVariableNames()) {
            groupCounts.merge(name.substring(0, name.indexOf('.')), 1, Integer::sum);
        }
        StringBuilder summary = new StringBuilder("Built-in groups: ");
        groupCounts.forEach((g, count) -> summary.append("$").append(g).append(".*$ (").append(count).append(") · "));
        summary.append("$client.nbt.*$ / $target.nbt.*$ (browse: /tupenter dump)");
        context.getSource().sendFeedback(Component.literal(summary.toString()).withStyle(ChatFormatting.GRAY));
        context.getSource().sendFeedback(Component.literal("Details: /tupenter vars <group>").withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int runVarSaveCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase(java.util.Locale.ROOT);
        // session value first, but fall back to the already-saved one so re-saving
        // an unchanged persistent var (the #setdefault + var save idempotent pattern,
        // session 2+) works instead of erroring.
        var value = SESSION_VARIABLES.resolve(name);
        if (value.isEmpty()) {
            value = PERSISTENT_VARIABLES.resolve(name);
        }
        if (value.isEmpty()) {
            context.getSource().sendError(Component.literal("No session or saved variable $" + name + "$ — set it first with #set $" + name + "$ = ..."));
            return 0;
        }
        var existing = PERSISTENT_VARIABLES.resolve(name);
        boolean unchanged = existing.isPresent()
                && existing.get().displayString().equals(value.get().displayString());
        if (!unchanged) {
            try {
                PERSISTENT_VARIABLES.set(name, value.get());
            } catch (IllegalArgumentException ex) {
                context.getSource().sendError(Component.literal(ex.getMessage()));
                return 0;
            }
            savePersistentVariables(); // only touch disk when the value actually changed
        }
        context.getSource().sendFeedback(Component.literal((unchanged ? "$" + name + "$ already saved = " : "Saved $" + name + "$ = ")
                + value.get().displayString() + " (persists across sessions)").withStyle(unchanged ? ChatFormatting.GRAY : ChatFormatting.GREEN));
        return 1;
    }

    private static int runVarDeleteCommand(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase(java.util.Locale.ROOT);
        if (!PERSISTENT_VARIABLES.remove(name)) {
            context.getSource().sendError(Component.literal("No persistent variable $" + name + "$"));
            return 0;
        }
        savePersistentVariables();
        context.getSource().sendFeedback(Component.literal("Deleted persistent variable $" + name + "$").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** /tupenter help expressions <topic> — deep pages, divided by what you're trying to do. */
    private static int runExpressionsHelp(CommandContext<FabricClientCommandSource> context, String topic) {
        String[] lines = switch (topic.toLowerCase(java.util.Locale.ROOT)) {
            case "math" -> new String[]{
                    "§bExpressions · math:",
                    "§7Arithmetic:§r + - * / % and implicit multiplication: $2(3+4)$ = 14 · % is floored modulo (like scoreboard %=): $-1 % 3$ = 2",
                    "§7Power:§r ^ raises to a power: $3^2$ = 9 · $2^-1$ = 0.5 · binds tighter than * and than unary - ($-2^2$ = -4) · exact for whole exponents. Great for squares: sqrt((a.x-b.x)^2 + (a.y-b.y)^2 + (a.z-b.z)^2)",
                    "§7Exact fractions:§r $1/3 * 3$ is exactly 1 — no float drift, ever",
                    "§7Stacks:§r a number with an s suffix is stacks of 64: $3s$ = 192 · $1.5s$ = 96",
                    "§7Rounding:§r int(x) truncates · floor / ceil / round · abs, min(a,b,...), max(a,b,...), sqrt",
                    "§7Trig:§r sin/cos/tan take DEGREES (Minecraft rotations are degrees): $sin(client.yaw)$",
                    "§7Implicit math:§r with Inline Expressions = Auto-detect, bare math evaluates WITHOUT markers: /give @s stick 64*5 → 320. Numbers-and-operators only, skipped inside {NBT braces}, unparseable text sends as-is (never errors).",
                    "§7Gotchas:§r write 2*sin(x), not 2sin(x) — a bare s reads as the stack suffix",
            };
            case "text" -> new String[]{
                    "§bExpressions · text:",
                    "§7Strings:§r \"quoted\" — quotes say what it is: \"air\" is text, air is a variable lookup",
                    "§7Joining:§r + concatenates: $\"lvl \" + client.xp_level$ → lvl 30",
                    "§7Comparing:§r == and != work on text: $client.gamemode == \"creative\"$",
                    "§7Functions:§r trim(s) · upper(s) / lower(s) · substr(s, start[, count]) — 0-based, clamped · replace(s, find, new) — all occurrences · len(s) — length",
                    "§7Strings that RUN:§r a chat statement that is exactly one $marker$ runs its string result by statement form — #set $cmd$ = \"/tp ~ ~1 ~\" then $cmd$ teleports. Same for /$ expr $ on a whole line.",
                    "§7Escapes:§r \\\" inside quotes · \\$ for a literal dollar sign",
            };
            case "logic" -> new String[]{
                    "§bExpressions · logic:",
                    "§7Booleans:§r true / false · comparisons: == != < <= > >=",
                    "§7Combine:§r && (and) · \\|\\| (or) · ! (not): $client.on_ground && client.light < 8$",
                    "§7Ternary:§r condition ? then : else — $client.health < 5 ? \"help!\" : \"fine\"$ · nests freely",
                    "§7#if / #while use these:§r #if (client.y > 60) (/say high) #elseif (...) (...) #else (...) · #while (client.health < 20) (/effect give @s regeneration 1 1 && #wait 3s)",
                    "§7Gotcha:§r a bare boolean can't be substituted into a command — route it through a ternary to pick real text",
            };
            case "random" -> new String[]{
                    "§bExpressions · random:",
                    "§7rand(min, max)§r — whole number, INCLUSIVE both ends: $rand(1, 64)$",
                    "§7randf(min, max)§r — decimal in [min, max)",
                    "§7rand(list)§r — one element of any list: $rand(effectset())$, $rand(range(0, 100, 10))$",
                    "§7pick(a | b | c)§r — one OPTION, where options are full expressions and nest: pick(rand(1,5) | client.y). Quote literal text: pick(\"say hi\" | \"say nah\") — a single | separates options, || is still boolean-or.",
                    "§7pick vs rand:§r pick chooses between things YOU wrote; rand samples a range or list",
                    "§7Re-rolls:§r resend history keeps the original line, so every resend rolls fresh",
            };
            case "lists" -> new String[]{
                    "§bExpressions · lists:",
                    "§7range(start, stop[, step])§r — inclusive whole numbers: range(1, 10), range(10, 0, -2)",
                    "§7len(x)§r — list length (or text length) · §7nth(list, i)§r — element i, 0-based · §7indexof(list, v)§r — position of v (or -1) · §7contains(list, v)§r — membership test",
                    "§7Cycling:§r nth(list, $i$ % len(list)) walks a list forever — with a #set counter, one step per run: #set $i$ = $i$ + 1 && /setblock ~ ~-1 ~ $nth(blockset(\"#minecraft:wool\"), i % 16)$",
                    "§7Registry sets:§r blockset(#minecraft:logs) / itemset(#c:ores) / effectset(#...) / entityset(#minecraft:skeletons) = a TAG's members as a list — no quotes needed, and typing # inside the parens TAB-COMPLETES the tags. A CONCRETE id makes a one-element set: blockset(\"stone\") — so block-or-blockset params feed the same functions. NO argument = the whole registry. Needs a live world.",
                    "§7Build your own set:§r pass SEVERAL members — tags and/or concrete ids — and they union (dedup, first-seen order): blockset(\"oak_planks\", \"oak_log\", \"#minecraft:wool\") · entityset(\"minecraft:skeleton\", \"minecraft:zombie\"). One space-separated STRING is also a whole set: blockset(\"oak_log stone #minecraft:wool\") — so a single <set:blockset> param can carry many via /sphere ... $\"oak_log stone #minecraft:wool\"$. Feed it to rand/#foreach/contains like a tag set.",
                    "§7Use them:§r rand(list) picks one: /effect give @s $rand(effectset())$ 30 1 · #foreach loops: #foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)",
                    "§7Item tags exist too:§r #minecraft:planks, #minecraft:logs, #c:ores, ... — type itemset(# and browse",
                    "§7Also a list:§r $client.effects$ — your active effect ids",
            };
            case "world" -> new String[]{
                    "§bExpressions · world reads:",
                    "§7block(x, y, z)§r or block(\"x y z\") — the block id at a position: $block(0, 64, 0)$ → minecraft:stone",
                    "§7No round trip:§r reads come from YOUR client's synced world copy, so #if handles them instantly — this is /execute if block folded into the expression world, with a real #else.",
                    "§7Crosshair:§r $client.target_hit$ = \"block\"/\"entity\"/\"miss\" · $client.target_block$ = \"x y z\" (errors on a miss — gate with target_hit) · $client.target_entity$ = the entity id",
                    "§7raycast(dist)§r or raycast(origin, dir, dist) — casts like your crosshair (hits collidable blocks): returns \"x y z\" or \"miss\". raycast_block(dist) returns the block id, or \"miss\". $client.eye_pos$ = your eye vec3 (a ray origin), $client.look$ = your unit look vec3 (a ray dir) — so raycast(client.eye_pos, client.look, 60) is the long-hand of raycast(60).",
                    "§7Pattern:§r #if (client.target_hit == \"block\" && block(client.target_block) == \"minecraft:diamond_ore\") (/echo &bfound it) — a condition is already an expression, so $ $ around names is optional",
                    "§7Limits:§r loaded chunks only (unloaded = loud error, never a guess) · states/NBT not included, just the id",
                    "§7Tick state:§r $world.tickrate$ (from /tick rate, ~20) · $world.frozen$ (is /tick freeze on) · $world.stepping$ (mid /tick step) — exact, synced from the server. So a freeze toggle is just: #if $world.frozen$ (/tick unfreeze) #else (/tick freeze)",
                    "§7Registry sets§r (blockset/itemset/effectset) are under: /tupenter help expressions lists",
            };
            default -> new String[]{
                    "§cUnknown expressions topic '" + topic + "' — try: math, text, logic, random, lists, world",
            };
        };
        for (String line : lines) {
            context.getSource().sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static int runHelpCommand(CommandContext<FabricClientCommandSource> context, String topic) {
        String[] lines = switch (topic) {
            case "expressions" -> new String[]{
                    "§bExpressions — $...$ evaluates before sending:",
                    "§7The rule:§r inside $...$ you're writing CODE, not command text — quotes say what it is (\"air\" = text, air = a variable), position says what it becomes.",
                    "§7Works everywhere:§r commands, chat, directives, custom command bodies, tick scripts. A bad $...$ shows a local error and sends NOTHING. \\$ = literal dollar.",
                    "§7Try it:§r /calc <expr> evaluates locally · /$ expr $ is top-down: numbers display, a string result RUNS as a fresh line",
                    "§7Go deeper — /tupenter help expressions <topic>:",
                    "§7  math§r — arithmetic, exact fractions, stack suffix, rounding, trig",
                    "§7  text§r — strings, joining, comparisons, strings that run",
                    "§7  logic§r — booleans, conditions, ternary, #if",
                    "§7  random§r — rand, randf, pick, and re-roll rules",
                    "§7  lists§r — range, len, registry sets, #foreach",
                    "§7  world§r — block(x,y,z) and reading your client's world copy",
            };
            case "variables" -> new String[]{
                    "§bVariables — use anywhere as $name$:",
                    "§7Yours:§r #set x = 5 (session, cleared on join) · #set x += 1 (also -= *= /= %=) · #local x = 5 (this line only, silent) · $ around the name is optional · dotted groups allowed: #set hitlist.bob = \"wanted\"",
                    "§7On the right side§r you're already in expression world: #set x = x + 1 — bare names work; $x + 1$ works too ($...$ always evaluates its inside)",
                    "§7#setdefault x = 5§r sets x ONLY if it isn't already defined (session, saved, or live) — idempotent init, so a stateful custom command is a clean drop-in: #setdefault $frozen$ = false && #if $frozen$ (/tick unfreeze) #else (/tick freeze) && #set $frozen$ = !$frozen$",
                    "§7Persistent:§r /tupenter var save <name> keeps it forever (re-saving an unchanged value is a no-op) · /tupenter var delete <name> removes it · create-once-across-sessions: #setdefault $x$ = 0 && /tupenter var save $x$",
                    "§7Built-in:§r $client.x/y/z/health/held_item/target_block/target_hit...$ · $world.time/difficulty/raining...$ · $players.count/list$ · $real.hour/day_of_week...$ — target_hit = \"block\"/\"entity\"/\"miss\"; target_block errors on a miss (gate it with target_hit)",
                    "§7Environment:§r $client.biome$ · $client.light / light_block / light_sky$ · $client.facing$ · $client.chunk_x/chunk_z$ · $world.spawn$ · $world.key$ (the per-world scripts id)",
                    "§7Movement:§r $client.speed$ (full 3D b/s) · $client.speed_xz$ (horizontal) · $client.speed_y$ (vertical, signed) · $client.motion$ (vec3 \"vx vy vz\" b/s) · booleans: on_ground, sneaking, sprinting, swimming, flying, gliding",
                    "§7Stats & session:§r max_health, absorption, armor, saturation, xp_level, xp_progress · slot (0-8), offhand_item, target_entity, target_uuid (the crosshair entity's UUID — an entity_nbt selector) · gamemode, ping, fps, uuid",
                    "§7Hazards & held:§r in_water, underwater, in_lava, on_fire, fall_distance, eye_y · riding + vehicle · effects (a LIST — #foreach $e$ in client.effects works) · held_count, offhand_count, held_durability/held_max_durability (error on non-damageable — guard with held_item)",
                    "§7Keys (a script IS a keybind):§r $client.key.<name>$ = held now · $client.keypress.<name>$ = the tick it goes down. <name> is a bind (jump, sneak, attack, hotbar.1 — follows your controls + mods) OR a physical key (g, space, f6). Arrows are up_arrow/down_arrow/left_arrow/right_arrow (bare left/right = the strafe binds). All false while a screen is open. Pair with a tick script: restock = #if (client.keypress.g) (/tp @s $client.target_block$)",
                    "§7Everything else:§r $client.nbt.<any path>$ / $target.nbt.<any path>$ — e.g. $client.nbt.Inventory.0.id$ · browse with /tupenter dump",
                    "§7Any entity by UUID:§r $entity_nbt(uuid, \"path\")$ reads the same NBT for ANY loaded entity, not just self/target — entity_nbt(\"self\"|\"target\"|<uuid>, \"Health\") · e.g. $entity_nbt(client.uuid, \"Pos.1\")$. Client-synced only (~render distance); an out-of-range UUID errors.",
                    "§7Finding UUIDs:§r entity_raycast(dist) = UUID you're aiming at (or \"miss\") · entities(radius[, type]) = LIST of nearby UUIDs for #foreach · nearest_entity(radius[, type]) = closest UUID (or \"miss\") · client.target_uuid = crosshair entity. Chain them: $entity_nbt(entity_raycast(30), \"Health\")$ · #foreach $e$ in entities(8, \"minecraft:zombie\") (...)",
                    "§7Entity type from a UUID:§r entity_type(selector) = the type id (\"minecraft:zombie\") — entity_nbt can't (entities are stored without their id tag). Name what you're aiming at: /echo This is a $entity_type(entity_raycast(100))$",
                    "§7Discover:§r /tupenter vars — groups overview · /tupenter vars <group> — live values",
                    "§7In custom commands:§r declared params bind as $name$ or $1$..$n$",
            };
            case "flow" -> new String[]{
                    "§bChains, loops & conditions:",
                    "§7Chain:§r /time set day && /weather clear — one line, sent in order. Each segment picks its form: /command, #directive, bare text = chat. A segment that is exactly one $expr$ runs its string result the same way ($cmd$ holding \"/tp ~ ~1 ~\" teleports).",
                    "§7Repeat:§r #repeat 5 (/say Tick $i$!) — $i$ counts 1..5",
                    "§7For:§r #for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~) — inclusive, counts down automatically",
                    "§7Foreach:§r #foreach $m$ in (zombie | skeleton) (/summon $m$) — or in range(1, 10)",
                    "§7If:§r #if ($client.y$ > 60) (/say high) #elseif ($client.y$ > 30) (/say mid) #else (/say low)",
                    "§7While:§r #while ($client.health$ < 20) (/effect give @s regeneration 1 1 && #wait 3s) — re-checks each iteration, stops when false. Runs across ticks; $i$ counts iterations.",
                    "§7Wait:§r #wait 10t / 1.5s / 2m / 3d (ticks/seconds/minutes/days) — pause the script mid-line without freezing anything else. Scripts run LAZILY: $...$ evaluates when its statement runs, so /attribute ... && #wait 2t && /tp @s $client.target_block$ reads the target AFTER the boost landed. Works in chains, groups, loops, and custom command bodies. Max 72000t.",
                    "§7Wait clock:§r default is §7gametime§r — WORLD ticks, so it speeds up under /tick sprint, halts under /tick freeze, and pauses when the world is paused (but /time set|add doesn't move it — that's only the day-time). Add §7realtime§r for wall-clock instead: #wait 5m realtime fires after 5 real minutes no matter the TPS.",
                    "§7Overlap:§r re-running a line starts another concurrent instance (up to the concurrency cap) — two /randomfills at different spots both run · they share the per-tick budget round-robin · /tupenter abort <id> stops one, /tupenter abort stops all",
                    "§7Groups (...) nest and can hold chains. Parens elsewhere are literal text.",
                    "§7Loops that DO something run free:§r a loop that sends or #waits each iteration paces itself over ticks (Max Commands Per Tick), so a big /randomfill or a #while poll runs however long it needs — /tupenter running shows it, /tupenter abort stops it. Max Loop Iterations only caps a loop that spins WITHOUT sending or waiting (the runaway guard).",
            };
            case "prefixes" -> new String[]{
                    "§bLine prefixes & local output:",
                    "§7#silent§r — hide command feedback on your screen: whole line (#silent /time set day) or part of it (#silent (/give @s stick) && /say hi). Also mutes #set notices.",
                    "§7#norecord§r — run the line but keep it out of resend history",
                    "§7#record§r — the inverse: records even when message tracking is OFF (and bypasses the filter)",
                    "§7#stage§r — put the line INTO resend history without running it — press R when you want it",
                    "§7#unstage [n]§r — remove the newest n (default 1) entries from resend history; reports what the next resend is",
                    "§7#pid N <line>§r — run <line> under a name you pick (its id in /tupenter running). Refuses if pid N is already live; add §7replace§r to restart it: #pid 3 replace /randomfill …. Stop or list with /tupenter abort N · /tupenter running.",
                    "§7#chat§r — send a normal chat message that evaluates its $...$: #chat my coords are $client.pos$ → posts \"my coords are 100 64 -30\". (Plain chat leaves $...$ literal; this is the opt-in.)",
                    "§7/echo§r — show text only to yourself, sends nothing: /echo y is $client.y$. Colors with &-codes: /echo &aall good &7($client.health$ hp) — \\& for a literal &",
                    "§7Shorthands:§r #s #nr #r #st #ust #c for #silent #norecord #record #stage #unstage #chat",
                    "§7Prefixes combine: #norecord #silent /say hi",
            };
            case "scripts" -> new String[]{
                    "§bTick scripts (Mod Menu → Tupenter → Scripts):",
                    "§7Each armed line runs as its own loop — one body pass per tick (20x/s) — while the master toggle is on. A walking mcfunction file.",
                    "§7Guard them:§r #if ($client.nbt.Health$ < 6) (/give @s totem_of_undying) — unguarded commands flood multiplayer chat.",
                    "§7#wait works inside:§r the loop resumes after it, so #wait paces a script (/effect … && #wait 3s) and $markers$ after a wait re-read live state. #while is allowed too.",
                    "§7Name them:§r start a script with §fname =§r (like a custom command, no params): restock = /clear && #wait 1s. Then toggle from chat: /tupenter scripts enable|disable restock.",
                    "§7Live tuning:§r reference $maxy$ in a script, change it anytime with #set $maxy$ = 80",
                    "§7Arming is PER WORLD:§r Global scripts are shared definitions you arm world-by-world; This World's scripts exist only in the world you're in. A world you never configured runs NOTHING — nukeOnDeath stays off on your survival server.",
                    "§7Status:§r /tupenter scripts — what's armed here · /tupenter running — armed loops + their ids · /tupenter abort <id|name> switches one OFF · /tupenter scripts enable|disable (no name) — master on/off, or with a §fname§r toggle just that one",
                    "§7Errors report once and pause that script until edited (or re-enabled) · tick scripts never touch resend history and never print #set notices.",
                    "§7Panic:§r /tupenter abort — also flips the master toggle off",
            };
            default -> new String[]{
                    "§bTupenter help — pick a topic:",
                    "§7/tupenter help expressions [topic]§r — the $...$ language: math, text, logic, random, lists, world",
                    "§7/tupenter help variables§r — #set, #local, client.*/world.*/nbt paths, groups",
                    "§7/tupenter help flow§r — && chains, #repeat, #for, #foreach, #if/#elseif, #while",
                    "§7/tupenter help prefixes§r — #silent, #norecord, #stage, /echo",
                    "§7/tupenter help scripts§r — the every-tick Scripts tab",
                    "§7/tupenter help command [name]§r — the mod's commands, with per-command detail pages",
                    "§7/customcommand help§r — make your own commands (typed params, autocomplete)",
                    "§7/customfunction help§r — write your own min()-style functions for expressions",
                    "§7Quick taste:§r #set $x$ = rand(1,10) && /give @s stick $x$ && /echo got $x$!",
            };
        };
        for (String line : lines) {
            context.getSource().sendFeedback(Component.literal(line));
        }
        return 1;
    }

    /** /tupenter help command [name] — brief overview, or one command in depth. */
    private static int runCommandHelp(CommandContext<FabricClientCommandSource> context, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        String[] lines = switch (name) {
            case "all", "commands" -> new String[]{
                    "§bCommands Tupenter adds (all client-side) — detail: /tupenter help command <name>:",
                    "§7/tupenter§r — running · abort · scripts · vars · var save/delete · dump · help",
                    "§7/customcommand§r — add · update · remove · list · make your own commands",
                    "§7/customfunction§r — add · update · remove · list · your own $name(...)$ expression functions",
                    "§7/echo <text>§r — local-only output, &-colors, evaluates $...$ · §7/echohud <text>§r — same, on the action bar (auto-fades)",
                    "§7/calc <expr>§r · §7/$ expr $§r — local calculator / top-down shorthand",
                    "§7/unroll <line>§r — dry-run debugger",
                    "§7Keybinds (Options → Controls):§r resend key (default R) · open config · toggle message tracking",
            };
            case "tupenter" -> new String[]{
                    "§b/tupenter — mod control:",
                    "§7running§r — armed tick scripts + running instances, each with an id and a clickable [abort]; §7running hud§r toggles the same list as an on-screen panel that survives chat spam",
                    "§7abort <id>§r — stop one by its id: a running instance is killed; an armed tick script is switched OFF (its Mod Menu toggle). §7abort <name>§r switches off a named tick script. §7abort§r alone stops everything + disables tick scripts (panic switch)",
                    "§7scripts§r — what's armed in THIS world · §7scripts enable|disable§r — master on/off (no name), or arm/disarm one by §fname§r",
                    "§7vars [group]§r — variables overview, or one group with live values",
                    "§7var save <name>§r — make a #set variable persistent · §7var delete <name>§r — remove it",
                    "§7dump [client|target] [path]§r — browse entity NBT (the data behind client.nbt.* / target.nbt.*)",
                    "§7help <topic>§r — topics: expressions [math|text|logic|random|lists|world], variables, flow, prefixes, scripts, command [name]",
            };
            case "customcommand" -> new String[]{
                    "§b/customcommand — make your own commands:",
                    "§7add <name> <body>§r — create · §7update <name> <body>§r — edit · §7remove <name>§r — delete",
                    "§7Description:§r a \"quoted note\" goes right before a §c§lrequired =§r that starts the body: /customcommand add tickfreeze \"toggles time\" = #if ($frozen$) (/tick unfreeze) #else (/tick freeze). §cWithout the =§r, those quotes are just body text (why it stays white). Shows on missing args + in /customcommand <name>; &-colors like /echo.",
                    "§7list [verbose]§r — signatures (verbose: full bodies) · §7/customcommand <name>§r — one command + [edit] link",
                    "§7add/update with a name but no body§r puts the existing definition in your chat bar for editing",
                    "§7Full guide§r (typed params, defaults, examples): /customcommand help",
            };
            case "customfunction" -> new String[]{
                    "§b/customfunction — your own expression functions:",
                    "§7add <name> <params...> = <expr>§r — create · §7update§r — edit · §7remove§r — delete · §7list§r — signatures with [edit] links",
                    "§7Called like built-ins inside $...$:§r $dist(client.pos, \"0 0 0\")$ — bodies are expressions that return a value, not commands",
                    "§7add/update with a name but no body§r puts the existing definition in your chat bar for editing",
                    "§7Full guide§r (param types, passing coordinates, vec() vs quotes): /customfunction help",
            };
            case "echo" -> new String[]{
                    "§b/echo <text> — show text only to yourself (nothing is sent):",
                    "§7$...$§r evaluates first: /echo y is $client.y$",
                    "§7&-codes color the text from that point on, until the next code or &r:",
                    "§7Colors:§r §0&0§1&1§2&2§3&3§4&4§5&5§6&6§7&7§8&8§9&9§a&a§b&b§c&c§d&d§e&e§f&f§r §7(0-9, a-f)",
                    "§7Formats:§r &l §lbold§r§7 · &o §oitalic§r§7 · &n §nunderline§r§7 · &m §mstrike§r§7 · &k obfuscated · &r reset",
                    "§7\\&§r prints a literal & · codes work from variables too: #set $ok$ = \"&aOK\"",
                    "§7Example:§r /echo &ahp $client.health$ &7/ 20",
                    "§7Sibling:§r /echohud — same thing, but on the action bar (above the hotbar): /tupenter help command echohud",
            };
            case "echohud" -> new String[]{
                    "§b/echohud <text> — like /echo, but on the action bar (above the hotbar). Nothing is sent:",
                    "§7Same $...$ evaluation and &-color codes as /echo — only the destination differs.",
                    "§7Fades on its own after a couple seconds. Send it again and it just UPDATES the text in place (no flicker) — send every tick for a live readout.",
                    "§7Live HUD:§r #while (true) (/echohud &7light &f$client.light$ &7· &f$client.speed$&7 b/s && #wait 1t)",
                    "§7Alert:§r #wait 5m realtime && /echohud &ecows are ready to be fed!",
            };
            case "calc" -> new String[]{
                    "§b/calc <expr> — local calculator (nothing is sent):",
                    "§7/calc int <expr>§r / §7/calc float <expr>§r — force integer / decimal display",
                    "§7/$ expr $§r — top-down shorthand: numbers, booleans, and lists display like /calc,",
                    "§7but a STRING result runs as a fresh line: \"/...\" command, \"#...\" directive, else chat.",
                    "§7/$pick(\"hi\" | \"/time set day\")$§r — chats hi, or runs the command. Resending re-rolls.",
                    "§7Expression reference: /tupenter help expressions",
            };
            case "unroll" -> new String[]{
                    "§b/unroll <line> — dry-run debugger (nothing is sent):",
                    "§7Parses and unrolls the line exactly like running it: statement forms, aliases, loops, markers.",
                    "§7Output: §b/commands§7 in aqua, §echat§7 in yellow, plus (silent) tags, #set notices, and the history mode.",
                    "§7#set values are NOT saved by a dry run · display caps at 30 rows.",
                    "§7rand()/pick() roll once per unroll — running the line afterwards re-rolls.",
                    "§7Example: /unroll /blink 200 — see exactly what /blink would send.",
            };
            default -> new String[]{
                    "§cNo command page for '" + rawName + "' — try: all, tupenter, customcommand, echo, echohud, calc, unroll",
            };
        };
        for (String line : lines) {
            context.getSource().sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static int runDumpCommand(CommandContext<FabricClientCommandSource> context, String which, String path) {
        if (!which.equals("client") && !which.equals("target")) {
            context.getSource().sendError(Component.literal("Usage: /tupenter dump [client|target] [path]"));
            return 0;
        }
        try {
            var entity = which.equals("target") ? EntityNbtVariableProvider.targetEntity() : EntityNbtVariableProvider.clientEntity();
            var root = EntityNbtVariableProvider.snapshot(entity);
            String cleanPath = path.trim().replaceAll("^\\.|\\.$", "");
            String fullName = which + ".nbt" + (cleanPath.isEmpty() ? "" : "." + cleanPath);
            var tag = EntityNbtVariableProvider.walk(root, cleanPath, fullName);

            context.getSource().sendFeedback(Component.literal("$" + fullName + "$:").withStyle(ChatFormatting.AQUA));
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                compound.keySet().stream().sorted().forEach(key ->
                        context.getSource().sendFeedback(Component.literal(" " + key + ": ").withStyle(ChatFormatting.YELLOW)
                                .append(Component.literal(summarizeTag(compound.get(key))).withStyle(ChatFormatting.WHITE))));
            } else if (tag instanceof net.minecraft.nbt.CollectionTag list) {
                int shown = Math.min(list.size(), 16);
                for (int i = 0; i < shown; i++) {
                    context.getSource().sendFeedback(Component.literal(" " + i + ": ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(summarizeTag(list.get(i))).withStyle(ChatFormatting.WHITE)));
                }
                if (list.size() > shown) {
                    context.getSource().sendFeedback(Component.literal(" … " + (list.size() - shown) + " more").withStyle(ChatFormatting.GRAY));
                }
            } else {
                context.getSource().sendFeedback(Component.literal(" " + summarizeTag(tag)));
            }
            return 1;
        } catch (IllegalArgumentException ex) {
            context.getSource().sendError(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    private static String summarizeTag(net.minecraft.nbt.Tag tag) {
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
            return "{…} " + compound.size() + " key" + (compound.size() == 1 ? "" : "s");
        }
        if (tag instanceof net.minecraft.nbt.CollectionTag list) {
            return "[…] " + list.size() + " entr" + (list.size() == 1 ? "y" : "ies");
        }
        return tag.toString();
    }

    private static int runAliasListCommand(CommandContext<FabricClientCommandSource> context, boolean verbose) {
        List<String> definitions = CommandAliasManager.getAliasDefinitions();
        if (definitions.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("No custom commands saved. Try /customcommand help").withStyle(ChatFormatting.YELLOW));
            return 1;
        }

        context.getSource().sendFeedback(Component.literal("Saved custom commands:").withStyle(ChatFormatting.AQUA));
        for (String definition : definitions) {
            CommandAliasManager.ParsedAlias parsed = CommandAliasManager.parseDefinition(definition);
            if (parsed == null) {
                context.getSource().sendFeedback(Component.literal(" - " + definition + " (invalid)").withStyle(ChatFormatting.RED));
                continue;
            }
            context.getSource().sendFeedback(aliasLine(parsed, verbose));
        }
        if (!verbose) {
            context.getSource().sendFeedback(Component.literal("Full bodies: /customcommand list verbose · one command: /customcommand <name>").withStyle(ChatFormatting.DARK_GRAY));
        }
        return 1;
    }

    private static Component aliasLine(CommandAliasManager.ParsedAlias parsed, boolean fullBody) {
        String body = parsed.definition().body();
        if (!fullBody && body.length() > 40) {
            body = body.substring(0, 40) + "…";
        }
        return Component.literal(" /").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(parsed.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(parsed.definition().params().isEmpty()
                        ? " " : " " + parsed.definition().declarationPrefix()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("→ ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(body).withStyle(fullBody ? ChatFormatting.WHITE : ChatFormatting.GRAY));
    }

    private static int runAliasDetailCommand(CommandContext<FabricClientCommandSource> context) {
        String name = CommandAliasManager.normalizeName(StringArgumentType.getString(context, "name"));
        AliasDefinition definition = CommandAliasManager.getAliasMap().get(name);
        if (definition == null) {
            context.getSource().sendError(Component.literal("No custom command /" + name + " — see /customcommand list"));
            return 0;
        }
        context.getSource().sendFeedback(Component.literal("/").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(definition.params().isEmpty() ? "" : " " + definition.declarationPrefix().trim()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  "))
                .append(suggestLink("[edit]", "/customcommand update " + name + " " + CommandAliasManager.getRawCommand(name))));
        if (!definition.description().isEmpty()) {
            context.getSource().sendFeedback(Component.literal(" ")
                    .append(Component.literal(applyAmpersandColors(definition.description())).withStyle(ChatFormatting.GRAY)));
        }
        context.getSource().sendFeedback(Component.literal(" body: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(definition.body()).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    /** Evaluate $...$ and translate &-colors for the /echo family; null (after reporting) on a bad expression. */
    private static String prepareLocalOutput(CommandContext<FabricClientCommandSource> context) {
        String text = StringArgumentType.getString(context, "message");
        if (TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                && TupenterConfig.INSTANCE.numberMathMode != net.tupenter.script.NumberMathMode.DISABLED) {
            try {
                text = MathEvaluator.applyNumberMath(text, net.tupenter.script.NumberMathMode.EXPLICIT_ONLY,
                        new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER, BLOCK_READER, CustomFunctionManager.resolver(), RAYCASTER, ENTITY_ACCESS));
            } catch (IllegalArgumentException ex) {
                context.getSource().sendError(Component.literal(ex.getMessage()));
                return null;
            }
        }
        return applyAmpersandColors(text);
    }

    private static int runEchoCommand(CommandContext<FabricClientCommandSource> context) {
        String text = prepareLocalOutput(context);
        if (text == null) {
            return 0;
        }
        context.getSource().sendFeedback(Component.literal(text).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** /echohud — like /echo, but to the action bar (above the hotbar). Fades on its own; repeated sends update in place. */
    private static int runEchoHudCommand(CommandContext<FabricClientCommandSource> context) {
        String text = prepareLocalOutput(context);
        if (text == null) {
            return 0;
        }
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text), false);
        return 1;
    }

    /** Shown when a custom command is run with too few args: its &-colored description + signature. */
    public static int showAliasUsage(String name, net.tupenter.script.AliasDefinition definition, FabricClientCommandSource source) {
        if (!definition.description().isEmpty()) {
            source.sendFeedback(Component.literal(applyAmpersandColors(definition.description())).withStyle(ChatFormatting.GRAY));
        }
        String declarations = definition.declarationPrefix().trim();
        String usage = "/" + name + (declarations.isEmpty() ? "" : " " + declarations);
        source.sendFeedback(Component.literal("usage: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(usage).withStyle(ChatFormatting.YELLOW)));
        return 1;
    }

    /** &a-style color codes for /echo (translated to §); \& is a literal ampersand. */
    private static String applyAmpersandColors(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                out.append('&');
                i++;
            } else if (c == '&' && i + 1 < text.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(i + 1)) >= 0) {
                out.append('§');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int runCustomCommandHelp(CommandContext<FabricClientCommandSource> context) {
        String[] lines = {
                "§bCustom commands:",
                "§7Create:§r /customcommand add <name> <body>  ·  Edit:§r /customcommand update <name> <body>  ·  Remove:§r /customcommand remove <name>  ·  List:§r /customcommand list",
                "§7Bodies§r can hold commands, chat, && chains, $...$ expressions, directives (#repeat, #if, #silent, ...), and other custom commands. Commands need their /: sunny = /weather clear && Have fun!",
                "§7Parameters§r go before the body: /customcommand add smite <target:player> /execute at $target$ run summon lightning_bolt — then /smite Steve. Use as $target$ or $1$.",
                "§7Description:§r a \"quoted note\" right before a §c§lrequired =§r that begins the body. No params: /customcommand add tickfreeze \"toggles time\" = #if ($frozen$) (/tick unfreeze) #else (/tick freeze). With params: /customcommand add greet <who:player> \"wave at someone\" = /me waves at $who$. §cWithout the =§r those quotes are just body text. The note shows on missing args and in /customcommand <name>; &-colors like /echo.",
                "§7Types:§r <name> or <name:string> = a word or \"anything quoted\" · <n:int> / <n:float> = numbers · <n:word> = one plain token (letters/digits/_-.+ only — no selectors!) · <n:selector> = @e[...] with tab-complete · <n:player> = player name · <n:text> = rest of the line (must be last) · <n:opt1,opt2,...> = one of a fixed list, tab-completed",
                "§7Position types:§r <n:pos> = whole x y z with ~ support and targeted-block tab-complete · <n:vec3> = decimal x y z with ~ · <n:column_pos> = whole x z with ~ · <n:rotation> = yaw pitch with ~ · <n:angle> = one yaw with ~. Tuples bind $n$ = the joined coords plus $n.x$ $n.y$ $n.z$ (or $n.yaw$ $n.pitch$); angle binds a number.",
                "§7More types:§r <n:time> = duration (10t / 1.5s / 2m / 3d), binds as ticks · <n:dimension> = dimension id, tab-completed · <n:color> = chat color, tab-completed · <n:id> = any namespaced id · <n:item> / <n:block> = item or block with full registry tab-complete (including [components] / [states]) · <n:itemset> / <n:blockset> = an item/block OR a #tag like #minecraft:logs, tab-completed · <n:entity> = entity type id with /summon-style tab-complete · <n:bool> = true/false, binds a boolean for #if/ternaries (a strictly-typed <g:bool=false> optional is skippable: /launch snowball true)",
                "§7Optional params:§r add =default to make a param optional: <r:int=5>, <p:pos=~ ~ ~>. Defaults may hold $...$ expressions (evaluated when omitted, earlier params visible). Strictly-typed optionals can even be skipped mid-command — /portal to_nether works with <p:pos=~ ~ ~> <dim:...> because to_nether isn't a coordinate. Loose types (string/word/text) always grab the next arg, so put those last.",
                "§7No natural default?§r Use a SENTINEL the body branches on: <filter:blockset=any> then #if ($filter$ == \"any\") (unfiltered...) #else (filtered...) — that's how an omitted param can mean 'do something else' rather than 'use this value'.",
                "§7Selectors:§r use <name:selector>, or quote them into a plain <name>: /cmd \"@e[type=!player,limit=1]\"",
                "§7Example:§r /customcommand add waves <count:int> <mob:word> #repeat $count$ (/summon $mob$ ~ ~ ~)  →  /waves 3 zombie",
        };
        for (String line : lines) {
            context.getSource().sendFeedback(Component.literal(line));
        }
        return 1;
    }

    public static boolean handleLocalCalcAlias(String command) {
        if (!command.startsWith("$")) {
            return false;
        }

        if (!TupenterConfig.INSTANCE.enhancedCommandParsingEnabled) {
            sendLocalCalcError(Component.literal("Enhanced command parsing is disabled."));
            return true;
        }

        String input = command.substring(1).trim();
        if (!input.endsWith("$")) {
            sendLocalCalcError(Component.literal("Invalid local calc syntax. Use /$ ... $"));
            return true;
        }

        String expression = input.substring(0, input.length() - 1).trim();
        net.tupenter.script.Value value;
        try {
            value = MathEvaluator.evaluateValue(expression, new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER, BLOCK_READER, CustomFunctionManager.resolver(), RAYCASTER, ENTITY_ACCESS));
        } catch (IllegalArgumentException ex) {
            sendLocalCalcError(Component.literal("Invalid math expression: " + ex.getMessage()));
            return true;
        }

        // top-down: a string result runs as a fresh statement line; numbers,
        // booleans, and lists display locally like /calc
        if (value instanceof net.tupenter.script.Value.StringValue string) {
            runEvaluatedLine(string.value(), "/" + command);
        } else {
            sendLocalCalcFeedback(Component.literal(value.displayString()).withStyle(ChatFormatting.AQUA));
        }
        return true;
    }

    /**
     * Runs the string a /$...$ line evaluated to, with alias-body statement
     * forms per segment: "/..." is a command (full pipeline, alias expansion
     * included), "#..." a directive, anything else a plain chat message —
     * so "/echo hello && hello" echoes AND chats. The leading / of /$...$
     * only marks the line as script, not chat. History records the ORIGINAL
     * /$...$ line, so resending re-rolls pick().
     */
    private static void runEvaluatedLine(String evaluated, String historyLine) {
        String content = evaluated.trim();
        if (content.isEmpty()) {
            sendLocalCalcError(Component.literal("The expression evaluated to nothing"));
            return;
        }

        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(content, historyLine, parserOptions());
        if (result.error() != null) {
            sendEnhancedParsingError(result.error());
            return;
        }

        Script script = result.script();
        switch (script.history()) {
            case NORMAL -> updateLastMessage(historyLine);
            case FORCE -> forceAddToHistory(historyLine);
            case SKIP -> { }
        }
        result.notices().forEach(TupenterModClient::sendEnhancedParsingInfo);
        submitScript(script);
    }

    private static int evaluateLocalCalcExpression(String expression, java.util.function.Consumer<Component> feedback, java.util.function.Consumer<Component> error) {
        try {
            String result = MathEvaluator.evaluateForDisplay(expression, new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER, BLOCK_READER, CustomFunctionManager.resolver(), RAYCASTER, ENTITY_ACCESS));
            feedback.accept(Component.literal(result).withStyle(ChatFormatting.AQUA));
            return 1;
        } catch (IllegalArgumentException ex) {
            error.accept(Component.literal("Invalid math expression: " + ex.getMessage()));
            return 0;
        }
    }

    private static void sendLocalCalcFeedback(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(component, false);
        }
    }

    private static void sendLocalCalcError(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(component.copy().withStyle(ChatFormatting.RED), false);
        }
    }

    public static void sendEnhancedParsingError(String message) {
        sendLocalCalcError(Component.literal(message));
    }

    private static String unwrapOptionalMarkers(String input) {
        if (input.length() >= 2 && input.startsWith("$") && input.endsWith("$")) {
            return input.substring(1, input.length() - 1);
        }
        return input;
    }
}
