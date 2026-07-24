package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE registry of # directives — same contract as {@link BuiltinFunctions} and
 * {@link ParamTypeDocs}: the help pages render from it, and DirectiveDocsTest
 * holds it equal to {@link ScriptParser#knownDirectiveWords()} (the parser's
 * own enumerable vocabulary), so a directive can't exist undocumented and the
 * docs can't name one that doesn't parse. #stage/#unstage/#pid are handled
 * client-side rather than by the parser — {@code parserWord} says which is
 * which, and the test asserts BOTH directions against isDirectiveLine.
 *
 * <p>#elseif and #else are continuations of #if, not statement heads — they're
 * documented on the if page, not as entries.
 */
public final class DirectiveDocs {

    public enum Group {
        VARIABLES("Variables"),
        LOOPS("Loops"),
        CONDITIONS("Conditions"),
        TIMING("Timing"),
        FUNCTIONS("Functions"),
        PREFIXES("Prefixes & output");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code name} has no leading #; {@code shorthand} is the #s-style alias or null. */
    public record Doc(String name, Group group, String signature, String blurb, String shorthand,
                      boolean parserWord, List<String> detail, String exampleSimple, String exampleComposed) {

        public String canonical() {
            return "#" + name;
        }
    }

    /** Every directive, in index order (grouped). */
    public static final List<Doc> ALL = buildAll();

    private static final Map<String, Doc> BY_NAME = buildIndex();

    private DirectiveDocs() {
    }

