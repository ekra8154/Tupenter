package net.tupenter.script;

/**
 * Reads the block id at a world position for the {@code block(x, y, z)}
 * expression function. The client backs this with its synced copy of the
 * world — no server round trip, which is why {@code #if} on block
 * conditions needs no delay. {@link #NONE} means no world available.
 */
@FunctionalInterface
public interface BlockReader {

    /** No world to read from (tests without a stub, menus). */
    BlockReader NONE = (x, y, z) -> null;

    /**
     * @return the namespaced block id at the position (e.g.
     *         "minecraft:air"), or null when unavailable — no world, or
     *         the chunk isn't loaded
     */
    String blockAt(long x, long y, long z);

    /**
     * Whether the server is ticking entities at this position — its chunk is
     * within the server's simulation distance of the player. This is what
     * governs item despawn, mob spawning, redstone and crop growth, unlike
     * {@link #blockAt} which reflects only what the client has rendered.
     *
     * @return TRUE/FALSE when it can be judged, null when there's no world.
     *         Default: unknown (null), so a bare block-only stub says nothing.
     */
    default Boolean simulated(long x, long y, long z) {
        return null;
    }
}
