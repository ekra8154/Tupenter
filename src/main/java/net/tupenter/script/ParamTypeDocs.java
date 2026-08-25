package net.tupenter.script;

import net.tupenter.script.AliasDefinition.ParamType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE registry of custom-command parameter types — one doc per
 * {@link ParamType} constant, same contract as {@link BuiltinFunctions}: the
 * /customcommand help pages render from it, fromKeyword's error message lists
 * it, and completeness is enforced (static init throws on an undocumented
 * constant; ParamTypeDocsTest keeps keywords round-tripping through
 * fromKeyword). Add a ParamType without a doc and nothing that uses types
 * even loads.
 *
 * <p>{@code example} is a full, runnable {@code /customcommand add} line
 * showing the type earning its keep — pages make it click-to-chat-bar.
 */
public final class ParamTypeDocs {

    /**
     * {@code keyword} is what you type after the colon; {@code synonyms} also
     * parse. CHOICE is the one type with no keyword at all — you write the
     * options themselves — so its "choice" keyword exists only as a help-page
     * name and is never fed to fromKeyword.
     */
    public record Doc(ParamType type, String keyword, List<String> synonyms, String group,
                      String blurb, List<String> detail, String example) {
    }

    /** Every param type, in index order (grouped). */
    public static final List<Doc> ALL = buildAll();

    private static final Map<ParamType, Doc> BY_TYPE = indexByType();
    private static final Map<String, Doc> BY_KEYWORD = indexByKeyword();

    private ParamTypeDocs() {
    }

    public static Doc of(ParamType type) {
        return BY_TYPE.get(type);
    }

    /** The doc whose keyword or synonym is {@code name} (case-insensitive), or null. */
    public static Doc find(String name) {
        return BY_KEYWORD.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The canonical keyword of every type, for tab-completion after a ":".
     * CHOICE is left out for the same reason it is left out of keywordSummary:
     * you write the options themselves, there is no keyword to type.
     */
    public static List<String> keywords() {
        List<String> keywords = new ArrayList<>();
        for (Doc doc : ALL) {
            if (doc.type() == ParamType.CHOICE) {
                continue;
            }
            // CANONICAL only. Offering "blockpos" and "block_pos" side by side
            // reads as two types rather than two spellings of one; the synonyms
            // still parse, they just aren't taught.
            keywords.add(doc.keyword());
        }
        return keywords;
    }

    /** Every keyword, for fromKeyword's error message — ends with the CHOICE special case. */
    public static String keywordSummary() {
        StringBuilder summary = new StringBuilder();
        for (Doc doc : ALL) {
            if (doc.type() == ParamType.CHOICE) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(doc.keyword());
        }
        return summary + " — or a comma list of your own options: <dim:to_overworld,to_nether>";
    }

    private static Map<ParamType, Doc> indexByType() {
        Map<ParamType, Doc> byType = new EnumMap<>(ParamType.class);
        for (Doc doc : ALL) {
            if (byType.put(doc.type(), doc) != null) {
                throw new IllegalStateException("duplicate param type doc: " + doc.type());
            }
        }
        for (ParamType type : ParamType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("ParamType." + type + " has no doc — document it in ParamTypeDocs");
            }
        }
        return byType;
    }

    private static Map<String, Doc> indexByKeyword() {
        Map<String, Doc> byKeyword = new LinkedHashMap<>();
        for (Doc doc : ALL) {
            byKeyword.put(doc.keyword(), doc);
            for (String synonym : doc.synonyms()) {
                byKeyword.put(synonym, doc);
            }
        }
        return byKeyword;
    }

    private static Doc doc(ParamType type, String keyword, List<String> synonyms, String group,
                           String blurb, String example, String... detail) {
        return new Doc(type, keyword, synonyms, group, blurb, List.of(detail), example);
    }

