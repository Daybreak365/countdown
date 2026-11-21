package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.newyear.countdown.NewYearCountdown;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * 네 컷을 한 장의 사진 띠(600x1800, 1:3)로 합친다. 프레임마다 배경 그라데이션·장식·글자색이 다르고,
 * 아래에는 픽셀 글꼴로 "2027", "HAPPY NEW YEAR", 날짜, 함께 찍은 사람 이름을 새긴다.
 * 파일은 게임 폴더의 photobooth/ 에 저장된다.
 */
public final class PhotoStrip {
    public static final int W = 600, H = 1800;
    private static final int M = 30, TOP = 36, GAP = 16;
    private static final int PW = PhotoBoothSession.PHOTO_W, PH = PhotoBoothSession.PHOTO_H;
    private static final int FOOT = TOP + 4 * PH + 3 * GAP;

    /** 저장 결과: 파일과 미리보기용 이미지(결과 화면이 텍스처로 올린 뒤 닫는다). */
    public record Result(File file, NativeImage image) {}

    private PhotoStrip() {}

    public static File folder() {
        return new File(Minecraft.getInstance().gameDirectory, "photobooth");
    }

    public static void saveAsync(NativeImage[] shots, int frame, int filter, List<String> names, Consumer<Result> done) {
        Minecraft mc = Minecraft.getInstance();
        Util.ioPool().execute(() -> {
            Result result = null;
            NativeImage img = null;
            try {
                img = compose(shots, PhotoFrames.get(frame), filter, names);
                File dir = folder();
                dir.mkdirs();
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                File out = new File(dir, "2027_photo_" + stamp + ".png");
                for (int i = 2; out.exists(); i++) out = new File(dir, "2027_photo_" + stamp + "_" + i + ".png");
                img.writeToFile(out);
                result = new Result(out, img);
            } catch (Exception e) {
                NewYearCountdown.LOGGER.warn("photo booth save failed", e);
                if (img != null) img.close();
            } finally {
                for (NativeImage s : shots) if (s != null) s.close();
            }
            Result r = result;
            mc.execute(() -> done.accept(r));
        });
    }

    // ------------------------------------------------------------------ 합치기

    static NativeImage compose(NativeImage[] shots, PhotoFrames.Frame f, int filter, List<String> names) {
        NativeImage img = new NativeImage(W, H, false);
        for (int y = 0; y < H; y++) img.fillRect(0, y, W, 1, lerpColor(f.top(), f.bottom(), y / (float) (H - 1)));
        Random rnd = new Random(f.key().hashCode() * 31L + 2027);
        decorate(img, f, rnd, true);

        for (int i = 0; i < shots.length; i++) {
            int y = TOP + i * (PH + GAP);
            img.fillRect(M - 5, y - 5, PW + 10, PH + 10, f.border());
            NativeImage s = shots[i];
            if (s == null) {
                img.fillRect(M, y, PW, PH, 0xFF202020);
                continue;
            }
            for (int py = 0; py < PH; py++) {
                for (int px = 0; px < PW; px++) {
                    img.setPixel(M + px, y + py, filter(s.getPixel(px, py), filter));
                }
            }
        }
        decorate(img, f, rnd, false);

        // 아래: 2027 + 인사말 + 날짜 + 이름
        int cx = W / 2;
        textCentered(img, "2027", cx, FOOT + 22, 12, f.text(), f.accent());
        textCentered(img, "HAPPY NEW YEAR", cx, FOOT + 124, 3, f.text(), 0);
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        textCentered(img, date, cx, FOOT + 154, 2, f.accent(), 0);
        String who = names(names);
        if (!who.isEmpty()) textCentered(img, who, cx, FOOT + 176, 2, f.text(), 0);
        if (f.deco() == PhotoFrames.Deco.SAEKDONG) {
            sprite(img, SHEEP, 22, FOOT + 120, 4, f);
            spriteMirrored(img, SHEEP, W - 22 - 16 * 4, FOOT + 120, 4, f);
        } else {
            sprite(img, HEART, 46, FOOT + 46, 5, f);
            sprite(img, HEART, W - 46 - 35, FOOT + 46, 5, f);
        }
        return img;
    }

