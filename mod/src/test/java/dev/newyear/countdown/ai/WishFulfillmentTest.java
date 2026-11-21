package dev.newyear.countdown.ai;

import com.google.gson.*;
import dev.newyear.countdown.ai.script.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WishFulfillmentTest {
    @TempDir Path dir;
    private JsonObject expected(AiConfig config) {
        var result=new JsonObject();
        for(var entry:AiSettings.snapshot(config).getAsJsonArray("fields")){
            var field=entry.getAsJsonObject();result.add(field.get("key").getAsString(),field.get("value"));
        }
        return result;
    }
    @Test void rewardAndDestructionSwitchesAreIndependentInEveryCombination() {
        for(boolean free:new boolean[]{false,true})for(boolean destructive:new boolean[]{false,true}){
            var config=new AiConfig();config.unrestrictedWishes=free;config.allowDestructiveWishes=destructive;
            assertEquals(!free,config.lastingConditionRequired());assertEquals(free?0:60,config.effectiveWishCooldownSeconds());
            assertEquals(destructive,config.destruction().explosions());assertEquals(destructive,config.destruction().containers());
            assertEquals(destructive?"any":"natural",config.destruction().editMode(config.editMode()));
            assertEquals(destructive,Policy.destructiveEntityAllowed("minecraft:wither",config.destruction()));
            assertFalse(Policy.destructiveEntityAllowed("minecraft:command_block_minecart",config.destruction()));
        }
    }
    @Test void togglesPersistWithoutOverwritingDetailedSettings() throws Exception {
        var initial=new AiConfig();initial.wishCooldownSeconds=117;initial.worldEditMode="empty_only";
        var patch=new JsonObject();patch.addProperty("unrestrictedWishes",true);
        var changed=AiSettings.prepare(initial,patch,expected(initial));
        assertFalse(changed.allowDestructiveWishes);assertEquals(117,changed.wishCooldownSeconds);
        Path file=dir.resolve("config.json");AiConfig.writeConfig(changed,file);
        var loaded=AiConfig.readConfig(new StringReader(Files.readString(file)));
        assertTrue(loaded.unrestrictedWishes);assertFalse(loaded.allowDestructiveWishes);
        assertEquals("empty_only",loaded.destruction().editMode(loaded.editMode()));
        patch.addProperty("unrestrictedWishes",false);var restored=AiSettings.prepare(loaded,patch,expected(loaded));
        assertEquals(117,restored.effectiveWishCooldownSeconds());
    }
    @Test void legacyFullReleaseMigratesButExplicitIndependentSwitchesWin() {
        var migrated=AiConfig.readConfig(new StringReader("{\"wishFulfillment\":\"unrestricted\"}"));
        assertTrue(migrated.unrestrictedWishes);assertTrue(migrated.allowDestructiveWishes);
        var explicit=AiConfig.readConfig(new StringReader("{\"wishFulfillment\":\"unrestricted\",\"allowDestructiveWishes\":false}"));
        assertTrue(explicit.unrestrictedWishes);assertFalse(explicit.allowDestructiveWishes);
        var restricted=AiConfig.readConfig(new StringReader("{\"wishFulfillment\":\"generous\"}"));
        assertFalse(restricted.unrestrictedWishes);assertFalse(restricted.allowDestructiveWishes);
    }
    @Test void staleOrObsoleteSettingsCannotSilentlyOverwriteTheNewPolicy() {
        var config=new AiConfig();var before=expected(config);config.allowDestructiveWishes=true;
        var patch=new JsonObject();patch.addProperty("allowDestructiveWishes",false);
        assertThrows(IllegalArgumentException.class,()->AiSettings.prepare(config,patch,before));
        patch.remove("allowDestructiveWishes");patch.addProperty("wishFulfillment","unrestricted");
        assertThrows(IllegalArgumentException.class,()->AiSettings.prepare(config,patch,expected(config)));
    }
    @Test void autonomousEventsNeverInheritEitherOverride() throws Exception {
        var field=AiConfig.class.getDeclaredField("instance");field.setAccessible(true);Object saved=field.get(null);
        try {
            var config=new AiConfig();config.unrestrictedWishes=true;config.allowDestructiveWishes=true;field.set(null,config);
            var record=new ScriptRecord();record.id="test";record.autonomous=true;var inst=new ScriptInstance(record,true);
            assertEquals(WishFulfillment.BALANCED,Policy.fulfillment(inst));assertEquals(WishDestruction.RESTRICTED,Policy.destruction(inst));
            record.autonomous=false;
            assertEquals(WishFulfillment.UNRESTRICTED,Policy.fulfillment(inst));assertEquals(WishDestruction.UNRESTRICTED,Policy.destruction(inst));
        }finally{field.set(null,saved);}
    }
    @Test void engineBoundsAndProtectedBlocksRemainEnforced() {
        for(var policy:WishFulfillment.values()){
            assertTrue(policy.maxGive<=4096);assertTrue(policy.maxAmplifier<=255);
            assertTrue(policy.maxEffectSeconds*20L<=Integer.MAX_VALUE);
        }
        assertFalse(WishFulfillment.BALANCED.allowsGive(2048,1));
        assertTrue(WishFulfillment.UNRESTRICTED.allowsGive(Long.MAX_VALUE,4096));
        assertFalse(WishFulfillment.UNRESTRICTED.allowsGive(0,-1));
        assertTrue(Policy.protectedBlock("minecraft:bedrock"));
        assertTrue(Policy.protectedBlock("newyearcountdown:wish_solid"));
        assertFalse(Policy.protectedBlock("minecraft:stone"));
        for(double power:new double[]{0,-1,4.1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->RuntimeDestruction.validatePower(power));
    }
    @Test void releasingRewardsDoesNotChangeTheBlockApprovalThreshold() {
        var config=new AiConfig();config.unrestrictedWishes=true;config.approvalBlockThreshold=100;
        assertEquals(100,config.destruction().approvalThreshold(100,4096));
        config.allowDestructiveWishes=true;assertEquals(Integer.MAX_VALUE,config.destruction().approvalThreshold(100,4096));
        assertEquals(100,config.approvalBlockThreshold);
    }
}
