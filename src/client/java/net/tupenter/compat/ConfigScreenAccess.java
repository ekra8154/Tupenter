package net.tupenter.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The one door to the config screen, for the keybind and /tupenter menu.
 *
 * <p>Cloth Config is a <em>suggested</em> dependency, not a required one, so
 * {@link TupenterConfigScreen} may be unloadable at runtime. This class holds no
 * Cloth types anywhere in its signatures or fields, and only names the screen
 * class inside a branch guarded by {@link #isAvailable()} — class references in
 * a method body resolve when that code RUNS, so a client without Cloth gets the
 * message instead of a NoClassDefFoundError. That crash was previously live: the
 * Open Config keybind called straight through with no check.
 */
public final class ConfigScreenAccess {

    /**
     * Tab indices, in the getOrCreateCategory order TupenterConfigScreen builds
     * them. They live here rather than on the screen so a command can name a tab
     * without pulling Cloth onto the classpath (these are compile-time
     * constants, so reading one loads nothing at all).
     *
     * <p>All four are listed even though TAB_SCRIPTING has no caller — the
     * sequence is the point, and an index of 2 means nothing on its own.
     */
    public static final int TAB_GENERAL = 0;
    public static final int TAB_SCRIPTING = 1;
    public static final int TAB_ALIASES = 2;
    public static final int TAB_SCRIPTS = 3;

    private ConfigScreenAccess() {
    }

    /** Is the settings screen openable on this client? */
    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("cloth-config");
    }

    /**
     * Open the settings on {@code tabIndex}, or return false if Cloth is missing.
     *
     * <p>Deferred to the next tick on purpose. A client command runs inside
     * ChatScreen's Enter handling, which calls setScreen(null) immediately
     * afterwards — opening synchronously would put up the screen and have chat
     * close it a moment later.
     */
    public static boolean open(int tabIndex) {
        if (!isAvailable()) {
            return false;
        }
        Minecraft.getInstance().execute(() -> TupenterConfigScreen.openAtTab(tabIndex));
        return true;
    }
}
