package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 스크립트의 모든 세계 변경은 여기서 권한·범위·수량을 검사한다.
 * 원칙: 소원을 빈 사람에게는 자유롭게, 다른 사람에게 해가 되는 일은 동의가 있을 때만,
 * 세계(건축물)는 비어 있는 자리만, 모든 것은 수량과 범위 안에서.
 */
public final class Policy {
    private Policy() {}

    /** 절대 지급하지 않는 아이템. */
    private static final Set<String> FORBIDDEN_ITEMS = Set.of("minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:structure_block",
            "minecraft:structure_void", "minecraft:jigsaw", "minecraft:barrier", "minecraft:debug_stick", "minecraft:light",
            "minecraft:bedrock", "minecraft:end_portal_frame", "minecraft:knowledge_book", "minecraft:test_block",
            "minecraft:test_instance_block", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault");

    /** 소환할 수 없는 엔티티. */
    private static final Set<String> FORBIDDEN_ENTITIES = Set.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:warden",
            "minecraft:elder_guardian", "minecraft:tnt", "minecraft:tnt_minecart", "minecraft:end_crystal", "minecraft:giant",
            "minecraft:lightning_bolt", "minecraft:wither_skull", "minecraft:fireball", "minecraft:dragon_fireball",
            "minecraft:command_block_minecart", "minecraft:falling_block", "minecraft:player", "minecraft:fishing_bobber",
            "minecraft:area_effect_cloud", "minecraft:marker", "minecraft:evoker_fangs", "minecraft:ravager");

    private static final int MAX_HOSTILE_PER_SCRIPT = 4;

    private static ScriptError deny(String msg) {
        return new ScriptError(ScriptError.Kind.PERMISSION, msg, 0, 0);
    }

    public static int maxGiveCount() {
        return 256;
    }
    public static dev.newyear.countdown.ai.WishFulfillment fulfillment(ScriptInstance inst) {
        return inst.rec.autonomous ? dev.newyear.countdown.ai.WishFulfillment.BALANCED : AiConfig.get().fulfillment();
    }
    public static dev.newyear.countdown.ai.WishDestruction destruction(ScriptInstance inst) {
        return inst.rec.autonomous ? dev.newyear.countdown.ai.WishDestruction.RESTRICTED : AiConfig.get().destruction();
    }
    public static int maxGiveCount(ScriptInstance inst) { return fulfillment(inst).maxGive; }
    public static int maxEffectSeconds(ScriptInstance inst) { return fulfillment(inst).maxEffectSeconds; }
    public static int maxAmplifier(ScriptInstance inst) { return fulfillment(inst).maxAmplifier; }
    public static boolean protectedBlock(String id) {
        return dev.newyear.countdown.ai.compat.RetiredMaterials.isRetired(id)
                || id.contains("command_block") || id.equals("minecraft:bedrock") || id.contains("structure")
                || id.equals("minecraft:barrier") || id.contains("portal") || id.equals("minecraft:jigsaw")
                || id.equals("minecraft:test_block") || id.equals("minecraft:test_instance_block");
    }
    public static boolean destructiveEntityAllowed(String id,dev.newyear.countdown.ai.WishDestruction profile) {
        return profile.explosions() && Set.of("minecraft:ender_dragon","minecraft:wither","minecraft:warden","minecraft:elder_guardian",
                "minecraft:tnt","minecraft:tnt_minecart","minecraft:end_crystal","minecraft:giant","minecraft:lightning_bolt",
                "minecraft:wither_skull","minecraft:fireball","minecraft:dragon_fireball","minecraft:area_effect_cloud",
                "minecraft:evoker_fangs","minecraft:ravager").contains(id);
    }

    public static int maxEffectSeconds() {
        return 3600;
    }

    public static int maxAmplifier() {
        return 4;
    }

    // ---------------------------------------------------------------- 아이템

    /** 스크립트별 최근 지급량 (1시간 창). 모의 실행은 세지 않는다. */
    private static final Map<String, long[]> GIVE_WINDOW = new HashMap<>();

