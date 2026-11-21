package dev.newyear.countdown.client;

import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 짠 화면 공용 그리기: 취기 칸, 잔 미리보기.
 */
final class JjanUi {
    private JjanUi() {}

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 취기 단계 이름과 다섯 칸 (오른쪽 끝이 right). 바닐라 HUD 글씨처럼 그림자 있는 글자와 단순한 칸. */
    static void tipsy(GuiGraphicsExtractor ctx, Font font, int right, int y, float level) {
        int stage = JjanRules.stage(level);
        int cells = Math.min(5, (int) Math.ceil(level / 0.2 - 0.001));
        int x = right - 5 * 5 + 1;
        for (int i = 0; i < 5; i++) {
            int cx = x + i * 5;
            ctx.fill(cx, y, cx + 4, y + 8, 0xFF000000);
            ctx.fill(cx + 1, y + 1, cx + 3, y + 7, i < cells ? (stage >= 3 ? 0xFFFF5555 : 0xFFFFAA00) : 0xFF3F3F3F);
        }
        Component name = JjanClient.stageName(level);
        ctx.text(font, name, x - 4 - font.width(name), y, stage >= 3 ? 0xFFFF5555 : 0xFFFFFFFF, true);
    }

    // ------------------------------------------------------------------ 잔 미리보기

    private record Run(int x0, int x1, int y, int rgb, boolean edge) {}

    private static final Map<JjanCatalog.Glass, List<Run>> FRONT = new HashMap<>();

    /** 잔을 앉은 사람 쪽에서 본 모습: (x, y) 마다 가장 앞 복셀. 같은 색이 이어지는 가로 줄로 묶어 둔다. */
    private static synchronized List<Run> front(JjanCatalog.Glass glass) {
        if (FRONT.size() > 128) FRONT.clear();
        return FRONT.computeIfAbsent(glass, g -> {
            Map<Long, int[]> cells = new HashMap<>();
            int[] v = g.voxels();
            for (int i = 0; i + 3 < v.length; i += 4) {
                long k = ((long) (v[i] + 64) << 16) | v[i + 1];
                int[] c = cells.get(k);
                if (c == null || v[i + 2] > c[0]) cells.put(k, new int[]{v[i + 2], JjanGlassMesh.jitter(v[i + 3], v[i], v[i + 1], v[i + 2], 0.04)});
            }
            List<Run> out = new ArrayList<>();
            for (int y = 0; y < g.height(); y++) {
                for (int x = -g.radius(); x < g.radius(); x++) {
                    int[] c = cells.get(((long) (x + 64) << 16) | y);
                    if (c == null) continue;
                    boolean edge = !cells.containsKey(((long) (x + 65) << 16) | y) || !cells.containsKey(((long) (x + 63) << 16) | y)
                            || !cells.containsKey(((long) (x + 64) << 16) | (y + 1)) || (y > 0 && !cells.containsKey(((long) (x + 64) << 16) | (y - 1)));
                    Run last = out.isEmpty() ? null : out.get(out.size() - 1);
                    if (last != null && last.y == y && last.x1 == x && last.rgb == c[1] && last.edge == edge) {
                        out.set(out.size() - 1, new Run(last.x0, x + 1, y, c[1], edge));
                    } else {
                        out.add(new Run(x, x + 1, y, c[1], edge));
                    }
                }
            }
            return out;
        });
    }

    /** 잔과 그 안의 술(색 띠·거품)을 (cx, baseY) 를 바닥 가운데로, boxW × boxH 안에 들어가게 그린다. */
    static void thumb(GuiGraphicsExtractor ctx, JjanCatalog.Glass glass, JjanCatalog.Look look, float cx, float baseY, float boxW, float boxH) {
        thumb(ctx, glass, look, cx, baseY, boxW, boxH, 1f);
    }

    /** remaining = 남은 비율 0..1 (가득 따른 양 대비). 비었으면 술 없이 잔만 그린다. */
    static void thumb(GuiGraphicsExtractor ctx, JjanCatalog.Glass glass, JjanCatalog.Look look, float cx, float baseY, float boxW, float boxH, float remaining) {
        float s = Math.min(boxH / (glass.height() + 1.5f), boxW / (glass.radius() * 2f));
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx, baseY);
        ctx.pose().scale(s, s);
        boolean clear = glass.transparent();
        if (look != null && clear && remaining > 0.01f) {
            int[][] cav = glass.cavity();
            int cs = glass.cavityStart();
            double top = glass.level(look.fill(), Math.min(1f, remaining)), span = Math.max(1e-3, top - cs);
            int[] lut = JjanGlassMesh.lut(look);
            int a = (int) (255 * Math.max(0.62, Math.min(1.0, look.opacity()))) << 24;
            int min = 0, max = -1, row = cs;
            for (int y = cs; y < cav.length && y + 0.5 < top; y++) {
                if (cav[y].length == 0) continue;
                min = Integer.MAX_VALUE; max = Integer.MIN_VALUE;
                for (int i = 0; i + 1 < cav[y].length; i += 2) { min = Math.min(min, cav[y][i]); max = Math.max(max, cav[y][i]); }
                int rgb = lut[(int) Math.round(Math.max(0, Math.min(1, (y + 0.5 - cs) / span)) * 16)];
                ctx.fill(min, -(y + 1), max + 1, -y, a | rgb);
                row = y + 1;
            }
            if (look.foamHeight() > 0 && max >= min && remaining > 0.5f) {
                int fh = Math.max(1, (int) Math.round(look.foamHeight()));
                ctx.fill(min, -(Math.min(glass.height(), row + fh)), max + 1, -row, 0xFF000000 | (look.foamColor() & 0xFFFFFF));
            }
        }
        for (Run r : front(glass)) {
            int color = !clear ? 0xFF000000 | r.rgb : r.edge ? 0xE07F9C9E : 0x50FFFFFF;
            ctx.fill(r.x0, -(r.y + 1), r.x1, -r.y, color);
        }
        ctx.pose().popMatrix();
    }
}
