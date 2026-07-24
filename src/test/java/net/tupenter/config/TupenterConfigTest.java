package net.tupenter.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The config file is the mod's only persistent state, and load() copies every
 * field across BY HAND — a field that's saved but not copied loads as its
 * default, and the next save() erases it from disk. That has already happened
 * once (custom functions vanishing), which is why the copy block carries a
 * FOOTGUN comment.
 *
 * <p>A comment can't fail a build, so {@link #everyConfigFieldSurvivesARoundTrip()}
 * does: it discovers the fields by reflection, so adding one and forgetting its
 * copy line breaks the test rather than someone's saved work.
 *
 * <p>These run against real files in a temp directory rather than around them,
 * so the JSON, the migrations and the repair paths are all genuinely exercised.
 */
class TupenterConfigTest {

    private TupenterConfig saved;

    @BeforeEach
    void isolate(@TempDir Path directory) {
        saved = TupenterConfig.INSTANCE;
        TupenterConfig.configDirectory = directory;
        TupenterConfig.INSTANCE = new TupenterConfig();
    }

    @AfterEach
    void restore() {
        TupenterConfig.INSTANCE = saved;
        TupenterConfig.configDirectory = null;
    }

    /** Public, non-static, non-deprecated fields — what a user can actually configure. */
    private static List<Field> configurableFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : TupenterConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getAnnotation(Deprecated.class) == null) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * A valid alternate for each range-clamped int. load() validates as well as
     * copies, and a clamp is a deliberate transform rather than a lost field —
     * so the round trip has to offer a value that's actually in range. The
     * comment on each is the range load() enforces; add a clamp and this test
     * fails until its field is listed here.
     */
    private static final Map<String, Integer> CLAMPED_INTS = Map.of(
            "chatInputLength", 1024,      // 256..32766
            "importHistoryCount", 25,     // 1..50
            "historyDepth", 4,            // >= 1
            "maxLoopIterations", 250,     // 1..100000
            "maxCommandsPerTick", 32,     // 1..512
            "maxCommandsPerScript", 500,  // 1..100000
            "maxConcurrentScripts", 16,   // 1..64
            "resendAmount", 3,            // >= 1
            "resendDelay", 4,             // >= 0
            "messageDelay", 2);           // >= 0

    /**
     * A value distinctly different from the default, so "it came back" can't be
     * confused with "it was never set".
     */
    private static Object distinctValue(Field field, Object current) throws Exception {
        Class<?> type = field.getType();
        if (type == boolean.class) {
            return !((boolean) current);
        }
        if (type == int.class) {
            Integer inRange = CLAMPED_INTS.get(field.getName());
            return inRange != null ? inRange : ((int) current) + 7;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            // pick any constant that isn't the current one
            for (Object constant : constants) {
                if (constant != current) {
                    return constant;
                }
            }
            return current;
        }
        if (List.class.isAssignableFrom(type)) {
            if (field.getName().equals("globalScripts")) {
                return new ArrayList<>(List.of(new TupenterConfig.GlobalScript("gid1", "/say global")));
            }
            return new ArrayList<>(List.of("round-trip-" + field.getName()));
        }
        if (Map.class.isAssignableFrom(type)) {
            TupenterConfig.WorldScriptState state = new TupenterConfig.WorldScriptState();
            state.enabledGlobalIds.add("gid1");
            state.scripts.add(new TupenterConfig.WorldScript("wid1", "/say world", true));
            Map<String, TupenterConfig.WorldScriptState> map = new LinkedHashMap<>();
            map.put("server:example.com", state);
            return map;
        }
        throw new AssertionError("no round-trip value defined for " + field.getName() + " (" + type + ") — "
                + "add one so the new config field is actually covered");
    }

    @Test
    void everyConfigFieldSurvivesARoundTrip() throws Exception {
        List<Field> fields = configurableFields();
        assertTrue(fields.size() > 25, "expected the real config surface, found " + fields.size());

        Map<String, Object> expected = new LinkedHashMap<>();
        for (Field field : fields) {
            Object value = distinctValue(field, field.get(TupenterConfig.INSTANCE));
            field.set(TupenterConfig.INSTANCE, value);
            expected.put(field.getName(), value);
        }

        TupenterConfig.save();
        TupenterConfig.INSTANCE = new TupenterConfig(); // as if the game restarted
        TupenterConfig.load();

        List<String> lost = new ArrayList<>();
        for (Field field : fields) {
            Object before = expected.get(field.getName());
            Object after = field.get(TupenterConfig.INSTANCE);
            if (!sameValue(field, before, after)) {
                lost.add(field.getName() + ": saved " + describe(before) + ", loaded back " + describe(after));
            }
        }
        if (!lost.isEmpty()) {
            fail("config fields lost across save/load — load() copies fields BY HAND, "
                    + "so each of these is probably a missing copy line:\n  " + String.join("\n  ", lost));
        }
    }

    /** Scripts compare by content: GSON rebuilds the objects, so identity and equals() are both out. */
    private static boolean sameValue(Field field, Object before, Object after) {
        if (field.getName().equals("globalScripts")) {
            List<?> a = (List<?>) before;
            List<?> b = (List<?>) after;
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                TupenterConfig.GlobalScript one = (TupenterConfig.GlobalScript) a.get(i);
                TupenterConfig.GlobalScript two = (TupenterConfig.GlobalScript) b.get(i);
                if (!one.id.equals(two.id) || !one.text.equals(two.text)) {
                    return false;
                }
            }
            return true;
        }
        if (field.getName().equals("worldScripts")) {
            Map<?, ?> b = (Map<?, ?>) after;
            TupenterConfig.WorldScriptState state =
                    (TupenterConfig.WorldScriptState) b.get("server:example.com");
            return state != null
                    && state.enabledGlobalIds.contains("gid1")
                    && state.scripts.size() == 1
                    && state.scripts.get(0).text.equals("/say world")
                    && state.scripts.get(0).enabled;
        }
        return before.equals(after);
    }

    private static String describe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /**
     * The other half of the round trip: values that are out of range are
     * REPAIRED on load, not carried through. A hand-edited file asking for a
     * 5000-line import or a zero-deep history has to come back sane.
     */
    @Test
    void outOfRangeValuesAreClampedOnLoad() throws Exception {
        java.nio.file.Files.writeString(TupenterConfig.configDirectory.resolve("tupenter.json"),
                """
                {
                  "importHistoryCount": 5000,
                  "historyDepth": 0,
                  "resendDelay": -20,
                  "messageDelay": -1
                }
                """);
        TupenterConfig.load();
        assertEquals(50, TupenterConfig.INSTANCE.importHistoryCount, "capped at the slider's maximum");
        assertEquals(1, TupenterConfig.INSTANCE.historyDepth, "at least one");
        assertEquals(0, TupenterConfig.INSTANCE.resendDelay, "never negative");
        assertEquals(0, TupenterConfig.INSTANCE.messageDelay);
    }

    /** A missing file is a first launch, not an error. */
    @Test
    void loadingWithNoFileLeavesTheDefaults() {
        TupenterConfig.load();
        assertTrue(TupenterConfig.INSTANCE.resetOnNewSession);
        assertFalse(TupenterConfig.INSTANCE.tickScriptsEnabled, "tick scripts stay opt-in");
    }

    // -------------------------------------------------------- the migration

    /**
     * Pre-per-world configs kept a flat script list, "//" meaning disabled.
     * They become global DEFINITIONS armed in no world — the safe default,
     * because a script that used to run everywhere must not silently start
     * running on someone's survival server after an update.
     */
    @Test
    void legacyFlatScriptsBecomeGlobalDefinitionsArmedNowhere() throws Exception {
        TupenterConfig.INSTANCE.tickScripts = new ArrayList<>(List.of(
                "/say always", "// /say was disabled", "   ", "/say third"));
        TupenterConfig.save();
        TupenterConfig.INSTANCE = new TupenterConfig();
        TupenterConfig.load();

        List<TupenterConfig.GlobalScript> migrated = TupenterConfig.INSTANCE.globalScripts;
        assertEquals(3, migrated.size(), "blank lines are dropped, disabled ones are kept as definitions");
        assertEquals("/say always", migrated.get(0).text);
        assertEquals("/say was disabled", migrated.get(1).text, "the // marker is stripped");
        assertEquals("/say third", migrated.get(2).text);
        for (TupenterConfig.GlobalScript script : migrated) {
            assertFalse(script.id.isEmpty(), "every migrated script gets an id");
        }

        assertTrue(TupenterConfig.INSTANCE.armedScripts("server:anywhere").isEmpty(),
                "migrated scripts are armed in NO world");
        assertTrue(TupenterConfig.INSTANCE.tickScriptsMigrationNoticePending,
                "the user is told once where their scripts went");
    }

    @Test
    void theMigrationRunsOnceAndThenLeavesThingsAlone() throws Exception {
        TupenterConfig.INSTANCE.tickScripts = new ArrayList<>(List.of("/say once"));
        TupenterConfig.save();
        TupenterConfig.INSTANCE = new TupenterConfig();
        TupenterConfig.load();
        assertEquals(1, TupenterConfig.INSTANCE.globalScripts.size());

        TupenterConfig.INSTANCE = new TupenterConfig();
        TupenterConfig.load();
        assertEquals(1, TupenterConfig.INSTANCE.globalScripts.size(),
                "loading again must not duplicate the migrated scripts");
    }

    /** Hand-edited files are repaired rather than crashing the load. */
    @Test
    void aHandEditedFileIsRepairedOnLoad() throws Exception {
        java.nio.file.Files.writeString(TupenterConfig.configDirectory.resolve("tupenter.json"),
                """
                {
                  "globalScripts": [ {"text": "/say no id"}, {"id": "keep"} ],
                  "worldScripts": { "server:a": null }
                }
                """);
        TupenterConfig.load();

        for (TupenterConfig.GlobalScript script : TupenterConfig.INSTANCE.globalScripts) {
            assertNotNull(script.id);
            assertFalse(script.id.isEmpty(), "a missing id is generated");
            assertNotNull(script.text, "a missing text becomes empty, not null");
        }
        assertNull(TupenterConfig.INSTANCE.worldScripts.get("server:a"), "null world state is dropped");
    }

    @Test
    void malformedJsonLeavesTheDefaultsRatherThanThrowing() throws Exception {
        java.nio.file.Files.writeString(TupenterConfig.configDirectory.resolve("tupenter.json"),
                "{ this is not json");
        TupenterConfig.load();
        assertTrue(TupenterConfig.INSTANCE.resetOnNewSession, "a corrupt file falls back to defaults");
    }

    // ------------------------------------------------------ per-world arming

    @Test
    void aWorldRunsOnlyWhatIsArmedThere() {
        TupenterConfig config = TupenterConfig.INSTANCE;
        config.globalScripts.add(new TupenterConfig.GlobalScript("g1", "/say global one"));
        config.globalScripts.add(new TupenterConfig.GlobalScript("g2", "/say global two"));

        TupenterConfig.WorldScriptState home = config.worldStateOrCreate("world:home");
        home.enabledGlobalIds.add("g1");
        home.scripts.add(new TupenterConfig.WorldScript("w1", "/say home only", true));
        home.scripts.add(new TupenterConfig.WorldScript("w2", "/say home off", false));

        assertEquals(List.of("/say global one", "/say home only"), config.armedScriptLines("world:home"));
        assertEquals(List.of(), config.armedScriptLines("server:elsewhere"),
                "a world you never configured runs NOTHING");
        assertEquals(List.of(), config.armedScriptLines(null));
    }

    @Test
    void disablingAnArmedScriptReturnsItAndStopsIt() {
        TupenterConfig config = TupenterConfig.INSTANCE;
        config.globalScripts.add(new TupenterConfig.GlobalScript("g1", "/say global"));
        TupenterConfig.WorldScriptState home = config.worldStateOrCreate("world:home");
        home.enabledGlobalIds.add("g1");
        home.scripts.add(new TupenterConfig.WorldScript("w1", "/say local", true));

        assertEquals("/say global", config.disableArmedScript("world:home", true, "g1"));
        assertEquals("/say local", config.disableArmedScript("world:home", false, "w1"));
        assertEquals(List.of(), config.armedScriptLines("world:home"));

        assertNull(config.disableArmedScript("world:home", true, "g1"), "already off");
        assertNull(config.disableArmedScript("world:nowhere", true, "g1"), "unknown world");
    }

    /** A script may carry an optional leading name, like a custom command without params. */
    @Test
    void scriptsCanBeNamedAndTheNameIsNotPartOfTheBody() {
        assertEquals("restock", TupenterConfig.scriptName("restock = /clear && #wait 1s"));
        assertEquals("/clear && #wait 1s", TupenterConfig.scriptBody("restock = /clear && #wait 1s"));
        assertEquals("", TupenterConfig.scriptName("/clear"));
        assertEquals("/clear", TupenterConfig.scriptBody("/clear"));
    }

    @Test
    void anArmedScriptCarriesAStableKeyPerKind() {
        TupenterConfig.ArmedScript global = new TupenterConfig.ArmedScript(true, "abc", "/say hi");
        TupenterConfig.ArmedScript world = new TupenterConfig.ArmedScript(false, "abc", "/say hi");
        assertEquals("g:abc", global.key());
        assertEquals("w:abc", world.key());
        assertFalse(global.key().equals(world.key()),
                "a global and a world script with the same id must not share a pid");
    }
}
