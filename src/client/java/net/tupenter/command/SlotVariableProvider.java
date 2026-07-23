package net.tupenter.command;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Your slots as dotted variables: {@code client.slot.<slot>.<field>} — e.g.
 * {@code client.slot.inventory.8.id}, {@code client.slot.armor.chest.durability}.
 * {@code <slot>} is a /item replace name (hotbar.0-8, inventory.0-26,
 * armor.head/chest/legs/feet, weapon.mainhand, weapon.offhand).
 *
 * <p>This is the same pairing {@code client.nbt.<path>} has with
 * {@code entity(selector, field)}: the VARIABLE form reads well and
 * tab-completes when the slot is known as you write the script; the FUNCTION
 * form ({@code slot(slot, field)})
 * is what you reach for when the slot is computed, e.g. inside a loop.
 */
public final class SlotVariableProvider implements VariableProvider {
    private static final String PREFIX = "client.slot.";

    /**
     * Named shorthands for the two slots you reach for constantly. They resolve
     * through the SAME reader as client.slot.weapon.*, so they're aliases in the
     * literal sense — one implementation, nothing to drift. (client.held_item and
     * friends used to be a second implementation, which is how held_durability
     * and the slot form came to disagree on non-damageable items.)
     */
    private static final String HELD = "client.held.";
    private static final String OFFHAND = "client.offhand.";

    private static final List<String> FIELDS = EntityAccessImpl.FIELDS;

    /** Slot names offered by tab-complete. SlotArgument stays the authority for what actually parses. */
    private static final List<String> SLOT_NAMES = buildSlotNames();

    @Override
    public Set<String> names() {
        return Set.of(); // dynamic — the root is offered by the completion hook instead
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("client.slot")) {
            // the old name for "which hotbar slot is selected" — it read like a
            // namespace root, which is exactly why it was confusing. Say so.
            throw new ExpressionException("client.slot split in two: $client.selected_slot$ is which hotbar slot "
                    + "you have selected (0-8); slot CONTENTS are client.slot.<slot>.<field>, "
                    + "e.g. client.slot.inventory.8.id");
        }
        if (lower.equals("client.held") || lower.equals("client.offhand")) {
            throw new ExpressionException(lower + " needs a field — " + String.join(", ", FIELDS)
                    + " (e.g. $" + lower + ".id$)");
        }
        if (lower.startsWith(HELD)) {
            return Optional.of(EntityAccessImpl.readField("weapon.mainhand", lower.substring(HELD.length())));
        }
        if (lower.startsWith(OFFHAND)) {
            return Optional.of(EntityAccessImpl.readField("weapon.offhand", lower.substring(OFFHAND.length())));
        }
        if (!lower.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = lower.substring(PREFIX.length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot < 0) {
            throw new ExpressionException("client.slot." + rest + " needs a field — "
                    + String.join(", ", FIELDS) + " (e.g. client.slot." + rest + ".id)");
        }
        String slot = rest.substring(0, lastDot);
        String field = rest.substring(lastDot + 1);
        // same reader the slot(slot, field) function uses — one implementation,
        // so the two spellings can never disagree
        return Optional.of(EntityAccessImpl.readField(slot, field));
    }

    /**
     * Tab-completion for client.slot.* — slot names first, then that slot's
     * fields once a full slot name has been typed.
     */
    public static List<String> completions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String shorthand : new String[]{HELD, OFFHAND}) {
            if (lower.startsWith(shorthand)) {
                List<String> fields = new ArrayList<>();
                for (String field : FIELDS) {
                    fields.add(shorthand + field);
                }
                return fields;
            }
        }
        if (!lower.startsWith(PREFIX)) {
            return List.of();
        }
        String rest = lower.substring(PREFIX.length());
        List<String> out = new ArrayList<>();
        for (String slot : SLOT_NAMES) {
            if (rest.startsWith(slot + ".")) {
                for (String field : FIELDS) { // a slot is settled — offer its fields
                    out.add(PREFIX + slot + "." + field);
                }
                return out;
            }
        }
        for (String slot : SLOT_NAMES) {
            out.add(PREFIX + slot + ".");
        }
        return out;
    }

    private static List<String> buildSlotNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            names.add("hotbar." + i);
        }
        for (int i = 0; i <= 26; i++) {
            names.add("inventory." + i);
        }
        names.add("armor.head");
        names.add("armor.chest");
        names.add("armor.legs");
        names.add("armor.feet");
        names.add("armor.body");
        names.add("weapon.mainhand");
        names.add("weapon.offhand");
        return List.copyOf(names);
    }
}
