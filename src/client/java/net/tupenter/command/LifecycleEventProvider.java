package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.tupenter.TupenterModClient;
import net.tupenter.script.Value;
import net.tupenter.script.VarDoc;
import net.tupenter.script.VariableProvider;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Game-lifecycle edges as one-tick booleans, in the same mould as
 * {@code client.keypress.*} — each reads true for exactly the tick its
 * transition happens, so a tick loop reacts to an "event" by testing a
 * variable rather than by any callback:
 *
 * <ul>
 *   <li>{@code client.just_died} — the tick the player's health crosses to 0
 *       (the LocalPlayer persists at 0 through the death screen, so the loop
 *       sees it before respawn resets position)</li>
 *   <li>{@code world.just_joined} — the first body pass after entering a
 *       world/server (fires again on every join, including re-joining the same
 *       world; not on a mere dimension change)</li>
 * </ul>
 *
 * <p>State is snapshotted once per client tick by {@link #tick()} — called
 * AFTER scripts have polled it, exactly like {@link KeyStateProvider} — and
 * cleared by {@link #onJoin()} so entering a world neither fires a stale death
 * edge nor misses the join edge. The join edge relies on the tick script's
 * first post-join body pass running in the same tick it is (re-)submitted,
 * before {@code tick()} advances the remembered world key.
 */
public final class LifecycleEventProvider implements VariableProvider {
    private static final String JUST_DIED = "client.just_died";
    private static final String JUST_JOINED = "world.just_joined";
    private static final String DIED_BLURB = "true for the one tick your health hits 0 — the death edge";
    private static final String JOINED_BLURB = "true the first pass after you enter a world/server — the join edge";

    private Boolean wasDead;      // null until first observed — no edge on the first look
    private String lastWorldKey;  // world key as of the previous tick; null right after a join

    @Override
    public Set<String> names() {
        return Set.of(JUST_DIED, JUST_JOINED);
    }

    @Override
    public String describe(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case JUST_DIED -> DIED_BLURB;
            case JUST_JOINED -> JOINED_BLURB;
            default -> null;
        };
    }

    /** Both edges as VarDocs, so they render on their subject's help page ("Events" row). */
    public List<VarDoc> docs() {
        return List.of(
                new VarDoc(JUST_DIED, "Events", DIED_BLURB),
                new VarDoc(JUST_JOINED, "Events", JOINED_BLURB));
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(JUST_DIED)) {
            return Optional.of(Value.of(deadNow() && Boolean.FALSE.equals(wasDead)));
        }
        if (lower.equals(JUST_JOINED)) {
            String key = TupenterModClient.currentWorldKey();
            return Optional.of(Value.of(key != null && !key.equals(lastWorldKey)));
        }
        return Optional.empty();
    }

    /** After scripts polled this tick, record the state they compared against. */
    public void tick() {
        wasDead = deadNow();
        String key = TupenterModClient.currentWorldKey();
        if (key != null) {
            lastWorldKey = key;
        }
    }

    /** Entering a world: forget the old edges so join fires and a stale death doesn't. */
    public void onJoin() {
        wasDead = null;
        lastWorldKey = null;
    }

    private static boolean deadNow() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getHealth() <= 0.0F;
    }
}
