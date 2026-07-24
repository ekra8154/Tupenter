package net.tupenter.config;

import java.nio.file.Path;

/**
 * Test-only access to the config's package-private persistence seam.
 *
 * <p>Lives in {@code net.tupenter.config} so it can reach
 * {@code TupenterConfig.configDirectory} without that field being public —
 * production code still can't move the user's config, but any test can point
 * it at a temp directory and exercise save/load for real.
 */
public final class ConfigTestSupport {

    private ConfigTestSupport() {
    }

    /** Sends config reads and writes to {@code directory}; null restores the Fabric config dir. */
    public static void redirectConfigTo(Path directory) {
        TupenterConfig.configDirectory = directory;
    }

    /** A fresh config, isolated in {@code directory}. Returns the previous INSTANCE to restore. */
    public static TupenterConfig isolate(Path directory) {
        TupenterConfig previous = TupenterConfig.INSTANCE;
        redirectConfigTo(directory);
        TupenterConfig.INSTANCE = new TupenterConfig();
        return previous;
    }

    public static void restore(TupenterConfig previous) {
        TupenterConfig.INSTANCE = previous;
        redirectConfigTo(null);
    }
}
