package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptParserTest {

    private static Map<String, AliasDefinition> aliasMap(Map<String, String> raw) {
        Map<String, AliasDefinition> map = new LinkedHashMap<>();
        raw.forEach((name, body) -> map.put(name, AliasDefinition.parse(body)));
        return map;
    }

    private static ScriptParser.Options options(Map<String, String> aliases) {
        return options(aliases, new SessionVariableStore());
    }

    private static ScriptParser.Options options(Map<String, String> aliases, SessionVariableStore store) {
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliasMap(aliases),
                true, true, true, true, 100, 1000, new Random(42), store, store);
    }

    private static ScriptParser.Options options(Map<String, String> aliases, SessionVariableStore store,
                                                boolean chaining, NumberMathMode mathMode,
                                                boolean silentEnabled, boolean variablesEnabled,
                                                boolean loopsEnabled, boolean conditionalsEnabled,
                                                int maxLoopIterations) {
        return new ScriptParser.Options(chaining, mathMode, aliasMap(aliases),
                silentEnabled, variablesEnabled, loopsEnabled, conditionalsEnabled,
                maxLoopIterations, 1000, new Random(42), store, store);
    }

    private static ScriptParser.Options optionsWithTags(TagResolver tags) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliasMap(Map.of()),
                true, true, true, true, 100, 1000, new Random(42), store, store, tags);
    }

    private static ScriptParser.ParseResult parse(String command) {
        return ScriptParser.parse(command, options(Map.of()));
    }

    private static ScriptParser.ParseResult parse(String command, Map<String, String> aliases) {
        return ScriptParser.parse(command, options(aliases));
    }

    private static List<String> contents(ScriptParser.ParseResult result) {
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    // --- && chaining ---

    @Test
    void plainCommandIsUnchanged() {
        ScriptParser.ParseResult result = parse("time set day");
        assertFalse(result.changed());
        assertNull(result.error());
    }

    @Test
    void chainSegmentsPickTheirOwnStatementForm() {
        // the typed / flavors only the first segment; the rest follow the
        // three forms — /command, #directive, bare chat
        ScriptParser.ParseResult result = parse("time set day && /weather clear && weather clear");
        assertTrue(result.changed());
        assertEquals(List.of("time set day", "weather clear", "weather clear"), contents(result));
        assertTrue(result.script().statements().get(0).isCommand());
        assertTrue(result.script().statements().get(1).isCommand());
        assertFalse(result.script().statements().get(2).isCommand());
        assertFalse(result.script().statements().get(0).silent());
    }

    @Test
    void chainingDisabledKeepsLineIntact() {
        ScriptParser.Options opts = options(Map.of(), new SessionVariableStore(), false, NumberMathMode.DISABLED, true, true, true, true, 100);
        ScriptParser.ParseResult result = ScriptParser.parse("say a && b", opts);
        assertFalse(result.changed());
    }

    @Test
    void ampersandsInsideMathMarkersAreShielded() {
        ScriptParser.ParseResult result = parse("say $1+1$ && say two");
        assertTrue(result.changed());
        assertEquals(List.of("say 2", "say two"), contents(result));
    }

    @Test
    void emptySegmentsAreSkipped() {
        ScriptParser.ParseResult result = parse("say a && && say b");
        assertTrue(result.changed());
        assertEquals(List.of("say a", "say b"), contents(result));
    }

    @Test
    void chainedBareSegmentsAreChat() {
        ScriptParser.ParseResult result = parse("time set day && hello");
        assertTrue(result.changed());
        assertFalse(result.script().statements().get(1).isCommand());
        assertEquals("hello", result.script().statements().get(1).content());
    }

    @Test
    void wholeMarkerStatementsRedispatchByForm() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("cmd1", Value.of("/tp ~ ~1 ~"));
        store.set("greet", Value.of("hello"));
        ScriptParser.ParseResult result = ScriptParser.parse(
                "say teleporting && $cmd1$ && $greet$", options(Map.of(), store));
        assertNull(result.error());
        List<Script.SendStatement> statements = result.script().statements();
        assertEquals(3, statements.size());
        assertTrue(statements.get(0).isCommand());
        assertEquals("say teleporting", statements.get(0).content());
        assertTrue(statements.get(1).isCommand());
        assertEquals("tp ~ ~1 ~", statements.get(1).content());
        assertFalse(statements.get(2).isCommand());
        assertEquals("hello", statements.get(2).content());
    }

    @Test
    void wholeMarkerRedispatchNestsAndSplitsChains() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("cmd2", Value.of("/tp ~ ~ ~1"));
        store.set("cmd1", Value.of("/tp ~ ~1 ~ && $cmd2$"));
        ScriptParser.ParseResult result = ScriptParser.parse("say teleporting! && $cmd1$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say teleporting!", "tp ~ ~1 ~", "tp ~ ~ ~1"), contents(result));
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::isCommand));
    }

    @Test
    void selfReferentialMarkerHitsDepthCapBeforeSending() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("loop", Value.of("$loop$"));
        ScriptParser.ParseResult result = ScriptParser.parse("say hi && $loop$", options(Map.of(), store));
        assertNotNull(result.error());
        assertTrue(result.error().contains("nest"));
    }

    @Test
    void wholeMarkerNumbersStayChatText() {
        ScriptParser.ParseResult result = parse("say x && $1+2$");
        assertNull(result.error());
        assertFalse(result.script().statements().get(1).isCommand());
        assertEquals("3", result.script().statements().get(1).content());
    }

    @Test
    void chatSegmentsEvaluateExplicitMarkers() {
        ScriptParser.ParseResult result = parse("say hi && score is $1+1$");
        assertNull(result.error());
        assertFalse(result.script().statements().get(1).isCommand());
        assertEquals("score is 2", result.script().statements().get(1).content());
    }

    @Test
    void literalParensAreNotStructural() {
        ScriptParser.ParseResult result = parse("say (hello) && say (a && b)");
        assertTrue(result.changed());
        // parens are literal in raw statements; && splits even inside them
        assertEquals(List.of("say (hello)", "say (a", "b)"), contents(result));
    }

    // --- number math / expressions ---

    @Test
    void mathAppliedPerSegment() {
        ScriptParser.ParseResult result = parse("give @s stick 32+5");
        assertTrue(result.changed());
        assertEquals(List.of("give @s stick 37"), contents(result));
    }

    @Test
    void mathDisabledMeansNoChange() {
        ScriptParser.Options opts = options(Map.of(), new SessionVariableStore(), true, NumberMathMode.DISABLED, true, true, true, true, 100);
        ScriptParser.ParseResult result = ScriptParser.parse("give @s stick 32+5", opts);
        assertFalse(result.changed());
    }

    @Test
    void invalidMarkerExpressionBecomesParseError() {
        ScriptParser.ParseResult result = parse("say $%%%$");
        assertNotNull(result.error());
        assertFalse(result.changed());
    }

    @Test
    void escapedDollarsBecomeLiteral() {
        ScriptParser.ParseResult result = parse("say I paid \\$100");
        assertTrue(result.changed());
        assertEquals(List.of("say I paid $100"), contents(result));
    }

    @Test
    void ternaryMarkersWork() {
        ScriptParser.ParseResult result = parse("tp @s ~ ~$3 > 2 ? 10 : 0$ ~");
        assertTrue(result.changed());
        assertEquals(List.of("tp @s ~ ~10 ~"), contents(result));
    }

    // --- aliases ---

    @Test
    void aliasExpandsToItsBody() {
        ScriptParser.ParseResult result = parse("sunny", Map.of("sunny", "/time set day && /weather clear"));
        assertTrue(result.changed());
        assertEquals(List.of("time set day", "weather clear"), contents(result));
    }

    @Test
    void aliasArgsAreAppended() {
        ScriptParser.ParseResult result = parse("g stick 5", Map.of("g", "/give @s"));
        assertTrue(result.changed());
        assertEquals(List.of("give @s stick 5"), contents(result));
    }

    @Test
    void aliasBodyMayContainChatMessages() {
        ScriptParser.ParseResult result = parse("greet", Map.of("greet", "/time set day && Have fun!"));
        assertTrue(result.changed());
        Script.SendStatement chat = result.script().statements().get(1);
        assertFalse(chat.isCommand());
        assertEquals("Have fun!", chat.content());
    }

    @Test
    void nestedAliasesExpand() {
        ScriptParser.ParseResult result = parse("outer", Map.of(
                "outer", "/say start && /inner",
                "inner", "/say end"
        ));
        assertTrue(result.changed());
        assertEquals(List.of("say start", "say end"), contents(result));
    }

    @Test
    void aliasNamesAreCaseInsensitive() {
        ScriptParser.ParseResult result = parse("SUNNY", Map.of("sunny", "/time set day"));
        assertTrue(result.changed());
        assertEquals(List.of("time set day"), contents(result));
    }

    @Test
    void recursiveAliasHitsExpansionLimit() {
        ScriptParser.ParseResult result = parse("loop", Map.of("loop", "/loop"));
        assertNotNull(result.error());
        assertTrue(result.error().contains(String.valueOf(ScriptParser.MAX_ALIAS_EXPANSIONS)));
        assertFalse(result.changed());
    }

    @Test
    void aliasMathAndChainingCompose() {
        ScriptParser.ParseResult result = parse("stacks", Map.of("stacks", "/give @s stone $2s$ && /give @s dirt 3s"));
        assertTrue(result.changed());
        assertEquals(List.of("give @s stone 128", "give @s dirt 192"), contents(result));
    }

    @Test
    void originalLineIsPreserved() {
        ScriptParser.ParseResult result = parse("say a && say b");
        assertEquals("say a && say b", result.script().originalLine());
    }

    // --- alias parameters ---

    @Test
    void typedParamsBindByNameAndPosition() {
        Map<String, String> aliases = Map.of("panic", "<level:int> <type:word> /say Level $level$ ($1$) type $type$");
        ScriptParser.ParseResult result = parse("panic 4 storm", aliases);
        assertNull(result.error());
        assertEquals(List.of("say Level 4 (4) type storm"), contents(result));
    }

    @Test
    void textParamIsGreedy() {
        Map<String, String> aliases = Map.of("shout", "<msg:text> /say $msg$!");
        ScriptParser.ParseResult result = parse("shout hello there world", aliases);
        assertEquals(List.of("say hello there world!"), contents(result));
    }

    @Test
    void paramTypeMismatchIsAnError() {
        Map<String, String> aliases = Map.of("panic", "<level:int> /say $level$");
        ScriptParser.ParseResult result = parse("panic notanumber", aliases);
        assertNotNull(result.error());
        assertTrue(result.error().contains("whole number"));
    }

    @Test
    void missingAndExtraParamsAreErrors() {
        Map<String, String> aliases = Map.of("panic", "<level:int> /say $level$");
        assertNotNull(parse("panic", aliases).error());
        assertNotNull(parse("panic 1 2", aliases).error());
    }

    @Test
    void quotedStringArgBindsSelectors() {
        Map<String, String> aliases = Map.of("lightning", "<target> /execute at $target$ run summon lightning_bolt");
        ScriptParser.ParseResult result = parse("lightning \"@e[type=!player,limit=1,sort=nearest]\"", aliases);
        assertNull(result.error());
        assertEquals(List.of("execute at @e[type=!player,limit=1,sort=nearest] run summon lightning_bolt"), contents(result));
    }

    @Test
    void quotedStringWithSpacesFollowedByMoreArgs() {
        Map<String, String> aliases = Map.of("t", "<msg> <n:int> /say $n$: $msg$");
        ScriptParser.ParseResult result = parse("t \"hello world\" 3", aliases);
        assertNull(result.error());
        assertEquals(List.of("say 3: hello world"), contents(result));
    }

    @Test
    void quotedStringEscapesQuotes() {
        Map<String, String> aliases = Map.of("t", "<msg> /say $msg$");
        ScriptParser.ParseResult result = parse("t \"he said \\\"hi\\\"\"", aliases);
        assertNull(result.error());
        assertEquals(List.of("say he said \"hi\""), contents(result));
    }

    @Test
    void unquotedSelectorWithBracketedSpacesIsOneToken() {
        Map<String, String> aliases = Map.of("l", "<target:selector> <n:int> /say $n$ $target$");
        ScriptParser.ParseResult result = parse("l @e[name=\"a b\"] 2", aliases);
        assertNull(result.error());
        assertEquals(List.of("say 2 @e[name=\"a b\"]"), contents(result));
    }

    @Test
    void unclosedQuoteIsAnError() {
        Map<String, String> aliases = Map.of("t", "<msg> /say $msg$");
        ScriptParser.ParseResult result = parse("t \"oops", aliases);
        assertNotNull(result.error());
        assertTrue(result.error().contains("quote"));
    }

    @Test
    void choiceParamsBindAndValidate() {
        Map<String, String> aliases = Map.of("portal", "<dim:to_overworld,to_nether> /say heading $dim$");
        ScriptParser.ParseResult ok = parse("portal to_nether", aliases);
        assertNull(ok.error());
        assertEquals(List.of("say heading to_nether"), contents(ok));

        ScriptParser.ParseResult caseInsensitive = parse("portal TO_OVERWORLD", aliases);
        assertNull(caseInsensitive.error());
        assertEquals(List.of("say heading to_overworld"), contents(caseInsensitive));

        ScriptParser.ParseResult bad = parse("portal to_end", aliases);
        assertNotNull(bad.error());
        assertTrue(bad.error().contains("to_overworld, to_nether"));
    }

    @Test
    void posParamsResolveTildesAndBindComponents() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.bx", Value.ofNumber(100));
        store.set("client.by", Value.ofNumber(64));
        store.set("client.bz", Value.ofNumber(-8));
        Map<String, String> aliases = Map.of("portal",
                "<p:pos> <dim:to_overworld,to_nether> /say $dim$: $floor(dim == \"to_nether\" ? p.x/8 : p.x*8)$ $p.y$ at $p$");

        ScriptParser.ParseResult absolute = ScriptParser.parse("portal 64 64 64 to_nether", options(aliases, store));
        assertNull(absolute.error());
        assertEquals(List.of("say to_nether: 8 64 at 64 64 64"), contents(absolute));

        ScriptParser.ParseResult relative = ScriptParser.parse("portal ~ ~5 ~-1 to_nether", options(aliases, store));
        assertNull(relative.error());
        assertEquals(List.of("say to_nether: 12 69 at 100 69 -9"), contents(relative));
    }

    @Test
    void posParamRejectsBadCoordinates() {
        SessionVariableStore store = new SessionVariableStore();
        Map<String, String> aliases = Map.of("here", "<p:pos> /say $p$");
        assertNotNull(ScriptParser.parse("here 1 2", options(aliases, store)).error());
        assertNotNull(ScriptParser.parse("here ^ ^ ^", options(aliases, store)).error());
        assertNotNull(ScriptParser.parse("here a b c", options(aliases, store)).error());
        // ~ without a position provider (not in-game)
        assertNotNull(ScriptParser.parse("here ~ ~ ~", options(aliases, store)).error());
    }

    @Test
    void vec3ParamKeepsDecimalsAndResolvesTildes() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.x", Value.ofNumber("100.5"));
        store.set("client.y", Value.ofNumber(64));
        store.set("client.z", Value.ofNumber("-8.25"));
        Map<String, String> aliases = Map.of("hop", "<p:vec3> /say tp $p$ mid $p.y + 0.5$");

        ScriptParser.ParseResult absolute = ScriptParser.parse("hop 1.5 70 -2", options(aliases, store));
        assertNull(absolute.error());
        assertEquals(List.of("say tp 1.5 70 -2 mid 70.5"), contents(absolute));

        ScriptParser.ParseResult relative = ScriptParser.parse("hop ~ ~0.5 ~-1", options(aliases, store));
        assertNull(relative.error());
        assertEquals(List.of("say tp 100.5 64.5 -9.25 mid 65"), contents(relative));
    }

    @Test
    void rotationAndAngleParamsResolveAgainstFacing() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber("-73.5"));
        store.set("client.pitch", Value.ofNumber(12));
        Map<String, String> aliases = Map.of(
                "face", "<r:rotation> /say rotate $r$ yaw $r.yaw$",
                "spin", "<a:angle> /say turn $a + 90$");

        assertEquals(List.of("say rotate 90 -45.5 yaw 90"),
                contents(ScriptParser.parse("face 90 -45.5", options(aliases, store))));
        assertEquals(List.of("say rotate -73.5 12 yaw -73.5"),
                contents(ScriptParser.parse("face ~ ~", options(aliases, store))));
        assertEquals(List.of("say turn 16.5"),
                contents(ScriptParser.parse("spin ~", options(aliases, store))));
    }

    @Test
    void columnPosParamBindsTwoWholeCoordinates() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.bx", Value.ofNumber(100));
        store.set("client.bz", Value.ofNumber(-8));
        Map<String, String> aliases = Map.of("chunkat", "<c:column_pos> /say column $c$ x $c.x$ z $c.z$");

        ScriptParser.ParseResult relative = ScriptParser.parse("chunkat ~4 ~", options(aliases, store));
        assertNull(relative.error());
        assertEquals(List.of("say column 104 -8 x 104 z -8"), contents(relative));

        // only two coordinates — a third is one argument too many
        assertNotNull(ScriptParser.parse("chunkat 1 2 3", options(aliases, store)).error());
    }

    @Test
    void timeParamConvertsToTicks() {
        Map<String, String> aliases = Map.of("delay", "<t:time> /say $t$ ticks");
        assertEquals(List.of("say 30 ticks"), contents(parse("delay 1.5s", aliases)));
        assertEquals(List.of("say 24000 ticks"), contents(parse("delay 1d", aliases)));
        assertEquals(List.of("say 10 ticks"), contents(parse("delay 10t", aliases)));
        assertEquals(List.of("say 8 ticks"), contents(parse("delay 8", aliases)));
        assertNotNull(parse("delay -5t", aliases).error());
        assertNotNull(parse("delay soon", aliases).error());
    }

    @Test
    void colorDimensionAndIdParamsBind() {
        Map<String, String> aliases = Map.of("theme", "<c:color> <d:dimension> <i:id> /say $c$ $d$ $i$");
        ScriptParser.ParseResult result = parse("theme GOLD minecraft:the_nether tupenter:thing", aliases);
        assertNull(result.error());
        assertEquals(List.of("say gold minecraft:the_nether tupenter:thing"), contents(result));

        assertNotNull(parse("theme chartreuse minecraft:overworld x", aliases).error());
    }

    @Test
    void itemAndBlockParamsBindVerbatim() {
        Map<String, String> aliases = Map.of("giveme", "<thing:item> <n:int> /give @s $thing$ $n$");
        ScriptParser.ParseResult result = parse("giveme minecraft:stone[custom_name='\"hi there\"'] 3", aliases);
        assertNull(result.error());
        assertEquals(List.of("give @s minecraft:stone[custom_name='\"hi there\"'] 3"), contents(result));
    }

    @Test
    void setAcceptsBareNamesCompoundOpsAndWrappedExpressions() {
        // bare name + compound assignment — the counter idiom
        assertEquals(List.of("say 3"), contents(parse("#set i = 1 && #set i += 2 && /say $i$")));
        assertEquals(List.of("say 6"), contents(parse("#set i = 3 && #set i *= 2 && /say $i$")));
        // $...$ wraps a full expression on the right-hand side too
        assertEquals(List.of("say 2"), contents(parse("#set $i$ = 1 && #set $i$ = $i+ 1$ && /say $i$")));
        // compound on an undefined variable is a clear error
        assertNotNull(parse("#set nope += 1").error());
    }

    @Test
    void waitEmitsAWaitStatement() {
        ScriptParser.ParseResult result = parse("say hi && #wait 1.5s && /say later");
        assertNull(result.error());
        List<Script.SendStatement> statements = result.script().statements();
        assertEquals(3, statements.size());
        assertEquals(Script.Kind.WAIT, statements.get(1).kind());
        assertEquals(30, statements.get(1).waitTicks());

        // #wait 0 is a no-op, negative and over-cap durations are errors
        assertEquals(2, contents(parse("say a && #wait 0t && /say b")).size());
        assertNotNull(parse("say a && #wait -5t").error());
        assertNotNull(parse("say a && #wait 99999999t").error());
        assertNotNull(parse("say a && #wait soon").error());
        assertNotNull(parse("say a && #wait").error());
    }

    @Test
    void tagSetsWorkInMarkersAndForeach() {
        TagResolver tags = (kind, tagId) -> kind == TagResolver.TagKind.BLOCK && tagId.equals("minecraft:logs")
                ? List.of("minecraft:oak_log", "minecraft:birch_log")
                : List.of();

        ScriptParser.ParseResult picked = ScriptParser.parse(
                "setblock ~ ~ ~ $rand(blockset(\"#minecraft:logs\"))$", optionsWithTags(tags));
        assertNull(picked.error());
        String content = contents(picked).get(0);
        assertTrue(content.equals("setblock ~ ~ ~ minecraft:oak_log")
                || content.equals("setblock ~ ~ ~ minecraft:birch_log"), content);

        ScriptParser.ParseResult looped = ScriptParser.parse(
                "#foreach $b$ in blockset(\"#minecraft:logs\") (/say $b$)", optionsWithTags(tags));
        assertNull(looped.error());
        assertEquals(List.of("say minecraft:oak_log", "say minecraft:birch_log"), contents(looped));

        // without a live world the functions fail with a clear error, not silently
        assertNotNull(ScriptParser.parse("say $rand(blockset(\"#minecraft:logs\"))$", options(Map.of())).error());
    }

    private static final String LAUNCH_BODY =
            "<e:entity> <s:float=1.5> "
            + "#local h = cos(client.pitch) && "
            + "#local x = -sin(client.yaw) * h && "
            + "#local y = -sin(client.pitch) && "
            + "#local z = cos(client.yaw) * h && "
            + "/summon $e$ ~$round(x*15)/10$ ~$1.5 + round(y*15)/10$ ~$round(z*15)/10$ "
            + "{Motion:[$round(x*s*1000)/1000$d,$round(y*s*1000)/1000$d,$round(z*s*1000)/1000$d]}";

    @Test
    void launchRecipeSummonsAlongTheFacingDirection() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(0));    // facing +z
        store.set("client.pitch", Value.ofNumber(0));  // level
        Map<String, String> aliases = Map.of("launch", LAUNCH_BODY);

        // facing +z at speed 2: motion is (0, 0, 2), spawn 1.5 blocks ahead
        assertEquals(List.of("summon minecraft:fireball ~0 ~1.5 ~1.5 {Motion:[0d,0d,2d]}"),
                contents(ScriptParser.parse("launch minecraft:fireball 2", options(aliases, store))));

        // default speed 1.5 when omitted
        assertEquals(List.of("summon minecraft:arrow ~0 ~1.5 ~1.5 {Motion:[0d,0d,1.5d]}"),
                contents(ScriptParser.parse("launch minecraft:arrow", options(aliases, store))));

        // looking straight down: motion is (0, -s, 0)
        store.set("client.pitch", Value.ofNumber(90));
        assertEquals(List.of("summon minecraft:tnt ~0 ~0 ~0 {Motion:[0d,-1d,0d]}"),
                contents(ScriptParser.parse("launch minecraft:tnt 1", options(aliases, store))));
    }

    private static final String LAUNCH2_BODY =
            "<e:entity> <s:float=1.5> <g:bool=false> "
            + "#local h = cos(client.pitch) && "
            + "#local x = -sin(client.yaw) * h && "
            + "#local y = -sin(client.pitch) && "
            + "#local z = cos(client.yaw) * h && "
            + "#local p = \"~\" + round(x*15)/10 + \" ~\" + (1.5 + round(y*15)/10) + \" ~\" + round(z*15)/10 && "
            + "#local m = round(x*s*1000)/1000 + \"d,\" + round(y*s*1000)/1000 + \"d,\" + round(z*s*1000)/1000 + \"d\" && "
            + "#local n = g ? \",NoGravity:1b\" : \"\" && "
            + "#if (e == \"minecraft:fireball\") (/summon $e$ $p$ {Motion:[$m$],ExplosionPower:50b$n$}) "
            + "#elseif (e == \"minecraft:ender_pearl\") (/summon $e$ $p$ {Motion:[$m$],Owner:$client.uuid_nbt$$n$}) "
            + "#else (/summon $e$ $p$ {Motion:[$m$]$n$})";

    @Test
    void launchSpecialCasesForFireballAndEnderPearl() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(0));
        store.set("client.pitch", Value.ofNumber(0));
        store.set("client.uuid_nbt", Value.of("[I;1,2,3,4]"));
        Map<String, String> aliases = Map.of("launch", LAUNCH2_BODY);

        assertEquals(List.of("summon minecraft:fireball ~0 ~1.5 ~1.5 {Motion:[0d,0d,2d],ExplosionPower:50b}"),
                contents(ScriptParser.parse("launch minecraft:fireball 2", options(aliases, store))));
        assertEquals(List.of("summon minecraft:ender_pearl ~0 ~1.5 ~1.5 {Motion:[0d,0d,1.5d],Owner:[I;1,2,3,4]}"),
                contents(ScriptParser.parse("launch minecraft:ender_pearl", options(aliases, store))));
        assertEquals(List.of("summon minecraft:arrow ~0 ~1.5 ~1.5 {Motion:[0d,0d,3d]}"),
                contents(ScriptParser.parse("launch minecraft:arrow 3", options(aliases, store))));
    }

    @Test
    void launchNoGravityBoolCanSkipTheSpeed() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(0));
        store.set("client.pitch", Value.ofNumber(0));
        store.set("client.uuid_nbt", Value.of("[I;1,2,3,4]"));
        Map<String, String> aliases = Map.of("launch", LAUNCH2_BODY);

        // "true" isn't a float, so the optional speed is skipped: /launch snowball true
        assertEquals(List.of("summon minecraft:snowball ~0 ~1.5 ~1.5 {Motion:[0d,0d,1.5d],NoGravity:1b}"),
                contents(ScriptParser.parse("launch minecraft:snowball true", options(aliases, store))));
        // fully spelled out
        assertEquals(List.of("summon minecraft:snowball ~0 ~1.5 ~1.5 {Motion:[0d,0d,4d],NoGravity:1b}"),
                contents(ScriptParser.parse("launch minecraft:snowball 4 true", options(aliases, store))));
        // bad bool errors clearly
        assertNotNull(ScriptParser.parse("launch minecraft:snowball maybe", options(aliases, store)).error());
    }

    private static final String RANDOMFILL_BODY =
            "<a:pos> <b:pos> <set:blockset> <filter:blockset=any> #silent #local choices = blockset(set) && "
            + "#for $x$ in a.x..b.x (#for $y$ in a.y..b.y (#for $z$ in a.z..b.z "
            + "(#if (filter == \"any\") (/setblock $x$ $y$ $z$ $rand(choices)$) "
            + "#else (#if (contains(blockset(filter), block($x$, $y$, $z$))) (/setblock $x$ $y$ $z$ $rand(choices)$)))))";

    private static ScriptParser.Options randomfillOptions(BlockReader blocks) {
        TagResolver tags = new TagResolver() {
            @Override
            public List<String> resolve(TagKind kind, String tagId) {
                return kind == TagResolver.TagKind.BLOCK && "minecraft:logs".equals(tagId)
                        ? List.of("minecraft:oak_log", "minecraft:birch_log")
                        : List.of();
            }

            @Override
            public String lookup(TagKind kind, String id) {
                return kind == TagResolver.TagKind.BLOCK && id.endsWith("dirt") ? "minecraft:dirt" : null;
            }
        };
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliasMap(Map.of("randomfill", RANDOMFILL_BODY)),
                true, true, true, true, 100, 1000, new Random(42), store, store, tags, blocks, FunctionResolver.NONE, false);
    }

    @Test
    void randomfillRecipeWorks() {
        // nested #for over the volume, each block independently random
        ScriptParser.ParseResult result = ScriptParser.parse(
                "randomfill 0 0 0 1 1 0 #minecraft:logs", randomfillOptions(BlockReader.NONE));
        assertNull(result.error());
        List<String> sent = contents(result);
        assertEquals(4, sent.size(), "2x2x1 volume = 4 setblocks");
        java.util.Set<String> picked = new java.util.HashSet<>();
        for (String line : sent) {
            assertTrue(line.matches("setblock \\d \\d \\d minecraft:(oak|birch)_log"), line);
            picked.add(line.substring(line.lastIndexOf(' ') + 1));
        }
        assertTrue(picked.size() > 1, "blocks vary across positions");
        assertTrue(result.script().statements().get(0).silent(), "feedback is suppressed");
    }

    @Test
    void randomfillFilterOnlyReplacesMatchingBlocks() {
        // world: dirt at even x, stone at odd x — filter "dirt" (a concrete
        // id, exercising the one-element-set fallback) touches only evens
        BlockReader blocks = (x, y, z) -> x % 2 == 0 ? "minecraft:dirt" : "minecraft:stone";
        ScriptParser.ParseResult result = ScriptParser.parse(
                "randomfill 0 0 0 3 0 0 #minecraft:logs dirt", randomfillOptions(blocks));
        assertNull(result.error());
        List<String> sent = contents(result);
        assertEquals(2, sent.size(), "only the two dirt columns are replaced");
        for (String line : sent) {
            assertTrue(line.matches("setblock [02] 0 0 minecraft:(oak|birch)_log"), line);
        }
    }

    @Test
    void itemsetAndBlocksetParamsBindTagsVerbatim() {
        Map<String, String> aliases = Map.of("swap", "<from:blockset> <to:block> /fill ~ ~ ~ ~ ~ ~ $to$ replace $from$");
        ScriptParser.ParseResult result = parse("swap #minecraft:logs[axis=y] minecraft:stone", aliases);
        assertNull(result.error());
        assertEquals(List.of("fill ~ ~ ~ ~ ~ ~ minecraft:stone replace #minecraft:logs[axis=y]"), contents(result));

        // a concrete block is just as valid for a blockset param
        assertEquals(List.of("fill ~ ~ ~ ~ ~ ~ air replace dirt"),
                contents(parse("swap dirt air", aliases)));
    }

    @Test
    void optionalParamsUseDefaultsAndCanBeSkipped() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.bx", Value.ofNumber(100));
        store.set("client.by", Value.ofNumber(64));
        store.set("client.bz", Value.ofNumber(-8));
        store.set("client.dimension", Value.of("minecraft:overworld"));
        Map<String, String> aliases = Map.of("portal",
                "<p:pos=~ ~ ~> <dim:to_overworld,to_nether=$client.dimension == \"minecraft:the_nether\" ? \"to_overworld\" : \"to_nether\"$> /say $dim$ $p$");

        // everything omitted: pos = where you stand, dim = the opposite dimension
        assertEquals(List.of("say to_nether 100 64 -8"), contents(ScriptParser.parse("portal", options(aliases, store))));
        // coords only
        assertEquals(List.of("say to_nether 64 64 64"), contents(ScriptParser.parse("portal 64 64 64", options(aliases, store))));
        // dimension only — pos is skipped because to_overworld isn't a coordinate
        assertEquals(List.of("say to_overworld 100 64 -8"), contents(ScriptParser.parse("portal to_overworld", options(aliases, store))));
        // both
        assertEquals(List.of("say to_overworld 1 2 3"), contents(ScriptParser.parse("portal 1 2 3 to_overworld", options(aliases, store))));
    }

    @Test
    void optionalDefaultsCanReferenceEarlierParams() {
        Map<String, String> aliases = Map.of("greet", "<who:player> <msg:text=hello $who$> /say $msg$");
        assertEquals(List.of("say hello Steve"), contents(parse("greet Steve", aliases)));
        assertEquals(List.of("say yo"), contents(parse("greet Steve yo", aliases)));
    }

    @Test
    void optionalParamEdgeCases() {
        Map<String, String> aliases = Map.of(
                "radius", "<r:int=5> /say r=$r$",
                "need", "<n:int> /say $n$");
        assertEquals(List.of("say r=5"), contents(parse("radius", aliases)));
        assertEquals(List.of("say r=9"), contents(parse("radius 9", aliases)));
        // junk that isn't an int falls through, and nothing else claims it
        assertNotNull(parse("radius bogus", aliases).error());
        // required params stay required
        assertNotNull(parse("need", aliases).error());
    }

    @Test
    void argumentsEvaluateBeforeBinding() {
        Map<String, String> aliases = Map.of(
                "cube", "<r:int> /say r=$r$",
                "wrap", "<n:int> /cube $n * 2$");
        // an expression argument feeds a typed param
        assertEquals(List.of("say r=6"), contents(parse("cube $2+4$", aliases)));
        assertNull(parse("cube $rand(1,15)$", aliases).error());
        // nested custom commands can pass computed params through
        assertEquals(List.of("say r=10"), contents(parse("wrap 5", aliases)));
        // a marker that evaluates to the wrong type still errors clearly
        assertNotNull(parse("cube $\"abc\"$", aliases).error());
    }

    @Test
    void generatedLinesUseBodyStatementForms() {
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(
                "/time set day && hello", "/$x$", options(Map.of()));
        assertNull(result.error());
        List<Script.SendStatement> statements = result.script().statements();
        assertEquals(2, statements.size());
        assertEquals(Script.Kind.COMMAND, statements.get(0).kind());
        assertEquals("time set day", statements.get(0).content());
        assertEquals(Script.Kind.CHAT, statements.get(1).kind());
        assertEquals("hello", statements.get(1).content());
        assertEquals("/$x$", result.script().originalLine());
    }

    @Test
    void generatedPlainTextIsChatAndStillAScript() {
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine("hi", "/$x$", options(Map.of()));
        assertNull(result.error());
        assertEquals(1, result.script().statements().size());
        assertEquals(Script.Kind.CHAT, result.script().statements().get(0).kind());
        assertEquals("hi", result.script().statements().get(0).content());
    }

    @Test
    void paramsCanDriveLoops() {
        Map<String, String> aliases = Map.of("spam", "<count:int> #repeat $count$ (/say hi $i$)");
        ScriptParser.ParseResult result = parse("spam 3", aliases);
        assertNull(result.error());
        assertEquals(List.of("say hi 1", "say hi 2", "say hi 3"), contents(result));
    }

    // --- #set and variables ---

    @Test
    void setThenUseInSameLine() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#set $x$ = 3 && give @s stick $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("give @s stick 3"), contents(result));
        assertEquals(List.of("$x$ = 3"), result.notices());
        assertEquals("3", store.resolve("x").orElseThrow().displayString());
    }

    @Test
    void setAloneProducesAnEmptyScript() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#set $spawn$ = \"100 64 -200\"", options(Map.of(), store));
        assertNull(result.error());
        assertTrue(result.script().statements().isEmpty());
        assertEquals("100 64 -200", store.resolve("spawn").orElseThrow().displayString());
    }

    @Test
    void setIsOrderedWithinTheLine() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#set $x$ = 1 && #set $x$ = $x$ + 1 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 2"), contents(result));
        assertEquals("2", store.resolve("x").orElseThrow().displayString());
    }

    @Test
    void setdefaultInitializesAnAbsentVariable() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#setdefault $x$ = 5 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 5"), contents(result));
        assertEquals("5", store.resolve("x").orElseThrow().displayString());
    }

    @Test
    void setdefaultLeavesAnExistingValueAlone() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("x", Value.ofNumber(3)); // already defined — e.g. a prior run or a loaded persistent var
        ScriptParser.ParseResult result = ScriptParser.parse("#setdefault $x$ = 5 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 3"), contents(result), "kept the existing value, idempotent init");
        assertEquals("3", store.resolve("x").orElseThrow().displayString());
    }

    @Test
    void setdefaultIsIdempotentWithinTheSameLine() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#setdefault $x$ = 1 && #setdefault $x$ = 2 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 1"), contents(result), "the second #setdefault is a no-op");
        assertEquals("1", store.resolve("x").orElseThrow().displayString());
    }

    @Test
    void setRollsBackWhenALaterStatementFails() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#set $x$ = 5 && say $nosuchvar$", options(Map.of(), store));
        assertNotNull(result.error());
        assertTrue(store.resolve("x").isEmpty(), "failed line must not commit #set");
    }

    @Test
    void variableNamesAreCaseInsensitive() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#set $X$ = 1 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 1"), contents(result));
    }

    @Test
    void setRejectsReservedAndMalformedNames() {
        assertNotNull(parse("#set $rand$ = 5").error());
        assertNotNull(parse("#set $1x$ = 5").error());
        assertNotNull(parse("#set x 5").error());
        assertNotNull(parse("#set $x$ =").error());
    }

    @Test
    void dottedUserVariablesGroup() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#set $hitlist.bob$ = \"wanted\" && say Bob is $hitlist.bob$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say Bob is wanted"), contents(result));
        assertEquals("wanted", store.resolve("hitlist.bob").orElseThrow().displayString());
    }

    @Test
    void dottedNamesCannotShadowBuiltinNamespaces() {
        assertNotNull(parse("#set $client.y$ = 5").error());
        assertNotNull(parse("#set $world.time$ = 5").error());
        assertNotNull(parse("#local $players.fake$ = 5").error());
        assertNotNull(parse("#set $a..b$ = 5").error());
        assertNotNull(parse("#set $a.$ = 5").error());
    }

    @Test
    void chatSetLineIsIntercepted() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parseChatLine("#set $x$ = 5", options(Map.of(), store));
        assertNull(result.error());
        assertTrue(result.changed());
        assertEquals("5", store.resolve("x").orElseThrow().displayString());
    }

    // --- #repeat ---

    @Test
    void repeatUnrollsWithImplicitIndex() {
        ScriptParser.ParseResult result = parse("#repeat 3 (/say Tick $i$)");
        assertNull(result.error());
        assertEquals(List.of("say Tick 1", "say Tick 2", "say Tick 3"), contents(result));
    }

    @Test
    void repeatBodyMayChainAndNest() {
        ScriptParser.ParseResult result = parse("weather clear && #repeat 2 (/time add 5 && #repeat 2 (/say $i$))");
        assertNull(result.error());
        assertEquals(List.of("weather clear", "time add 5", "say 1", "say 2", "time add 5", "say 1", "say 2"), contents(result));
    }

    @Test
    void repeatCountMayBeAnExpression() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("n", Value.ofNumber(2));
        ScriptParser.ParseResult result = ScriptParser.parse("#repeat $n$ + 1 (/say hi)", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(3, result.script().statements().size());
    }

    @Test
    void repeatZeroSendsNothing() {
        ScriptParser.ParseResult result = parse("#repeat 0 (/say hi)");
        assertNull(result.error());
        assertTrue(result.script().statements().isEmpty());
    }

    @Test
    void repeatOverIterationCapIsRejected() {
        ScriptParser.ParseResult result = parse("#repeat 101 (/say hi)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("loop limit"));
    }

    @Test
    void repeatErrorsWithoutBodyOrCount() {
        assertNotNull(parse("#repeat 5").error());
        assertNotNull(parse("#repeat (/say hi)").error());
        assertNotNull(parse("#repeat 3 (/say hi) trailing").error());
    }

    @Test
    void loopsDisabledIsAnError() {
        ScriptParser.Options opts = options(Map.of(), new SessionVariableStore(), true, NumberMathMode.AUTO_DETECT, true, true, false, true, 100);
        ScriptParser.ParseResult result = ScriptParser.parse("#repeat 3 (/say hi)", opts);
        assertNotNull(result.error());
        assertTrue(result.error().contains("disabled"));
    }

    // --- #if / #else ---

    @Test
    void ifRunsBodyWhenTrue() {
        ScriptParser.ParseResult result = parse("#if (2 > 1) (/say yes)");
        assertNull(result.error());
        assertEquals(List.of("say yes"), contents(result));
    }

    @Test
    void ifSkipsBodyWhenFalse() {
        ScriptParser.ParseResult result = parse("#if (1 > 2) (/say yes)");
        assertNull(result.error());
        assertTrue(result.script().statements().isEmpty());
    }

    @Test
    void ifElseTakesTheElseBranch() {
        ScriptParser.ParseResult result = parse("#if (1 > 2) (/say yes) #else (/say no && /say really)");
        assertNull(result.error());
        assertEquals(List.of("say no", "say really"), contents(result));
    }

    @Test
    void ifConditionMustBeBoolean() {
        ScriptParser.ParseResult result = parse("#if (5) (/say hi)");
        assertNotNull(result.error());
    }

    // --- (C) absent live values read as false inside a condition only ---

    /** Resolves nothing, but throws for two names: one "absent state", one genuinely bad. */
    private static VariableProvider crosshairProvider() {
        return new VariableProvider() {
            @Override
            public java.util.Set<String> names() {
                return java.util.Set.of("client.target_entity");
            }

            @Override
            public java.util.Optional<Value> resolve(String name) {
                if (name.equalsIgnoreCase("client.target_entity")) {
                    throw new MissingValueException("no entity under the crosshair");
                }
                if (name.equalsIgnoreCase("bad.var")) {
                    throw new ExpressionException("no such variable");
                }
                return java.util.Optional.empty();
            }
        };
    }

    private static ScriptParser.Options optionsWith(VariableProvider provider) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliasMap(Map.of()),
                true, true, true, true, 100, 1000, new Random(42), provider, store);
    }

    @Test
    void absentValueMakesAConditionFalseNotAnError() {
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#if ($client.target_entity$ == \"minecraft:zombie\") (/say hit)", optionsWith(crosshairProvider()));
        assertNull(result.error(), "an absent live value is normal world state, not a scripting error");
        assertTrue(contents(result).isEmpty(), "condition read false — nothing emitted");
    }

    @Test
    void absentValueInAConditionTakesTheElseBranch() {
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#if ($client.target_entity$ == \"minecraft:zombie\") (/say hit) #else (/say miss)",
                optionsWith(crosshairProvider()));
        assertNull(result.error());
        assertEquals(List.of("say miss"), contents(result));
    }

    @Test
    void aGenuineBadVariableInAConditionStillAborts() {
        ScriptParser.ParseResult result = ScriptParser.parse(
                "#if ($bad.var$ == \"x\") (/say hit)", optionsWith(crosshairProvider()));
        assertNotNull(result.error(), "only absent-state misses are softened; real errors still surface");
    }

    // --- user-defined functions (/customfunction) ---

    @Test
    void aUserFunctionResolvesInsideAnExpression() {
        FunctionResolver fns = (name, args, context) ->
                name.equalsIgnoreCase("lightlevel") && args.isEmpty() ? Value.ofNumber(12) : null;
        ScriptParser.Options opts = options(Map.of()).withFunctions(fns);
        ScriptParser.ParseResult result = ScriptParser.parse("#if ($lightlevel() > 10$) (/say bright)", opts);
        assertNull(result.error());
        assertEquals(List.of("say bright"), contents(result));
    }

    @Test
    void anUnknownFunctionStillErrors() {
        ScriptParser.ParseResult result = ScriptParser.parse("#if ($nope() > 1$) (/say x)", options(Map.of()));
        assertNotNull(result.error(), "a name that's neither built-in nor a user function is still an error");
    }

    @Test
    void aUserFunctionEvaluatesArgsOnceAndBindsCallByValue() {
        // resolver shaped like CustomFunctionManager's: bind arg to a param, eval body in a scope
        FunctionResolver fns = (name, args, context) -> {
            if (!name.equalsIgnoreCase("dbl")) {
                return null;
            }
            VariableProvider scoped = new VariableProvider() {
                @Override
                public java.util.Set<String> names() {
                    return java.util.Set.of("x");
                }

                @Override
                public java.util.Optional<Value> resolve(String n) {
                    return n.equalsIgnoreCase("x") ? java.util.Optional.of(args.get(0)) : context.variables().resolve(n);
                }
            };
            return MathEvaluator.evaluateValue("x * 2",
                    new EvalContext(context.random(), scoped, context.tags(), context.blocks(), context.functions()));
        };
        // dbl(1 + 1): arg evaluates ONCE to 2, body x*2 = 4 — proves call-by-value + precedence
        ScriptParser.ParseResult result = ScriptParser.parse("#if ($dbl(1 + 1) == 4$) (/say ok)",
                options(Map.of()).withFunctions(fns));
        assertNull(result.error());
        assertEquals(List.of("say ok"), contents(result));
    }

    @Test
    void ifWithVariables() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("y", Value.ofNumber(70));
        ScriptParser.ParseResult result = ScriptParser.parse("#if ($y$ > 60) (/say high) #else (/say low)", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say high"), contents(result));
    }

    @Test
    void conditionalsDisabledIsAnError() {
        ScriptParser.Options opts = options(Map.of(), new SessionVariableStore(), true, NumberMathMode.AUTO_DETECT, true, true, true, false, 100);
        ScriptParser.ParseResult result = ScriptParser.parse("#if (1 > 0) (/say hi)", opts);
        assertNotNull(result.error());
        assertTrue(result.error().contains("disabled"));
    }

    // --- #for ---

    @Test
    void forCountsInclusiveWithStep() {
        ScriptParser.ParseResult result = parse("#for $x$ in 1..10 step 2 (/summon zombie ~$x$ ~ ~)");
        assertNull(result.error());
        assertEquals(List.of(
                "summon zombie ~1 ~ ~", "summon zombie ~3 ~ ~", "summon zombie ~5 ~ ~",
                "summon zombie ~7 ~ ~", "summon zombie ~9 ~ ~"), contents(result));
    }

    @Test
    void forCountsDownAutomatically() {
        ScriptParser.ParseResult result = parse("#for $x$ in 3..1 (/say $x$)");
        assertNull(result.error());
        assertEquals(List.of("say 3", "say 2", "say 1"), contents(result));
    }

    @Test
    void forRejectsBadHeaders() {
        assertNotNull(parse("#for $x$ in 1..10 step 0 (/say $x$)").error());
        assertNotNull(parse("#for $x$ in 1..10 step -1 (/say $x$)").error());
        assertNotNull(parse("#for $x$ 1..10 (/say $x$)").error());
        assertNotNull(parse("#for $x$ in 1 (/say $x$)").error());
    }

    // --- #foreach ---

    @Test
    void foreachIteratesLiteralItems() {
        ScriptParser.ParseResult result = parse("#foreach $mob$ in (zombie | skeleton | creeper) (/summon $mob$ ~ ~ ~)");
        assertNull(result.error());
        assertEquals(List.of("summon zombie ~ ~ ~", "summon skeleton ~ ~ ~", "summon creeper ~ ~ ~"), contents(result));
    }

    @Test
    void foreachItemsAreLiteralNbtSafe() {
        ScriptParser.ParseResult result = parse("#foreach $m$ in (0,0]} | 5.0,1]}) (/say $m$)");
        assertNull(result.error());
        assertEquals(List.of("say 0,0]}", "say 5.0,1]}"), contents(result));
    }

    @Test
    void foreachEscapedPipeIsLiteral() {
        ScriptParser.ParseResult result = parse("#foreach $m$ in (a \\| b) (/say $m$)");
        assertNull(result.error());
        assertEquals(List.of("say a | b"), contents(result));
    }

    @Test
    void foreachOverRange() {
        ScriptParser.ParseResult result = parse("#foreach $n$ in range(1, 5, 2) (/say $n$)");
        assertNull(result.error());
        assertEquals(List.of("say 1", "say 3", "say 5"), contents(result));
    }

    @Test
    void foreachOverTooManyItemsIsRejected() {
        ScriptParser.ParseResult result = parse("#foreach $n$ in range(1, 101) (/say $n$)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("loop limit"));
    }

    @Test
    void loopVariablesAreReadOnly() {
        ScriptParser.ParseResult result = parse("#repeat 2 (#set $i$ = 5)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("read-only"));
    }

    // --- #silent ---

    @Test
    void silentPrefixOnCommandOrigin() {
        ScriptParser.ParseResult result = parse("#silent /time set day && /weather clear");
        assertTrue(result.changed());
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::silent));
        assertEquals(List.of("time set day", "weather clear"), contents(result));
    }

    @Test
    void silentWorksWithoutInnerSlash() {
        ScriptParser.ParseResult result = parse("#silent time set day");
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::silent));
        assertEquals(List.of("time set day"), contents(result));
    }

    @Test
    void silentCombinesWithDirectives() {
        ScriptParser.ParseResult result = parse("#silent #repeat 2 (/say hi)");
        assertNull(result.error());
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::silent));
        assertEquals(List.of("say hi", "say hi"), contents(result));
    }

    @Test
    void silentAloneIsAnError() {
        assertNotNull(parse("#silent").error());
    }

    @Test
    void silentMidChainIsAnError() {
        ScriptParser.ParseResult result = parse("say a && #silent say b");
        assertNotNull(result.error());
        assertTrue(result.error().contains("start of the line"));
    }

    @Test
    void unknownDirectiveIsAnError() {
        ScriptParser.ParseResult result = parse("#bogus (/say hi)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("Unknown directive"));
    }

    @Test
    void whileNeedsLazyExecution() {
        // the eager test harness can't run a cross-tick loop
        ScriptParser.ParseResult result = parse("#while (1 > 0) (/say hi)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("Lazy Execution"));
    }

    @Test
    void silentDisabledInConfigIsAnError() {
        ScriptParser.Options opts = options(Map.of(), new SessionVariableStore(), true, NumberMathMode.AUTO_DETECT, false, true, true, true, 100);
        ScriptParser.ParseResult result = ScriptParser.parse("#silent /time set day", opts);
        assertNotNull(result.error());
        assertTrue(result.error().contains("disabled"));
    }

    @Test
    void aliasBodyMayCarrySilentPrefix() {
        ScriptParser.ParseResult result = parse("sshh", Map.of("sshh", "#silent /time set day && /weather clear"));
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::silent));
        assertEquals(List.of("time set day", "weather clear"), contents(result));
    }

    @Test
    void aliasBodyMayContainDirectives() {
        ScriptParser.ParseResult result = parse("waves", Map.of("waves", "#repeat 2 (/summon zombie) && /say done"));
        assertNull(result.error());
        assertEquals(List.of("summon zombie", "summon zombie", "say done"), contents(result));
    }

    @Test
    void fireballCommandComputesDirectionFromRotation() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(0));   // facing south (+Z)
        store.set("client.pitch", Value.ofNumber(0)); // level
        Map<String, String> aliases = Map.of("fireball",
                "<power:int> <speed:float> "
                        + "#local $dx$ = -sin(client.yaw) * cos(client.pitch) && "
                        + "#local $dy$ = -sin(client.pitch) && "
                        + "#local $dz$ = cos(client.yaw) * cos(client.pitch) && "
                        + "/summon minecraft:fireball ~$1.5*dx$ ~$1.2 + 1.5*dy$ ~$1.5*dz$ "
                        + "{ExplosionPower:$power$b,Motion:[$speed*dx$d,$speed*dy$d,$speed*dz$d],acceleration_power:0.02}");
        ScriptParser.ParseResult result = ScriptParser.parse("fireball 3 0.5", options(aliases, store));
        assertNull(result.error());
        assertEquals(List.of("summon minecraft:fireball ~0 ~1.2 ~1.5 {ExplosionPower:3b,Motion:[0d,0d,0.5d],acceleration_power:0.02}"),
                contents(result));
        assertTrue(store.resolve("dx").isEmpty(), "#local stays local");
    }

    // --- #silent group form ---

    @Test
    void silentGroupScopesPerStatement() {
        ScriptParser.ParseResult result = parse("#repeat 2 (#silent (/give @s stick) && /say here is your stick)");
        assertNull(result.error());
        assertEquals(List.of("give @s stick", "say here is your stick", "give @s stick", "say here is your stick"), contents(result));
        assertTrue(result.script().statements().get(0).silent());
        assertFalse(result.script().statements().get(1).silent());
        assertTrue(result.script().statements().get(2).silent());
        assertFalse(result.script().statements().get(3).silent());
    }

    @Test
    void silentGroupAtLineStartIsStatementForm() {
        ScriptParser.ParseResult result = parse("#silent (/give @s stick) && /say done");
        assertNull(result.error());
        assertTrue(result.script().statements().get(0).silent());
        assertFalse(result.script().statements().get(1).silent());
    }

    @Test
    void silentSuppressesSetNotices() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#silent #set $x$ = 70 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertTrue(result.notices().isEmpty(), "silent lines must not spam set notices");
        assertEquals(List.of("say 70"), contents(result));
    }

    // --- #local ---

    @Test
    void localDoesNotCommitOrNotice() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#local $x$ = 70 && say $x$", options(Map.of(), store));
        assertNull(result.error());
        assertEquals(List.of("say 70"), contents(result));
        assertTrue(result.notices().isEmpty(), "#local is quiet");
        assertTrue(store.resolve("x").isEmpty(), "#local never touches the session");
    }

    @Test
    void localThenExplicitSetCommits() {
        SessionVariableStore store = new SessionVariableStore();
        ScriptParser.ParseResult result = ScriptParser.parse("#local $x$ = 1 && #set $x$ = $x$ + 1", options(Map.of(), store));
        assertNull(result.error());
        assertEquals("2", store.resolve("x").orElseThrow().displayString());
    }

    // --- #norecord ---

    @Test
    void norecordMarksTheScript() {
        ScriptParser.ParseResult result = parse("#norecord say hi");
        assertNull(result.error());
        assertTrue(result.changed());
        assertEquals(Script.HistoryMode.SKIP, result.script().history());
        assertEquals(List.of("say hi"), contents(result));
    }

    @Test
    void prefixesCombineInAnyOrder() {
        ScriptParser.ParseResult result = parse("#norecord #silent /say hi");
        assertNull(result.error());
        assertEquals(Script.HistoryMode.SKIP, result.script().history());
        assertTrue(result.script().statements().get(0).silent());

        ScriptParser.ParseResult reversed = parse("#silent #norecord /say hi");
        assertNull(reversed.error());
        assertEquals(Script.HistoryMode.SKIP, reversed.script().history());
        assertTrue(reversed.script().statements().get(0).silent());
    }

    @Test
    void recordedByDefault() {
        ScriptParser.ParseResult result = parse("say a && say b");
        assertEquals(Script.HistoryMode.NORMAL, result.script().history());
    }

    @Test
    void recordPrefixForcesHistory() {
        ScriptParser.ParseResult result = parse("#record say hi");
        assertNull(result.error());
        assertEquals(Script.HistoryMode.FORCE, result.script().history());
        assertEquals(List.of("say hi"), contents(result));
    }

    @Test
    void lastHistoryPrefixWins() {
        assertEquals(Script.HistoryMode.FORCE, parse("#norecord #record say hi").script().history());
        assertEquals(Script.HistoryMode.SKIP, parse("#record #norecord say hi").script().history());
    }

    // --- #echo removed: /echo is a client command now ---

    @Test
    void echoDirectiveIsGone() {
        assertNotNull(parse("#echo hi").error());
        // /echo in a chain is just a (client-routed) command
        ScriptParser.ParseResult result = parse("say a && /echo done");
        assertNull(result.error());
        assertEquals(Script.Kind.COMMAND, result.script().statements().get(1).kind());
        assertEquals("echo done", result.script().statements().get(1).content());
    }

    // --- #elseif ---

    @Test
    void elseifChainsPickTheRightBranch() {
        assertEquals(List.of("say b"),
                contents(parse("#if (1 > 2) (/say a) #elseif (2 > 1) (/say b) #else (/say c)")));
        assertEquals(List.of("say c"),
                contents(parse("#if (1 > 2) (/say a) #elseif (2 > 3) (/say b) #else (/say c)")));
        assertEquals(List.of("say a"),
                contents(parse("#if (2 > 1) (/say a) #elseif (3 > 2) (/say b) #else (/say c)")));
    }

    @Test
    void elseifWithoutElseFallsThroughToNothing() {
        ScriptParser.ParseResult result = parse("#if (1 > 2) (/say a) #elseif (2 > 3) (/say b)");
        assertNull(result.error());
        assertTrue(result.script().statements().isEmpty());
    }

    // --- chat-origin lines ---

    @Test
    void chatSilentLineParsesWithChatSegments() {
        ScriptParser.ParseResult result = ScriptParser.parseChatLine("#silent /time set day && hello there", options(Map.of()));
        assertTrue(result.changed());
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::silent));
        assertTrue(result.script().statements().get(0).isCommand());
        assertFalse(result.script().statements().get(1).isCommand());
        assertEquals("hello there", result.script().statements().get(1).content());
    }

    @Test
    void chatDirectiveLineIsIntercepted() {
        ScriptParser.ParseResult result = ScriptParser.parseChatLine("#repeat 2 (/say hi)", options(Map.of()));
        assertNull(result.error());
        assertTrue(result.changed());
        assertEquals(List.of("say hi", "say hi"), contents(result));
    }

    @Test
    void ordinaryHashChatPassesThrough() {
        ScriptParser.ParseResult result = ScriptParser.parseChatLine("#1 victory royale", options(Map.of()));
        assertFalse(result.changed());
        assertNull(result.error());
    }

    @Test
    void plainChatPassesThrough() {
        ScriptParser.ParseResult result = ScriptParser.parseChatLine("hello world", options(Map.of()));
        assertFalse(result.changed());
        assertNull(result.error());
    }
}
