package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeded creeperAlert body as logic: in SURVIVAL, warn once — with how
 * close — when the nearest creeper comes within 8 blocks; go quiet in creative;
 * re-arm after the creeper leaves. Exercised with stub entity access so the
 * gamemode gate, nearest_entity/entity/dist wiring and the edge flag are all
 * checked without a live world.
 */
class CreeperAlertScriptTest {

    private static final String BODY =
            "#setdefault creeperNear = false && #if (client.gamemode == \"survival\") "
            + "(#local c = nearest_entity(8, \"minecraft:creeper\") "
            + "&& #if (c != \"miss\" && !creeperNear) (#local d = round(dist(client.pos, entity(c, \"pos\", client.pos))) "
            + "&& /echo &cCreeper $d$ blocks away! && #set creeperNear = true) "
            + "&& #if (c == \"miss\" && creeperNear) (#set creeperNear = false)) #else (#set creeperNear = false)";

    private String gamemode = "survival";
    private String nearestCreeper = "miss"; // "miss" or a uuid
    private String creeperPos = "3 0 4";    // dist 5 from "0 0 0"
    private final SessionVariableStore store = new SessionVariableStore();

    private List<String> tick() {
        VariableRegistry variables = new VariableRegistry();
        variables.register(store);
        variables.register(new VariableProvider() {
            public Set<String> names() { return Set.of("client.gamemode", "client.pos"); }
            public Optional<Value> resolve(String n) {
                return switch (n.toLowerCase(java.util.Locale.ROOT)) {
                    case "client.gamemode" -> Optional.of(Value.of(gamemode));
                    case "client.pos" -> Optional.of(Value.of("0 0 0"));
                    default -> Optional.empty();
                };
            }
        });
        EntityAccess entities = new EntityAccess() {
            public Value entityField(String selector, String field) {
                if (field.equals("pos")) return Value.of(creeperPos);
                throw new ExpressionException("no field " + field);
            }
            public String raycastUuid(double maxDist) { return "miss"; }
            public List<String> nearbyUuids(double radius, String type) { return List.of(); }
            public String nearestUuid(double radius, String type) { return nearestCreeper; }
            public Value slotField(String slot, String field) { throw new ExpressionException("no slots"); }
            public List<String> nbtKeys(String selector, String path) { return List.of(); }
        };
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(),
                true, true, true, true, 1000, 1000, new Random(1), variables, store,
                TagResolver.NONE, BlockReader.NONE, FunctionResolver.NONE, Raycaster.NONE, entities, false);
        ScriptParser.ParseResult result = ScriptParser.parse(BODY, options);
        assertNull(result.error(), "creeperAlert parsed: " + result.error());
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    @Test
    void survivalWarnsOnceWithTheDistanceWhenACreeperComesWithinRange() {
        nearestCreeper = "miss";
        assertTrue(tick().stream().noneMatch(s -> s.contains("Creeper")), "quiet with no creeper near");

        nearestCreeper = "creeper-1"; // one wanders within 8 (5 blocks off)
        List<String> alert = tick();
        assertTrue(alert.stream().anyMatch(s -> s.contains("Creeper 5 blocks away")),
                "warns with distance: " + alert);

        // still there next tick — no repeat
        assertTrue(tick().stream().noneMatch(s -> s.contains("Creeper")), "no spam while it lingers");

        // it leaves, then a new one arrives — warns again
        nearestCreeper = "miss";
        tick();
        nearestCreeper = "creeper-2";
        assertTrue(tick().stream().anyMatch(s -> s.contains("Creeper 5 blocks away")), "re-arms after it leaves");
    }

    @Test
    void creativeModeStaysSilent() {
        gamemode = "creative";
        nearestCreeper = "creeper-1";
        assertTrue(tick().stream().noneMatch(s -> s.contains("Creeper")), "no warning in creative");
        assertEquals("false", store.resolve("creeperNear").orElseThrow().displayString(),
                "the flag is held cleared in creative");
    }
}
