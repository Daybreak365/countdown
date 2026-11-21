package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.api.McUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * "원래 세계"(자연 지형)와 "누가 지은 것"을 가른다. 자연 지형은 소원으로 깎고 쌓고 바꿀 수 있지만,
 * 지은 것(가공한 블록, 내용물이 있는 블록)과 바로 아래의 기초는 건드리지 않는다.
 * <ul>
 *   <li>자연: 돌·흙·모래·자갈·점토·눈·얼음·테라코타·네더/엔드 지형, 광석, 꽃·버섯·선인장·대나무·덩굴,
 *       플레이어가 놓지 않은 나뭇잎(persistent=false) 과 그런 잎이 달린 원목, 물.</li>
 *   <li>지은 것으로 본다: 판자·벽돌·유리·양털·콘크리트처럼 가공한 블록, 놓은 나뭇잎, 잎이 없는 원목, 상자 같은 블록 엔티티.
 *       마을·폐광 같은 자연 구조물도 여기에 걸려 보호된다.</li>
 * </ul>
 */
public final class NaturalBlocks {
    private NaturalBlocks() {}

    private static final Set<String> EXTRA = Set.of("minecraft:gravel", "minecraft:clay", "minecraft:mud", "minecraft:sandstone", "minecraft:red_sandstone",
            "minecraft:snow_block", "minecraft:powder_snow", "minecraft:end_stone", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:vine", "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block",
            "minecraft:mushroom_stem", "minecraft:dripstone_block", "minecraft:pointed_dripstone", "minecraft:amethyst_block", "minecraft:calcite",
            "minecraft:sculk", "minecraft:moss_block", "minecraft:big_dripleaf", "minecraft:small_dripleaf",
            "minecraft:glow_lichen", "minecraft:hanging_roots", "minecraft:rooted_dirt", "minecraft:mycelium", "minecraft:podzol", "minecraft:mangrove_roots",
            "minecraft:muddy_mangrove_roots", "minecraft:lily_pad", "minecraft:sweet_berry_bush", "minecraft:cocoa");

    /** 절대 바꾸지 않는 자연 블록 (얻을 수 없거나 세계 구조상 중요). */
    private static final Set<String> NEVER = Set.of("minecraft:bedrock", "minecraft:budding_amethyst", "minecraft:end_portal_frame", "minecraft:reinforced_deepslate",
            "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault");

    public static boolean isNatural(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir() || s.liquid()) return true;
        if (s.hasBlockEntity()) return false;
        String id = McUtil.blockId(s);
        if (NEVER.contains(id)) return false;
        if (s.getBlock() instanceof LeavesBlock) return s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
        if (s.is(BlockTags.LOGS)) return naturalLeavesNear(level, pos);
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.BADLANDS_TERRACOTTA) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.NYLIUM) || s.is(BlockTags.CORAL_BLOCKS)
                || s.is(BlockTags.MOSS_REPLACEABLE) || s.is(BlockTags.LUSH_GROUND_REPLACEABLE) || s.is(BlockTags.SCULK_REPLACEABLE)) return true;
        if (id.endsWith("_ore")) return true;
        if (EXTRA.contains(id)) return true;
        return s.canBeReplaced();
    }

    /** Follow connected upright trunk blocks so tall trees are not mistaken for log cabins. */
    private static boolean naturalLeavesNear(ServerLevel level, BlockPos pos) {
        int height=0;
        while(height<32 && level.isLoaded(pos.above(height+1)) && level.getBlockState(pos.above(height+1)).is(BlockTags.LOGS)) height++;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, height+4, 3))) {
            if (!level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }

    /** A neighboring wall no longer protects all nearby terrain; retain only direct foundation support. */
    public static boolean supportsBuild(ServerLevel level, BlockPos pos, ScriptInstance inst) {
        BlockState current=level.getBlockState(pos);
        if(current.canBeReplaced() || current.getBlock() instanceof LeavesBlock || current.is(BlockTags.LOGS)
                || current.getCollisionShape(level,pos).isEmpty()) return false;
        BlockPos above=pos.above();
        if(!level.isLoaded(above) || inst.ownsBlock(McUtil.dimId(level),above.getX(),above.getY(),above.getZ())) return false;
        BlockState support=level.getBlockState(above);
        return !support.isAir() && !support.liquid() && !support.canBeReplaced() && !isNatural(level,above,support);
    }
}
