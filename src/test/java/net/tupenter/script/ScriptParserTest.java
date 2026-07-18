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
    void chainSplitsIntoCommands() {
        ScriptParser.ParseResult result = parse("time set day && weather clear");
        assertTrue(result.changed());
        assertEquals(List.of("time set day", "weather clear"), contents(result));
        assertTrue(result.script().statements().stream().allMatch(Script.SendStatement::isCommand));
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
    void nonAliasChatSegmentsAreForcedToCommands() {
        ScriptParser.ParseResult result = parse("time set day && hello");
        assertTrue(result.changed());
        assertTrue(result.script().statements().get(1).isCommand());
        assertEquals("hello", result.script().statements().get(1).content());
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
        ScriptParser.ParseResult result = parse("#while (1 > 0) (/say hi)");
        assertNotNull(result.error());
        assertTrue(result.error().contains("Unknown directive"));
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

    // --- #echo ---

    @Test
    void echoEmitsALocalStatement() {
        ScriptParser.ParseResult result = parse("#echo y is $1+1$");
        assertNull(result.error());
        assertEquals(1, result.script().statements().size());
        Script.SendStatement echo = result.script().statements().get(0);
        assertEquals(Script.Kind.ECHO, echo.kind());
        assertEquals("y is 2", echo.content());
    }

    @Test
    void echoWorksMidChain() {
        ScriptParser.ParseResult result = parse("say a && #echo done");
        assertNull(result.error());
        assertEquals(Script.Kind.COMMAND, result.script().statements().get(0).kind());
        assertEquals(Script.Kind.ECHO, result.script().statements().get(1).kind());
    }

    @Test
    void echoNeedsText() {
        assertNotNull(parse("#echo").error());
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
