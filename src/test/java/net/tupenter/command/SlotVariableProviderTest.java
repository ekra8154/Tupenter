package net.tupenter.command;

import net.tupenter.script.ExpressionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client.slot address space: tab-completion and the errors you hit typing
 * a half-finished path. Both run before any live inventory is read, so they
 * test without a world — and completion is what the user actually leans on to
 * discover the vocabulary, so a gap here is a gap in the UI.
 */
class SlotVariableProviderTest {

    private static final SlotVariableProvider PROVIDER = new SlotVariableProvider();

    private static ExpressionException resolveError(String name) {
        return assertThrows(ExpressionException.class, () -> PROVIDER.resolve(name));
    }

    // ------------------------------------------------------- completion

    @Test
    void anEmptyPrefixOffersEverySlotAsABranch() {
        List<String> slots = SlotVariableProvider.completions("client.slot.");
        assertTrue(slots.contains("client.slot.hotbar.0."));
        assertTrue(slots.contains("client.slot.hotbar.8."));
        assertTrue(slots.contains("client.slot.inventory.0."));
        assertTrue(slots.contains("client.slot.inventory.26."));
        assertTrue(slots.contains("client.slot.armor.head."));
        assertTrue(slots.contains("client.slot.armor.body."), "horse/wolf armor slot");
        assertTrue(slots.contains("client.slot.weapon.mainhand."));
        assertTrue(slots.contains("client.slot.weapon.offhand."));
        // every entry is a branch (trailing dot), none is a leaf yet
        assertTrue(slots.stream().allMatch(s -> s.endsWith(".")), slots.toString());
    }

    @Test
    void theSlotRangesMatchWhatCanActuallyBeAddressed() {
        List<String> slots = SlotVariableProvider.completions("client.slot.");
        long hotbar = slots.stream().filter(s -> s.startsWith("client.slot.hotbar.")).count();
        long inventory = slots.stream().filter(s -> s.startsWith("client.slot.inventory.")).count();
        assertEquals(9, hotbar, "hotbar.0-8");
        assertEquals(27, inventory, "inventory.0-26");
        assertFalse(slots.contains("client.slot.hotbar.9."), "there is no hotbar.9");
        assertFalse(slots.contains("client.slot.inventory.27."));
    }

    @Test
    void onceASlotIsSettledCompletionOffersItsFields() {
        List<String> fields = SlotVariableProvider.completions("client.slot.armor.chest.");
        assertEquals(List.of(
                "client.slot.armor.chest.id",
                "client.slot.armor.chest.count",
                "client.slot.armor.chest.durability",
                "client.slot.armor.chest.max_durability"), fields);
    }

    @Test
    void theHandShorthandsCompleteToTheirFields() {
        assertEquals(List.of(
                "client.held.id", "client.held.count",
                "client.held.durability", "client.held.max_durability"),
                SlotVariableProvider.completions("client.held."));
        assertTrue(SlotVariableProvider.completions("client.offhand.").contains("client.offhand.durability"));
    }

    @Test
    void anUnrelatedPrefixCompletesToNothing() {
        assertEquals(List.of(), SlotVariableProvider.completions("client.health"));
        assertEquals(List.of(), SlotVariableProvider.completions("world."));
    }

    // ------------------------------------------------------- resolve errors

    /**
     * The old client.slot meant "which hotbar slot is selected". That reads
     * like a namespace root, which was the confusion — so hitting it bare now
     * explains the split rather than returning a number.
     */
    @Test
    void theBareRootExplainsTheSplitInsteadOfResolving() {
        String message = resolveError("client.slot").getMessage();
        assertTrue(message.contains("client.selected_slot"), message);
        assertTrue(message.contains("client.slot.<slot>.<field>"), message);
    }

    /**
     * A slot with nothing after it — no dot to split on — can't be read, and
     * says which fields it wanted. (client.slot.hotbar.0 does NOT land here:
     * that parses as slot "hotbar", field "0", which is a real read.)
     */
    @Test
    void aSlotWithNoFieldNamesTheFieldsItWants() {
        String message = resolveError("client.slot.armor").getMessage();
        assertTrue(message.contains("needs a field"), message);
        assertTrue(message.contains("durability"), "it lists the fields: " + message);
        assertTrue(message.contains("client.slot.armor.id"), "with a worked example: " + message);
    }

    @Test
    void theHandShorthandsAlsoDemandAField() {
        for (String hand : List.of("client.held", "client.offhand")) {
            String message = resolveError(hand).getMessage();
            assertTrue(message.contains("needs a field"), message);
            assertTrue(message.contains(hand + ".id"), message);
        }
    }

    /** A name in nobody's namespace is simply not ours — absent, not an error. */
    @Test
    void aNameOutsideTheSlotNamespaceIsAbsent() {
        assertTrue(PROVIDER.resolve("client.health").isEmpty());
        assertTrue(PROVIDER.resolve("world.time").isEmpty());
    }

    @Test
    void theProviderEnumeratesNothing() {
        assertTrue(PROVIDER.names().isEmpty(),
                "client.slot.* is dynamic — the completion hook offers the root, not names()");
    }
}
