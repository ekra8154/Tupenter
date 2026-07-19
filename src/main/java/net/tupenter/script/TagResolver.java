package net.tupenter.script;

import java.util.List;

/**
 * Resolves a registry tag (e.g. {@code minecraft:logs}, {@code c:ores}) to
 * its member ids — or, with a null tag, enumerates the whole registry — for
 * the {@code blockset(...)} / {@code itemset(...)} / {@code effectset(...)}
 * expression functions. The client backs this with the live connection's
 * synced registries; tests stub it; {@link #NONE} means no lookup available.
 */
@FunctionalInterface
public interface TagResolver {

    /** Tag lookup unavailable (no live world). */
    TagResolver NONE = (kind, tagId) -> null;

    enum TagKind {
        ITEM,
        BLOCK,
        EFFECT
    }

    /**
     * @param tagId namespaced tag id without the leading '#', or null for
     *              every entry in the registry
     * @return member ids; empty when the tag is unknown or empty; null when
     *         lookup is unavailable (not in-game)
     */
    List<String> resolve(TagKind kind, String tagId);

    /**
     * A CONCRETE registry entry: the canonical id ("stone" -&gt;
     * "minecraft:stone") when the registry contains it, else null. Lets the
     * set functions treat a plain block/item/effect id as a one-element set.
     */
    default String lookup(TagKind kind, String id) {
        return null;
    }
}
