package net.tupenter.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.AngleArgument;
import net.minecraft.commands.arguments.ColorArgument;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.tupenter.TupenterMod;
import net.tupenter.script.AliasDefinition;

import java.lang.reflect.Field;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Builds Brigadier trees for custom commands (typed params get real
 * autocomplete prompts) and registers/unregisters them dynamically so
 * add/remove takes effect without relaunching.
 */
public final class ClientCommandRegistrar {

    private ClientCommandRegistrar() {
    }

    /** Tree for the Fabric client-command dispatcher (executes the alias). */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildAliasNode(String name, AliasDefinition definition, CommandBuildContext buildContext) {
        LiteralArgumentBuilder<FabricClientCommandSource> node = literal(name);

        if (definition.params().isEmpty()) {
            node.executes(context -> sendAliasCommand(name, ""))
                    .then(argument("args", StringArgumentType.greedyString())
                            .executes(context -> sendAliasCommand(name, StringArgumentType.getString(context, "args"))));
            return node;
        }

        Command<FabricClientCommandSource> terminal = context -> sendAliasCommand(name, collectArgs(context, definition));
        if (allOptionalFrom(definition, 0)) {
            node.executes(terminal);
        }
        for (ArgumentBuilder<FabricClientCommandSource, ?> branch : paramBranches(definition, buildContext, 0, terminal)) {
            node.then(branch);
        }
        return node;
    }

