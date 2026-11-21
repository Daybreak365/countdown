package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 짠: 테이블 6종의 정의 (TableDefinition). 모든 좌표는 테이블 기준 로컬 좌표(블록 단위)이며, 원점은 기준(마스터) 칸의 바닥 중심,
 * 정면이 +Z 다. 설치 회전은 기존 대형 블록(BellLayout.cell)과 같은 규칙으로 적용한다.
 *
 * 외형(parts)은 복셀 느낌의 작은 직육면체 목록이고, 같은 목록에서 점유 칸·칸별 충돌 모양·선택 모양을 계산한다
 * (그래서 ㄷ자 가운데나 다리 사이는 막지 않는다). 원본의 cm 좌표를 그대로 쓰지 않고, 자리 배치·비율만 따라 블록 크기로 다시 잡았다.
 * 조각의 texture 가 jjan_ 으로 시작하면 이 모드의 회색조 결 텍스처(tools/jjan/textures.py)이고 color(원작 색)를 곱해 그린다.
 */
public final class JjanTables {
    public enum Role { TOP, LEG, CHAIR, DECOR }

    /** 외형 조각. yaw 가 0 이 아니면 (pivotX, pivotZ) 를 축으로 돌려 그린다(의자). solid 만 충돌에 쓴다. */
    public record Part(float x0, float y0, float z0, float x1, float y1, float z1, String texture, int color, Role role,
                       int seat, float yaw, float pivotX, float pivotZ, boolean solid) {}

    /**
     * 자리. chair = 의자 중심, seatTop = 앉는 면 높이, angle = 탁자 가운데에서 앉은 사람 쪽 방향(라디안, 0 = +Z),
     * home = 잔이 놓이는 곳(잔 받침), exits = 일어날 때 설 자리 후보.
     */
    public record Seat(int index, double chairX, double chairZ, double seatTop, double angle, double homeX, double homeZ, double[][] exits) {
        /** 탁자를 바라보는 Minecraft yaw(도). */
        public float lookYaw() {
            return (float) Math.toDegrees(Math.atan2(Math.sin(angle), -Math.cos(angle)));
        }
    }

    /** 잔이 움직일 수 있는 영역: 원판 또는 직사각형들의 합집합. */
    public record Shape(boolean circle, double a, double b, double c, double d) {
        static Shape rect(double x0, double x1, double z0, double z1) { return new Shape(false, x0, x1, z0, z1); }
        static Shape disc(double r) { return new Shape(true, r, 0, 0, 0); }

        boolean contains(double x, double z) {
            return circle ? x * x + z * z <= a * a + 1e-9 : x >= a - 1e-9 && x <= b + 1e-9 && z >= c - 1e-9 && z <= d + 1e-9;
        }

        double[] project(double x, double z) {
            if (circle) {
                double r = Math.hypot(x, z);
                return r <= a ? new double[]{x, z} : new double[]{x / r * a, z / r * a};
            }
            return new double[]{Math.max(a, Math.min(b, x)), Math.max(c, Math.min(d, z))};
        }
    }

    public static final class Def {
        public final int index;
        public final String id;
        public final int capacity;
        public final double top;
        public final int minX, maxX, minZ, maxZ, maxY;
        public final List<Part> parts;
        public final List<Seat> seats;
        public final List<Shape> region;
        /** occupied[x - minX][y][z - minZ] */
        private final boolean[][][] occupied;
        private final List<int[]> cells = new ArrayList<>();
        private final Map<Long, VoxelShape> shapes = new HashMap<>();

        Def(int index, String id, double top, List<Part> parts, List<Seat> seats, List<Shape> region) {
            this.index = index;
            this.id = id;
            this.top = top;
            this.parts = List.copyOf(parts);
            this.seats = List.copyOf(seats);
            this.region = List.copyOf(region);
            this.capacity = seats.size();
            int x0 = 0, x1 = 0, z0 = 0, z1 = 0, y1 = 0;
            for (Part p : parts) {
                float[] b = bounds(p);
                x0 = Math.min(x0, cell(b[0])); x1 = Math.max(x1, cell(b[3] - 1e-4f));
                z0 = Math.min(z0, cell(b[2])); z1 = Math.max(z1, cell(b[5] - 1e-4f));
                y1 = Math.max(y1, (int) Math.floor(b[4] - 1e-4f));
            }
            minX = x0; maxX = x1; minZ = z0; maxZ = z1; maxY = y1;
            occupied = new boolean[maxX - minX + 1][maxY + 1][maxZ - minZ + 1];
            for (Part p : parts) {
                float[] b = bounds(p);
                for (int x = cell(b[0]); x <= cell(b[3] - 1e-4f); x++)
                    for (int z = cell(b[2]); z <= cell(b[5] - 1e-4f); z++)
                        for (int y = Math.max(0, (int) Math.floor(b[1])); y <= (int) Math.floor(b[4] - 1e-4f); y++)
                            occupied[x - minX][y][z - minZ] = true;
            }
            occupied[-minX][0][-minZ] = true;   // 기준 칸은 항상 이 구조물 것
            for (int x = minX; x <= maxX; x++)
                for (int y = 0; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++)
                        if (occupied[x - minX][y][z - minZ] && !(x == 0 && y == 0 && z == 0)) cells.add(new int[]{x, y, z});
        }

