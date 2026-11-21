package dev.newyear.countdown.client.ai;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Map;

/**
 * 소원 UI 디자인 시스템. AI 는 내용과 구조만 정하고, 모양은 전부 여기서 나온다.
 * 방향: 한 해의 마지막 밤, 첫눈이 내리는 크리스마스 — 짙은 전나무색 바탕, 붉은 열매색 테두리와 금빛 선,
 * 패널 위에 소복이 쌓인 눈과 호랑가시 장식, 캔디케인 밑단, 꼬마전구 줄, 천천히 내리는 눈.
 * 카운트다운 HUD 의 기본 테마(HudTheme.CHRISTMAS)와 같은 색 계열을 쓴다.
 */
public final class WishUi {
    private WishUi() {}

    /** 제목·알림 앞에 붙는 글리프. */
    public static final String GLYPH = "❄";

    // ---------------------------------------------------------------- 색 토큰
    public static final int BG_TOP = 0xF0102A1C;
    public static final int BG_BOTTOM = 0xF00A1A14;
    public static final int BG_SOFT = 0xC80C2017;
    public static final int EDGE_DARK = 0xFF04100A;
    public static final int EDGE_GOLD = 0xFFD9B45F;
    public static final int EDGE_GOLD_DIM = 0xFF7A6232;
    public static final int BERRY = 0xFFC6303A;
    public static final int BERRY_DIM = 0xFF7E1F27;
    public static final int LEAF = 0xFF3E9B5B;
    public static final int LEAF_DARK = 0xFF2B6E42;
    public static final int INNER_LINE = 0x33F2D58A;
    public static final int TEXT = 0xFFF4EFE2;
    public static final int TEXT_DIM = 0xFFA9BDAF;
    public static final int TEXT_FAINT = 0xFF6F877A;
    public static final int GOLD = 0xFFF2D58A;
    public static final int SNOW = 0xFFF2FAFF;
    public static final int FROST = 0xFFA8DDF0;
    public static final int LANTERN = 0xFFF0A65A;
    public static final int SPIRIT_BUBBLE = 0xD0173A2B;
    public static final int PLAYER_BUBBLE = 0xD04A1D22;
    public static final int PLAYER_TEXT = 0xFFFBE6D8;
    public static final int SUCCESS = 0xFF8FD9A4;
    public static final int WARNING = 0xFFE9B56A;
    public static final int DANGER = 0xFFF07F7F;
    public static final int MYSTIC = 0xFFBFB2F2;
    public static final int INFO = 0xFF8EC5F0;
    public static final int CURSE = 0xFFC27AD6;
    public static final int TIMER = 0xFF7FDCE6;
    public static final int BAR_BG = 0xFF06140E;

    // 면(surface) 토큰: 탭·입력칸·카드·목록 줄·버튼
    public static final int TAB_SELECTED = 0xE01F4A34;
    public static final int TAB_HOVER = 0xC0183A2A;
    public static final int TAB_IDLE = 0x800F261B;
    public static final int FIELD_BG = 0xE006140E;
    public static final int WELL_BG = 0xC0050F0A;
    public static final int CARD_TOP = 0xF0163325;
    public static final int CARD_BOTTOM = 0xF00E2219;
    public static final int ROW_SELECTED = 0xC01F4A34;
    public static final int ROW_HOVER = 0x80183A2A;
    public static final int BUTTON_IDLE = 0xE0143325;
    public static final int BUTTON_HOVER = 0xE01C4632;
    public static final int BUTTON_DISABLED = 0xC00C1E16;
    public static final int CODE_TEXT = 0xFFCFE6D8;

    private static final int[] BULBS = {0xFFE0464F, 0xFFF2D58A, 0xFF6FD08C, 0xFF8EC5F0};

    /** 호랑가시: 잎 두 장 사이에 열매 송이 (13x6). */
    private static final String[] HOLLY = {
            ".LL.......LL.",
            "LLLL.....LLLL",
            "LDDLL.R.LLDDL",
            ".LLDLRHRLDLL.",
            "..LL.RRR.LL..",
            "......R......",
    };
    private static final int HOLLY_W = 13;

