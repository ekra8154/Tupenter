package net.tupenter.command;

import net.tupenter.config.DefaultExamples;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan drives a warning shown next to a script, so the failure that matters
 * is a MISS — a script that floods a server while the row looks clean. These
 * lean on the direction of the error: over-flagging is tolerable, under-flagging
 * is not.
 */
class ServerTrafficScanTest {

    @Test
    void plainCommandsSend() {
        assertTrue(ServerTrafficScan.sendsToServer("/say hello"));
        assertTrue(ServerTrafficScan.sendsToServer("/setblock ~ ~ ~ stone"));
    }

    @Test
    void tupentersOwnCommandsStayLocal() {
        assertFalse(ServerTrafficScan.sendsToServer("/echo hi"));
        assertFalse(ServerTrafficScan.sendsToServer("/echohud hi"));
        assertFalse(ServerTrafficScan.sendsToServer("/calc 1 + 1"));
        assertFalse(ServerTrafficScan.sendsToServer("#set x = 5"));
    }

    /** The whole point: a command tucked inside a group must still be found. */
    @Test
    void commandsInsideGroupsAreFound() {
        assertTrue(ServerTrafficScan.sendsToServer("#if (client.health < 6) (/effect give @s regeneration)"));
        assertTrue(ServerTrafficScan.sendsToServer("#if (a) (/echo safe) #else (/give @s stick)"));
        assertTrue(ServerTrafficScan.sendsToServer("#repeat 5 (#silent (/give @s stick))"));
    }

    @Test
    void conditionsAreNotMistakenForCommands() {
        assertFalse(ServerTrafficScan.sendsToServer("#if (client.pos.y / 2 > 30) (/echo high)"));
        assertFalse(ServerTrafficScan.sendsToServer("#if (a && b) (/echohud ok)"));
    }

    /** A slash inside a quoted string is text, not a statement. */
    @Test
    void quotedTextIsNotACommand() {
        assertFalse(ServerTrafficScan.sendsToServer("/echo \"type /say to talk\""));
        assertFalse(ServerTrafficScan.sendsToServer("/echo \"escaped \\\" then /kill\""));
    }

    @Test
    void customCommandsCountAsSending() {
        // /blink is not one of Tupenter's own commands, and its body is /tp
        assertTrue(ServerTrafficScan.sendsToServer("#if (client.keypress.g) (/blink 20)"));
    }

    /**
     * Run it over what actually ships. Locks in which seeded scripts carry the
     * warning, so a later edit that quietly starts sending gets caught here.
     */
    @Test
    void theSeededSetIsClassifiedAsExpected() {
        java.util.Map<String, Boolean> expected = java.util.Map.of(
                "ex-tunnel", true,        // /setblock
                "ex-nightvision", true,   // /effect
                "ex-despawn", false,      // /echo only
                "ex-creeper", false,      // /echo only
                "ex-restock", false,      // /echo only
                "ex-elytra", false,       // /echohud only
                "ex-waxed", false);       // /echohud only

        for (var script : DefaultExamples.globalScripts()) {
            Boolean want = expected.get(script.id);
            assertTrue(want != null, "unclassified seeded script '" + script.id
                    + "' — add it here so a sending script can't ship unflagged");
            assertEquals(want, ServerTrafficScan.sendsToServer(script.text),
                    script.id + " classified wrongly");
        }
    }
}