        private static int cell(double v) {
            return (int) Math.floor(v + 0.5);
        }

        public boolean occupies(int x, int y, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= 0 && y <= maxY && occupied[x - minX][y][z - minZ];
        }

        /** 기준 칸을 뺀 점유 칸들. */
        public List<int[]> partCells() {
            return cells;
        }

        /** 이 칸의 충돌·선택 모양(블록 좌표 0..1, facing 으로 회전). solid 조각을 칸 안으로 잘라 합친다. */
        public synchronized VoxelShape shape(int lx, int ly, int lz, Direction facing) {
            long key = (((long) (lx + 16) * 8 + ly) * 32 + (lz + 16)) * 4 + facing.get2DDataValue();
            return shapes.computeIfAbsent(key, k -> build(lx, ly, lz, facing));
        }

        private VoxelShape build(int lx, int ly, int lz, Direction facing) {
            VoxelShape out = Shapes.empty();
            for (Part p : parts) {
                if (!p.solid) continue;
                float[] b = bounds(p);
                double x0 = Math.max(b[0], lx - 0.5), x1 = Math.min(b[3], lx + 0.5);
                double z0 = Math.max(b[2], lz - 0.5), z1 = Math.min(b[5], lz + 0.5);
                double y0 = Math.max(b[1], ly), y1 = Math.min(b[4], ly + 1);
                if (x1 - x0 < 1e-4 || z1 - z0 < 1e-4 || y1 - y0 < 1e-4) continue;
                double[] r = rotate(x0 - (lx - 0.5), x1 - (lx - 0.5), z0 - (lz - 0.5), z1 - (lz - 0.5), facing);
                out = Shapes.or(out, Shapes.box(r[0], y0 - ly, r[2], r[1], y1 - ly, r[3]));
            }
            return out.isEmpty() ? Shapes.box(0.3, 0, 0.3, 0.7, 0.1, 0.7) : out.optimize();
        }

        /** 이 칸 안에 앉는 자리(의자 좌판)가 있으면 그 번호, 없으면 -1. */
        public int seatAt(double x, double z) {
            for (Seat s : seats) if (Math.abs(x - s.chairX) <= 0.4 && Math.abs(z - s.chairZ) <= 0.4) return s.index;
            return -1;
        }

        /** 이 지점이 잔 받침이면 그 자리 번호, 아니면 -1. */
        public int coasterAt(double x, double z) {
            int best = -1;
            double bestD = 0.36 * 0.36;
            for (Seat s : seats) {
                double d = (x - s.homeX) * (x - s.homeX) + (z - s.homeZ) * (z - s.homeZ);
                if (d < bestD) { bestD = d; best = s.index; }
            }
            return best;
        }

        public boolean inRegion(double x, double z) {
            for (Shape s : region) if (s.contains(x, z)) return true;
            return false;
        }

        /**
         * 그 자리의 안주 접시가 놓이는 곳 {x, z} (원작 anjuPose): 잔 오른쪽, 탁자 안쪽으로 조금.
         * 원탁은 잔보다 안쪽 원 위에서 조금 돌아간 자리, 바는 잔 바깥 옆.
         */
        public double[] anju(int seat) {
            Seat s = seats.get(seat);
            double a = s.angle();
            if (id.equals("round") || id.equals("biground")) {
                boolean small = id.equals("round");
                double r = Math.hypot(s.homeX(), s.homeZ()) * (small ? 33.0 / 48 : 52.0 / 70), b = a + (small ? 0.33 : 0.23);
                return new double[]{Math.sin(b) * r, Math.cos(b) * r};
            }
            if (id.equals("bar")) return new double[]{s.homeX() + (seat == 0 ? -0.7 : 0.7), 0.1};
            double right = id.equals("long") ? 0.56 : id.equals("ushape") ? 0.5 : 0.45, fwd = id.equals("ushape") ? 0.08 : 0.2;
            return new double[]{s.homeX() + Math.cos(a) * right - Math.sin(a) * fwd, s.homeZ() - Math.sin(a) * right - Math.cos(a) * fwd};
        }

        /** 영역 밖이면 가장 가까운 영역 안 점으로 옮긴다. */
        public double[] clampRegion(double x, double z) {
            if (inRegion(x, z)) return new double[]{x, z};
            double[] best = null;
            double bestD = Double.MAX_VALUE;
            for (Shape s : region) {
                double[] p = s.project(x, z);
                double d = (p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z);
                if (d < bestD) { bestD = d; best = p; }
            }
            return best;
        }
    }

