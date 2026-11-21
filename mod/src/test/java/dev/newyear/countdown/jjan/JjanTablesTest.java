package dev.newyear.countdown.jjan;

import dev.newyear.countdown.bell.BellLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 짠 테이블 정의: 정원, 자리 배치의 일관성, 설치 회전(로컬 ↔ 월드) 규칙. */
class JjanTablesTest {
    private static final Direction[] FACINGS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};

    @Test
    void capacities() {
        int[] expected = {2, 4, 8, 12, 12, 16};
        String[] ids = {"bar", "square", "round", "long", "biground", "ushape"};
        assertEquals(6, JjanTables.ALL.size());
        for (int i = 0; i < ids.length; i++) {
            JjanTables.Def d = JjanTables.byId(ids[i]);
            assertNotNull(d, ids[i]);
            assertEquals(i, d.index);
            assertEquals(expected[i], d.capacity, ids[i]);
            assertEquals(expected[i], d.seats.size(), ids[i]);
        }
    }

    @Test
    void seatsAreConsistent() {
        for (JjanTables.Def d : JjanTables.ALL) {
            for (JjanTables.Seat s : d.seats) {
                String at = d.id + " #" + s.index();
                assertEquals(s.index(), d.seatAt(s.chairX(), s.chairZ()), at + " 의자를 누르면 그 자리");
                assertEquals(s.index(), d.coasterAt(s.homeX(), s.homeZ()), at + " 받침을 누르면 그 자리");
                assertTrue(d.inRegion(s.homeX(), s.homeZ()), at + " 받침은 잔이 움직이는 영역 안");
                assertEquals(-1, d.seatAt(s.homeX(), s.homeZ()), at + " 받침은 의자가 아니다");
                // 앉으면 탁자(자기 받침) 쪽을 본다: yaw 의 시선 벡터 = (-sin, cos)
                double yaw = Math.toRadians(s.lookYaw());
                double dot = -Math.sin(yaw) * (s.homeX() - s.chairX()) + Math.cos(yaw) * (s.homeZ() - s.chairZ());
                assertTrue(dot > 0.3, at + " 시선이 탁자를 향한다");
                assertTrue(d.occupies((int) Math.floor(s.chairX() + 0.5), 0, (int) Math.floor(s.chairZ() + 0.5)), at + " 의자 칸은 구조물");
                assertTrue(s.seatTop() < d.top, at + " 의자는 상판보다 낮다");
                assertTrue(s.exits().length > 0);
            }
            // 받침끼리는 서로 헷갈리지 않을 만큼 떨어져 있다
            for (JjanTables.Seat a : d.seats)
                for (JjanTables.Seat b : d.seats)
                    if (a.index() < b.index()) assertTrue(Math.hypot(a.homeX() - b.homeX(), a.homeZ() - b.homeZ()) > 0.5, d.id + " 받침 간격");
        }
    }

    @Test
    void cellsFitThePartBlockState() {
        for (JjanTables.Def d : JjanTables.ALL) {
            assertTrue(d.minX >= -JjanLayout.OFF && d.maxX <= JjanLayout.OFF, d.id + " x 범위");
            assertTrue(d.minZ >= -JjanLayout.OFF && d.maxZ <= JjanLayout.OFF, d.id + " z 범위");
            assertTrue(d.maxY <= 1, d.id + " 높이");
            assertTrue(d.occupies(0, 0, 0));
            for (int[] c : d.partCells()) assertFalse(c[0] == 0 && c[1] == 0 && c[2] == 0);
        }
    }

    @Test
    void clampKeepsGlassesOnTheTable() {
        for (JjanTables.Def d : JjanTables.ALL) {
            for (double[] p : new double[][]{{50, 50}, {-50, 3}, {0, -80}, {0.1, 0.1}}) {
                double[] c = d.clampRegion(p[0], p[1]);
                assertTrue(d.inRegion(c[0], c[1]), d.id);
            }
        }
        // ㄷ자 가운데 빈 곳은 영역이 아니다
        assertFalse(JjanTables.byId("ushape").inRegion(0, -1));
    }

    @Test
    void rotationIsConsistent() {
        BlockPos master = new BlockPos(10, 64, -7);
        for (Direction f : FACINGS) {
            // 로컬 → 월드 → 로컬
            double[] l = JjanLayout.toLocal(master, f, JjanLayout.toWorld(master, f, 1.25, 0.82, -2.5));
            assertEquals(1.25, l[0], 1e-9);
            assertEquals(0.82, l[1], 1e-9);
            assertEquals(-2.5, l[2], 1e-9);
            // 칸 번호 회전(BellLayout.cell)과 같은 규칙: 칸 (x, y, z) 의 바닥 중심
            for (int[] c : new int[][]{{1, 0, 0}, {0, 0, 1}, {-3, 1, 2}}) {
                BlockPos cell = JjanLayout.cell(master, f, c[0], c[1], c[2]);
                assertEquals(BellLayout.cell(master, f, c[0], c[1], c[2]), cell);
                Vec3 w = JjanLayout.toWorld(master, f, c[0], c[1], c[2]);
                assertEquals(cell.getX() + 0.5, w.x, 1e-9, f + " x");
                assertEquals(cell.getY(), w.y, 1e-9, f + " y");
                assertEquals(cell.getZ() + 0.5, w.z, 1e-9, f + " z");
            }
            // 방향 벡터와 yaw: 로컬 +Z 를 보는 yaw 0 은 월드에서 dirToWorld(0,0,1) 을 본다
            Vec3 dir = JjanLayout.dirToWorld(f, 0, 0, 1);
            double yaw = Math.toRadians(JjanLayout.worldYaw(f, 0f));
            assertEquals(dir.x, -Math.sin(yaw), 1e-6, f + " yaw x");
            assertEquals(dir.z, Math.cos(yaw), 1e-6, f + " yaw z");
        }
    }

    @Test
    void anjuPlatesSitOnTheTableWithoutCrowding() {
        double plate = 0.24;   // 접시 반지름 (9복셀 × 0.85/32)
        for (JjanTables.Def d : JjanTables.ALL) {
            for (JjanTables.Seat s : d.seats) {
                double[] a = d.anju(s.index());
                String at = d.id + " 자리 " + s.index();
                // 접시 가운데가 상판 위 (잔이 다닐 수 있는 영역에서 크게 벗어나지 않는다)
                double[] c = d.clampRegion(a[0], a[1]);
                assertTrue(Math.hypot(c[0] - a[0], c[1] - a[1]) < 0.16, at + " 접시가 상판 밖 " + a[0] + ", " + a[1]);
                // 내 잔·남의 잔 자리와 겹치지 않고, 다른 접시와도 겹치지 않는다
                for (JjanTables.Seat o : d.seats) {
                    assertTrue(Math.hypot(o.homeX() - a[0], o.homeZ() - a[1]) >= plate + 0.1, at + " 접시가 자리 " + o.index() + " 잔과 겹친다");
                    if (o.index() == s.index()) continue;
                    double[] b = d.anju(o.index());
                    assertTrue(Math.hypot(b[0] - a[0], b[1] - a[1]) >= plate * 2, at + " 접시가 자리 " + o.index() + " 접시와 겹친다");
                }
                // 내 접시는 내 잔에서 손 닿는 곳
                assertTrue(Math.hypot(s.homeX() - a[0], s.homeZ() - a[1]) < 0.75, at + " 접시가 너무 멀다");
            }
        }
    }

    @Test
    void everyDrinkComesWithAnju() {
        JjanCatalog cat = JjanCatalog.load();
        assertEquals(14, cat.anju().size());
        for (JjanCatalog.Drink d : cat.drinks()) {
            JjanCatalog.Anju a = cat.anjuFor(d);
            assertNotNull(a, d.id());
            assertFalse(a.pieces().isEmpty(), a.id());
            assertEquals(0, a.plate().length % 7, a.id());
            for (float[] piece : a.pieces()) {
                assertTrue(piece.length >= 7 && piece.length % 7 == 0, a.id());
                // 접시(반지름 12복셀 안쪽) 위에 놓인다
                for (int i = 0; i < piece.length; i += 7) {
                    assertTrue(piece[i + 1] >= 0 && piece[i + 4] < 40, a.id() + " 높이");
                    assertTrue(Math.abs(piece[i]) < 14 && Math.abs(piece[i + 3]) < 14 && Math.abs(piece[i + 2]) < 14 && Math.abs(piece[i + 5]) < 14, a.id() + " 조각이 접시 밖");
                }
            }
        }
        assertEquals("chicken", cat.anjuFor(cat.drinks().stream().filter(x -> x.category().equals("beer")).findFirst().orElseThrow()).id());
    }
}
