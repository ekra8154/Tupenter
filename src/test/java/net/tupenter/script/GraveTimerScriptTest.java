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
 * The seeded "gravetimer" showcase script, exercised as pure logic so its
 * control flow is verified without dying in-game. It leans on the lifecycle
 * edge {@code client.just_died}, {@code loaded(...)}/{@code block(...)} and
 * {@code real.timestamp}; the death branch marks where you died, then a
 * 5-minute despawn countdown announces at falling thresholds and PAUSES while
 * the grave chunk is unloaded.
 *
 * <p>Each {@link #tick()} re-parses the body eagerly against a shared session
 * store — the only state that carries across ticks — which is a strict model of
 * the tick loop: everything durable is a #set/#setdefault, nothing relies on
 * in-loop scope. If the body's parens, markers or thresholds broke, this fails.
 */
class GraveTimerScriptTest {

    /** The body exactly as seeded (name prefix stripped, as the tick runner runs it). */
    private static final String BODY =
            "#setdefault grave = \"\" && #setdefault graveleft = -1 && #setdefault gravestamp = 0 && #setdefault gravesaid = 999 && #if (client.just_died) (#set grave = client.blockpos && #local isvoid = block(grave) == \"minecraft:void_air\" && #if (isvoid) (/echohud &cYou died in the void - nothing to recover && #set graveleft = -1) #else (#set graveleft = 300 && #set gravestamp = real.timestamp && #set gravesaid = 300)) && #if (client.just_respawned && graveleft > 0) (/echo &7Grave at &f$grave$&7 - items despawn in &e$floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$&7 (pauses while that chunk is unsimulated)) && #if (graveleft > 0 && simulated(grave)) (#set graveleft = graveleft - (real.timestamp - gravestamp) && #set gravestamp = real.timestamp && #local now = floor(graveleft) && #if (now < gravesaid) (#set gravesaid = now && #if (client.health > 0 && (now == 120 || now == 60 || now == 30 || (now <= 10 && now >= 1))) (/echohud &e$floor(now / 60)$:$now % 60 < 10 ? \"0\" : \"\"$$now % 60$ &7until your items despawn) && #if (now <= 0) (/echohud &cYour items have despawned && #set graveleft = -1))) && #if (graveleft > 0 && !simulated(grave)) (#set gravestamp = real.timestamp && /echohud &8grave not simulated - timer paused at $floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$)";

    // The mutable "world" the stubs read — the harness sets these before each tick.
    private boolean justDied;
    private boolean justRespawned;
    private String blockpos = "10 64 20";
    private String blockId = "minecraft:air"; // what block(grave) returns
    private boolean simulated = true;         // what simulated(grave) returns
    private long timestamp = 1000;
    private double health = 20;

    private final SessionVariableStore store = new SessionVariableStore();

    /** Run the body once with the current world state; returns this tick's sends. */
    private List<String> tick() {
        VariableRegistry variables = new VariableRegistry();
        variables.register(store); // #set values resolve back
        variables.register(new VariableProvider() {
            @Override
            public Set<String> names() {
                return Set.of("client.just_died", "client.just_respawned", "client.blockpos",
                        "client.health", "real.timestamp");
            }

            @Override
            public Optional<Value> resolve(String name) {
                return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                    case "client.just_died" -> Optional.of(Value.of(justDied));
                    case "client.just_respawned" -> Optional.of(Value.of(justRespawned));
                    case "client.blockpos" -> Optional.of(Value.of(blockpos));
                    case "client.health" -> Optional.of(Value.ofNumber((long) health));
                    case "real.timestamp" -> Optional.of(Value.ofNumber(timestamp));
                    default -> Optional.empty();
                };
            }
        });
        BlockReader blocks = new BlockReader() {
            @Override
            public String blockAt(long x, long y, long z) {
                return blockId;
            }

            @Override
            public Boolean simulated(long x, long y, long z) {
                return simulated;
            }
        };

        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(),
                true, true, true, true, 1000, 1000, new Random(1), variables, store,
                TagResolver.NONE, blocks, FunctionResolver.NONE, false);

        ScriptParser.ParseResult result = ScriptParser.parse(BODY, options);
        assertNull(result.error(), "gravetimer body parsed");
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    private String num(String var) {
        return store.resolve(var).orElseThrow().displayString();
    }

    @Test
    void beforeAnyDeathNothingHappensAndTheTimerStaysInactive() {
        justDied = false;
        List<String> sends = tick();
        assertEquals("-1", num("graveleft"), "no countdown yet");
        assertTrue(sends.stream().noneMatch(s -> s.contains("despawn") || s.contains("Grave") || s.contains("paused")),
                "silent before a death: " + sends);
    }

    @Test
    void aNormalDeathMarksTheGraveAndArmsAFiveMinuteTimer() {
        justDied = false;
        tick(); // seed the defaults

        justDied = true;
        blockpos = "128 70 -64";
        blockId = "minecraft:air"; // not the void
        health = 0;                // dead: on the death screen
        timestamp = 1000;
        tick();

        assertEquals("128 70 -64", store.resolve("grave").orElseThrow().displayString());
        assertEquals("300", num("graveleft"), "5 minutes armed at death");
        assertEquals("300", num("gravesaid"));
    }

    @Test
    void theCoordsAreAnnouncedOnRespawnNotWhileStillOnTheDeathScreen() {
        justDied = false;
        tick();

        // die, then sit on the death screen for 8 seconds — no coords announced yet
        justDied = true;
        blockpos = "128 70 -64";
        health = 0;
        timestamp = 1000;
        List<String> atDeath = tick();
        assertTrue(atDeath.stream().noneMatch(s -> s.contains("Grave at")),
                "nothing announced on the death screen: " + atDeath);

        justDied = false;
        timestamp = 1008;
        tick(); // still dead, timer ticking down quietly

        // respawn: NOW the grave is announced, showing the time already elapsed
        justRespawned = true;
        health = 20;
        timestamp = 1008;
        List<String> onRespawn = tick();
        assertTrue(onRespawn.stream().anyMatch(s -> s.contains("Grave at") && s.contains("128 70 -64")),
                "announced on respawn: " + onRespawn);
        assertTrue(onRespawn.stream().anyMatch(s -> s.contains("4:52")),
                "shows the 8s already spent (5:00 -> 4:52): " + onRespawn);
    }

    @Test
    void dyingInTheVoidRecordsNoTimerBecauseThereIsNothingToRecover() {
        justDied = false;
        tick();

        justDied = true;
        blockId = "minecraft:void_air";
        List<String> sends = tick();

        assertEquals("-1", num("graveleft"), "no countdown for a void death");
        assertTrue(sends.stream().anyMatch(s -> s.contains("void")), "warns about the void: " + sends);
    }

    @Test
    void theCountdownAnnouncesAtThresholdsFormattedAsMinutesAndSeconds() {
        justDied = false;
        tick();
        justDied = true;
        timestamp = 1000;
        tick(); // die at t=1000, graveleft=300

        justDied = false;

        // 2:00 — 180s elapsed
        timestamp = 1180;
        assertTrue(String.join(" | ", tick()).contains("2:00"), "announces 2:00");
        assertEquals("120", num("graveleft"));

        // 1:00 — one minute later
        timestamp = 1240;
        assertTrue(String.join(" | ", tick()).contains("1:00"), "announces 1:00");

        // 0:30
        timestamp = 1270;
        assertTrue(String.join(" | ", tick()).contains("0:30"), "announces 0:30");

        // 0:10 — the per-second phase, zero-padded
        timestamp = 1290;
        assertTrue(String.join(" | ", tick()).contains("0:10"), "announces 0:10");

        // 0:05
        timestamp = 1295;
        assertTrue(String.join(" | ", tick()).contains("0:05"), "zero-pads seconds");
    }

    @Test
    void anUnsimulatedGraveChunkPausesTheCountdown() {
        justDied = false;
        tick();
        justDied = true;
        timestamp = 1000;
        tick(); // graveleft=300 at t=1000

        justDied = false;

        // 60s pass while the grave chunk is beyond simulation distance — the
        // items aren't ticking there, so the despawn timer must hold
        simulated = false;
        timestamp = 1060;
        List<String> paused = tick();
        assertEquals("300", num("graveleft"), "paused while the grave chunk isn't simulated");
        assertTrue(paused.stream().anyMatch(s -> s.contains("paused")), "shows a paused HUD: " + paused);

        // it comes back into simulation; only time from HERE counts
        simulated = true;
        timestamp = 1100;
        tick();
        assertEquals("260", num("graveleft"), "resumes without charging the paused gap");
    }

    @Test
    void theTimerRetargetsToYourMostRecentDeath() {
        justDied = false;
        tick();
        justDied = true;
        blockpos = "1 1 1";
        timestamp = 1000;
        tick();
        // let it run down a bit
        justDied = false;
        timestamp = 1100;
        tick();
        assertEquals("200", num("graveleft"));

        // die again somewhere else — the timer resets to a fresh 5:00 at the new grave
        justDied = true;
        blockpos = "2 2 2";
        timestamp = 1105;
        tick();
        assertEquals("2 2 2", store.resolve("grave").orElseThrow().displayString());
        assertEquals("300", num("graveleft"), "a new death starts over");
    }
}