    /** 조각을 돌린 뒤의 축 정렬 경계 {x0, y0, z0, x1, y1, z1}. */
    static float[] bounds(Part p) {
        if (p.yaw == 0f) return new float[]{p.x0, p.y0, p.z0, p.x1, p.y1, p.z1};
        double c = Math.cos(p.yaw), s = Math.sin(p.yaw);
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (float x : new float[]{p.x0, p.x1}) {
            for (float z : new float[]{p.z0, p.z1}) {
                double dx = x - p.pivotX, dz = z - p.pivotZ;
                // 렌더러와 같은 회전: Y 축으로 yaw (dx, dz) → (dx cos + dz sin, -dx sin + dz cos)
                float rx = (float) (p.pivotX + dx * c + dz * s), rz = (float) (p.pivotZ - dx * s + dz * c);
                x0 = Math.min(x0, rx); x1 = Math.max(x1, rx); z0 = Math.min(z0, rz); z1 = Math.max(z1, rz);
            }
        }
        return new float[]{x0, p.y0, z0, x1, p.y1, z1};
    }

    /** 칸 안 (x, z) 구간을 칸 중심 기준으로 facing 에 맞춰 돌린다 (BellLayout.cell 과 같은 규칙). */
    static double[] rotate(double x0, double x1, double z0, double z1, Direction f) {
        double cx0 = x0 - 0.5, cx1 = x1 - 0.5, cz0 = z0 - 0.5, cz1 = z1 - 0.5;
        double a0, a1, c0, c1;
        switch (f) {
            case SOUTH -> { a0 = cx0; a1 = cx1; c0 = cz0; c1 = cz1; }
            case WEST -> { a0 = -cz1; a1 = -cz0; c0 = cx0; c1 = cx1; }
            case NORTH -> { a0 = -cx1; a1 = -cx0; c0 = -cz1; c1 = -cz0; }
            default -> { a0 = cz0; a1 = cz1; c0 = -cx1; c1 = -cx0; }
        }
        return new double[]{a0 + 0.5, a1 + 0.5, c0 + 0.5, c1 + 0.5};
    }

    // ------------------------------------------------------------------ 만들기 도우미

    /** 원작(room.js TABLE_LOOKS)의 탁자 색: 상판 널빤지들, 테두리·밑판, 다리, 의자 종류(chair, stool), 방석 색들, 의자 틀. */
    record Look(int[] top, int edge, int leg, String chair, int[] seat, int frame) {}

    static final Look WOOD = new Look(new int[]{0x9a6a43, 0x8f6240, 0xa37148, 0x94653f}, 0x6f4a2e, 0x5f3d26, "chair", new int[]{0xb5523b, 0x8f6a3a}, 0x7a4f30);
    static final Look HOF = new Look(new int[]{0xb07a48, 0xa8723f, 0xb8824e, 0xa06c3c}, 0x7a5232, 0x5a3a24, "stool", new int[]{0x8a5a34, 0x9c6a3c}, 0x6b4529);
    static final Look DARK = new Look(new int[]{0x4a2e20, 0x45291c, 0x52331f, 0x4d2f20}, 0x2e1c14, 0x24160f, "chair", new int[]{0x6b1f2a, 0x4a4a2a}, 0x2e1c14);
    static final Look RUSTIC = new Look(new int[]{0x8a5a33, 0x7d5030, 0x94633a, 0x87582f}, 0x5a3a20, 0x4a2f1a, "chair", new int[]{0xb3282e, 0x2f6b3a}, 0x5a3a20);
    static final Look PINE = new Look(new int[]{0xc49a6c, 0xb98f60, 0xcca474, 0xbf9566}, 0x9a7048, 0x7a5636, "stool", new int[]{0x2f8f8a, 0xd29b2e, 0x8a4a9a}, 0x7a5636);

