package net.tupenter.script;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The call machinery for user-defined expression functions: arity checking,
 * call-by-value parameter binding (with .x/.y/.z accessors for tuple types),
 * and body evaluation in a scoped context. Lives in the main source set —
 * NOT the client — precisely so unit tests exercise the same code the game
 * runs; the client-side {@code CustomFunctionManager} only supplies the
 * name → definition map from config.
 */
public final class UserFunctions {
    private static final int MAX_DEPTH = 32;
    /** Loop bound for callers that don't supply one (existing tests, non-config contexts). */
    private static final int DEFAULT_MAX_LOOP_ITERATIONS = 100;

    private UserFunctions() {
    }

    /** Convenience: a resolver with the default loop bound. */
    public static FunctionResolver resolver(Map<String, AliasDefinition> functions) {
        return resolver(functions, DEFAULT_MAX_LOOP_ITERATIONS);
    }

    /**
     * Wraps a name → definition map as the evaluator's {@link FunctionResolver},
     * with a recursion guard. Returns null for an unknown name so the evaluator
     * falls through to its "unknown function" error.
     *
     * <p>A body is auto-detected as either a pure EXPRESSION (evaluated as today)
     * or a STATEMENT body (loops/locals/#return, run through the Walker in
     * function mode). {@code maxLoopIterations} bounds statement-body loops.
     */
    public static FunctionResolver resolver(Map<String, AliasDefinition> functions, int maxLoopIterations) {
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
                    // call-by-value: bind each arg to its param, then evaluate the
                    // body in a scope that overlays those bindings on the world
                    Map<String, Value> bindings = new HashMap<>();
                    for (int i = 0; i < arity; i++) {
                        bindParam(def.params().get(i), args.get(i), bindings);
                    }
                    EvalContext scoped = new EvalContext(context.random(), overlay(bindings, context.variables()),
                            context.tags(), context.blocks(), context.functions(), context.raycaster(), context.nbt());
                    try {
                        // statement bodies run through the Walker; pure expressions stay on the fast path
                        if (ScriptParser.isStatementBody(def.body())) {
                            return ScriptParser.evaluateFunctionBody(def.body(),
                                    functionOptions(scoped, maxLoopIterations));
                        }
                        return MathEvaluator.evaluateValue(def.body(), scoped);
                    } catch (ExpressionException ex) {
                        // attribute body failures to the function, not the caller's marker
                        throw new ExpressionException("in " + name + "() body — " + ex.getMessage());
                    }
                } finally {
                    depth--;
                }
            }
        };
    }

    /**
     * The Options a statement-body function runs under: chaining on, explicit-only
     * math, no aliases, all statement features enabled, loops bounded by
     * {@code maxLoopIterations}, effectively no send cap (functions never send),
     * and the world/params drawn from the scoped call context. A throwaway session
     * store guarantees #set stays function-local (it is never committed anyway).
     */
    private static ScriptParser.Options functionOptions(EvalContext scoped, int maxLoopIterations) {
        return new ScriptParser.Options(
                true,                              // chainingEnabled
                NumberMathMode.EXPLICIT_ONLY,      // mathMode
                Map.of(),                          // aliases — functions don't expand custom commands
                true,                              // silentDirectiveEnabled (unused; #silent errors in function mode)
                true,                              // variablesEnabled
                true,                              // loopsEnabled
                true,                              // conditionalsEnabled
                maxLoopIterations,
                Integer.MAX_VALUE,                 // maxCommandsPerScript — a function never sends
                scoped.random(),
                scoped.variables(),                // the param overlay provider
                new SessionVariableStore(),        // throwaway — nothing commits to it
                scoped.tags(),
                scoped.blocks(),
                scoped.functions(),                // same resolver → nested calls keep the depth guard
                scoped.raycaster(),                // raycast(...) works in statement-body functions too
                scoped.nbt(),                      // ...and entity_nbt(...)
                false);                            // lazyExecution — functions compute synchronously
    }

    /** A provider that checks the param bindings first, then falls through to the live world. */
    private static VariableProvider overlay(Map<String, Value> bindings, VariableProvider fallback) {
        return new VariableProvider() {
            @Override
            public Set<String> names() {
                Set<String> names = new HashSet<>(fallback.names());
                names.addAll(bindings.keySet());
                return names;
            }

            @Override
            public Optional<Value> resolve(String name) {
                Value bound = bindings.get(name.toLowerCase(Locale.ROOT));
                return bound != null ? Optional.of(bound) : fallback.resolve(name);
            }
        };
    }

    /** Binds one arg value to its param name. Tuple types (vec3/pos/...) also bind .x/.y/.z accessors. */
    private static void bindParam(AliasDefinition.Param param, Value arg, Map<String, Value> bindings) {
        switch (param.type()) {
            case POS, VEC3 -> bindTuple(param.name(), arg, new String[]{"x", "y", "z"}, bindings);
            case COLUMN_POS -> bindTuple(param.name(), arg, new String[]{"x", "z"}, bindings);
            case ROTATION -> bindTuple(param.name(), arg, new String[]{"yaw", "pitch"}, bindings);
            // everything else binds as-is; the body's expression coerces numbers/strings as needed
            default -> bindings.put(param.name(), arg);
        }
    }

    private static void bindTuple(String name, Value arg, String[] axes, Map<String, Value> bindings) {
        String[] parts = Coords.split(arg.displayString());
        if (parts.length != axes.length) {
            throw new ExpressionException("'" + arg.displayString() + "' isn't a " + axes.length + "-part "
                    + (axes.length == 3 ? "vec3" : "position") + " for " + name + " — needs " + axes.length
                    + " numbers (e.g. \"0 64 0\" or vec(0, 64, 0))");
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < axes.length; i++) {
            double coord;
            try {
                coord = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ex) {
                throw new ExpressionException("'" + parts[i] + "' in '" + arg.displayString() + "' isn't a number");
            }
            Value number = Value.ofNumber(coord);
            bindings.put(name + "." + axes[i], number);
            if (i > 0) {
                joined.append(' ');
            }
            joined.append(number.displayString());
        }
        bindings.put(name, Value.of(joined.toString())); // $name$ = "x y z" for interpolation
    }
}
