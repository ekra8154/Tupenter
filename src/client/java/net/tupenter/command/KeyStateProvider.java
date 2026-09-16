package net.tupenter.command;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

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

    /** Physical key name -> SDL scancode. Arrows are *_arrow to dodge the strafe binds. */
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
        if (mc.gui.screen() != null) {
            return false; // don't let typing in chat trigger binds
        }
        KeyMapping bind = bindsByName().get(suffix);
        if (bind != null) {
            return bind.isDown();
        }
        Integer code = PHYSICAL.get(suffix);
        if (code != null) {
            return InputConstants.isKeyDown(code);
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
        // SDL scancodes run a..z and F1..F12 contiguously, so those two can be
        // counted. Digits can't: SDL orders them 1..9 then 0 (KEY_1 = 30,
        // KEY_0 = 39), so KEY_0 + n would read every digit one key off.
        for (char c = 'a'; c <= 'z'; c++) {
            keys.put(String.valueOf(c), InputConstants.KEY_A + (c - 'a'));
        }
        keys.put("0", InputConstants.KEY_0);
        keys.put("1", InputConstants.KEY_1);
        keys.put("2", InputConstants.KEY_2);
        keys.put("3", InputConstants.KEY_3);
        keys.put("4", InputConstants.KEY_4);
        keys.put("5", InputConstants.KEY_5);
        keys.put("6", InputConstants.KEY_6);
        keys.put("7", InputConstants.KEY_7);
        keys.put("8", InputConstants.KEY_8);
        keys.put("9", InputConstants.KEY_9);
        for (int f = 1; f <= 12; f++) {
            keys.put("f" + f, InputConstants.KEY_F1 + (f - 1));
        }
        keys.put("space", InputConstants.KEY_SPACE);
        keys.put("enter", InputConstants.KEY_RETURN);
        keys.put("tab", InputConstants.KEY_TAB);
        keys.put("backspace", InputConstants.KEY_BACKSPACE);
        keys.put("escape", InputConstants.KEY_ESCAPE);
        keys.put("delete", InputConstants.KEY_DELETE);
        keys.put("insert", InputConstants.KEY_INSERT);
        keys.put("home", InputConstants.KEY_HOME);
        keys.put("end", InputConstants.KEY_END);
        keys.put("page_up", InputConstants.KEY_PAGEUP);
        keys.put("page_down", InputConstants.KEY_PAGEDOWN);
        keys.put("caps_lock", InputConstants.KEY_CAPSLOCK);
        keys.put("left_shift", InputConstants.KEY_LSHIFT);
        keys.put("right_shift", InputConstants.KEY_RSHIFT);
        keys.put("left_control", InputConstants.KEY_LCONTROL);
        keys.put("right_control", InputConstants.KEY_RCONTROL);
        keys.put("left_alt", InputConstants.KEY_LALT);
        keys.put("right_alt", InputConstants.KEY_RALT);
        // the four arrows: *_arrow keeps them distinct from the strafe binds
        keys.put("up_arrow", InputConstants.KEY_UP);
        keys.put("down_arrow", InputConstants.KEY_DOWN);
        keys.put("left_arrow", InputConstants.KEY_LEFT);
        keys.put("right_arrow", InputConstants.KEY_RIGHT);
        // a few common punctuation keys
        keys.put("minus", InputConstants.KEY_MINUS);
        keys.put("equal", InputConstants.KEY_EQUALS);
        keys.put("comma", InputConstants.KEY_COMMA);
        keys.put("period", InputConstants.KEY_PERIOD);
        keys.put("slash", InputConstants.KEY_SLASH);
        keys.put("semicolon", InputConstants.KEY_SEMICOLON);
        keys.put("grave", InputConstants.KEY_GRAVE);
        keys.put("left_bracket", InputConstants.KEY_LBRACKET);
        keys.put("right_bracket", InputConstants.KEY_RBRACKET);
        return keys;
    }
}
