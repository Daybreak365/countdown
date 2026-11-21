package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.api.ApiObject;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTimersTest {
    private ScriptInstance boot(String code){
        var record=new ScriptRecord();record.id="timer-test";record.code=code;
        var inst=new ScriptInstance(record,true);inst.simulationTick=100;
        // Real timer registration and tick() API; test does not need a server or mutate a world.
        ScriptApi.install(inst,null);inst.in.budget.maxNanos=5_000_000_000L;
        inst.in.budget.reset();inst.in.run(Parser.parse(code));return inst;
    }
    @Test void nestedAfterUsesTheVirtualCurrentTickInsteadOfTheOriginalServerTick(){
        var inst=boot("state.times=[]; after(20,()=>{state.times.push(tick()); after(20,()=>state.times.push(tick()));});");
        var result=ValidationTimers.run(inst,100,100,t->{});
        assertTrue(result.errors().isEmpty(),result.errors().toString());assertEquals(2,result.callbacks());
        assertEquals(List.of(120.0,140.0),((JsArray)inst.state.get("times")).list);
        assertEquals(-1,inst.simulationTick);
    }
    @Test void namedTimerAndNestedCallbackAreBothExercisedInTimeOrder(){
        var inst=boot("state.times=[]; timer('blast',30); on('timer',e=>{state.times.push(tick()); after(10,()=>state.times.push(tick()));});");
        var result=ValidationTimers.run(inst,100,100,t->{
            for(var listener:inst.listeners.get("timer"))inst.in.invoke(listener.fn(),List.of(new JsObject().put("name",t.name)));
        });
        assertTrue(result.errors().isEmpty(),result.errors().toString());assertEquals(2,result.callbacks());
        assertEquals(List.of(130.0,140.0),((JsArray)inst.state.get("times")).list);assertEquals(0,result.remainingOneShots());
    }
    @Test void omittedSimulationCannotLetAnUnaffordableDelayedChainPass(){
        var inst=boot("for(let n=0;n<18;n++){after(n*10+1,()=>world.explode([0,64,0],4));}");
        // Same lifetime ledger as runtime; fixed conservative world estimate makes the regression deterministic.
        inst.in.define("world",new ApiObject("world").fn("explode","",(in,s,args)->{
            long left=ExplosionBudget.remaining(inst.rec,0,0,4096);
            if(2718>left)throw in.error(ExplosionBudget.exhausted(2718,left));
            ExplosionBudget.charge(inst.rec,2718);return null;
        }));
        int horizon=ValidationTimers.horizon(Set.of("world.explode","after"),0);
        var result=ValidationTimers.run(inst,100,horizon,t->{});
        assertEquals(2400,horizon);assertEquals(2,result.callbacks());
        assertTrue(result.errors().getFirst().contains("only 1378 remain"));
        assertTrue(result.errors().getFirst().contains("simulated tick 11"));
    }
    @Test void cancelledCallbacksDoNotRunAndLateDestructiveCallbacksCannotPass(){
        var inst=boot("let id=after(1,()=>{throw new Error('cancelled');}); cancel(id); after(3000,()=>{});");
        var result=ValidationTimers.run(inst,100,2400,t->{});
        assertEquals(0,result.callbacks());assertEquals(1,result.remainingOneShots());
        assertFalse(ValidationTimers.coverageErrors(Set.of("world.explode"),1,1,result).isEmpty());
    }
    @Test void eventTriggeredExplosionsNeedAnExercisedTriggerAndRepeatsNeedPreflight(){
        var idle=new ValidationTimers.Result(0,0,0,false,false,List.of());
        assertFalse(ValidationTimers.coverageErrors(Set.of("world.explode"),0,0,idle).isEmpty());
        assertTrue(ValidationTimers.coverageErrors(Set.of("world.explode"),0,1,idle).isEmpty());
        var repeating=new ValidationTimers.Result(5,0,0,true,false,List.of());
        assertFalse(ValidationTimers.coverageErrors(Set.of("world.explode"),5,0,repeating).isEmpty());
        assertTrue(ValidationTimers.coverageErrors(Set.of("world.explode"),5,5,repeating).isEmpty());
    }
    @Test void truncatedRecurringSimulationCannotBeMistakenForACompletedTest(){
        var inst=boot("every(2,()=>{});");var result=ValidationTimers.run(inst,100,2400,t->{});
        assertEquals(200,result.callbacks());assertTrue(result.truncated());
        assertFalse(ValidationTimers.coverageErrors(Set.of("world.explode"),1,1,result).isEmpty());
    }
    @Test void computedLiteralExplosionCallsAlsoTriggerMandatorySimulation(){
        var checked=StaticChecker.check(Parser.parse("after(1,()=>world['explode']([0,64,0],4));"),ScriptApi.globalNames(),ScriptApi.HOST_MEMBERS);
        assertTrue(checked.apiCalls().contains("world.explode"));assertEquals(2400,ValidationTimers.horizon(checked.apiCalls(),0));
        assertEquals(0,ValidationTimers.horizon(Set.of("fx.burst"),0));
    }
}
