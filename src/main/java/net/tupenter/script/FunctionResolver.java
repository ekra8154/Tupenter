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
}