    /** The doc named {@code name} (case-insensitive, leading # tolerated), or null. */
    public static Doc find(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("#")) {
            key = key.substring(1);
        }
        return BY_NAME.get(key);
    }

    private static Map<String, Doc> buildIndex() {
        Map<String, Doc> index = new LinkedHashMap<>();
        for (Doc doc : ALL) {
            if (index.put(doc.name(), doc) != null) {
                throw new IllegalStateException("duplicate directive doc: " + doc.name());
            }
        }
        return index;
    }

    private static Doc doc(String name, Group group, String signature, String blurb, String shorthand,
                           boolean parserWord, String exampleSimple, String exampleComposed, String... detail) {
        return new Doc(name, group, signature, blurb, shorthand, parserWord, List.of(detail), exampleSimple, exampleComposed);
    }

    private static List<Doc> buildAll() {
        return List.of(
                // ---- Variables ----
                doc("set", Group.VARIABLES, "#set name = value",
                        "a session variable — lives until you leave the world", null, true,
                        "#set x = rand(1,10) && /give @s stick $x$ && /echo got $x$!",
                        "#set home = client.blockpos && /tupenter var save home",
                        "§7Bare name is normal:§r #set x = 5. The name is x — $x$ is just x with EXPLICIT wrapping. Both the target (#set x = / #set $x$ =) and reads in expression world (#if (x > 3), x + 1, function args) take the bare name.",
                        "§7$...$ is the door from command/chat text§r — there text is literal, so /give @s stick $x$ needs the wrapping to substitute the value (plain 'stick x' is the letter x). Same reason #repeat bodies write $i$.",
                        "§7Compound forms:§r #set x += 1 (also -= *= /= %=) · dotted groups organize: #set hitlist.bob = \"wanted\".",
                        "§7Session-scoped:§r cleared when you leave, unless kept with /tupenter var save <name> (the composed example makes a permanent home).",
                        "§7Prints a notice§r when it sets — #silent mutes it. For a value that shouldn't outlive the line, use #local instead."),
                doc("local", Group.VARIABLES, "#local name = value",
                        "the workhorse: compute ONCE, use many times, save NOTHING", null, true,
                        "#local r = rand(1, 5) && /give @s stick $r$ && /echo gave $r$ (same roll, both places)",
                        "#local hit = raycast_entity(30) && #if (hit != \"miss\") (/echo $entity(hit, \"name\")$ · $entity(hit, \"health\")$ hp)",
                        "§7Line-scoped and silent:§r nothing written to the session, no notice printed — a tick script using #local stays stateless between runs.",
                        "§7One evaluation:§r every later read sees the SAME value — a rand doesn't re-roll, a raycast doesn't re-cast (the composed example reads two fields off one hit).",
                        "§7Bare name is normal§r — #local hit, then hit in the condition and hit inside entity(...); $r$ only where it substitutes into command text.",
                        "§7The choosing rule:§r #local by default; #set only when the value must OUTLIVE the line."),
                doc("setdefault", Group.VARIABLES, "#setdefault name = value",
                        "set only if not already defined — idempotent init", null, true,
                        "#setdefault maxy = 80 && /echo building up to $maxy$",
                        "#setdefault runs = 0 && #set runs = runs + 1 && /echo run $runs$ this session",
                        "§7Already defined§r means session, saved, or live — an existing value always wins.",
                        "§7A tunable knob:§r #setdefault seeds the value once and then leaves it, so a later #set — even one you type in chat while a tick loop is running — wins and the loop reads the NEW value on its next pass. It never stomps your change back to the default. Rule of thumb: #set what the script OWNS, #setdefault what the PLAYER tunes.",
                        "§7Bare name throughout:§r runs reads bare in the #set arithmetic — $runs$ only where it lands in command text.",
                        "§7The stateful-command idiom:§r first run initializes, every run advances — paste it anywhere, no separate setup line.",
                        "§7Only track what the game DOESN'T tell you:§r for live state read the real variable — $world.frozen$, $client.held.id$, $client.riding$. A self-tracked flag drifts the moment anything else changes it (or you rejoin), and then your toggle does the opposite of what you meant.",
                        "§7Create-once-across-sessions:§r #setdefault x = 0 && /tupenter var save x."),

                // ---- Loops ----
                doc("repeat", Group.LOOPS, "#repeat N (body)",
                        "run the body N times — $i$ counts 1..N", null, true,
                        "#repeat 5 (/say Tick $i$!)",
                        "#repeat 3 (/summon minecraft:zombie ~ ~ ~ && #wait 1s)",
                        "§7$i$ is provided§r — 1-based, so it reads naturally in chat output.",
                        "§7Paced automatically:§r a body that sends or waits spreads over ticks (Max Commands Per Tick) — /tupenter running shows it, /tupenter abort stops it."),
                doc("for", Group.LOOPS, "#for $x$ in a..b [step s] (body)",
                        "count a whole-number range, inclusive — direction automatic", null, true,
                        "#for $x$ in 1..10 step 2 (/summon minecraft:zombie ~$x$ ~ ~)",
                        "#for $i$ in 0..8 (/echo hotbar $i$: $slot(\"hotbar.\" + i, \"id\")$)",
                        "§7Inclusive both ends,§r and 10..1 counts down without being told.",
                        "§7Bounds are expressions:§r #for $y$ in client.blockpos.y..client.blockpos.y+10 works."),
                doc("foreach", Group.LOOPS, "#foreach $x$ in (a | b | c) (body) · #foreach $x$ in <list> (body)",
                        "walk options you wrote, or any LIST value", null, true,
                        "#foreach $m$ in (zombie | skeleton) (/summon $m$)",
                        "#foreach $b$ in blockset(#minecraft:wool) (/give @s $b$)",
                        "§7Two sources:§r (a | b | c) options written in place, or any list — range(1, 10), registry sets, entities(radius), client.effects.",
                        "§7The set pairing§r is the classic: every member of a #tag, one command each."),
                doc("while", Group.LOOPS, "#while (condition) (body)",
                        "repeat while the condition holds — re-checked each pass, runs across ticks", null, true,
                        "#while (client.health < 20) (/effect give @s minecraft:regeneration 1 1 && #wait 3s)",
                        "#while (true) (/echohud &7light &f$client.light$ &7· &f$client.speed$&7 b/s && #wait 1t)",
                        "§7$i$ counts iterations§r — don't #set your own counter.",
                        "§7Pace it with #wait§r — the condition re-reads live state each pass, so the simple example is a regen drip that stops itself at 20 hp.",
                        "§7#while (true)§r + /echohud is the live-HUD idiom; /tupenter abort <id> ends it.",
                        "§7Runaway guard:§r a loop that sends or waits runs as long as it needs; only a loop that does NEITHER hits Max Loop Iterations."),

                // ---- Conditions ----
                doc("if", Group.CONDITIONS, "#if (cond) (then) #elseif (cond) (…) #else (…)",
                        "branch on a condition — client-side, instant, with a real #else", null, true,
                        "#if (client.pos.y > 60) (/say high) #elseif (client.pos.y > 30) (/say mid) #else (/say low)",
                        "#if (client.target.hit == \"block\" && client.target.block == \"minecraft:diamond_ore\") (/echo &bfound it)",
                        "§7Conditions are expressions§r — the $ $ around names is optional there, and any function composes in.",
                        "§7#elseif chains, #else closes§r — they continue an #if, they can't start a line.",
                        "§7Absent-not-wrong:§r transient world state that's missing (no crosshair target, unloaded UUID) reads FALSE in a condition instead of erroring — that's what lets a tick script poll client.target.health bare."),

                // ---- Timing ----
                doc("wait", Group.TIMING, "#wait 10t / 1.5s / 2m / 3d [realtime]",
                        "pause the script mid-line — everything else keeps running", null, true,
                        "/say ready && #wait 3s && /say GO",
                        "#wait 5m realtime && /echohud &ecows are ready to be fed!",
                        "§7Units:§r t = ticks, s = seconds, m = minutes, d = days · max 72000t (one real-time hour) · works in chains, groups, loops, and custom command bodies.",
                        "§7Lazy evaluation:§r $...$ after a #wait reads state at RESUME time — /attribute ... && #wait 2t && /tp @s $client.target.blockpos$ sees the world after the boost landed.",
                        "§7Two clocks:§r default counts WORLD ticks (sprints under /tick sprint, halts under /tick freeze and pause); add §frealtime§r for wall-clock no matter the TPS."),

                // ---- Functions ----
                doc("return", Group.FUNCTIONS, "#return <expression>",
                        "set a function's value and unwind — function bodies only", null, true,
                        "/customfunction add clamp <x:float> = #if (x > 100) (#return 100) && #if (x < 0) (#return 0) && x",
                        "/customfunction add sign <x:float> = #if (x > 0) (#return 1) && #if (x < 0) (#return -1) && 0",
                        "§7A function's value§r is your #return — or the last expression when no #return fires (both examples use the trailing-expression fallback as the \"else\").",
                        "§7Function bodies only:§r anywhere else it errors by design — the word is reserved so the error can say why."),

                // ---- Prefixes & output ----
                doc("silent", Group.PREFIXES, "#silent <line> · #silent (group)",
                        "hide command feedback — the whole line, or just one group", "#s", true,
                        "#silent /time set day",
                        "#silent (/give @s minecraft:stick 64) && /say restocked",
                        "§7Two shapes:§r prefix the LINE, or wrap one (group) mid-chain — the composed example mutes the give but not the say.",
                        "§7Also mutes #set notices.§r Prefixes combine: #norecord #silent /say hi."),
                doc("norecord", Group.PREFIXES, "#norecord <line>",
                        "run the line but keep it OUT of resend history", "#nr", true,
                        "#norecord /spawnpoint",
                        "#norecord #silent /gamemode creative",
                        "§7For one-offs§r you don't want the resend key (R) to repeat.",
                        "§7Bake it into a command:§r start a custom command's BODY with it and the command decides for itself — /customcommand add oneshot = #norecord /kill @e[type=item] — so you never type the prefix. Applies to a bare invocation of that command."),
                doc("record", Group.PREFIXES, "#record <line>",
                        "the inverse: record even when message tracking is OFF (bypasses the filter)", "#r", true,
                        "#record /tp @s ~ ~10 ~",
                        "#record #silent /fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 minecraft:glass",
                        "§7The opt-in§r when tracking is disabled but THIS line should be resendable.",
                        "§7Bake it into a command:§r a body starting with #record makes a command you plan to repeat always resendable — /customcommand add nextwave = #record /summon minecraft:zombie ~ ~ ~ — no prefix to remember."),
                doc("stage", Group.PREFIXES, "#stage <line>",
                        "put the line INTO resend history WITHOUT running it", "#st", false,
                        "#stage /tp @s 0 100 0",
                        "#stage #repeat 10 (/summon minecraft:zombie ~ ~ ~)",
                        "§7Load the resend key:§r press R when you actually want it — an escape hatch, a panic teleport, a prepared burst."),
                doc("unstage", Group.PREFIXES, "#unstage [n]",
                        "remove the newest n entries (default 1) from resend history", "#ust", false,
                        "#unstage",
                        "#unstage 3",
                        "§7Reports what the next resend is§r after removing, so you always know what R will do."),
                doc("chat", Group.PREFIXES, "#chat <message>",
                        "send a normal chat message that EVALUATES its $...$", "#c", true,
                        "#chat my coords are $client.pos$",
                        "#chat rolled a $rand(1, 20)$!",
                        "§7The opt-in:§r plain chat leaves $...$ literal on purpose — #chat is how a real, recorded chat message gets evaluation."),
                doc("pid", Group.PREFIXES, "#pid N <line> · #pid N replace <line>",
                        "run a line under an id YOU pick — name it for /tupenter running and abort", null, false,
                        "#pid 7 #while (true) (/echohud &f$client.speed$&7 b/s && #wait 2t)",
                        "#pid 7 replace #while (true) (/echohud &f$client.light$&7 light && #wait 2t)",
                        "§7Stable ids:§r /tupenter abort 7 always means THAT loop, whatever else is running.",
                        "§7Refuses a live id§r unless you add §freplace§r — the composed example swaps the running HUD for a new one in place."));
    }
}
