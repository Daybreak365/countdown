package dev.newyear.countdown.photo;

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
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 포토부스의 부속 블록 (보이지 않음, 모습은 마스터 렌더러가 그린다).
 * 벽·지붕은 꽉 찬 칸, 바닥은 낮은 단. 입구 커튼은 평소엔 지나갈 수 있지만, 촬영 화면을 쓰는 동안의 그 플레이어에게는
 * 벽처럼 막혀서 이동키로는 밖으로 나가지 못한다(나가기 버튼으로만 나간다). 그래서 충돌 모양이 엔티티마다 다르다(dynamicShape).
 * 커튼·정면을 밖에서 우클릭하면 안으로 들어가고, 안에서 아무 곳이나 우클릭하면 촬영 화면이 열린다.
 */
public class PhotoBoothPartBlock extends Block {
    public static final IntegerProperty OX = IntegerProperty.create("ox", 0, PhotoBoothLayout.MAX_X - PhotoBoothLayout.MIN_X);
    public static final IntegerProperty OY = IntegerProperty.create("oy", 0, PhotoBoothLayout.MAX_Y);
    public static final IntegerProperty OZ = IntegerProperty.create("oz", 0, PhotoBoothLayout.MAX_Z - PhotoBoothLayout.MIN_Z);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    /** 실내 바닥: 2px 높이의 단. */
    public static final VoxelShape FLOOR = Block.box(0, 0, 0, 16, 2, 16);

    public PhotoBoothPartBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OX, OY, OZ, FACING);
    }

    public static int lx(BlockState s) { return s.getValue(OX) + PhotoBoothLayout.MIN_X; }
    public static int ly(BlockState s) { return s.getValue(OY); }
    public static int lz(BlockState s) { return s.getValue(OZ) + PhotoBoothLayout.MIN_Z; }

    public static PhotoBoothLayout.Kind kind(BlockState state) {
        return PhotoBoothLayout.kind(lx(state), ly(state), lz(state));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return kind(state) == PhotoBoothLayout.Kind.FLOOR ? FLOOR : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return switch (kind(state)) {
            case CURTAIN -> context instanceof EntityCollisionContext ec && ec.getEntity() != null && PhotoBooth.LOCKED.test(ec.getEntity())
                    ? Shapes.block() : Shapes.empty();
            case FLOOR -> FLOOR;
            default -> Shapes.block();
        };
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(PhotoBooth.BOOTH_ITEM);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        BlockPos master = PhotoBoothLayout.masterOf(pos, state);
        if (world.getBlockEntity(master) instanceof PhotoBoothBlockEntity be && player instanceof ServerPlayer sp) {
            be.use(sp, PhotoBoothLayout.isEntrance(lx(state), ly(state), lz(state)));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        PhotoBoothLayout.collapseByPlayer(world, PhotoBoothLayout.masterOf(pos, state), state.getValue(FACING), player);
        return super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        PhotoBoothLayout.collapse(world, PhotoBoothLayout.masterOf(pos, state), state.getValue(FACING), true);
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
