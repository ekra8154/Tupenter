package net.tupenter.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A custom command body plus its declared parameters
 * (docs/SCRIPTING_DESIGN.md §7), e.g. for
 * {@code /customcommand add smite <target:player> /execute at $target$ ...}
 * the body is "/execute at $target$ ..." and params is [target:PLAYER].
 *
 * Params bind as script-scope variables during expansion: by name
 * ({@code $target$}) and by position ({@code $1$}..{@code $n$}).
 */
public record AliasDefinition(String body, List<Param> params) {

    public static AliasDefinition bodyOnly(String body) {
        return new AliasDefinition(body, List.of());
    }

    /** {@code defaultValue} != null makes the param optional; the text may hold $...$ expressions. */
    public record Param(String name, ParamType type, List<String> options, String defaultValue) {
        public Param(String name, ParamType type) {
            this(name, type, List.of(), null);
        }

        public Param(String name, ParamType type, List<String> options) {
            this(name, type, options, null);
        }

        public boolean optional() {
            return defaultValue != null;
        }
    }

    public enum ParamType {
        INT,
        FLOAT,
        STRING,   // bare word OR "quoted anything" — the default for bare <name>
        WORD,     // one strict token (letters, digits, _-.+)
        TEXT,     // greedy — must be last
        PLAYER,   // word + online-player suggestions in the command UI
        SELECTOR, // entity selector, validated + tab-completed, no quotes needed
        CHOICE,   // fixed set of options: <dim:to_overworld,to_nether>
        POS,      // block position: three whole coords, ~ supported, targeted-block autocomplete
        VEC3,     // precise position: three decimal coords, ~ supported
        COLUMN_POS, // x z column: two whole coords, ~ supported
        ROTATION, // yaw pitch: two decimal coords, ~ supported
        ANGLE,    // single yaw angle, ~ supported
        TIME,     // duration like 10t / 1.5s / 3d (or plain ticks) — binds as ticks
        DIMENSION,// dimension id, tab-completed from the worlds the client knows
        COLOR,    // one of the 16 chat colors, tab-completed
        ID,       // namespaced id (resource location), e.g. minecraft:stone
        ITEM,     // item id + optional [components], registry tab-complete
        BLOCK;    // block id + optional [state], registry tab-complete

        public static ParamType fromKeyword(String keyword) {
            return switch (keyword.toLowerCase(Locale.ROOT)) {
                case "int" -> INT;
                case "float" -> FLOAT;
                case "string" -> STRING;
                case "word" -> WORD;
                case "text" -> TEXT;
                case "player" -> PLAYER;
                case "selector" -> SELECTOR;
                case "pos" -> POS;
                case "vec3" -> VEC3;
                case "column_pos", "column" -> COLUMN_POS;
                case "rotation" -> ROTATION;
                case "angle" -> ANGLE;
                case "time" -> TIME;
                case "dimension" -> DIMENSION;
                case "color" -> COLOR;
                case "id", "resource" -> ID;
                case "item" -> ITEM;
                case "block" -> BLOCK;
                default -> throw new IllegalArgumentException("Unknown parameter type '" + keyword
                        + "' — use int, float, string, word, text, player, selector, pos, vec3, column_pos, rotation, angle, time, dimension, color, id, item, or block");
            };
        }
    }

    /**
     * Splits leading {@code <name:type>} declarations off a raw body string.
     * Throws IllegalArgumentException on malformed declarations.
     */
    public static AliasDefinition parse(String rawBody) {
        String rest = rawBody.trim();
        List<Param> params = new ArrayList<>();

        while (rest.startsWith("<")) {
            // > (and later = ) inside $...$ belong to a default expression, not the declaration
            int close = indexOfOutsideMarkers(rest, '>');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed parameter declaration: " + rest);
            }
            String declaration = rest.substring(1, close).trim();

            String defaultValue = null;
            String head = declaration;
            int eq = indexOfOutsideMarkers(declaration, '=');
            if (eq >= 0) {
                head = declaration.substring(0, eq).trim();
                defaultValue = declaration.substring(eq + 1).trim();
                if (defaultValue.isEmpty()) {
                    throw new IllegalArgumentException("Empty default in <" + declaration + "> — write <name:type=value>");
                }
            }

            int colon = head.indexOf(':');
            String name;
            ParamType type;
            List<String> choices = List.of();
            if (colon < 0) {
                // bare <name> defaults to the quotable string type
                name = head.toLowerCase(Locale.ROOT);
                type = ParamType.STRING;
            } else {
                if (colon == 0 || colon == head.length() - 1) {
                    throw new IllegalArgumentException("Parameter declarations look like <name:type> or just <name>, got <" + declaration + ">");
                }
                name = head.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String typeText = head.substring(colon + 1).trim();
                if (typeText.contains(",")) {
                    // enumerated options: <dim:to_overworld,to_nether>
                    type = ParamType.CHOICE;
                    List<String> parsed = new ArrayList<>();
                    for (String option : typeText.split(",")) {
                        String trimmedOption = option.trim();
                        if (trimmedOption.isEmpty()) {
                            throw new IllegalArgumentException("Empty option in <" + declaration + ">");
                        }
                        parsed.add(trimmedOption);
                    }
                    choices = List.copyOf(parsed);
                } else {
                    type = ParamType.fromKeyword(typeText);
                }
            }

            validateParamName(name, params);
            if (!params.isEmpty() && params.get(params.size() - 1).type() == ParamType.TEXT) {
                throw new IllegalArgumentException("A <name:text> parameter is greedy and must be last");
            }
            params.add(new Param(name, type, choices, defaultValue));
            rest = rest.substring(close + 1).trim();
        }

        if (rest.isEmpty()) {
            throw new IllegalArgumentException("Custom command body cannot be empty");
        }
        return new AliasDefinition(rest, List.copyOf(params));
    }

    /** First index of {@code target} that isn't inside a $...$ span ({@code \} escapes the next char). */
    private static int indexOfOutsideMarkers(String text, char target) {
        boolean inMarker = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                inMarker = !inMarker;
            } else if (c == target && !inMarker) {
                return i;
            }
        }
        return -1;
    }

    private static void validateParamName(String name, List<Param> existing) {
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            throw new IllegalArgumentException("Parameter names must start with a letter");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                throw new IllegalArgumentException("Parameter names may only contain letters, numbers, and _");
            }
        }
        for (Param param : existing) {
            if (param.name().equals(name)) {
                throw new IllegalArgumentException("Duplicate parameter name '" + name + "'");
            }
        }
    }

    /** Rebuilds the declaration prefix, e.g. "<level:int> <target> ". */
    public String declarationPrefix() {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Param param : params) {
            builder.append('<').append(param.name());
            if (param.type() == ParamType.CHOICE) {
                builder.append(':').append(String.join(",", param.options()));
            } else if (param.type() != ParamType.STRING) {
                builder.append(':').append(param.type().name().toLowerCase(Locale.ROOT));
            }
            if (param.defaultValue() != null) {
                builder.append('=').append(param.defaultValue());
            }
            builder.append("> ");
        }
        return builder.toString();
    }
}
