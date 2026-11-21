package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.jjan.JjanCatalog;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * 잔 복셀 메시: 카탈로그의 복셀(원본 voxel.js 가 만든 것)에서 겉으로 드러난 면만 뽑아 두고 그린다.
 * 좌표는 복셀 단위(칸 x 는 x..x+1), 잔 바닥 중심이 원점. 호출자가 1/32 로 줄여 놓는다.
 *
 * 원본과 맞춘 것: 복셀마다 조금씩 다른 밝기(voxel.js 의 hash3 jitter), 재질별 유리 투명도, 술이 비치는 부분의 유리를 어둡게,
 * 칸 단위로 울퉁불퉁한 거품, 16단계 색 띠(gradientLUT), 따르는 그릇(병·캔·주전자)의 실제 모양.
 */
public final class JjanGlassMesh {
    private static final Map<JjanCatalog.Glass, JjanGlassMesh> CACHE = new HashMap<>();
    private static final int[][] D = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** 액체 옆면 방향 순서 (+X, -X, +Z, -Z). */
    private static final int[] SIDE = {0, 1, 4, 5};

    /** 겉면: {x, y, z, 방향(0..5 = +X -X +Y -Y +Z -Z), rgb(밝기 차이 적용), 표시} 반복. */
    private final int[] faces;
    private static final int FACE = 6;
    private static final int INNER = 1, GLINT = 2, RIM = 4;
    /** 층별 액체 옆면: {x, z, 방향(0 +X, 1 -X, 4 +Z, 5 -Z)} 반복. */
    private final int[][] liquidSides;
    /** 층별 칸의 이웃 칸 번호(+X, -X, +Z, -Z 순, 없으면 -1). 거품 기둥의 옆면을 그릴 때 쓴다. */
    private final int[][] neighbors;
    /** 층별 칸이 위로 몇 층까지 이어지는지(끝 층 + 1). 거품이 잔 벽을 뚫고 올라가지 않게 한다. */
    private final int[][] columnTop;
    private final JjanCatalog.Glass glass;

