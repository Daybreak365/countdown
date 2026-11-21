package dev.newyear.countdown.ai.ui;

import com.google.gson.*;
import dev.newyear.countdown.ai.*;
import dev.newyear.countdown.ai.agent.*;
import dev.newyear.countdown.ai.script.*;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Server validates every moved cell; clients can transform an owned design, never supply new blocks. */
public final class BuildPlacement {
    private BuildPlacement() {}
    private static final Map<UUID,Long> CHECKS=new WeakHashMap<>(),COMMITS=new WeakHashMap<>();
    public static void send(WishEngine eng,ServerPlayer player,Wish wish,String title,int seconds) {
        var plan=wish.buildPlan;
        if(!eng.ui().hasModClient(player)){eng.ui().blockPreview(player,plan.id,title,plan.cells,seconds);return;}
        // Send the entire design, including plans larger than the old 2048-cell preview limit.
        int parts=(plan.cells.size()+127)/128;
        for(int part=0;part<parts;part++){
            var packet=eng.ui().previewJson(plan.id,title,plan.cells.subList(part*128,Math.min(plan.cells.size(),(part+1)*128)),0);
            packet.addProperty("op","build_hologram");packet.addProperty("revision",plan.revision);
            packet.addProperty("wish",wish.id);packet.addProperty("part",part);packet.addProperty("parts",parts);
            packet.addProperty("editable",plan.canPosition() && player.getUUID().toString().equals(wish.player));packet.addProperty("placement_required",plan.placementRequired);
            eng.ui().send(player,packet);
        }
    }
    public static void sync(WishEngine eng,ServerPlayer player) {
        Wish wish=eng.wishes().openWishOf(player);
        if(wish!=null && wish.buildPlan!=null && wish.buildPlan.unfinished())send(eng,player,wish,"건축 설계",0);
    }
    public static void handle(WishEngine eng,ServerPlayer player,String encoded) {
        BuildTransform.Request request;
        try{request=BuildTransform.Request.parse(encoded);}catch(IllegalArgumentException ex){return;}
        Map<UUID,Long> rate=request.commit()?COMMITS:CHECKS;
        long now=eng.tick(),last=rate.getOrDefault(player.getUUID(),Long.MIN_VALUE/2);
        if(now>=last && now-last<(request.commit()?10:5))return;
        rate.put(player.getUUID(),now);
        JsonObject response=new JsonObject();response.addProperty("op","build_site_result");response.addProperty("request",encoded);
        response.addProperty("valid",false);response.addProperty("committed",false);
        try {
            Wish wish=eng.state().wishes.values().stream().filter(w->player.getUUID().toString().equals(w.player)
                    && !w.state.terminal() && w.buildPlan!=null && request.plan().equals(w.buildPlan.id)).findFirst().orElseThrow(()->new IllegalArgumentException("현재 설계를 다시 불러오세요."));
            var old=wish.buildPlan;
            if(old.revision!=request.revision() || !old.canPosition())throw new IllegalArgumentException("이미 수락했거나 변경된 설계입니다. 현재 설계를 확인하세요.");
            if(!old.dimension.equals(McUtil.dimId(player.level())))throw new IllegalArgumentException("설계와 같은 차원에서 위치를 지정하세요.");
            if(!player.getAbilities().mayBuild)throw new IllegalArgumentException("현재 게임 모드에서는 건축할 수 없습니다.");
            var cells=BuildTransform.apply(old.cells,request.x(),request.y(),request.z(),request.turns(),s->BuildTransform.rotateState(s,request.turns()));
            if(cells.size()>Math.min(4096,AiConfig.get().scriptMaxBlocks))throw new IllegalArgumentException("설계가 서버의 건축 한도를 초과합니다.");
            var record=new ScriptRecord();record.id="site-check";record.owner=wish.player;record.dimension=old.dimension;
            record.anchor=new double[]{player.getX(),player.getY(),player.getZ()};
            var dry=new ScriptInstance(record,true);var level=player.level();
            JsonArray blocked=new JsonArray();int failures=0;String reason="";
            for(int i=0;i<cells.size();i++){
                var c=cells.get(i);var pos=new BlockPos(c.x,c.y,c.z);
                try {
                    if(!level.isLoaded(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
                        throw new IllegalArgumentException("로드된 월드 경계 안에 배치하세요.");
                    if(!level.mayInteract(player,pos))throw new IllegalArgumentException("보호된 위치입니다.");
                    var target=McUtil.parseBlockString(c.after);if(target==null)throw new IllegalArgumentException("알 수 없는 블록입니다.");
                    c.before=McUtil.blockString(level.getBlockState(pos));
                    Policy.checkRange(dry.in,dry,new double[]{c.x+.5,c.y+.5,c.z+.5});
                    if(BuildPlan.matchesPlaced(c.after,c.before))continue;
                    Policy.checkBlock(dry.in,dry,level,pos,target,true);
                    if(RuntimeConstruction.occupied(level,pos,target))throw new IllegalArgumentException("사람이나 생물이 건축할 자리에 있습니다.");
                }catch(RuntimeException ex){
                    if(failures++==0)reason=pos.toShortString()+": "+ex.getMessage();
                    if(blocked.size()<4096)blocked.add(i);
                }
            }
            response.add("blocked",blocked);response.addProperty("blocked_count",failures);
            response.addProperty("valid",failures==0);response.addProperty("reason",failures==0?"이 위치에 배치할 수 있습니다.":reason);
            if(request.commit() && failures==0){
                var next=BuildPlan.revision(old,cells);next.anchor=record.anchor.clone();next.approvalReasons=new ArrayList<>(old.approvalReasons);
                next.placementRequired=false;
                wish.buildPlan=next;
                if(wish.contract!=null){wish.contract.buildPlanId=next.id;wish.contract.buildPlanRevision=next.revision;wish.contract.revision++;}
                eng.ui().cancelInteractionsOfWish(wish.id);
                eng.ui().clearBlockPreview(player,old.id);
                BuildPreview.showSaved(eng,player,wish,"확정한 건축 위치",0);
                eng.ui().notify(player,"건축 위치 확정",next.bounds()+" · 계약 수락 후 이 위치에 시공합니다.","success",6);
                if(wish.contract!=null && !wish.contract.accepted)eng.ui().contract(player,wish,eng.wishes().aiResponder(wish.id,"contract",wish.contract.terms));
                else eng.agent().submit(new TurnRequest(wish.id,player.getUUID(),TurnRequest.Kind.UI,
                        "[BUILD SITE CONFIRMED] Player positioned the hologram and confirmed "+next.summary()+". Use this exact plan; propose the building agreement and then construction.start. Do not regenerate the design or coordinates.",null));
                eng.writeAsync();response.addProperty("committed",true);
            }
        }catch(RuntimeException ex){response.addProperty("reason",ex.getMessage()==null?"위치를 확인하지 못했습니다.":ex.getMessage());}
        eng.ui().send(player,response);
    }
}