    private static String names(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            String s = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
            if (s.isEmpty()) continue;
            String next = (sb.length() == 0 ? "WITH " : sb + ", ") + s;
            if (width(next, 2) > W - 40) {
                sb.append(" +");
                break;
            }
            sb.setLength(0);
            sb.append(next);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 필터

    private static int filter(int argb, int filter) {
        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
        switch (filter) {
            case 1 -> {   // 뽀샤시: 밝고 부드럽게, 살짝 따뜻하게
                r = lift(r, 0.80f) + 6;
                g = lift(g, 0.82f) + 2;
                b = lift(b, 0.84f);
                int l = (r * 3 + g * 6 + b) / 10;
                r = (int) (r * 0.88f + l * 0.12f);
                g = (int) (g * 0.88f + l * 0.12f);
                b = (int) (b * 0.88f + l * 0.12f);
            }
            case 2 -> {   // 흑백: 대비를 조금 올린다
                int l = (r * 30 + g * 59 + b * 11) / 100;
                l = (int) ((l - 128) * 1.12f + 132);
                r = g = b = l;
            }
            default -> {}
        }
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int lift(int c, float k) {
        return 255 - (int) ((255 - c) * k);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    private static void blend(NativeImage img, int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= W || y >= H) return;
        int a = argb >>> 24;
        if (a == 0) return;
        if (a < 255) argb = lerpColor(img.getPixel(x, y), argb | 0xFF000000, a / 255f);
        img.setPixel(x, y, argb | 0xFF000000);
    }

    private static void rect(NativeImage img, int x, int y, int w, int h, int argb) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) blend(img, x + i, y + j, argb);
    }

    // ------------------------------------------------------------------ 장식

    /** under = 사진 밑에 깔리는 장식(여백에 흩뿌림), 아니면 사진 위에 살짝 걸치는 장식. */
    private static void decorate(NativeImage img, PhotoFrames.Frame f, Random rnd, boolean under) {
        switch (f.deco()) {
            case SPARKLE -> scatter(img, rnd, under ? 40 : 6, SPARKLE, 3, f.accent(), 0xFFFFFFFF, under);
            case GOLD -> scatter(img, rnd, under ? 34 : 6, STAR, 3, f.accent(), 0xFFFFF3C0, under);
            case SNOW -> scatter(img, rnd, under ? 30 : 8, SNOWFLAKE, 3, f.accent(), 0xFFFFFFFF, under);
            case HEART -> scatter(img, rnd, under ? 30 : 7, HEART, 3, f.accent(), 0xFFFFFFFF, under);
            case CONFETTI -> {
                int[] cols = {0xFFE8453C, 0xFF4DA8E8, 0xFF5CC96B, 0xFFB06AE8, 0xFFF5A623};
                for (int k = 0; k < (under ? 140 : 24); k++) {
                    int[] p = spot(rnd, under);
                    int w = 4 + rnd.nextInt(6), h = 3 + rnd.nextInt(4);
                    rect(img, p[0], p[1], rnd.nextBoolean() ? w : h, rnd.nextBoolean() ? h : w, cols[rnd.nextInt(cols.length)]);
                }
            }
            case FIREWORK -> {
                int[] cols = {0xFFFFE27A, 0xFFFF8FD0, 0xFF8FD8FF, 0xFFB7FF8F};
                for (int k = 0; k < (under ? 9 : 2); k++) {
                    int[] p = spot(rnd, under);
                    firework(img, p[0], p[1], 14 + rnd.nextInt(16), cols[rnd.nextInt(cols.length)]);
                }
                scatter(img, rnd, under ? 30 : 0, SPARKLE, 2, 0xFFFFFFFF, 0xFFFFFFFF, under);
            }
            case SAEKDONG -> {
                if (under) {
                    // 색동 줄무늬: 양옆 여백
                    int[] cols = {0xFFE8453C, 0xFFF5A623, 0xFFF8D347, 0xFF5CC96B, 0xFF4DA8E8, 0xFFB06AE8, 0xFFF06292};
                    int band = 12;
                    for (int y = 0; y < H; y += band) {
                        int c = cols[(y / band) % cols.length];
                        rect(img, 0, y, 14, band, c);
                        rect(img, W - 14, y, 14, band, c);
                    }
                    scatter(img, rnd, 18, STAR, 2, 0xFFE8B830, 0xFFFFF3C0, true);
                }
            }
        }
    }