    /** 자리마다 늘 같은 값 [0, 1). 원작 BoxBuilder 의 상자별 밝기 차이에 쓴다. */
    static double hash3(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** 0xRRGGBB 를 k 배 밝게 한 불투명 ARGB. */
    static int shade(int rgb, double k) {
        int r = (int) Math.round(Math.min(255, (rgb >> 16 & 255) * k)), g = (int) Math.round(Math.min(255, (rgb >> 8 & 255) * k)),
                b = (int) Math.round(Math.min(255, (rgb & 255) * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static final class B {
        final List<Part> parts = new ArrayList<>();
        final List<Seat> seats = new ArrayList<>();

        /** 상자 하나. 원작처럼 상자마다 밝기가 ±jitter 만큼 다르다. 색은 0xRRGGBB. */
        void put(double x0, double y0, double z0, double x1, double y1, double z1, String tex, int rgb, double jitter, Role role, boolean solid) {
            double k = 1 + (hash3((int) Math.round(x0 * 40), (int) Math.round(y0 * 40), (int) Math.round(z0 * 40)) - 0.5) * 2 * jitter;
            parts.add(new Part((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, tex, shade(rgb, k), role, -1, 0f, 0f, 0f, solid));
        }

        /** 널빤지 상판(두께 0.05). alongX 면 x 방향으로 긴 널을 z 로 늘어놓는다. 원작처럼 널마다 색이 번갈아 바뀐다. */
        void planks(double x0, double x1, double z0, double z1, double top, boolean alongX, double w, int[] colors, int n, int shift) {
            int i = 0;
            if (alongX) {
                for (double z = z0; z < z1 - 1e-6; z += w, i++)
                    put(x0, top - 0.05, z, x1, top, Math.min(z1, z + w), "jjan_plank", colors[(i + shift) % n], 0.03, Role.TOP, true);
            } else {
                for (double x = x0; x < x1 - 1e-6; x += w, i++)
                    put(x, top - 0.05, z0, Math.min(x1, x + w), top, z1, "jjan_plank_z", colors[(i + shift) % n], 0.03, Role.TOP, true);
            }
        }

        /** 상판 가장자리를 따라가는 테두리 한 줄 (원작의 rim: 선을 가운데 두고 조금 튀어나온다). */
        void edge(double x0, double z0, double x1, double z1, double top, int color) {
            put(Math.min(x0, x1) - 0.02, top - 0.065, Math.min(z0, z1) - 0.02, Math.max(x0, x1) + 0.02, top - 0.02, Math.max(z0, z1) + 0.02,
                    "jjan_plank", color, 0, Role.TOP, false);
        }

        void rim(double x0, double x1, double z0, double z1, double top, int color) {
            edge(x0, z0, x1, z0, top, color);
            edge(x0, z1, x1, z1, top, color);
            edge(x0, z0, x0, z1, top, color);
            edge(x1, z0, x1, z1, top, color);
        }

        /** 상판 밑판(에이프런). */
        void apron(double x0, double x1, double z0, double z1, double top, int color) {
            put(x0 + 0.08, top - 0.15, z0 + 0.08, x1 - 0.08, top - 0.05, z1 - 0.08, "jjan_plank", color, 0.03, Role.TOP, true);
        }

        void leg(double x, double z, double w, double y1, int color) {
            put(x - w / 2, 0, z - w / 2, x + w / 2, y1, z + w / 2, "jjan_wood", color, 0.03, Role.LEG, true);
        }

        /** 복셀 계단형 원판. 색은 띠 번호로 고른다. */
        void disc(double r, double y0, double y1, double step, String tex, java.util.function.IntUnaryOperator color, double jitter, Role role, boolean solid) {
            int i = 0;
            for (double z = -r; z < r - 1e-6; z += step, i++) {
                double zc = z < 0 && z + step > 0 ? 0 : Math.min(Math.abs(z), Math.abs(z + step));
                double half = Math.floor(Math.sqrt(Math.max(0, r * r - zc * zc)) / 0.0625) * 0.0625;
                if (half <= 0) continue;
                put(-half, y0, z, half, y1, Math.min(r, z + step), tex, color.applyAsInt(i), jitter, role, solid);
            }
        }

        /** 원탁: 널빤지 원판 + 밑판 + 가운데 기둥 + 십자 발 (원작 buildRoundTable). */
        void roundTable(double r, double top, Look k, double column, double foot) {
            disc(r, top - 0.05, top, 0.125, "jjan_plank", i -> k.top[(i / 2) % 3], 0.03, Role.TOP, true);
            disc(r - 0.05, top - 0.09, top - 0.05, 0.25, "jjan_plank", i -> k.edge, 0.02, Role.TOP, true);
            put(-column, 0.06, -column, column, top - 0.09, column, "jjan_wood", k.leg, 0.03, Role.LEG, true);
            put(-foot, 0, -0.1, foot, 0.06, 0.1, "jjan_plank", k.leg, 0.03, Role.LEG, true);
            put(-0.1, 0, -foot, 0.1, 0.06, -0.1, "jjan_plank_z", k.leg, 0.03, Role.LEG, true);
            put(-0.1, 0, 0.1, 0.1, 0.06, foot, "jjan_plank_z", k.leg, 0.03, Role.LEG, true);
        }

        /** 땅콩 그릇 (원작 단골 바의 탁자 가운데 소품). 든 잔은 그 위로 지나간다. */
        void bowl(double x, double z, double top) {
            double r = 0.15, a = r * 0.55;
            put(x - r, top, z - a, x + r, top + 0.07, z + a, "jjan_plank", 0x6b4529, 0.03, Role.DECOR, false);
            put(x - a, top, z - r, x + a, top + 0.07, z - a, "jjan_plank", 0x6b4529, 0.03, Role.DECOR, false);
            put(x - a, top, z + a, x + a, top + 0.07, z + r, "jjan_plank", 0x6b4529, 0.03, Role.DECOR, false);
            put(x - r + 0.03, top + 0.07, z - a + 0.01, x + r - 0.03, top + 0.085, z + a - 0.01, "jjan_nuts", 0xc9a26b, 0, Role.DECOR, false);
            put(x - a + 0.01, top + 0.07, z - r + 0.03, x + a - 0.01, top + 0.085, z - a + 0.01, "jjan_nuts", 0xc9a26b, 0, Role.DECOR, false);
            put(x - a + 0.01, top + 0.07, z + a - 0.01, x + a - 0.01, top + 0.085, z + r - 0.03, "jjan_nuts", 0xc9a26b, 0, Role.DECOR, false);
        }

        private record C(double x0, double y0, double z0, double x1, double y1, double z1, String tex, int rgb, boolean solid) {}

        /** 팔각형 판(의자 좌판)을 겹치지 않는 세 조각으로. */
        private static void oct(List<C> out, double r, double y0, double y1, String tex, int rgb, boolean solid) {
            double a = r * 0.55;
            out.add(new C(-r, y0, -a, r, y1, a, tex, rgb, solid));
            out.add(new C(-a, y0, -r, a, y1, -a, tex, rgb, solid));
            out.add(new C(-a, y0, a, a, y1, r, tex, rgb, solid));
        }

        /**
         * 의자. angle = 탁자에서 앉은 사람 쪽 방향. 원작 buildChair 그대로 등받이가 없다:
         * chair = 네모 틀 + 방석 + 다리 넷 + 가로대, stool = 둥근 좌판(테두리는 틀 색) + 다리 넷 + 십자 가로대, bar = 쇠기둥 스툴.
         */
        void chair(int seat, double cx, double cz, double seatTop, double angle, String kind, int cushion, int frame, int leg) {
            List<C> l = new ArrayList<>();
            if (kind.equals("bar")) {
                oct(l, 0.2, seatTop - 0.07, seatTop, "jjan_fabric", cushion, true);
                oct(l, 0.17, seatTop - 0.1, seatTop - 0.07, "jjan_metal", frame, false);
                l.add(new C(-0.045, 0.04, -0.045, 0.045, seatTop - 0.1, 0.045, "jjan_metal", frame, false));
                // 발걸이 고리
                l.add(new C(-0.17, 0.25, -0.17, 0.17, 0.28, -0.14, "jjan_metal", frame, false));
                l.add(new C(-0.17, 0.25, 0.14, 0.17, 0.28, 0.17, "jjan_metal", frame, false));
                l.add(new C(-0.17, 0.25, -0.14, -0.14, 0.28, 0.14, "jjan_metal", frame, false));
                l.add(new C(0.14, 0.25, -0.14, 0.17, 0.28, 0.14, "jjan_metal", frame, false));
                l.add(new C(-0.14, 0.255, -0.015, 0.14, 0.275, 0.015, "jjan_metal", frame, false));
                oct(l, 0.16, 0, 0.04, "jjan_metal", leg, false);
            } else if (kind.equals("stool")) {
                oct(l, 0.24, seatTop - 0.07, seatTop - 0.015, "jjan_wood", frame, true);
                oct(l, 0.195, seatTop - 0.015, seatTop + 0.012, "jjan_plank", cushion, false);
                for (int sx = -1; sx <= 1; sx += 2)
                    for (int sz = -1; sz <= 1; sz += 2)
                        l.add(new C(sx * 0.14 - 0.03, 0, sz * 0.14 - 0.03, sx * 0.14 + 0.03, seatTop - 0.07, sz * 0.14 + 0.03, "jjan_wood", leg, false));
                l.add(new C(-0.17, 0.15, -0.02, 0.17, 0.19, 0.02, "jjan_plank", leg, false));
                l.add(new C(-0.02, 0.14, -0.17, 0.02, 0.18, 0.17, "jjan_plank", leg, false));
            } else {
                l.add(new C(-0.24, seatTop - 0.09, -0.24, 0.24, seatTop - 0.03, 0.24, "jjan_plank", frame, true));
                l.add(new C(-0.205, seatTop - 0.03, -0.205, 0.205, seatTop + 0.015, 0.205, "jjan_fabric", cushion, false));
                for (int sx = -1; sx <= 1; sx += 2)
                    for (int sz = -1; sz <= 1; sz += 2)
                        l.add(new C(sx * 0.18 - 0.03, 0, sz * 0.18 - 0.03, sx * 0.18 + 0.03, seatTop - 0.09, sz * 0.18 + 0.03, "jjan_wood", leg, false));
                l.add(new C(-0.2, 0.15, -0.02, 0.2, 0.19, 0.02, "jjan_plank", leg, false));
            }
            float yaw = (float) angle;
            for (int i = 0; i < l.size(); i++) {
                C c = l.get(i);
                double k = 1 + (hash3(seat * 31 + i, (int) Math.round(cx * 40), (int) Math.round(cz * 40)) - 0.5) * 2 * 0.04;
                parts.add(new Part((float) (cx + c.x0), (float) c.y0, (float) (cz + c.z0), (float) (cx + c.x1), (float) c.y1, (float) (cz + c.z1),
                        c.tex, shade(c.rgb, k), Role.CHAIR, seat, yaw, (float) cx, (float) cz, c.solid));
            }
        }

        void chair(int seat, double cx, double cz, double seatTop, double angle, Look k) {
            chair(seat, cx, cz, seatTop, angle, k.chair, k.seat[seat % k.seat.length], k.frame, k.leg);
        }

        void seat(double chairX, double chairZ, double seatTop, double angle, double homeX, double homeZ) {
            double dx = Math.sin(angle), dz = Math.cos(angle), px = Math.cos(angle), pz = -Math.sin(angle);
            double[][] exits = {
                    {chairX + dx * 0.95, chairZ + dz * 0.95},
                    {chairX + px * 0.95, chairZ + pz * 0.95},
                    {chairX - px * 0.95, chairZ - pz * 0.95},
                    {chairX + dx * 0.95 + px * 0.9, chairZ + dz * 0.95 + pz * 0.9},
                    {chairX + dx * 0.95 - px * 0.9, chairZ + dz * 0.95 - pz * 0.9},
            };
            seats.add(new Seat(seats.size(), chairX, chairZ, seatTop, angle, homeX, homeZ, exits));
        }

        /** 원 둘레 자리 n 개. */
        void ring(int n, double chairR, double homeR, double seatTop, Look k) {
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                double cx = Math.sin(a) * chairR, cz = Math.cos(a) * chairR;
                chair(i, cx, cz, seatTop, a, k);
                seat(cx, cz, seatTop, a, Math.sin(a) * homeR, Math.cos(a) * homeR);
            }
        }
    }

    private static final double TOP = 0.82, SEAT = 0.45;

    public static final List<Def> ALL = build();

    /**
     * 외형은 원작(room.js 의 buildSquareTable / buildLongTable / buildRoundTable / buildUTable / buildChair, 바는 BAR_LOOK)을 블록 크기로 옮긴 것:
     * 색이 번갈아 바뀌는 널빤지 상판, 테두리와 밑판, 다리와 가로대, 등받이 없는 의자. 색은 원작 값 그대로이고 결만 텍스처(jjan_*)로 얹는다.
     * 자리·잔 자리·움직일 수 있는 영역·상판 높이는 그대로다.
     */
    private static List<Def> build() {
        List<Def> out = new ArrayList<>();

        // 바 테이블 (2인): 높은 카운터와 스툴 둘, 뒤에는 술병 선반
        B bar = new B();
        bar.put(-2.2, 0, -0.42, 2.2, 0.1, 0.42, "jjan_plank", 0x3d2416, 0.02, Role.TOP, true);   // 걸레받이
        int[] front = {0x5e3a24, 0x684129};
        for (int i = 0; i < 16; i++)   // 세로 판자를 댄 몸통
            bar.put(-2.2 + i * 0.275, 0.1, -0.45, -2.2 + (i + 1) * 0.275, 1.0, 0.45, "jjan_wood", front[i % 2], 0.04, Role.TOP, true);
        bar.put(-2.22, 0.88, -0.47, 2.22, 0.94, 0.47, "jjan_plank", 0x7a4d2f, 0.02, Role.TOP, false);   // 몰딩
        bar.put(-2.3, 1.0, -0.5, 2.3, 1.03, 0.55, "jjan_plank", 0x5a3520, 0.02, Role.TOP, true);
        int[] barTop = {0x8b5a36, 0x84542f, 0x91603a};
        bar.planks(-2.28, 2.28, -0.48, 0.53, 1.08, true, 0.3367, barTop, 3, 0);
        // 놋쇠 발걸이
        bar.put(-2.1, 0.2, 0.455, 2.1, 0.24, 0.495, "jjan_metal", 0xc9a24a, 0, Role.DECOR, false);
        // 뒤 선반
        for (int i = 0; i < 8; i++)
            bar.put(-2.2 + i * 0.55, 0, -1.4, -2.2 + (i + 1) * 0.55, 0.92, -0.85, "jjan_wood", 0x4a2e1c, 0.05, Role.DECOR, true);
        bar.put(-2.22, 0.92, -1.42, 2.22, 0.95, -0.83, "jjan_plank", 0x6b4529, 0.02, Role.DECOR, true);
        int[] bottles = {0x2f6e3a, 0x5a3410, 0xe6eef2, 0x8a3e12, 0x2f7a3a, 0xb5121c, 0x3f5a34, 0xe8d6a0};
        for (int i = 0; i < bottles.length; i++) {
            double x = -1.9 + i * 0.54;
            bar.put(x - 0.07, 0.95, -1.2, x + 0.07, 1.27, -1.06, "jjan_plain", bottles[i], 0.03, Role.DECOR, false);
            bar.put(x - 0.03, 1.27, -1.16, x + 0.03, 1.38, -1.1, "jjan_plain", bottles[i], 0.03, Role.DECOR, false);
            bar.put(x - 0.073, 1.03, -1.203, x + 0.073, 1.15, -1.057, "jjan_plain", 0xf4eedc, 0.02, Role.DECOR, false);   // 라벨
            bar.put(x - 0.034, 1.38, -1.164, x + 0.034, 1.4, -1.096, "jjan_metal", i % 3 == 0 ? 0xc9a24a : 0x2a2a2d, 0, Role.DECOR, false);   // 마개
        }
        bar.bowl(0, 0.1, 1.08);
        for (int s = 0; s < 2; s++) {
            double x = s == 0 ? -0.8 : 0.8;
            bar.chair(s, x, 1.05, 0.75, 0, "bar", 0xc8202f, 0xbfc3c8, 0x8e9298);
            bar.seat(x, 1.05, 0.75, 0, x, 0.2);
        }
        out.add(new Def(0, "bar", 1.08, bar.parts, bar.seats, List.of(Shape.rect(-2.0, 2.0, -0.3, 0.35))));

        // 사각 테이블 (4인)
        B sq = new B();
        sq.planks(-0.9, 0.9, -0.9, 0.9, TOP, false, 0.3, WOOD.top, 3, 0);
        sq.rim(-0.9, 0.9, -0.9, 0.9, TOP, WOOD.edge);
        sq.apron(-0.9, 0.9, -0.9, 0.9, TOP, WOOD.edge);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) sq.leg(sx * 0.75, sz * 0.75, 0.12, TOP - 0.15, WOOD.leg);
        for (int sz = -1; sz <= 1; sz += 2)
            sq.put(-0.69, 0.14, sz * 0.75 - 0.025, 0.69, 0.19, sz * 0.75 + 0.025, "jjan_plank", WOOD.leg, 0.03, Role.LEG, false);
        sq.bowl(0, 0, TOP);
        double[] sqA = {0, Math.PI, Math.PI / 2, -Math.PI / 2};
        for (int s = 0; s < 4; s++) {
            double a = sqA[s], cx = Math.sin(a) * 1.75, cz = Math.cos(a) * 1.75;
            sq.chair(s, cx, cz, SEAT, a, WOOD);
            sq.seat(cx, cz, SEAT, a, Math.sin(a) * 0.55, Math.cos(a) * 0.55);
        }
        out.add(new Def(1, "square", TOP, sq.parts, sq.seats, List.of(Shape.rect(-0.75, 0.75, -0.75, 0.75))));

        // 원형 테이블 (8인)
        B rd = new B();
        rd.roundTable(1.85, TOP, PINE, 0.15, 0.73);
        rd.bowl(0, 0, TOP);
        rd.ring(8, 2.7, 1.3, SEAT, PINE);
        out.add(new Def(2, "round", TOP, rd.parts, rd.seats, List.of(Shape.disc(1.65))));

        // 긴 테이블 (12인): 양쪽에 여섯 명씩. 널은 원작처럼 줄마다 다른 곳에서 한 번 이어 붙인다
        B lg = new B();
        for (int i = 0; i < 8; i++) {
            double z = -0.85 + i * 0.2125, cut = (-40 + (i * 37) % 80) / 122.0 * 3.9;
            lg.put(-3.9, TOP - 0.05, z, cut, TOP, z + 0.2125, "jjan_plank", HOF.top[i % 4], 0.03, Role.TOP, true);
            lg.put(cut, TOP - 0.05, z, 3.9, TOP, z + 0.2125, "jjan_plank", HOF.top[(i + 2) % 4], 0.03, Role.TOP, true);
        }
        lg.rim(-3.9, 3.9, -0.85, 0.85, TOP, HOF.edge);
        lg.apron(-3.9, 3.9, -0.85, 0.85, TOP, HOF.edge);
        for (double x : new double[]{-3.7, 0, 3.7}) {
            for (int sz = -1; sz <= 1; sz += 2) lg.leg(x, sz * 0.7, 0.12, TOP - 0.15, HOF.leg);
            lg.put(x - 0.03, 0.14, -0.64, x + 0.03, 0.19, 0.64, "jjan_plank", HOF.leg, 0.03, Role.LEG, false);
        }
        lg.put(-3.64, 0.13, -0.025, 3.64, 0.18, 0.025, "jjan_plank", HOF.leg, 0.03, Role.LEG, false);
        lg.bowl(-1.8, 0, TOP);
        lg.bowl(1.8, 0, TOP);
        for (int s = 0; s < 12; s++) {
            int side = s < 6 ? 1 : -1;
            double x = -3.0 + 1.2 * (s % 6), a = side > 0 ? 0 : Math.PI;
            lg.chair(s, x, side * 1.75, SEAT, a, HOF);
            lg.seat(x, side * 1.75, SEAT, a, x, side * 0.5);
        }
        out.add(new Def(3, "long", TOP, lg.parts, lg.seats, List.of(Shape.rect(-3.7, 3.7, -0.65, 0.65))));

        // 큰 원탁 (12인): 가운데 기둥 하나로는 모자라 다리를 넷 더 단다
        B bg = new B();
        bg.roundTable(2.85, TOP, DARK, 0.2, 1.13);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) bg.leg(sx * 1.7, sz * 1.7, 0.12, TOP - 0.09, DARK.leg);
        bg.bowl(0, 0, TOP);
        bg.ring(12, 3.65, 2.1, SEAT, DARK);
        out.add(new Def(4, "biground", TOP, bg.parts, bg.seats, List.of(Shape.disc(2.6))));

        // ㄷ자 단체석 (16인): 앞 긴 탁자 + 양 팔. 모두 바깥쪽에 앉아 안쪽을 본다
        B u = new B();
        u.planks(-4.4, 4.4, 1.6, 2.6, TOP, true, 0.25, RUSTIC.top, 4, 0);
        u.planks(-4.4, -3.4, -3.4, 1.6, TOP, false, 0.25, RUSTIC.top, 4, 1);
        u.planks(3.4, 4.4, -3.4, 1.6, TOP, false, 0.25, RUSTIC.top, 4, 1);
        u.apron(-4.4, 4.4, 1.6, 2.6, TOP, RUSTIC.edge);
        u.apron(-4.4, -3.4, -3.4, 1.6 + 0.16, TOP, RUSTIC.edge);
        u.apron(3.4, 4.4, -3.4, 1.6 + 0.16, TOP, RUSTIC.edge);
        // 둘레 테두리
        u.edge(-4.4, 2.6, 4.4, 2.6, TOP, RUSTIC.edge);
        u.edge(-4.4, -3.4, -4.4, 2.6, TOP, RUSTIC.edge);
        u.edge(4.4, -3.4, 4.4, 2.6, TOP, RUSTIC.edge);
        u.edge(-4.4, -3.4, -3.4, -3.4, TOP, RUSTIC.edge);
        u.edge(3.4, -3.4, 4.4, -3.4, TOP, RUSTIC.edge);
        u.edge(-3.4, -3.4, -3.4, 1.6, TOP, RUSTIC.edge);
        u.edge(3.4, -3.4, 3.4, 1.6, TOP, RUSTIC.edge);
        u.edge(-3.4, 1.6, 3.4, 1.6, TOP, RUSTIC.edge);
        double[][] uLegs = {{-4.25, 2.45}, {4.25, 2.45}, {-4.25, -3.25}, {4.25, -3.25}, {-3.55, 1.75}, {3.55, 1.75}, {-3.55, -3.25}, {3.55, -3.25}, {0, 2.45}, {0, 1.75}, {-4.25, -0.8}, {4.25, -0.8}};
        for (double[] l : uLegs) u.leg(l[0], l[1], 0.12, TOP - 0.15, RUSTIC.leg);
        double[] armZ = {-2.7, -1.5, -0.3, 0.9};
        for (int s = 0; s < 16; s++) {
            double cx, cz, a, hx, hz;
            if (s < 4) { cx = -5.0; cz = armZ[s]; a = -Math.PI / 2; hx = -3.95; hz = cz; }
            else if (s < 12) { cx = -3.5 + (s - 4); cz = 3.2; a = 0; hx = cx; hz = 2.15; }
            else { cx = 5.0; cz = armZ[15 - s]; a = Math.PI / 2; hx = 3.95; hz = cz; }
            u.chair(s, cx, cz, SEAT, a, RUSTIC);
            u.seat(cx, cz, SEAT, a, hx, hz);
        }
        out.add(new Def(5, "ushape", TOP, u.parts, u.seats, List.of(
                Shape.rect(-4.25, 4.25, 1.75, 2.45), Shape.rect(-4.25, -3.55, -3.25, 2.45), Shape.rect(3.55, 4.25, -3.25, 2.45))));
        return List.copyOf(out);
    }

    public static Def byId(String id) {
        for (Def d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    private JjanTables() {}
}
