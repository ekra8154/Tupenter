package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE registry of variable SUBJECTS — the dotted namespaces scripts read live
 * state through. Two kinds: ENUMERATED subjects (client, world, players, real)
 * whose members are registered one by one with a {@link VarDoc} apiece (their
 * pages list members straight from the provider, so the listing can't drift),
 * and DYNAMIC subjects (client.target, client.slot, client.nbt, ...) whose
 * members are computed at read time — their field vocabulary is documented
 * here, at the subject level.
 *
 * <p>Same contract as BuiltinFunctions / ParamTypeDocs / DirectiveDocs: the
 * /tupenter help variables hub and every subject page render from this list.
 */
public final class SubjectDocs {

    /**
     * {@code gate} is the boolean to check before reading a subject that may
     * be absent (null when always readable). {@code enumerated} subjects list
     * their members live from the provider's VarDocs.
     */
    public record Subject(String name, String blurb, String gate, boolean enumerated,
                          List<String> detail, String exampleSimple, String exampleComposed) {
    }

    /** Every subject, in hub order. */
    public static final List<Subject> ALL = buildAll();

    private static final Map<String, Subject> BY_NAME = buildIndex();

    private SubjectDocs() {
    }

    /** The subject named exactly {@code name} (case-insensitive), or null. */
    public static Subject find(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The subject whose namespace contains the dotted {@code name} — longest
     * segment-aware prefix wins, so client.vehicle.health lands on
     * client.vehicle and client.keypress.g on client.keypress (never on
     * client.key by raw-prefix accident). Null when nothing matches.
     */
    public static Subject findByPrefix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Subject best = null;
        for (Subject subject : ALL) {
            if (lower.startsWith(subject.name() + ".")
                    && (best == null || subject.name().length() > best.name().length())) {
                best = subject;
            }
        }
        return best;
    }

    private static Map<String, Subject> buildIndex() {
        Map<String, Subject> index = new LinkedHashMap<>();
        for (Subject subject : ALL) {
            if (index.put(subject.name(), subject) != null) {
                throw new IllegalStateException("duplicate subject doc: " + subject.name());
            }
        }
        return index;
    }

    private static Subject subject(String name, String blurb, String gate, boolean enumerated,
                                   String exampleSimple, String exampleComposed, String... detail) {
        return new Subject(name, blurb, gate, enumerated, List.of(detail), exampleSimple, exampleComposed);
    }

    private static List<Subject> buildAll() {
        return List.of(
                subject("client", "you — position, movement, vitals, session", null, true,
                        "/echo at $client.pos$ with $client.health$ hp",
                        "#if (client.light < 8 && client.on_ground) (/echohud &cmob-spawnable light!)",
                        "§7One vocabulary, three subjects:§r the entity fields (type, uuid, name, health, pos, blockpos, nbt.<path>) work here, on client.target.*, and via entity(uuid, ...) — swapping subject changes only the subject.",
                        "§7Vec components:§r pos, blockpos, motion, look, eye_pos carry .x/.y/.z for math."),
                subject("world", "the world you're in — time, weather, tick state", null, true,
                        "/echo day $world.day$, time $world.time$",
                        "#if (world.frozen) (/tick unfreeze) #else (/tick freeze)",
                        "§7Synced, not measured:§r tickrate/frozen/stepping are what /tick actually set — exact values from the server."),
                subject("players", "the tab list", null, true,
                        "/echo $players.count$ online",
                        "#foreach $p$ in players.list (/msg $p$ hello)",
                        "§7players.list is a LIST§r — len(), rand(), contains(), and #foreach all compose."),
                subject("real", "the real-world clock — not Minecraft data, just script-useful", null, true,
                        "/echo it's $real.hour$:$real.minute$ real time",
                        "#if (real.day_of_week == \"saturday\" || real.day_of_week == \"sunday\") (/echo weekend build day!)",
                        "§7Wall-clock,§r independent of world time — pair with #wait ... realtime for alarms."),
                subject("client.target", "whatever's under your crosshair — block or entity",
                        "client.target.hit — \"block\", \"entity\", or \"miss\"", false,
                        "/echo aiming at $client.target.block$",
                        "#if (client.target.hit == \"entity\") (/echo $client.target.name$: $client.target.health$ hp)",
                        "§7Block fields:§r blockpos (+ .x/.y/.z, where it is) · block (what it is).",
                        "§7Entity fields:§r type, uuid, name, health, pos, blockpos, nbt.<path> — the same vocabulary as client.<field> and entity(uuid, ...).",
                        "§7Wrong-kind reads error§r in value position, but read FALSE in an #if/#while condition — a tick script can poll client.target.health bare, no gate needed.",
                        "§7Normal reach only§r — for aim-at-anything distance, raycast_entity(dist) + entity(...)."),
                subject("client.vehicle", "what you're riding — the full entity vocabulary",
                        "client.riding", false,
                        "/echo riding $client.vehicle.type$",
                        "#if (client.riding && client.vehicle.health < 10) (/echohud &cyour mount is hurt!)",
                        "§7Fields:§r type, uuid, name, health, pos, blockpos, nbt.<path> — identical to client.target.* and entity(uuid, ...).",
                        "§7Not riding?§r value reads error loudly; conditions read false — same absent-not-wrong rule as the crosshair."),
                subject("client.held", "your main hand — id, count, durability, max_durability", null, false,
                        "/echo holding $client.held.id$",
                        "#if (client.held.max_durability > 0 && client.held.durability < 50) (/echohud &c$client.held.id$ is about to break!)",
                        "§7A named shorthand§r for client.slot.weapon.mainhand — same reader, so the two spellings can't disagree.",
                        "§7Empty hand:§r id = \"empty\", numbers = 0 — never an error. Non-damageable items read durability 0."),
                subject("client.offhand", "your off hand — id, count, durability, max_durability", null, false,
                        "/echo offhand: $client.offhand.id$",
                        "#if (client.offhand.id != \"minecraft:totem_of_undying\") (/echohud &egrab a totem!)",
                        "§7A named shorthand§r for client.slot.weapon.offhand — same reader as client.held.*.",
                        "§7Empty hand:§r id = \"empty\", numbers = 0 — never an error."),
                subject("client.slot", "any of your slots, by /item replace name", null, false,
                        "/echo chest: $client.slot.armor.chest.durability$",
                        "#for $i$ in 0..8 (/echo hotbar $i$: $slot(\"hotbar.\" + i, \"id\")$)",
                        "§7Address:§r client.slot.<slot>.<field> — slot names are hotbar.0-8, inventory.0-26 (0-8 is the TOP row), armor.head/chest/legs/feet/body, weapon.mainhand/offhand; fields are id, count, durability, max_durability. Both halves tab-complete.",
                        "§7Computed slot?§r that's the function twin: slot(slot, field) — the composed example walks the hotbar.",
                        "§7Empty slot:§r id = \"empty\", numbers = 0 — never an error."),
                subject("client.nbt", "your entity's raw NBT tree — Mojang's spelling, any path", null, false,
                        "/echo $client.nbt.Health$",
                        "/echo $entity(\"self\", \"nbt.equipment.chest.components.minecraft:damage\", 0)$",
                        "§7The escape hatch:§r everything the client has synced, addressed by dotted path — numeric segments index lists. client.target.nbt.<path> reads the crosshair entity the same way.",
                        "§7Capitalization signals ownership:§r nbt.Health is Mojang's field in Mojang's casing; the lowercase direct fields (client.health) are the mod's stable API.",
                        "§7Tab-completes the LIVE tree§r one level at a time · browse it all with /tupenter dump.",
                        "§7Absent paths error:§r NBT omits defaulted values (an undamaged item has no minecraft:damage) — the entity(sel, path, FALLBACK) form is the safe read, like the composed example.",
                        "§7Not client.nbt.Inventory:§r that's the SAVE format — a compacted list of non-empty stacks, so Inventory.0 is \"my first stack\", not slot 0. Use client.slot.* for slots."),
                subject("client.key", "keyboard state, held RIGHT NOW — a tick script IS a keybind", null, false,
                        "/echo jump held: $client.key.jump$",
                        "#if (client.key.sneak && client.key.sprint) (/echohud &7both held)",
                        "§7Address:§r client.key.<name> — a BIND name first (jump, sneak, attack, hotbar.1 — follows your controls and modded binds), else a physical key (g, space, f6).",
                        "§7Arrows:§r up_arrow/down_arrow/left_arrow/right_arrow — bare left/right are the strafe binds.",
                        "§7All false while a screen is open§r (chat, inventory, menus) — keys mean gameplay keys.",
                        "§7Held vs pressed:§r this is \"is it down\"; the one-shot edge is client.keypress.<name>."),
                subject("client.keypress", "the single tick a key goes DOWN — the one-shot edge", null, false,
                        "#if (client.keypress.g) (/echo G!)",
                        "#if (client.keypress.g) (/tp @s $client.target.blockpos$)",
                        "§7True for exactly one tick§r per press — the natural trigger for a tick-script action (the composed example is a blink-to-crosshair on G).",
                        "§7Same names as client.key.<name>§r — binds first, physical keys second; the rest of the story is on /tupenter help client.key."));
    }
}
