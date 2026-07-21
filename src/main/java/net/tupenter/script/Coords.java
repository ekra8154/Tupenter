package net.tupenter.script;

import java.util.Arrays;

/**
 * Splits a coordinate/vec string into its parts, lenient about the separator:
 * spaces, commas, or any mix of them. {@code "0 64 0"}, {@code "0,64,0"}, and
 * {@code "0,  64 , 0"} all yield {@code ["0", "64", "0"]}. Shared by
 * {@code block("x y z")} and by {@code /customfunction} vec3/pos parameters so a
 * vec literal reads the same everywhere.
 */
public final class Coords {
    private Coords() {
    }

    public static String[] split(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        // a leading separator would leave a "" head — drop empties
        return Arrays.stream(trimmed.split("[,\\s]+"))
                .filter(part -> !part.isEmpty())
                .toArray(String[]::new);
    }
}
