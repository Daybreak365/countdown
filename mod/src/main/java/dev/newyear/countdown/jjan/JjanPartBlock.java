package dev.newyear.countdown.jjan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 짠 테이블 부속 블록 (보이지 않음). 자신이 구조물의 어느 칸인지(ox, oy, oz)와 방향을 상태에 담아 기준 블록을 찾는다.
 * 충돌·선택 모양은 테이블 정의의 외형 조각을 이 칸으로 잘라 만든다(상판·다리·의자 좌판). 상호작용은 기준 블록 엔티티로 넘긴다.
 */
public class JjanPartBlock extends Block {
    public static final IntegerProperty OX = IntegerProperty.create("ox", 0, JjanLayout.OFF * 2);
    public static final IntegerProperty OY = IntegerProperty.create("oy", 0, 1);
    public static final IntegerProperty OZ = IntegerProperty.create("oz", 0, JjanLayout.OFF * 2);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public final JjanTables.Def def;

    public JjanPartBlock(Properties settings, JjanTables.Def def) {
        super(settings);
        this.def = def;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OX, OY, OZ, FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return def.shape(state.getValue(OX) - JjanLayout.OFF, state.getValue(OY), state.getValue(OZ) - JjanLayout.OFF, state.getValue(FACING));
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(Jjan.ITEMS.get(def.index));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        BlockPos master = JjanLayout.masterOf(pos, state);
        if (world.getBlockEntity(master) instanceof JjanTableEntity be && player instanceof ServerPlayer sp) be.interact(sp, hit.getLocation());
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        JjanLayout.collapseByPlayer(world, JjanLayout.masterOf(pos, state), state.getValue(FACING), def, player);
        return super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        JjanLayout.collapse(world, JjanLayout.masterOf(pos, state), state.getValue(FACING), def, true);
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