    /** 여백(사진 사이·바깥) 또는 사진 모서리 근처의 한 점. */
    private static int[] spot(Random rnd, boolean under) {
        if (under) {
            int side = rnd.nextInt(3);
            if (side == 0) return new int[]{rnd.nextInt(M), rnd.nextInt(FOOT)};
            if (side == 1) return new int[]{W - M + rnd.nextInt(M), rnd.nextInt(FOOT)};
            int i = rnd.nextInt(5);
            int y = i == 0 ? rnd.nextInt(TOP) : (i == 4 ? FOOT + rnd.nextInt(H - FOOT) : TOP + i * (PH + GAP) - GAP + rnd.nextInt(GAP));
            return new int[]{rnd.nextInt(W), y};
        }
        int i = rnd.nextInt(4);
        int y0 = TOP + i * (PH + GAP);
        boolean left = rnd.nextBoolean(), top = rnd.nextBoolean();
        return new int[]{left ? M - 16 + rnd.nextInt(30) : M + PW - 14 + rnd.nextInt(30), top ? y0 - 14 + rnd.nextInt(24) : y0 + PH - 12 + rnd.nextInt(24)};
    }

    private static void scatter(NativeImage img, Random rnd, int n, String[] art, int scale, int c1, int c2, boolean under) {
        for (int k = 0; k < n; k++) {
            int[] p = spot(rnd, under);
            int s = Math.max(1, scale + rnd.nextInt(2) - (under ? rnd.nextInt(2) : 0));
            draw(img, art, p[0] - art[0].length() * s / 2, p[1] - art.length * s / 2, s, rnd.nextBoolean() ? c1 : c2, false);
        }
    }

    private static void firework(NativeImage img, int cx, int cy, int r, int c) {
        for (int k = 0; k < 14; k++) {
            double a = k * Math.PI * 2 / 14;
            for (int d = r / 3; d <= r; d += 3) {
                int x = cx + (int) Math.round(Math.cos(a) * d), y = cy + (int) Math.round(Math.sin(a) * d);
                int alpha = 255 - (d * 140 / Math.max(1, r));
                rect(img, x - 1, y - 1, 3, 3, alpha << 24 | (c & 0xFFFFFF));
            }
        }
        rect(img, cx - 2, cy - 2, 5, 5, 0xFFFFFFFF);
    }

    private static void sprite(NativeImage img, String[] art, int x, int y, int s, PhotoFrames.Frame f) {
        draw(img, art, x, y, s, f.accent(), false);
    }

    private static void spriteMirrored(NativeImage img, String[] art, int x, int y, int s, PhotoFrames.Frame f) {
        draw(img, art, x, y, s, f.accent(), true);
    }

    /** 문자 그림: '#' 은 주어진 색, 나머지 문자는 팔레트 색, ' ' 는 투명. */
    private static void draw(NativeImage img, String[] art, int x, int y, int s, int color, boolean mirror) {
        for (int j = 0; j < art.length; j++) {
            String row = art[j];
            for (int i = 0; i < row.length(); i++) {
                char ch = row.charAt(mirror ? row.length() - 1 - i : i);
                int c = switch (ch) {
                    case '#' -> color;
                    case 'w' -> 0xFFFAFAF6;
                    case 'g' -> 0xFFD9D6CF;
                    case 'f' -> 0xFFE8CDB5;
                    case 'k' -> 0xFF2A2222;
                    case 'r' -> 0xFFD7263D;
                    case 'y' -> 0xFFF6C945;
                    case 'l' -> 0xFF9C8670;
                    default -> 0;
                };
                if (c != 0) rect(img, x + i * s, y + j * s, s, s, c);
            }
        }
    }

