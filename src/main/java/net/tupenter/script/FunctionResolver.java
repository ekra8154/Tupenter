package net.tupenter.script;

import java.util.List;

/**
 * Resolves a user-defined function call inside an expression — the bridge that
 * lets {@code /customfunction}s slot in beside built-ins like {@code min} or
 * {@code sqrt}. The evaluator asks this before giving up on an unknown
 * function name.
 */
public interface FunctionResolver {
    FunctionResolver NONE = (name, args, context) -> null;

    /**
     * Calls the named user function with the already-evaluated argument values.
     *
     * @return the function's value, or {@code null} if no function by that name
     *         exists (so the evaluator can fall through to its "unknown
     *         function" error). Throws {@link ExpressionException} for a real
     *         problem — wrong arity, a bad body, runaway recursion.
     */
    Value call(String name, List<Value> args, EvalContext context);

    /**
     * Whether a function by this name exists — asked BEFORE any arguments are
     * parsed, so the evaluator can tell {@code f(x)} (a call) from {@code v (x)}
     * (a variable times a parenthesized group; the language has implicit
     * multiplication). Defaults to false so a resolver that only implements
     * {@link #call} keeps working — an unknown name then falls through to the
     * variable reading, and to the "unknown function" error if that fails too.
     */
    default boolean defines(String name) {
        return false;
    }
}
