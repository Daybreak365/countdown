package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded virtual scheduler shared by validation and its regression tests. Never advances the server clock. */
public final class ValidationTimers {
    private ValidationTimers() {}
    public static final int DEFAULT_DESTRUCTIVE_TICKS=2400,MAX_CALLBACKS=200;
    public record Result(int callbacks,long steps,int remainingOneShots,boolean repeating,boolean truncated,List<String> errors) {}
    public static boolean destructive(Set<String> calls){
        return calls.stream().anyMatch(s->s.endsWith(".explode"));
    }
    public static int horizon(Set<String> calls,int requested){
        return Math.max(destructive(calls)?DEFAULT_DESTRUCTIVE_TICKS:0,Math.clamp(requested,0,24000));
    }
    public static Result run(ScriptInstance inst,long base,int ticks,Consumer<ScriptRecord.NamedTimer> named) {
        long end=base+ticks,now=base,steps=0;int runs=0;List<String> errors=new ArrayList<>();
        inst.simulationTick=base;
        try {
            while(runs<MAX_CALLBACKS){
                var next=inst.timers.stream().filter(t->!t.cancelled && t.due<=end).min(Comparator.comparingLong(t->t.due)).orElse(null);
                var nt=inst.rec.timers.stream().filter(t->t.fireTick<=end).min(Comparator.comparingLong(t->t.fireTick)).orElse(null);
                if(next==null && nt==null)break;
                boolean isNamed=nt!=null && (next==null || nt.fireTick<next.due);
                long due=isNamed?nt.fireTick:next.due;
                CooldownLedger.advance(inst.rec,(int)Math.clamp(due-now,0,24000),false);
                now=Math.max(now,due);inst.simulationTick=now;inst.in.budget.reset();
                try {
                    if(isNamed){inst.rec.timers.remove(nt);named.accept(nt);}
                    else {
                        if(next.interval>0)next.due=now+next.interval;else next.cancelled=true;
                        next.runs++;inst.in.invoke(next.fn,List.of());
                    }
                }catch(ScriptError ex){errors.add("in "+(isNamed?"named timer '"+nt.name+"'":"timer callback")+" at simulated tick "+(now-base)+": "+ex.describe());}
                catch(RuntimeException ex){errors.add("internal error in timer callback: "+ex.getClass().getSimpleName()+": "+ex.getMessage());}
                steps+=inst.in.budget.steps();runs++;
                if(!errors.isEmpty())break;
            }
            int oneShots=(int)inst.timers.stream().filter(t->!t.cancelled && t.interval==0).count()+inst.rec.timers.size();
            boolean repeating=inst.timers.stream().anyMatch(t->!t.cancelled && t.interval>0);
            boolean due=inst.timers.stream().anyMatch(t->!t.cancelled && t.due<=end)||inst.rec.timers.stream().anyMatch(t->t.fireTick<=end);
            return new Result(runs,steps,oneShots,repeating,runs==MAX_CALLBACKS && due,errors);
        }finally{inst.simulationTick=-1;}
    }
    public static List<String> coverageErrors(Set<String> calls,int explosionCalls,int checks,Result timers) {
        if(!destructive(calls))return List.of();
        var errors=new ArrayList<String>();
        if(explosionCalls==0 && checks==0)errors.add("The explosion path was never exercised. Supply test_events with the actual trigger/action and prerequisites; untested destructive callbacks cannot be activated.");
        if(timers.remainingOneShots()>0)errors.add("Destructive script still has "+timers.remainingOneShots()+" untested delayed callbacks. Shorten the sequence or supply a complete bounded timer scenario before activation.");
        if(timers.repeating() && checks==0)errors.add("Repeating explosions require a live world.checkExplosion check and handling of allowed:false; spacing blasts does not refill the script block budget.");
        if(timers.truncated())errors.add("Destructive timer simulation reached its callback limit; validate a bounded sequence instead of accepting a truncated test.");
        return errors;
    }
}
