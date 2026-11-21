package dev.newyear.countdown.client.ai;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooldownHudStateTest {
    private JsonObject packet(String script,int remaining,int ready) {
        return JsonParser.parseString("{script:'"+script+"',entries:[{id:'dash',label:'질주',texture:'',remaining:"+remaining+",duration:100,readyTicks:"+ready+"}]}").getAsJsonObject();
    }
    @Test void predictionWaitsForServerReadyEvenAfterDelayedPackets() {
        var state=new CooldownHudState();state.apply(packet("one",5,0));
        for(int i=0;i<120;i++)state.tick();
        assertEquals(1,state.entries().getFirst().remaining());
        state.apply(packet("one",0,40));assertEquals(0,state.entries().getFirst().remaining());
        for(int i=0;i<40;i++)state.tick();assertTrue(state.entries().isEmpty());
    }
    @Test void serverCorrectionAndCleanupApplyOnlyToTheTargetScript() {
        var state=new CooldownHudState();state.apply(packet("one",60,0));state.apply(packet("two",90,0));
        state.tick();state.apply(packet("one",70,0));
        assertEquals(70,state.entries().getFirst().remaining());
        state.apply(JsonParser.parseString("{script:'one',entries:[]}").getAsJsonObject());
        assertEquals("two",state.entries().getFirst().script());
        state.clear();assertTrue(state.entries().isEmpty());
    }
    @Test void cardsFitSmallAndLargeGuiScalesAndYieldToVanillaMessages() {
        for(int width:new int[]{160,240,320,480,960})for(int count:new int[]{1,2,3,5,40}) {
            var a=CooldownHudState.layout(width,240,count,false);
            var b=CooldownHudState.layout(width,240,count,true);
            assertTrue(a.left()>=0);assertTrue(a.left()+a.columns()*a.cardWidth()-4<=width);
            assertTrue(a.rows()<=2);assertTrue(a.shown()<=6);assertEquals(22,a.bottom()-b.bottom());
        }
    }
}
