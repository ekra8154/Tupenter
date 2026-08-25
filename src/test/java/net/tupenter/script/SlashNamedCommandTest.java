package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Commands whose NAME begins with a slash - WorldEdit's //replace, //set,
 * //undo and the rest of the region commands.
 *
 * <p>These are the one shape where the leading slash is not punctuation. What
 * reaches {@link ScriptParser#parse} is a command PACKET, so vanilla has already
 * eaten the slash that opened the line; a //replace arrives looking exactly like
 * an ordinary /replace, and stripping "its" slash a second time sent
 * "replace obsidian air" to a dispatcher that only knows "/replace".
 *
 * <p>It hid for a long time because WorldEdit registers its UTILITY commands
 * under both spellings — //replacenear works either way, so only the region
 * commands, which exist solely as //name, ever showed the fault. And unchained
 * lines pass through untouched, so it took an && to see at all.
 */
class SlashNamedCommandTest {

    /** What the client hands the parser: the packet, with vanilla's slash gone. */
    private static List<String> packet(String command) {
        SessionVariableStore store = new SessionVariableStore();
        Map<String, AliasDefinition> aliases = new LinkedHashMap<>();
        aliases.put("sunny", AliasDefinition.parse("= /time set day"));
        ScriptParser.Options options = new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliases,
                true, true, true, true, 100, 1000, new Random(42), store, store);
        ScriptParser.ParseResult result = ScriptParser.parse(command, options);
        assertNull(result.error(), command);
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    @Test
    void bothSlashesSurviveAChain() {
        assertEquals(List.of("/replace obsidian air", "/undo"),
                packet("/replace obsidian air && //undo"),
                "the first segment lost a slash that belonged to the command's name");
    }

    @Test
    void andSoDoesAnOrdinaryCommandsSingleSlash() {
        assertEquals(List.of("time set day", "weather clear"), packet("time set day && /weather clear"));
        assertEquals(List.of("time set day", "/undo"), packet("time set day && //undo"));
    }

    /** Mixed: vanilla first, WorldEdit second, and the other way round. */
    @Test
    void mixedChains() {
        assertEquals(List.of("setblock 1 2 3 stone", "/set air"),
                packet("setblock 1 2 3 stone && //set air"));
        assertEquals(List.of("/set air", "setblock 1 2 3 stone"),
                packet("/set air && /setblock 1 2 3 stone"));
    }

    /**
     * After a #prefix the slash vanilla ate was the PREFIX'S. Every slash still
     * standing was typed on purpose, so nothing gets put back.
     */
    @Test
    void aPrefixOwnsTheSlashVanillaAte() {
        assertEquals(List.of("/replace obsidian air", "/undo"),
                packet("#silent //replace obsidian air && //undo"));
        assertEquals(List.of("time set day"), packet("#silent time set day"));
        assertEquals(List.of("time set day"), packet("#silent /time set day"));
    }

    /**
     * "//sunny" is a command named /sunny, not the alias "sunny" — the slash
     * that would have made it one is the command's.
     */
    @Test
    void aDoubleSlashIsNeverAnAliasInvocation() {
        assertEquals(List.of("time set day", "/undo"), packet("sunny && //undo"),
                "the bare name still expands");
        assertEquals(List.of("/sunny", "/undo"), packet("/sunny && //undo"),
                "//sunny goes to the server as /sunny");
    }
}