    private JjanGlassMesh(JjanCatalog.Glass g) {
        glass = g;
        boolean clear = g.transparent();
        double jitter = clear ? 0.025 : 0.05;
        int[] v = g.voxels();
        Set<Long> solid = new HashSet<>();
        for (int i = 0; i + 3 < v.length; i += 4) solid.add(key(v[i], v[i + 1], v[i + 2]));
        int[][] cav = g.cavity();
        Set<Long> hollow = new HashSet<>();
        for (int y = 0; y < cav.length; y++)
            for (int i = 0; i + 1 < cav[y].length; i += 2) hollow.add(key(cav[y][i], y, cav[y][i + 1]));
        int[] out = new int[v.length / 4 * 6 * FACE];
        int n = 0;
        for (int i = 0; i + 3 < v.length; i += 4) {
            int x = v[i], y = v[i + 1], z = v[i + 2];
            int rgb = jitter(v[i + 3], x, y, z, jitter);
            // 유리에 맺힌 빛: 앉은 사람이 볼 때 왼쪽 앞의 세로 한 줄, 그리고 잔 입구 테두리
            double angle = Math.atan2(z + 0.5, x + 0.5), dist = Math.hypot(x + 0.5, z + 0.5);
            boolean glint = clear && y > g.cavityStart() && y < g.height() - 1 && Math.abs(angle - 2.25) < 0.21 && dist <= g.contactR() + 0.5;
            for (int f = 0; f < 6; f++) {
                long next = key(x + D[f][0], y + D[f][1], z + D[f][2]);
                if (solid.contains(next)) continue;
                int flags = hollow.contains(next) ? INNER : 0;
                if (glint && f != 2 && f != 3 && flags == 0) flags |= GLINT;
                if (clear && f == 2 && y == g.height() - 1) flags |= RIM;
                out[n++] = x; out[n++] = y; out[n++] = z; out[n++] = f; out[n++] = rgb; out[n++] = flags;
            }
        }
        faces = Arrays.copyOf(out, n);

        liquidSides = new int[cav.length][];
        neighbors = new int[cav.length][];
        columnTop = new int[cav.length][];
        Map<Long, Integer> above = new HashMap<>();
        for (int y = cav.length - 1; y >= 0; y--) {
            int cells = cav[y].length / 2;
            Map<Long, Integer> index = new HashMap<>();
            for (int i = 0; i < cells; i++) index.put(key(cav[y][i * 2], 0, cav[y][i * 2 + 1]), i);
            int[] s = new int[cells * 4 * 3];
            int[] nb = new int[cells * 4];
            int[] top = new int[cells];
            int m = 0;
            for (int i = 0; i < cells; i++) {
                int x = cav[y][i * 2], z = cav[y][i * 2 + 1];
                for (int d = 0; d < 4; d++) {
                    int f = SIDE[d];
                    Integer other = index.get(key(x + D[f][0], 0, z + D[f][2]));
                    nb[i * 4 + d] = other == null ? -1 : other;
                    if (other != null) continue;
                    s[m++] = x; s[m++] = z; s[m++] = f;
                }
                Integer up = above.get(key(x, 0, z));
                // 맨 위 층까지 이어진 칸은 거품이 잔 입구 위로 한 칸 더 올라올 수 있다 (원본 yMax = H + 1)
                top[i] = up != null ? columnTop[y + 1][up] : y + 1 >= cav.length ? cav.length + 1 : y + 1;
            }
            liquidSides[y] = Arrays.copyOf(s, m);
            neighbors[y] = nb;
            columnTop[y] = top;
            above = index;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    /** 카탈로그를 다시 읽으면 잔 객체가 새로 만들어지므로 메시도 그때 새로 만든다. */
    public static synchronized JjanGlassMesh of(JjanCatalog.Glass g) {
        if (CACHE.size() > 128) CACHE.clear();
        return CACHE.computeIfAbsent(g, JjanGlassMesh::new);
    }

    // ------------------------------------------------------------------ 색

    /** 원본 voxel.js 의 hash3: 결정적인 해시 → [0, 1). */
    public static double hash3(int x, int y, int z) {
        int h = (int) (x * 374761393L + y * 668265263L + z * 2147483647L);
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static double toLinear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int toSrgb(double v) {
        v = Math.max(0, Math.min(1, v));
        return (int) Math.round(255 * (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055));
    }

    /** 색을 선형 공간에서 s 배 밝게 (원본은 선형 색에 밝기 차이를 곱한다). */
    public static int scaleLinear(int rgb, double s) {
        return toSrgb(toLinear(rgb >> 16 & 255) * s) << 16 | toSrgb(toLinear(rgb >> 8 & 255) * s) << 8 | toSrgb(toLinear(rgb & 255) * s);
    }

    /** 복셀 (x, y, z) 의 밝기 차이를 적용한 색. */
    public static int jitter(int rgb, int x, int y, int z, double amount) {
        return scaleLinear(rgb, 1 + (hash3(x, y, z) - 0.5) * 2 * amount);
    }

    /** 매 프레임 쓰는 가벼운 밝기 조절 (sRGB 값에 바로 곱한다). */
    private static int scaleFast(int rgb, float k) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * k)), g = Math.min(255, (int) ((rgb >> 8 & 255) * k)), b = Math.min(255, (int) ((rgb & 255) * k));
        return r << 16 | g << 8 | b;
    }

    /** 칸마다 다른 밝기 (선형 jitter 를 sRGB 배율로 어림한 것). */
    private static float cellShade(int x, int y, int z, double amount) {
        return (float) (1 + (hash3(x, y, z) - 0.5) * 2 * amount / 2.2);
    }

