package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolution chain (docs/SCRIPTING_DESIGN.md §5.4): script scope → session
 * → persistent → live providers, FIRST MATCH WINS. That order is a language
 * rule, not an implementation detail — it's what makes #local shadow a session
 * variable, and a session variable shadow client.*, so a script can name
 * something without worrying what else in the world is called that.
 */
class VariableRegistryTest {

    /** A provider that knows exactly the names it's given. */
    private static VariableProvider provider(Map<String, String> values) {
        return new VariableProvider() {
            @Override
            public Set<String> names() {
                return values.keySet();
            }

            @Override
            public Optional<Value> resolve(String name) {
                return Optional.ofNullable(values.get(name)).map(Value::of);
            }

            @Override
            public String describe(String name) {
                return values.containsKey(name) ? "docs for " + name : null;
            }
        };
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    void theFirstProviderRegisteredWins() {
        VariableRegistry registry = new VariableRegistry();
        registry.register(provider(map("x", "session")));
        registry.register(provider(map("x", "live")));
        assertEquals(Value.of("session"), registry.resolve("x").orElseThrow(),
                "a later provider must not override an earlier one — that's what shadowing means");
    }

    @Test
    void resolutionFallsThroughToLaterProviders() {
        VariableRegistry registry = new VariableRegistry();
        registry.register(provider(map("mine", "a")));
        registry.register(provider(map("theirs", "b")));
        assertEquals(Value.of("a"), registry.resolve("mine").orElseThrow());
        assertEquals(Value.of("b"), registry.resolve("theirs").orElseThrow());
    }

    @Test
    void anUnknownNameResolvesToAbsentRatherThanThrowing() {
        VariableRegistry registry = new VariableRegistry();
        registry.register(provider(map("x", "1")));
        assertTrue(registry.resolve("nope").isEmpty());
        assertTrue(new VariableRegistry().resolve("anything").isEmpty(), "an empty chain answers, it doesn't crash");
    }

    /** names() feeds the "did you mean" suggestions, so it has to see the whole chain. */
    @Test
    void namesUnionsEveryProvider() {
        VariableRegistry registry = new VariableRegistry();
        registry.register(provider(map("a", "1", "shared", "session")));
        registry.register(provider(map("b", "2", "shared", "live")));
        assertEquals(Set.of("a", "b", "shared"), registry.names());
    }

    /** describe() drives the help pages, and follows the same first-match rule. */
    @Test
    void describeFollowsTheSameOrderAndMissesQuietly() {
        VariableRegistry registry = new VariableRegistry();
        registry.register(provider(map("x", "session")));
        registry.register(provider(map("x", "live", "y", "live")));
        assertEquals("docs for x", registry.describe("x"));
        assertEquals("docs for y", registry.describe("y"), "an undocumented earlier provider doesn't block a later one");
        assertNull(registry.describe("nope"));
    }

    /**
     * The real chain, in the real order: a #local shadows a session variable,
     * which shadows a persistent one, which shadows a live client.* read.
     */
    @Test
    void theDocumentedOrderShadowsCorrectly() {
        SessionVariableStore session = new SessionVariableStore();
        PersistentVariableStore persistent = new PersistentVariableStore();
        VariableProvider live = provider(map("health", "live"));

        VariableRegistry registry = new VariableRegistry();
        registry.register(session);
        registry.register(persistent);
        registry.register(live);

        assertEquals(Value.of("live"), registry.resolve("health").orElseThrow());

        persistent.set("health", Value.of("persistent"));
        assertEquals(Value.of("persistent"), registry.resolve("health").orElseThrow());

        session.set("health", Value.of("session"));
        assertEquals(Value.of("session"), registry.resolve("health").orElseThrow());

        // and removing a shadow uncovers exactly what it was hiding
        session.clear();
        assertEquals(Value.of("persistent"), registry.resolve("health").orElseThrow());
        persistent.remove("health");
        assertEquals(Value.of("live"), registry.resolve("health").orElseThrow());
    }
}