    public static void checkGive(Interpreter in, ScriptInstance inst, ItemStack proto, int count) {
        String id = McUtil.itemId(proto);
        if (dev.newyear.countdown.ai.compat.RetiredMaterials.isRetired(id) || FORBIDDEN_ITEMS.contains(id) || id.endsWith("_spawn_egg") && id.contains("wither") && !destruction(inst).explosions()) {
            throw deny("Item " + id + " cannot be given by wish scripts");
        }
        if (inst.dry) return;
        long now = System.currentTimeMillis();
        long[] w = GIVE_WINDOW.computeIfAbsent(inst.rec.id, k -> new long[]{now, 0});
        if (now - w[0] > 3_600_000L) { w[0] = now; w[1] = 0; }
        if (!fulfillment(inst).allowsGive(w[1],count)) throw deny("This script has given too many items this hour (limit " + fulfillment(inst).hourlyGive + ")");
    }

    public static void recordGive(ScriptInstance inst, ItemStack proto, int given) {
        long[] w = GIVE_WINDOW.get(inst.rec.id);
        if (w != null) w[1] += given;
    }

    // ---------------------------------------------------------------- 플레이어

    /**
     * 다른 플레이어에게 해가 되는 조작(아이템 회수, 해로운 효과, 순간이동, 피해)은 소원 주인 본인이거나
     * 그 플레이어가 이 소원에 동의한 경우에만 허용한다.
     */
    public static void checkAffect(Interpreter in, ScriptInstance inst, PlayerRef target, String action, boolean harmful) {
        if (!harmful) return;
        String t = target.id.toString();
        if (t.equals(inst.rec.owner)) return;
        if (!AiConfig.get().requireConsentForOthers) return;
        if (hasConsent(inst, t)) return;
        throw deny("Cannot " + action + " " + target.name + " without consent. Ask first with ui.consent(player, text, (ok) => {...})");
    }

    public static boolean hasConsent(ScriptInstance inst, String uuid) {
        WishEngine e = WishEngine.get();
        if (e == null || inst.rec.wishId == null) return false;
        Wish w = e.state().wishes.get(inst.rec.wishId);
        return w != null && w.consents.contains(uuid);
    }

