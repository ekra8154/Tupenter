package net.tupenter.script;

import java.util.Random;

/**
 * Everything an expression needs from the outside world
 * (docs/SCRIPTING_DESIGN.md §5.3).
 */
public record EvalContext(Random random, VariableProvider variables, TagResolver tags) {

    public EvalContext(Random random) {
        this(random, VariableProvider.EMPTY, TagResolver.NONE);
    }

    public EvalContext(Random random, VariableProvider variables) {
        this(random, variables, TagResolver.NONE);
    }
}
