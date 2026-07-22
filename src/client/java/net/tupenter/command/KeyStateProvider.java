package net.tupenter.command;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live keyboard state as read-only booleans, so a script IS a keybind — no
 * custom GUI needed. Two forms under one namespace:
 *
 * <ul>
 *   <li>{@code client.key.<name>} — held right now</li>
 *   <li>{@code client.keypress.<name>} — true only on the tick it goes down (edge)</li>
 * </ul>
 *
 * <p>{@code <name>} resolves bind-first: a registered key mapping ({@code jump},
 * {@code sneak}, {@code attack}, {@code hotbar.1}, or any modded one) reads that
 * bind's CURRENT key — so it follows the player's rebinds and covers mods for
 * free. Otherwise it's a physical key ({@code g}, {@code space}, {@code f6}).
 * The two vocabularies are disjoint by construction: the four arrow keys are
 * spelled {@code up_arrow}/{@code down_arrow}/{@code left_arrow}/{@code right_arrow}
 * so they never shadow the strafe binds {@code key.left}/{@code key.right}.
 *
 * <p>Everything reports not-pressed while any screen is open, so typing in chat
 * never fires a bind. (Logical binds are cleared by the game on screen-open
 * anyway; the gate keeps physical keys consistent with them.)
 */
public final class KeyStateProvider implements VariableProvider {
    private static final String HELD = "client.key.";
    private static final String EDGE = "client.keypress.";

    /** Physical key name -> GLFW code. Arrows are *_arrow to dodge the strafe binds. */
    private static final Map<String, Integer> PHYSICAL = buildPhysical();

    /** Suffixes a keypress query has asked about + their down-state as of the previous tick. */
    private final Map<String, Boolean> lastDown = new ConcurrentHashMap<>();

    @Override
    public Set<String> names() {
        Set<String> all = new TreeSet<>();
        for (String suffix : allSuffixes()) {
            all.add(HELD + suffix);
            all.add(EDGE + suffix);
        }
        return all;
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(EDGE)) {
            String suffix = lower.substring(EDGE.length());
            validate(suffix);
            boolean now = isDown(suffix);
            boolean was = lastDown.getOrDefault(suffix, Boolean.FALSE);
            lastDown.putIfAbsent(suffix, now); // begin tracking from this reading
            return Optional.of(Value.of(now && !was));
        }
        if (lower.startsWith(HELD)) {
            String suffix = lower.substring(HELD.length());
            validate(suffix);
            return Optional.of(Value.of(isDown(suffix)));
        }
        return Optional.empty();
    }

    /**
     * Snapshot each watched key's down-state — call once per client tick, AFTER
     * scripts have run, so a script sees "down now vs. down last tick" and an
     * edge fires exactly once per physical press.
     */
    public void tick() {
        for (String suffix : lastDown.keySet()) {
            lastDown.put(suffix, isDown(suffix));
        }
    }

    /** Is this key/bind held right now? False while a screen is open (chat, menus). */
    private boolean isDown(String suffix) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return false; // don't let typing in chat trigger binds
        }
        KeyMapping bind = bindsByName().get(suffix);
        if (bind != null) {
            return bind.isDown();
        }
        Integer code = PHYSICAL.get(suffix);
        if (code != null && mc.getWindow() != null) {
            return InputConstants.isKeyDown(mc.getWindow(), code);
        }
        return false;
    }

    private void validate(String suffix) {
        if (!bindsByName().containsKey(suffix) && !PHYSICAL.containsKey(suffix)) {
            throw new ExpressionException("unknown key or bind '" + suffix
                    + "' — e.g. client.key.jump (a bind), client.key.g (a key), client.key.left_arrow. Tab-complete for the list.");
        }
    }

    /** Registered binds keyed by their short name: "key.jump" -> "jump", "key.hotbar.1" -> "hotbar.1". */
    private static Map<String, KeyMapping> bindsByName() {
        Map<String, KeyMapping> binds = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return binds;
        }
        for (KeyMapping mapping : mc.options.keyMappings) {
            String id = mapping.getName();
            String shortName = id.startsWith("key.") ? id.substring(4) : id;
            binds.put(shortName.toLowerCase(Locale.ROOT), mapping);
        }
        return binds;
    }

    private static Set<String> allSuffixes() {
        Set<String> names = new TreeSet<>(PHYSICAL.keySet());
        names.addAll(bindsByName().keySet());
        return names;
    }

    private static Map<String, Integer> buildPhysical() {
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (char c = 'a'; c <= 'z'; c++) {
            keys.put(String.valueOf(c), GLFW.GLFW_KEY_A + (c - 'a'));
        }
        for (char d = '0'; d <= '9'; d++) {
            keys.put(String.valueOf(d), GLFW.GLFW_KEY_0 + (d - '0'));
        }
        for (int f = 1; f <= 12; f++) {
            keys.put("f" + f, GLFW.GLFW_KEY_F1 + (f - 1));
        }
        keys.put("space", GLFW.GLFW_KEY_SPACE);
        keys.put("enter", GLFW.GLFW_KEY_ENTER);
        keys.put("tab", GLFW.GLFW_KEY_TAB);
        keys.put("backspace", GLFW.GLFW_KEY_BACKSPACE);
        keys.put("escape", GLFW.GLFW_KEY_ESCAPE);
        keys.put("delete", GLFW.GLFW_KEY_DELETE);
        keys.put("insert", GLFW.GLFW_KEY_INSERT);
        keys.put("home", GLFW.GLFW_KEY_HOME);
        keys.put("end", GLFW.GLFW_KEY_END);
        keys.put("page_up", GLFW.GLFW_KEY_PAGE_UP);
        keys.put("page_down", GLFW.GLFW_KEY_PAGE_DOWN);
        keys.put("caps_lock", GLFW.GLFW_KEY_CAPS_LOCK);
        keys.put("left_shift", GLFW.GLFW_KEY_LEFT_SHIFT);
        keys.put("right_shift", GLFW.GLFW_KEY_RIGHT_SHIFT);
        keys.put("left_control", GLFW.GLFW_KEY_LEFT_CONTROL);
        keys.put("right_control", GLFW.GLFW_KEY_RIGHT_CONTROL);
        keys.put("left_alt", GLFW.GLFW_KEY_LEFT_ALT);
        keys.put("right_alt", GLFW.GLFW_KEY_RIGHT_ALT);
        // the four arrows: *_arrow keeps them distinct from the strafe binds
        keys.put("up_arrow", GLFW.GLFW_KEY_UP);
        keys.put("down_arrow", GLFW.GLFW_KEY_DOWN);
        keys.put("left_arrow", GLFW.GLFW_KEY_LEFT);
        keys.put("right_arrow", GLFW.GLFW_KEY_RIGHT);
        // a few common punctuation keys
        keys.put("minus", GLFW.GLFW_KEY_MINUS);
        keys.put("equal", GLFW.GLFW_KEY_EQUAL);
        keys.put("comma", GLFW.GLFW_KEY_COMMA);
        keys.put("period", GLFW.GLFW_KEY_PERIOD);
        keys.put("slash", GLFW.GLFW_KEY_SLASH);
        keys.put("semicolon", GLFW.GLFW_KEY_SEMICOLON);
        keys.put("grave", GLFW.GLFW_KEY_GRAVE_ACCENT);
        keys.put("left_bracket", GLFW.GLFW_KEY_LEFT_BRACKET);
        keys.put("right_bracket", GLFW.GLFW_KEY_RIGHT_BRACKET);
        return keys;
    }
}
