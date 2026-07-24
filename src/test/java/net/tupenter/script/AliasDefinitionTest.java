package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasDefinitionTest {

    @Test
    void signatureSeparatorSkipsDeclarationsAndMarkers() {
        // canonical: declarations in the signature — the = inside <...> is not the separator
        assertEquals("blink <distance:int=5> ".length(),
                AliasDefinition.signatureSeparator("blink <distance:int=5> = /attribute $distance$"));
        // legacy: declarations after the = — first top-level = wins
        assertEquals("blink ".length(),
                AliasDefinition.signatureSeparator("blink = <distance:int=5> /attribute $distance$"));
        // = and == inside a $...$ default expression stay invisible
        assertEquals("portal <dim:a,b=$x == 1 ? \"a\" : \"b\"$> ".length(),
                AliasDefinition.signatureSeparator("portal <dim:a,b=$x == 1 ? \"a\" : \"b\"$> = /say $dim$"));
        assertEquals(-1, AliasDefinition.signatureSeparator("no separator here"));
    }

    @Test
    void bareParamDefaultsToQuotableString() {
        AliasDefinition def = AliasDefinition.parse("<target> = /execute at $target$ run summon lightning_bolt");
        assertEquals(1, def.params().size());
        assertEquals("target", def.params().get(0).name());
        assertEquals(AliasDefinition.ParamType.STRING, def.params().get(0).type());
        assertEquals("/execute at $target$ run summon lightning_bolt", def.body());
    }

    @Test
    void quotedDescriptionBeforeEqualsIsExtracted() {
        AliasDefinition def = AliasDefinition.parse("<name:word> \"Wave at someone\" = /me waves at $name$");
        assertEquals(1, def.params().size());
        assertEquals("Wave at someone", def.description());
        assertEquals("/me waves at $name$", def.body());
    }

    @Test
    void explicitSeparatorWithoutDescription() {
        AliasDefinition def = AliasDefinition.parse("<n:int> = /say $n$");
        assertEquals("", def.description());
        assertEquals("/say $n$", def.body());
    }

    @Test
    void quoteAfterSeparatorStaysBody() {
        // the quote comes AFTER '=', so it's the body, not a description
        AliasDefinition def = AliasDefinition.parse("= \"Server up\"");
        assertEquals("", def.description());
        assertEquals("\"Server up\"", def.body());
    }

    @Test
    void equalsIsRequiredBeforeTheBody() {
        // no '=' is now an error — the separator is mandatory, matching the stored form
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AliasDefinition.parse("/weather clear && Have fun!"));
        assertTrue(ex.getMessage().contains("="));
    }

    @Test
    void quotedNoteWithoutEqualsIsAnError() {
        // a leading "quote" no longer silently becomes body text — without '=' it's an error
        assertThrows(IllegalArgumentException.class,
                () -> AliasDefinition.parse("<n:int> \"just a note\" /say $n$"));
    }

    @Test
    void equalsInsideBodyIsNotASeparator() {
        // the leading '=' is the separator; the a=b inside the body is left alone
        AliasDefinition def = AliasDefinition.parse("= /scoreboard players set @s obj a=b");
        assertEquals("", def.description());
        assertEquals("/scoreboard players set @s obj a=b", def.body());
    }

    @Test
    void stringIsNotGreedySoItCanBeFollowed() {
        AliasDefinition def = AliasDefinition.parse("<target> <count:int> = /say $target$ $count$");
        assertEquals(2, def.params().size());
        assertEquals(AliasDefinition.ParamType.STRING, def.params().get(0).type());
        assertEquals(AliasDefinition.ParamType.INT, def.params().get(1).type());
    }

    @Test
    void typedDeclarationsParse() {
        AliasDefinition def = AliasDefinition.parse("<a:int> <b:float> <c:word> <d:player> <e:selector> <f:text> = /say hi");
        assertEquals(AliasDefinition.ParamType.INT, def.params().get(0).type());
        assertEquals(AliasDefinition.ParamType.FLOAT, def.params().get(1).type());
        assertEquals(AliasDefinition.ParamType.WORD, def.params().get(2).type());
        assertEquals(AliasDefinition.ParamType.PLAYER, def.params().get(3).type());
        assertEquals(AliasDefinition.ParamType.SELECTOR, def.params().get(4).type());
        assertEquals(AliasDefinition.ParamType.TEXT, def.params().get(5).type());
    }

    @Test
    void textMustBeLast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AliasDefinition.parse("<msg:text> <n:int> /say $msg$"));
        assertTrue(ex.getMessage().contains("last"));
    }

    @Test
    void unknownTypeIsRejectedWithTheFullList() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AliasDefinition.parse("<t:banana> /say hi"));
        assertTrue(ex.getMessage().contains("selector"));
    }

    @Test
    void duplicateAndInvalidNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<a:int> <a:int> /say hi"));
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<1a> /say hi"));
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<a b> /say hi"));
    }

    @Test
    void declarationPrefixOmitsStringType() {
        AliasDefinition def = AliasDefinition.parse("<target> <count:int> = /say hi");
        assertEquals("<target> <count:int> ", def.declarationPrefix());
    }

    @Test
    void choiceDeclarationsParse() {
        AliasDefinition def = AliasDefinition.parse("<dim:to_overworld,to_nether> = /say $dim$");
        assertEquals(AliasDefinition.ParamType.CHOICE, def.params().get(0).type());
        assertEquals(java.util.List.of("to_overworld", "to_nether"), def.params().get(0).options());
        assertEquals("<dim:to_overworld,to_nether> ", def.declarationPrefix());
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<dim:a,,b> = /say hi"));
    }

    @Test
    void defaultsMakeParamsOptional() {
        AliasDefinition def = AliasDefinition.parse("<r:int=5> <p:pos=~ ~ ~> <msg=hi> = /say $r$ $p$ $msg$");
        assertEquals("5", def.params().get(0).defaultValue());
        assertTrue(def.params().get(0).optional());
        assertEquals("~ ~ ~", def.params().get(1).defaultValue());
        assertEquals(AliasDefinition.ParamType.STRING, def.params().get(2).type());
        assertEquals("hi", def.params().get(2).defaultValue());
        assertEquals("<r:int=5> <p:pos=~ ~ ~> <msg=hi> ", def.declarationPrefix());
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<r:int=> /say hi"));
    }

    @Test
    void defaultExpressionsMayContainDeclarationCharacters() {
        // = > : , inside $...$ belong to the expression, not the declaration
        AliasDefinition def = AliasDefinition.parse(
                "<dim:to_overworld,to_nether=$client.y > 0 ? \"to_nether\" : \"to_overworld\"$> = /say $dim$");
        assertEquals(AliasDefinition.ParamType.CHOICE, def.params().get(0).type());
        assertEquals(java.util.List.of("to_overworld", "to_nether"), def.params().get(0).options());
        assertEquals("$client.y > 0 ? \"to_nether\" : \"to_overworld\"$", def.params().get(0).defaultValue());
        assertEquals("/say $dim$", def.body());
    }

    @Test
    void noParamsWithSeparatorIsJustABody() {
        AliasDefinition def = AliasDefinition.parse("= /weather clear && Have fun!");
        assertTrue(def.params().isEmpty());
        assertEquals("/weather clear && Have fun!", def.body());
    }

    private static String rejects(String definition) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AliasDefinition.parse(definition)).getMessage();
    }

    private static void assertRejectionNames(String definition, String... mustContain) {
        String message = rejects(definition);
        for (String fragment : mustContain) {
            assertTrue(message.contains(fragment),
                    "'" + definition + "' should mention \"" + fragment + "\" but said: " + message);
        }
    }

    /**
     * A definition that won't parse is a command that silently isn't there —
     * parseDefinition swallows the failure on config load — so these messages
     * are the only thing standing between a typo and a command that vanished.
     */
    @Test
    void malformedDeclarationsSayWhatShapeWasExpected() {
        assertRejectionNames("<name = /say hi", "Unclosed parameter declaration");
        assertRejectionNames("<:int> = /say hi", "<name:type> or just <name>");
        assertRejectionNames("<x:> = /say hi", "<name:type> or just <name>");
        assertRejectionNames("<x:int=> = /say hi", "Empty default", "<name:type=value>");
    }

    @Test
    void parameterNamesAreValidatedWithTheRuleTheyBroke() {
        assertRejectionNames("<1x:int> = /say hi", "Parameter names must start with a letter");
        assertRejectionNames("<x-y:int> = /say hi", "letters, numbers, and _");
        assertRejectionNames("<x:int> <x:int> = /say hi", "Duplicate parameter name", "'x'");
        // and the legal shapes are accepted
        assertEquals("x_2", AliasDefinition.parse("<x_2:int> = /say hi").params().get(0).name());
    }

    @Test
    void theBodyMustActuallyExist() {
        assertRejectionNames("<x:int> =", "body after '=' cannot be empty");
        assertRejectionNames("<x:int> /say hi", "Missing '=' before the body");
        assertRejectionNames("/say hi", "Missing '=' before the body", "sunny = /weather clear");
    }

    /** A quoted note only counts as one when an = follows it; otherwise it's body text. */
    @Test
    void aQuotedNoteIsToldApartFromAQuotedBody() {
        AliasDefinition noted = AliasDefinition.parse("\"toggles time\" = /tick freeze");
        assertEquals("toggles time", noted.description());
        assertEquals("/tick freeze", noted.body());

        AliasDefinition quotedBody = AliasDefinition.parse("= \"hello there\"");
        assertEquals("", quotedBody.description());
        assertEquals("\"hello there\"", quotedBody.body());

        // an unterminated quote isn't a note, so the = rule applies as normal
        assertRejectionNames("\"unterminated = /say hi", "Missing '=' before the body");
    }

    @Test
    void aNoteKeepsItsEscapedQuotesThroughARoundTrip() {
        AliasDefinition def = AliasDefinition.parse("\"say \\\"hi\\\" nicely\" = /say hi");
        assertEquals("say \"hi\" nicely", def.description());
        assertEquals("\"say \\\"hi\\\" nicely\"", def.descriptionSuffix(), "re-quoted for storage");
        assertEquals("", AliasDefinition.parse("= /say hi").descriptionSuffix(), "no note, no suffix");
    }

    /** declarationPrefix reconstructs the signature — it's what [edit] puts in your chat bar. */
    @Test
    void theDeclarationPrefixRebuildsWhatWasParsed() {
        assertEquals("", AliasDefinition.parse("= /say hi").declarationPrefix());
        assertEquals("<n:int> ", AliasDefinition.parse("<n:int> = /say hi").declarationPrefix());
        assertEquals("<who> ", AliasDefinition.parse("<who> = /say hi").declarationPrefix(),
                "a bare name is the string type and stays bare");
        assertEquals("<m:on,off> ", AliasDefinition.parse("<m:on,off> = /say hi").declarationPrefix());
    }

    /** A backslash escapes the next character everywhere the declaration is scanned. */
    @Test
    void escapesAreHonoredWhileScanningDeclarations() {
        AliasDefinition def = AliasDefinition.parse("<msg:text=a \\$ b> = /say $msg$");
        assertEquals("a \\$ b", def.params().get(0).defaultValue(),
                "the escaped $ doesn't open a marker span");
    }
}
