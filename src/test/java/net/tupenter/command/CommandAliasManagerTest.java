package net.tupenter.command;

import net.tupenter.config.ConfigTestSupport;
import net.tupenter.config.TupenterConfig;
import net.tupenter.script.AliasDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom commands, as they're actually stored: one "name signature = body"
 * string per entry in the config list, re-parsed on every read.
 *
 * <p>The dangerous property of that design is that {@code parseDefinition}
 * returns null rather than throwing — a definition that stops parsing doesn't
 * error, it silently ISN'T THERE. That has already bitten once (a shipped
 * `circle` alias vanished when the {@code =} became required), so the round
 * trip and the skip behaviour both get pinned here.
 */
class CommandAliasManagerTest {

    private TupenterConfig previous;

    @BeforeEach
    void isolate(@TempDir Path directory) {
        previous = ConfigTestSupport.isolate(directory);
    }

    @AfterEach
    void restore() {
        ConfigTestSupport.restore(previous);
    }

    // ------------------------------------------------------------- storage

    @Test
    void addingACommandStoresItInTheFormItIsRead() {
        CommandAliasManager.addAlias("sunny", "= /weather clear");
        assertEquals(List.of("sunny = /weather clear"), TupenterConfig.INSTANCE.aliases);

        Map<String, AliasDefinition> aliases = CommandAliasManager.getAliasMap();
        assertEquals(1, aliases.size());
        assertEquals("/weather clear", aliases.get("sunny").body());
    }

    @Test
    void aCommandSurvivesTheConfigRoundTrip() {
        CommandAliasManager.addAlias("waves", "<count:int> <mob:entity> = #repeat $count$ (/summon $mob$ ~ ~ ~)");
        TupenterConfig.save();

        TupenterConfig.INSTANCE = new TupenterConfig();
        TupenterConfig.load();

        AliasDefinition reloaded = CommandAliasManager.getAliasMap().get("waves");
        assertNotNull(reloaded, "the command came back");
        assertEquals(2, reloaded.params().size());
        assertEquals("#repeat $count$ (/summon $mob$ ~ ~ ~)", reloaded.body());
    }

    @Test
    void updatingReplacesRatherThanAppending() {
        CommandAliasManager.addAlias("sunny", "= /weather clear");
        CommandAliasManager.updateAlias("sunny", "= /weather rain");
        assertEquals(1, TupenterConfig.INSTANCE.aliases.size());
        assertEquals("/weather rain", CommandAliasManager.getAliasMap().get("sunny").body());
    }

    @Test
    void removingReportsWhetherThereWasAnythingToRemove() {
        CommandAliasManager.addAlias("sunny", "= /weather clear");
        assertTrue(CommandAliasManager.removeAlias("sunny"));
        assertFalse(CommandAliasManager.removeAlias("sunny"), "already gone");
        assertTrue(CommandAliasManager.getAliasMap().isEmpty());
    }

    @Test
    void namesAreCaseInsensitiveAndTheSlashIsOptional() {
        CommandAliasManager.addAlias("/Sunny", "= /weather clear");
        assertTrue(CommandAliasManager.hasAlias("sunny"));
        assertTrue(CommandAliasManager.hasAlias("SUNNY"));
        assertTrue(CommandAliasManager.hasAlias("/sunny"));
        assertEquals("sunny", CommandAliasManager.normalizeName("  /SUNNY  "));
    }

    // ------------------------------------------------------ the silent skip

    /**
     * An entry that won't parse is skipped, not reported — so the config can
     * hold junk (hand edits, a format change) and the mod still starts, with
     * that one command simply absent. Everything ELSE must still load.
     */
    @Test
    void anUnparseableEntryIsSkippedAndTheRestStillLoad() {
        TupenterConfig.INSTANCE.aliases = new java.util.ArrayList<>(List.of(
                "good = /say hi",
                "nobodyhere",                    // a name with nothing after it
                "missingequals /say oops",       // the pre-'=' form, no longer valid
                "<broken = /say oops",           // unclosed declaration
                "alsogood <n:int> = /say $n$"));

        Map<String, AliasDefinition> loaded = CommandAliasManager.getAliasMap();
        assertEquals(java.util.Set.of("good", "alsogood"), loaded.keySet());
    }

    @Test
    void parseDefinitionReturnsNullForEveryMalformedShape() {
        assertNull(CommandAliasManager.parseDefinition(null));
        assertNull(CommandAliasManager.parseDefinition(""));
        assertNull(CommandAliasManager.parseDefinition("justaname"));
        assertNull(CommandAliasManager.parseDefinition("name   "), "name with a blank body");
        assertNull(CommandAliasManager.parseDefinition("name /say no equals"));
        assertNull(CommandAliasManager.parseDefinition("bad!name = /say hi"), "illegal character in the name");
        assertNotNull(CommandAliasManager.parseDefinition("name = /say yes"));
    }

    /** A multi-line body (from the editor) is folded to one line before storage. */
    @Test
    void aMultiLineDefinitionIsFoldedToOneLine() {
        CommandAliasManager.ParsedAlias parsed = CommandAliasManager.parseDefinition(
                "greet = /say hello\n   && /say again");
        assertNotNull(parsed);
        assertEquals("/say hello && /say again", parsed.definition().body());
    }

