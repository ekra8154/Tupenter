package net.tupenter.script;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The type you can hang off a variable: {@code #local c:blockpos = -10 20 85}.
 *
 * <p>The vocabulary is deliberately {@link AliasDefinition.ParamType} — the SAME
 * keywords a custom command's parameters use. One word means one thing whether
 * it is declaring a variable or a parameter, and /customcommand help types
 * documents both at once.
 *
 * <p>An annotation buys two things. It CHECKS the value at assignment, so a name
 * that says blockpos holds three whole numbers or the line stops. And it tells
 * the chat bar the value's SHAPE before the value exists — see
 * {@link #declaredOn(String)}. That second one is the point: while you are still
 * typing the line that defines it, a variable has no value to measure, so
 * {@code /tp $c$ } could not know it was about to become three coordinates, and
 * everything after it stopped completing.
 */
public final class VariableTypes {

    private VariableTypes() {
    }

    /** The type for a keyword; throws IllegalArgumentException naming the valid ones. */
    public static AliasDefinition.ParamType parse(String keyword) {
        return AliasDefinition.ParamType.fromKeyword(keyword);
    }

    /**
     * How many whitespace-separated tokens a value of this type occupies in a
     * command. This sizes the suggestion mask, so it only has to be right for the
     * types that are worth more than one token.
     */
    public static int arity(AliasDefinition.ParamType type) {
        return switch (type) {
            case POS, BLOCKPOS -> 3;
            case COLUMN_POS, ROTATION -> 2;
            default -> 1;
        };
    }

    /**
     * Why {@code value} is not a valid {@code type}, or null when it is fine.
     *
     * <p>Only the types with a checkable SHAPE are checked: coordinate arity,
     * whole/decimal numbers, true/false. An id-ish type (item, block, player,
     * dimension...) is left alone on purpose — validating those means holding the
     * registries, and a computed value like {@code "minecraft:" + name} is
     * perfectly good but unresolvable at assignment time. Checking nothing beats
     * rejecting something correct.
     */
    public static String validate(AliasDefinition.ParamType type, Value value, String name) {
        if (value instanceof Value.ListValue) {
            return switch (type) {
                case STRING, TEXT -> null; // free-form content; a list is legitimate there
                default -> "$" + name + "$ is declared " + keywordFor(type)
                        + ", but a list was assigned to it — a list fits #foreach, nth(...) or rand(...).";
            };
        }
        int wanted = arity(type);
        switch (type) {
            case POS, BLOCKPOS, COLUMN_POS, ROTATION -> {
                String[] parts = Coords.split(value.displayString());
                if (parts.length != wanted) {
                    return shapeError(name, type, "it needs " + wanted + " numbers, got " + parts.length
                            + " in \"" + value.displayString() + "\"") + collapsedCoordinateHint(type, parts.length);
                }
                boolean whole = type == AliasDefinition.ParamType.BLOCKPOS
                        || type == AliasDefinition.ParamType.COLUMN_POS;
                for (String part : parts) {
                    String bad = coordinateProblem(part, whole);
                    if (bad != null) {
                        return shapeError(name, type, bad);
                    }
                }
            }
            case ANGLE -> {
                String bad = coordinateProblem(value.displayString().trim(), false);
                if (bad != null) {
                    return shapeError(name, type, bad);
                }
            }
            case INT -> {
                if (!(value instanceof Value.NumberValue number)) {
                    return shapeError(name, type, "\"" + value.displayString() + "\" is not a number");
                }
                if (!number.value().isWhole()) {
                    return shapeError(name, type, number.value().toCommandString()
                            + " is not whole — round(...), floor(...) or int(...) makes it one");
                }
            }
            case FLOAT -> {
                if (!(value instanceof Value.NumberValue)) {
                    return shapeError(name, type, "\"" + value.displayString() + "\" is not a number");
                }
            }
            case BOOL -> {
                if (!(value instanceof Value.BoolValue)) {
                    return shapeError(name, type, "\"" + value.displayString()
                            + "\" is not true or false — a comparison like a > b produces one");
                }
            }
            default -> {
                // free-form: word/string/text/player/id/item/block/... — see the javadoc
            }
        }
        return null;
    }

    /** Null when {@code part} is a usable coordinate, else why not. */
    private static String coordinateProblem(String part, boolean whole) {
        if (part.isEmpty()) {
            return "one of the coordinates is empty";
        }
        // ~ and ^ are relative/local coordinates. Vanilla takes them bare ("~") or
        // with an offset ("~-3.5"), and a relative offset is allowed to be
        // fractional even where the absolute form has to be whole — ~0.5 is a
        // legal blockpos argument.
        char first = part.charAt(0);
        boolean relative = first == '~' || first == '^';
        String number = relative ? part.substring(1) : part;
        if (number.isEmpty()) {
            return null; // a bare ~ or ^
        }
        Rational parsed;
        try {
            parsed = Rational.parse(number);
        } catch (RuntimeException notANumber) {
            return "\"" + part + "\" is not a coordinate";
        }
        if (whole && !relative && !parsed.isWhole()) {
            return "\"" + part + "\" is not whole — blockpos and column_pos take whole numbers "
                    + "(blockpos(...) floors a position for you)";
        }
        return null;
    }

    /**
     * The extra sentence for the mistake this check exists to catch.
     *
     * <p>A right-hand side is an EXPRESSION, so {@code = -10 20 85} is not three
     * coordinates — it is implicit multiplication, and it quietly evaluates to
     * -17000. Untyped, that ships. The arity check is what turns it into an
     * error, so the error had better name the three ways to actually write a
     * position.
     */
    private static String collapsedCoordinateHint(AliasDefinition.ParamType type, int got) {
        if (got != 1) {
            return "";
        }
        String constructor = type == AliasDefinition.ParamType.BLOCKPOS ? "blockpos" : "vec";
        return " The right-hand side is an expression, so bare coordinates multiply together"
                + " — write " + constructor + "(x, y, z), or quote them: \"x y z\" (which is also"
                + " how ~ and ^ coordinates go in).";
    }

    private static String shapeError(String name, AliasDefinition.ParamType type, String detail) {
        return "$" + name + "$ is declared " + keywordFor(type) + ", but " + detail + ".";
    }

    /** The canonical keyword for a type — the one the error messages should say. */
    public static String keywordFor(AliasDefinition.ParamType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Every {@code name -> type} an assignment on this line declares, scanned
     * straight from the raw text.
     *
     * <p>A text scan rather than a parse result, because the caller is the CHAT
     * BAR: the line is half-typed and unparseable, and the whole point of the
     * annotation is to describe a value that has not been computed yet.
     * Deliberately forgiving — anything that does not look like a declaration is
     * skipped rather than reported, because everything here is someone
     * mid-keystroke.
     */
    public static Map<String, AliasDefinition.ParamType> declaredOn(String line) {
        Map<String, AliasDefinition.ParamType> declared = new LinkedHashMap<>();
        if (line == null || line.indexOf(':') < 0) {
            return declared; // no colon anywhere means no declaration, and no scan
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (String directive : new String[]{"#setdefault", "#local", "#set"}) {
            int at = 0;
            while ((at = lower.indexOf(directive, at)) >= 0) {
                int after = at + directive.length();
                // "#set" must not re-match the "#setdefault" already scanned
                if (after < lower.length() && isNameChar(lower.charAt(after))) {
                    at = after;
                    continue;
                }
                readDeclaration(line, after, declared);
                at = after;
            }
        }
        return declared;
    }

    /** Reads "[$]name[:type]" at {@code from}, recording it only when a type is present. */
    private static void readDeclaration(String line, int from, Map<String, AliasDefinition.ParamType> into) {
        int i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i < line.length() && line.charAt(i) == '$') {
            i++;
        }
        int nameStart = i;
        while (i < line.length() && isNameChar(line.charAt(i))) {
            i++;
        }
        if (i == nameStart) {
            return;
        }
        String name = line.substring(nameStart, i).toLowerCase(Locale.ROOT);
        if (i < line.length() && line.charAt(i) == '$') {
            i++; // the $name$:type spelling
        }
        if (i >= line.length() || line.charAt(i) != ':') {
            return; // an untyped assignment — nothing to record
        }
        i++;
        int typeStart = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
            i++;
        }
        if (i == typeStart) {
            return; // the ":" is typed, the keyword is not
        }
        try {
            into.put(name, parse(line.substring(typeStart, i)));
        } catch (RuntimeException halfTypedKeyword) {
            // the scan stays silent; the parser is the one that complains
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }
}