    public static int kindColor(String kind) {
        if (kind == null) return GOLD;
        return switch (kind) {
            case "success", "blessing" -> SUCCESS;
            case "warning" -> WARNING;
            case "danger" -> DANGER;
            case "mystic" -> MYSTIC;
            case "curse" -> CURSE;
            case "info" -> INFO;
            case "timer" -> TIMER;
            default -> GOLD;
        };
    }

    public static String kindGlyph(String kind) {
        if (kind == null) return GLYPH;
        return switch (kind) {
            case "curse" -> "☾";
            case "blessing" -> "❀";
            case "timer" -> "⌛";
            case "info" -> "❅";
            default -> GLYPH;
        };
    }

    // ---------------------------------------------------------------- 시간·애니메이션

    private static final long START_NANOS = System.nanoTime();

    /** 게임을 켠 뒤 흐른 초. (작은 값이라 float 로도 프레임 단위 정밀도가 유지된다) */
    public static float now() {
        return (System.nanoTime() - START_NANOS) / 1_000_000_000f;
    }

    public static float easeOut(float t) {
        t = clamp01(t);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    public static float clamp01(float t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static int alpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        int na = Math.round(base * clamp01(a));
        // 글자는 알파가 너무 낮으면 그려지지 않으므로 최소값을 둔다
        return (Math.max(na, 4) << 24) | (argb & 0xFFFFFF);
    }

    public static int textAlpha(int argb, float a) {
        int na = Math.round(255 * clamp01(a));
        return (Math.max(na, 0x10) << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = clamp01(t);
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    // ---------------------------------------------------------------- 패널

    /**
     * 큰 패널: 전나무색 바탕, 붉은 테두리, 금빛 안쪽 선, 위에 쌓인 눈과 호랑가시, 캔디케인 밑단.
     * 눈은 y0 위로 2픽셀까지 올라온다.
     */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float a) {
        int w = x1 - x0, h = y1 - y0;
        if (w < 6 || h < 6) return;
        // 바깥 어두운 테두리 (모서리 깎기)
        g.fill(x0 + 1, y0, x1 - 1, y0 + 1, alpha(EDGE_DARK, a));
        g.fill(x0 + 1, y1 - 1, x1 - 1, y1, alpha(EDGE_DARK, a));
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, alpha(EDGE_DARK, a));
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, alpha(EDGE_DARK, a));
        // 바탕
        g.fillGradient(x0 + 1, y0 + 1, x1 - 1, y1 - 1, alpha(BG_TOP, a), alpha(BG_BOTTOM, a));
        // 붉은 테두리 (위는 밝게, 아래로 갈수록 어둡게)
        g.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, alpha(BERRY, a));
        g.fillGradient(x0 + 1, y0 + 2, x0 + 2, y1 - 3, alpha(BERRY, a), alpha(BERRY_DIM, a));
        g.fillGradient(x1 - 2, y0 + 2, x1 - 1, y1 - 3, alpha(BERRY, a), alpha(BERRY_DIM, a));
        // 캔디케인 밑단 (2px 사선 줄무늬)
        for (int row = 0; row < 2; row++) {
            int y = y1 - 3 + row;
            for (int x = x0 + 1; x < x1 - 1; ) {
                int k = Math.floorMod(x - x0 + row, 6);
                int end = Math.min(x1 - 1, x + 3 - k % 3);
                g.fill(x, y, end, y + 1, alpha(k < 3 ? BERRY : SNOW, a));
                x = end;
            }
        }
        // 안쪽 가는 금빛 선
        g.outline(x0 + 3, y0 + 3, w - 6, h - 7, alpha(INNER_LINE, a));
        snowCap(g, x0 + 1, x1 - 1, y0, a);
        if (w >= 60) {
            sprite(g, HOLLY, x0 + 4, y0 - 2, a);
            sprite(g, HOLLY, x1 - 4 - HOLLY_W, y0 - 2, a);
        }
    }

    /** 가벼운 패널 (HUD 용): 왼쪽 강조 막대와 위쪽 서리 선만 있다. */
    public static void softPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int accent, float a) {
        g.fill(x0 + 1, y0, x1 - 1, y1, alpha(BG_SOFT, a));
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, alpha(BG_SOFT, a));
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, alpha(BG_SOFT, a));
        g.fill(x0, y0 + 1, x0 + 2, y1 - 1, alpha(accent, a * 0.95f));
        g.fill(x0 + 2, y0, x1 - 1, y0 + 1, alpha(SNOW, a * 0.28f));
    }

    /** 가로선 위에 쌓인 눈: 선 한 줄 + 불규칙한 두 줄 높이의 눈 더미. */
    public static void snowCap(GuiGraphicsExtractor g, int x0, int x1, int y, float a) {
        int snow = alpha(SNOW, a);
        g.fill(x0, y, x1, y + 1, snow);
        // 29픽셀마다 되풀이되는 큰 더미(11칸, 2줄)와 작은 더미(6칸)
        int right = x1 - 2;
        for (int base = x0; base < right; base += 29) {
            mound(g, base + 2, base + 13, y - 1, right, snow);
            mound(g, base + 4, base + 10, y - 2, right, snow);
            mound(g, base + 18, base + 24, y - 1, right, snow);
            mound(g, base + 20, base + 22, y - 2, right, snow);
        }
    }

    private static void mound(GuiGraphicsExtractor g, int xa, int xb, int y, int right, int color) {
        if (xa < right) g.fill(xa, y, Math.min(xb, right), y + 1, color);
    }

    /** 문자 격자 스프라이트. L/D = 잎, R = 열매, H = 열매 반짝임, 그 밖은 비움. */
    private static void sprite(GuiGraphicsExtractor g, String[] rows, int x, int y, float a) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                int color = switch (rows[r].charAt(c)) {
                    case 'L' -> LEAF;
                    case 'D' -> LEAF_DARK;
                    case 'R' -> BERRY;
                    case 'H' -> 0xFFFFB3B8;
                    default -> 0;
                };
                if (color != 0) g.fill(x + c, y + r, x + c + 1, y + r + 1, alpha(color, a));
            }
        }
    }

    // ---------------------------------------------------------------- 눈·전구 장식

    /** 픽셀 눈송이: 십자 팔(길이 r)과 한 칸 짧은 대각선 팔. r = 1 이면 3x3 십자. */
    public static void flake(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        g.fill(cx, cy - r, cx + 1, cy + r + 1, color);
        g.fill(cx - r, cy, cx + r + 1, cy + 1, color);
        int soft = alpha(color, 0.55f);
        for (int d = 1; d < r; d++) {
            g.fill(cx - d, cy - d, cx - d + 1, cy - d + 1, soft);
            g.fill(cx + d, cy - d, cx + d + 1, cy - d + 1, soft);
            g.fill(cx - d, cy + d, cx - d + 1, cy + d + 1, soft);
            g.fill(cx + d, cy + d, cx + d + 1, cy + d + 1, soft);
        }
    }

    /** 반짝이는 눈송이. phase 에 따라 밝기가 바뀌고 가장 밝을 때 가운데가 하얗게 빛난다. */
    public static void twinkle(GuiGraphicsExtractor g, int cx, int cy, float phase, int color) {
        twinkle(g, cx, cy, 2, phase, color);
    }

    public static void twinkle(GuiGraphicsExtractor g, int cx, int cy, int r, float phase, int color) {
        float tw = 0.5f + 0.5f * (float) Math.sin(phase);
        flake(g, cx, cy, r, alpha(color, 0.35f + 0.65f * tw));
        if (tw > 0.6f) g.fill(cx, cy, cx + 1, cy + 1, 0xFFFFFFFF);
    }

    /**
     * 영역 안에 천천히 내리는 눈. 눈송이마다 속도·흔들림·밝기가 다르고, 시간만으로 위치가 정해져 상태를 두지 않는다.
     */
    public static void snowfall(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float t, int count, float a) {
        int w = x1 - x0, h = y1 - y0;
        if (w < 8 || h < 8 || a <= 0.01f) return;
        for (int i = 0; i < count; i++) {
            float speed = 7f + frac(i * 0.4142f) * 11f;
            float fy = frac(frac(i * 0.7548f) + t * speed / h) * h;
            float fx = frac(i * 0.6180f) * w + (float) Math.sin(t * 0.7f + i * 1.9f) * 5f;
            int px = x0 + Math.floorMod(Math.round(fx), w), py = y0 + (int) fy;
            boolean big = i % 4 == 0;
            int size = big ? 2 : 1;
            if (px + size > x1 || py + size > y1) continue;
            g.fill(px, py, px + size, py + size, alpha(SNOW, a * (big ? 0.5f : 0.4f + 0.6f * frac(i * 0.37f))));
        }
    }

    /** 꼬마전구 줄: 초록 전선에 네 가지 색 전구가 번갈아 깜빡인다. */
    public static void garland(GuiGraphicsExtractor g, int x0, int x1, int y, float t, float a) {
        g.fill(x0, y, x1, y + 1, alpha(LEAF_DARK, a));
        int i = 0;
        for (int x = x0 + 6; x + 2 <= x1 - 4; x += 14, i++) {
            int color = BULBS[i % BULBS.length];
            boolean lit = Math.sin(t * 2.2f + i * 1.7f) > -0.6;
            if (lit) g.fill(x - 1, y + 1, x + 3, y + 5, alpha(color, a * 0.25f));
            g.fill(x, y + 1, x + 2, y + 4, alpha(lit ? color : mix(color, 0xFF000000, 0.4f), a));
            if (lit) g.fill(x, y + 1, x + 1, y + 2, alpha(0xFFFFFFFF, a * 0.8f));
        }
    }

    // ---------------------------------------------------------------- 머리글·탭

    /** 화면 머리글: 반짝이는 눈송이 + 금빛 제목. */
    public static void header(GuiGraphicsExtractor g, Font f, String title, int x0, int y0, float a) {
        twinkle(g, x0 + 13, y0 + 11, 3, now() * 2.5f, alpha(SNOW, a));
        g.text(f, title, x0 + 22, y0 + 8, textAlpha(GOLD, a), false);
    }

    /** 탭 하나를 그린다. 반환: 탭 너비 (높이는 12). */
    public static int tab(GuiGraphicsExtractor g, Font f, String label, int x, int y, boolean selected, int mx, int my, float a) {
        int w = f.width(label) + 12;
        boolean hover = inside(mx, my, x, y, w, 12);
        g.fill(x, y, x + w, y + 12, alpha(selected ? TAB_SELECTED : hover ? TAB_HOVER : TAB_IDLE, a));
        if (selected) g.fill(x, y + 11, x + w, y + 12, alpha(GOLD, a));
        g.text(f, label, x + 6, y + 2, textAlpha(selected ? GOLD : hover ? TEXT : TEXT_DIM, a), false);
        return w;
    }

    // ---------------------------------------------------------------- 진행·칩·버튼

    /** 픽셀 진행 막대. shown 은 0..1. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float shown, int color, float a) {
        g.fill(x, y, x + w, y + h, alpha(BAR_BG, a));
        int fw = Math.round((w - 2) * clamp01(shown));
        if (fw > 0) {
            g.fillGradient(x + 1, y + 1, x + 1 + fw, y + h - 1, alpha(mix(color, 0xFFFFFFFF, 0.25f), a), alpha(color, a));
            // 끝 반짝임
            g.fill(x + fw, y + 1, x + 1 + fw, y + h - 1, alpha(0xFFFFFFFF, a * 0.7f));
        }
        g.outline(x, y, w, h, alpha(EDGE_DARK, a));
    }

    public static int chip(GuiGraphicsExtractor g, Font f, String text, int x, int y, int color, float a) {
        int w = f.width(text) + 8;
        g.fill(x, y, x + w, y + 11, alpha(mix(color, 0xFF000000, 0.72f), a));
        g.outline(x, y, w, 11, alpha(color, a));
        g.text(f, text, x + 4, y + 2, textAlpha(color, a), false);
        return w;
    }

    /** 커스텀 버튼 (호버 시 밝아짐). 반환: 마우스가 위에 있는지. */
    public static boolean button(GuiGraphicsExtractor g, Font f, String text, int x, int y, int w, int h, int mx, int my, boolean enabled, int accent, float a) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? BUTTON_DISABLED : hover ? mix(BUTTON_HOVER, accent, 0.25f) : BUTTON_IDLE;
        g.fill(x + 1, y, x + w - 1, y + h, alpha(bg, a));
        g.fill(x, y + 1, x + 1, y + h - 1, alpha(bg, a));
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, alpha(bg, a));
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, alpha(hover ? accent : enabled ? mix(accent, EDGE_DARK, 0.55f) : EDGE_DARK, a));
        if (hover) g.fill(x + 1, y, x + w - 1, y + 1, alpha(SNOW, a * 0.75f));
        int tc = !enabled ? TEXT_FAINT : hover ? 0xFFFFFFFF : TEXT;
        g.centeredText(f, text, x + w / 2, y + (h - 8) / 2, textAlpha(tc, a));
        return hover;
    }

    /** 입력칸·미리보기 같은 움푹한 면. */
    public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int edge, float a) {
        g.fill(x, y, x + w, y + h, alpha(bg, a));
        g.outline(x, y, w, h, alpha(edge, a));
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void clickSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.3f);
    }

    // ---------------------------------------------------------------- 아이콘

    /** AI 가 만든 픽셀 아이콘을 size 픽셀 정사각형 안에 그린다. 없으면 작은 눈송이. */
    public static void pixelIcon(GuiGraphicsExtractor g, String[] rows, Map<Character, Integer> palette, int x, int y, int size, int fallback, float a) {
        if (rows == null || rows.length == 0) {
            flake(g, x + size / 2, y + size / 2, 2, alpha(fallback, a));
            return;
        }
        int n = Math.max(rows.length, 1);
        for (String r : rows) n = Math.max(n, r.length());
        int px = Math.max(1, size / n);
        int ox = x + (size - px * n) / 2, oy = y + (size - px * rows.length) / 2;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                if (ch == '.' || ch == ' ') continue;
                Integer col = palette.get(ch);
                int color = col != null ? col : fallback;
                g.fill(ox + c * px, oy + r * px, ox + (c + 1) * px, oy + (r + 1) * px, alpha(color, a));
            }
        }
    }

    // ---------------------------------------------------------------- 텍스트

    public static List<FormattedCharSequence> wrap(Font f, String text, int width) {
        return f.split(Component.literal(text == null ? "" : text), Math.max(20, width));
    }

    public static String time(long ms) {
        if (ms < 0) return "";
        long s = (ms + 999) / 1000;
        if (s >= 3600) return String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public static boolean terminalState(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "REJECTED".equals(state) || "CANCELLED".equals(state);
    }

    public static String stateKo(String state) {
        if (state == null) return "";
        return switch (state) {
            case "CREATED", "ANALYZING" -> "살피는 중";
            case "NEGOTIATING" -> "대화 중";
            case "AWAITING_ACCEPTANCE" -> "계약 대기";
            case "AWAITING_APPROVAL" -> "겨울밤의 허락 대기";
            case "PREPARING" -> "준비 중";
            case "VALIDATING" -> "눈꽃 엮는 중";
            case "ACTIVE" -> "진행 중";
            case "COMPLETED" -> "이루어짐";
            case "FAILED" -> "이루지 못함";
            case "REJECTED" -> "거절됨";
            case "CANCELLED" -> "취소됨";
            default -> state;
        };
    }

    public static int stateColor(String state) {
        if (state == null) return TEXT_DIM;
        return switch (state) {
            case "COMPLETED" -> SUCCESS;
            case "FAILED", "REJECTED" -> DANGER;
            case "CANCELLED" -> TEXT_FAINT;
            case "ACTIVE" -> GOLD;
            case "AWAITING_ACCEPTANCE", "AWAITING_APPROVAL" -> WARNING;
            default -> FROST;
        };
    }
}
