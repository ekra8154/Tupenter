package net.tupenter.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasDefinitionTest {

    @Test
    void bareParamDefaultsToQuotableString() {
        AliasDefinition def = AliasDefinition.parse("<target> /execute at $target$ run summon lightning_bolt");
        assertEquals(1, def.params().size());
        assertEquals("target", def.params().get(0).name());
        assertEquals(AliasDefinition.ParamType.STRING, def.params().get(0).type());
        assertEquals("/execute at $target$ run summon lightning_bolt", def.body());
    }

    @Test
    void stringIsNotGreedySoItCanBeFollowed() {
        AliasDefinition def = AliasDefinition.parse("<target> <count:int> /say $target$ $count$");
        assertEquals(2, def.params().size());
        assertEquals(AliasDefinition.ParamType.STRING, def.params().get(0).type());
        assertEquals(AliasDefinition.ParamType.INT, def.params().get(1).type());
    }

    @Test
    void typedDeclarationsParse() {
        AliasDefinition def = AliasDefinition.parse("<a:int> <b:float> <c:word> <d:player> <e:selector> <f:text> /say hi");
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
        AliasDefinition def = AliasDefinition.parse("<target> <count:int> /say hi");
        assertEquals("<target> <count:int> ", def.declarationPrefix());
    }

    @Test
    void choiceDeclarationsParse() {
        AliasDefinition def = AliasDefinition.parse("<dim:to_overworld,to_nether> /say $dim$");
        assertEquals(AliasDefinition.ParamType.CHOICE, def.params().get(0).type());
        assertEquals(java.util.List.of("to_overworld", "to_nether"), def.params().get(0).options());
        assertEquals("<dim:to_overworld,to_nether> ", def.declarationPrefix());
        assertThrows(IllegalArgumentException.class, () -> AliasDefinition.parse("<dim:a,,b> /say hi"));
    }

    @Test
    void defaultsMakeParamsOptional() {
        AliasDefinition def = AliasDefinition.parse("<r:int=5> <p:pos=~ ~ ~> <msg=hi> /say $r$ $p$ $msg$");
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
                "<dim:to_overworld,to_nether=$client.y > 0 ? \"to_nether\" : \"to_overworld\"$> /say $dim$");
        assertEquals(AliasDefinition.ParamType.CHOICE, def.params().get(0).type());
        assertEquals(java.util.List.of("to_overworld", "to_nether"), def.params().get(0).options());
        assertEquals("$client.y > 0 ? \"to_nether\" : \"to_overworld\"$", def.params().get(0).defaultValue());
        assertEquals("/say $dim$", def.body());
    }

    @Test
    void noParamsIsJustABody() {
        AliasDefinition def = AliasDefinition.parse("/weather clear && Have fun!");
        assertTrue(def.params().isEmpty());
        assertEquals("/weather clear && Have fun!", def.body());
    }
}
