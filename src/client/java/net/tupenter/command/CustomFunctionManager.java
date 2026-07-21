package net.tupenter.command;

import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;
import net.tupenter.script.EvalContext;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.FunctionResolver;
import net.tupenter.script.MathEvaluator;
import net.tupenter.script.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * User-defined expression functions — the {@code /customfunction} side of the
 * house. A function is stored like a custom command ({@code name <params> = body})
 * but its body is an EXPRESSION, and it's called inside {@code $...$} the way you
 * call {@code min} or {@code sqrt}: {@code $lightlevel()$}, {@code $dist(a, b)$}.
 *
 * <p>Slice 1: zero-arg functions. Parameters are parsed/stored (forward-compatible
 * with {@link AliasDefinition}) but rejected at save time until binding lands.
 */
public final class CustomFunctionManager {
    private static final int MAX_DEPTH = 32;

    /** Names owned by the expression evaluator — a function can't shadow these. */
    private static final Set<String> BUILTINS = Set.of(
            "int", "float", "abs", "floor", "ceil", "round", "min", "max", "len", "nth", "contains", "indexof",
            "trim", "upper", "lower", "substr", "replace", "rand", "randf", "sin", "cos", "tan", "sqrt", "range",
            "itemset", "blockset", "effectset", "entityset", "block", "pick", "vec", "true", "false");

    private CustomFunctionManager() {
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
        if (!parsed.params().isEmpty()) {
            throw new IllegalArgumentException("Function parameters aren't supported yet — for now a function is zero-arg, e.g. /customfunction add "
                    + name + " client.light");
        }
        String expr = parsed.body().trim();
        if (expr.startsWith("/") || expr.startsWith("#")) {
            throw new IllegalArgumentException("A function body must be an EXPRESSION that returns a value, not a command or directive — did you mean /customcommand?");
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

    private static String formatDefinition(String name, String body) {
        try {
            AliasDefinition parsed = AliasDefinition.parse(toSingleLine(body).trim());
            return name + " = " + parsed.body();
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
     * set, with a recursion guard. Returns null for an unknown name so the
     * evaluator falls through to its "unknown function" error.
     */
    public static FunctionResolver resolver() {
        Map<String, AliasDefinition> functions = getFunctionMap();
        return new FunctionResolver() {
            private int depth;

            @Override
            public Value call(String name, List<Value> args, EvalContext context) {
                AliasDefinition def = functions.get(name.toLowerCase(Locale.ROOT));
                if (def == null) {
                    return null; // not a user function — let the evaluator report "unknown"
                }
                int arity = def.params().size();
                if (args.size() != arity) {
                    throw new ExpressionException(name + "() takes " + arity + " argument" + (arity == 1 ? "" : "s")
                            + ", got " + args.size());
                }
                if (depth >= MAX_DEPTH) {
                    throw new ExpressionException("Function recursion too deep near " + name + "()");
                }
                depth++;
                try {
                    // slice 1: zero-arg — evaluate the body in the same context
                    return MathEvaluator.evaluateValue(def.body(), context);
                } finally {
                    depth--;
                }
            }
        };
    }

    public record ParsedFunction(String name, AliasDefinition definition) {
    }
}