    public static int mixLinear(int a, int b, double k) {
        double r = toLinear(a >> 16 & 255), g = toLinear(a >> 8 & 255), bl = toLinear(a & 255);
        r += (toLinear(b >> 16 & 255) - r) * k;
        g += (toLinear(b >> 8 & 255) - g) * k;
        bl += (toLinear(b & 255) - bl) * k;
        return toSrgb(r) << 16 | toSrgb(g) << 8 | toSrgb(bl);
    }

    private static final Map<JjanCatalog.Look, int[]> LUTS = new IdentityHashMap<>();

    /** 원본 gradientLUT: 높이 0..1 을 17칸으로 나눈 술 색. 색 띠가 없는 술은 한 가지 색. */
    public static synchronized int[] lut(JjanCatalog.Look look) {
        if (LUTS.size() > 512) LUTS.clear();
        return LUTS.computeIfAbsent(look, l -> {
            int[] lut = new int[17];
            double[] at = l.gradientAt();
            int[] col = l.gradientColor();
            for (int n = 0; n <= 16; n++) {
                if (at == null || col == null || at.length < 2) { lut[n] = l.color() & 0xFFFFFF; continue; }
                double t = n / 16.0;
                int i = 1;
                while (i < at.length - 1 && t > at[i]) i++;
                double span = at[i] - at[i - 1];
                double u = Math.max(0, Math.min(1, (t - at[i - 1]) / (span == 0 ? 1 : span)));
                lut[n] = mixLinear(col[i - 1] & 0xFFFFFF, col[i] & 0xFFFFFF, u);
            }
            return lut;
        });
    }

    /** 유리 불투명도. 원본(thick 0.24, crystal 0.13, 그 외 0.17)에 반사광이 없는 만큼을 조금 더했다. */
    public static float glassAlpha(JjanCatalog.Glass g) {
        return switch (g.material()) {
            case "thick" -> 0.30f;
            case "crystal" -> 0.20f;
            default -> 0.24f;
        };
    }

    /** 술이 비치는 부분의 유리 밝기 (원본 glass.js: 술이 어두울수록 앞 유리를 더 어둡게). */
    public static float glassDim(JjanCatalog.Look look) {
        int c = look.color();
        double lum = 0.2126 * toLinear(c >> 16 & 255) + 0.7152 * toLinear(c >> 8 & 255) + 0.0722 * toLinear(c & 255);
        double dim = Math.max(0.3, Math.min(1, 0.3 + Math.sqrt(lum) * 0.9));
        return (float) Math.pow(dim, look.opacity() > 0.6 ? 1 : 0.5);
    }

    // ------------------------------------------------------------------ 잔

    /**
     * 잔 껍데기. alpha < 1 이면 반투명 버퍼에 그려야 한다. ghost 가 0 이 아니면 그 색으로 덮는다.
     * inner 가 false 면 술과 맞닿는 안쪽 면은 건너뛴다: 투명한 잔은 겉면 한 겹만 그려야 유리가 겹겹이 쌓여 술 색을 뿌옇게 가리지 않는다.
     * level(복셀 높이) 아래의 유리는 dim 배로 어둡게 그린다 (level < 0 이면 하지 않는다).
     */
    public void shell(VertexConsumer vc, PoseStack.Pose e, int light, float alpha, int ghost, boolean inner, double level, float dim) {
        boolean clear = alpha < 1f;
        int base = (int) (255 * alpha);
        for (int i = 0; i < faces.length; i += FACE) {
            int flags = faces[i + 5];
            if (!inner && (flags & INNER) != 0) continue;
            int argb;
            if (ghost != 0) {
                argb = ghost;
            } else {
                int rgb = faces[i + 4], a = base;
                if (clear) {
                    if ((flags & GLINT) != 0) { a = Math.min(255, (int) (base * 1.9f)); rgb = 0xFFFFFF; }
                    else if ((flags & RIM) != 0) a = Math.min(255, (int) (base * 1.5f));
                    else if (level >= 0 && faces[i + 1] + 0.5 < level) rgb = scaleFast(rgb, dim);
                }
                argb = (a << 24) | rgb;
            }
            face(vc, e, faces[i], faces[i + 1], faces[i + 1] + 1, faces[i + 2], faces[i + 3], argb, light);
        }
    }

