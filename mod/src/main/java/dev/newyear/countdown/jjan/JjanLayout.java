package dev.newyear.countdown.jjan;

import dev.newyear.countdown.bell.BellLayout;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 짠 테이블 배치: 기존 대형 블록(보신각·오미쿠지·포토부스)과 같은 기준 블록 + 부속 블록 방식.
 * 설치 판정(클라이언트 홀로그램과 서버 확정이 같은 함수를 쓴다), 원자적 설치, 어느 칸이 부서져도 한 번에 철거·드롭 한 번.
 */
public final class JjanLayout {
    /** 부속 블록 상태의 칸 번호 = 로컬 좌표 + OFF. */
    public static final int OFF = 5;
    private static final ThreadLocal<Boolean> COLLAPSING = ThreadLocal.withInitial(() -> false);

    private JjanLayout() {}

    public static BlockPos cell(BlockPos master, Direction facing, int x, int y, int z) {
        return BellLayout.cell(master, facing, x, y, z);
    }

    public static BlockPos masterOf(BlockPos partPos, BlockState part) {
        int lx = part.getValue(JjanPartBlock.OX) - OFF, ly = part.getValue(JjanPartBlock.OY), lz = part.getValue(JjanPartBlock.OZ) - OFF;
        return partPos.subtract(BellLayout.cell(BlockPos.ZERO, part.getValue(JjanPartBlock.FACING), lx, ly, lz));
    }

    /** 로컬 좌표(블록, 기준 칸 바닥 중심 원점) → 월드. */
    public static Vec3 toWorld(BlockPos master, Direction facing, double lx, double ly, double lz) {
        double dx, dz;
        switch (facing) {
            case SOUTH -> { dx = lx; dz = lz; }
            case WEST -> { dx = -lz; dz = lx; }
            case NORTH -> { dx = -lx; dz = -lz; }
            default -> { dx = lz; dz = -lx; }
        }
        return new Vec3(master.getX() + 0.5 + dx, master.getY() + ly, master.getZ() + 0.5 + dz);
    }

    /** 월드 → 로컬 (toWorld 의 역변환). {x, y, z} */
    public static double[] toLocal(BlockPos master, Direction facing, Vec3 w) {
        double dx = w.x - master.getX() - 0.5, dz = w.z - master.getZ() - 0.5, y = w.y - master.getY();
        return switch (facing) {
            case SOUTH -> new double[]{dx, y, dz};
            case WEST -> new double[]{dz, y, -dx};
            case NORTH -> new double[]{-dx, y, -dz};
            default -> new double[]{-dz, y, dx};
        };
    }

    /** 로컬 방향 벡터 → 월드 방향. */
    public static Vec3 dirToWorld(Direction facing, double lx, double ly, double lz) {
        return toWorld(BlockPos.ZERO, facing, lx, ly, lz).subtract(0.5, 0, 0.5);
    }

    /** 로컬 프레임의 Minecraft yaw(도) → 월드 yaw. (로컬 +Z 를 보는 yaw 0 이 facing 방향을 보게 된다) */
    public static float worldYaw(Direction facing, float localMcYaw) {
        return localMcYaw + facing.toYRot();
    }

