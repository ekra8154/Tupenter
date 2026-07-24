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
import net.tupenter.script.ParamTypeDocs;
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
     * True only while a send the USER drove is in flight: typing a line and
     * pressing Enter (which always routes through ChatScreen.handleChatInput)
     * or a resend. Every command funnels through the same sendCommand/send, so
     * by the time the mixins see it the origin is gone — this is how we get it
     * back. Another mod firing a command straight into sendCommand from a
     * keybind (CommandKeys and the like) passes through neither path, so its
     * sends are still evaluated and sent (a macro's $marker$ still works), but
     * they stay OUT of your resend history. Set at exactly two places:
     * MixinChatScreen's handleChatInput hook, and the resend queue drain below.
     */
    private static boolean userDrivenSend = false;

    public static boolean isUserDrivenSend() {
        return userDrivenSend;
    }

    public static void beginUserDrivenSend() {
        userDrivenSend = true;
    }

    public static void endUserDrivenSend() {
        userDrivenSend = false;
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
        VARIABLE_REGISTRY.register(new net.tupenter.command.EntitySubjectProvider());
        VARIABLE_REGISTRY.register(new net.tupenter.command.SlotVariableProvider());
    }

    private static final TickScriptRunner TICK_SCRIPTS = new TickScriptRunner();

    public static void resetTickScriptFaults() {
        TICK_SCRIPTS.clearFaults();
    }

    /** Namespaces too big to list flat — collapsed to a root until you type into them. */
    private static final java.util.List<String> COLLAPSED_NAMESPACES =
            java.util.List.of("client.key.", "client.keypress.");

    public static java.util.List<String> expressionCompletions() {
        return expressionCompletions("");
    }

    /**
     * Everything worth tab-completing inside a $...$ marker, for what's typed so far.
     *
     * <p>{@code client.key.*}/{@code client.keypress.*} enumerate every bind AND every
     * physical key — 250+ entries that would bury the ~90 other variables. So they
     * collapse to their two roots until the prefix reaches into one, exactly how
     * client.nbt./client.target.nbt. already behave. Resolution and validation are untouched:
     * this only shapes the suggestion list.
     */
    public static java.util.List<String> expressionCompletions(String prefix) {
        String typed = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (String name : VARIABLE_REGISTRY.names()) {
            String collapsedRoot = collapsedRootFor(name);
            if (collapsedRoot != null && !typed.startsWith(collapsedRoot)) {
                names.add(collapsedRoot); // show the door, not every room behind it
                continue;
            }
            names.add(name);
        }
        names.addAll(SESSION_VARIABLES.names());
        names.add("client.nbt.");
        names.add("client.target.");
        names.add("client.vehicle.");
        names.add("client.held.");
        names.add("client.offhand.");
        names.add("client.slot.");
        // ...and once you're inside one, expand it: the live NBT tree one level
        // at a time, or the slot names / that slot's fields
        names.addAll(net.tupenter.command.EntityNbtVariableProvider.pathCompletions(typed));
        names.addAll(net.tupenter.command.SlotVariableProvider.completions(typed));
        names.addAll(net.tupenter.command.EntitySubjectProvider.completions(typed));
        names.addAll(java.util.List.of(
                "rand", "randf", "pick", "range", "len", "nth", "indexof", "int", "float",
                "abs", "floor", "ceil", "round", "min", "max", "sqrt", "sin", "cos", "tan",
                "blockset", "itemset", "effectset", "entityset", "block", "contains", "true", "false",
                "trim", "upper", "lower", "substr", "replace", "vec", "component", "raycast", "raycast_block",
                "entity", "raycast_entity", "entities", "nearest_entity",
                "slot"));
        names.addAll(CustomFunctionManager.getFunctionMap().keySet()); // user functions tab-complete too
        return new java.util.ArrayList<>(names);
    }

    /** The collapsed root a name lives under, or null if it isn't in a collapsed namespace. */
    private static String collapsedRootFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String root : COLLAPSED_NAMESPACES) {
            if (lower.startsWith(root) && lower.length() > root.length()) {
                return root;
            }
        }
        return null;
    }

    /**
     * Like ChatInputStyler.maskMarkers, but each complete $...$ is replaced by
     * a SAME-LENGTH mask whose token count matches the marker's evaluated
     * value — so a position marker like $client.target.blockpos$ (three coords)
     * masks to three numeric tokens and a command's later arguments still
     * parse and complete (/setblock $client.target.blockpos$ mine|). Positions
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
                // eval failed (e.g. target.blockpos off-crosshair while chat is open) —
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
            "client.pos", "client.target.blockpos", "client.motion");

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
     * Backs entity / raycast_entity / entities / nearest_entity off the
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
        // history is YOURS — only what you typed/resent, never another mod's
        // programmatic send (see userDrivenSend)
        if (!userDrivenSend) {
            return;
        }
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
        // only record sends the USER drove — typing/resend set userDrivenSend;
        // a keybind mod firing straight into sendCommand does not, so its
        // commands still run but don't land in your resend history
        if (!userDrivenSend) return;
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
                                                            new String[]{"math", "text", "logic", "random", "lists", "world", "vectors"}, b))
                                                    .executes(context -> runExpressionsHelp(context, StringArgumentType.getString(context, "topic")))))
                                    .then(literal("variables").executes(context -> runHelpCommand(context, "variables")))
                                    .then(literal("flow").executes(context -> runHelpCommand(context, "flow")))
                                    .then(literal("prefixes").executes(context -> runHelpCommand(context, "prefixes")))
                                    .then(literal("scripts").executes(context -> runHelpCommand(context, "scripts")))
                                    // the ONE list page; a single command opens by bare name (/tupenter help echo)
                                    .then(literal("commands").executes(context -> runCommandHelp(context, "all")))
                                    .then(literal("functions").executes(TupenterModClient::runFunctionsIndexCommand))
                                    // any function or custom definition by name: /tupenter help blockset
                                    .then(argument("name", StringArgumentType.word())
                                            .suggests(TupenterModClient::suggestHelpNames)
                                            .executes(context -> runNameHelpCommand(context, StringArgumentType.getString(context, "name"))))));

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
                                    .executes(TupenterModClient::runCustomCommandHelp)
                                    .then(argument("topic", StringArgumentType.word())
                                            .suggests(TupenterModClient::suggestCustomCommandHelpTopics)
                                            .executes(context -> runCustomCommandHelpTopic(context, StringArgumentType.getString(context, "topic")))))
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
            // the fresh server sends its own command tree; any nodes a shadowing
            // alias displaced belonged to the OLD tree and must not be restored
            // into this one
            ClientCommandRegistrar.clearShadowedNodes();

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
                    // a resend is user-driven — it belongs in history like the
                    // original did (see userDrivenSend); scope it tight so the
                    // flag never leaks across the delay-returns below
                    beginUserDrivenSend();
                    try {
                        if (msg.startsWith("/")) {
                            dispatchStoredCommand(client, msg.substring(1));
                        } else {
                            client.player.connection.sendChat(msg);
                        }
                    } finally {
                        endUserDrivenSend();
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
            String shadowWarning = CommandAliasManager.vanillaShadowWarning(savedName);
            if (shadowWarning != null) {
                context.getSource().sendFeedback(Component.literal(shadowWarning).withStyle(ChatFormatting.YELLOW));
            }
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
                context.getSource().sendError(Component.literal("/customcommand add needs a body: /customcommand add " + normalized + " = <body> — see /customcommand help"));
            } else {
                context.getSource().sendError(Component.literal("/" + normalized + " doesn't exist — create it with /customcommand add " + normalized + " = <body>"));
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
            if (candidate.startsWith("client.nbt.") || candidate.startsWith("client.target.nbt.")) {
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
        beginHelpPage();
        if (functions.isEmpty()) {
            helpLine(Component.literal(
                    "No custom functions. Add one: /customfunction add lightlevel = client.light — then use $lightlevel()$").withStyle(ChatFormatting.GRAY));
            endHelpPage();
            return 1;
        }
        helpLine(Component.literal("Custom functions (" + functions.size() + ") — call as $name(...)$ in expressions; click one for its page:").withStyle(ChatFormatting.AQUA));
        for (java.util.Map.Entry<String, AliasDefinition> entry : functions.entrySet()) {
            String decls = entry.getValue().declarationPrefix().trim();
            String sig = entry.getKey() + (decls.isEmpty() ? "()" : " " + decls);
            String page = "/tupenter help " + entry.getKey();
            // the whole row navigates; the [edit] child keeps its own click event
            net.minecraft.network.chat.MutableComponent row = Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(sig).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" = " + previewLine(entry.getValue().body())).withStyle(ChatFormatting.DARK_GRAY))
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand(page))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(page))));
            String stored = CustomFunctionManager.getStoredDefinition(entry.getKey());
            if (stored != null) {
                row.append(" ").append(suggestLink("[edit]", "/customfunction update " + stored));
            }
            helpLine(row);
        }
        endHelpPage();
        return 1;
    }

    /** /customfunction help — the guide, restyled as a navigable page with runnable examples. */
    private static int runCustomFunctionHelp(CommandContext<FabricClientCommandSource> context) {
        String midpointExample = "/customfunction add midpoint <a:vec3> <b:vec3> = scale(vadd(a, b), 0.5)";
        String rayhitExample = "/customfunction add rayhit <p:vec3> <d:vec3> <n:int> = #set $x$=p.x && #set $y$=p.y && #set $z$=p.z && "
                + "#for $i$ in 1..n (#if (block(x,y,z) != \"minecraft:air\") (#return vec(x,y,z)) && "
                + "#set $x$=x+d.x && #set $y$=y+d.y && #set $z$=z+d.z) && #return \"miss\"";
        beginHelpPage();
        helpLine("§bCustom functions — your own min()/sqrt()-style functions for $...$ expressions:");
        helpLine("§7Create:§r /customfunction add <name> <params...> = <expression> · §7edit:§r update · §7remove§r · §7list§r");
        helpLine("§7The body returns a value§r — no commands or chat (that's /customcommand). Simplest is one expression, and it can build on the built-ins:");
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(midpointExample, midpointExample))
                .append(Component.literal(" — then /echo $midpoint(client.pos, world.spawn)$").withStyle(ChatFormatting.DARK_GRAY)));
        helpLine("§7Statement bodies§r do real algorithms: #set (function-local), #for/#foreach/#while, #if, #return — the value is your #return, or the last expression. Pure compute: no #wait, no commands; loops capped by Max Loop Iterations.");
        helpLine(Component.literal("Then: ").withStyle(ChatFormatting.GRAY)
                .append(suggestLink("a raytracer in one definition (click to load)", rayhitExample))
                .append(Component.literal(" — then $rayhit(client.pos, \"0 -1 0\", 30)$").withStyle(ChatFormatting.DARK_GRAY)));
        helpLine("§7Gotchas:§r a body is a statement block only if it STARTS with one of those directives — otherwise x>=0 && x<=100 stays logical-AND · a trailing logical && in a statement body needs parens · #while already provides $i$, don't #set your own.");
        helpLine("§7Passing coordinates:§r \"0 64 0\" is a LITERAL (quotes keep everything, $ included); to COMPUTE components use vec(x, y, z) — each slot its own expression. A vec3 variable like client.pos passes straight through, no quotes.");
        helpLine(navRow("param types", "the custom-command vocabulary — <a:pos> binds $a$ plus a.x/a.y/a.z", "/customcommand help types"));
        helpLine(navRow("component(v, axis)", "read a computed vec3 back apart", "/tupenter help component"));
        helpLine("§7Editing:§r add/update with a name and NO body puts the definition in your chat bar (so does [edit] in list) · your functions get pages too: /tupenter help <name>.");
        helpLine("§7Functions can call functions§r — including themselves — recursion capped at depth 32.");
        helpLine(runLink("« all built-in functions", ChatFormatting.DARK_GRAY, "/tupenter help functions"));
        endHelpPage();
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

    /**
     * The live INSPECTOR — current values, where the help pages hold meaning.
     * The two cross-link: every row here clicks through to its help page, and
     * subject help pages link back to their live group. Vars pages are pager
     * pages too, so inspecting doesn't pile up in chat.
     */
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
            beginHelpPage();
            helpLine(Component.literal("$" + group + ".*$ right now — click a name for its meaning:").withStyle(ChatFormatting.AQUA));
            for (String name : names) {
                String value;
                try {
                    value = VARIABLE_REGISTRY.resolve(name).map(Value::displayString).orElse("—");
                } catch (IllegalArgumentException ex) {
                    value = "—";
                }
                String page = "/tupenter help " + name;
                helpLine(Component.literal(" $" + name + "$").withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(" = " + value).withStyle(ChatFormatting.GRAY))
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent.RunCommand(page))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal(page)))));
            }
            endHelpPage();
            return 1;
        }

        beginHelpPage();
        Map<String, Value> vars = SESSION_VARIABLES.snapshot();
        if (vars.isEmpty()) {
            helpLine(Component.literal("No session variables set. Use #set $name$ = value.").withStyle(ChatFormatting.YELLOW));
        } else {
            helpLine(Component.literal("Session variables:").withStyle(ChatFormatting.AQUA));
            vars.forEach((name, value) ->
                    helpLine(Component.literal(" $" + name + "$ = " + value.displayString())));
        }
        Map<String, Value> persistent = PERSISTENT_VARIABLES.snapshot();
        if (!persistent.isEmpty()) {
            helpLine(Component.literal("Persistent variables:").withStyle(ChatFormatting.AQUA));
            persistent.forEach((name, value) ->
                    helpLine(Component.literal(" $" + name + "$ = " + value.displayString())));
        }

        java.util.Map<String, Integer> groupCounts = new java.util.TreeMap<>();
        for (String name : knownDottedVariableNames()) {
            groupCounts.merge(name.substring(0, name.indexOf('.')), 1, Integer::sum);
        }
        helpLine(Component.literal("Built-in groups — click one for live values:").withStyle(ChatFormatting.AQUA));
        groupCounts.forEach((g, count) ->
                helpLine(navRow("$" + g + ".*$", count + " variables", "/tupenter vars " + g)));
        helpLine("§7Dynamic trees:§r $client.nbt.*$ / $client.target.nbt.*$ — browse with /tupenter dump");
        helpLine(runLink("what they mean: /tupenter help variables", ChatFormatting.DARK_GRAY, "/tupenter help variables"));
        endHelpPage();
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

    /**
     * The mod's own commands, which answer to a BARE name like everything else
     * (/tupenter help echo) — there's no "help command &lt;name&gt;" qualifier any
     * more, just the /tupenter help commands list for discovery. Resolved ahead
     * of your custom commands, so a same-named alias of yours loses here and is
     * still reachable via /customcommand &lt;name&gt;.
     */
    private static final java.util.List<String> MOD_COMMAND_PAGES = CommandAliasManager.MOD_COMMANDS;

    /**
     * Autocomplete for /tupenter help &lt;name&gt;. Two rules keep it from burying
     * the front door: (1) it stays SILENT on an empty prefix — the ~9 topic
     * literals (expressions, variables, functions, ...) carry the bare
     * /tupenter help&#32; and this catch-all only speaks once you start typing;
     * (2) it offers the 12 SUBJECT roots, not the ~75 individual dotted
     * variables — you browse to client.health from the clickable client page,
     * and typing the full name still resolves. So the flat vocabulary here is
     * functions + directives + subjects + your own definitions, matched by
     * whatever you've typed.
     */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestHelpNames(
            CommandContext<FabricClientCommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (builder.getRemaining().isEmpty()) {
            return builder.buildFuture(); // let the topic literals stand alone
        }
        return net.minecraft.commands.SharedSuggestionProvider.suggest(helpNameSuggestions(), builder);
    }

    /** Everything /tupenter help &lt;name&gt; can resolve by NAME (variables collapsed to subject roots). */
    private static java.util.List<String> helpNameSuggestions() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (net.tupenter.script.BuiltinFunctions.Doc doc : net.tupenter.script.BuiltinFunctions.ALL) {
            names.add(doc.name());
        }
        for (net.tupenter.script.DirectiveDocs.Doc doc : net.tupenter.script.DirectiveDocs.ALL) {
            names.add(doc.name());
        }
        for (net.tupenter.script.SubjectDocs.Subject subject : net.tupenter.script.SubjectDocs.ALL) {
            names.add(subject.name()); // the subject page is where individual vars are browsed
        }
        names.addAll(MOD_COMMAND_PAGES); // the mod's own commands answer to bare names too
        names.addAll(CustomFunctionManager.getFunctionMap().keySet());
        names.addAll(CommandAliasManager.getAliasMap().keySet());
        return names;
    }

    /**
     * Help renders as a PAGE, not a log append: the exact components of the last
     * page are remembered, and the next page deletes them from chat history
     * before printing — clicking through the help index NAVIGATES instead of
     * piling pages up. Only lines printed through helpLine are ever touched;
     * ordinary chat stays exactly where it is.
     */
    private static final List<Component> HELP_PAGE_LINES = new ArrayList<>();

    private static void beginHelpPage() {
        net.minecraft.client.gui.components.ChatComponent chat = Minecraft.getInstance().gui.getChat();
        if (!HELP_PAGE_LINES.isEmpty()) {
            // identity, not equals — we delete the exact component instances we added
            java.util.Set<Component> previous = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            previous.addAll(HELP_PAGE_LINES);
            net.tupenter.mixin.client.ChatComponentAccessor accessor = (net.tupenter.mixin.client.ChatComponentAccessor) chat;
            if (accessor.tupenter$allMessages().removeIf(message -> previous.contains(message.content()))) {
                accessor.tupenter$refreshTrimmedMessages();
            }
            HELP_PAGE_LINES.clear();
        }
    }

    private static void helpLine(Component line) {
        HELP_PAGE_LINES.add(line);
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    private static void helpLine(String line) {
        helpLine(Component.literal(line));
    }

    /**
     * Close the page: if anything on it is clickable, say so — nothing marks a
     * clickable line visually, so the page teaches its own interactivity. Then
     * snap chat to the bottom: a page you navigated to should be LOOKED AT,
     * not appended out of view.
     */
    private static void endHelpPage() {
        boolean clickable = false;
        for (Component line : HELP_PAGE_LINES) {
            if (hasClick(line)) {
                clickable = true;
                break;
            }
        }
        if (clickable) {
            helpLine("§8§oHover a line to see what clicking it does.");
        }
        Minecraft.getInstance().gui.getChat().resetChatScroll();
    }

    private static boolean hasClick(Component component) {
        if (component.getStyle().getClickEvent() != null) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (hasClick(sibling)) {
                return true;
            }
        }
        return false;
    }

    /** A whole page of plain §-styled lines, replacing the previous page; backLink (nullable) navigates up a level. */
    private static int sendHelpPage(String[] lines, Component backLink) {
        beginHelpPage();
        for (String line : lines) {
            helpLine(line);
        }
        if (backLink != null) {
            helpLine(backLink);
        }
        endHelpPage();
        return 1;
    }

    /** Clickable chat line that RUNS a (read-only, navigation-only) command; hover shows the command. */
    private static Component runLink(String label, ChatFormatting color, String command) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /**
     * An index row: white head, gray description, the WHOLE line clickable
     * through to {@code command}. Built from styled components, not §-codes —
     * legacy codes reset the style mid-line and punch holes in the click region.
     */
    private static Component navRow(String head, String desc, String command) {
        return Component.literal(" " + head).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(" — " + desc).withStyle(ChatFormatting.GRAY))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /**
     * /tupenter help functions — the index. One blurb line per built-in, grouped,
     * every line clickable through to its detail page. Rendered straight from the
     * BuiltinFunctions registry, so it cannot advertise a function that doesn't
     * exist (or miss one that does). Built from styled components, not §-codes —
     * legacy codes reset the style mid-line and punch holes in the click region.
     */
    private static int runFunctionsIndexCommand(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bFunctions — click one (or /tupenter help <name>) for details and examples:");
        net.tupenter.script.BuiltinFunctions.Group group = null;
        for (net.tupenter.script.BuiltinFunctions.Doc doc : net.tupenter.script.BuiltinFunctions.ALL) {
            if (doc.group() != group) {
                group = doc.group();
                helpLine(Component.literal(group.label() + ":").withStyle(ChatFormatting.DARK_AQUA));
            }
            helpLine(navRow(doc.signature(), doc.blurb(), "/tupenter help " + doc.name()));
        }
        helpLine("§8Yours too: /customfunction list · /customcommand list — /tupenter help <name> opens those as well.");
        endHelpPage();
        return 1;
    }

    /**
     * /tupenter help <name> — one page for any single thing: a built-in function
     * (from the registry), one of the mod's commands, or one of YOUR definitions
     * (where the stored definition is the documentation).
     */
    private static int runNameHelpCommand(CommandContext<FabricClientCommandSource> context, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        net.tupenter.script.BuiltinFunctions.Doc doc = net.tupenter.script.BuiltinFunctions.find(name);
        if (doc != null) {
            return renderFunctionPage(context, doc);
        }
        if (MOD_COMMAND_PAGES.contains(name)) {
            return runCommandHelp(context, name);
        }
        net.tupenter.script.DirectiveDocs.Doc directive = net.tupenter.script.DirectiveDocs.find(name);
        if (directive != null) {
            return renderDirectivePage(context, directive);
        }
        net.tupenter.script.SubjectDocs.Subject subjectDoc = net.tupenter.script.SubjectDocs.find(name);
        if (subjectDoc != null) {
            return renderSubjectPage(context, subjectDoc);
        }
        String varBlurb = VARIABLE_REGISTRY.describe(name);
        if (varBlurb != null) {
            return renderVariableMiniPage(context, name, varBlurb);
        }
        // a dotted name inside a dynamic namespace lands on its subject's page
        net.tupenter.script.SubjectDocs.Subject bySegment = net.tupenter.script.SubjectDocs.findByPrefix(name);
        if (bySegment != null) {
            return renderSubjectPage(context, bySegment);
        }
        if (CustomFunctionManager.hasFunction(name)) {
            return renderCustomFunctionPage(context, name);
        }
        if (CommandAliasManager.getAliasMap().containsKey(CommandAliasManager.normalizeName(name))) {
            return runAliasDetailCommand(context); // reads the same "name" argument
        }
        String near = net.tupenter.script.BuiltinFunctions.nearest(name);
        context.getSource().sendError(Component.literal("No help page for '" + rawName + "'"
                + (near != null ? " — did you mean '" + near + "'?" : "")
                + " · every function: /tupenter help functions · every topic: /tupenter help"));
        return 0;
    }

    /** One built-in function in depth: blurb, the details, and two clickable examples. */
    private static int renderFunctionPage(CommandContext<FabricClientCommandSource> context, net.tupenter.script.BuiltinFunctions.Doc doc) {
        beginHelpPage();
        helpLine("§b" + doc.signature() + "§r §7— " + doc.blurb());
        for (String line : doc.detail()) {
            helpLine(line);
        }
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY)
                .append(suggestLink(doc.exampleSimple(), doc.exampleSimple())));
        helpLine(Component.literal("Then: ").withStyle(ChatFormatting.GRAY)
                .append(suggestLink(doc.exampleComposed(), doc.exampleComposed())));
        helpLine(runLink("« all functions", ChatFormatting.DARK_GRAY, "/tupenter help functions"));
        endHelpPage();
        return 1;
    }

    /** /tupenter help <your function> — auto-generated: the definition IS the documentation. */
    private static int renderCustomFunctionPage(CommandContext<FabricClientCommandSource> context, String rawName) {
        String name = CommandAliasManager.normalizeName(rawName);
        AliasDefinition definition = CustomFunctionManager.getFunctionMap().get(name);
        if (definition == null) {
            context.getSource().sendError(Component.literal("No custom function '" + name + "' — see /customfunction list"));
            return 0;
        }
        beginHelpPage();
        String decls = definition.declarationPrefix().trim();
        helpLine("§b" + name + (decls.isEmpty() ? "()" : " " + decls)
                + "§r §7— your custom function (call it in any $...$)");
        helpLine(" §8= §f" + definition.body());
        String stored = CustomFunctionManager.getStoredDefinition(name);
        if (stored != null) {
            helpLine(Component.literal(" ").append(suggestLink("[edit]", "/customfunction update " + stored)));
        }
        endHelpPage();
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
                    "§7#if / #while use these:§r #if (client.pos.y > 60) (/say high) #elseif (...) (...) #else (...) · #while (client.health < 20) (/effect give @s regeneration 1 1 && #wait 3s)",
                    "§7Gotcha:§r a bare boolean can't be substituted into a command — route it through a ternary to pick real text",
            };
            case "random" -> new String[]{
                    "§bExpressions · random:",
                    "§7rand(min, max)§r — whole number, INCLUSIVE both ends: $rand(1, 64)$",
                    "§7randf(min, max)§r — decimal in [min, max)",
                    "§7rand(list)§r — one element of any list: $rand(effectset())$, $rand(range(0, 100, 10))$",
                    "§7pick(a | b | c)§r — one OPTION you wrote, chosen at random; options are full expressions and nest — /tupenter help pick",
                    "§7pick vs rand:§r pick chooses between things YOU wrote; rand samples a range or list",
                    "§7Re-rolls:§r resend history keeps the original line, so every resend rolls fresh",
            };
            case "lists" -> new String[]{
                    "§bExpressions · lists:",
                    "§7range(start, stop[, step])§r — inclusive whole numbers: range(1, 10), range(10, 0, -2)",
                    "§7len(x)§r — list length (or text length) · §7nth(list, i)§r — element i, 0-based · §7indexof(list, v)§r — position of v (or -1) · §7contains(list, v)§r — membership test",
                    "§7Cycling:§r nth(list, $i$ % len(list)) walks a list forever — with a #set counter, one step per run: #set $i$ = $i$ + 1 && /setblock ~ ~-1 ~ $nth(blockset(\"#minecraft:wool\"), i % 16)$",
                    "§7Registry sets:§r blockset / itemset / effectset / entityset — a TAG's members as a list (typing # inside the parens tab-completes). Tags and concrete ids mix and UNION; no argument = the whole registry — /tupenter help blockset",
                    "§7Pass a set to a custom command:§r wrap it in $...$ — /sphere ~ ~ ~ 5 $blockset(\"stone\", #wool)$. A list ARGUMENT flattens to one token and the set functions read it back — details on the set pages.",
                    "§7Use them:§r rand(list) picks one: /effect give @s $rand(effectset())$ 30 1 · #foreach loops: #foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)",
                    "§7Also a list:§r $client.effects$ — your active effect ids",
            };
            case "world" -> new String[]{
                    "§bExpressions · world reads:",
                    "§7block(x, y, z)§r or block(\"x y z\") — the block id at a position: $block(0, 64, 0)$ → minecraft:stone",
                    "§7No round trip:§r reads come from YOUR client's synced world copy, so #if handles them instantly — this is /execute if block folded into the expression world, with a real #else.",
                    "§7Crosshair:§r $client.target.hit$ = \"block\"/\"entity\"/\"miss\" — gate with it · BLOCK fields: $client.target.blockpos$ (+ .x/.y/.z), $client.target.block$ (the id) · ENTITY fields: $client.target.type$, .uuid, .name, .health, .pos, .nbt.<path>",
                    "§7raycast(dist)§r or raycast(origin, dir, dist) — where your look (or any ray) hits: \"x y z\" or \"miss\" · raycast_block(dist) — WHAT it hits instead of where — /tupenter help raycast",
                    "§7Pattern:§r #if (client.target.hit == \"block\" && client.target.block == \"minecraft:diamond_ore\") (/echo &bfound it) — a condition is already an expression, so $ $ around names is optional",
                    "§7Limits:§r loaded chunks only (unloaded = loud error, never a guess) · states/NBT not included, just the id",
                    "§7Tick state:§r $world.tickrate$ (from /tick rate, ~20) · $world.frozen$ (is /tick freeze on) · $world.stepping$ (mid /tick step) — exact, synced from the server. So a freeze toggle is just: #if $world.frozen$ (/tick unfreeze) #else (/tick freeze)",
                    "§7Registry sets§r (blockset/itemset/effectset) are under: /tupenter help expressions lists",
            };
            case "vectors" -> new String[]{
                    "§bExpressions · vectors:",
                    "§7A vec3 is a point or a direction:§r \"x y z\". client.pos, client.look, client.motion, world.spawn are all vec3s, and each carries .x/.y/.z.",
                    "§7Build & read:§r vec(x, y, z) makes one (each slot an expression) · component(v, \"y\") — or the dotted .y — reads one back out.",
                    "§7Measure:§r dist(a, b) between two points · mag(v) a vector's length · dot(a, b) alignment: >0 same-ish way, 0 perpendicular, <0 opposite.",
                    "§7Combine:§r vadd / vsub add and subtract · scale(v, s) stretches · normalize(v) keeps the direction at length 1 · cross(a, b) gives a perpendicular.",
                    "§7The move-along pattern:§r N blocks down a heading = vadd(from, scale(normalize(dir), n)) · direction to a target = normalize(vsub(target, here)).",
                    "§7Straight into commands:§r a vec3 substitutes as x y z — /tp @s $vadd(client.pos, scale(client.look, 5))$ leaps 5 blocks the way you face.",
                    "§7Every one has a page:§r /tupenter help functions (the Vectors group), or by name like /tupenter help cross.",
            };
            default -> new String[]{
                    "§cUnknown expressions topic '" + topic + "' — try: math, text, logic, random, lists, world, vectors",
            };
        };
        return sendHelpPage(lines, runLink("« expressions topics", ChatFormatting.DARK_GRAY, "/tupenter help expressions"));
    }

    /** /tupenter help — the front door: every topic as a clickable row. */
    private static int runHelpIndex(CommandContext<FabricClientCommandSource> context) {
        String taste = "#set $x$ = rand(1,10) && /give @s stick $x$ && /echo got $x$!";
        beginHelpPage();
        helpLine("§bTupenter help — pick a topic:");
        // commands first: "what can I even type" is where everyone starts, and
        // it's how you find /tupenter itself
        helpLine(navRow("commands", "everything the mod adds — start here", "/tupenter help commands"));
        helpLine(navRow("custom commands", "make your own /commands (typed params, autocomplete)", "/customcommand help"));
        helpLine(navRow("custom functions", "write your own min()-style functions for expressions", "/customfunction help"));
        helpLine(navRow("expressions", "$...$ — compute inside any vanilla command, or read state and send nothing", "/tupenter help expressions"));
        helpLine(navRow("functions", "every built-in function at a glance", "/tupenter help functions"));
        helpLine(navRow("variables", "#set, #local, client.*/world.*/nbt paths, groups", "/tupenter help variables"));
        helpLine(navRow("flow", "&& chains, #repeat, #for, #foreach, #if/#elseif, #while", "/tupenter help flow"));
        helpLine(navRow("prefixes", "#silent, #norecord, #stage, /echo", "/tupenter help prefixes"));
        helpLine(navRow("scripts", "the every-tick Scripts tab", "/tupenter help scripts"));
        helpLine("§8Anything by name: /tupenter help <command | function | directive | variable> — e.g. help echo, help blockset, help local");
        helpLine(Component.literal("Quick taste: ").withStyle(ChatFormatting.GRAY).append(suggestLink(taste, taste)));
        endHelpPage();
        return 1;
    }

    /** /tupenter help expressions — the overview; the six subtopics are clickable rows. */
    private static int runExpressionsOverview(CommandContext<FabricClientCommandSource> context) {
        String vanillaExample = "/setblock ~ ~-1 ~ $rand(blockset(#minecraft:wool))$";
        String localExample = "/echohud chest: $client.slot.armor.chest.durability$ left";
        beginHelpPage();
        helpLine("§bExpressions — $...$ computes BEFORE the line leaves your client:");
        helpLine("§7Vanilla comes out the other side.§r Whatever $...$ evaluates to is what actually gets sent, so the server receives an ordinary command and never knows Tupenter exists. Nothing is installed there — this works on ANY server you can type commands on, vanilla realms included.");
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(vanillaExample, vanillaExample))
                .append(Component.literal(" → the server just sees /setblock ~ ~-1 ~ minecraft:red_wool").withStyle(ChatFormatting.DARK_GRAY)));
        helpLine("§7See it for yourself:§r /unroll <line> prints the exact vanilla commands your line turns into, and sends none of them.");
        helpLine("§7Or send NOTHING at all.§r /echo and /echohud show text only to you, and conditions read live state locally — so you can watch your own gear, light level or coordinates with zero server traffic (and nothing to get you kicked for chat spam).");
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(localExample, localExample)));
        helpLine("§7Make it a keybind:§r put that in a tick script behind a key and it becomes a HUD you summon — gearcheck = #if (client.keypress.g) (/echohud chest: $client.slot.armor.chest.durability$) — see /tupenter help scripts.");
        helpLine("§7The rule:§r inside $...$ you're writing CODE, not command text — quotes say what it is (\"air\" = text, air = a variable), position says what it becomes.");
        helpLine("§7Works everywhere:§r commands, chat, directives, custom command bodies, tick scripts. A bad $...$ shows a local error and sends NOTHING. \\$ = literal dollar.");
        helpLine("§7Calculator:§r /calc <expr> evaluates locally · /$ expr $ is top-down: numbers display, a string result RUNS as a fresh line");
        helpLine(navRow("math", "arithmetic, exact fractions, stack suffix, rounding, trig", "/tupenter help expressions math"));
        helpLine(navRow("text", "strings, joining, comparisons, strings that run", "/tupenter help expressions text"));
        helpLine(navRow("logic", "booleans, conditions, ternary, #if", "/tupenter help expressions logic"));
        helpLine(navRow("random", "rand, randf, pick, and re-roll rules", "/tupenter help expressions random"));
        helpLine(navRow("lists", "range, len, registry sets, #foreach", "/tupenter help expressions lists"));
        helpLine(navRow("world", "block(x,y,z) and reading your client's world copy", "/tupenter help expressions world"));
        helpLine(navRow("vectors", "dist, dot, cross, normalize — math on positions and directions", "/tupenter help expressions vectors"));
        helpLine(navRow("functions", "the full function index — every one with its own page", "/tupenter help functions"));
        helpLine(runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
        endHelpPage();
        return 1;
    }

    /** /tupenter help flow — the concepts stay prose; each directive's row clicks through to its page. */
    private static int runFlowHelp(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bChains, loops & conditions:");
        helpLine("§7Chain:§r /time set day && /weather clear — one line, sent in order. Each segment picks its form: /command, #directive, bare text = chat. A segment that is exactly one $expr$ runs its string result the same way ($cmd$ holding \"/tp ~ ~1 ~\" teleports).");
        helpLine("§7Groups (...)§r nest and can hold chains. Parens elsewhere are literal text.");
        for (net.tupenter.script.DirectiveDocs.Doc doc : net.tupenter.script.DirectiveDocs.ALL) {
            switch (doc.group()) {
                case LOOPS, CONDITIONS, TIMING ->
                        helpLine(navRow(doc.signature(), doc.blurb(), "/tupenter help " + doc.name()));
                default -> { }
            }
        }
        helpLine("§7Variables have pages too:§r #set · #local · #setdefault — /tupenter help local is the one to read first");
        helpLine("§7Overlap:§r re-running a line starts another concurrent instance (up to the concurrency cap) — they share the per-tick budget round-robin · /tupenter abort <id> stops one, /tupenter abort stops all");
        helpLine("§7Loops that DO something run free:§r a loop that sends or waits each iteration paces itself over ticks, however long it needs — Max Loop Iterations only caps a loop that spins WITHOUT sending or waiting (the runaway guard).");
        helpLine(navRow("prefixes", "#silent, #stage, #chat and friends", "/tupenter help prefixes"));
        helpLine(runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
        endHelpPage();
        return 1;
    }

    /** /tupenter help prefixes — one clickable row per prefix, shorthands derived from the registry. */
    private static int runPrefixesHelp(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bLine prefixes & local output — click one for details:");
        StringBuilder shorthands = new StringBuilder();
        for (net.tupenter.script.DirectiveDocs.Doc doc : net.tupenter.script.DirectiveDocs.ALL) {
            if (doc.group() != net.tupenter.script.DirectiveDocs.Group.PREFIXES) {
                continue;
            }
            helpLine(navRow(doc.signature(), doc.blurb(), "/tupenter help " + doc.name()));
            if (doc.shorthand() != null) {
                if (shorthands.length() > 0) {
                    shorthands.append(" · ");
                }
                shorthands.append(doc.shorthand()).append(" = ").append(doc.canonical());
            }
        }
        helpLine(navRow("/echo <text>", "show text only to yourself — &-colors, evaluates $...$", "/tupenter help echo"));
        helpLine("§7Shorthands:§r " + shorthands + " · prefixes combine: #norecord #silent /say hi");
        helpLine(runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
        endHelpPage();
        return 1;
    }

    /** One directive in depth: blurb, details, two clickable examples, and the way back up its tree. */
    private static int renderDirectivePage(CommandContext<FabricClientCommandSource> context, net.tupenter.script.DirectiveDocs.Doc doc) {
        beginHelpPage();
        helpLine("§b" + doc.signature() + "§r §7— " + doc.blurb());
        if (doc.shorthand() != null) {
            helpLine("§8Shorthand: " + doc.shorthand());
        }
        for (String line : doc.detail()) {
            helpLine(line);
        }
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(doc.exampleSimple(), doc.exampleSimple())));
        helpLine(Component.literal("Then: ").withStyle(ChatFormatting.GRAY).append(suggestLink(doc.exampleComposed(), doc.exampleComposed())));
        switch (doc.group()) {
            case VARIABLES -> helpLine(runLink("« variables", ChatFormatting.DARK_GRAY, "/tupenter help variables"));
            case FUNCTIONS -> helpLine(runLink("« custom functions guide", ChatFormatting.DARK_GRAY, "/customfunction help"));
            case PREFIXES -> helpLine(runLink("« prefixes", ChatFormatting.DARK_GRAY, "/tupenter help prefixes"));
            default -> helpLine(runLink("« flow", ChatFormatting.DARK_GRAY, "/tupenter help flow"));
        }
        endHelpPage();
        return 1;
    }

    /** /tupenter help variables — the concepts stay prose; every subject is a clickable row. */
    private static int runVariablesHelp(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bVariables — the name IS the variable:");
        helpLine("§7Bare name is normal:§r #set x = 5, then x in expression world — #if (x > 3), x + 1, a function arg. $x$ is the SAME name with explicit wrapping; you only need it as the door into command/chat text, where /give @s stick $x$ substitutes but plain x is the letter.");
        helpLine(navRow("#set · #local · #setdefault", "YOUR variables — session, line-local, init-once (each has a page)", "/tupenter help local"));
        helpLine("§7Persistent:§r /tupenter var save <name> keeps a #set forever · var delete <name> removes it · create-once-across-sessions: #setdefault $x$ = 0 && /tupenter var save $x$");
        helpLine("§7Dotted or function?§r Both read LIVE — the difference is the ADDRESS, not the value. Spell the address out and it's a dotted variable (tab-completes); COMPUTE it and it's a function: client.slot.inventory.8.id vs slot(\"inventory.\" + i, \"id\").");
        helpLine("§7One vocabulary, three subjects:§r type, uuid, name, health, pos, blockpos, nbt.<path> work on YOU (client.*), your CROSSHAIR (client.target.*), and ANY entity (entity(uuid, ...)). Swapping subject changes only the subject.");
        for (net.tupenter.script.SubjectDocs.Subject subject : net.tupenter.script.SubjectDocs.ALL) {
            helpLine(navRow(subject.name() + ".*", subject.blurb(), "/tupenter help " + subject.name()));
        }
        helpLine(navRow("/tupenter vars", "the live inspector — current values by group", "/tupenter vars"));
        helpLine("§7In custom commands:§r declared params bind as $name$ or $1$..$n$.");
        helpLine(runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
        endHelpPage();
        return 1;
    }

    /** The VarDocs of an enumerated subject, or null for dynamic subjects. */
    private static java.util.List<net.tupenter.script.VarDoc> subjectVarDocs(String subjectName) {
        return switch (subjectName) {
            case "client" -> CLIENT_VARIABLES.docs();
            case "world" -> WORLD_VARIABLES.docs();
            case "players" -> PLAYERS_VARIABLES.docs();
            case "real" -> REAL_VARIABLES.docs();
            default -> null;
        };
    }

    /**
     * One subject in depth. Enumerated subjects list their members straight
     * from the provider's registrations — compact category rows where every
     * name is clickable and its blurb rides in the hover.
     */
    private static int renderSubjectPage(CommandContext<FabricClientCommandSource> context, net.tupenter.script.SubjectDocs.Subject subject) {
        beginHelpPage();
        helpLine("§b" + subject.name() + ".*§r §7— " + subject.blurb());
        if (subject.gate() != null) {
            helpLine("§7Gate:§r " + subject.gate());
        }
        for (String line : subject.detail()) {
            helpLine(line);
        }
        java.util.List<net.tupenter.script.VarDoc> docs = subjectVarDocs(subject.name());
        if (docs != null) {
            java.util.LinkedHashMap<String, MutableComponent> rows = new java.util.LinkedHashMap<>();
            int prefixLen = subject.name().length() + 1;
            for (net.tupenter.script.VarDoc doc : docs) {
                String shortName = doc.name().substring(prefixLen);
                if (shortName.indexOf('.') >= 0) {
                    continue; // .x/.y/.z components ride on their base's blurb
                }
                MutableComponent row = rows.computeIfAbsent(doc.category(),
                        category -> Component.literal(category + ": ").withStyle(ChatFormatting.DARK_AQUA));
                if (!row.getSiblings().isEmpty()) {
                    row.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
                }
                String page = "/tupenter help " + doc.name();
                row.append(Component.literal(shortName).withStyle(style -> style
                        .withColor(ChatFormatting.WHITE)
                        .withClickEvent(new ClickEvent.RunCommand(page))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§7" + doc.blurb() + "\n§8" + page)))));
            }
            rows.values().forEach(TupenterModClient::helpLine);
        }
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(subject.exampleSimple(), subject.exampleSimple())));
        helpLine(Component.literal("Then: ").withStyle(ChatFormatting.GRAY).append(suggestLink(subject.exampleComposed(), subject.exampleComposed())));
        if (docs != null) {
            helpLine(runLink("live values: /tupenter vars " + subject.name(), ChatFormatting.DARK_GRAY, "/tupenter vars " + subject.name()));
        }
        helpLine(runLink("« variables", ChatFormatting.DARK_GRAY, "/tupenter help variables"));
        endHelpPage();
        return 1;
    }

    /** One enumerated variable: its blurb, its value right now, and the way up. */
    private static int renderVariableMiniPage(CommandContext<FabricClientCommandSource> context, String name, String blurb) {
        beginHelpPage();
        helpLine("§b$" + name + "$§r §7— " + blurb);
        try {
            VARIABLE_REGISTRY.resolve(name).ifPresent(value -> helpLine("§7Right now:§r §f" + value.displayString()));
        } catch (RuntimeException notAvailable) {
            helpLine("§8(no live value — " + notAvailable.getMessage() + ")");
        }
        net.tupenter.script.SubjectDocs.Subject subject = net.tupenter.script.SubjectDocs.findByPrefix(name);
        helpLine(subject != null
                ? runLink("« " + subject.name() + ".*", ChatFormatting.DARK_GRAY, "/tupenter help " + subject.name())
                : runLink("« variables", ChatFormatting.DARK_GRAY, "/tupenter help variables"));
        endHelpPage();
        return 1;
    }

    private static int runHelpCommand(CommandContext<FabricClientCommandSource> context, String topic) {
        if (topic.equals("index")) {
            return runHelpIndex(context);
        }
        if (topic.equals("variables")) {
            return runVariablesHelp(context);
        }
        if (topic.equals("expressions")) {
            return runExpressionsOverview(context);
        }
        if (topic.equals("flow")) {
            return runFlowHelp(context);
        }
        if (topic.equals("prefixes")) {
            return runPrefixesHelp(context);
        }
        String[] lines = switch (topic) {
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
            default -> null;
        };
        if (lines == null) {
            return runHelpIndex(context);
        }
        return sendHelpPage(lines, runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
    }

    /** /tupenter help commands — the list; one command in depth comes via bare-name resolution. */
    private static int runCommandHelp(CommandContext<FabricClientCommandSource> context, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.equals("all") || name.equals("commands")) {
            beginHelpPage();
            helpLine("§bCommands Tupenter adds (all client-side) — click one for its page:");
            helpLine(navRow("/tupenter", "running · abort · scripts · vars · var save/delete · dump · help", "/tupenter help tupenter"));
            helpLine(navRow("/customcommand", "add · update · remove · list — make your own commands", "/tupenter help customcommand"));
            helpLine(navRow("/customfunction", "add · update · remove · list — your own $name(...)$ expression functions", "/tupenter help customfunction"));
            helpLine(navRow("/echo <text>", "local-only output, &-colors, evaluates $...$", "/tupenter help echo"));
            helpLine(navRow("/echohud <text>", "same, on the action bar (auto-fades)", "/tupenter help echohud"));
            helpLine(navRow("/calc <expr>", "local calculator · /$ expr $ is the top-down shorthand", "/tupenter help calc"));
            helpLine(navRow("/unroll <line>", "dry-run debugger", "/tupenter help unroll"));
            helpLine("§7Keybinds (Options → Controls):§r resend key (default R) · open config · toggle message tracking");
            helpLine(runLink("« help topics", ChatFormatting.DARK_GRAY, "/tupenter help"));
            endHelpPage();
            return 1;
        }
        String[] lines = switch (name) {
            case "tupenter" -> new String[]{
                    "§b/tupenter — mod control:",
                    "§7running§r — armed tick scripts + running instances, each with an id and a clickable [abort]; §7running hud§r toggles the same list as an on-screen panel that survives chat spam",
                    "§7abort <id>§r — stop one by its id: a running instance is killed; an armed tick script is switched OFF (its Mod Menu toggle). §7abort <name>§r switches off a named tick script. §7abort§r alone stops everything + disables tick scripts (panic switch)",
                    "§7scripts§r — what's armed in THIS world · §7scripts enable|disable§r — master on/off (no name), or arm/disarm one by §fname§r",
                    "§7vars [group]§r — variables overview, or one group with live values",
                    "§7var save <name>§r — make a #set variable persistent · §7var delete <name>§r — remove it",
                    "§7dump [client|target] [path]§r — BROWSE entity NBT: click a branch to open it, click a value to put its $variable$ in your chat bar, breadcrumb walks back up (the data behind client.nbt.* / client.target.nbt.*)",
                    "§7help <topic>§r — topics: commands, expressions [math|text|logic|random|lists|world], variables, flow, prefixes, scripts, functions · §7help <name>§r opens ANY single thing by name — a command (help echo), function (help blockset), directive (help local), or variable (help client.vehicle)",
            };
            case "customcommand" -> new String[]{
                    "§b/customcommand — make your own commands:",
                    "§7add <name> [params] = <body>§r — create · §7update§r — edit · §7remove <name>§r — delete",
                    "§7Always signature = body§r — the = separates them (the same form it's stored in). An optional \"quoted note\" goes last before the =: /customcommand add tickfreeze \"toggles time\" = #if ($world.frozen$) (/tick unfreeze) #else (/tick freeze). The note shows on missing args + in /customcommand <name>; &-colors like /echo.",
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
                    "§7$...$§r evaluates first: /echo y is $client.pos.y$",
                    "§7&-codes color the text from that point on, until the next code or &r:",
                    "§7Colors:§r §0&0§1&1§2&2§3&3§4&4§5&5§6&6§7&7§8&8§9&9§a&a§b&b§c&c§d&d§e&e§f&f§r §7(0-9, a-f)",
                    "§7Formats:§r &l §lbold§r§7 · &o §oitalic§r§7 · &n §nunderline§r§7 · &m §mstrike§r§7 · &k obfuscated · &r reset",
                    "§7\\&§r prints a literal & · codes work from variables too: #set $ok$ = \"&aOK\"",
                    "§7Example:§r /echo &ahp $client.health$ &7/ 20",
                    "§7Sibling:§r /echohud — same thing, but on the action bar (above the hotbar): /tupenter help echohud",
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
                    "§7/calc int <expr>§r / §7/calc float <expr>§r — wraps the whole expression in int(...) / float(...): truncated toward zero, or forced to a decimal",
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
                    "§cNo command page for '" + rawName + "' — try: " + String.join(", ", MOD_COMMAND_PAGES)
                            + " · see them all: /tupenter help commands",
            };
        };
        return sendHelpPage(lines, runLink("« all commands", ChatFormatting.DARK_GRAY, "/tupenter help commands"));
    }

    /** How many list entries one dump page shows before pointing you at direct indexing. */
    private static final int DUMP_LIST_LIMIT = 32;

    /**
     * /tupenter dump — an NBT BROWSER, not a log. One level per pager page:
     * compounds and lists click through to their own level, and a SCALAR leaf
     * clicks its {@code $variable$} into the chat bar, which is the whole point
     * of browsing — find the path, then use it. A breadcrumb walks back up.
     */
    private static int runDumpCommand(CommandContext<FabricClientCommandSource> context, String which, String path) {
        if (!which.equals("client") && !which.equals("target")) {
            context.getSource().sendError(Component.literal("Usage: /tupenter dump [client|target] [path]"));
            return 0;
        }
        // The dump's own word is NOT the variable root: the crosshair entity's
        // tree is client.target.nbt.*, so every reference we hand back stays a
        // name you can actually paste into a command.
        String varRoot = which.equals("target") ? "client.target.nbt" : "client.nbt";
        try {
            var entity = which.equals("target") ? EntityNbtVariableProvider.targetEntity() : EntityNbtVariableProvider.clientEntity();
            var root = EntityNbtVariableProvider.snapshot(entity);
            String cleanPath = path.trim().replaceAll("^\\.|\\.$", "");
            String fullName = varRoot + (cleanPath.isEmpty() ? "" : "." + cleanPath);
            var tag = EntityNbtVariableProvider.walk(root, cleanPath, fullName);

            beginHelpPage();
            helpLine(dumpBreadcrumb(which, varRoot, cleanPath));
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                if (compound.isEmpty()) {
                    helpLine("§8(empty compound)");
                }
                compound.keySet().stream().sorted().forEach(key ->
                        helpLine(dumpChildRow(which, varRoot, cleanPath, key, compound.get(key))));
            } else if (tag instanceof net.minecraft.nbt.CollectionTag list) {
                if (list.isEmpty()) {
                    helpLine("§8(empty list)");
                }
                int shown = Math.min(list.size(), DUMP_LIST_LIMIT);
                for (int i = 0; i < shown; i++) {
                    helpLine(dumpChildRow(which, varRoot, cleanPath, String.valueOf(i), list.get(i)));
                }
                if (list.size() > shown) {
                    helpLine("§8… " + (list.size() - shown) + " more — index one directly: /tupenter dump "
                            + which + " " + (cleanPath.isEmpty() ? "" : cleanPath + ".") + shown);
                }
            } else {
                // a leaf reached head-on: show the value and offer its reference
                helpLine(Component.literal(" " + summarizeTag(tag)).withStyle(ChatFormatting.WHITE));
                helpLine(Component.literal("Use it: ").withStyle(ChatFormatting.GRAY)
                        .append(suggestLink("$" + fullName + "$", "$" + fullName + "$")));
            }
            helpLine(dumpUpLink(which, varRoot, cleanPath));
            endHelpPage();
            return 1;
        } catch (IllegalArgumentException ex) {
            context.getSource().sendError(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    /** {@code client.nbt › Inventory › 0} — every ancestor clickable, the current level plain. */
    private static Component dumpBreadcrumb(String which, String varRoot, String cleanPath) {
        MutableComponent line = Component.literal("");
        String rootCommand = "/tupenter dump " + which;
        boolean atRoot = cleanPath.isEmpty();
        line.append(atRoot
                ? Component.literal(varRoot).withStyle(ChatFormatting.AQUA)
                : runLink(varRoot, ChatFormatting.AQUA, rootCommand));
        if (!atRoot) {
            String[] segments = cleanPath.split("\\.");
            StringBuilder walked = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (walked.length() > 0) {
                    walked.append('.');
                }
                walked.append(segments[i]);
                line.append(Component.literal(" › ").withStyle(ChatFormatting.DARK_GRAY));
                line.append(i == segments.length - 1
                        ? Component.literal(segments[i]).withStyle(ChatFormatting.AQUA)
                        : runLink(segments[i], ChatFormatting.AQUA, "/tupenter dump " + which + " " + walked));
            }
        }
        return line;
    }

    /** The bottom nav — the page snaps to its end, so the way back up has to be reachable there. */
    private static Component dumpUpLink(String which, String varRoot, String cleanPath) {
        if (cleanPath.isEmpty()) {
            return Component.literal("§8Click a branch to open it · click a value to put its $variable$ in your chat bar");
        }
        int lastDot = cleanPath.lastIndexOf('.');
        String parent = lastDot < 0 ? "" : cleanPath.substring(0, lastDot);
        String label = "« " + varRoot + (parent.isEmpty() ? "" : "." + parent);
        return runLink(label, ChatFormatting.DARK_GRAY,
                "/tupenter dump " + which + (parent.isEmpty() ? "" : " " + parent));
    }

    /**
     * One child row. A branch (compound/list) navigates into itself; a leaf
     * offers its {@code $variable$} for the chat bar — browse, then use.
     */
    private static Component dumpChildRow(String which, String varRoot, String cleanPath,
                                          String key, net.minecraft.nbt.Tag child) {
        String childPath = cleanPath.isEmpty() ? key : cleanPath + "." + key;
        boolean branch = child instanceof net.minecraft.nbt.CompoundTag
                || child instanceof net.minecraft.nbt.CollectionTag;
        MutableComponent row = Component.literal(" " + key + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(summarizeTag(child))
                        .withStyle(branch ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        if (branch) {
            String command = "/tupenter dump " + which + " " + childPath;
            return row.withStyle(style -> style
                    .withClickEvent(new ClickEvent.RunCommand(command))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
        }
        String reference = "$" + varRoot + "." + childPath + "$";
        return row.withStyle(style -> style
                .withClickEvent(new ClickEvent.SuggestCommand(reference))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(reference + "\n§8click to put it in your chat bar"))));
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
        beginHelpPage();
        if (definitions.isEmpty()) {
            helpLine(Component.literal("No custom commands saved. Try /customcommand help").withStyle(ChatFormatting.YELLOW));
            endHelpPage();
            return 1;
        }

        helpLine(Component.literal("Saved custom commands — click one for its page:").withStyle(ChatFormatting.AQUA));
        for (String definition : definitions) {
            CommandAliasManager.ParsedAlias parsed = CommandAliasManager.parseDefinition(definition);
            if (parsed == null) {
                helpLine(Component.literal(" - " + definition + " (invalid)").withStyle(ChatFormatting.RED));
                continue;
            }
            helpLine(aliasLine(parsed, verbose));
        }
        if (!verbose) {
            helpLine(Component.literal("Full bodies: /customcommand list verbose · one command: /customcommand <name>").withStyle(ChatFormatting.DARK_GRAY));
        }
        endHelpPage();
        return 1;
    }

    private static Component aliasLine(CommandAliasManager.ParsedAlias parsed, boolean fullBody) {
        String body = parsed.definition().body();
        if (!fullBody && body.length() > 40) {
            body = body.substring(0, 40) + "…";
        }
        String page = "/customcommand " + parsed.name();
        return Component.literal(" /").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(parsed.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(parsed.definition().params().isEmpty()
                        ? " " : " " + parsed.definition().declarationPrefix()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("→ ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(body).withStyle(fullBody ? ChatFormatting.WHITE : ChatFormatting.GRAY))
                // the whole row navigates to the command's detail page
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(page))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(page))));
    }

    private static int runAliasDetailCommand(CommandContext<FabricClientCommandSource> context) {
        String name = CommandAliasManager.normalizeName(StringArgumentType.getString(context, "name"));
        AliasDefinition definition = CommandAliasManager.getAliasMap().get(name);
        if (definition == null) {
            context.getSource().sendError(Component.literal("No custom command /" + name + " — see /customcommand list"));
            return 0;
        }
        beginHelpPage();
        helpLine(Component.literal("/").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(definition.params().isEmpty() ? "" : " " + definition.declarationPrefix().trim()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  "))
                .append(suggestLink("[edit]", "/customcommand update " + name + " " + CommandAliasManager.getRawCommand(name))));
        if (!definition.description().isEmpty()) {
            helpLine(Component.literal(" ")
                    .append(Component.literal(applyAmpersandColors(definition.description())).withStyle(ChatFormatting.GRAY)));
        }
        helpLine(Component.literal(" body: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(definition.body()).withStyle(ChatFormatting.WHITE)));
        endHelpPage();
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

    /** /customcommand help — the overview; the deep topics are their own navigable pages. */
    private static int runCustomCommandHelp(CommandContext<FabricClientCommandSource> context) {
        String wavesExample = "/customcommand add waves <count:int> <mob:entity> = #repeat $count$ (/summon $mob$ ~ ~ ~)";
        beginHelpPage();
        helpLine("§bCustom commands — make your own /commands:");
        helpLine("§7Create:§r /customcommand add <name> [params] = <body> · §7edit:§r update · §7remove§r · §7list§r");
        helpLine("§7The shape is always signature = body§r — the = separates them, exactly how the command is stored. Simplest: sunny = /weather clear && Have fun!");
        helpLine("§7Bodies§r hold anything a chat line can: commands, chat, && chains, $...$ expressions, #directives, other custom commands. Commands keep their /.");
        helpLine("§7Parameters§r go before the = and bind as $name$ or $1$..$n$: /customcommand add smite <target:player> = /execute at $target$ run summon minecraft:lightning_bolt — then /smite Steve.");
        helpLine(navRow("types", "all " + ParamTypeDocs.ALL.size() + " parameter types, each with its own page", "/customcommand help types"));
        helpLine(navRow("optionals", "=defaults, mid-command skipping, the sentinel trick", "/customcommand help optionals"));
        helpLine(navRow("descriptions", "the optional \"quoted note\" before the =", "/customcommand help descriptions"));
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(wavesExample, wavesExample)));
        helpLine("§8Then /waves 3 zombie · your saved commands: /customcommand list");
        endHelpPage();
        return 1;
    }

    /** Topics /customcommand help <topic> can open: the three fixed pages plus every type keyword. */
    /** The three real topic pages — the ONLY thing shown at an empty prefix so the 23 type keywords don't bury them. */
    private static final java.util.List<String> CUSTOM_COMMAND_HELP_TOPICS = java.util.List.of("types", "optionals", "descriptions");

    private static java.util.List<String> customCommandHelpTopics() {
        java.util.List<String> topics = new ArrayList<>(CUSTOM_COMMAND_HELP_TOPICS);
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            topics.add(doc.keyword());
        }
        return topics;
    }

    /**
     * Same decongestion as /tupenter help: this argument has no literal siblings
     * to fall back on, so an empty prefix shows the three real topic pages
     * (types is the clickable index into the 23 type keywords); once you start
     * typing, the type keywords join in so /customcommand help block completes.
     */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCustomCommandHelpTopics(
            CommandContext<FabricClientCommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        java.util.List<String> pool = builder.getRemaining().isEmpty() ? CUSTOM_COMMAND_HELP_TOPICS : customCommandHelpTopics();
        return net.minecraft.commands.SharedSuggestionProvider.suggest(pool, builder);
    }

    private static int runCustomCommandHelpTopic(CommandContext<FabricClientCommandSource> context, String rawTopic) {
        String topic = rawTopic.toLowerCase(java.util.Locale.ROOT);
        switch (topic) {
            case "types" -> {
                return runParamTypesIndex(context);
            }
            case "optionals" -> {
                return runOptionalsHelp(context);
            }
            case "descriptions" -> {
                return runDescriptionsHelp(context);
            }
            default -> {
                ParamTypeDocs.Doc doc = ParamTypeDocs.find(topic);
                if (doc != null) {
                    return renderParamTypePage(context, doc);
                }
                context.getSource().sendError(Component.literal("No page '" + rawTopic
                        + "' — topics: types, optionals, descriptions, or a type keyword (see /customcommand help types)"));
                return 0;
            }
        }
    }

    /** /customcommand help types — the index, rendered from the ParamTypeDocs registry. */
    private static int runParamTypesIndex(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bParameter types — click one (or /customcommand help <type>) for details:");
        String group = null;
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            if (!doc.group().equals(group)) {
                group = doc.group();
                helpLine(Component.literal(group + ":").withStyle(ChatFormatting.DARK_AQUA));
            }
            String shown = doc.type() == AliasDefinition.ParamType.CHOICE ? "<n:a,b,c>" : "<n:" + doc.keyword() + ">";
            helpLine(navRow(shown, doc.blurb(), "/customcommand help " + doc.keyword()));
        }
        helpLine("§8Bare <name> is a string · strict types make optionals skippable — /customcommand help optionals");
        helpLine(runLink("« custom commands guide", ChatFormatting.DARK_GRAY, "/customcommand help"));
        endHelpPage();
        return 1;
    }

    /** One parameter type in depth: blurb, details, a runnable example. */
    private static int renderParamTypePage(CommandContext<FabricClientCommandSource> context, ParamTypeDocs.Doc doc) {
        beginHelpPage();
        String head = doc.type() == AliasDefinition.ParamType.CHOICE ? "<name:opt1,opt2,...>" : "<name:" + doc.keyword() + ">";
        helpLine("§b" + head + "§r §7— " + doc.blurb());
        if (!doc.synonyms().isEmpty()) {
            helpLine("§8Also spelled: " + String.join(", ", doc.synonyms()));
        }
        for (String line : doc.detail()) {
            helpLine(line);
        }
        helpLine(Component.literal("Try: ").withStyle(ChatFormatting.GRAY).append(suggestLink(doc.example(), doc.example())));
        helpLine(runLink("« all types", ChatFormatting.DARK_GRAY, "/customcommand help types"));
        endHelpPage();
        return 1;
    }

    /** /customcommand help optionals — defaults, skipping, sentinels. */
    private static int runOptionalsHelp(CommandContext<FabricClientCommandSource> context) {
        beginHelpPage();
        helpLine("§bOptional parameters — add =default:");
        helpLine("§7<r:int=5>, <p:pos=~ ~ ~>§r — omitting the argument means the default. Defaults may hold $...$ expressions, evaluated when omitted, with earlier params visible.");
        helpLine("§7Mid-command skipping:§r a STRICTLY-typed optional can be skipped — /portal to_nether works with <p:pos=~ ~ ~> <dim:to_overworld,to_nether> because to_nether isn't a coordinate, so the parser skips p.");
        helpLine("§7Loose types grab greedily:§r string/word/text take whatever comes next — put those last.");
        helpLine("§7No natural default?§r Use a SENTINEL the body branches on: <filter:blockset=any> then #if ($filter$ == \"any\") (unfiltered...) #else (filtered...) — an omitted param can mean \"do something else\", not just \"use this value\".");
        helpLine(runLink("« custom commands guide", ChatFormatting.DARK_GRAY, "/customcommand help"));
        endHelpPage();
        return 1;
    }

    /** /customcommand help descriptions — the quoted note. */
    private static int runDescriptionsHelp(CommandContext<FabricClientCommandSource> context) {
        String plainExample = "/customcommand add tickfreeze \"toggles time\" = #if ($world.frozen$) (/tick unfreeze) #else (/tick freeze)";
        String paramExample = "/customcommand add greet <who:player> \"wave at someone\" = /me waves at $who$";
        beginHelpPage();
        helpLine("§bDescriptions — a note on your command:");
        helpLine("§7An optional \"quoted note\" is the last thing before the = : §fname [params] \"note\" = body§r. That's all there is to it — the = is always there, so the note is never mistaken for body text.");
        helpLine(Component.literal("No params: ").withStyle(ChatFormatting.GRAY).append(suggestLink(plainExample, plainExample)));
        helpLine(Component.literal("With params: ").withStyle(ChatFormatting.GRAY).append(suggestLink(paramExample, paramExample)));
        helpLine("§7A quoted body still works§r — quotes AFTER the = are body text: greet = \"hello there\" chats it.");
        helpLine("§7Where it shows:§r on missing arguments and in /customcommand <name> · &-colors work, like /echo.");
        helpLine(runLink("« custom commands guide", ChatFormatting.DARK_GRAY, "/customcommand help"));
        endHelpPage();
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
