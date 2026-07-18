package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealTimeVariableProviderTest {

    private final RealTimeVariableProvider provider = new RealTimeVariableProvider();

    @Test
    void advertisesItsNames() {
        assertTrue(provider.names().containsAll(
                Set.of("real.hour", "real.minute", "real.day_of_week", "real.timestamp")));
    }

    @Test
    void hourIsInRange() {
        Value hour = provider.resolve("real.hour").orElseThrow();
        int value = Integer.parseInt(hour.displayString());
        assertTrue(value >= 0 && value <= 23);
    }

    @Test
    void dayOfWeekIsLowercaseName() {
        String day = provider.resolve("real.day_of_week").orElseThrow().displayString();
        assertTrue(Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").contains(day));
    }

    @Test
    void unknownNamesPassThrough() {
        assertEquals(java.util.Optional.empty(), provider.resolve("real.nope"));
    }
}
