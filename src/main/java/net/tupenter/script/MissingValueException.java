package net.tupenter.script;

/**
 * A live value that is simply <em>absent right now</em> rather than wrong —
 * no block under the crosshair, not riding a vehicle, a held item with no
 * durability. It is a normal, transient world state, not a scripting mistake.
 *
 * <p>In a command or value position it still surfaces as a loud error (a
 * mis-aimed {@code /setblock $client.target.blockpos$} should tell you nothing was
 * there, not silently send garbage). But inside an {@code #if}/{@code #while}
 * <em>condition</em>, the parser catches this and reads the condition as
 * {@code false} — so {@code #if $client.target.type$ == "minecraft:zombie"}
 * quietly skips when you're aiming at nothing instead of killing the script.
 * That's the only difference from a plain {@link ExpressionException}.
 */
public class MissingValueException extends ExpressionException {
    public MissingValueException(String message) {
        super(message);
    }
}
