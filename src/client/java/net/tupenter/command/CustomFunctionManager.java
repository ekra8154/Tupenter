package net.tupenter.command;

import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;
import net.tupenter.script.FunctionResolver;
import net.tupenter.script.UserFunctions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-defined value functions — the {@code /customfunction} side of the house.
 * A function is stored like a custom command ({@code name <params> = body}) and
 * called inside {@code $...$} the way you call {@code min} or {@code sqrt}:
 * {@code $lightlevel()$}, {@code $dist(a, b)$}. The body returns a value — either
 * a single expression, or a statement block ({@code #set}/{@code #for}/
 * {@code #while}/{@code #if}/{@code #return}); see {@link UserFunctions}. No
 * commands or chat — that's {@code /customcommand}.
 */
public final class CustomFunctionManager {
    /** Names owned by the expression evaluator — a function can't shadow these. */
    private static final Set<String> BUILTINS = Set.of(
            "int", "float", "abs", "floor", "ceil", "round", "min", "max", "len", "nth", "contains", "indexof",
            "trim", "upper", "lower", "substr", "replace", "rand", "randf", "sin", "cos", "tan", "sqrt", "range",
            "itemset", "blockset", "effectset", "entityset", "block", "pick", "vec", "x", "y", "z",
            "raycast", "raycast_block", "entity_nbt", "entity_type", "entity_raycast", "entities", "nearest_entity",
            "slot_item", "slot_count", "slot_durability",
            "true", "false");

    /** Statement directives allowed as the head of a statement-body function. */
    private static final Set<String> STATEMENT_BODY_DIRECTIVES = Set.of(
            "#set", "#local", "#setdefault", "#for", "#foreach", "#while", "#if", "#return");

    private CustomFunctionManager() {
    }

    /** The leading directive word of a body (lowercased), e.g. "#set" — used for save-time validation. */
    private static String firstDirectiveWord(String body) {
        int end = 0;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end)) && body.charAt(end) != '(') {
            end++;
        }
        return body.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    /** Name → parsed definition (params + body expression). Invalid entries are skipped. */
    public static Map<String, AliasDefinition> getFunctionMap() {
        LinkedHashMap<String, AliasDefinition> functions = new LinkedHashMap<>();
        for (String definition : TupenterConfig.INSTANCE.functions) {
            ParsedFunction parsed = parseDefinition(definition);
            if (parsed != null) {
                functions.put(parsed.name(), parsed.definition());
            }
        }
        return functions;
    }

    public static List<String> getFunctionDefinitions() {
        return new ArrayList<>(TupenterConfig.INSTANCE.functions);
    }

    public static boolean hasFunction(String rawName) {
        return getFunctionMap().containsKey(CommandAliasManager.normalizeName(rawName));
    }

    public static String addFunction(String rawName, String rawBody) {
        String name = CommandAliasManager.normalizeName(rawName);
        if (hasFunction(name)) {
            throw new IllegalArgumentException(name + "() already exists — use /customfunction update " + name + " ...");
        }
        return saveFunction(name, rawBody);
    }

    public static String updateFunction(String rawName, String rawBody) {
        String name = CommandAliasManager.normalizeName(rawName);
        if (!hasFunction(name)) {
            throw new IllegalArgumentException(name + "() doesn't exist — use /customfunction add " + name + " ...");
        }
        return saveFunction(name, rawBody);
    }

    private static String saveFunction(String name, String rawBody) {
        validateName(name);
        String body = rawBody.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("A function body cannot be empty");
        }
        AliasDefinition parsed = AliasDefinition.parse(body); // validates <params> + non-empty body
        String expr = parsed.body().trim();
        // A body may now be an expression OR a statement body (loops/locals/#return, etc.).
        // Still reject a bare command; runtime enforces the finer rules (#wait, #silent, ...).
        if (expr.startsWith("/")) {
            throw new IllegalArgumentException("A function body can't be a command — it must compute a value. Did you mean /customcommand?");
        }
        if (expr.startsWith("#") && !STATEMENT_BODY_DIRECTIVES.contains(firstDirectiveWord(expr))) {
            throw new IllegalArgumentException("A function body must be an EXPRESSION or use statement directives (#set, #for, #if, #return, ...), not '"
                    + firstDirectiveWord(expr) + "' — did you mean /customcommand?");
        }

        List<String> updated = new ArrayList<>();
        boolean replaced = false;
        for (String definition : TupenterConfig.INSTANCE.functions) {
            ParsedFunction parsedExisting = parseDefinition(definition);
            if (parsedExisting != null && parsedExisting.name().equals(name)) {
                updated.add(formatDefinition(name, body));
                replaced = true;
            } else {
                updated.add(definition);
            }
        }
        if (!replaced) {
            updated.add(formatDefinition(name, body));
        }
        TupenterConfig.INSTANCE.functions = updated;
        TupenterConfig.save();
        return name;
    }

    public static boolean removeFunction(String rawName) {
        String name = CommandAliasManager.normalizeName(rawName);
        List<String> updated = new ArrayList<>();
        boolean removed = false;
        for (String definition : TupenterConfig.INSTANCE.functions) {
            ParsedFunction parsed = parseDefinition(definition);
            if (parsed != null && parsed.name().equals(name)) {
                removed = true;
            } else {
                updated.add(definition);
            }
        }
        if (removed) {
            TupenterConfig.INSTANCE.functions = updated;
            TupenterConfig.save();
        }
        return removed;
    }

    /** The stored body expression of a function (what /customfunction update prefills), or null. */
    public static String getRawBody(String rawName) {
        AliasDefinition def = getFunctionMap().get(CommandAliasManager.normalizeName(rawName));
        return def == null ? null : def.body();
    }

    /** The full stored line ("name <decls...> = body") for a function, or null — what [edit] links re-offer. */
    public static String getStoredDefinition(String rawName) {
        String name = CommandAliasManager.normalizeName(rawName);
        for (String definition : TupenterConfig.INSTANCE.functions) {
            ParsedFunction parsed = parseDefinition(definition);
            if (parsed != null && parsed.name().equals(name)) {
                return toSingleLine(definition).trim();
            }
        }
        return null;
    }

    private static String formatDefinition(String name, String body) {
        try {
            AliasDefinition parsed = AliasDefinition.parse(toSingleLine(body).trim());
            StringBuilder signature = new StringBuilder(name);
            String declarations = parsed.declarationPrefix().trim();
            if (!declarations.isEmpty()) {
                signature.append(' ').append(declarations); // keep the params! (dist <a:vec3> <b:vec3>)
            }
            if (!parsed.description().isEmpty()) {
                signature.append(' ').append(parsed.descriptionSuffix());
            }
            return signature + " = " + parsed.body();
        } catch (IllegalArgumentException ignored) {
            // unparseable — round-trip the plain form
        }
        return name + " = " + body;
    }

    public static ParsedFunction parseDefinition(String definition) {
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
            String name = CommandAliasManager.normalizeName(rawName);
            validateName(name);
            return new ParsedFunction(name, AliasDefinition.parse(rest));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void validateName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Function name cannot be empty");
        }
        if (BUILTINS.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is a built-in function — pick another name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                throw new IllegalArgumentException("Function names may only contain letters, numbers, and _");
            }
        }
    }

    private static String toSingleLine(String text) {
        return text.replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }

    private static int firstSpace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The evaluator hook: resolves {@code name(args)} against the current function
     * set. The actual call machinery (binding, body eval, recursion guard) lives
     * in {@link UserFunctions} on the main source set so unit tests run the same
     * code path the game does.
     */
    public static FunctionResolver resolver() {
        return UserFunctions.resolver(getFunctionMap(), TupenterConfig.INSTANCE.maxLoopIterations);
    }

    public record ParsedFunction(String name, AliasDefinition definition) {
    }
}
