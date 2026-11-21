package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.jjan.JjanCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 잔 장식 (레몬·라임·오렌지 조각, 올리브, 체리, 우산, 빨대, 소금 테두리, 커피콩, 계피, 티백).
 * 원본 props.js 의 buildGarnish 를 그대로 옮겼다: 좌표는 잔 복셀, 색은 상자마다 조금씩 다른 밝기.
 * 원본처럼 네 묶음으로 나눈다: 마지막 모금에 먹는 것, 수면에 뜨는 것(높이 0 = 수면), 꽂아 두는 것, 소금 테두리.
 */
public final class JjanGarnish {
    public static final int EDIBLE = 0, FLOATING = 1, PROP = 2, SALT = 3;
    private static final Set<String> EDIBLES = Set.of("lime", "lemon", "orange", "olive", "cherry");
    private static final Map<String, int[]> CITRUS = Map.of(
            "lime", new int[]{0x4e8f2b, 0xb8d86a},
            "lemon", new int[]{0xe8c62a, 0xfaeb8a},
            "orange", new int[]{0xe8781e, 0xf6a94a});

    /** 묶음별 상자: 위치 {x0, y0, z0, x1, y1, z1} 반복, 색은 상자마다 하나. */
    private final float[][] boxes = new float[4][];
    private final int[][] colors = new int[4][];

    private record Key(JjanCatalog.Glass glass, List<String> list) {}

    private static final Map<Key, JjanGarnish> CACHE = new HashMap<>();
    private static final JjanGarnish NONE = new JjanGarnish();

    public static synchronized JjanGarnish of(JjanCatalog.Glass glass, List<String> list) {
        if (list.isEmpty()) return NONE;
        if (CACHE.size() > 256) CACHE.clear();
        return CACHE.computeIfAbsent(new Key(glass, list), k -> new JjanGarnish(k.glass, k.list));
    }

    private JjanGarnish() {
        for (int g = 0; g < 4; g++) { boxes[g] = new float[0]; colors[g] = new int[0]; }
    }

    public boolean has(int group) {
        return colors[group].length > 0;
    }

    public void draw(VertexConsumer vc, PoseStack.Pose e, int light, int group, float lift) {
        float[] b = boxes[group];
        int[] c = colors[group];
        for (int i = 0; i < c.length; i++) {
            int o = i * 6;
            JjanGlassMesh.box(vc, e, b[o], b[o + 1] + lift, b[o + 2], b[o + 3], b[o + 4] + lift, b[o + 5], 0xFF000000 | c[i], light);
        }
    }

    // ------------------------------------------------------------------ 만들기 (원본 BoxBuilder)

    private static final class Builder {
        final List<float[]> pos = new ArrayList<>();
        final List<Integer> col = new ArrayList<>();

        void box(double x, double y, double z, double w, double h, double d, int color, double jitter) {
            double s = 1 + (JjanGlassMesh.hash3((int) Math.round(x * 7), (int) Math.round(y * 7), (int) Math.round(z * 7)) - 0.5) * 2 * jitter;
            pos.add(new float[]{(float) x, (float) y, (float) z, (float) (x + w), (float) (y + h), (float) (z + d)});
            col.add(JjanGlassMesh.scaleLinear(color, s));
        }

        /** a 에서 c 까지 작은 상자를 이어 붙인 막대. color: 번호 → 색. */
        void line(double ax, double ay, double az, double cx, double cy, double cz, double size, java.util.function.IntUnaryOperator color) {
            double len = Math.sqrt((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay) + (cz - az) * (cz - az));
            int steps = Math.max(1, (int) Math.ceil(len / (size * 0.7)));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                double x = ax + (cx - ax) * t, y = ay + (cy - ay) * t, z = az + (cz - az) * t;
                box(x - size / 2, y - size / 2, z - size / 2, size, size, size, color.applyAsInt(i), 0.02);
            }
        }

