package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directive third of the anti-drift contract (functions: BuiltinFunctionsTest,
 * param types: ParamTypeDocsTest). The parser's vocabulary is enumerable via
 * {@link ScriptParser#knownDirectiveWords()}; this holds the DirectiveDocs
 * registry equal to it — parser-word docs must all parse, client-side docs
 * (#stage/#unstage/#pid) must NOT be parser words, and both directions of
 * "exists but undocumented / documented but nonexistent" fail the build.
 */
class DirectiveDocsTest {

    @Test
    void registryMatchesTheParserVocabulary() {
        Set<String> documented = new TreeSet<>();
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            if (doc.parserWord()) {
                documented.add(doc.canonical());
            }
        }
        assertEquals(new TreeSet<>(ScriptParser.knownDirectiveWords()), documented,
                "DirectiveDocs (parserWord entries) and ScriptParser.knownDirectiveWords disagree — "
                        + "every parser directive needs a doc, every parser-word doc needs the parser to know it");
    }

    @Test
    void clientSideDirectivesAreNotParserWords() {
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            assertEquals(doc.parserWord(), ScriptParser.isDirectiveLine(doc.canonical() + " /say hi"),
                    doc.canonical() + ": parserWord flag disagrees with what isDirectiveLine actually does");
        }
    }

    @Test
    void everyDirectiveIsFullyDocumented() {
        Set<String> seen = new HashSet<>();
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            String name = doc.name();
            assertTrue(seen.add(name), "duplicate directive doc: " + name);
            assertFalse(name.startsWith("#"), name + ": names are stored without the #");
            assertTrue(doc.signature().startsWith(doc.canonical()) || doc.signature().startsWith("/"),
                    name + ": the signature should open with the directive (or the command that hosts it)");
            assertFalse(doc.blurb().isBlank(), name + " needs a blurb");
            assertFalse(doc.detail().isEmpty(), name + " needs detail lines");
            assertTrue(doc.exampleSimple().contains(doc.canonical()) || doc.exampleComposed().contains(doc.canonical()),
                    name + ": at least one example should actually use the directive");
            if (doc.shorthand() != null) {
                assertTrue(doc.shorthand().startsWith("#"), name + ": shorthand keeps its #");
            }
        }
    }

    @Test
    void findToleratesTheHashAndCase() {
        assertNotNull(DirectiveDocs.find("local"));
        assertNotNull(DirectiveDocs.find("#local"));
        assertNotNull(DirectiveDocs.find("WAIT"));
        assertNull(DirectiveDocs.find("elseif")); // a continuation of #if, not an entry
    }
}
