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
 * {@code entity_nbt(selector, path)}: the VARIABLE form reads well and
 * tab-completes when the slot is known as you write the script; the FUNCTION
 * form ({@code slot_item(s)}/{@code slot_count(s)}/{@code slot_durability(s)})
 * is what you reach for when the slot is computed, e.g. inside a loop.
 */
public final class SlotVariableProvider implements VariableProvider {
    private static final String PREFIX = "client.slot.";

    private static final List<String> FIELDS = List.of("id", "count", "durability", "max_durability");

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
        ItemStack stack = EntityAccessImpl.stackAt(slot); // throws with the valid slot names
        return Optional.of(switch (field) {
            case "id" -> Value.of(stack.isEmpty() ? "empty"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            case "count" -> Value.ofNumber(stack.getCount());
            case "durability" -> Value.ofNumber(
                    stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0);
            case "max_durability" -> Value.ofNumber(stack.isDamageableItem() ? stack.getMaxDamage() : 0);
            default -> throw new ExpressionException("client.slot." + slot + "." + field
                    + ": unknown field '" + field + "' — use " + String.join(", ", FIELDS));
        });
    }

    /**
     * Tab-completion for client.slot.* — slot names first, then that slot's
     * fields once a full slot name has been typed.
     */
    public static List<String> completions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
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
