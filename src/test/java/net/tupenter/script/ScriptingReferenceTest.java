package net.tupenter.script;

import net.tupenter.command.ClientVariableProvider;
import net.tupenter.command.LifecycleEventProvider;
import net.tupenter.command.PlayersVariableProvider;
import net.tupenter.command.WorldVariableProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SCRIPTING.md is generated, not hand-written, so it cannot fall behind the
 * implementation. This test regenerates it and fails when the checked-in file
 * differs — set {@code -Dtupenter.writeReference=true} (or just run this test
 * once after changing a doc registry) to rewrite it.
 *
 * <p>It also checks the document is actually COMPLETE: every built-in function,
 * every directive, every parameter type and every registered variable has to
 * appear, which is what makes the file safe to hand someone as the whole story.
 */
class ScriptingReferenceTest {

    private static final Path FILE = Path.of("SCRIPTING.md");

    /** The full variable vocabulary, exactly as the client assembles it. */
    static List<VarDoc> variableDocs() {
        List<VarDoc> docs = new ArrayList<>(new ClientVariableProvider().docs());
        docs.addAll(new WorldVariableProvider().docs());
        docs.addAll(new LifecycleEventProvider().docs());
        docs.addAll(new PlayersVariableProvider().docs());
        docs.addAll(new RealTimeVariableProvider().docs());
        return docs;
    }

    @Test
    void theCheckedInReferenceMatchesWhatTheRegistriesProduce() throws IOException {
        String generated = ScriptingReference.render(variableDocs());

        if (Boolean.getBoolean("tupenter.writeReference") || !Files.exists(FILE)) {
            Files.writeString(FILE, generated);
            return;
        }
        String onDisk = Files.readString(FILE).replace("\r\n", "\n");
        if (!onDisk.equals(generated.replace("\r\n", "\n"))) {
            Files.writeString(FILE, generated);
            fail("SCRIPTING.md was out of date with the doc registries — it has been "
                    + "regenerated, re-run the build and commit the result.");
        }
    }

    @Test
    void everyBuiltinFunctionIsDocumented() {
        String reference = ScriptingReference.render(variableDocs());
        TreeSet<String> missing = new TreeSet<>();
        for (String name : BuiltinFunctions.NAMES) {
            if (!reference.contains("`" + name + "(")) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "functions missing from the reference: " + missing);
    }

    @Test
    void everyDirectiveIsDocumented() {
        String reference = ScriptingReference.render(variableDocs());
        TreeSet<String> missing = new TreeSet<>();
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            if (!reference.contains("`" + doc.canonical() + "`")) {
                missing.add(doc.canonical());
            }
        }
        assertTrue(missing.isEmpty(), "directives missing from the reference: " + missing);
    }

    @Test
    void everyParameterTypeIsDocumented() {
        String reference = ScriptingReference.render(variableDocs());
        TreeSet<String> missing = new TreeSet<>();
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            if (!reference.contains("`" + doc.keyword() + "`")) {
                missing.add(doc.keyword());
            }
        }
        assertTrue(missing.isEmpty(), "parameter types missing from the reference: " + missing);
    }

    @Test
    void everyVariableIsDocumented() {
        List<VarDoc> variables = variableDocs();
        String reference = ScriptingReference.render(variables);
        TreeSet<String> missing = new TreeSet<>();
        for (VarDoc doc : variables) {
            // .x/.y/.z components ride on their vec's row by design
            if (doc.name().matches(".*\\.[xyz]$") && doc.name().chars().filter(c -> c == '.').count() > 1) {
                continue;
            }
            if (!reference.contains("`" + doc.name() + "`")) {
                missing.add(doc.name());
            }
        }
        assertTrue(missing.isEmpty(), "variables missing from the reference: " + missing);
    }

    /** No §-colour codes should survive into a Markdown file. */
    @Test
    void theReferenceCarriesNoLegacyColourCodes() {
        String reference = ScriptingReference.render(variableDocs());
        assertEquals(-1, reference.indexOf('§'),
                "a § colour code leaked into the reference at index " + reference.indexOf('§'));
    }
}
