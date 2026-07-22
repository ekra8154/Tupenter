package net.tupenter.script;

import java.util.Random;

/**
 * Everything an expression needs from the outside world
 * (docs/SCRIPTING_DESIGN.md §5.3).
 */
public record EvalContext(Random random, VariableProvider variables, TagResolver tags, BlockReader blocks,
                          FunctionResolver functions, Raycaster raycaster, NbtReader nbt) {

    public EvalContext(Random random) {
        this(random, VariableProvider.EMPTY, TagResolver.NONE, BlockReader.NONE, FunctionResolver.NONE,
                Raycaster.NONE, NbtReader.NONE);
    }

    public EvalContext(Random random, VariableProvider variables) {
        this(random, variables, TagResolver.NONE, BlockReader.NONE, FunctionResolver.NONE,
                Raycaster.NONE, NbtReader.NONE);
    }

    public EvalContext(Random random, VariableProvider variables, TagResolver tags) {
        this(random, variables, tags, BlockReader.NONE, FunctionResolver.NONE, Raycaster.NONE, NbtReader.NONE);
    }

    public EvalContext(Random random, VariableProvider variables, TagResolver tags, BlockReader blocks) {
        this(random, variables, tags, blocks, FunctionResolver.NONE, Raycaster.NONE, NbtReader.NONE);
    }

    public EvalContext(Random random, VariableProvider variables, TagResolver tags, BlockReader blocks,
                       FunctionResolver functions) {
        this(random, variables, tags, blocks, functions, Raycaster.NONE, NbtReader.NONE);
    }

    public EvalContext(Random random, VariableProvider variables, TagResolver tags, BlockReader blocks,
                       FunctionResolver functions, Raycaster raycaster) {
        this(random, variables, tags, blocks, functions, raycaster, NbtReader.NONE);
    }
}
