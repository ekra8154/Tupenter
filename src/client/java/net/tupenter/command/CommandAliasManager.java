package net.tupenter.command;

import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandAliasManager {
    private CommandAliasManager() {
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
            String declarations = parsed.declarationPrefix().trim();
            if (!declarations.isEmpty()) {
                return name + " " + declarations + " = " + parsed.body();
            }
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
                Split split = split(definition);
                // declarations + body, whichever side of the = they were on —
                // this is what /customcommand update <name> ... prefills with
                return split.declarations().isEmpty()
                        ? split.body()
                        : split.declarations() + " " + split.body();
            }
        }
        return null;
    }

    /** Newlines in stored definitions are formatting only — runtime always sees one line. */
    private static String toSingleLine(String text) {
        return text.replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }

    /** A stored definition cut at its signature separator (see {@link AliasDefinition#signatureSeparator}). */
    private record Split(String rawName, String declarations, String body) {
    }

    /**
     * Splits a stored definition around the first {@code =} outside
     * declarations and markers: {@code name <params> = body}. Declarations
     * that lead the body instead also work, because AliasDefinition.parse
     * strips them there (that's the /customcommand add syntax).
     */
    private static Split split(String definition) {
        String single = toSingleLine(definition);
        int separator = AliasDefinition.signatureSeparator(single);
        if (separator < 0) {
            return null;
        }
        String signature = single.substring(0, separator).trim();
        String body = single.substring(separator + 1).trim();

        int space = -1;
        for (int i = 0; i < signature.length(); i++) {
            if (Character.isWhitespace(signature.charAt(i))) {
                space = i;
                break;
            }
        }
        String rawName = space >= 0 ? signature.substring(0, space) : signature;
        String declarations = space >= 0 ? signature.substring(space).trim() : "";
        return new Split(rawName, declarations, body);
    }

    public static ParsedAlias parseDefinition(String definition) {
        if (definition == null) {
            return null;
        }
        Split split = split(definition);
        if (split == null || split.rawName().isEmpty() || split.body().isEmpty()) {
            return null;
        }
        String rawCommand = split.declarations().isEmpty()
                ? split.body()
                : split.declarations() + " " + split.body();

        try {
            String name = normalizeName(split.rawName());
            validateName(name);
            return new ParsedAlias(name, AliasDefinition.parse(rawCommand));
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

        if ("alias".equals(name) || "calc".equals(name) || "customcommand".equals(name) || "tupenter".equals(name) || "echo".equals(name)
                || "list".equals(name) || "verbose".equals(name) || "help".equals(name) || "add".equals(name) || "remove".equals(name)
                || "update".equals(name) || "unroll".equals(name)) {
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