        void wheel(String kind, double cx, double cy, double cz) {
            int[] c = CITRUS.get(kind);
            for (int i = -3; i < 3; i++) {
                for (int j = -3; j < 3; j++) {
                    double d = Math.hypot(i + 0.5, j + 0.5);
                    if (d >= 2.7) continue;
                    double a = Math.atan2(j + 0.5, i + 0.5);
                    boolean seg = Math.abs(Math.sin(a * 3)) < 0.18 && d < 2;
                    box(cx + i, cy + j, cz - 0.5, 1, 1, 1, d > 2 ? c[0] : seg ? 0xfff8dc : c[1], 0.04);
                }
            }
        }
    }

    private JjanGarnish(JjanCatalog.Glass glass, List<String> list) {
        Builder[] b = {new Builder(), new Builder(), new Builder(), new Builder()};
        int H = glass.height(), cs = glass.cavityStart();
        double rimR = glass.rimR();
        boolean wheelPlaced = false, umbrella = list.contains("umbrella");
        for (String g : list) {
            Builder o = b[EDIBLES.contains(g) ? EDIBLE : g.equals("beans") ? FLOATING : g.equals("salt") ? SALT : PROP];
            switch (g) {
                case "lime", "lemon", "orange" -> {
                    o.wheel(g, -rimR + (wheelPlaced ? 0.6 : 0.3), H - 0.4, wheelPlaced ? 2.2 : 0);
                    wheelPlaced = true;
                }
                case "olive" -> {
                    double tx = 0.6, ty = cs + 1.5, tz = 0.3;
                    o.line(tx, ty, tz, -rimR + 0.5, H + 5, 0.5, 0.5, i -> 0xc79a5e);
                    o.box(tx - 1, ty, tz - 1, 2, 2, 2, 0x7d8f2e, 0.06);
                    o.box(tx - 0.4, ty + 2, tz - 0.4, 0.8, 0.4, 0.8, 0xc23a2a, 0);
                }
                case "cherry" -> {
                    double x = umbrella ? 1.0 : -rimR + 1.2, z = umbrella ? 0.5 : -2.2, y = umbrella ? H + 1.5 : H - 0.2;
                    o.box(x - 1, y, z - 1, 2, 2, 2, 0xb3122a, 0.05);
                    o.line(x, y + 2, z, x + 1.2, y + 4.5, z + 0.3, 0.35, i -> 0x5b3a1e);
                }
                case "umbrella" -> {
                    double tx = 3.2, ty = H + 8, tz = -1;
                    o.line(0.8, H - 4, 0, tx, ty, tz, 0.45, i -> 0xe9d2a6);
                    int[] cols = {0xe86b8a, 0xfbe9d7};
                    double[][] tiers = {{4.2, 0}, {3.0, 1}, {1.6, 2}};
                    for (double[] tier : tiers) {
                        for (int i = -5; i < 5; i++) for (int k = -5; k < 5; k++) {
                            double d = Math.hypot(i + 0.5, k + 0.5);
                            if (d >= tier[0] || (tier[1] == 0 && d < 2.6)) continue;
                            int seg = (int) Math.floor(((Math.atan2(k + 0.5, i + 0.5) + Math.PI) / (Math.PI * 2)) * 8) % 2;
                            o.box(tx + i, ty + tier[1] - 1, tz + k, 1, 1, 1, cols[seg], 0.03);
                        }
                    }
                }
                case "straw" -> o.line(1.0, cs + 1, -1.2, rimR + 1.6, H + 6, -2.2, 0.8, i -> (i / 2) % 2 != 0 ? 0xffffff : 0xd8443a);
                case "salt" -> {
                    for (int x = -7; x < 7; x++) for (int z = -7; z < 7; z++) {
                        double d = Math.hypot(x + 0.5, z + 0.5);
                        if (d < glass.rimInner() || d >= glass.rimOuter() + 0.2 || JjanGlassMesh.hash3(x, H, z) < 0.25) continue;
                        o.box(x + 0.1, H, z + 0.1, 0.8, 0.5 + JjanGlassMesh.hash3(z, x, H) * 0.4, 0.8, 0xfbfaf4, 0.04);
                    }
                }
                case "beans" -> {
                    // 에스프레소 마티니: 거품 위에 커피콩 세 알 (높이 0 = 수면)
                    double[][] at = {{-1.6, -0.4}, {0.2, 0.9}, {1.4, -0.9}};
                    for (int i = 0; i < at.length; i++) {
                        o.box(at[i][0] - 0.6, 0, at[i][1] - 0.8, 1.2, 0.6, 1.6, i % 2 != 0 ? 0x3a1d0c : 0x4a2610, 0.04);
                        o.box(at[i][0] - 0.1, 0.6, at[i][1] - 0.7, 0.2, 0.05, 1.4, 0x24110a, 0);
                    }
                }
                case "cinnamon" -> o.line(1.2, cs + 3, 1.2, 2.8, H + 5, 2.4, 1, i -> i % 3 == 0 ? 0x6a3519 : 0x7f4220);
                case "teabag" -> {
                    double rim = -rimR + 0.5;
                    o.line(-1.2, cs + 2.5, 0.2, rim, H + 0.6, 0.2, 0.28, i -> 0xf4efe2);
                    o.line(rim - 0.3, H + 0.6, 0.2, rim - 1.2, H - 3, 0.2, 0.28, i -> 0xf4efe2);
                    o.box(rim - 2.5, H - 5.5, -0.1, 2.6, 2.6, 0.5, 0xe9b949, 0.02);
                    o.box(-2.4, cs + 0.2, -1, 2.4, 2.2, 1.6, 0x8a6236, 0.05);
                }
                default -> { }
            }
        }
        for (int g = 0; g < 4; g++) {
            boxes[g] = new float[b[g].pos.size() * 6];
            colors[g] = new int[b[g].col.size()];
            for (int i = 0; i < colors[g].length; i++) {
                System.arraycopy(b[g].pos.get(i), 0, boxes[g], i * 6, 6);
                colors[g][i] = b[g].col.get(i);
            }
        }
    }
}
