package net.tupenter.script;

import java.util.Random;

/**
 * Everything an expression needs from the outside world
 * (docs/SCRIPTING_DESIGN.md §5.3).
 */
public record EvalContext(Random random, VariableProvider variables) {

    public EvalContext(Random random) {
        this(random, VariableProvider.EMPTY);
    }
}
