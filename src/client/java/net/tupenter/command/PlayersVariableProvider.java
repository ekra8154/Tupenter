package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The tab list as variables. players.list is a list value, so
 * {@code #foreach $p$ in $players.list$ (/msg $p$ hello)} works.
 */
public final class PlayersVariableProvider implements VariableProvider {
    private static final Set<String> NAMES = Set.of("players.count", "players.list");

    /** Small enough to document in place — the players subject page renders from this. */
    public List<net.tupenter.script.VarDoc> docs() {
        return List.of(
                new net.tupenter.script.VarDoc("players.count", "Players", "how many players are online"),
                new net.tupenter.script.VarDoc("players.list", "Players", "their names — a LIST for #foreach"));
    }

    @Override
    public String describe(String name) {
        for (net.tupenter.script.VarDoc doc : docs()) {
            if (doc.name().equalsIgnoreCase(name)) {
                return doc.blurb();
            }
        }
        return null;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public Optional<Value> resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!NAMES.contains(lower)) {
            return Optional.empty();
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            throw new ExpressionException(name + " is only available in-game");
        }

        List<String> playerNames = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            // GameProfile became a record in the authlib that ships with 1.21.9
            playerNames.add(info.getProfile().getName());
        }
        playerNames.sort(String::compareToIgnoreCase);

        if (lower.equals("players.count")) {
            return Optional.of(Value.ofNumber(playerNames.size()));
        }
        List<Value> values = playerNames.stream().<Value>map(Value::of).toList();
        return Optional.of(new Value.ListValue(values));
    }
}
