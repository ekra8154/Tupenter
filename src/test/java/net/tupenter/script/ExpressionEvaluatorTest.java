package net.tupenter.script;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private static String eval(String expression) {
        return ExpressionEvaluator.evaluate(expression, new EvalContext(new Random(42))).displayString();
    }

    /**
     * Namespaced NBT keys (components.minecraft:damage) are ONE address, so ':'
     * has to be part of a name — but it's also the ternary separator. The parser
     * resolves that by context, and both directions are pinned here: the dump
     * browser and tab-completion both hand out colon-bearing paths, and compact
     * ternaries over bare identifiers must keep working.
     */
    @Test
    void colonBelongsToNamespacedNamesButStillSeparatesTernaries() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.nbt.components.minecraft:damage", Value.ofNumber(42));
        store.set("a", Value.ofNumber(1));
        store.set("b", Value.ofNumber(2));
        EvalContext ctx = new EvalContext(new Random(1), store);

        // the whole namespaced path is one identifier
        assertEquals("42", ExpressionEvaluator.evaluate(
                "client.nbt.components.minecraft:damage", ctx).displayString());
        assertEquals("43", ExpressionEvaluator.evaluate(
                "client.nbt.components.minecraft:damage + 1", ctx).displayString());

        // ternaries: spaced AND compact, with bare identifier branches
        assertEquals("1", ExpressionEvaluator.evaluate("true ? a : b", ctx).displayString());
        assertEquals("2", ExpressionEvaluator.evaluate("false ? a : b", ctx).displayString());
        assertEquals("1", ExpressionEvaluator.evaluate("true ? a:b", ctx).displayString());
        assertEquals("2", ExpressionEvaluator.evaluate("false ? a:b", ctx).displayString());
        // nested, and a namespaced name is still reachable in the ELSE branch
        assertEquals("42", ExpressionEvaluator.evaluate(
                "false ? a : client.nbt.components.minecraft:damage", ctx).displayString());
        assertEquals("2", ExpressionEvaluator.evaluate("true ? (false ? a : b) : a", ctx).displayString());
    }

    /**
     * int and float are the language's ONLY coercions — substr results and NBT
     * string tags have no other way into math. Everything else stays strict, so
     * text keeps concatenating rather than silently adding.
     */
    @Test
    void intAndFloatConvertNumericText() {
        assertEquals("7", eval("float(\"3.5\") * 2"));
        assertEquals("43", eval("float(substr(\"level_42\", 6)) + 1"));
        assertEquals("42", eval("int(\"42\")"));
        assertEquals("3", eval("int(\"3.9\")"));      // still truncates
        assertEquals("-3", eval("int(\"-3.9\")"));    // toward zero, not floor
        assertEquals("5", eval("float(\"  5  \")"));  // surrounding space is fine

        // non-numeric text fails at the source, naming the offender
        ExpressionException ex = assertThrows(ExpressionException.class, () -> eval("float(\"abc\")"));
        assertTrue(ex.getMessage().contains("abc"), ex.getMessage());

        // and the coercion does NOT leak: + on text still concatenates
        assertEquals("31", eval("\"3\" + 1"));
    }

    @Test
    void powerOperator() {
        assertEquals("9", eval("3^2"));
        assertEquals("8", eval("2^3"));
        assertEquals("-4", eval("-2^2"));       // ^ binds tighter than unary minus: -(2^2)
        assertEquals("0.125", eval("2^-3"));    // negative exponent
        assertEquals("512", eval("2^3^2"));     // right-associative: 2^(3^2) = 2^9
        assertEquals("25", eval("(2+3)^2"));
        assertEquals("12", eval("2^2 * 3"));    // power before multiply
    }

    @Test
    void powerInsideSqrtLikeADistanceBody() {
        // the exact shape that broke: sqrt of a sum of squares
        assertEquals("5", eval("sqrt((0-3)^2 + (0-4)^2)"));
    }

    @Test
    void vecBuildsAPositionString() {
        assertEquals("0 64 0", eval("vec(0, 64, 0)"));
        assertEquals("1.5 -2 10", eval("vec(1.5, -2, 5 + 5)"));
    }

    @Test
    void vecNeedsThreeArgs() {
        assertThrows(ExpressionException.class, () -> eval("vec(0, 64)"));
    }

    @Test
    void aBuiltinStillWinsOverASameNamedVariable() {
        // the flip side of the x(2) fix: a BUILTIN name followed by '(' is always
        // a call, even if a variable shadows it — otherwise binding a variable
        // called "min" would silently disable min(a, b).
        SessionVariableStore store = new SessionVariableStore();
        store.set("min", Value.ofNumber(99));
        EvalContext ctx = new EvalContext(new Random(1), store);
        assertEquals("1", ExpressionEvaluator.evaluate("min(1, 2)", ctx).displayString());
        assertEquals("99", ExpressionEvaluator.evaluate("min", ctx).displayString()); // bare = the variable
    }

    @Test
    void componentIndexesAnyVec() {
        assertEquals("0", eval("component(vec(0, 64, 128), \"x\")"));
        assertEquals("64", eval("component(vec(0, 64, 128), \"y\")"));
        assertEquals("128", eval("component(vec(0, 64, 128), \"z\")"));
        // works on a bare vec string, comma- or space-separated
        assertEquals("64", eval("component(\"0 64 128\", \"y\")"));
        assertEquals("128", eval("component(\"0,64,128\", \"z\")"));
        // the component is a real number you can compute with
        assertEquals("130", eval("component(\"0 64 128\", \"z\") + 2"));
    }

    @Test
    void componentKeepsExactPrecision() {
        // not routed through a lossy double — 1/2 stays 1/2
        assertEquals("0.5", eval("component(vec(1/2, 0, 0), \"x\")"));
        assertEquals("2.5", eval("component(\"2.5 0 0\", \"x\")"));
    }

    @Test
    void componentOnANonVecErrors() {
        assertThrows(ExpressionException.class, () -> eval("component(\"miss\", \"x\")")); // a missed raycast
        assertThrows(ExpressionException.class, () -> eval("component(\"0 64\", \"y\")")); // only two components
        assertThrows(ExpressionException.class, () -> eval("component(42, \"z\")"));       // a plain number
        assertThrows(ExpressionException.class, () -> eval("component(vec(1,2,3), \"w\")")); // no such axis
        assertThrows(ExpressionException.class, () -> eval("component(vec(1,2,3))"));       // needs an axis
    }

    @Test
    void aVariableNamedXStillMultipliesByAParenthesizedGroup() {
        // regression: builtins named x/y/z used to win over a bound variable the
        // moment an identifier was followed by '(' — so "x (2)" called the builtin
        // instead of multiplying. $x$/$y$/$z$ are THE loop names in the randomfill
        // and circle recipes, so this has to keep working.
        SessionVariableStore store = new SessionVariableStore();
        store.set("x", Value.ofNumber(5));
        store.set("y", Value.ofNumber(3));
        EvalContext ctx = new EvalContext(new Random(1), store);
        assertEquals("10", ExpressionEvaluator.evaluate("x (2)", ctx).displayString());
        assertEquals("10", ExpressionEvaluator.evaluate("x(2)", ctx).displayString());
        assertEquals("40", ExpressionEvaluator.evaluate("x (3 + 5)", ctx).displayString());
        assertEquals("15", ExpressionEvaluator.evaluate("x(y)", ctx).displayString());
        assertEquals("5", ExpressionEvaluator.evaluate("x", ctx).displayString());
    }

    // A stub EntityAccess: "target" has 18 Health; two fake mobs sit at radius 3 and 7.
    private static EvalContext entityCtx() {
        EntityAccess access = new EntityAccess() {
            // one field vocabulary for every subject — the whole point of entity()
            @Override
            public Value entityField(String selector, String field) {
                if (!selector.equals("target") && !selector.equals("uuid-hit")) {
                    throw new ExpressionException("no entity: " + selector);
                }
                return switch (field) {
                    case "type" -> Value.of("minecraft:zombie");
                    case "uuid" -> Value.of("uuid-hit");
                    case "name" -> Value.of("Zombie");
                    case "health" -> Value.ofNumber(18);
                    case "pos" -> Value.of("1.5 64 -2.5");
                    case "blockpos" -> Value.of("1 64 -3");
                    case "nbt.Health" -> Value.ofNumber(18);
                    default -> throw new ExpressionException("no such field: " + field);
                };
            }

            @Override
            public String raycastUuid(double maxDist) {
                return maxDist >= 5 ? "uuid-hit" : "miss";
            }

            @Override
            public java.util.List<String> nearbyUuids(double radius, String type) {
                java.util.List<String> out = new java.util.ArrayList<>();
                if (radius >= 3 && (type == null || type.equals("minecraft:zombie"))) {
                    out.add("uuid-near");
                }
                if (radius >= 7 && type == null) {
                    out.add("uuid-far");
                }
                return out;
            }

            @Override
            public String nearestUuid(double radius, String type) {
                return radius >= 3 ? "uuid-near" : "miss";
            }

            // a stub loadout: an elytra at 32/432 in the chest, 12 dirt top-right
            @Override
            public Value slotField(String slot, String field) {
                return switch (field) {
                    case "id" -> Value.of(switch (slot) {
                        case "armor.chest" -> "minecraft:elytra";
                        case "inventory.8" -> "minecraft:dirt";
                        default -> "empty";
                    });
                    case "count" -> Value.ofNumber(
                            slot.equals("inventory.8") ? 12 : slot.equals("armor.chest") ? 1 : 0);
                    case "durability" -> Value.ofNumber(slot.equals("armor.chest") ? 32 : 0);
                    case "max_durability" -> Value.ofNumber(slot.equals("armor.chest") ? 432 : 0);
                    default -> throw new ExpressionException("unknown slot field: " + field);
                };
            }

            // a mending+unbreaking chestplate; anything else is an absent path
            @Override
            public java.util.List<String> nbtKeys(String selector, String path) {
                return switch (path) {
                    case "nbt.equipment.chest.components.minecraft:enchantments" ->
                            java.util.List.of("minecraft:mending", "minecraft:unbreaking");
                    case "nbt.Inventory" -> java.util.List.of("0", "1", "2");
                    default -> java.util.List.of(); // absent or scalar — "nothing here"
                };
            }
        };
        return new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE,
                BlockReader.NONE, FunctionResolver.NONE, Raycaster.NONE, access);
    }

    @Test
    void keysBridgesNbtIntoTheListVocabulary() {
        EvalContext ctx = entityCtx();
        String enchants = "\"nbt.equipment.chest.components.minecraft:enchantments\"";

        // the membership test that started this: a compound is not a list, its KEYS are
        assertEquals("2", ExpressionEvaluator.evaluate(
                "len(keys(\"self\", " + enchants + "))", ctx).displayString());
        assertEquals("true", ExpressionEvaluator.evaluate(
                "contains(keys(\"self\", " + enchants + "), \"minecraft:mending\")", ctx).displayString());
        assertEquals("false", ExpressionEvaluator.evaluate(
                "contains(keys(\"self\", " + enchants + "), \"minecraft:fortune\")", ctx).displayString());

        // an NBT list yields indices, which is what makes it loopable at all
        assertEquals("3", ExpressionEvaluator.evaluate("len(keys(\"self\", \"nbt.Inventory\"))", ctx).displayString());
        assertEquals("0", ExpressionEvaluator.evaluate("nth(keys(\"self\", \"nbt.Inventory\"), 0)", ctx).displayString());

        // absent path is an EMPTY list, not an error — an unenchanted item answers "none"
        assertEquals("0", ExpressionEvaluator.evaluate("len(keys(\"self\", \"nbt.nope\"))", ctx).displayString());
        assertEquals("false", ExpressionEvaluator.evaluate(
                "contains(keys(\"self\", \"nbt.nope\"), \"anything\")", ctx).displayString());

        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("keys(\"self\")", ctx));
    }

    @Test
    void entityReadsAnyFieldOfAnySubject() {
        EvalContext ctx = entityCtx();
        // one field vocabulary — the same words client.<field> and
        // client.target.<field> use, so only the SUBJECT changes
        assertEquals("minecraft:zombie", ExpressionEvaluator.evaluate("entity(\"target\", \"type\")", ctx).displayString());
        assertEquals("18", ExpressionEvaluator.evaluate("entity(\"target\", \"health\")", ctx).displayString());
        assertEquals("1.5 64 -2.5", ExpressionEvaluator.evaluate("entity(\"target\", \"pos\")", ctx).displayString());
        // nbt.<path> is the escape hatch for everything not curated
        assertEquals("18", ExpressionEvaluator.evaluate("entity(\"target\", \"nbt.Health\")", ctx).displayString());
        // and the subject can be COMPUTED — the reason the function form exists
        assertEquals("minecraft:zombie",
                ExpressionEvaluator.evaluate("entity(raycast_entity(30), \"type\")", ctx).displayString());
    }

    @Test
    void entityFallbackCoversAnAbsentField() {
        EvalContext ctx = entityCtx();
        // NBT omits defaulted values, so "0 if it isn't there" must not fault a script
        assertEquals("0", ExpressionEvaluator.evaluate("entity(\"target\", \"nbt.Nope\", 0)", ctx).displayString());
        assertEquals("18", ExpressionEvaluator.evaluate("entity(\"target\", \"health\", 0)", ctx).displayString());
        // an unknown entity falls back too
        assertEquals("none", ExpressionEvaluator.evaluate("entity(\"nobody\", \"type\", \"none\")", ctx).displayString());
        // ...but without a fallback it still errors
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("entity(\"target\", \"nbt.Nope\")", ctx));
    }

    @Test
    void entityValidatesArity() {
        EvalContext ctx = entityCtx();
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("entity(\"target\")", ctx));
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("entity(\"a\", \"b\", \"c\", \"d\")", ctx));
    }

    @Test
    void slotFunctionTakesSlotAndField() {
        EvalContext ctx = entityCtx();
        // addressed by /item replace name — no compacted-list index games
        assertEquals("minecraft:dirt", ExpressionEvaluator.evaluate("slot(\"inventory.8\", \"id\")", ctx).displayString());
        assertEquals("12", ExpressionEvaluator.evaluate("slot(\"inventory.8\", \"count\")", ctx).displayString());
        // an empty slot is the "empty" sentinel with count 0, never an error
        assertEquals("empty", ExpressionEvaluator.evaluate("slot(\"inventory.0\", \"id\")", ctx).displayString());
        assertEquals("0", ExpressionEvaluator.evaluate("slot(\"inventory.0\", \"count\")", ctx).displayString());
        // durability is what's LEFT; a non-damageable item reports 0
        assertEquals("32", ExpressionEvaluator.evaluate("slot(\"armor.chest\", \"durability\")", ctx).displayString());
        assertEquals("0", ExpressionEvaluator.evaluate("slot(\"inventory.8\", \"durability\")", ctx).displayString());
        assertEquals("432", ExpressionEvaluator.evaluate("slot(\"armor.chest\", \"max_durability\")", ctx).displayString());
    }

    @Test
    void slotAddressCanBeComputed() {
        // the whole reason the function form exists: a slot built at run time,
        // which the dotted client.slot.<slot>.<field> variable can't express
        EvalContext ctx = entityCtx();
        assertEquals("minecraft:dirt",
                ExpressionEvaluator.evaluate("slot(\"inventory.\" + 8, \"id\")", ctx).displayString());
        assertEquals("12",
                ExpressionEvaluator.evaluate("slot(\"inventory.\" + (4 + 4), \"count\")", ctx).displayString());
    }

    @Test
    void slotValidatesArityAndField() {
        EvalContext ctx = entityCtx();
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("slot(\"armor.chest\")", ctx));
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("slot(\"armor.chest\", \"nope\")", ctx));
    }

    @Test
    void entityRaycastReturnsUuidOrMiss() {
        EvalContext ctx = entityCtx();
        assertEquals("uuid-hit", ExpressionEvaluator.evaluate("raycast_entity(30)", ctx).displayString());
        assertEquals("miss", ExpressionEvaluator.evaluate("raycast_entity(2)", ctx).displayString());
    }

    @Test
    void entitiesReturnsAList() {
        EvalContext ctx = entityCtx();
        assertEquals("2", ExpressionEvaluator.evaluate("len(entities(8))", ctx).displayString());
        assertEquals("1", ExpressionEvaluator.evaluate("len(entities(8, \"minecraft:zombie\"))", ctx).displayString());
        assertEquals("uuid-near", ExpressionEvaluator.evaluate("nth(entities(8), 0)", ctx).displayString());
        assertEquals("0", ExpressionEvaluator.evaluate("len(entities(1))", ctx).displayString()); // nothing in range
    }

    @Test
    void nearestEntityReturnsUuidOrMiss() {
        EvalContext ctx = entityCtx();
        assertEquals("uuid-near", ExpressionEvaluator.evaluate("nearest_entity(8)", ctx).displayString());
        assertEquals("miss", ExpressionEvaluator.evaluate("nearest_entity(1)", ctx).displayString());
    }

    @Test
    void entityFunctionsValidateArity() {
        assertThrows(ExpressionException.class, () -> eval("entity_nbt(\"target\")"));
        assertThrows(ExpressionException.class, () -> eval("entities()"));
        assertThrows(ExpressionException.class, () -> eval("nearest_entity(1, \"a\", \"b\")"));
    }

    @Test
    void entityFunctionsWithoutAWorldError() {
        // the default EntityAccess.NONE (no live world) throws, not returns a bogus value
        assertThrows(ExpressionException.class, () -> eval("entity_nbt(\"self\", \"Health\")"));
        assertThrows(ExpressionException.class, () -> eval("raycast_entity(10)"));
        assertThrows(ExpressionException.class, () -> eval("entities(8)"));
    }

    @Test
    void functionArgsAcceptAQuotedStringWithSpaces() {
        assertEquals("5", eval("len(\"0 0 0\")")); // one spaced-string arg
        FunctionResolver f = (name, args, ctx) -> name.equalsIgnoreCase("second") ? args.get(1) : null;
        EvalContext ctx = new EvalContext(new Random(1), VariableProvider.EMPTY, TagResolver.NONE, BlockReader.NONE, f);
        assertEquals("0 0 0", ExpressionEvaluator.evaluate("second(1, \"0 0 0\")", ctx).displayString());
    }

    @Test
    void functionCallWithADottedVariableThenQuotedString() {
        VariableProvider vars = new VariableProvider() {
            @Override
            public Set<String> names() {
                return Set.of("client.pos");
            }

            @Override
            public java.util.Optional<Value> resolve(String n) {
                return n.equalsIgnoreCase("client.pos") ? java.util.Optional.of(Value.of("1 2 3")) : java.util.Optional.empty();
            }
        };
        FunctionResolver f = (name, args, ctx) -> name.equalsIgnoreCase("dist") ? args.get(1) : null;
        EvalContext ctx = new EvalContext(new Random(1), vars, TagResolver.NONE, BlockReader.NONE, f);
        assertEquals("0 0 0", ExpressionEvaluator.evaluate("dist(client.pos, \"0 0 0\")", ctx).displayString());
        // through the /echo marker path too
        assertEquals("0 0 0", MathEvaluator.applyNumberMath("$dist(client.pos, \"0 0 0\")$", NumberMathMode.EXPLICIT_ONLY, ctx));
    }

    // --- numbers (inherited behavior) ---

    @Test
    void arithmeticAndPrecedence() {
        assertEquals("14", eval("2+3*4"));
        assertEquals("20", eval("(2+3)*4"));
        assertEquals("8", eval("2(3+1)"));
        assertEquals("192", eval("3s"));
        assertEquals("8192", eval("2ss"));
        assertEquals("3", eval("int(7/2)"));
        assertEquals("0.3", eval("0.1+0.2"));
    }

    // --- strings ---

    @Test
    void stringLiterals() {
        assertEquals("hello world", eval("\"hello world\""));
        assertEquals("say \"hi\"", eval("\"say \\\"hi\\\"\""));
    }

    @Test
    void concatenation() {
        assertEquals("a1", eval("\"a\" + 1"));
        assertEquals("1a", eval("1 + \"a\""));
        assertEquals("ab", eval("\"a\" + \"b\""));
        assertEquals("x64", eval("\"x\" + 1s"));
    }

    // --- booleans and comparisons ---

    @Test
    void comparisons() {
        assertEquals("true", eval("2 > 1"));
        assertEquals("false", eval("2 < 1"));
        assertEquals("true", eval("1 <= 1"));
        assertEquals("true", eval("3 == 1+2"));
        assertEquals("true", eval("1 != 2"));
        assertEquals("true", eval("\"a\" == \"a\""));
        assertEquals("true", eval("\"a\" != \"b\""));
    }

    @Test
    void booleanOperators() {
        assertEquals("true", eval("1 > 0 && 2 > 1"));
        assertEquals("true", eval("1 > 2 || 2 > 1"));
        assertEquals("false", eval("!(2 > 1)"));
        assertEquals("true", eval("true"));
        assertEquals("false", eval("false && true"));
    }

    @Test
    void ternary() {
        assertEquals("yes", eval("3 > 2 ? \"yes\" : \"no\""));
        assertEquals("0", eval("3 < 2 ? 10 : 0"));
        assertEquals("b", eval("1 > 2 ? \"a\" : 2 > 1 ? \"b\" : \"c\""));
    }

    @Test
    void typeErrors() {
        assertThrows(ExpressionException.class, () -> eval("\"a\" < \"b\""));
        assertThrows(ExpressionException.class, () -> eval("1 == \"1\""));
        assertThrows(ExpressionException.class, () -> eval("1 && 2"));
        assertThrows(ExpressionException.class, () -> eval("!5"));
        assertThrows(ExpressionException.class, () -> eval("\"a\" * 2"));
        assertThrows(ExpressionException.class, () -> eval("5 ? 1 : 2"));
    }

    @Test
    void booleansRefuseCommandSubstitution() {
        Value bool = ExpressionEvaluator.evaluate("1 > 0", new EvalContext(new Random()));
        assertThrows(ExpressionException.class, bool::substitutionString);
    }

    // --- rand ---

    @Test
    void randStaysInInclusiveRange() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 200; i++) {
            String result = ExpressionEvaluator.evaluate("rand(1, 5)", context).displayString();
            int value = Integer.parseInt(result);
            assertTrue(value >= 1 && value <= 5, "rand out of range: " + value);
        }
    }

    @Test
    void randDegenerateAndInvalidRanges() {
        assertEquals("3", eval("rand(3, 3)"));
        assertThrows(ExpressionException.class, () -> eval("rand(5, 1)"));
        assertThrows(ExpressionException.class, () -> eval("rand(1.5, 3)"));
        assertThrows(ExpressionException.class, () -> eval("rand(1)"));
    }

    @Test
    void randComposesWithMath() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 50; i++) {
            int value = Integer.parseInt(ExpressionEvaluator.evaluate("10 + rand(1, 5)", context).displayString());
            assertTrue(value >= 11 && value <= 15);
        }
    }

    // --- % and nth ---

    @Test
    void moduloIsFloored() {
        assertEquals("1", eval("7 % 3"));
        assertEquals("0", eval("9 % 3"));
        // floored: the result follows the divisor's sign — cycling stays in range
        assertEquals("2", eval("-1 % 3"));
        assertEquals("0.5", eval("7.5 % 3.5"));
        assertThrows(ExpressionException.class, () -> eval("5 % 0"));
        // precedence: multiplicative, same as * and /
        assertEquals("1", eval("1 + 6 % 3"));
    }

    @Test
    void nthIndexesLists() {
        assertEquals("3", eval("nth(range(1, 5), 2)"));
        assertEquals("1", eval("nth(range(1, 5), 0)"));
        // the cycling idiom: nth(list, i % len(list))
        assertEquals("2", eval("nth(range(1, 3), 4 % len(range(1, 3)))"));
        assertThrows(ExpressionException.class, () -> eval("nth(range(1, 3), 3)"));
        assertThrows(ExpressionException.class, () -> eval("nth(range(1, 3), -1)"));
        assertThrows(ExpressionException.class, () -> eval("nth(5, 0)"));
    }

    // --- tag sets ---

    private static final TagResolver STUB_TAGS = new TagResolver() {
        @Override
        public java.util.List<String> resolve(TagKind kind, String tagId) {
            if (tagId == null) { // whole-registry enumeration
                return kind == TagResolver.TagKind.EFFECT
                        ? java.util.List.of("minecraft:speed", "minecraft:slowness", "minecraft:levitation")
                        : java.util.List.of("minecraft:everything");
            }
            if (kind == TagResolver.TagKind.BLOCK && tagId.equals("minecraft:logs")) {
                return java.util.List.of("minecraft:oak_log", "minecraft:birch_log");
            }
            // "ice" is BOTH a concrete block (see lookup) and a tag family
            if (kind == TagResolver.TagKind.BLOCK && tagId.equals("minecraft:ice")) {
                return java.util.List.of("minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice");
            }
            if (kind == TagResolver.TagKind.ITEM && tagId.equals("c:ores")) {
                return java.util.List.of("minecraft:iron_ore");
            }
            return java.util.List.of();
        }

        @Override
        public String lookup(TagKind kind, String id) {
            // the stub registry knows two concrete blocks: stone and ice
            if (kind != TagResolver.TagKind.BLOCK) {
                return null;
            }
            return switch (id) {
                case "minecraft:stone", "stone" -> "minecraft:stone";
                case "minecraft:ice", "ice" -> "minecraft:ice";
                default -> null;
            };
        }
    };

    private static EvalContext tagContext() {
        return new EvalContext(new Random(7), VariableProvider.EMPTY, STUB_TAGS);
    }

    @Test
    void tagSetsResolveToListsAndLeadingHashIsOptional() {
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"#minecraft:logs\"))", tagContext()).displayString());
        // single-member set: rand() is deterministic, and the # prefix is optional
        assertEquals("minecraft:iron_ore", ExpressionEvaluator.evaluate("rand(itemset(\"c:ores\"))", tagContext()).displayString());
    }

    @Test
    void tagLiteralsWorkWithoutQuotes() {
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(#minecraft:logs))", tagContext()).displayString());
        assertEquals("minecraft:iron_ore", ExpressionEvaluator.evaluate("rand(itemset(#c:ores))", tagContext()).displayString());
        // a bare # is an error, not an empty tag
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("blockset(#)", tagContext()));
    }

    @Test
    void concreteIdsMakeOneElementSets() {
        // "block OR blockset" filters: a plain id becomes a singleton list,
        // normalized to its canonical form
        assertEquals("1", ExpressionEvaluator.evaluate("len(blockset(\"stone\"))", tagContext()).displayString());
        assertEquals("minecraft:stone", ExpressionEvaluator.evaluate("rand(blockset(\"minecraft:stone\"))", tagContext()).displayString());
        // an explicit # is ALWAYS a tag — no silent fallback
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("blockset(#minecraft:stone)", tagContext()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("blockset(\"minecraft:nope\")", tagContext()));
    }

    @Test
    void bareIdWinsOverASameNamedTag() {
        // "ice" is both minecraft:ice (block) and #minecraft:ice (family) —
        // a bare "ice" must be the single block, not the four-block tag
        assertEquals("1", ExpressionEvaluator.evaluate("len(blockset(\"ice\"))", tagContext()).displayString());
        assertEquals("minecraft:ice", ExpressionEvaluator.evaluate("rand(blockset(\"ice\"))", tagContext()).displayString());
        // ...and an explicit #minecraft:ice still gets the whole family
        assertEquals("3", ExpressionEvaluator.evaluate("len(blockset(#minecraft:ice))", tagContext()).displayString());
    }

    @Test
    void multipleArgsBuildAnAdHocUnionSet() {
        // two concrete blocks → a two-member set
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"stone\", \"ice\"))", tagContext()).displayString());
        assertEquals("true", ExpressionEvaluator.evaluate(
                "contains(blockset(\"stone\", \"ice\"), \"minecraft:ice\")", tagContext()).displayString());
        // a concrete id unioned with a whole #tag
        assertEquals("3", ExpressionEvaluator.evaluate(
                "len(blockset(\"stone\", \"#minecraft:logs\"))", tagContext()).displayString()); // stone + oak_log + birch_log
        // duplicates across members collapse (stone appears in both slots)
        assertEquals("1", ExpressionEvaluator.evaluate(
                "len(blockset(\"stone\", \"minecraft:stone\"))", tagContext()).displayString());
        // an unknown member in the list still errors clearly
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(\"stone\", \"minecraft:nope\")", tagContext()));
    }

    @Test
    void aSingleSpaceSeparatedStringIsAWholeSet() {
        // one scalar string carries several members — how a <set:blockset> param passes a set
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"stone ice\"))", tagContext()).displayString());
        assertEquals("3", ExpressionEvaluator.evaluate(
                "len(blockset(\"stone #minecraft:logs\"))", tagContext()).displayString()); // stone + 2 logs
        // extra whitespace is harmless; dedup still applies
        assertEquals("1", ExpressionEvaluator.evaluate("len(blockset(\"  stone   stone \"))", tagContext()).displayString());
        // commas separate too — the form a flattened list argument arrives in
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"stone,ice\"))", tagContext()).displayString());
        assertEquals("2", ExpressionEvaluator.evaluate("len(blockset(\"stone, ice\"))", tagContext()).displayString());
    }

    @Test
    void aListArgumentFlattensToOneCommaJoinedToken() {
        // the /sphere path: $blockset(...)$ as a command ARGUMENT flattens to one
        // token, which blockset() then reads back as the same set
        Value set = ExpressionEvaluator.evaluate("blockset(\"stone\", \"ice\")", tagContext());
        assertEquals("minecraft:stone,minecraft:ice", set.argumentString());
        assertTrue(!set.argumentString().contains(" "), "must stay ONE argument token");
        // and it round-trips
        assertEquals("2", ExpressionEvaluator.evaluate(
                "len(blockset(\"" + set.argumentString() + "\"))", tagContext()).displayString());
        // free command text still refuses a list (the helpful guardrail)
        assertThrows(ExpressionException.class, set::substitutionString);
    }

    @Test
    void memberSplitIgnoresSeparatorsInsideBlockStates() {
        // a multi-property state carries commas — they must NOT split the member apart.
        // The unknown-member error names the token, so it proves what the splitter produced:
        // split correctly it reports the whole "nope[a=1,b=2]", not a torn "nope[a=1".
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(\"nope[a=1,b=2]\")", tagContext()));
        assertTrue(ex.getMessage().contains("nope[a=1,b=2]"), ex.getMessage());
    }

    @Test
    void containsChecksMembership() {
        assertEquals("true", ExpressionEvaluator.evaluate(
                "contains(blockset(#minecraft:logs), \"minecraft:oak_log\")", tagContext()).displayString());
        assertEquals("false", ExpressionEvaluator.evaluate(
                "contains(blockset(#minecraft:logs), \"minecraft:stone\")", tagContext()).displayString());
        assertEquals("true", eval("contains(range(1, 5), 3)"));
        // a differently-typed element is not a match, not an error
        assertEquals("false", eval("contains(range(1, 5), \"three\")"));
        assertThrows(ExpressionException.class, () -> eval("contains(5, 1)"));
    }

    @Test
    void noArgSetsEnumerateTheWholeRegistry() {
        assertEquals("3", ExpressionEvaluator.evaluate("len(effectset())", tagContext()).displayString());
        assertEquals("minecraft:everything", ExpressionEvaluator.evaluate("rand(blockset())", tagContext()).displayString());
        Set<String> effects = Set.of("minecraft:speed", "minecraft:slowness", "minecraft:levitation");
        for (int i = 0; i < 20; i++) {
            assertTrue(effects.contains(
                    ExpressionEvaluator.evaluate("rand(effectset())", tagContext()).displayString()));
        }
        // still needs a live world
        assertThrows(ExpressionException.class, () -> eval("effectset()"));
    }

    @Test
    void randPicksFromLists() {
        EvalContext context = tagContext();
        Set<String> logs = Set.of("minecraft:oak_log", "minecraft:birch_log");
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            String result = ExpressionEvaluator.evaluate("rand(blockset(\"#minecraft:logs\"))", context).displayString();
            assertTrue(logs.contains(result), "unexpected member: " + result);
            seen.add(result);
        }
        assertEquals(logs, seen);
        // rand(list) works on any list, not just tag sets
        int fromRange = Integer.parseInt(ExpressionEvaluator.evaluate("rand(range(1, 5))", context).displayString());
        assertTrue(fromRange >= 1 && fromRange <= 5);
    }

    @Test
    void tagSetErrors() {
        // unknown tag
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(\"#minecraft:nope\")", tagContext()));
        // no resolver available (not in-game)
        assertThrows(ExpressionException.class, () -> eval("blockset(\"#minecraft:logs\")"));
        // not a string
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("blockset(5)", tagContext()));
        // rand of a non-list single arg still errors
        assertThrows(ExpressionException.class, () -> eval("rand(1)"));
    }

    // --- block(x, y, z) ---

    private static final BlockReader STUB_BLOCKS = (x, y, z) ->
            y >= 64 ? "minecraft:air" : (x == 1 && y == 2 && z == 3 ? "minecraft:oak_log" : "minecraft:stone");

    private static EvalContext blockContext() {
        return new EvalContext(new Random(7), VariableProvider.EMPTY, TagResolver.NONE, STUB_BLOCKS);
    }

    @Test
    void blockReadsThePositionInBothForms() {
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(1, 2, 3)", blockContext()).displayString());
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(\"1 2 3\")", blockContext()).displayString());
        // decimal coordinates floor to block positions
        assertEquals("minecraft:oak_log", ExpressionEvaluator.evaluate("block(1.9, 2.5, 3.2)", blockContext()).displayString());
        // composes into conditions
        assertEquals("high", ExpressionEvaluator.evaluate(
                "block(0, 70, 0) == \"minecraft:air\" ? \"high\" : \"low\"", blockContext()).displayString());
    }

    @Test
    void blockErrors() {
        // no world / unloaded position
        assertThrows(ExpressionException.class, () -> eval("block(1, 2, 3)"));
        // wrong shapes
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(1, 2)", blockContext()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(\"1 2\")", blockContext()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.evaluate("block(\"a b c\")", blockContext()));
    }

    // --- pick ---

    @Test
    void pickChoosesAnOption() {
        EvalContext context = new EvalContext(new Random(7));
        Set<String> options = Set.of("stick", "iron_ingot", "diamond");
        for (int i = 0; i < 50; i++) {
            String result = ExpressionEvaluator.evaluate("pick(\"stick\" | \"iron_ingot\" | \"diamond\")", context).displayString();
            assertTrue(options.contains(result), "unexpected pick: " + result);
        }
    }

    @Test
    void pickOptionsAreExpressions() {
        assertEquals("4", eval("pick(2+2 | 2*2)"));
        // nested pick evaluates instead of returning its own source text
        assertEquals("7", eval("pick(pick(7 | 7) | 7)"));
        // literal text (spaces, commas, pipes) goes in quotes
        String quoted = eval("pick(\"say hi there\" | \"say hi there\")");
        assertEquals("say hi there", quoted);
        assertEquals("a | b", eval("pick(\"a | b\" | \"a | b\")"));
    }

    @Test
    void pickSeparatorDoesNotEatBooleanOr() {
        // || binds inside an option; a single | separates options
        EvalContext context = new EvalContext(new Random(3));
        Set<String> results = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            results.add(ExpressionEvaluator.evaluate("pick(true || false | 9)", context).displayString());
        }
        assertEquals(Set.of("true", "9"), results);
    }

    @Test
    void pickRequiresAnOption() {
        assertThrows(ExpressionException.class, () -> eval("pick()"));
        assertThrows(ExpressionException.class, () -> eval("pick(1 | 2"));
    }

    // --- $...$ wraps full expressions ---

    @Test
    void dollarWrapsAnyExpression() {
        // in expression world, $...$ is explicit wrapping — one rule everywhere
        assertEquals("2", eval("$1 + 1$"));
        assertEquals("2", eval("$1+ 1$"));
        assertEquals("14", eval("2 * $3 + 4$"));
        SessionVariableStore store = new SessionVariableStore();
        store.set("i", Value.ofNumber(4));
        assertEquals("5", ExpressionEvaluator.evaluate("$i+ 1$", contextWith(store)).displayString());
        assertThrows(ExpressionException.class, () -> eval("$1 + 1"));
    }

    @Test
    void digitsOnlyDollarStaysAPositionalReference() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("1", Value.of("bound-param"));
        assertEquals("bound-param", ExpressionEvaluator.evaluate("$1$", contextWith(store)).displayString());
        // unbound digits-only still errors like an unknown variable
        assertThrows(ExpressionException.class, () -> eval("$1$"));
    }

    // --- variables ---

    private static EvalContext contextWith(SessionVariableStore store) {
        return new EvalContext(new Random(42), store);
    }

    @Test
    void variablesResolveBareAndWrapped() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("x", Value.ofNumber(5));
        assertEquals("6", ExpressionEvaluator.evaluate("x + 1", contextWith(store)).displayString());
        assertEquals("6", ExpressionEvaluator.evaluate("$x$ + 1", contextWith(store)).displayString());
    }

    @Test
    void dottedProviderNamesResolve() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.y", Value.ofNumber(70));
        assertEquals("true", ExpressionEvaluator.evaluate("client.y > 60", contextWith(store)).displayString());
    }

    @Test
    void stringVariablesSubstituteAndCompare() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("spawn", Value.of("100 64 -200"));
        assertEquals("100 64 -200", ExpressionEvaluator.evaluate("spawn", contextWith(store)).substitutionString());
        assertEquals("true", ExpressionEvaluator.evaluate("spawn == \"100 64 -200\"", contextWith(store)).displayString());
    }

    @Test
    void unknownVariableSuggestsNearestName() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("health", Value.ofNumber(20));
        ExpressionException ex = org.junit.jupiter.api.Assertions.assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("helth", contextWith(store)));
        assertTrue(ex.getMessage().contains("did you mean 'health'"), ex.getMessage());
    }

    // --- math functions ---

    @Test
    void trigTakesDegrees() {
        assertEquals("1", eval("sin(90)"));
        assertEquals("1", eval("cos(0)"));
        assertEquals("0", eval("sin(0)"));
        double sin30 = Double.parseDouble(eval("sin(30)"));
        assertTrue(sin30 > 0.499 && sin30 < 0.501, "sin(30) ≈ 0.5, got " + sin30);
        double halfCircle = Double.parseDouble(eval("-sin(client_yaw_stub + 0)".replace("client_yaw_stub + 0", "180")));
        assertTrue(Math.abs(halfCircle) < 1e-9, "-sin(180) ≈ 0");
    }

    @Test
    void trigComposesWithVariables() {
        SessionVariableStore store = new SessionVariableStore();
        store.set("client.yaw", Value.ofNumber(90));
        String result = ExpressionEvaluator.evaluate("-sin(client.yaw)", contextWith(store)).displayString();
        assertEquals("-1", result);
    }

    @Test
    void sqrtAndAbs() {
        assertEquals("3", eval("sqrt(9)"));
        assertEquals("5", eval("abs(-5)"));
        assertEquals("5", eval("abs(5)"));
        assertThrows(ExpressionException.class, () -> eval("sqrt(-1)"));
    }

    @Test
    void floorCeilRound() {
        assertEquals("2", eval("floor(2.9)"));
        assertEquals("-3", eval("floor(-2.1)"));
        assertEquals("3", eval("ceil(2.1)"));
        assertEquals("-2", eval("ceil(-2.9)"));
        assertEquals("3", eval("round(2.5)"));
        assertEquals("2", eval("round(2.4)"));
        assertEquals("-2", eval("round(-2.5)"));
        assertEquals("7", eval("floor(15/2)"));
    }

    @Test
    void minMaxAndLen() {
        assertEquals("1", eval("min(3, 1, 2)"));
        assertEquals("3", eval("max(3, 1, 2)"));
        assertEquals("2.5", eval("min(2.5, 3)"));
        assertEquals("3", eval("len(range(1, 3))"));
        assertEquals("5", eval("len(\"hello\")"));
        assertThrows(ExpressionException.class, () -> eval("min()"));
        assertThrows(ExpressionException.class, () -> eval("len(5)"));
    }

    @Test
    void randfStaysInRange() {
        EvalContext context = new EvalContext(new Random(7));
        for (int i = 0; i < 100; i++) {
            double value = Double.parseDouble(ExpressionEvaluator.evaluate("randf(1, 2)", context).displayString());
            assertTrue(value >= 1.0 && value <= 2.0, "randf out of range: " + value);
        }
        assertThrows(ExpressionException.class, () -> eval("randf(2, 1)"));
    }

    // --- errors ---

    @Test
    void unknownFunctionAndValueErrors() {
        assertThrows(ExpressionException.class, () -> eval("foo(1)"));
        assertThrows(ExpressionException.class, () -> eval("hello"));
        assertThrows(ExpressionException.class, () -> eval("1/0"));
        assertThrows(ExpressionException.class, () -> eval("\"unterminated"));
        assertThrows(ExpressionException.class, () -> eval("1 +"));
    }
}
