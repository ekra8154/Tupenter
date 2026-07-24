package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only data in the mod that outlives the session. A variable saved with
 * /tupenter var save goes to the config file as a "name = literal" string and
 * comes back by being EVALUATED as an expression — so the round trip runs
 * through the whole language, and anything the literal can't say is silently
 * lost on the next launch.
 */
class PersistentVariableStoreTest {

    private static Value evaluate(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(1)));
    }

    /** Saves, writes to config, reads back — the shape of an actual restart. */
    private static Value restart(String name, Value value) {
        PersistentVariableStore saved = new PersistentVariableStore();
        saved.set(name, value);
        PersistentVariableStore reloaded = new PersistentVariableStore();
        reloaded.load(saved.serialize());
        return reloaded.resolve(name).orElseThrow(() -> new AssertionError(name + " didn't survive the restart"));
    }

    @Test
    void everyTypeComesBackAsTheSameType() {
        assertEquals(evaluate("42"), restart("n", evaluate("42")));
        assertEquals(evaluate("12.5"), restart("d", evaluate("12.5")));
        assertEquals(Value.of("hello"), restart("s", Value.of("hello")));
        assertEquals(Value.of(true), restart("b", Value.of(true)));
        assertEquals(Value.of(false), restart("b", Value.of(false)));
    }

    /**
     * The headline claim of the math is that it never drifts — $1/3 * 3$ is
     * exactly 1. displayString rounds to 16 places to stay command-safe, so
     * storing THAT would have made a saved third come back as a different
     * number, and the promise would hold right up until you restarted.
     */
    @Test
    void anExactFractionIsStillExactAfterARestart() {
        Value third = evaluate("1/3");
        assertEquals(third, restart("third", third));

        PersistentVariableStore store = new PersistentVariableStore();
        store.set("third", third);
        assertEquals(List.of("third = 1/3"), store.serialize(), "the config keeps the fraction, not a decimal");

        // and the reloaded value still multiplies back to exactly 1
        PersistentVariableStore reloaded = new PersistentVariableStore();
        reloaded.load(store.serialize());
        assertEquals(evaluate("1"),
                new Value.NumberValue(((Value.NumberValue) reloaded.resolve("third").orElseThrow())
                        .value().multiply(Rational.of(3))));
    }

    @Test
    void negativeFractionsKeepTheirSign() {
        Value value = evaluate("-1/3");
        assertEquals(value, restart("neg", value));
    }

    /** Numbers are BigInteger-backed, so nothing overflows on the way to disk. */
    @Test
    void hugeWholeNumbersSurviveIntact() {
        Value huge = evaluate("2^80");
        assertEquals(huge, restart("huge", huge));
        assertEquals("1208925819614629174706176", restart("huge", huge).displayString());
    }

    /**
     * Text is re-parsed as an expression on load, so the quoting has to be
     * airtight — an unescaped quote in a saved value would either truncate it
     * or take the whole config entry down with it.
     */
    @Test
    void textWithQuotesAndBackslashesSurvives() {
        for (String awkward : List.of("he said \"hi\"", "back\\slash", "both \" and \\", "", "  spaced  ",
                "= not a separator", "a $marker$ shaped thing")) {
            assertEquals(Value.of(awkward), restart("t", Value.of(awkward)), "lost: " + awkward);
        }
    }

    @Test
    void listsAreRefusedRatherThanSavedWrong() {
        PersistentVariableStore store = new PersistentVariableStore();
        Value list = evaluate("range(1, 3)");
        String message = assertThrows(ExpressionException.class, () -> store.set("l", list)).getMessage();
        assertTrue(message.contains("Lists can't be saved"), message);
    }

    /**
     * A hand-edited or half-written config must not stop the mod from loading —
     * every entry that can't be read is dropped and the rest still arrive.
     */
    @Test
    void malformedEntriesAreSkippedNotFatal() {
        PersistentVariableStore store = new PersistentVariableStore();
        store.load(List.of(
                "good = 1",
                "no separator here",
                "= 5",                    // no name
                "empty =",                // no literal
                "broken = 1 +",           // doesn't evaluate
                "unclosed = \"oops",      // doesn't lex
                "alsogood = \"fine\""));
        assertEquals(evaluate("1"), store.resolve("good").orElseThrow());
        assertEquals(Value.of("fine"), store.resolve("alsogood").orElseThrow());
        assertEquals(java.util.Set.of("good", "alsogood"), store.names());
    }

    @Test
    void loadReplacesRatherThanMerges() {
        PersistentVariableStore store = new PersistentVariableStore();
        store.set("old", evaluate("1"));
        store.load(List.of("new = 2"));
        assertTrue(store.resolve("old").isEmpty(), "load starts from the config, not from what was already there");
        assertEquals(evaluate("2"), store.resolve("new").orElseThrow());
    }

    @Test
    void loadOfNothingClearsInsteadOfThrowing() {
        PersistentVariableStore store = new PersistentVariableStore();
        store.set("x", evaluate("1"));
        store.load(null); // a config file with no persistent section at all
        assertTrue(store.names().isEmpty());
    }

    @Test
    void namesAreCaseInsensitiveEverywhere() {
        PersistentVariableStore store = new PersistentVariableStore();
        store.set("MyVar", evaluate("7"));
        assertEquals(evaluate("7"), store.resolve("myvar").orElseThrow());
        assertEquals(evaluate("7"), store.resolve("MYVAR").orElseThrow());
        assertEquals(java.util.Set.of("myvar"), store.names());
        assertTrue(store.remove("MYVAR"));
        assertFalse(store.remove("myvar"), "removing twice reports that there was nothing to remove");
    }

    @Test
    void theSnapshotIsACopy() {
        PersistentVariableStore store = new PersistentVariableStore();
        store.set("x", evaluate("1"));
        store.snapshot().clear();
        assertEquals(1, store.names().size(), "callers can't empty the store by clearing a snapshot");
    }
}
