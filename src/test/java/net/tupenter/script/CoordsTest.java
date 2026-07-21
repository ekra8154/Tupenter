package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CoordsTest {

    @Test
    void splitsOnSpacesCommasOrAnyMix() {
        String[] expected = {"0", "64", "0"};
        assertArrayEquals(expected, Coords.split("0 64 0"));
        assertArrayEquals(expected, Coords.split("0,64,0"));
        assertArrayEquals(expected, Coords.split("0, 64, 0"));
        assertArrayEquals(expected, Coords.split("0,  64 , 0"));
        assertArrayEquals(expected, Coords.split("  0   64   0  "));
    }

    @Test
    void dropsLeadingAndDoubledSeparators() {
        assertArrayEquals(new String[]{"0", "64", "0"}, Coords.split(",0,,64,0"));
    }

    @Test
    void keepsNegativesAndDecimals() {
        assertArrayEquals(new String[]{"-5", "64.5", "-10"}, Coords.split("-5, 64.5, -10"));
    }

    @Test
    void emptyIsNoParts() {
        assertArrayEquals(new String[0], Coords.split("   "));
    }
}
