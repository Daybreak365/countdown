package dev.newyear.countdown.ai.script;

import java.util.*;
import java.util.function.UnaryOperator;

/** Shared, deterministic grid transform for the hologram and server-owned blueprint. */
public final class BuildTransform {
    private BuildTransform() {}
    public static String rotateState(String state,int turns) {
        var block=dev.newyear.countdown.ai.script.api.McUtil.parseBlockString(state);
        if(block==null)throw new IllegalArgumentException("Unknown block in blueprint");
        var rotation=switch(turns){
            case 0->net.minecraft.world.level.block.Rotation.NONE;
            case 1->net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2->net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3->net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default->throw new IllegalArgumentException("Invalid rotation");
        };
        return dev.newyear.countdown.ai.script.api.McUtil.blockString(block.rotate(rotation));
    }
    public record Request(String plan, int revision, int x, int y, int z, int turns, boolean commit) {
        public String encode() { return plan+","+revision+","+x+","+y+","+z+","+turns+","+(commit?1:0); }
        public static Request parse(String value) {
            if(value==null || value.length()>128)throw new IllegalArgumentException("Invalid placement request");
            String[] fields=value.split(",",-1);
            if(fields.length!=7 || !fields[0].matches("bp-[a-z0-9-]{1,60}"))throw new IllegalArgumentException("Invalid plan");
            int revision=Integer.parseInt(fields[1]),x=Integer.parseInt(fields[2]),y=Integer.parseInt(fields[3]),z=Integer.parseInt(fields[4]);
            int turns=Integer.parseInt(fields[5]),commit=Integer.parseInt(fields[6]);
            if(revision<1 || turns<0 || turns>3 || commit<0 || commit>1
                    || Math.abs((long)x)>30_000_000 || Math.abs((long)y)>30_000_000 || Math.abs((long)z)>30_000_000)
                throw new IllegalArgumentException("Placement outside supported bounds");
            return new Request(fields[0],revision,x,y,z,turns,commit==1);
        }
    }
    public static int[] pivot(List<ScriptRecord.BlockChange> cells) {
        if(cells.isEmpty())throw new IllegalArgumentException("Empty blueprint");
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,z1=x1;
        for(var c:cells){x0=Math.min(x0,c.x);x1=Math.max(x1,c.x);y0=Math.min(y0,c.y);z0=Math.min(z0,c.z);z1=Math.max(z1,c.z);}
        return new int[]{(int)Math.floorDiv((long)x0+x1,2),y0,(int)Math.floorDiv((long)z0+z1,2)};
    }
    public static List<ScriptRecord.BlockChange> apply(List<ScriptRecord.BlockChange> source,int x,int y,int z,int turns,UnaryOperator<String> rotate) {
        if(turns<0 || turns>3)throw new IllegalArgumentException("Rotation must be 0..3 quarter turns");
        int[] origin=pivot(source);var result=new ArrayList<ScriptRecord.BlockChange>(source.size());
        Map<String,String> palette=new HashMap<>();
        for(var c:source){
            int dx=Math.subtractExact(c.x,origin[0]),dz=Math.subtractExact(c.z,origin[2]);
            for(int i=0;i<turns;i++){int next=Math.negateExact(dz);dz=dx;dx=next;}
            var out=new ScriptRecord.BlockChange();out.dimension=c.dimension;
            out.x=Math.addExact(x,dx);out.y=Math.addExact(y,Math.subtractExact(c.y,origin[1]));out.z=Math.addExact(z,dz);
            out.before=c.before;out.after=palette.computeIfAbsent(c.after,rotate);out.temporary=c.temporary;
            result.add(out);
        }
        return result;
    }
}