    private static List<Doc> buildAll() {
        return List.of(
                // ---- Text ----
                doc(ParamType.STRING, "string", List.of(), "Text",
                        "a word, or \"anything quoted\" — what a bare <name> means",
                        "/customcommand add announce <msg> = #chat [!] $msg$",
                        "§7The default:§r <msg> and <msg:string> are the same thing.",
                        "§7Quotes let anything through§r — spaces, selectors: /announce \"back in 5\". Unquoted, it grabs one word.",
                        "§7Loose:§r it accepts any next token, so an OPTIONAL string can't be skipped mid-command — strict types can (see /customcommand help optionals)."),
                doc(ParamType.WORD, "word", List.of(), "Text",
                        "one strict token: letters, digits, _-.+ only",
                        "/customcommand add label <tag:word> = /echo tagged: $tag$",
                        "§7Strict on purpose:§r no quotes, no selectors, no spaces — when you want a clean identifier and nothing else.",
                        "§7Need @-selectors?§r use <n:selector>. Need spaces? <n:string> quoted, or <n:text> for the rest of the line."),
                doc(ParamType.TEXT, "text", List.of(), "Text",
                        "the rest of the line, spaces and all — must be last",
                        "/customcommand add shout <what:text> = #chat $what$!!!",
                        "§7Greedy:§r everything after the earlier params is yours, no quotes needed.",
                        "§7Must be last§r — nothing can come after it, and an optional text can never be skipped."),

                // ---- Numbers ----
                doc(ParamType.INT, "int", List.of(), "Numbers",
                        "a whole number",
                        "/customcommand add waves <count:int> <mob:entity> = #repeat $count$ (/summon $mob$ ~ ~ ~)",
                        "§7Validated at the prompt:§r /waves abc zombie errors before anything runs.",
                        "§7Strictly typed,§r so an optional <r:int=5> can be skipped mid-command (see /customcommand help optionals)."),
                doc(ParamType.FLOAT, "float", List.of(), "Numbers",
                        "a decimal number",
                        "/customcommand add slowmo <rate:float=5> = /tick rate $rate$",
                        "§7Accepts whole numbers too§r — int is for when a decimal would be WRONG, float for when it's welcome."),
                doc(ParamType.TIME, "time", List.of(), "Numbers",
                        "a duration — 10t / 1.5s / 2m / 3d — binds as TICKS",
                        "/customcommand add later <delay:time> <cmd:text> = #wait $delay$ && $cmd$",
                        "§7Units:§r t = ticks, s = seconds, m = minutes, d = days; a plain number is ticks.",
                        "§7Binds the tick count§r (1.5s → 30), so it feeds #wait and math directly."),

                // ---- Positions & rotation ----
                doc(ParamType.POS, "pos", List.of("vec3"), "Positions & rotation",
                        "a PRECISE position: three decimal coords, ~ works",
                        "/customcommand add mark <p:pos=~ ~ ~> = /particle minecraft:flame $p$",
                        "§7Binds a tuple:§r $p$ = \"x y z\" joined, plus $p.x$ $p.y$ $p.z$ for math.",
                        "§7pos is precise, blockpos is whole§r — the same split as client.pos / client.blockpos.",
                        "§7vec3 is a synonym§r — the honest keyword when the value is a direction, not a place."),
                doc(ParamType.BLOCKPOS, "blockpos", List.of("block_pos"), "Positions & rotation",
                        "a block position: three WHOLE coords, ~ and targeted-block tab-complete",
                        "/customcommand add drill <at:blockpos> = /setblock $at$ minecraft:air",
                        "§7Binds a tuple:§r $at$ = \"x y z\" joined, plus $at.x$ $at.y$ $at.z$.",
                        "§7Tab-complete offers the block you're looking at§r — same as vanilla /setblock."),
                doc(ParamType.COLUMN_POS, "column_pos", List.of("column"), "Positions & rotation",
                        "an x z column: two whole coords, ~ works",
                        "/customcommand add chunkof <c:column_pos=~ ~> = /echo chunk $floor(c.x / 16)$ $floor(c.z / 16)$",
                        "§7Binds a tuple:§r $c$ = \"x z\" joined, plus $c.x$ $c.z$ — no y, that's the point."),
                doc(ParamType.ROTATION, "rotation", List.of(), "Positions & rotation",
                        "yaw pitch: two decimals, ~ works",
                        "/customcommand add face <r:rotation> = /tp @s ~ ~ ~ $r$",
                        "§7Binds a tuple:§r $r$ = \"yaw pitch\" joined, plus $r.yaw$ $r.pitch$."),
                doc(ParamType.ANGLE, "angle", List.of(), "Positions & rotation",
                        "one yaw angle, ~ works — binds a plain number",
                        "/customcommand add spin <a:angle=~180> = /tp @s ~ ~ ~ $a$ ~",
                        "§7A number, not a tuple§r — ~180 means \"my yaw plus 180\", the about-face."),

                // ---- Players & entities ----
                doc(ParamType.PLAYER, "player", List.of(), "Players & entities",
                        "a player name, tab-completed from who's online",
                        "/customcommand add greet <who:player> \"wave at someone\" = /me waves at $who$",
                        "§7A NAME, not a selector§r — for @-selectors use <n:selector>."),
                doc(ParamType.SELECTOR, "selector", List.of(), "Players & entities",
                        "an @-selector, validated and tab-completed — no quotes needed",
                        "/customcommand add zap <t:selector> = /execute at $t$ run summon minecraft:lightning_bolt",
                        "§7Full selector syntax:§r @e[type=!player,limit=1] parses as ONE argument, brackets and all.",
                        "§7Binds the selector text§r — the SERVER resolves it when your body's command runs."),
                doc(ParamType.ENTITY, "entity", List.of(), "Players & entities",
                        "an entity type id, /summon-style tab-complete",
                        "/customcommand add spawn3 <mob:entity> = #repeat 3 (/summon $mob$ ~ ~ ~)",
                        "§7The registry backs the tab-complete§r — every entity type the server knows."),

                // ---- Ids & sets ----
                doc(ParamType.ID, "id", List.of("resource"), "Ids & sets",
                        "any namespaced id: minecraft:stone, mymod:thing",
                        "/customcommand add noted <x:id> = /echo noted: $x$",
                        "§7The general form§r — when item/block/entity are too specific. No registry check, just id syntax."),
                doc(ParamType.ITEM, "item", List.of(), "Ids & sets",
                        "an item id + optional [components], registry tab-complete",
                        "/customcommand add gimme <it:item> <n:int=1> = /give @s $it$ $n$",
                        "§7What /give takes§r — [components] ride along intact."),
                doc(ParamType.BLOCK, "block", List.of(), "Ids & sets",
                        "a block id + optional [state], registry tab-complete",
                        "/customcommand add carpet <b:block> = /fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 $b$",
                        "§7What /setblock takes§r — [state] rides along intact."),
                doc(ParamType.ITEMSET, "itemset", List.of(), "Ids & sets",
                        "an item id OR a #item_tag — pairs with itemset(...)",
                        "/customcommand add lucky <pool:itemset> = /give @s $rand(itemset(pool))$",
                        "§7One param, both shapes:§r /lucky diamond and /lucky #minecraft:planks both work — itemset($pool$) reads either back as a set.",
                        "§7Tab-completes§r items AND #tags."),
                doc(ParamType.BLOCKSET, "blockset", List.of(), "Ids & sets",
                        "a block id OR a #block_tag — pairs with blockset(...)",
                        "/customcommand add sampler <of:blockset> = #foreach $b$ in blockset(of) (/give @s $b$)",
                        "§7One param, both shapes:§r a concrete block or a whole #tag — blockset($of$) reads either back as a set.",
                        "§7Tab-completes§r blocks AND #tags."),
                doc(ParamType.DIMENSION, "dimension", List.of(), "Ids & sets",
                        "a dimension id, tab-completed from the worlds the client knows",
                        "/customcommand add visit <d:dimension> = /execute in $d$ run tp @s 0 100 0",
                        "§7What /execute in takes§r — minecraft:overworld, the_nether, the_end, plus modded."),
                doc(ParamType.COLOR, "color", List.of(), "Ids & sets",
                        "one of the 16 chat colors, tab-completed",
                        "/customcommand add team <c:color> = /echo joining the $c$ team",
                        "§7Binds the color NAME§r (red, aqua, ...) — vanilla's /team modify color vocabulary."),

                // ---- Fixed choices ----
                doc(ParamType.CHOICE, "choice", List.of(), "Fixed choices",
                        "your own fixed list — written as the options themselves",
                        "/customcommand add mode <m:on,off> = /echo mode is $m$",
                        "§7No keyword:§r ANY comma list after the colon is a choice — <dim:to_overworld,to_nether> — tab-completed, anything else rejected.",
                        "§7Strictly typed,§r so an optional choice can be skipped mid-command; a sentinel option like =any makes \"omitted\" mean something (see /customcommand help optionals)."),
                doc(ParamType.BOOL, "bool", List.of("boolean"), "Fixed choices",
                        "true/false, tab-completed — binds a real boolean for #if and ternaries",
                        "/customcommand add nv <on:bool=true> = #if ($on$) (/effect give @s night_vision infinite 0 true) #else (/effect clear @s night_vision)",
                        "§7A real boolean,§r not text — #if ($on$) works directly, no == \"true\" needed.",
                        "§7Strictly typed,§r so <g:bool=false> is skippable mid-command: /launch snowball true."));
    }
}