    // --------------------------------------------------------- name rules

    /**
     * Custom commands register into the SAME Brigadier dispatcher as the mod's
     * own, and a duplicate literal merges onto the existing node — so a custom
     * command named "echohud" wouldn't sit beside /echohud, it would replace
     * it. Every command the mod registers has to be refused here, which is why
     * the list is derived rather than retyped.
     */
    @Test
    void everyCommandTheModRegistersIsRefused() {
        for (String reserved : CommandAliasManager.MOD_COMMANDS) {
            String message = assertThrows(IllegalArgumentException.class,
                    () -> CommandAliasManager.addAlias(reserved, "= /say hi")).getMessage();
            assertTrue(message.contains(reserved), message);
            assertTrue(message.contains("own commands"), message);
        }
    }

    /**
     * A vanilla command name is ALLOWED — overriding is a real use — but it
     * doesn't happen silently: the create succeeds and a warning explains that
     * a client alias runs before the server sees the line, so it takes over.
     */
    @Test
    void namingACommandAfterAVanillaOneIsAllowedButWarns() {
        for (String vanilla : List.of("function", "tp", "give", "execute", "kill", "gamemode")) {
            CommandAliasManager.addAlias(vanilla, "= /say overridden");
            assertTrue(CommandAliasManager.hasAlias(vanilla), vanilla + " is created");
            String warning = CommandAliasManager.vanillaShadowWarning(vanilla);
            assertNotNull(warning, vanilla + " should warn");
            assertTrue(warning.contains("/" + vanilla), warning);
            assertTrue(warning.contains("vanilla"), warning);
            CommandAliasManager.removeAlias(vanilla);
        }
    }

    @Test
    void anOrdinaryNameDrawsNoVanillaWarning() {
        for (String ordinary : List.of("blink", "sunny", "homewarp", "sqrt")) {
            assertNull(CommandAliasManager.vanillaShadowWarning(ordinary), ordinary + " is not a vanilla command");
        }
    }

    @Test
    void theWarningIsCaseAndSlashInsensitiveLikeTheName() {
        assertNotNull(CommandAliasManager.vanillaShadowWarning("/TP"));
        assertNotNull(CommandAliasManager.vanillaShadowWarning("Give"));
    }

    /**
     * A hard-blocked name never ALSO warns — the two paths are exclusive, so a
     * name is either refused outright (Tupenter's own commands, reserved words)
     * or allowed, possibly with a vanilla heads-up. Nothing is both.
     */
    @Test
    void hardBlockedNamesDoNotAlsoWarn() {
        for (String blocked : CommandAliasManager.MOD_COMMANDS) {
            assertThrows(IllegalArgumentException.class, () -> CommandAliasManager.addAlias(blocked, "= /say hi"));
            assertNull(CommandAliasManager.vanillaShadowWarning(blocked),
                    blocked + " is hard-blocked; it must not appear on the warn path too");
        }
    }

    @Test
    void ambiguousSubcommandWordsAreRefusedToo() {
        for (String reserved : List.of("alias", "list", "verbose", "help", "add", "remove", "update")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CommandAliasManager.addAlias(reserved, "= /say hi"), reserved);
        }
    }

    @Test
    void nameCharactersAreLimitedToWhatACommandCanBe() {
        for (String legal : List.of("blink", "go_home", "tp-here", "a.b", "x2")) {
            CommandAliasManager.addAlias(legal, "= /say hi");
            assertTrue(CommandAliasManager.hasAlias(legal), legal);
        }
        for (String illegal : List.of("has space", "bang!", "hash#", "dollar$", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CommandAliasManager.addAlias(illegal, "= /say hi"), "should reject '" + illegal + "'");
        }
    }

    // ---------------------------------------------------------- round trip

    /** What [edit] puts in your chat bar has to be what the parser reads back. */
    @Test
    void theFormattedDefinitionParsesBackToTheSameThing() {
        for (String body : List.of(
                "= /weather clear",
                "<n:int> = #repeat $n$ (/say hi)",
                "<who:player> \"wave at someone\" = /me waves at $who$",
                "<p:pos=~ ~ ~> = /particle minecraft:flame $p$")) {
            CommandAliasManager.addAlias("round", body);
            String stored = CommandAliasManager.getRawCommand("round");
            assertNotNull(stored, body);

            CommandAliasManager.ParsedAlias reparsed =
                    CommandAliasManager.parseDefinition(CommandAliasManager.formatDefinition("round", stored));
            assertNotNull(reparsed, "formatDefinition output must parse: " + body);
            assertEquals(CommandAliasManager.getAliasMap().get("round").body(), reparsed.definition().body(), body);
            CommandAliasManager.removeAlias("round");
        }
    }

    @Test
    void theDefinitionListMatchesWhatIsStored() {
        CommandAliasManager.addAlias("a", "= /say a");
        CommandAliasManager.addAlias("b", "<n:int> = /say $n$");
        assertEquals(List.of("a = /say a", "b <n:int> = /say $n$"), CommandAliasManager.getAliasDefinitions());
    }
}
