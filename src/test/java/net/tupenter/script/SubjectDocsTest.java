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
 * The subject quarter of the anti-drift contract. Enumerated subjects get
 * their MEMBER docs enforced at registration (a compile error, not a test) —
 * this covers what tests can reach: every subject doc is complete, prefix
 * resolution is segment-aware (client.keypress must not fall into client.key),
 * and the one MC-free provider proves the describe() pattern end to end.
 */
class SubjectDocsTest {

    @Test
    void everySubjectIsFullyDocumented() {
        Set<String> seen = new HashSet<>();
        for (SubjectDocs.Subject subject : SubjectDocs.ALL) {
            assertTrue(seen.add(subject.name()), "duplicate subject: " + subject.name());
            assertFalse(subject.blurb().isBlank(), subject.name() + " needs a blurb");
            assertFalse(subject.detail().isEmpty(), subject.name() + " needs detail lines");
            assertFalse(subject.exampleSimple().isBlank(), subject.name() + " needs a simple example");
            assertFalse(subject.exampleComposed().isBlank(), subject.name() + " needs a composed example");
        }
    }

    @Test
    void enumeratedSubjectsAreExactlyTheProviderBackedGroups() {
        Set<String> enumerated = new TreeSet<>();
        for (SubjectDocs.Subject subject : SubjectDocs.ALL) {
            if (subject.enumerated()) {
                enumerated.add(subject.name());
            }
        }
        assertEquals(new TreeSet<>(Set.of("client", "world", "players", "real")), enumerated,
                "enumerated subjects must match the providers whose registrations carry VarDocs");
    }

    @Test
    void prefixResolutionIsSegmentAware() {
        assertEquals("client.vehicle", SubjectDocs.findByPrefix("client.vehicle.health").name());
        assertEquals("client.keypress", SubjectDocs.findByPrefix("client.keypress.g").name());
        assertEquals("client.key", SubjectDocs.findByPrefix("client.key.jump").name());
        assertEquals("client", SubjectDocs.findByPrefix("client.pos.x").name());
        assertEquals("client.slot", SubjectDocs.findByPrefix("client.slot.hotbar.0.id").name());
        assertNull(SubjectDocs.findByPrefix("nonsense.path"));
    }

    @Test
    void theMcFreeProviderDocumentsEveryName() {
        RealTimeVariableProvider provider = new RealTimeVariableProvider();
        for (String name : provider.names()) {
            assertNotNull(provider.describe(name), name + " is registered but undocumented");
        }
        assertEquals(provider.names().size(), provider.docs().size(),
                "docs() and names() should cover the same set");
        assertNull(provider.describe("real.nope"));
    }
}
