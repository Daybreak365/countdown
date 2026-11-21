package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooldownLedgerTest {
    private ScriptRecord record() { var r=new ScriptRecord();r.id="ability";r.state=ScriptRecord.State.ACTIVE;return r; }
    @Test void repeatedItemAndKeyTriggersShareOneServerCooldown() {
        var r=record();
        assertTrue(CooldownLedger.tryUse(r,"alice","dash",2,"질주",null));
        assertFalse(CooldownLedger.tryUse(r,"alice","dash",900,"changed",null));
        assertEquals(40,CooldownLedger.remaining(r,"alice","dash"));
        for(int i=0;i<39;i++)CooldownLedger.advance(r,false);
        assertFalse(CooldownLedger.tryUse(r,"alice","dash",2,null,null));
        CooldownLedger.advance(r,false);
        assertTrue(CooldownLedger.tryUse(r,"alice","dash",2,null,null));
    }
    @Test void restartAndMetadataRegistrationPreserveRemainingTime() {
        var r=record();CooldownLedger.tryUse(r,"alice","spell",6,"마법","img_star");
        for(int i=0;i<43;i++)CooldownLedger.advance(r,false);
        var loaded=new Gson().fromJson(new Gson().toJson(r),ScriptRecord.class);
        CooldownLedger.register(loaded,"alice","spell","별빛 마법","img_star");
        assertEquals(77,CooldownLedger.remaining(loaded,"alice","spell"));
        assertEquals("별빛 마법",CooldownLedger.visible(loaded,"alice").get(0).getAsJsonObject().get("label").getAsString());
    }
    @Test void playersAndScriptsDoNotShareCooldownsOrHudEntries() {
        var a=record();var b=record();
        CooldownLedger.tryUse(a,"alice","dash",2,null,null);
        assertTrue(CooldownLedger.tryUse(a,"bob","dash",2,null,null));
        assertTrue(CooldownLedger.tryUse(b,"alice","dash",2,null,null));
        assertEquals(1,CooldownLedger.visible(a,"alice").size());
        assertTrue(CooldownLedger.visible(a,"eve").isEmpty());
        a.state=ScriptRecord.State.SUSPENDED;
        assertTrue(CooldownLedger.visible(a,"alice").isEmpty());
        a.state=ScriptRecord.State.ACTIVE;
        assertEquals(40,CooldownLedger.remaining(a,"alice","dash"));
    }
    @Test void readyFlashAndRemovalAreBothSynchronized() {
        var r=record();CooldownLedger.tryUse(r,"alice","blink",0.05,null,null);
        assertTrue(CooldownLedger.advance(r,false).contains("alice"));
        assertEquals(0,CooldownLedger.visible(r,"alice").get(0).getAsJsonObject().get("remaining").getAsInt());
        for(int i=0;i<39;i++)CooldownLedger.advance(r,false);
        assertTrue(CooldownLedger.active(r));
        assertTrue(CooldownLedger.advance(r,false).contains("alice"));
        assertFalse(CooldownLedger.active(r));assertTrue(CooldownLedger.visible(r,"alice").isEmpty());
    }
    @Test void boundsCannotCreateInfiniteTimersOrUnboundedState() {
        var r=record();
        for(double n:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,86401})
            assertThrows(IllegalArgumentException.class,()->CooldownLedger.tryUse(r,"alice","x",n,null,null));
        assertThrows(IllegalArgumentException.class,()->CooldownLedger.remaining(r,"alice","bad:id"));
        for(int i=0;i<16;i++)CooldownLedger.register(r,"alice","a"+i,null,null);
        assertThrows(IllegalArgumentException.class,()->CooldownLedger.tryUse(r,"alice","overflow",1,null,null));
        CooldownLedger.clear(r,"alice","a0");
        assertTrue(CooldownLedger.tryUse(r,"alice","overflow",1,null,null));
    }
    @Test void freshRegistrationDoesNotShowAnUnstartedCooldown() {
        var r=record();CooldownLedger.register(r,"alice","dash","질주",null);
        assertTrue(CooldownLedger.visible(r,"alice").isEmpty());assertFalse(CooldownLedger.active(r));
        assertEquals(0,CooldownLedger.remaining(r,"alice","dash"));
    }
    @Test void cooldownApiIsAvailableToTheScriptStaticChecker() {
        var program=dev.newyear.countdown.ai.script.lang.Parser.parse("cooldowns.register(owner,'dash',{label:'질주'}); cooldowns.tryUse(owner,'dash',6);");
        var checked=dev.newyear.countdown.ai.script.lang.StaticChecker.check(program,ScriptApi.globalNames(),ScriptApi.HOST_MEMBERS);
        assertTrue(checked.issues().stream().noneMatch(dev.newyear.countdown.ai.script.lang.StaticChecker.Issue::error));
    }
    @Test void timerSimulationCanAdvancePastExpiryWithoutResettingIt() {
        var r=record();CooldownLedger.tryUse(r,"alice","pulse",2,null,null);
        CooldownLedger.advance(r,39,false);assertEquals(1,CooldownLedger.remaining(r,"alice","pulse"));
        CooldownLedger.advance(r,5,false);assertEquals(36,CooldownLedger.visible(r,"alice").get(0).getAsJsonObject().get("readyTicks").getAsInt());
        CooldownLedger.advance(r,36,false);assertFalse(CooldownLedger.active(r));
        assertTrue(CooldownLedger.tryUse(r,"alice","pulse",2,null,null));
        CooldownLedger.advance(r,200,false);assertTrue(CooldownLedger.visible(r,"alice").isEmpty());
    }
}
