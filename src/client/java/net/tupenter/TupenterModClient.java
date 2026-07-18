package net.tupenter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.tupenter.command.CommandAliasManager;
import net.tupenter.config.TupenterConfig;
import net.tupenter.command.ClientCommandRegistrar;
import net.tupenter.command.ClientVariableProvider;
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

    /** Sends a stored line the way the resend system and scripts need it sent. */
    public static void dispatchStoredCommand(Minecraft client, String commandWithoutSlash) {
        if (tryExecuteClientCommand(commandWithoutSlash)) {
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
    private static final WorldVariableProvider WORLD_VARIABLES = new WorldVariableProvider();
    private static final PlayersVariableProvider PLAYERS_VARIABLES = new PlayersVariableProvider();
    private static final RealTimeVariableProvider REAL_VARIABLES = new RealTimeVariableProvider();

    static {
        VARIABLE_REGISTRY.register(SESSION_VARIABLES);
        VARIABLE_REGISTRY.register(PERSISTENT_VARIABLES);
        VARIABLE_REGISTRY.register(CLIENT_VARIABLES);
        VARIABLE_REGISTRY.register(WORLD_VARIABLES);
        VARIABLE_REGISTRY.register(PLAYERS_VARIABLES);
        VARIABLE_REGISTRY.register(REAL_VARIABLES);
        VARIABLE_REGISTRY.register(new EntityNbtVariableProvider());
    }

    private static final TickScriptRunner TICK_SCRIPTS = new TickScriptRunner();

    public static void resetTickScriptFaults() {
        TICK_SCRIPTS.reset();
    }

    /** Names to check /customcommand bodies against at save time. */
    public static java.util.Set<String> knownDottedVariableNames() {
        java.util.Set<String> names = new java.util.HashSet<>(CLIENT_VARIABLES.names());
        names.addAll(WORLD_VARIABLES.names());
        names.addAll(PLAYERS_VARIABLES.names());
        names.addAll(REAL_VARIABLES.names());
        return names;
    }

    public static ScriptParser.Options parserOptions() {
        return new ScriptParser.Options(
                TupenterConfig.INSTANCE.commandChainingEnabled,
                TupenterConfig.INSTANCE.numberMathMode,
                CommandAliasManager.getAliasMap(),
                TupenterConfig.INSTANCE.silentDirectiveEnabled,
                TupenterConfig.INSTANCE.variablesEnabled,
                TupenterConfig.INSTANCE.loopsEnabled,
                TupenterConfig.INSTANCE.conditionalsEnabled,
                TupenterConfig.INSTANCE.maxLoopIterations,
                TupenterConfig.INSTANCE.maxCommandsPerScript,
                SCRIPT_RANDOM,
                VARIABLE_REGISTRY,
                SESSION_VARIABLES,
                TAG_RESOLVER
        );
    }

    /**
     * Backs blockset("#...")/itemset("#...") with the connection's synced
     * registries. Null when not in-game (the functions error clearly then);
     * an unknown tag resolves to an empty list.
     */
    public static final net.tupenter.script.TagResolver TAG_RESOLVER = (kind, tagId) -> {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(tagId);
        if (location == null) {
            return java.util.List.of();
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (kind == net.tupenter.script.TagResolver.TagKind.ITEM) {
            var registry = connection.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ITEM);
            for (var holder : registry.getTagOrEmpty(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, location))) {
                holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()));
            }
        } else {
            var registry = connection.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            for (var holder : registry.getTagOrEmpty(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, location))) {
                holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()));
            }
        }
        return ids;
    };

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
    public static boolean handleStagePrefix(String line, boolean commandOrigin) {
        String trimmed = line.trim();
        if (!trimmed.regionMatches(true, 0, "#stage", 0, 6)) {
            return false;
        }
        if (trimmed.length() > 6 && !Character.isWhitespace(trimmed.charAt(6))) {
            return false; // "#staged..." or similar — not our word
        }

        String staged = trimmed.substring(6).trim();
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
        if (!trimmed.regionMatches(true, 0, "#unstage", 0, 8)) {
            return false;
        }
        if (trimmed.length() > 8 && !Character.isWhitespace(trimmed.charAt(8))) {
            return false; // "#unstaged..." or similar — not our word
        }

        String argument = trimmed.substring(8).trim();
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
                                    .executes(context -> {
                                        int aborted = SCRIPT_EXECUTOR.runningCount();
                                        SCRIPT_EXECUTOR.abortAll();
                                        pendingQueue.clear();
                                        delayTimer = 0;
                                        // panic switch: aborting while tick scripts keep
                                        // resubmitting every tick would be futile
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
                                    }))
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
                                    .then(literal("expressions").executes(context -> runHelpCommand(context, "expressions")))
                                    .then(literal("variables").executes(context -> runHelpCommand(context, "variables")))
                                    .then(literal("flow").executes(context -> runHelpCommand(context, "flow")))
                                    .then(literal("prefixes").executes(context -> runHelpCommand(context, "prefixes")))
                                    .then(literal("scripts").executes(context -> runHelpCommand(context, "scripts")))
                                    .then(literal("commands").executes(context -> runCommandHelp(context, "all")))
                                    .then(literal("command")
                                            .executes(context -> runCommandHelp(context, "all"))
                                            .then(argument("name", StringArgumentType.word())
                                                    .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                            new String[]{"all", "tupenter", "customcommand", "echo", "calc", "unroll"}, b))
                                                    .executes(context -> runCommandHelp(context, StringArgumentType.getString(context, "name")))))));

                    dispatcher.register(literal("echo")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(TupenterModClient::runEchoCommand)));

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
        });

		// Load Config
		TupenterConfig.load();
		PERSISTENT_VARIABLES.load(TupenterConfig.INSTANCE.persistentVariables);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Drain any scripts still holding statements (budget-stretched ones)
            SCRIPT_EXECUTOR.tick();

            // Mod Menu "Scripts" list — runs every tick while enabled
            TICK_SCRIPTS.tick(SCRIPT_EXECUTOR);

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

        ScriptParser.Options base = parserOptions();
        ScriptParser.Options options = new ScriptParser.Options(
                base.chainingEnabled(), base.mathMode(), base.aliases(), base.silentDirectiveEnabled(),
                base.variablesEnabled(), base.loopsEnabled(), base.conditionalsEnabled(),
                base.maxLoopIterations(), base.maxCommandsPerScript(), base.random(), base.variables(),
                null, base.tags()); // dry run — #set writes stay out of the session

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
            };
            String label = switch (statement.kind()) {
                case COMMAND -> "/";
                case CHAT -> "chat: ";
            };
            net.minecraft.network.chat.MutableComponent entry = Component.literal(" " + shown + ". ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(label + statement.content()).withStyle(color));
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
        if (suggested.length() > 256) {
            message.append(Component.literal(" (over 256 chars — the chat bar will cut it off; edit long commands in Mod Menu)")
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
        var value = SESSION_VARIABLES.resolve(name);
        if (value.isEmpty()) {
            context.getSource().sendError(Component.literal("No session variable $" + name + "$ — set it first with #set $" + name + "$ = ..."));
            return 0;
        }
        try {
            PERSISTENT_VARIABLES.set(name, value.get());
        } catch (IllegalArgumentException ex) {
            context.getSource().sendError(Component.literal(ex.getMessage()));
            return 0;
        }
        savePersistentVariables();
        context.getSource().sendFeedback(Component.literal("Saved $" + name + "$ = " + value.get().displayString() + " (persists across sessions)").withStyle(ChatFormatting.GREEN));
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

    private static int runHelpCommand(CommandContext<FabricClientCommandSource> context, String topic) {
        String[] lines = switch (topic) {
            case "expressions" -> new String[]{
                    "§bExpressions — $...$ evaluates before sending:",
                    "§7Math:§r /give @s stick $32+5$ · exact fractions, no float drift · $3s$ = 3 stacks (×64)",
                    "§7Text:§r \"quoted\" · + joins: $\"lvl \" + 5$ · comparisons: == != < <= > >=",
                    "§7Conditions:§r $client.y > 60 ? 10 : 0$ · true/false · && \\|\\| !",
                    "§7Functions:§r rand(1,64) randf pick(a | b | c) range(1,10) int float abs floor ceil round min max len sqrt sin cos tan (degrees). pick options are expressions and nest — quote literal text: pick(\"say hi\" | \"say nah\")",
                    "§7Tag sets:§r blockset(\"#minecraft:logs\") / itemset(\"#c:ores\") = the tag's members as a list (needs a live world) · rand(list) picks one: /setblock ~ ~ ~ $rand(blockset(\"#minecraft:logs\"))$ · loops too: #foreach $b$ in blockset(\"#minecraft:logs\") (/say $b$)",
                    "§7Implicit math:§r with Inline Expressions = Auto-detect (the default), bare math evaluates WITHOUT markers: /give @s stick 64*5 → 320. Numbers-and-operators only (no variables/functions beyond int/float), skipped inside {NBT braces}, and unparseable text is sent as-is (never errors). $...$ is the full language and works everywhere, in every mode except Disabled.",
                    "§7Gotchas:§r \\$ = literal dollar · write 2*sin(x), not 2sin(x) (bare s = stack suffix)",
                    "§7Errors:§r a bad $...$ shows a local error and sends NOTHING.",
                    "§7Try it:§r /calc <expr> evaluates locally. /$ expr $ is top-down: numbers display, but a string result RUNS as a fresh line — \"/...\" = command, \"#...\" = directive, else chat. /$pick(\"hi\" | \"/time set day\")$",
            };
            case "variables" -> new String[]{
                    "§bVariables — use anywhere as $name$:",
                    "§7Yours:§r #set $x$ = 5 (session, cleared on join) · #local $x$ = 5 (this line only, silent) · dotted groups allowed: #set $hitlist.bob$ = \"wanted\"",
                    "§7Persistent:§r /tupenter var save <name> keeps it forever · /tupenter var delete <name>",
                    "§7Built-in:§r $client.x/y/z/health/held_item/target_block...$ · $world.time/difficulty/raining...$ · $players.count/list$ · $real.hour/day_of_week...$",
                    "§7Everything else:§r $client.nbt.<any path>$ / $target.nbt.<any path>$ — e.g. $client.nbt.Inventory.0.id$ · browse with /tupenter dump",
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
                    "§7Groups (...) nest and can hold chains. Parens elsewhere are literal text.",
                    "§7Caps:§r loops ≤ Max Loop Iterations · scripts ≤ Max Commands Per Script · sends spread over ticks past Max Commands Per Tick",
            };
            case "prefixes" -> new String[]{
                    "§bLine prefixes & local output:",
                    "§7#silent§r — hide command feedback on your screen: whole line (#silent /time set day) or part of it (#silent (/give @s stick) && /say hi). Also mutes #set notices.",
                    "§7#norecord§r — run the line but keep it out of resend history",
                    "§7#record§r — the inverse: records even when message tracking is OFF (and bypasses the filter)",
                    "§7#stage§r — put the line INTO resend history without running it — press R when you want it",
                    "§7#unstage [n]§r — remove the newest n (default 1) entries from resend history; reports what the next resend is",
                    "§7/echo§r — show text only to yourself, sends nothing: /echo y is $client.y$. Colors with &-codes: /echo &aall good &7($client.health$ hp) — \\& for a literal &",
                    "§7Prefixes combine: #norecord #silent /say hi",
            };
            case "scripts" -> new String[]{
                    "§bTick scripts (Mod Menu → Tupenter → Scripts):",
                    "§7One-line scripts that run EVERY TICK (20x/s) while the master toggle is on — a walking mcfunction file.",
                    "§7Guard them:§r #if ($client.nbt.Health$ < 6) (/give @s totem_of_undying) — unguarded commands flood multiplayer chat.",
                    "§7Live tuning:§r reference $maxy$ in a script, change it anytime with #set $maxy$ = 80",
                    "§7Disable one script with its toggle (or // prefix) · errors report once and pause that script until edited",
                    "§7Tick scripts never touch resend history and never print #set notices.",
                    "§7Panic:§r /tupenter abort — also flips the master toggle off",
            };
            default -> new String[]{
                    "§bTupenter help — pick a topic:",
                    "§7/tupenter help expressions§r — $...$ math, text, conditions, functions",
                    "§7/tupenter help variables§r — #set, #local, client.*/world.*/nbt paths, groups",
                    "§7/tupenter help flow§r — && chains, #repeat, #for, #foreach, #if/#elseif",
                    "§7/tupenter help prefixes§r — #silent, #norecord, #stage, /echo",
                    "§7/tupenter help scripts§r — the every-tick Scripts tab",
                    "§7/tupenter help command [name]§r — the mod's commands, with per-command detail pages",
                    "§7/customcommand help§r — make your own commands (typed params, autocomplete)",
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
                    "§7/tupenter§r — abort · vars · var save/delete · dump · help",
                    "§7/customcommand§r — add · update · remove · list · make your own commands",
                    "§7/echo <text>§r — local-only output, &-colors, evaluates $...$",
                    "§7/calc <expr>§r · §7/$ expr $§r — local calculator / top-down shorthand",
                    "§7/unroll <line>§r — dry-run debugger",
                    "§7Keybinds (Options → Controls):§r resend key (default R) · open config · toggle message tracking",
            };
            case "tupenter" -> new String[]{
                    "§b/tupenter — mod control:",
                    "§7abort§r — stop all running scripts + the resend queue, and disable tick scripts (panic switch)",
                    "§7vars [group]§r — variables overview, or one group with live values",
                    "§7var save <name>§r — make a #set variable persistent · §7var delete <name>§r — remove it",
                    "§7dump [client|target] [path]§r — browse entity NBT (the data behind client.nbt.* / target.nbt.*)",
                    "§7help <topic>§r — topics: expressions, variables, flow, prefixes, scripts, command [name]",
            };
            case "customcommand" -> new String[]{
                    "§b/customcommand — make your own commands:",
                    "§7add <name> <body>§r — create · §7update <name> <body>§r — edit · §7remove <name>§r — delete",
                    "§7list [verbose]§r — signatures (verbose: full bodies) · §7/customcommand <name>§r — one command + [edit] link",
                    "§7add/update with a name but no body§r puts the existing definition in your chat bar for editing",
                    "§7Full guide§r (typed params, defaults, examples): /customcommand help",
            };
            case "echo" -> new String[]{
                    "§b/echo <text> — show text only to yourself (nothing is sent):",
                    "§7$...$§r evaluates first: /echo y is $client.y$",
                    "§7&-codes color the text from that point on, until the next code or &r:",
                    "§7Colors:§r §0&0§1&1§2&2§3&3§4&4§5&5§6&6§7&7§8&8§9&9§a&a§b&b§c&c§d&d§e&e§f&f§r §7(0-9, a-f)",
                    "§7Formats:§r &l §lbold§r§7 · &o §oitalic§r§7 · &n §nunderline§r§7 · &m §mstrike§r§7 · &k obfuscated · &r reset",
                    "§7\\&§r prints a literal & · codes work from variables too: #set $ok$ = \"&aOK\"",
                    "§7Example:§r /echo &ahp $client.health$ &7/ 20",
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
                    "§cNo command page for '" + rawName + "' — try: all, tupenter, customcommand, echo, calc, unroll",
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
        context.getSource().sendFeedback(Component.literal(" body: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(definition.body()).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private static int runEchoCommand(CommandContext<FabricClientCommandSource> context) {
        String text = StringArgumentType.getString(context, "message");
        if (TupenterConfig.INSTANCE.enhancedCommandParsingEnabled
                && TupenterConfig.INSTANCE.numberMathMode != net.tupenter.script.NumberMathMode.DISABLED) {
            try {
                text = MathEvaluator.applyNumberMath(text, net.tupenter.script.NumberMathMode.EXPLICIT_ONLY,
                        new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER));
            } catch (IllegalArgumentException ex) {
                context.getSource().sendError(Component.literal(ex.getMessage()));
                return 0;
            }
        }
        context.getSource().sendFeedback(Component.literal(applyAmpersandColors(text)).withStyle(ChatFormatting.GRAY));
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
                "§7Types:§r <name> or <name:string> = a word or \"anything quoted\" · <n:int> / <n:float> = numbers · <n:word> = one plain token (letters/digits/_-.+ only — no selectors!) · <n:selector> = @e[...] with tab-complete · <n:player> = player name · <n:text> = rest of the line (must be last) · <n:opt1,opt2,...> = one of a fixed list, tab-completed",
                "§7Position types:§r <n:pos> = whole x y z with ~ support and targeted-block tab-complete · <n:vec3> = decimal x y z with ~ · <n:column_pos> = whole x z with ~ · <n:rotation> = yaw pitch with ~ · <n:angle> = one yaw with ~. Tuples bind $n$ = the joined coords plus $n.x$ $n.y$ $n.z$ (or $n.yaw$ $n.pitch$); angle binds a number.",
                "§7More types:§r <n:time> = duration (10t / 1.5s / 3d), binds as ticks · <n:dimension> = dimension id, tab-completed · <n:color> = chat color, tab-completed · <n:id> = any namespaced id · <n:item> / <n:block> = item or block with full registry tab-complete (including [components] / [states]) · <n:itemset> / <n:blockset> = an item/block OR a #tag like #minecraft:logs, tab-completed",
                "§7Optional params:§r add =default to make a param optional: <r:int=5>, <p:pos=~ ~ ~>. Defaults may hold $...$ expressions (evaluated when omitted, earlier params visible). Strictly-typed optionals can even be skipped mid-command — /portal to_nether works with <p:pos=~ ~ ~> <dim:...> because to_nether isn't a coordinate. Loose types (string/word/text) always grab the next arg, so put those last.",
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
            value = MathEvaluator.evaluateValue(expression, new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER));
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
            String result = MathEvaluator.evaluateForDisplay(expression, new EvalContext(SCRIPT_RANDOM, VARIABLE_REGISTRY, TAG_RESOLVER));
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
