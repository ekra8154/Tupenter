package net.tupenter.script;

import java.util.Optional;
import java.util.Set;

/**
 * A source of variable values (docs/SCRIPTING_DESIGN.md §5.3). The parser
 * knows only "this token is a variable reference"; which names exist and what
 * they resolve to lives behind this interface. Adding a variable is
 * registration data, never a parser change.
 */
public interface VariableProvider {

    VariableProvider EMPTY = new VariableProvider() {
        @Override
        public Set<String> names() {
            return Set.of();
        }

        @Override
        public Optional<Value> resolve(String name) {
            return Optional.empty();
        }
    };

    /** All names this provider can resolve — used for error suggestions. */
    Set<String> names();

    /** Case-insensitive lookup. Called lazily, at evaluation time. */
    Optional<Value> resolve(String name);

    /**
     * One-line documentation for a name this provider registered, or null when
     * it doesn't document that name (dynamic providers resolve paths they can't
     * enumerate — their docs live at the SUBJECT level, in SubjectDocs).
     */
    default String describe(String name) {
        return null;
    }
}
