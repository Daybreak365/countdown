package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplosionBudgetTest {
    @Test void readingUnusedBudgetDoesNotMutateSavedRuntime(){
        var record=new ScriptRecord();String before=record.runtime.toString();
        assertEquals(4096,ExplosionBudget.remaining(record,0,0,4096));
        assertEquals(before,record.runtime.toString());
    }
    @Test void eighteenBlastsChargeChangedBlocksInsteadOfEveryNeighborInTheirBoxes(){
        var record=new ScriptRecord();
        for(int blast=0;blast<18;blast++){
            var reservation=new ExplosionBudget.Reservation(ExplosionBudget.remaining(record,0,0,4096));
            for(int ray=0;ray<20;ray++)for(long cell=0;cell<100;cell++)reservation.include(cell);
            assertEquals(100,reservation.size());
            ExplosionBudget.charge(record,60); // Sixty changed; forty selected blocks remained untouched.
        }
        assertEquals(1080,ExplosionBudget.used(record));
        assertEquals(3016,ExplosionBudget.remaining(record,0,0,4096));
    }
    @Test void failedReservationCannotSpendBudgetOrPartiallyAdmitAnotherCell(){
        var record=new ScriptRecord();var reservation=new ExplosionBudget.Reservation(2);
        reservation.include(11);reservation.include(11);reservation.include(12);
        var error=assertThrows(ScriptError.class,()->reservation.include(13));
        assertEquals(2,reservation.size());assertEquals(0,ExplosionBudget.used(record));
        assertTrue(error.getMessage().contains("delays do not refill"));
        assertThrows(ScriptError.class,()->new ExplosionBudget.Reservation(0).include(1));
    }
    @Test void recordedEditsPendingJobsAndBlastsShareTheAvailableBudget(){
        var record=new ScriptRecord();ExplosionBudget.charge(record,500);
        assertEquals(796,ExplosionBudget.remaining(record,1800,1000,4096));
        assertEquals(0,ExplosionBudget.remaining(record,5000,1000,4096));
    }
    @Test void restartPreservesConsumptionAndLegacyReservationsAreNotSilentlyRefunded(){
        var gson=new Gson();var record=new ScriptRecord();ExplosionBudget.charge(record,120);
        var restored=gson.fromJson(gson.toJson(record),ScriptRecord.class);
        assertEquals(120,ExplosionBudget.used(restored));
        var legacy=gson.fromJson("{\"runtime\":{\"destruction\":{\"blocks\":2718}}}",ScriptRecord.class);
        ExplosionBudget.charge(legacy,20);assertEquals(2738,ExplosionBudget.used(legacy));
        assertThrows(IllegalArgumentException.class,()->ExplosionBudget.charge(record,-1));
    }
    @Test void waitingRefillsTheFourBlastWindowButNeverTheBlockLedger(){
        var record=new ScriptRecord();var window=new ExplosionBudget.Window();
        for(int n=0;n<4;n++){window.claim(100);ExplosionBudget.charge(record,10);}
        assertEquals(20,window.retryAfter(100));assertEquals(1,window.retryAfter(119));
        assertThrows(ScriptError.class,()->window.claim(119));
        window.claim(120);assertEquals(0,window.retryAfter(120));assertEquals(40,ExplosionBudget.used(record));
    }
}