    /**
     * 액체: 바닥(cavityStart)부터 level(복셀 높이)까지, 칸마다 색을 정한다. lut 는 {@link #lut}, span 은 색 띠가 걸치는 높이
     * (층으로 쌓은 술은 처음 따른 높이, 그 외는 지금 액면까지). 거품은 그 위에 foamH 만큼, 칸마다 높이가 다르게 얹는다(phase 가 바뀌면 다시 흔들린다).
     * swirl > 0 이면 술이 돌아 액면이 가운데는 패이고 벽 쪽은 올라간다 (원작 swirlTerm: swirl × 2.6 × (r / 잔 반지름 − 0.55)).
     */
    public void liquid(VertexConsumer vc, PoseStack.Pose e, int light, double level, int[] lut, double span, int alpha, double jitter,
                       double foamH, int foamRgb, int phase, double swirl) {
        int[][] cav = glass.cavity();
        int start = glass.cavityStart();
        if (level <= start + 0.02) return;
        span = Math.max(1e-3, span);
        int a = alpha << 24;
        int last = -1;
        // 도는 술은 가장 낮은 곳(가운데)까지만 통으로 그리고, 그 위는 칸마다 기둥으로 올린다
        double low = swirl > 0.01 ? Math.max(start + 0.1, level - swirl * 2.6 * 0.55) : level;
        for (int y = start; y < cav.length && y < low; y++) {
            float y0 = y, y1 = (float) Math.min(y + 1, low);
            int rgb = lut[(int) Math.round(Math.max(0, Math.min(1, ((y0 + y1) / 2 - start) / span)) * 16)];
            int[] s = liquidSides[y];
            for (int i = 0; i < s.length; i += 3) {
                face(vc, e, s[i], y0, y1, s[i + 1], s[i + 2], a | scaleFast(rgb, cellShade(s[i], y, s[i + 1], jitter)), light);
            }
            last = y;
        }
        if (last < 0) return;
        float top = (float) Math.min(low, last + 1);
        int[] c = cav[last];
        int cells = c.length / 2;
        int[] nb = neighbors[last], limit = columnTop[last];
        int topRgb = lut[(int) Math.round(Math.max(0, Math.min(1, (top - 0.5 - start) / span)) * 16)];
        // 칸마다 술의 높이
        float[] lt = new float[cells];
        double cr = glass.cavityRadius((int) Math.floor(level));
        for (int i = 0; i < cells; i++) {
            lt[i] = top;
            if (swirl > 0.01) {
                double r = Math.hypot(c[i * 2] + 0.5, c[i * 2 + 1] + 0.5);
                lt[i] = (float) Math.max(top, Math.min(limit[i], level + swirl * 2.6 * (r / cr - 0.55)));
            }
        }
        boolean foam = foamH > 0.05;
        for (int i = 0; i < cells; i++) {
            int x = c[i * 2], z = c[i * 2 + 1];
            int rgb = a | scaleFast(topRgb, cellShade(x, last, z, jitter));
            if (!foam) face(vc, e, x, lt[i] - 1, lt[i], z, 2, rgb, light);
            if (lt[i] <= top) continue;
            for (int d = 0; d < 4; d++) {
                int other = nb[i * 4 + d];
                float from = other < 0 ? top : Math.max(top, lt[other]);
                if (lt[i] > from) face(vc, e, x, from, lt[i], z, SIDE[d], rgb, light);
            }
        }
        if (!foam) return;
        // 거품: 칸마다 높이가 다른 기둥 (원본: 액면 + 거품 높이 + 칸별 들쭉날쭉, 칸 단위로 끊어진다)
        float[] h = new float[cells];
        for (int i = 0; i < cells; i++) {
            int x = c[i * 2], z = c[i * 2 + 1];
            double bump = (hash3(x, z, phase) - 0.5) * 1.1;
            double want = Math.ceil(Math.max(lt[i], start + 0.5) + foamH + bump - 0.5);
            h[i] = (float) Math.max(lt[i], Math.min(limit[i], want));
        }
        int fa = 0xF5000000;
        for (int i = 0; i < cells; i++) {
            int x = c[i * 2], z = c[i * 2 + 1];
            int rgb = fa | scaleFast(foamRgb, cellShade(x, (int) h[i], z, 0.03));
            face(vc, e, x, h[i] - 1, h[i], z, 2, rgb, light);
            if (h[i] <= lt[i]) continue;
            for (int d = 0; d < 4; d++) {
                int other = nb[i * 4 + d];
                float from = other < 0 ? lt[i] : Math.max(lt[i], h[other]);
                if (h[i] > from) face(vc, e, x, from, h[i], z, SIDE[d], rgb, light);
            }
        }
    }