    /**
     * 설치 가능 여부: 점유 칸이 모두 비어 있고(교체 가능, 유체 없음, 높이 안), 바닥에 닿는 다리·좌판 아래가 단단하며,
     * 모든 자리에 머리 공간과 일어날 자리(출구) 하나 이상이 있어야 한다.
     */
    public static boolean canPlace(Level world, BlockPos master, Direction facing, JjanTables.Def def) {
        if (!free(world, cell(master, facing, 0, 0, 0))) return false;
        for (int[] c : def.partCells()) if (!free(world, cell(master, facing, c[0], c[1], c[2]))) return false;
        // 지지면: 바닥에 닿는 조각이 있는 칸 아래는 단단해야 한다
        Set<Long> ground = new HashSet<>();
        for (JjanTables.Part p : def.parts) {
            if (p.y0() > 0.01f) continue;
            float[] b = JjanTables.bounds(p);
            for (int x = (int) Math.floor(b[0] + 0.5); x <= (int) Math.floor(b[3] + 0.5 - 1e-4); x++)
                for (int z = (int) Math.floor(b[2] + 0.5); z <= (int) Math.floor(b[5] + 0.5 - 1e-4); z++)
                    ground.add(((long) x << 32) ^ (z & 0xffffffffL));
        }
        for (long g : ground) {
            BlockPos below = cell(master, facing, (int) (g >> 32), -1, (int) g);
            if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) return false;
        }
        // 자리마다 머리 공간 + 출구
        for (JjanTables.Seat s : def.seats) {
            int cx = (int) Math.floor(s.chairX() + 0.5), cz = (int) Math.floor(s.chairZ() + 0.5);
            if (!def.occupies(cx, 1, cz) && !free(world, cell(master, facing, cx, 1, cz))) return false;
            boolean exit = false;
            for (double[] e : s.exits()) {
                int ex = (int) Math.floor(e[0] + 0.5), ez = (int) Math.floor(e[1] + 0.5);
                if (def.occupies(ex, 0, ez) || def.occupies(ex, 1, ez)) continue;
                if (open(world, cell(master, facing, ex, 0, ez)) && open(world, cell(master, facing, ex, 1, ez))) { exit = true; break; }
            }
            if (!exit) return false;
        }
        return true;
    }

    private static boolean free(Level world, BlockPos p) {
        return !world.isOutsideBuildHeight(p) && world.getBlockState(p).canBeReplaced() && world.getFluidState(p).isEmpty();
    }

    private static boolean open(Level world, BlockPos p) {
        return !world.isOutsideBuildHeight(p) && world.getBlockState(p).getCollisionShape(world, p).isEmpty();
    }

    public static void place(Level world, BlockPos master, Direction facing, JjanTables.Def def) {
        world.setBlock(master, Jjan.MASTERS.get(def.index).defaultBlockState().setValue(JjanTableBlock.FACING, facing), Block.UPDATE_ALL);
        for (int[] c : def.partCells()) world.setBlock(cell(master, facing, c[0], c[1], c[2]), partState(def, c, facing), Block.UPDATE_CLIENTS);
    }

    private static BlockState partState(JjanTables.Def def, int[] c, Direction facing) {
        return Jjan.PARTS.get(def.index).defaultBlockState()
                .setValue(JjanPartBlock.OX, c[0] + OFF).setValue(JjanPartBlock.OY, c[1])
                .setValue(JjanPartBlock.OZ, c[2] + OFF).setValue(JjanPartBlock.FACING, facing);
    }

    /** 구조물 전체를 한 번에 치운다. 앉은 사람은 먼저 일으킨다. 마스터가 남아 있었다면 아이템은 한 번만 떨어진다. */
    public static void collapse(Level world, BlockPos master, Direction facing, JjanTables.Def def, boolean drop) {
        if (COLLAPSING.get()) return;
        COLLAPSING.set(true);
        try {
            if (world.getBlockEntity(master) instanceof JjanTableEntity be) be.closeAll();
            boolean hadMaster = world.getBlockState(master).is(Jjan.MASTERS.get(def.index));
            if (hadMaster) world.setBlock(master, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int[] c : def.partCells()) {
                BlockPos p = cell(master, facing, c[0], c[1], c[2]);
                if (world.getBlockState(p) == partState(def, c, facing)) world.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            if (hadMaster && drop && world instanceof ServerLevel) Block.popResource(world, master, new ItemStack(Jjan.ITEMS.get(def.index)));
        } finally {
            COLLAPSING.set(false);
        }
    }

    public static void collapseByPlayer(Level world, BlockPos master, Direction facing, JjanTables.Def def, Player player) {
        collapse(world, master, facing, def, !player.isCreative());
    }
}
