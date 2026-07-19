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
}
