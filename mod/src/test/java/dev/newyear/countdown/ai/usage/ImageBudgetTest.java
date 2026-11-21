package dev.newyear.countdown.ai.usage;

import dev.newyear.countdown.ai.openai.ApiResponse;
import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class ImageBudgetTest {
    @Test void simultaneousImagesRespectReservedBudgetAndReleaseOnFailure() {
        var usage=new UsageTracker(ZoneId.of("UTC"));
        assertTrue(usage.reserveImage(.25,.5));assertTrue(usage.reserveImage(.25,.5));assertFalse(usage.reserveImage(.25,.5));
        usage.releaseImage(.25);assertTrue(usage.reserveImage(.25,.5));
        assertThrows(IllegalArgumentException.class,()->usage.reserveImage(Double.NaN,1));
    }
    @Test void imageCostSharesDailyBudgetAndPersistsEstimatedUsageMarker() {
        var usage=new UsageTracker(ZoneId.of("UTC"));
        usage.recordImage("alice",new ApiResponse.Usage(100,0,50,0),.1,"gpt-image-2.5-flare",false);
        usage.recordImage("alice",ApiResponse.Usage.ZERO,.25,"gpt-image-2.5-flare",true);
        assertEquals(.35,usage.costToday(),1e-9);assertEquals(2,usage.today().imageRequests);
        assertEquals(1,usage.today().estimatedImageRequests);assertEquals(.35,usage.today().costByPlayer.get("alice"),1e-9);
        assertFalse(usage.reserveImage(.25,.5));
        var loaded=new UsageTracker(ZoneId.of("UTC"));loaded.load(usage.today(),usage.lifetime());
        assertEquals(1,loaded.today().estimatedImageRequests);
    }
}
