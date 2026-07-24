package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The help screens are the mod's only manual, and every line of them is
 * clickable — so a wrong example isn't a typo, it's a button that breaks when
 * pressed, and a link to a page that doesn't exist is a dead end.
 *
 * <p>The five doc registries already can't drift from the code (BuiltinFunctions,
 * DirectiveDocs, SubjectDocs, ParamTypeDocs, VarDoc). What they can't catch is
 * the prose: the hand-written pages in TupenterModClient, which name variables,
 * functions, directives and other pages in plain text. This test reads that
 * source as TEXT (the client source set needs Minecraft; its help STRINGS
 * don't) and holds every name it mentions to the same standard as the
 * registries — plus runs every example through the real parser.
 */
class HelpScreensTest {

    private static final Path CLIENT = Path.of("src", "client", "java", "net", "tupenter", "TupenterModClient.java");

    /**
     * Every line of text that reaches a user through a help page, from both
     * halves of the help system: the hand-written pages in the client source
     * and the prose the five registries carry. A name is held to the same
     * standard wherever it's written.
     */
    private static final List<String> HELP_TEXT = helpText();

    /** The pages /tupenter help &lt;name&gt; can open besides functions/directives/variables. */
    private static final List<String> MOD_COMMAND_PAGES = sourceListOf("MOD_COMMAND_PAGES");

    /** The literal topics registered under /tupenter help. */
    private static final Set<String> HELP_TOPIC_LITERALS =
            Set.of("expressions", "variables", "flow", "prefixes", "scripts", "commands", "functions");

    private static final Set<String> EXPRESSION_SUBTOPICS =
            Set.of("math", "text", "logic", "random", "lists", "world");

    private static final Set<String> CUSTOM_COMMAND_TOPICS = Set.of("types", "optionals", "descriptions");

    /** Every enumerated variable name, scanned from the providers that register them. */
    private static final Set<String> ENUMERATED_VARIABLES = enumeratedVariables();

    // ---------------------------------------------------------------- links

    /**
     * Every page the help links to exists. Help navigates by RUNNING commands,
     * so a stale "/tupenter help &lt;something&gt;" in prose is a click that
     * lands on an error.
     */
    @Test
    void everyHelpLinkOpensARealPage() {
        List<String> dead = new ArrayList<>();
        int checked = 0;
        for (String text : HELP_TEXT) {
            checked += checkLinks(text, "/tupenter help", HelpScreensTest::tupenterHelpTargetResolves, dead);
            checked += checkLinks(text, "/customcommand help", HelpScreensTest::customCommandHelpTargetResolves, dead);
        }
        if (!dead.isEmpty()) {
            fail("help links to pages that don't exist:\n  " + String.join("\n  ", dead));
        }
        assertScanned(checked, 45, "help links");
    }

    /** Finds every "<command> <args...>" occurrence and hands the args to {@code resolves}. */
    private static int checkLinks(String text, String command,
                                  java.util.function.Predicate<List<String>> resolves, List<String> dead) {
        int checked = 0;
        int at = text.indexOf(command);
        while (at >= 0) {
            List<String> args = argsAfter(text.substring(at + command.length()));
            if (!args.isEmpty()) {
                checked++;
                if (!resolves.test(args)) {
                    dead.add(command + " " + String.join(" ", args) + "   ← in: " + text);
                }
            }
            at = text.indexOf(command, at + command.length());
        }
        return checked;
    }

    /**
     * The lowercase words that follow a help command, up to two — the point
     * where prose takes over ("/tupenter help local is the one to read") is
     * indistinguishable from arguments, so the first word is the one that has
     * to resolve and the second is only checked when the first says it takes one.
     */
    private static List<String> argsAfter(String rest) {
        List<String> args = new ArrayList<>();
        Matcher word = Pattern.compile("\\G[ ]+([a-z][a-z0-9_.]*)").matcher(rest);
        while (args.size() < 2 && word.find()) {
            args.add(word.group(1).replaceAll("[.]+$", ""));
        }
        return args;
    }

    private static boolean tupenterHelpTargetResolves(List<String> args) {
        String topic = args.get(0);
        if (topic.equals("expressions")) {
            // a second word is only an argument if it IS a subtopic; otherwise it's prose
            return true;
        }
        return helpNameResolves(topic);
    }

    /** Mirrors runNameHelpCommand's resolution order. */
    private static boolean helpNameResolves(String name) {
        return HELP_TOPIC_LITERALS.contains(name)
                || BuiltinFunctions.find(name) != null
                || MOD_COMMAND_PAGES.contains(name)
                || DirectiveDocs.find(name) != null
                || SubjectDocs.find(name) != null
                || ENUMERATED_VARIABLES.contains(name)
                || SubjectDocs.findByPrefix(name) != null;
    }

    private static boolean customCommandHelpTargetResolves(List<String> args) {
        String topic = args.get(0);
        return CUSTOM_COMMAND_TOPICS.contains(topic) || ParamTypeDocs.find(topic) != null;
    }

    /**
     * These checks all work by scanning text, so an extraction that quietly
     * stops matching would turn them green while checking nothing. Every one
     * of them states how much it expects to find.
     */
    private static void assertScanned(int found, int atLeast, String what) {
        assertTrue(found >= atLeast,
                "only found " + found + " " + what + " to check (expected at least " + atLeast
                        + ") — the help text extraction has probably stopped matching");
    }

    /** Every subtopic advertised on the expressions hub is one runExpressionsHelp answers to. */
    @Test
    void expressionSubtopicsAllExist() {
        for (String text : HELP_TEXT) {
            Matcher link = Pattern.compile("/tupenter help expressions ([a-z]+)").matcher(text);
            while (link.find()) {
                assertTrue(EXPRESSION_SUBTOPICS.contains(link.group(1)),
                        "no expressions subtopic '" + link.group(1) + "' — in: " + text);
            }
        }
    }

    // ------------------------------------------------------------ the names

    /**
     * Every dotted variable the prose names is one you can actually read.
     * This is the check that would have caught $frozen$ (a session variable
     * that was never set) being documented as the freeze flag.
     */
    @Test
    void everyVariableNamedInHelpIsReadable() {
        Set<String> unknown = new TreeSet<>();
        int checked = 0;
        for (String text : HELP_TEXT) {
            for (String name : dottedNames(text)) {
                checked++;
                if (ENUMERATED_VARIABLES.contains(name) || SubjectDocs.find(name) != null) {
                    continue;
                }
                SubjectDocs.Subject subject = SubjectDocs.findByPrefix(name);
                if (subject != null && !subject.enumerated()) {
                    continue; // a dynamic subject computes its members at read time
                }
                unknown.add(name + "   ← in: " + text);
            }
        }
        if (!unknown.isEmpty()) {
            fail("help names variables that don't resolve:\n  " + String.join("\n  ", unknown));
        }
        assertScanned(checked, 120, "variable mentions");
    }

    /**
     * Dotted names under a documented subject root. Only subject-rooted names
     * are checked: those are the ones the mod promises to resolve, and anything
     * else with a dot in it is an item id, a file name or ordinary prose.
     */
    private static Set<String> dottedNames(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher name = Pattern.compile("\\b(client|world|players|real)((?:\\.[a-zA-Z0-9_]+)+)").matcher(text);
        while (name.find()) {
            String full = name.group(0);
            String base = full.substring(0, Math.max(0, full.length() - 2));
            if ((full.endsWith(".x") || full.endsWith(".y") || full.endsWith(".z")) && base.contains(".")) {
                full = base; // a vec's .x/.y/.z ride on its base — but client.y is its OWN claim
            }
            found.add(full);
        }
        return found;
    }

    /** Every #directive the prose names is one the parser knows. */
    @Test
    void everyDirectiveNamedInHelpExists() {
        Set<String> known = ScriptParser.knownDirectiveWords();
        Set<String> unknown = new TreeSet<>();
        int checked = 0;
        for (String text : HELP_TEXT) {
            Matcher directive = Pattern.compile("#([a-z_]+)(:?)").matcher(text);
            while (directive.find()) {
                String word = "#" + directive.group(1);
                if (!directive.group(2).isEmpty() || insideSetFunction(text, directive.start())) {
                    continue; // #minecraft:wool — and a bare #wool inside blockset() — are TAGS
                }
                checked++;
                if (!known.contains(word) && !DIRECTIVE_LOOKALIKES.contains(word)) {
                    unknown.add(word + "   ← in: " + text);
                }
            }
        }
        if (!unknown.isEmpty()) {
            fail("help names directives that don't exist:\n  " + String.join("\n  ", unknown));
        }
        assertScanned(checked, 200, "directive mentions");
    }

    /**
     * Whether {@code at} sits inside a set function's parentheses, where a
     * leading # means a registry tag: blockset(#minecraft:wool) and
     * blockset("stone", #wool) both name tags, not directives. Walks back to
     * the nearest unclosed '(' and reads the name in front of it.
     */
    private static boolean insideSetFunction(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    Matcher opener = Pattern.compile("([a-z]+)\\($").matcher(text.substring(0, i + 1));
                    return opener.find() && opener.group(1).endsWith("set");
                }
                depth--;
            }
        }
        return false;
    }

    /**
     * Words that look like directives on a help page and aren't in the parser's
     * statement vocabulary: the two #if continuations, the client-side
     * directives the parser never sees, and #directive itself — the placeholder
     * the prose pairs with /command.
     */
    private static final Set<String> DIRECTIVE_LOOKALIKES = Set.of(
            "#else", "#elseif",
            "#stage", "#unstage", "#pid",
            "#directive", "#directives",
            // a # in front of a placeholder means a registry TAG, not a directive
            "#tag", "#tags", "#block_tag", "#item_tag");

    /** Every built-in function the prose names is one the evaluator dispatches. */
    @Test
    void everyFunctionNamedInHelpExists() {
        Set<String> unknown = new TreeSet<>();
        int checked = 0;
        for (String text : HELP_TEXT) {
            Matcher call = Pattern.compile("\\b([a-z][a-z0-9_]*)\\(").matcher(text);
            while (call.find()) {
                String name = call.group(1);
                checked++;
                if (!BuiltinFunctions.NAMES.contains(name) && !NOT_A_FUNCTION.contains(name)) {
                    unknown.add(name + "()   ← in: " + text);
                }
            }
        }
        if (!unknown.isEmpty()) {
            fail("help names functions that don't exist:\n  " + String.join("\n  ", unknown));
        }
        assertScanned(checked, 270, "function mentions");
    }

    /**
     * Words the prose legitimately writes with a paren after them: the custom
     * functions the guides teach you to define, and English.
     */
    private static final Set<String> NOT_A_FUNCTION = Set.of(
            "dist", "rayhit", "lightlevel", // the custom functions the guides teach you to define
            "name",                         // the placeholder in "$name(...)$"
            "and", "or", "not", "above", "like", "s", "x", "y", "z");

    // --------------------------------------------------------- the examples

    /**
     * Every example in the registries runs. Help examples are click-to-run, so
     * one that doesn't parse is a broken promise — the float("3.5") page
     * advertised a conversion the function couldn't do for a whole release.
     */
    @Test
    void everyRegistryExampleParses() {
        List<String> broken = new ArrayList<>();
        for (BuiltinFunctions.Doc doc : BuiltinFunctions.ALL) {
            check(broken, doc.name(), doc.exampleSimple());
            check(broken, doc.name(), doc.exampleComposed());
        }
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            check(broken, doc.name(), doc.exampleSimple());
            check(broken, doc.name(), doc.exampleComposed());
        }
        for (SubjectDocs.Subject subject : SubjectDocs.ALL) {
            check(broken, subject.name(), subject.exampleSimple());
            check(broken, subject.name(), subject.exampleComposed());
        }
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            check(broken, doc.keyword(), doc.example());
        }
        if (!broken.isEmpty()) {
            fail("documented examples that don't parse:\n  " + String.join("\n  ", broken));
        }
    }

    /**
     * Every example written into the pages by hand runs too. These are the
     * ones with no registry behind them — the "Try:" lines on the hubs, the
     * worked definitions on the custom-command guides.
     */
    @Test
    void everyHandWrittenExampleParses() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String example : handWrittenExamples()) {
            checked++;
            check(broken, "help page", example);
        }
        assertTrue(checked >= 8, "expected the pages' worked examples to be found, got " + checked);
        if (!broken.isEmpty()) {
            fail("hand-written help examples that don't parse:\n  " + String.join("\n  ", broken));
        }
    }

    /**
     * The click-to-run examples: every page holds the line it offers in a local
     * {@code String} so the suggestLink target and the printed text can't
     * disagree, which makes those declarations the complete list. Only
     * command-shaped values count — the same methods declare labels and page
     * addresses, and those are covered by the link test instead.
     */
    private static List<String> handWrittenExamples() {
        List<String> examples = new ArrayList<>();
        Matcher declaration = Pattern.compile("String \\w+ =((?:[^;\"]|\"(?:\\\\.|[^\"\\\\])*\")*);")
                .matcher(source(CLIENT));
        while (declaration.find()) {
            String assigned = declaration.group(1);
            StringBuilder joined = new StringBuilder();
            Matcher piece = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(assigned);
            while (piece.find()) {
                joined.append(unescape(piece.group(1)));
            }
            // a value spliced from variables is an address the mod builds, not a
            // line a user is offered — those are the link test's business
            boolean allLiteral = piece.reset().replaceAll("").replace("+", "").isBlank();
            String value = joined.toString();
            if (allLiteral && (value.startsWith("/") || value.startsWith("#"))) {
                examples.add(value);
            }
        }
        return examples;
    }

    /** Parses one documented line the way running it would, recording the failure instead of throwing. */
    private static void check(List<String> broken, String where, String example) {
        try {
            String error = parseError(example);
            if (error != null) {
                broken.add(where + ": " + example + "  →  " + error);
            }
        } catch (RuntimeException thrown) {
            broken.add(where + ": " + example + "  →  " + thrown);
        }
    }

    /** The parse error for a documented line, or null when it's well-formed. */
    private static String parseError(String example) {
        String definition = definitionBody(example);
        if (definition != null) {
            try {
                AliasDefinition.parse(definition);
                return null;
            } catch (IllegalArgumentException rejected) {
                return rejected.getMessage();
            }
        }
        // lazily: structure is what a doc example promises — the VALUES need a world
        ScriptParser.Options options = lazyOptions();
        ScriptParser.ParseResult result = example.startsWith("/")
                ? ScriptParser.parse(example.substring(1), options)
                : ScriptParser.parseChatLine(example, options);
        return result.error();
    }

    /**
     * The signature-and-body of a "/customcommand add &lt;name&gt; ..." example —
     * what actually gets stored, minus the name, which is exactly what
     * AliasDefinition.parse reads. Null when the example isn't a definition.
     *
     * <p>This is the check that would have caught the shipped `circle` alias
     * silently vanishing when the = became required: parseDefinition swallows
     * failures, so a definition that doesn't parse just isn't there.
     */
    private static String definitionBody(String example) {
        for (String prefix : List.of("/customcommand add ", "/customcommand update ",
                "/customfunction add ", "/customfunction update ")) {
            if (!example.startsWith(prefix)) {
                continue;
            }
            String withName = example.substring(prefix.length()).trim();
            int afterName = withName.indexOf(' ');
            return afterName < 0 ? "" : withName.substring(afterName + 1);
        }
        return null;
    }

    private static ScriptParser.Options lazyOptions() {
        SessionVariableStore store = new SessionVariableStore();
        return new ScriptParser.Options(true, NumberMathMode.AUTO_DETECT, Map.of(), true, true, true, true,
                1000, 1000, new Random(7), store, store).withLazyExecution(true);
    }

    // ----------------------------------------------------------- the source

    private static String source(Path path) {
        try {
            assertTrue(Files.exists(path), "expected to run from the project root; can't find " + path.toAbsolutePath());
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new AssertionError("can't read " + path, unreadable);
        }
    }

    private static List<String> helpText() {
        List<String> text = new ArrayList<>(clientHelpText());
        for (BuiltinFunctions.Doc doc : BuiltinFunctions.ALL) {
            text.add(doc.signature());
            text.add(doc.blurb());
            text.addAll(doc.detail());
            text.add(doc.exampleSimple());
            text.add(doc.exampleComposed());
        }
        for (DirectiveDocs.Doc doc : DirectiveDocs.ALL) {
            text.add(doc.signature());
            text.add(doc.blurb());
            text.addAll(doc.detail());
            text.add(doc.exampleSimple());
            text.add(doc.exampleComposed());
        }
        for (SubjectDocs.Subject subject : SubjectDocs.ALL) {
            text.add(subject.blurb());
            if (subject.gate() != null) {
                text.add(subject.gate());
            }
            text.addAll(subject.detail());
            text.add(subject.exampleSimple());
            text.add(subject.exampleComposed());
        }
        for (ParamTypeDocs.Doc doc : ParamTypeDocs.ALL) {
            text.add(doc.blurb());
            text.addAll(doc.detail());
            text.add(doc.example());
        }
        return text;
    }

    /**
     * The hand-written pages' strings, pulled out of the client source. Only
     * lines that BUILD a help page count — that keeps internal command wiring,
     * config keys and log messages out of the prose checks.
     */
    private static List<String> clientHelpText() {
        List<String> text = new ArrayList<>();
        for (String line : source(CLIENT).split("\n")) {
            String trimmed = line.trim();
            boolean rendersHelp = trimmed.startsWith("\"")            // a String[] page entry
                    || trimmed.startsWith("+ \"")                     // its continuation
                    || trimmed.contains("helpLine(")
                    || trimmed.contains("navRow(")
                    || trimmed.contains("runLink(")
                    || trimmed.contains("suggestLink(")
                    || trimmed.matches("String \\w*[Ee]xample .*");
            if (!rendersHelp) {
                continue;
            }
            Matcher literal = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(line);
            while (literal.find()) {
                text.add(unescape(literal.group(1)));
            }
        }
        return text;
    }

    /** The elements of a {@code List.of(...)} constant in the client source. */
    private static List<String> sourceListOf(String constant) {
        Matcher declaration = Pattern.compile(Pattern.quote(constant) + " = [\\w.]*List\\.of\\(([^)]*)\\)")
                .matcher(source(CLIENT));
        assertTrue(declaration.find(), "can't find " + constant + " in " + CLIENT);
        List<String> values = new ArrayList<>();
        Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(declaration.group(1));
        while (literal.find()) {
            values.add(literal.group(1));
        }
        return values;
    }

    /**
     * The names of every enumerated variable, scanned from the providers that
     * register them. They live in the client source set (they read live
     * Minecraft state), so the doc checks reach them as text — a registration
     * line is the only place a name like "client.light_block" is written.
     */
    private static Set<String> enumeratedVariables() {
        Set<String> names = new TreeSet<>(new RealTimeVariableProvider().names());
        Pattern registered = Pattern.compile("\"((?:client|world|players)\\.[a-z0-9_.]+)\"");
        for (String provider : List.of("ClientVariableProvider", "WorldVariableProvider", "PlayersVariableProvider")) {
            Matcher name = registered.matcher(
                    source(Path.of("src", "client", "java", "net", "tupenter", "command", provider + ".java")));
            while (name.find()) {
                names.add(name.group(1).toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(names.size() > 50, "expected the full variable vocabulary, found " + names.size());
        return names;
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
