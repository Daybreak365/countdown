package dev.newyear.countdown.photo;

import dev.newyear.countdown.bell.BellLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 새해 네컷 포토부스: 가로 7 x 깊이 7 x 높이 5 칸 구조물(실내 5x5) 하나가 블록 하나처럼 동작한다.
 * 구조물 좌표는 마스터 칸(실내 바닥 한가운데) 기준이고 정면(렌즈 모양 입구)이 +Z, 촬영 키오스크가 뒤쪽 벽(-Z)에 있다.
 *
 * <pre>
 *   z=-3  벽 벽 벽 키오스크 벽 벽 벽     y=0..3 벽, y=4 지붕
 *   z=-2  벽 ·  ·  ·  ·  ·  벽         · = 실내(바닥만 블록, y=1..3 은 빈 칸)
 *   z= 0  벽 ·  ·  M  ·  ·  벽         M = 마스터
 *   z= 2  벽 ·  ·  ·  ·  ·  벽
 *   z= 3  벽 벽 문 문 문 벽 벽           문: 바닥 + 커튼(y=1..2), 그 위(y=3)는 상인방
 * </pre>
 */
public final class PhotoBoothLayout {
    public static final int MIN_X = -3, MAX_X = 3, MIN_Y = 0, MAX_Y = 4, MIN_Z = -3, MAX_Z = 3;
    /** 실내 반폭(블록): 실내는 x, z 모두 -2.5..2.5. */
    public static final double INSIDE = 2.5;

    /** 칸 종류. */
    public enum Kind { MASTER, FLOOR, WALL, CURTAIN, ROOF, AIR }

    /** 실제로 블록이 놓이는 칸들 (마스터 제외). */
    public static final List<int[]> PART_CELLS = new ArrayList<>();
    private static final ThreadLocal<Boolean> COLLAPSING = ThreadLocal.withInitial(() -> false);

    /** 들어올 때 서는 자리 후보(구조물 좌표 x, z): 커튼 앞 뒷줄부터 채운다. */
    private static final double[][] SPOTS = {
            {0, 1.6}, {-1.0, 1.6}, {1.0, 1.6}, {-2.0, 1.6}, {2.0, 1.6},
            {-0.5, 0.5}, {0.5, 0.5}, {-1.5, 0.5}, {1.5, 0.5}, {0, -0.6}, {-1.2, -0.6}, {1.2, -0.6},
    };

