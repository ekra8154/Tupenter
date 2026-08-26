package net.tupenter.command;

import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandAliasManager {

    /**
     * Every command the mod registers itself. Custom commands go into the SAME
     * Brigadier dispatcher, and registering a duplicate literal merges onto the
     * existing node — so a custom command named "echohud" doesn't sit beside
     * /echohud, it clobbers it. This list is what stops that, and it's the one
     * place the names live: TupenterModClient's help pages read it too, and a
     * test holds it against the literals actually registered.
     */
    public static final List<String> MOD_COMMANDS = List.of(
            "calc", "customcommand", "customfunction", "echo", "echohud", "tupenter", "unroll");

    /**
     * Subcommand words that would read as ambiguous at the front of a line,
     * plus "alias" — the pre-1.0 name of /customcommand.
     */
    private static final java.util.Set<String> RESERVED_WORDS = java.util.Set.of(
            "alias", "list", "verbose", "help", "add", "remove", "update");

    /**
     * The vanilla singleplayer command set (1.21). A custom command with one of
     * these names is ALLOWED — overriding a vanilla command is a real use, and
     * the client can't know a given server's command set anyway (plugins vary,
     * and aliases are defined before joining). But client commands are matched
     * before the packet reaches the server, so naming one "tp" quietly takes
     * over vanilla /tp — a heads-up, not a wall. See {@link #vanillaShadowWarning}.
     */
    private static final java.util.Set<String> VANILLA_COMMANDS = java.util.Set.of(
            "advancement", "attribute", "ban", "banlist", "bossbar", "clear", "clone", "damage",
            "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect", "enchant",
            "execute", "experience", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule",
            "give", "item", "jfr", "kick", "kill", "locate", "loot", "me", "msg", "op", "pardon",
            "particle", "place", "playsound", "publish", "random", "recipe", "reload", "return", "ride",
            "rotate", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
            "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon",
            "tag", "team", "teammsg", "teleport", "tell", "tellraw", "test", "tick", "time", "title",
            "tp", "transfer", "trigger", "weather", "whitelist", "worldborder", "xp");

    private CommandAliasManager() {
    }

    /**
     * A heads-up if {@code rawName} would override a vanilla command, else null.
     * Not a rejection — the command is still created; this is what the UI shows
     * so the override is a choice, not a surprise.
     */
    public static String vanillaShadowWarning(String rawName) {
        String name = normalizeName(rawName);
        if (!VANILLA_COMMANDS.contains(name)) {
            return null;
        }
        return "Heads up: /" + name + " is a vanilla command. Your custom /" + name
                + " runs on the client BEFORE the line reaches the server, so on any server that has /"
                + name + " it takes over — that's intended for overriding, but rename if you didn't mean to.";
    }

    /** Name → parsed definition (body + typed params). Invalid entries are skipped. */
    public static Map<String, AliasDefinition> getAliasMap() {
        LinkedHashMap<String, AliasDefinition> aliases = new LinkedHashMap<>();
        for (String definition : TupenterConfig.INSTANCE.aliases) {
            ParsedAlias parsed = parseDefinition(definition);
            if (parsed != null) {
                aliases.put(parsed.name(), parsed.definition());
            }
        }
        return aliases;
    }

    public static List<String> getAliasDefinitions() {
        return new ArrayList<>(TupenterConfig.INSTANCE.aliases);
    }

    /** Saves a NEW alias; throws if the name is already taken (use updateAlias). */
    public static String addAlias(String rawName, String rawCommand) {
        String name = normalizeName(rawName);
        if (hasAlias(name)) {
            throw new IllegalArgumentException("/" + name + " already exists — use /customcommand update " + name + " ...");
        }
        return saveAlias(name, rawCommand);
    }

    /** Replaces an EXISTING alias (body and/or signature); throws if it doesn't exist. */
    public static String updateAlias(String rawName, String rawCommand) {
        String name = normalizeName(rawName);
        if (!hasAlias(name)) {
            throw new IllegalArgumentException("/" + name + " doesn't exist — use /customcommand add " + name + " ...");
        }
        return saveAlias(name, rawCommand);
    }

    public static boolean hasAlias(String rawName) {
        return getAliasMap().containsKey(normalizeName(rawName));
    }

    private static String saveAlias(String name, String rawCommand) {
        validateName(name);

        String command = rawCommand.trim();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Custom command body cannot be empty");
        }
        AliasDefinition.parse(command); // validates param declarations + body

        List<String> updated = new ArrayList<>();
        boolean replaced = false;

        for (String definition : TupenterConfig.INSTANCE.aliases) {
            ParsedAlias parsed = parseDefinition(definition);
            if (parsed != null && parsed.name().equals(name)) {
                updated.add(formatDefinition(name, command));
                replaced = true;
            } else {
                updated.add(definition);
            }
        }

        if (!replaced) {
            updated.add(formatDefinition(name, command));
        }

        TupenterConfig.INSTANCE.aliases = updated;
        TupenterConfig.save();
        return name;
    }

    public static boolean removeAlias(String rawName) {
        String name = normalizeName(rawName);
        List<String> updated = new ArrayList<>();
        boolean removed = false;

        for (String definition : TupenterConfig.INSTANCE.aliases) {
            ParsedAlias parsed = parseDefinition(definition);
            if (parsed != null && parsed.name().equals(name)) {
                removed = true;
            } else {
                updated.add(definition);
            }
        }

        if (removed) {
            TupenterConfig.INSTANCE.aliases = updated;
            TupenterConfig.save();
        }

        return removed;
    }

    /**
     * Canonical stored form is signature = body — parameter declarations sit
     * with the name, like a function definition:
     * {@code blink <distance:int=5> = /attribute ...}
     */
    public static String formatDefinition(String name, String command) {
        try {
            AliasDefinition parsed = AliasDefinition.parse(toSingleLine(command).trim());
            StringBuilder signature = new StringBuilder(name);
            String declarations = parsed.declarationPrefix().trim();
            if (!declarations.isEmpty()) {
                signature.append(' ').append(declarations);
            }
            if (!parsed.description().isEmpty()) {
                signature.append(' ').append(parsed.descriptionSuffix());
            }
            return signature + " = " + parsed.body();
        } catch (IllegalArgumentException ignored) {
            // unparseable — store as typed; the plain form at least round-trips
        }
        return name + " = " + command;
    }

    /**
     * The raw body text of an alias as a single chat-safe line, or null if it
     * doesn't exist. Newlines (Mod Menu formatting) collapse to spaces.
     */
    public static String getRawCommand(String rawName) {
        String name = normalizeName(rawName);
        for (String definition : TupenterConfig.INSTANCE.aliases) {
            ParsedAlias parsed = parseDefinition(definition);
            if (parsed != null && parsed.name().equals(name)) {
                // everything after the name: <decls> "desc" = body — what
                // /customcommand update <name> ... prefills with (description kept)
                String single = toSingleLine(definition).trim();
                int space = firstSpace(single);
                return space < 0 ? "" : single.substring(space + 1).trim();
            }
        }
        return null;
    }

    /** Newlines in stored definitions are formatting only — runtime always sees one line. */
    private static String toSingleLine(String text) {
        return net.tupenter.script.Comments.flatten(text);
    }

    private static int firstSpace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    public static ParsedAlias parseDefinition(String definition) {
        if (definition == null) {
            return null;
        }
        String single = toSingleLine(definition).trim();
        int space = firstSpace(single);
        if (space < 0) {
            return null; // just a name, no body
        }
        String rawName = single.substring(0, space);
        String rest = single.substring(space + 1).trim();
        if (rest.isEmpty()) {
            return null;
        }

        try {
            String name = normalizeName(rawName);
            validateName(name);
            return new ParsedAlias(name, AliasDefinition.parse(rest)); // parse handles <decls> "desc" = body
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static String normalizeName(String rawName) {
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1).trim();
        }
        return name;
    }

    private static void validateName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Custom command name cannot be empty");
        }

        if (MOD_COMMANDS.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is one of Tupenter's own commands — "
                    + "a custom command by that name would replace it. Pick another name.");
        }
        if (RESERVED_WORDS.contains(name)) {
            throw new IllegalArgumentException("That custom command name is reserved");
        }

        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            boolean allowed = Character.isLetterOrDigit(current) || current == '_' || current == '-' || current == '.';
            if (!allowed) {
                throw new IllegalArgumentException("Custom command names may only contain letters, numbers, ., _, and -");
            }
        }
    }

    public record ParsedAlias(String name, AliasDefinition definition) {
    }
}