    private static final String[] HEART = {" ## ## ", "#######", "#######", " ##### ", "  ###  ", "   #   "};
    private static final String[] STAR = {"  #  ", " ### ", "#####", " ### ", " # # "};
    private static final String[] SPARKLE = {"  #  ", "  #  ", "## ##", "  #  ", "  #  "};
    private static final String[] SNOWFLAKE = {"#  #  #", " # # # ", "  ###  ", "### ###", "  ###  ", " # # # ", "#  #  #"};
    /** 2027 정미년(丁未年) 붉은 양: 털은 하얗게, 목에 붉은 리본과 금방울. */
    private static final String[] SHEEP = {
            "      wwwwww    ",
            "    wwwwwwwwww  ",
            "  wwwwwwwwwwwwww",
            " fffwwwwwwwwwwww",
            "fkffrwwwwwwwwwgw",
            "fffffrwwwwwwwwgg",
            " fffryrwwwwwwwgg",
            "  ff rrwwwwwwggg",
            "      wwwwwwwgg ",
            "      ll  ll ll ",
            "      ll  ll ll ",
            "      kk  kk kk ",
    };

    // ------------------------------------------------------------------ 픽셀 글꼴 (5x7)

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,-!:/_+& ";
    private static final String[][] GLYPHS = {
            {" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"},
            {"#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### "},
            {" ### ", "#   #", "#    ", "#    ", "#    ", "#   #", " ### "},
            {"#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### "},
            {"#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"},
            {"#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    "},
            {" ### ", "#   #", "#    ", "# ###", "#   #", "#   #", " ####"},
            {"#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"},
            {" ### ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "},
            {"  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  "},
            {"#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"},
            {"#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"},
            {"#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #"},
            {"#   #", "#   #", "##  #", "# # #", "#  ##", "#   #", "#   #"},
            {" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "},
            {"#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "},
            {" ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #"},
            {"#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #"},
            {" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "},
            {"#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "},
            {"#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "},
            {"#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  "},
            {"#   #", "#   #", "#   #", "# # #", "# # #", "# # #", " # # "},
            {"#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #"},
            {"#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  "},
            {"#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####"},
            {" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "},
            {"  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "},
            {" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"},
            {"#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "},
            {"   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "},
            {"#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "},
            {"  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "},
            {"#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "},
            {" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "},
            {" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "},
            {"     ", "     ", "     ", "     ", "     ", " ##  ", " ##  "},
            {"     ", "     ", "     ", "     ", " ##  ", "  #  ", " #   "},
            {"     ", "     ", "     ", "#####", "     ", "     ", "     "},
            {"  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "     ", "  #  "},
            {"     ", " ##  ", " ##  ", "     ", " ##  ", " ##  ", "     "},
            {"    #", "    #", "   # ", "  #  ", " #   ", "#    ", "#    "},
            {"     ", "     ", "     ", "     ", "     ", "     ", "#####"},
            {"     ", "  #  ", "  #  ", "#####", "  #  ", "  #  ", "     "},
            {" ##  ", "#  # ", "# #  ", " #   ", "# # #", "#  # ", " ## #"},
            {"     ", "     ", "     ", "     ", "     ", "     ", "     "},
    };

    static int width(String s, int scale) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (CHARS.indexOf(s.charAt(i)) >= 0) n++;
        return n == 0 ? 0 : n * 6 * scale - scale;
    }

    private static void textCentered(NativeImage img, String s, int cx, int y, int scale, int color, int shadow) {
        text(img, s, cx - width(s, scale) / 2, y, scale, color, shadow);
    }

    private static void text(NativeImage img, String s, int x, int y, int scale, int color, int shadow) {
        if (shadow != 0) {
            int off = Math.max(1, scale / 2);
            glyphs(img, s, x + off, y + off, scale, shadow);
        }
        glyphs(img, s, x, y, scale, color);
    }

    private static void glyphs(NativeImage img, String s, int x, int y, int scale, int color) {
        for (int i = 0; i < s.length(); i++) {
            int gi = CHARS.indexOf(s.charAt(i));
            if (gi < 0) continue;
            String[] g = GLYPHS[gi];
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if (g[row].charAt(col) == '#') rect(img, x + col * scale, y + row * scale, scale, scale, color);
                }
            }
            x += 6 * scale;
        }
    }
}