    public static void checkTeleport(Interpreter in, ScriptInstance inst, ServerPlayer p, double[] to) {
        double max = Math.max(16, AiConfig.get().scriptMaxRadius * 2.0);
        if (p.distanceToSqr(to[0], to[1], to[2]) > max * max) throw deny("Teleport too far (max " + (int) max + " blocks)");
        ServerLevel level = (ServerLevel) p.level();
        BlockPos feet = BlockPos.containing(to[0], to[1], to[2]);
        if (!level.isLoaded(feet)) throw deny("Teleport target is not loaded");
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
            throw deny("Teleport target is inside blocks; pick an open spot (use world.surface(x, z))");
        }
    }

    // ---------------------------------------------------------------- 범위·엔티티

    /** 스크립트의 기준 위치(생성 당시 소원 주인 위치) 또는 주인의 현재 위치에서 일정 거리 안이어야 한다. */
    public static void checkRange(Interpreter in, ScriptInstance inst, double[] p) {
        double r = AiConfig.get().scriptMaxRadius;
        if (inst.rec.anchor != null && dist2(inst.rec.anchor, p) <= r * r) return;
        if (inst.rec.owner != null) {
            WishEngine e = WishEngine.get();
            if (e != null) {
                ServerPlayer op = e.player(java.util.UUID.fromString(inst.rec.owner));
                if (op != null && op.distanceToSqr(p[0], p[1], p[2]) <= r * r) return;
            }
        }
        if (inst.rec.anchor == null && inst.rec.owner == null) return;
        throw deny("Position " + (int) p[0] + "," + (int) p[1] + "," + (int) p[2] + " is outside this script's area (" + (int) r + " blocks around the wish location or the owner)");
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    public static void checkControl(Interpreter in, ScriptInstance inst, EntityRef r) {
        if (r.fake) return;
        Entity e = r.live();
        if (e == null) return;
        if (e instanceof OwnableEntity own && own.getOwnerReference() != null && !EntityRef.owned(inst, r)) {
            String ownerId = own.getOwnerReference().getUUID().toString();
            if (!ownerId.equals(inst.rec.owner) && !hasConsent(inst, ownerId)) {
                throw deny("That " + r.type + " is someone else's pet; it can't be controlled without their consent");
            }
        }
    }

    public static void checkSpawn(Interpreter in, ScriptInstance inst, EntityType<?> type, double[] at) {
        String id = McUtil.entityId(type);
        if (FORBIDDEN_ENTITIES.contains(id) && !destructiveEntityAllowed(id,destruction(inst))) throw deny("Entity " + id + " requires destructive wish permissions");
        if (destructiveEntityAllowed(id,destruction(inst))) {
            if (!AiConfig.get().allowBlockPlacement) throw deny("Destructive entities require world changes to be enabled");
            if (inst.rec.autonomous) throw deny("Autonomous events cannot spawn destructive entities");
        }
        checkRange(in, inst, at);
        AiConfig cfg = AiConfig.get();
        int mine = inst.dry ? inst.dryEntitySeq : liveCount(inst);
        if (mine >= cfg.scriptMaxEntities) throw deny("This script already has " + mine + " entities (max " + cfg.scriptMaxEntities + "). Remove some first");
        if (!inst.dry) {
            WishEngine e = WishEngine.get();
            if (e != null && e.scripts().totalEntities() >= cfg.globalMaxScriptEntities) throw deny("Server-wide script entity limit reached");
        }
        if (type.getCategory() == MobCategory.MONSTER) {
            int hostile = 0;
            if (!inst.dry) {
                for (String u : inst.rec.entities) {
                    EntityRef er = new EntityRef(java.util.UUID.fromString(u), "?");
                    Entity en = er.live();
                    if (en != null && en.getType().getCategory() == MobCategory.MONSTER) hostile++;
                }
            }
            int hostileLimit=destruction(inst).explosions()?cfg.scriptMaxEntities:MAX_HOSTILE_PER_SCRIPT;
            if (hostile >= hostileLimit) throw deny("At most " + hostileLimit + " hostile mobs per script");
        }
    }

    public static int liveCount(ScriptInstance inst) {
        int n = 0;
        for (String u : inst.rec.entities) {
            Entity en = new EntityRef(java.util.UUID.fromString(u), "?").live();
            if (en != null && en.isAlive()) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 블록

    public static void checkBlock(Interpreter in, ScriptInstance inst, ServerLevel level, BlockPos pos, BlockState newState) {
        checkBlock(in, inst, level, pos, newState, false);
    }

    /**
     * 블록 변경 허가. 빈 공간은 언제나, 자연 지형(돌·흙·모래·광석·자연 나무·물…)은 worldEditMode "natural"(기본)에서 바꿀 수 있다.
     * 기본 설정에서는 건축물·직접 기초·컨테이너를 보호한다. 별도 파괴 권한으로 건축물·컨테이너 파괴를 허용한다.
     * gradual=true 는 작업 실행기가 속도를 정하므로 틱당 한도를 세지 않는다.
     */
    public static void checkBlock(Interpreter in, ScriptInstance inst, ServerLevel level, BlockPos pos, BlockState newState, boolean gradual) {
        AiConfig cfg = AiConfig.get();
        if (!cfg.allowBlockPlacement) throw deny("Block changes are disabled on this server");
        if (inst.rec.autonomous && !cfg.autonomyMayModifyWorld) throw deny("Autonomous events may not modify blocks on this server");
        double[] p = {pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5};
        checkRange(in, inst, p);
        if (!level.isLoaded(pos)) throw deny("That position is not loaded");
        if (!level.getWorldBorder().isWithinBounds(pos)) throw deny("Outside world border");
        var construction=RuntimeConstruction.plan(inst.rec);
        if(!inst.dry && construction!=null && java.util.Objects.equals(construction.runnerScriptId,inst.rec.id)){
            var owner=inst.rec.owner==null?null:WishEngine.get().player(java.util.UUID.fromString(inst.rec.owner));
            if(owner==null || !owner.getAbilities().mayBuild || !level.mayInteract(owner,pos))
                throw deny("The owner cannot build at this position");
        }
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) throw deny("Outside build height");
        BlockState cur = inst.dry && inst.blueprintPreview ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
        if (protectedBlock(McUtil.blockId(cur))) throw deny("Protected administrative/unbreakable block at " + pos.toShortString());
        boolean mine = inst.ownsBlock(McUtil.dimId(level), pos.getX(), pos.getY(), pos.getZ());
        var profile=destruction(inst);
        String mode = profile.editMode(cfg.editMode());
        if (!mine) {
            if (cur.hasBlockEntity() && !profile.containers()) throw deny("Container/block-entity destruction requires destructive wish permissions at " + pos.toShortString());
            boolean free = cur.isAir() || cur.canBeReplaced() || cur.liquid();
            if (!free) {
                switch (mode) {
                    case "any" -> { }
                    case "natural" -> {
                        if (!NaturalBlocks.isNatural(level, pos, cur)
                                && !(cur.is(net.minecraft.tags.BlockTags.LOGS) && RuntimeConstruction.verifiedNaturalLog(inst,McUtil.dimId(level),pos,McUtil.blockString(cur)))) {
                            throw deny("Block at " + pos.toShortString() + " is " + McUtil.blockId(cur) + ", which looks built by someone; scripts may change empty space and natural terrain only");
                        }
                        if (NaturalBlocks.supportsBuild(level, pos, inst)
                                && !newState.isFaceSturdy(level,pos,net.minecraft.core.Direction.UP)) {
                            throw deny("Block at " + pos.toShortString() + " supports an existing build; keep a solid foundation under it");
                        }
                    }
                    default -> throw deny("Block at " + pos.toShortString() + " is " + McUtil.blockId(cur) + "; this server only lets scripts place blocks in empty space (air, water, grass) or change blocks they placed themselves");
                }
            }
        }
        if (newState.hasBlockEntity()) {
            String id = McUtil.blockId(newState);
            if (!id.endsWith("_sign") && !id.endsWith("chest") && !id.endsWith("barrel") && !id.contains("lantern") && !id.contains("candle")) {
                throw deny("Block " + id + " cannot be placed by scripts");
            }
        }
        String nid = McUtil.blockId(newState);
        if (protectedBlock(nid) || nid.equals("minecraft:spawner") || nid.equals("minecraft:tnt") && !profile.explosions()) {
            throw deny("Block " + nid + " cannot be placed by scripts");
        }
        // 번지는 불·용암은 주변 건물을 태울 수 있다
        if (!profile.hazards(cfg.editMode()) && (nid.equals("minecraft:lava") || nid.equals("minecraft:fire") || nid.equals("minecraft:soul_fire"))) {
            throw deny("Block " + nid + " can spread and burn builds; use magma_block, campfire light or particles instead");
        }
        long used = inst.rec.blocks.size()+(inst.dry?(long)inst.planned.size():0)+RuntimeDestruction.reservedBlocks(inst.rec);
        if (!mine && used >= cfg.scriptMaxBlocks * (gradual ? 2 : 1)) throw deny("This script reached its block limit (" + cfg.scriptMaxBlocks + ")");
        if (gradual) return;
        long tick = WishEngine.get() == null ? 0 : WishEngine.get().tick();
        if (inst.blockTickStamp != tick) { inst.blockTickStamp = tick; inst.blocksThisTick = 0; }
        if (++inst.blocksThisTick > cfg.scriptBlocksPerTick) throw deny("Too many block changes in one tick (max " + cfg.scriptBlocksPerTick + "); use world.box/sphere/terraform for big changes, or spread them with every()");
    }
}
