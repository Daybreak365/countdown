package dev.newyear.countdown.ai.content;

import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.script.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemAppearanceTest {
    @Test void customItemsNeedGeneratedArtEvenWithoutAnAppearanceRequest() {
        assertThrows(IllegalArgumentException.class,()->ItemAppearance.requireGenerated(1,true,null));
        var procedural=new Textures.Def();
        assertThrows(IllegalArgumentException.class,()->ItemAppearance.requireGenerated(1,true,procedural));
        procedural.imageModel="gpt-image-2.5-flare";
        assertDoesNotThrow(()->ItemAppearance.requireGenerated(1,true,procedural));
    }
    @Test void ordinaryVanillaGiftsAndPreviouslySavedScriptsStillWork() {
        assertDoesNotThrow(()->ItemAppearance.requireGenerated(1,false,null));
        assertDoesNotThrow(()->ItemAppearance.requireGenerated(0,true,null));
    }
    @Test void validationPreservesMandatoryAppearanceRule() throws Exception {
        var draft=new ScriptRecord();draft.appearanceVersion=1;
        var copy=ScriptValidator.class.getDeclaredMethod("shallowCopy",ScriptRecord.class);copy.setAccessible(true);
        assertEquals(1,((ScriptRecord)copy.invoke(null,draft)).appearanceVersion);
    }
    @Test void weaponBasesInferAppropriateGripWithoutExplicitStyle() {
        assertEquals("handheld",ItemAppearance.infer("minecraft:diamond_sword"));
        assertEquals("handheld",ItemAppearance.infer("iron_pickaxe"));
        assertEquals("bow",ItemAppearance.infer("bow"));assertEquals("shield",ItemAppearance.infer("minecraft:shield"));
        assertEquals("flat",ItemAppearance.infer("minecraft:paper"));
    }
    @Test void everyPoseReferencesAPackagedModelAndBlocksUseCubeGeometry() throws Exception {
        Path assets=Path.of("src/main/resources/assets/newyearcountdown");
        for(String profile:ItemAppearance.PROFILES){
            var item=JsonParser.parseString(Files.readString(assets.resolve("items/wish_"+profile+".json"))).getAsJsonObject().getAsJsonObject("model");
            String base=item.get("base").getAsString().split(":")[1];
            assertTrue(Files.isRegularFile(assets.resolve("models/"+base+".json")));
            assertEquals(profile.equals("block"),item.getAsJsonObject("model").get("cube").getAsBoolean());
        }
        for(String profile:new String[]{"staff","bow","shield","block"}){
            var display=JsonParser.parseString(Files.readString(assets.resolve("models/item/wish_"+profile+".json"))).getAsJsonObject().getAsJsonObject("display");
            for(String view:new String[]{"firstperson_righthand","firstperson_lefthand","thirdperson_righthand","thirdperson_lefthand"})assertTrue(display.has(view),profile+" "+view);
        }
    }
    @Test void retiredBlockApiIsRejectedWhileExistingBlockConstructionRemainsAvailable() {
        ScriptValidator.primeDocs();
        assertFalse(ScriptApi.globalNames().contains("blocks"));
        var parsed=dev.newyear.countdown.ai.script.lang.Parser.parse("const id=blocks.define('stone',{phase:'solid',texture:'img_test'}); blocks.give(owner,id,1);");
        var report=dev.newyear.countdown.ai.script.lang.StaticChecker.check(parsed,ScriptApi.globalNames(),ScriptApi.HOST_MEMBERS);
        assertFalse(report.ok());
        var ordinary=dev.newyear.countdown.ai.script.lang.Parser.parse("world.setBlock(owner.blockPos(),'stone');");
        assertTrue(dev.newyear.countdown.ai.script.lang.StaticChecker.check(ordinary,ScriptApi.globalNames(),ScriptApi.HOST_MEMBERS).ok());
    }
}
