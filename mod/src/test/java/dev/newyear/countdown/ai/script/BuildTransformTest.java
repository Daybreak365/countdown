package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildTransformTest {
    private ScriptRecord.BlockChange cell(int x,int y,int z,String state){
        var c=new ScriptRecord.BlockChange();c.dimension="minecraft:overworld";c.x=x;c.y=y;c.z=z;c.before="minecraft:air";c.after=state;return c;
    }
    private List<ScriptRecord.BlockChange> house(){return List.of(cell(10,64,20,"minecraft:stone"),cell(12,66,22,"minecraft:oak_stairs[facing=north]"));}
    @Test void translationKeepsRelativeHeightAndNeverMutatesTheSource(){
        var source=house();var shifted=BuildTransform.apply(source,100,70,200,0,s->s);
        assertEquals(99,shifted.get(0).x);assertEquals(199,shifted.get(0).z);assertEquals(70,shifted.get(0).y);
        assertEquals(101,shifted.get(1).x);assertEquals(201,shifted.get(1).z);assertEquals(72,shifted.get(1).y);
        assertEquals(10,source.get(0).x);assertEquals(64,source.get(0).y);
    }
    @Test void clockwiseRotationAndNativeStateTransformStayTogether(){
        var turned=BuildTransform.apply(house(),100,70,200,1,s->s.replace("north","east"));
        assertEquals(101,turned.get(0).x);assertEquals(199,turned.get(0).z);
        assertEquals(99,turned.get(1).x);assertEquals(201,turned.get(1).z);
        assertEquals("minecraft:oak_stairs[facing=east]",turned.get(1).after);
        assertEquals(72,turned.get(1).y);
    }
    @Test void oppositeRotationsPreserveDistancesAndAllCellsIncludingClearance(){
        var source=new ArrayList<>(house());source.add(cell(11,65,21,"minecraft:air"));
        for(int turns=0;turns<4;turns++){
            var result=BuildTransform.apply(source,-20,-60,-30,turns,s->s);
            assertEquals(3,result.size());assertEquals("minecraft:air",result.get(2).after);
            var a=result.get(0);var b=result.get(1);
            assertEquals(8,(a.x-b.x)*(a.x-b.x)+(a.z-b.z)*(a.z-b.z));
        }
    }
    @Test void clientAndServerReplayAnIdenticalSerializedRequest(){
        var r=new BuildTransform.Request("bp-12345678",7,-25,80,91,3,false);
        assertEquals(r,BuildTransform.Request.parse(r.encode()));
        var client=BuildTransform.apply(house(),r.x(),r.y(),r.z(),r.turns(),s->s);
        var wire=BuildTransform.Request.parse(r.encode());
        var server=BuildTransform.apply(house(),wire.x(),wire.y(),wire.z(),wire.turns(),s->s);
        assertEquals(new Gson().toJson(client),new Gson().toJson(server));
    }
    @Test void malformedRequestsCannotChooseArbitraryActionsOrOverflowCoordinates(){
        for(String value:List.of("", "bp-a,1,0,64,0,4,1", "bp-a,0,0,64,0,0,1", "bp-a,1,0,64,0,0,2",
                "bp-a,1,2147483647,64,0,0,1", "bp-a,1,0,64,NaN,0,1", "other,1,0,64,0,0,1", "bp-a,1,0,64,0,0,1,extra"))
            assertThrows(IllegalArgumentException.class,()->BuildTransform.Request.parse(value));
        assertThrows(IllegalArgumentException.class,()->BuildTransform.Request.parse(null));
        assertThrows(IllegalArgumentException.class,()->BuildTransform.apply(house(),0,64,0,-1,s->s));
    }
    @Test void cannotAcceptAnUnpositionedPlanOrMoveAcceptedOrEndedDesigns(){
        var p=BuildPlan.capture(house(),1);p.placementRequired=true;
        assertTrue(p.canPosition());assertThrows(IllegalArgumentException.class,()->p.accept(p.id,p.revision));
        p.placementRequired=false;p.accept(p.id,p.revision);assertFalse(p.canPosition());
        for(String end:List.of("COMPLETE","CANCELLED","FAILED","REJECTED")){
            var ended=BuildPlan.capture(house(),1);ended.status=end;assertFalse(ended.canPosition());
        }
    }
    @Test void confirmedSiteSurvivesRestartAndFeedsTheActualConstructionJob(){
        var original=BuildPlan.capture(house(),1);original.placementRequired=true;
        var moved=BuildPlan.revision(original,BuildTransform.apply(original.cells,100,70,200,1,s->s));
        moved.placementRequired=false;moved.anchor=new double[]{100,70,200};
        var loaded=new Gson().fromJson(new Gson().toJson(moved),BuildPlan.class);loaded.accept(loaded.id,loaded.revision);
        assertArrayEquals(new int[]{101,70,199,0,99,72,201,1},loaded.job().ops);
        assertEquals(10,original.cells.get(0).x);assertFalse(original.accepted);
        assertThrows(IllegalArgumentException.class,()->loaded.requireCell(loaded.dimension,10,64,20,"minecraft:stone"));
    }
    @Test void oldSavedBlueprintsDoNotAcquireANewUnfulfillablePlacementGate(){
        var loaded=new Gson().fromJson("{\"id\":\"bp-old\",\"revision\":1,\"status\":\"PREVIEW\"}",BuildPlan.class);
        assertFalse(loaded.placementRequired);assertDoesNotThrow(()->loaded.accept("bp-old",1));
    }
    @Test void naturalTrunkEvidenceSurvivesRestartAndCannotAuthorizeAnotherBlockOrDimension(){
        var plan=BuildPlan.capture(house(),1);
        plan.rememberNaturalLog(10,64,20,"minecraft:oak_log[axis=y]");
        var loaded=new Gson().fromJson(new Gson().toJson(plan),BuildPlan.class);
        assertTrue(loaded.wasNaturalLog("minecraft:overworld",10,64,20,"minecraft:oak_log[axis=y]"));
        assertFalse(loaded.wasNaturalLog("minecraft:the_nether",10,64,20,"minecraft:oak_log[axis=y]"));
        assertFalse(loaded.wasNaturalLog("minecraft:overworld",10,64,20,"minecraft:oak_planks"));
        assertFalse(loaded.wasNaturalLog("minecraft:overworld",11,64,20,"minecraft:oak_log[axis=y]"));
        var moved=BuildPlan.revision(loaded,BuildTransform.apply(loaded.cells,50,70,20,0,s->s));
        assertFalse(moved.wasNaturalLog("minecraft:overworld",10,64,20,"minecraft:oak_log[axis=y]"));
    }
    @Test void fullSizePlansKeepEveryCellAndRejectExcessInsteadOfTruncating(){
        var cells=new ArrayList<ScriptRecord.BlockChange>();
        for(int i=0;i<4096;i++)cells.add(cell(i%64,64,i/64,"minecraft:stone"));
        var plan=BuildPlan.capture(cells,1);plan.accept(plan.id,plan.revision);
        assertEquals(4096*4,plan.job().ops.length);
        cells.add(cell(64,64,64,"minecraft:stone"));
        assertThrows(IllegalArgumentException.class,()->BuildPlan.capture(cells,1));
    }
}
