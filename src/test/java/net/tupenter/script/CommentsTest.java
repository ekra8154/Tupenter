package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "## a note" comments.
 *
 * <p>The whole design is two questions - where a comment may START, and where it
 * ENDS - so that is what these check, along with the thing both rules exist to
 * protect: "##" typed anywhere else is still ordinary text that gets sent.
 */
class CommentsTest {

    private static ScriptParser.Options options() {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, new LinkedHashMap<>(),
                true, true, true, true, 100, 1000, new Random(42), store, store);
    }

    private static ScriptParser.Options options(Map<String, AliasDefinition> aliases) {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, aliases,
                true, true, true, true, 100, 1000, new Random(42), store, store);
    }

    private static List<String> run(String line) {
        ScriptParser.ParseResult result = ScriptParser.parseGeneratedLine(line, line, options());
        assertNull(result.error(), line);
        assertNotNull(result.script(), line);
        return result.script().statements().stream().map(Script.SendStatement::content).toList();
    }

    // ------------------------------------------------------------- stripping

    @Test
    void aWholeLineCommentDisappears() {
        assertEquals("", Comments.strip("## just a note"));
        assertEquals("  ", Comments.strip("  ## just a note"));
    }

    @Test
    void theNewlineThatEndsACommentSurvives() {
        assertEquals("#set x = 1 &&\n", Comments.strip("#set x = 1 &&\n## why"));
        assertEquals("\n/say hi", Comments.strip("## header\n/say hi"));
    }

    @Test
    void aSpanCoversTheMarkAndTheRestOfTheLine() {
        String text = "/say hi && ## why";
        List<int[]> spans = Comments.spans(text);
        assertEquals(1, spans.size());
        assertEquals(text.indexOf("##"), spans.get(0)[0]);
        assertEquals(text.length(), spans.get(0)[1]);
    }

    // ------------------------------------------------------ where one STARTS

    @Test
    void atTheStartOfALine() {
        assertEquals(List.of("say hi"), run("## explain\n/say hi"));
        assertEquals(List.of("say hi"), run("   ## indented, still a line\n/say hi"));
    }

    @Test
    void afterAChain() {
        assertEquals(List.of("say one"), run("/say one && ## why"));
        assertEquals(List.of("say one", "say two"), run("/say one && ## why\n/say two"));
    }

    /**
     * The rule that keeps "##" usable: mid-statement it is content. Servers get
     * asked plenty of things with hashes in them, and none of them are notes.
     */
    @Test
    void nowhereElse() {
        assertEquals(List.of("say ## hi"), run("/say ## hi"));
        assertEquals(List.of("say a ## b"), run("/say a ## b"));
        assertEquals(List.of("say hi ## why"), run("/say hi ## why"),
                "no && before it, so this is text the server is meant to see");
    }

    @Test
    void aSpaceIsRequired() {
        assertEquals(List.of("say ##hashtag"), run("/say ##hashtag"));
        assertTrue(Comments.spans("## note").size() == 1, "a space opens a comment");
        assertTrue(Comments.spans("##1 winner").isEmpty(), "no space, no comment");
        assertTrue(Comments.spans("##").size() == 1, "an empty comment is still one");
    }

    @Test
    void quotesAndMarkersKeepTheirHashes() {
        assertTrue(Comments.spans("#set s = \"## not\"").isEmpty(), "inside a string");
        assertTrue(Comments.spans("#set s = $\"## not\"$").isEmpty(), "inside a marker");
    }

    // -------------------------------------------------------- where one ENDS

    @Test
    void atTheEndOfTheLineNotTheNextChain() {
        assertEquals(List.of("say one", "say two"),
                run("## a note with && in it\n/say one &&\n/say two"),
                "the && inside the comment never becomes a separator");
    }

    @Test
    void aTrailingCommentNeedsNoChainOfItsOwn() {
        assertEquals(List.of("say one", "say two"),
                run("/say one &&\n## why\n/say two"),
                "the && before the comment already separated the two statements");
    }

    @Test
    void aCommentAtTheVeryEndIsFine() {
        assertEquals(List.of("say one"), run("/say one &&\n/say two\n## done").subList(0, 1));
        assertEquals(List.of("say one", "say two"), run("/say one &&\n/say two\n## done"));
    }

    /**
     * The shape the multi-line editor actually produces: a group body written
     * over several lines with a note above one of them.
     */
    @Test
    void insideAGroupBody() {
        assertEquals(List.of("say hi", "say hi", "say hi"),
                run("#repeat 3 (\n  ## why three\n  /say hi\n)"));
    }

    // ---------------------------------------------------------- the run paths

    @Test
    void aCommentOnlyLineSaysSoRatherThanRunningNothing() {
        assertEquals(Comments.nothingToRunMessage(),
                ScriptParser.parse("## a note", options()).error());
        assertEquals(Comments.nothingToRunMessage(),
                ScriptParser.parseChatLine("## a note", options()).error());
        assertEquals(Comments.nothingToRunMessage(),
                ScriptParser.parseGeneratedLine("## a note", "## a note", options()).error());
    }

    /** Plain chat with hashes in it still goes out - that is what the space rule buys. */
    @Test
    void chatWithHashesStillSends() {
        for (String message : List.of("##1 winner", "that is ## good", "#hashtag")) {
            ScriptParser.ParseResult result = ScriptParser.parseChatLine(message, options());
            assertFalse(result.changed(), message);
            assertNull(result.error(), message);
        }
    }

    @Test
    void aCustomCommandBodyCanCarryComments() {
        Map<String, AliasDefinition> aliases = new LinkedHashMap<>();
        aliases.put("greet", AliasDefinition.parse("=\n## the friendly one\n/say hello &&\n/say again"));
        ScriptParser.ParseResult result = ScriptParser.parse("greet", options(aliases));
        assertNull(result.error());
        assertEquals(List.of("say hello", "say again"),
                result.script().statements().stream().map(Script.SendStatement::content).toList());
    }

    /** body() is what gets shown and saved; only the run path strips. */
    @Test
    void theStoredBodyKeepsItsComments() {
        AliasDefinition definition = AliasDefinition.parse("= ## note\n/say hello");
        assertTrue(definition.body().contains("## note"));
        assertFalse(definition.runnableBody().contains("## note"));
    }
}
