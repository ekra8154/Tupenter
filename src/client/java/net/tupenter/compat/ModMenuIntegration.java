package net.tupenter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The Mod Menu entrypoint, and nothing else.
 *
 * <p>It stays this small deliberately. The JVM resolves a class's
 * superinterfaces when it loads the class, so ANY class implementing
 * {@link ModMenuApi} is unloadable on a client without Mod Menu installed — and
 * Mod Menu is only <em>suggested</em>. The screen therefore lives in
 * {@link TupenterConfigScreen}, which needs Cloth Config and nothing more, so
 * /tupenter menu and the Open Config keybind still work for someone who has
 * Cloth but not Mod Menu.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TupenterConfigScreen::createScreen;
    }
}
