package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * THE registry of built-in expression functions — every function's name and its
 * documentation, in one place. {@link ExpressionEvaluator}'s call-vs-multiplication
 * set and CustomFunctionManager's reserved names both derive from {@link #NAMES},
 * and the /tupenter help pages render straight from {@link #ALL} — so a function
 * cannot exist undocumented, and docs cannot name a function that no longer
 * exists. BuiltinFunctionsTest asserts this registry and the dispatch switch
 * agree, which turns doc drift into a build failure.
 *
 * <p>Doc conventions: {@code blurb} is a half-line for the index; {@code detail}
 * lines are full styled chat lines (same §-code style as the help pages);
 * examples are literal runnable chat lines — {@code exampleSimple} shows the
 * function alone, {@code exampleComposed} shows it composing with something else.
 */
public final class BuiltinFunctions {

    public enum Group {
        MATH("Math"),
        TEXT("Text"),
        RANDOM("Random"),
        LISTS("Lists"),
        SETS("Registry sets"),
        VECTORS("Vectors"),
        WORLD("World"),
        ENTITIES("Entities"),
        SLOTS("Slots");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Doc(String name, Group group, String signature, String blurb,
                      List<String> detail, String exampleSimple, String exampleComposed) {
    }

    /** Every built-in, in index order (grouped). */
    public static final List<Doc> ALL = buildAll();

    /** Doc lookup by lowercase name. */
    private static final Map<String, Doc> BY_NAME = buildIndex();

    /** Every built-in function name (lowercase) — the single source the evaluator and reserved-name checks derive from. */
    public static final Set<String> NAMES = Set.copyOf(BY_NAME.keySet());

    private BuiltinFunctions() {
    }

    /** The doc for {@code name} (case-insensitive), or null. */
    public static Doc find(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The error for calling a function that doesn't exist — names the closest
     * real function when the typo is small, and always points at the index.
     */
    public static String unknownFunctionMessage(String name) {
        String near = nearest(name);
        return "Unknown function: " + name
                + (near != null ? " — did you mean " + near + "(...)?" : "")
                + " (/tupenter help functions lists them all)";
    }

    /** The registered name within edit distance 2 of {@code name}, or null if none is close. */
    public static String nearest(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDist = 3; // only offer genuinely close names
        for (String candidate : BY_NAME.keySet()) {
            int dist = editDistance(lower, candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    private static Map<String, Doc> buildIndex() {
        Map<String, Doc> index = new LinkedHashMap<>();
        for (Doc doc : ALL) {
            Doc previous = index.put(doc.name(), doc);
            if (previous != null) {
                throw new IllegalStateException("duplicate builtin doc: " + doc.name());
            }
        }
        return index;
    }

    private static Doc doc(String name, Group group, String signature, String blurb,
                           String exampleSimple, String exampleComposed, String... detail) {
        return new Doc(name, group, signature, blurb, List.of(detail), exampleSimple, exampleComposed);
    }

    /** The five facts true of every registry-set function, worded for one kind. */
    private static String[] setDetail(String fn, String tagExample, String idExample, String registryNote) {
        return new String[]{
                "§7A tag's members as a list:§r " + fn + "(" + tagExample + ") — typing # inside the parens TAB-COMPLETES the tags.",
                "§7A concrete id is a one-element set§r (" + fn + "(\"" + idExample + "\")), and several members UNION — tags and ids mix freely, deduped, first-seen order.",
                "§7No argument = the whole registry:§r " + registryNote,
                "§7Draw ONE with rand(" + fn + "(...))§r — not pick(...), which treats the whole set as a single option.",
                "§7One string also works§r — members split on spaces or commas — which is how a set survives the trip through a custom-command argument.",
                "§7Needs a live world:§r ids and tags come from the server you're on.",
        };
    }

    private static List<Doc> buildAll() {
        return List.of(
                // ---- Math ----
                doc("int", Group.MATH, "int(x)",
                        "the whole part of x — truncates toward zero",
                        "/calc int(3.9)",
                        "/echo $int(client.health / 2)$ full hearts left",
                        "§7int(3.9)§r = 3 and §7int(-3.9)§r = -3 — the decimal part is dropped, no rounding.",
                        "§7Not floor:§r floor always goes DOWN, so they differ on negatives: floor(-3.9) = -4, int(-3.9) = -3."),
                doc("float", Group.MATH, "float(x)",
                        "x as a number — turns numeric text into math material",
                        "/calc float(\"3.5\") * 2",
                        "/calc float(substr(\"level_42\", 6)) + 1",
                        "§7Text to number:§r NBT reads and substr results are text — float(\"3.5\") is the NUMBER 3.5, ready for * and <.",
                        "§7Errors loudly§r on non-numeric text, so a bad read fails at the source, not three math steps later."),
                doc("abs", Group.MATH, "abs(x)",
                        "distance from zero: abs(-5) = 5",
                        "/calc abs(-5)",
                        "/echo $abs(client.pos.y - 63)$ blocks from sea level",
                        "§7Differences:§r abs(a - b) is \"how far apart\" without caring which is bigger."),
                doc("floor", Group.MATH, "floor(x)",
                        "round DOWN to a whole number (toward -infinity)",
                        "/calc floor(-2.1)",
                        "/echo you're in chunk $floor(client.blockpos.x / 16)$, $floor(client.blockpos.z / 16)$",
                        "§7floor(2.9)§r = 2 and §7floor(-2.1)§r = -3 — always down, which is exactly how block coordinates work.",
                        "§7Self-parity:§r floor(client.pos.y) equals client.blockpos.y — block positions are floored, not truncated."),
                doc("ceil", Group.MATH, "ceil(x)",
                        "round UP to a whole number (toward +infinity)",
                        "/calc ceil(2.1)",
                        "/echo $ceil(200 / 64)$ stacks hold 200 items",
                        "§7\"How many containers\":§r ceil(items / 64) is the stack count that FITS everything — the classic use."),
                doc("round", Group.MATH, "round(x)",
                        "the nearest whole number",
                        "/calc round(2.6)",
                        "/echo $round(client.pos.x)$ $round(client.pos.y)$ $round(client.pos.z)$",
                        "§7Display-friendly:§r echo positions and health without decimal noise. When the DIRECTION matters, use floor or ceil instead."),
                doc("min", Group.MATH, "min(a, b, ...)",
                        "the smallest of any number of values",
                        "/calc min(3, 7)",
                        "/echo weakest stat: $min(client.health, client.food)$",
                        "§7Any count:§r min(a, b, c, d) works — not just pairs.",
                        "§7Clamp:§r max(low, min(high, x)) pins x into [low, high] — min and max nest into a clamp."),
                doc("max", Group.MATH, "max(a, b, ...)",
                        "the largest of any number of values",
                        "/calc max(3, 7)",
                        "/calc max(0, min(100, 120))",
                        "§7Any count:§r max(a, b, c, d) works — not just pairs.",
                        "§7Clamp:§r max(low, min(high, x)) pins x into [low, high] — the composed example clamps 120 to 100."),
                doc("sqrt", Group.MATH, "sqrt(x)",
                        "square root (a negative input errors)",
                        "/calc sqrt(9)",
                        "/echo $round(sqrt(client.pos.x^2 + client.pos.z^2))$ blocks from x=0 z=0",
                        "§7Distance:§r sqrt(dx^2 + dy^2 + dz^2) — ^ binds tighter than *, so the distance formula reads naturally."),
                doc("sin", Group.MATH, "sin(deg)",
                        "sine, in DEGREES (Minecraft rotations are degrees)",
                        "/calc sin(90)",
                        "#for $a$ in 0..359 step 30 (/particle minecraft:flame ~$5 * cos(a)$ ~1 ~$5 * sin(a)$)",
                        "§7Degrees, not radians:§r client.yaw and client.pitch feed straight in.",
                        "§7Circles:§r x = r*cos(a), z = r*sin(a) walks a circle as a sweeps 0..359 — the composed example draws one in particles."),
                doc("cos", Group.MATH, "cos(deg)",
                        "cosine, in DEGREES (Minecraft rotations are degrees)",
                        "/calc cos(0)",
                        "/echo $-sin(client.yaw) * cos(client.pitch)$ equals $client.look.x$",
                        "§7Degrees, not radians:§r client.yaw and client.pitch feed straight in.",
                        "§7The composed example§r rebuilds client.look.x by hand — how yaw and pitch become a look vector."),
                doc("tan", Group.MATH, "tan(deg)",
                        "tangent, in DEGREES",
                        "/calc tan(45)",
                        "/echo ground hit ~$round(1.62 / tan(client.pitch))$ blocks ahead (while looking down)",
                        "§7Look-ahead:§r looking DOWN at pitch p, level ground sits about 1.62/tan(p) blocks ahead — eye height over slope."),

                // ---- Text ----
                doc("len", Group.TEXT, "len(x)",
                        "length of text, or element count of a list",
                        "/calc len(\"minecraft\")",
                        "/echo $len(entities(16))$ entities within 16 blocks",
                        "§7Dual:§r len(\"creeper\") = 7 · len(blockset(#minecraft:wool)) = 16 — one function, both kinds."),
                doc("trim", Group.TEXT, "trim(text)",
                        "strip whitespace from both ends",
                        "/calc trim(\"  hi  \")",
                        "/calc len(trim(\"  hi  \"))",
                        "§7Edges only:§r inner spaces stay — trim(\"  a b  \") is \"a b\"."),
                doc("upper", Group.TEXT, "upper(text)",
                        "ALL CAPS",
                        "/calc upper(\"stop\")",
                        "/echo $upper(replace(client.dimension, \"minecraft:\", \"\"))$!",
                        "§7For display§r — for case-insensitive COMPARISONS, lower both sides instead (see lower)."),
                doc("lower", Group.TEXT, "lower(text)",
                        "all lowercase",
                        "/calc lower(\"STOP\")",
                        "/calc lower(\"Zombie\") == \"zombie\"",
                        "§7Case-insensitive compare:§r lower(a) == lower(b) matches regardless of capitalization."),
                doc("substr", Group.TEXT, "substr(text, start[, count])",
                        "part of a text — 0-based start, clamped; omit count for \"to the end\"",
                        "/calc substr(\"minecraft:oak_log\", 10)",
                        "/calc substr(\"creeper\", len(\"creeper\") - 3)",
                        "§7Clamped, not fussy:§r a start or count past the end just gives what's there — no error.",
                        "§7Namespace tip:§r vanilla ids are \"minecraft:\" + 10 characters in, so substr(id, 10) strips the default namespace (replace(...) is the general tool)."),
                doc("replace", Group.TEXT, "replace(text, find, new)",
                        "swap every occurrence of find — literal text, not a pattern",
                        "/calc replace(\"oak_log\", \"oak\", \"birch\")",
                        "/echo holding $replace(client.held.id, \"minecraft:\", \"\")$",
                        "§7All occurrences,§r plain-text matching. The text to find can't be empty."),

                // ---- Random ----
                doc("rand", Group.RANDOM, "rand(min, max) · rand(list)",
                        "a random whole number (inclusive both ends), or one element of a list",
                        "/echo $rand(1, 64)$",
                        "/give @s $rand(itemset(#minecraft:planks))$ 16",
                        "§7Inclusive:§r rand(1, 64) can yield both 1 and 64.",
                        "§7rand(list):§r a uniform pick from ANY list — registry sets, range(...), client.effects.",
                        "§7rand vs pick:§r rand samples a range or one list; pick chooses between options YOU wrote out.",
                        "§7Fresh every resend:§r history keeps the original $...$, so a resend re-rolls."),
                doc("randf", Group.RANDOM, "randf(min, max)",
                        "a random decimal in [min, max)",
                        "/echo $randf(0, 1)$",
                        "/tp @s ~$randf(-0.5, 0.5)$ ~ ~$randf(-0.5, 0.5)$",
                        "§7Half-open:§r min can come out, max can't — the standard convention. Use rand for whole numbers."),
                doc("pick", Group.RANDOM, "pick(a | b | c)",
                        "one of the options you wrote, chosen at random",
                        "/echo $pick(\"heads\" | \"tails\")$",
                        "/summon $pick(\"zombie\" | \"skeleton\" | rand(entityset(#minecraft:skeletons)))$",
                        "§7Options are full expressions§r separated by a single top-level | (|| is still boolean-or) — they nest and compute: pick(rand(1,5) | client.pos.y).",
                        "§7Quote literal text:§r pick(\"say hi\" | \"say nah\").",
                        "§7pick vs rand:§r a set is ONE value, so pick(entityset(...)) is one option holding a whole list — rand(entityset(...)) is how you draw one member."),

                // ---- Lists ----
                doc("range", Group.LISTS, "range(start, stop[, step])",
                        "an inclusive whole-number list; step optional, direction automatic",
                        "/calc range(1, 5)",
                        "#foreach $y$ in range(60, 100, 10) (/setblock ~ $y$ ~ minecraft:glass)",
                        "§7Inclusive both ends:§r range(1, 5) = 1 2 3 4 5 · range(10, 0, -2) counts down by 2.",
                        "§7No step?§r direction is inferred — range(5, 1) already counts down.",
                        "§7Built for #foreach§r — and rand(range(0, 100, 10)) is a random multiple of 10."),
                doc("nth", Group.LISTS, "nth(list, i)",
                        "element i of a list, 0-based",
                        "/calc nth(range(10, 20), 0)",
                        "#setdefault $i$ = 0 && /setblock ~ ~-1 ~ $nth(blockset(#minecraft:wool), i % 16)$ && #set $i$ = i + 1",
                        "§70-based:§r nth(list, 0) is the FIRST element; out of range errors and tells you the size.",
                        "§7Cycling:§r nth(list, i % len(list)) walks a list forever as i grows — the composed example steps through the wools, one per resend."),
                doc("contains", Group.LISTS, "contains(list, value)",
                        "is value in the list? — membership by ==",
                        "/calc contains(range(1, 5), 3)",
                        "#if (contains(blockset(#minecraft:logs), client.target.block)) (/echo that's a log)",
                        "§7Membership by ==:§r numbers match numbers, text matches text; a differently-typed element simply doesn't match."),
                doc("indexof", Group.LISTS, "indexof(list, value)",
                        "0-based position of the first == match, or -1",
                        "/calc indexof(range(10, 20), 15)",
                        "/calc indexof(blockset(#minecraft:wool), client.target.block)",
                        "§7The inverse of nth:§r nth(list, indexof(list, v)) gets v back; -1 means \"not there\" — gate with it.",
                        "§7The composed example§r numbers the wool you're aiming at (and -1 for anything that isn't wool)."),

                // ---- Registry sets ----
                doc("blockset", Group.SETS, "blockset(members...)",
                        "block ids from tags and/or ids — one deduped list",
                        "/calc blockset(#minecraft:logs)",
                        "#foreach $b$ in blockset(\"oak_planks\", #minecraft:wool) (/give @s $b$)",
                        setDetail("blockset", "#minecraft:logs", "stone", "every block in the game.")),
                doc("itemset", Group.SETS, "itemset(members...)",
                        "item ids from tags and/or ids — one deduped list",
                        "/calc itemset(#minecraft:planks)",
                        "/give @s $rand(itemset(#c:ores))$ 8",
                        setDetail("itemset", "#minecraft:planks", "stick", "every item in the game.")),
                doc("effectset", Group.SETS, "effectset(members...)",
                        "effect ids from tags and/or ids — one deduped list",
                        "/calc len(effectset())",
                        "/effect give @s $rand(effectset())$ 30 1",
                        setDetail("effectset", "#...", "speed", "every status effect — rand(effectset()) is a mystery potion.")),
                doc("entityset", Group.SETS, "entityset(members...)",
                        "entity type ids from tags and/or ids — one deduped list",
                        "/calc entityset(#minecraft:skeletons)",
                        "/summon $rand(entityset(#minecraft:skeletons))$ ~ ~ ~",
                        setDetail("entityset", "#minecraft:skeletons", "minecraft:zombie", "every entity type in the game.")),

                // ---- Vectors ----
                doc("vec", Group.VECTORS, "vec(x, y, z)",
                        "three numbers as one vec3 value (\"x y z\")",
                        "/calc vec(0, 64, 0)",
                        "/echo ground below: $raycast(client.eye_pos, vec(0, -1, 0), 100)$",
                        "§7The literal-position spelling:§r vec(0, 64, 0) beats \"0 64 0\" because the components get to be EXPRESSIONS: vec(x, 64, z).",
                        "§7Feeds anything that takes a vec3:§r raycast origins and directions, block(...), component(...)."),
                doc("component", Group.VECTORS, "component(v, axis)",
                        "one component of a vec3 — axis is \"x\", \"y\", or \"z\"",
                        "/calc component(vec(1, 2, 3), \"y\")",
                        "/echo aim height: $component(raycast(100), \"y\")$",
                        "§7For COMPUTED vecs:§r a spelled-out address has a dotted form (client.pos.x) — component is for RESULTS: component(raycast(100), \"y\").",
                        "§7Exact:§r keeps the full-precision number, no rounding.",
                        "§7Gate sentinels:§r \"miss\" isn't a vec3 — check == \"miss\" before pulling components off a raycast."),

                // ---- World ----
                doc("block", Group.WORLD, "block(x, y, z) · block(\"x y z\")",
                        "the block id at a position, from your client's synced world",
                        "/calc block(0, 64, 0)",
                        "#if (block(client.blockpos) == \"minecraft:water\") (/echo swimming in it)",
                        "§7Both spellings:§r three numbers, or one vec3 — block(client.target.blockpos) reads what you're aiming at.",
                        "§7Decimals floor§r (block coordinates always do), and an unloaded chunk errors LOUDLY — never a guess.",
                        "§7Id only:§r no states or NBT — \"minecraft:oak_stairs\" whichever way it faces.",
                        "§7No round trip:§r this is /execute if block folded into the expression world, with a real #else."),
                doc("raycast", Group.WORLD, "raycast(dist) · raycast(origin, dir, dist)",
                        "where your look (or any ray) hits — \"x y z\", or \"miss\"",
                        "/echo aiming at $raycast(100)$",
                        "/particle minecraft:flame $raycast(30)$",
                        "§7One argument:§r cast from your eyes along your look, up to dist blocks — your crosshair as a value, past normal reach.",
                        "§7Three arguments:§r any ray — origin and dir are vec3s (dir is normalized for you): raycast(client.eye_pos, client.look, 60) is the long-hand of raycast(60).",
                        "§7Hits what your crosshair hits:§r collidable blocks, sub-block accurate; passes through grass and fluids.",
                        "§7\"miss\" never throws§r — gate with == \"miss\"."),
                doc("raycast_block", Group.WORLD, "raycast_block(dist)",
                        "the block ID your look hits, or \"miss\"",
                        "/echo looking at $raycast_block(100)$",
                        "#if (raycast_block(6) == \"minecraft:diamond_ore\") (/echo &bdiamonds!)",
                        "§7raycast tells you WHERE, raycast_block tells you WHAT§r — same cast, different answer.",
                        "§7Spelled-out sibling:§r client.target.block is the crosshair's block id at normal reach; raycast_block takes any distance."),

                // ---- Entities ----
                doc("entity", Group.ENTITIES, "entity(selector, field[, fallback])",
                        "one field of one entity — the subject as a value",
                        "/echo $entity(\"target\", \"health\")$ hp",
                        "/echo nearest: $entity(nearest_entity(16), \"type\")$",
                        "§7Selector:§r \"self\", \"target\", or a UUID — from raycast_entity, entities, nearest_entity, or client.target.uuid.",
                        "§7Fields:§r type, uuid, name, health, pos, blockpos, nbt.<path> — the SAME vocabulary as client.<field> and client.target.<field>. This is the computed-subject spelling of that vocabulary.",
                        "§7Fallback:§r a third argument replaces ANY failed read — absent NBT (an undamaged item has no minecraft:damage), no target, a UUID that left render distance. That's what keeps a tick script from faulting."),
                doc("raycast_entity", Group.ENTITIES, "raycast_entity(dist)",
                        "the UUID of the entity your look hits, or \"miss\"",
                        "/echo $raycast_entity(30)$",
                        "/echo $entity(raycast_entity(30), \"name\", \"nothing\")$ in the crosshair",
                        "§7Feed it to entity(...):§r entity(raycast_entity(30), \"health\") — aim-at-anything, past normal reach.",
                        "§7Gate:§r == \"miss\" when nothing is on the line (or use entity's fallback, like the composed example)."),
                doc("entities", Group.ENTITIES, "entities(radius[, type])",
                        "UUIDs of everything within radius blocks — a list",
                        "/echo $len(entities(16))$ nearby",
                        "#foreach $e$ in entities(8, \"minecraft:zombie\") (/echo zombie at $entity(e, \"blockpos\")$)",
                        "§7Spherical,§r centered on you, read from the client's synced world (~render distance).",
                        "§7type filters:§r \"zombie\" or \"minecraft:zombie\". No matches = an empty list, so len(...) and #foreach compose instead of erroring."),
                doc("nearest_entity", Group.ENTITIES, "nearest_entity(radius[, type])",
                        "the closest entity's UUID within radius, or \"miss\"",
                        "/echo $nearest_entity(16)$",
                        "/echo closest drop: $entity(nearest_entity(16, \"minecraft:item\"), \"pos\", \"none\")$",
                        "§7entities(...) finds ALL, nearest_entity finds ONE§r — same radius and type rules, plus the \"miss\" sentinel to gate on."),

                // ---- Slots ----
                doc("slot", Group.SLOTS, "slot(slot, field)",
                        "one field of one of your slots — the slot as a value",
                        "/echo $slot(\"armor.chest\", \"durability\")$",
                        "#for $i$ in 0..8 (/echo hotbar $i$: $slot(\"hotbar.\" + i, \"id\")$)",
                        "§7Slot names are /item replace names:§r hotbar.0-8, inventory.0-26 (0-8 is the TOP row), armor.head/chest/legs/feet, weapon.mainhand/offhand.",
                        "§7Fields:§r id, count, durability, max_durability. An empty slot reads \"empty\" and 0 — never an error.",
                        "§7Dotted counterpart:§r a spelled-out slot reads better as client.slot.<slot>.<field> (it tab-completes); slot(...) is for COMPUTED slots, like the loop in the composed example. client.held.* / client.offhand.* are the hand shorthands. All one reader — the spellings can't disagree."));
    }
}