    /**
     * Builds the subtree for params[index..]. Optional params add a sibling
     * branch where they're skipped (so /portal to_overworld parses even though
     * the pos param comes first), and every node whose remaining params are
     * all optional can execute.
     */
    private static <S extends SharedSuggestionProvider> java.util.List<ArgumentBuilder<S, ?>> paramBranches(
            AliasDefinition definition, CommandBuildContext buildContext, int index, Command<S> terminal) {
        java.util.List<ArgumentBuilder<S, ?>> branches = new java.util.ArrayList<>();
        if (index >= definition.params().size()) {
            return branches;
        }
        AliasDefinition.Param param = definition.params().get(index);
        RequiredArgumentBuilder<S, ?> arg = RequiredArgumentBuilder.argument(param.name(), argumentTypeFor(param.type(), buildContext));
        if (param.type() == AliasDefinition.ParamType.PLAYER) {
            arg.suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder));
        } else if (param.type() == AliasDefinition.ParamType.CHOICE) {
            arg.suggests((context, builder) -> SharedSuggestionProvider.suggest(param.options(), builder));
        }
        if (terminal != null && allOptionalFrom(definition, index + 1)) {
            arg.executes(terminal);
        }
        for (ArgumentBuilder<S, ?> tail : paramBranches(definition, buildContext, index + 1, terminal)) {
            arg.then(tail);
        }
        branches.add(arg);
        if (param.optional()) {
            branches.addAll(paramBranches(definition, buildContext, index + 1, terminal));
        }
        return branches;
    }

    private static boolean allOptionalFrom(AliasDefinition definition, int index) {
        for (int i = index; i < definition.params().size(); i++) {
            if (!definition.params().get(i).optional()) {
                return false;
            }
        }
        return true;
    }

    private static ArgumentType<?> argumentTypeFor(AliasDefinition.ParamType type, CommandBuildContext buildContext) {
        if (buildContext == null && (type == AliasDefinition.ParamType.ITEM || type == AliasDefinition.ParamType.BLOCK)) {
            // no registries yet (shouldn't happen — trees are built per-connection); degrade to plain text
            TupenterMod.LOGGER.warn("No registry context for an item/block parameter — falling back to a plain string argument");
            return StringArgumentType.string();
        }
        ArgumentType<?> strict = strictArgumentTypeFor(type, buildContext);
        if (type == AliasDefinition.ParamType.TEXT) {
            return strict; // greedy — already accepts $...$ arguments
        }
        int tokens = switch (type) {
            case POS, VEC3 -> 3;
            case COLUMN_POS, ROTATION -> 2;
            default -> 1;
        };
        // $...$ expression arguments must get past Brigadier so the script
        // parser can evaluate them: /replacenearcube $rand(1,15)$ ...
        return new MarkerTolerantArgumentType(strict, tokens);
    }

    private static ArgumentType<?> strictArgumentTypeFor(AliasDefinition.ParamType type, CommandBuildContext buildContext) {
        return switch (type) {
            case INT -> IntegerArgumentType.integer();
            case FLOAT -> DoubleArgumentType.doubleArg();
            case STRING -> StringArgumentType.string(); // bare word or "quoted anything"
            case TEXT -> StringArgumentType.greedyString();
            case WORD, PLAYER, CHOICE -> StringArgumentType.word();
            case SELECTOR -> EntityArgument.entities();
            case POS -> BlockPosArgument.blockPos(); // ~ support + targeted-block suggestions
            case VEC3 -> Vec3Argument.vec3();
            case COLUMN_POS -> ColumnPosArgument.columnPos();
            case ROTATION -> RotationArgument.rotation();
            case ANGLE -> AngleArgument.angle();
            case TIME -> TimeArgument.time();
            case DIMENSION -> DimensionArgument.dimension(); // suggests the dimensions the client knows
            case COLOR -> ColorArgument.color();
            case ID -> ResourceLocationArgument.id();
            case ITEM -> ItemArgument.item(buildContext);      // registry tab-complete incl. [components]
            case BLOCK -> BlockStateArgument.block(buildContext); // registry tab-complete incl. [states]
        };
    }

    /**
     * Rebuilds the argument string exactly as typed, via each argument's
     * consumed input range — so selectors, quoted strings, etc. round-trip
     * verbatim into the packet the script parser re-tokenizes.
     */
    private static String collectArgs(CommandContext<FabricClientCommandSource> context, AliasDefinition definition) {
        StringBuilder args = new StringBuilder();
        for (AliasDefinition.Param param : definition.params()) {
            String raw = rawArgument(context, param.name());
            if (raw == null) {
                continue; // omitted optional — the script parser binds its default
            }
            if (args.length() > 0) {
                args.append(' ');
            }
            args.append(raw);
        }
        return args.toString();
    }

    private static String rawArgument(CommandContext<FabricClientCommandSource> context, String name) {
        for (ParsedCommandNode<FabricClientCommandSource> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof ArgumentCommandNode<?, ?> argument && argument.getName().equals(name)) {
                return parsed.getRange().get(context.getInput());
            }
        }
        return null; // not in the parse — an omitted optional param
    }

    /**
     * Sends the alias invocation as a raw command packet — MixinConnection
     * intercepts it and the script parser expands the alias with the args.
     */
    private static int sendAliasCommand(String name, String args) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        String command = args.isBlank() ? name : name + " " + args.trim();
        client.player.connection.getConnection().send(new ServerboundChatCommandPacket(command));
        return 1;
    }

    /** Registers (or replaces) an alias in the live dispatchers — no relaunch. */
    public static void registerDynamic(String name, AliasDefinition definition) {
        CommandBuildContext buildContext = currentBuildContext();

        CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommandManager.getActiveDispatcher();
        if (dispatcher != null) {
            removeNode(dispatcher.getRoot(), name);
            dispatcher.register(buildAliasNode(name, definition, buildContext));
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            removeNode(connection.getCommands().getRoot(), name);
            connection.getCommands().getRoot().addChild(buildSuggestionNode(name, definition, buildContext).build());
        }
    }

    /** Registry context from the live connection (item/block params need it); null when not in-game. */
    public static CommandBuildContext currentBuildContext() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return null;
        }
        return CommandBuildContext.simple(connection.registryAccess(), connection.enabledFeatures());
    }

    /** Removes an alias from the live dispatchers. Best-effort (reflection). */
    public static boolean unregisterDynamic(String name) {
        boolean removed = false;
        CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommandManager.getActiveDispatcher();
        if (dispatcher != null) {
            removed = removeNode(dispatcher.getRoot(), name);
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            removed |= removeNode(connection.getCommands().getRoot(), name);
        }
        return removed;
    }

    /** Suggestion-only tree for the vanilla command tree (drives tab-complete). */
    private static LiteralArgumentBuilder<ClientSuggestionProvider> buildSuggestionNode(String name, AliasDefinition definition, CommandBuildContext buildContext) {
        LiteralArgumentBuilder<ClientSuggestionProvider> node = LiteralArgumentBuilder.literal(name);

        if (definition.params().isEmpty()) {
            node.then(RequiredArgumentBuilder.<ClientSuggestionProvider, String>argument("args", StringArgumentType.greedyString()));
            return node;
        }

        for (ArgumentBuilder<ClientSuggestionProvider, ?> branch
                : ClientCommandRegistrar.<ClientSuggestionProvider>paramBranches(definition, buildContext, 0, null)) {
            node.then(branch);
        }
        return node;
    }

    /**
     * Wraps a strict Brigadier type so $...$ expression arguments parse:
     * when the upcoming input holds a marker, the tokens are consumed as
     * opaque text (markers are atomic even with spaces inside, and the
     * script parser evaluates them after the packet is intercepted).
     * Marker-free input still gets the wrapped type's validation, and
     * suggestions always delegate. {@code tokens} > 1 covers multi-token
     * types like pos (three coordinates).
     */
    private static final class MarkerTolerantArgumentType implements ArgumentType<Object> {
        private final ArgumentType<?> wrapped;
        private final int tokens;

        MarkerTolerantArgumentType(ArgumentType<?> wrapped, int tokens) {
            this.wrapped = wrapped;
            this.tokens = tokens;
        }

        @Override
        public Object parse(com.mojang.brigadier.StringReader reader) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            if (!upcomingTokensContainMarker(reader)) {
                return wrapped.parse(reader);
            }
            return parseLenient(reader);
        }

        @Override
        public <S> Object parse(com.mojang.brigadier.StringReader reader, S source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            if (!upcomingTokensContainMarker(reader)) {
                return wrapped.parse(reader, source);
            }
            return parseLenient(reader);
        }

        private String parseLenient(com.mojang.brigadier.StringReader reader) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            int start = reader.getCursor();
            for (int i = 0; i < tokens; i++) {
                if (i > 0) {
                    if (!reader.canRead() || reader.peek() != ' ') {
                        throw com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS
                                .dispatcherExpectedArgumentSeparator().createWithContext(reader);
                    }
                    reader.skip();
                }
                consumeToken(reader);
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        /** Scans only the text this argument would consume for a $...$ marker. */
        private boolean upcomingTokensContainMarker(com.mojang.brigadier.StringReader reader) {
            String text = reader.getRemaining();
            boolean marker = false;
            boolean quoted = false;
            int depth = 0;
            int completed = 0;
            boolean sawMarker = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '$') {
                    marker = !marker;
                    sawMarker = true;
                    continue;
                }
                if (marker) {
                    continue;
                }
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted) {
                    if (c == '[' || c == '{') {
                        depth++;
                    } else if (c == ']' || c == '}') {
                        depth = Math.max(0, depth - 1);
                    } else if (c == ' ' && depth == 0) {
                        completed++;
                        if (completed >= tokens) {
                            return sawMarker;
                        }
                    }
                }
            }
            return sawMarker;
        }

        /** One argument token; $...$ spans, "..." quotes, and [..]/{..} are atomic. */
        private static void consumeToken(com.mojang.brigadier.StringReader reader) {
            boolean marker = false;
            boolean quoted = false;
            int depth = 0;
            while (reader.canRead()) {
                char c = reader.peek();
                if (c == '\\') {
                    reader.skip();
                    if (reader.canRead()) {
                        reader.skip();
                    }
                    continue;
                }
                if (c == '$') {
                    marker = !marker;
                } else if (!marker) {
                    if (c == '"') {
                        quoted = !quoted;
                    } else if (!quoted) {
                        if (c == '[' || c == '{') {
                            depth++;
                        } else if (c == ']' || c == '}') {
                            depth = Math.max(0, depth - 1);
                        } else if (c == ' ' && depth == 0) {
                            return;
                        }
                    }
                }
                reader.skip();
            }
        }

        @Override
        public <S> java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> listSuggestions(
                CommandContext<S> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
            return wrapped.listSuggestions(context, builder);
        }

        @Override
        public java.util.Collection<String> getExamples() {
            return wrapped.getExamples();
        }
    }

    /**
     * Brigadier has no removal API; reach into the node's maps. Failure is
     * non-fatal — the stale node just lingers until relaunch.
     */
    private static boolean removeNode(CommandNode<?> root, String name) {
        try {
            boolean removed = false;
            for (String fieldName : new String[]{"children", "literals", "arguments"}) {
                Field field = CommandNode.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) field.get(root);
                if (map.remove(name) != null) {
                    removed = true;
                }
            }
            return removed;
        } catch (ReflectiveOperationException | ClassCastException ex) {
            TupenterMod.LOGGER.warn("Could not remove command node '{}' dynamically: {}", name, ex.toString());
            return false;
        }
    }
}