    /** 이 층에서 액체 옆면(잔 벽과 맞닿는 면)의 개수. */
    public int sideCount(int layer) {
        return layer < 0 || layer >= liquidSides.length ? 0 : liquidSides[layer].length / 3;
    }

    /** 탄산 방울 하나: 이 층의 index 번째 액체 옆면 바로 바깥(유리 안쪽)에 작은 상자를 그린다. */
    public void bubble(VertexConsumer vc, PoseStack.Pose e, int layer, int index, float y, float size, int argb, int light) {
        int[] s = liquidSides[layer];
        int o = index * 3;
        float cx = s[o] + 0.5f, cz = s[o + 1] + 0.5f, out = 0.5f + size * 0.5f + 0.02f;
        switch (s[o + 2]) {
            case 0 -> cx += out;
            case 1 -> cx -= out;
            case 4 -> cz += out;
            default -> cz -= out;
        }
        float h = size / 2;
        box(vc, e, cx - h, y - h, cz - h, cx + h, y + h, cz + h, argb, light);
    }

    // ------------------------------------------------------------------ 따르는 그릇·얼음

    /** 따르는 그릇(병·캔·주전자 …). 입구가 원점. alpha 는 유리로 된 그릇(피처, 믹싱 글라스)만 255 보다 작다. */
    public static void vessel(VertexConsumer vc, PoseStack.Pose e, int light, JjanCatalog.Vessel v, JjanCatalog.Bottle b, int alpha) {
        int[] f = v.faces(), def = v.defaults();
        int a = alpha << 24;
        for (int i = 0; i < f.length; i += 5) {
            int c = f[i + 4], rgb;
            if (c >= 0) {
                rgb = c;
            } else {
                int n = -c - 1, role = n & 3;
                int base = switch (role) {
                    case 0 -> b.color() >= 0 ? b.color() : def[0];
                    case 1 -> b.label() >= 0 ? b.label() : def[1];
                    case 2 -> b.cap() >= 0 ? b.cap() : def[2];
                    default -> b.liquid() >= 0 ? b.liquid() : b.stream();
                };
                rgb = scaleFast(base & 0xFFFFFF, 1 + ((n >> 2) / 1000f - 1) / 2.2f);
            }
            face(vc, e, f[i], f[i + 1], f[i + 1] + 1, f[i + 2], f[i + 3], a | rgb, light);
        }
    }

    private static int[] sphere;