    static {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    Kind k = kind(x, y, z);
                    if (k != Kind.MASTER && k != Kind.AIR) PART_CELLS.add(new int[]{x, y, z});
                }
            }
        }
    }

    private PhotoBoothLayout() {}

    public static Kind kind(int x, int y, int z) {
        if (x == 0 && y == 0 && z == 0) return Kind.MASTER;
        if (y == MAX_Y) return Kind.ROOF;
        if (Math.abs(x) == MAX_X || z == MIN_Z) return Kind.WALL;
        if (z == MAX_Z) {
            if (Math.abs(x) <= 1 && y <= 2) return y == 0 ? Kind.FLOOR : Kind.CURTAIN;
            return Kind.WALL;
        }
        if (y == 0) return Kind.FLOOR;
        return Kind.AIR;
    }

    /** 정면(렌즈 모양 입구) 칸: 밖에서 여기를 우클릭하면 부스 안으로 들어간다. */
    public static boolean isEntrance(int x, int y, int z) {
        return z == MAX_Z && Math.abs(x) <= 2 && y < MAX_Y;
    }

    public static BlockPos masterOf(BlockPos partPos, BlockState part) {
        int lx = part.getValue(PhotoBoothPartBlock.OX) + MIN_X;
        int ly = part.getValue(PhotoBoothPartBlock.OY);
        int lz = part.getValue(PhotoBoothPartBlock.OZ) + MIN_Z;
        return partPos.subtract(BellLayout.cell(BlockPos.ZERO, part.getValue(PhotoBoothPartBlock.FACING), lx, ly, lz));
    }

    /** 구조물 좌표(마스터 바닥 중심 기준, 블록 단위) → 월드 좌표. */
    public static Vec3 toWorld(BlockPos master, Direction facing, double lx, double ly, double lz) {
        double dx, dz;
        switch (facing) {
            case SOUTH -> { dx = lx; dz = lz; }
            case WEST -> { dx = -lz; dz = lx; }
            case NORTH -> { dx = -lx; dz = -lz; }
            default -> { dx = lz; dz = -lx; } // EAST
        }
        return new Vec3(master.getX() + 0.5 + dx, master.getY() + ly, master.getZ() + 0.5 + dz);
    }

    /** 사진에 찍히는 실내 영역. */
    public static AABB interior(BlockPos master, Direction facing) {
        Vec3 a = toWorld(master, facing, -INSIDE, 0.0, -INSIDE);
        Vec3 b = toWorld(master, facing, INSIDE, MAX_Y, INSIDE);
        return new AABB(a, b);
    }

    /** 이 플레이어가 부스 안(발 위치 기준)에 있는지. */
    public static boolean isInside(Player p, BlockPos master, Direction facing) {
        return interior(master, facing).inflate(0.05).contains(p.position().add(0, 0.1, 0));
    }

    /** 플레이어가 부스 안에서 카메라(키오스크)를 바라보는 방향(yaw). */
    public static float cameraFacingYaw(Direction facing) {
        Vec3 o = toWorld(BlockPos.ZERO, facing, 0, 0, 0);
        Vec3 d = toWorld(BlockPos.ZERO, facing, 0, 0, -1).subtract(o);
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    /** 입구로 들어올 때 설 자리: 다른 사람과 겹치지 않는 첫 후보. 모두 차 있으면 가운데. */
    public static Vec3 entrySpot(Level world, BlockPos master, Direction facing) {
        List<? extends Player> players = world.players();
        for (double[] s : SPOTS) {
            Vec3 p = toWorld(master, facing, s[0], 0.125, s[1]);
            boolean free = true;
            for (Player o : players) {
                if (!o.isSpectator() && Math.abs(o.getY() - p.y) < 2 && o.position().subtract(p).horizontalDistance() < 0.75) {
                    free = false;
                    break;
                }
            }
            if (free) return p;
        }
        return toWorld(master, facing, 0, 0.125, 0);
    }

    /** 나가기: 입구 바로 바깥, 부스를 등지고 선다. */
    public static Vec3 exitSpot(BlockPos master, Direction facing) {
        return toWorld(master, facing, 0, 0.0, MAX_Z + 1.3);
    }

    public static boolean canPlace(Level world, BlockPos master, Direction facing) {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos p = BellLayout.cell(master, facing, x, y, z);
                    if (world.isOutsideBuildHeight(p)) return false;
                    if (!world.getBlockState(p).canBeReplaced()) return false;
                    if (!world.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    public static void place(Level world, BlockPos master, Direction facing) {
        world.setBlock(master, PhotoBooth.BOOTH.defaultBlockState().setValue(PhotoBoothBlock.FACING, facing), Block.UPDATE_ALL);
        for (int[] c : PART_CELLS) {
            world.setBlock(BellLayout.cell(master, facing, c[0], c[1], c[2]), partState(c, facing), Block.UPDATE_CLIENTS);
        }
    }

    private static BlockState partState(int[] c, Direction facing) {
        return PhotoBooth.BOOTH_PART.defaultBlockState()
                .setValue(PhotoBoothPartBlock.OX, c[0] - MIN_X)
                .setValue(PhotoBoothPartBlock.OY, c[1])
                .setValue(PhotoBoothPartBlock.OZ, c[2] - MIN_Z)
                .setValue(PhotoBoothPartBlock.FACING, facing);
    }

    /** 구조물 전체를 한 번에 치운다. 마스터가 남아 있었다면 아이템은 한 번만 떨어진다. */
    public static void collapse(Level world, BlockPos master, Direction facing, boolean drop) {
        if (COLLAPSING.get()) return;
        COLLAPSING.set(true);
        try {
            boolean hadMaster = world.getBlockState(master).is(PhotoBooth.BOOTH);
            if (hadMaster) world.setBlock(master, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int[] c : PART_CELLS) {
                BlockPos p = BellLayout.cell(master, facing, c[0], c[1], c[2]);
                if (world.getBlockState(p) == partState(c, facing)) {
                    world.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (hadMaster && drop && world instanceof ServerLevel) {
                Block.popResource(world, master, new ItemStack(PhotoBooth.BOOTH_ITEM));
            }
        } finally {
            COLLAPSING.set(false);
        }
    }

    public static void collapseByPlayer(Level world, BlockPos master, Direction facing, Player player) {
        collapse(world, master, facing, !player.isCreative());
    }
}
