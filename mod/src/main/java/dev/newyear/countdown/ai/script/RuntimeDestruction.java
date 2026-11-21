package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import java.util.*;

/** Actual Minecraft explosions, bounded before execution; native drops/fire/chain reactions are not undoable. */
public final class RuntimeDestruction {
    private RuntimeDestruction() {}
    private static final Map<Object,ExplosionBudget.Window> WINDOWS=new WeakHashMap<>();
    public static void validatePower(double power) {
        if(!Double.isFinite(power)||power<.5||power>4)throw new IllegalArgumentException("Explosion power must be .5..4");
    }
    public static int reservedBlocks(ScriptRecord record) {
        return ExplosionBudget.used(record);
    }
    private static long remaining(ScriptInstance inst){
        return ExplosionBudget.remaining(inst.rec,inst.rec.blocks.size()+(inst.dry?(long)inst.planned.size():0),
                inst.dry?inst.priorPendingBlocks:WorldEditQueue.remainingBlocks(inst.rec),AiConfig.get().scriptMaxBlocks);
    }
    private static ExplosionBudget.Window window(ScriptInstance inst){
        return inst.dry?inst.dryExplosionWindow:WINDOWS.computeIfAbsent(WishEngine.get(),k->new ExplosionBudget.Window());
    }
    public static Values.JsObject budget(ScriptInstance inst){
        return new Values.JsObject().put("limit",(double)AiConfig.get().scriptMaxBlocks)
                .put("explosionBlocksUsed",(double)reservedBlocks(inst.rec)).put("remainingBlocks",(double)remaining(inst))
                .put("refills",false).put("retryAfterTicks",(double)window(inst).retryAfter(inst.currentTick()));
    }
    private record Area(ServerLevel level,Set<Long> allowed,Map<BlockPos,BlockState> before,int potentialBlocks) {}
    private static Area inspect(ScriptInstance inst,Interpreter in,double[] at,double power,Values.JsObject opts) {
        try {validatePower(power);}catch(IllegalArgumentException ex){throw in.error(ex.getMessage());}
        if(!Policy.destruction(inst).explosions())throw in.error("Real explosions require destructive wish permissions");
        AiConfig cfg=AiConfig.get();
        if(!cfg.allowBlockPlacement || inst.rec.autonomous)throw in.error("Destructive world operations are disabled for this script");
        ServerLevel level=McUtil.level(WishEngine.get().server(),Args.optStr(opts,"dimension",inst.rec.dimension));
        if(level==null)throw in.error("Unknown dimension");
        Policy.checkRange(in,inst,at);
        int radius=(int)Math.ceil(power*2+1);BlockPos center=McUtil.blockPos(at);
        for(int x:new int[]{-radius,radius})for(int y:new int[]{-radius,radius})for(int z:new int[]{-radius,radius})
            Policy.checkRange(in,inst,new double[]{at[0]+x,at[1]+y,at[2]+z});
        boolean fire=Args.optBool(opts,"fire",false);
        Set<Long> allowed=new HashSet<>();Map<BlockPos,BlockState> before=new HashMap<>();int potential=0;
        for(BlockPos pos:BlockPos.betweenClosed(center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius))){
            if(!level.isLoaded(pos)||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos))
                throw in.error("The entire explosion area must be loaded and inside world bounds");
            BlockState state=level.getBlockState(pos);
            if(Policy.protectedBlock(McUtil.blockId(state)))continue;
            RuntimeConstruction.guard(inst,McUtil.dimId(level),pos,"minecraft:air");
            allowed.add(pos.asLong());
            if(!state.isAir() || fire){before.put(pos.immutable(),state);potential++;}
        }
        in.budget.allocate(allowed.size()+before.size());
        return new Area(level,allowed,before,potential);
    }
    /** Read-only conservative admission check. No explosions, reservations or world RNG consumption. */
    public static Values.JsObject check(ScriptInstance inst,Interpreter in,double[] at,double power,Values.JsObject opts){
        inst.explosionChecks++;
        var out=budget(inst).put("allowed",false).put("estimate","upper_bound");
        try{
            var area=inspect(inst,in,at,power,opts);long left=remaining(inst),retry=window(inst).retryAfter(inst.currentTick());
            out.put("potentialBlocks",(double)area.potentialBlocks);
            if(retry>0)return out.put("reason","Server explosion work window is full; retry after "+retry+" ticks");
            if(area.potentialBlocks>left)return out.put("reason",ExplosionBudget.exhausted(area.potentialBlocks,left));
            return out.put("allowed",true).put("reason","");
        }catch(ScriptError ex){if(ex.kind==ScriptError.Kind.BUDGET)throw ex;return out.put("reason",ex.getMessage());}
    }
    public static Object explode(ScriptInstance inst,Interpreter in,double[] at,double power,Values.JsObject opts) {
        inst.explosionCalls++;
        var area=inspect(inst,in,at,power,opts);var level=area.level;var allowed=area.allowed;var before=area.before;
        long left=remaining(inst);
        if(inst.dry){
            if(area.potentialBlocks>left)throw in.error(ExplosionBudget.exhausted(area.potentialBlocks,left));
            window(inst).claim(inst.currentTick());
            // Each call consumes its own upper bound. Deduplicated preview cells are not a lifetime ledger.
            ExplosionBudget.charge(inst.rec,area.potentialBlocks);inst.approximateBuild=true;
            inst.effect("native explosion power="+power+"; conservative block estimate="+area.potentialBlocks+"; remaining="+remaining(inst));
            return new Values.JsObject().put("executed",false).put("preview",true).put("potentialBlocks",(double)area.potentialBlocks)
                    .put("remainingBlocks",(double)remaining(inst));
        }
        window(inst).claim(inst.currentTick());
        var reservation=new ExplosionBudget.Reservation(left);
        AiConfig cfg=AiConfig.get();
        boolean damage=Args.optBool(opts,"damage",true),fire=Args.optBool(opts,"fire",false);
        ExplosionDamageCalculator calculator=new ExplosionDamageCalculator(){
            private boolean affect(Entity entity){
                if(!damage)return false;
                if(entity instanceof Player)return entity.getUUID().toString().equals(inst.rec.owner)||!cfg.requireConsentForOthers||Policy.hasConsent(inst,entity.getUUID().toString());
                if(entity instanceof OwnableEntity pet && pet.getOwnerReference()!=null){
                    String owner=pet.getOwnerReference().getUUID().toString();return owner.equals(inst.rec.owner)||Policy.hasConsent(inst,owner);
                }
                return true;
            }
            @Override public boolean shouldDamageEntity(Explosion explosion,Entity entity){return affect(entity);}
            @Override public float getKnockbackMultiplier(Entity entity){return affect(entity)?super.getKnockbackMultiplier(entity):0;}
            @Override public Optional<Float> getBlockExplosionResistance(Explosion explosion,BlockGetter world,BlockPos pos,BlockState state,FluidState fluid){
                return allowed.contains(pos.asLong())?super.getBlockExplosionResistance(explosion,world,pos,state,fluid):Optional.of(Float.MAX_VALUE);
            }
            @Override public boolean shouldBlockExplode(Explosion explosion,BlockGetter world,BlockPos pos,BlockState state,float strength){
                if(!allowed.contains(pos.asLong()) || Policy.protectedBlock(McUtil.blockId(state)))return false;
                // Vanilla gathers this list before hurtEntities/interactWithBlocks. An over-budget blast aborts there.
                // Air costs nothing unless fire may replace it. Multiple rays through one cell reserve it once.
                if(!state.isAir() || fire)reservation.include(pos.asLong());
                return true;
            }
        };
        Entity source=inst.rec.owner==null?null:WishEngine.get().player(UUID.fromString(inst.rec.owner));
        long changed;
        try{level.explode(source,null,calculator,at[0],at[1],at[2],(float)power,fire,Level.ExplosionInteraction.BLOCK);}
        finally{
            // Include changes made before a downstream native error; never permanently charge untouched neighbors.
            changed=before.entrySet().stream().filter(e->!e.getValue().equals(level.getBlockState(e.getKey()))).count();
            ExplosionBudget.charge(inst.rec,changed);
        }
        return new Values.JsObject().put("executed",true).put("affectedBlocks",(double)changed)
                .put("remainingBlocks",(double)remaining(inst)).put("rollback",false);
    }
}
