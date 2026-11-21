package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import java.util.HashSet;
import java.util.Set;

/** Lifetime block accounting. Reservations are local to a blast; only actual changes are committed. */
public final class ExplosionBudget {
    private ExplosionBudget() {}
    public static int used(ScriptRecord record) {
        if(record.runtime==null || !record.runtime.has("destruction"))return 0;
        var data=record.runtime.getAsJsonObject("destruction");
        return data.has("blocks")?(int)Math.clamp(data.get("blocks").getAsLong(),0,Integer.MAX_VALUE):0;
    }
    public static long remaining(ScriptRecord record,long directBlocks,long pendingBlocks,int limit) {
        return Math.max(0,(long)limit-Math.max(0,directBlocks)-Math.max(0,pendingBlocks)-used(record));
    }
    public static void charge(ScriptRecord record,long changed) {
        if(changed<0)throw new IllegalArgumentException("Negative explosion charge");
        if(record.runtime==null)record.runtime=new JsonObject();
        if(!record.runtime.has("destruction"))record.runtime.add("destruction",new JsonObject());
        var data=record.runtime.getAsJsonObject("destruction");
        data.addProperty("blocks",Math.min(Integer.MAX_VALUE,(long)used(record)+changed));
        data.addProperty("accounting","actual_changes");
    }
    public static String exhausted(long needed,long remaining) {
        return "Explosion needs "+needed+" block changes but only "+remaining+" remain for this script. "
                +"Use world.checkExplosion immediately before each blast and handle allowed:false; delays do not refill this lifetime budget. "
                +"Reduce the planned work or use bounded gradual world edits for demolition.";
    }
    /** Called while vanilla calculates its affected positions, before it damages entities or changes blocks. */
    public static final class Reservation {
        private final long remaining;
        private final Set<Long> cells=new HashSet<>();
        public Reservation(long remaining){this.remaining=Math.max(0,remaining);}
        public void include(long position) {
            if(cells.contains(position))return;
            if(cells.size()>=remaining)throw new ScriptError(ScriptError.Kind.RUNTIME,exhausted(cells.size()+1L,remaining),0,0);
            cells.add(position);
        }
        public int size(){return cells.size();}
    }
    public static final class Window {
        private long start=-1;
        private int count;
        public long retryAfter(long tick){
            if(start<0 || tick<start || tick-start>=20)return 0;
            return count>=4?20-(tick-start):0;
        }
        public void claim(long tick){
            if(start<0 || tick<start || tick-start>=20){start=tick;count=0;}
            if(count>=4)throw new ScriptError(ScriptError.Kind.RUNTIME,"Native explosion work limit: four per server second; check world.checkExplosion().retryAfterTicks",0,0);
            count++;
        }
    }
}
