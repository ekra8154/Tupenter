package net.tupenter.script;

import net.tupenter.script.AliasDefinition.ParamType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The param-type half of the anti-drift contract (see BuiltinFunctionsTest for
 * the function half): every ParamType constant is documented (ParamTypeDocs
 * static init throws otherwise — touching ALL here makes that a test failure),
 * every documented keyword and synonym round-trips through fromKeyword to the
 * type it documents, and fromKeyword's error speaks the registry's list.
 */
class ParamTypeDocsTest {

    @Test
    void everyTypeIsDocumentedCompletely() {
        assertEquals(ParamType.values().length, ParamTypeDocs.ALL.size(),
                "one doc per ParamType constant");
        Set<String> names = new HashSet<>();
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            assertTrue(names.add(doc.keyword()), "duplicate keyword: " + doc.keyword());
            for (String synonym : doc.synonyms()) {
                assertTrue(names.add(synonym), "duplicate synonym: " + synonym);
            }
            assertFalse(doc.blurb().isBlank(), doc.keyword() + " needs a blurb");
            assertFalse(doc.detail().isEmpty(), doc.keyword() + " needs detail lines");
            assertTrue(doc.example().startsWith("/customcommand add "),
                    doc.keyword() + ": the example should be a runnable /customcommand add line");
            assertTrue(doc.example().contains("<"),
                    doc.keyword() + ": the example should declare a parameter");
        }
    }

    @Test
    void everyKeywordRoundTripsThroughFromKeyword() {
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            if (doc.type() == ParamType.CHOICE) {
                continue; // choices have no keyword — you write the options themselves
            }
            assertEquals(doc.type(), ParamType.fromKeyword(doc.keyword()),
                    "keyword '" + doc.keyword() + "' should parse to " + doc.type());
            for (String synonym : doc.synonyms()) {
                assertEquals(doc.type(), ParamType.fromKeyword(synonym),
                        "synonym '" + synonym + "' should parse to " + doc.type());
            }
        }
    }

    @Test
    void findResolvesKeywordsAndSynonyms() {
        assertEquals(ParamType.POS, ParamTypeDocs.find("VEC3").type());
        assertEquals(ParamType.CHOICE, ParamTypeDocs.find("choice").type());
        assertNotNull(ParamTypeDocs.of(ParamType.BLOCKPOS));
    }

    @Test
    void unknownKeywordErrorSpeaksTheRegistry() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> ParamType.fromKeyword("integer")).getMessage();
        assertTrue(message.contains("int"), message);
        assertTrue(message.contains("blockset"), message);
        assertTrue(message.contains("/customcommand help types"), message);
    }
}