    /** 동그란 얼음 (원본 buildIceGeometry: 반지름 2.45 복셀 공). 중심이 원점. */
    public static void iceSphere(VertexConsumer vc, PoseStack.Pose e, int light, int alpha) {
        if (sphere == null) {
            boolean[][][] in = new boolean[6][6][6];
            for (int x = -3; x < 3; x++) for (int y = -3; y < 3; y++) for (int z = -3; z < 3; z++)
                in[x + 3][y + 3][z + 3] = Math.sqrt((x + 0.5) * (x + 0.5) + (y + 0.5) * (y + 0.5) + (z + 0.5) * (z + 0.5)) < 2.45;
            int[] out = new int[6 * 6 * 6 * 6 * 5];
            int n = 0;
            for (int x = -3; x < 3; x++) for (int y = -3; y < 3; y++) for (int z = -3; z < 3; z++) {
                if (!in[x + 3][y + 3][z + 3]) continue;
                int rgb = jitter(y > 0 ? 0xeaf7fc : 0xcfe7f0, x, y, z, 0.04);
                for (int f = 0; f < 6; f++) {
                    int ox = x + D[f][0] + 3, oy = y + D[f][1] + 3, oz = z + D[f][2] + 3;
                    if (ox >= 0 && ox < 6 && oy >= 0 && oy < 6 && oz >= 0 && oz < 6 && in[ox][oy][oz]) continue;
                    out[n++] = x; out[n++] = y; out[n++] = z; out[n++] = f; out[n++] = rgb;
                }
            }
            sphere = Arrays.copyOf(out, n);
        }
        int a = alpha << 24;
        for (int i = 0; i < sphere.length; i += 5) face(vc, e, sphere[i], sphere[i + 1], sphere[i + 1] + 1, sphere[i + 2], sphere[i + 3], a | sphere[i + 4], light);
    }

    // ------------------------------------------------------------------ 면

    /** 칸 (x, y0..y1, z) 의 한 면. */
    static void face(VertexConsumer vc, PoseStack.Pose e, float x, float y0, float y1, float z, int dir, int argb, int light) {
        float x1 = x + 1, z1 = z + 1;
        switch (dir) {
            case 0 -> quad(vc, e, x1, y0, z1, x1, y0, z, x1, y1, z, x1, y1, z1, 1, 0, 0, argb, light);
            case 1 -> quad(vc, e, x, y0, z, x, y0, z1, x, y1, z1, x, y1, z, -1, 0, 0, argb, light);
            case 2 -> quad(vc, e, x, y1, z1, x1, y1, z1, x1, y1, z, x, y1, z, 0, 1, 0, argb, light);
            case 3 -> quad(vc, e, x, y0, z, x1, y0, z, x1, y0, z1, x, y0, z1, 0, -1, 0, argb, light);
            case 4 -> quad(vc, e, x, y0, z1, x1, y0, z1, x1, y1, z1, x, y1, z1, 0, 0, 1, argb, light);
            default -> quad(vc, e, x1, y0, z, x, y0, z, x, y1, z, x1, y1, z, 0, 0, -1, argb, light);
        }
    }

    /** 임의 상자 (현재 변환의 단위). */
    static void box(VertexConsumer vc, PoseStack.Pose e, float x0, float y0, float z0, float x1, float y1, float z1, int argb, int light) {
        quad(vc, e, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, argb, light);
        quad(vc, e, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, argb, light);
        quad(vc, e, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, argb, light);
        quad(vc, e, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, argb, light);
        quad(vc, e, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, argb, light);
        quad(vc, e, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, argb, light);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose e, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz, int argb, int light) {
        vc.addVertex(e, ax, ay, az).setColor(argb).setUv(0.25f, 0.75f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, nx, ny, nz);
        vc.addVertex(e, bx, by, bz).setColor(argb).setUv(0.75f, 0.75f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, nx, ny, nz);
        vc.addVertex(e, cx, cy, cz).setColor(argb).setUv(0.75f, 0.25f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, nx, ny, nz);
        vc.addVertex(e, dx, dy, dz).setColor(argb).setUv(0.25f, 0.25f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, nx, ny, nz);
    }
}
