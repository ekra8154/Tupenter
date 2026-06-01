package net.tupenter.command;

import net.tupenter.config.TupenterConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandAliasManager {
    private CommandAliasManager() {
    }

    public static Map<String, String> getAliasMap() {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String definition : TupenterConfig.INSTANCE.aliases) {
            ParsedAlias parsed = parseDefinition(definition);
            if (parsed != null) {
                aliases.put(parsed.name(), parsed.command());
            }
        }
        return aliases;
    }

    public static List<String> getAliasDefinitions() {
        return new ArrayList<>(TupenterConfig.INSTANCE.aliases);
    }

    public static String addAlias(String rawName, String rawCommand) {
        String name = normalizeName(rawName);
        validateName(name);

        String command = normalizeBody(rawCommand);
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

    public static String formatDefinition(String name, String command) {
        return name + " = " + command;
    }

    public static ParsedAlias parseDefinition(String definition) {
        if (definition == null) {
            return null;
        }

        int separator = definition.indexOf('=');
        if (separator < 0) {
            return null;
        }

        String rawName = definition.substring(0, separator).trim();
        String rawCommand = definition.substring(separator + 1).trim();

        if (rawName.isEmpty() || rawCommand.isEmpty()) {
            return null;
        }

        String name;
        try {
            name = normalizeName(rawName);
            validateName(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        try {
            return new ParsedAlias(name, normalizeBody(rawCommand));
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

    public static String normalizeBody(String rawCommand) {
        String command = rawCommand.trim();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Custom command body cannot be empty");
        }
        return command;
    }

    private static void validateName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Custom command name cannot be empty");
        }

        if ("alias".equals(name) || "calc".equals(name) || "customcommand".equals(name)) {
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

    public record ParsedAlias(String name, String command) {
    }
}
