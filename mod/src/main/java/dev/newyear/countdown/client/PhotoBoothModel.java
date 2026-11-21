package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.photo.PhotoBooth;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 포토부스 3D 모델: "안으로 걸어 들어가는 커다란 레트로 카메라".
 * 크림색 몸체에 가죽 질감의 아래 띠와 무지개 줄무늬, 정면 입구는 커다란 렌즈 링(안쪽 커튼이 셔터), 지붕에는 플래시·셔터 버튼·뷰파인더,
 * 뒷면에는 바깥 사람도 볼 수 있는 LCD(카운트다운)가 있다. 실내는 체크무늬 바닥, 파스텔 벽, 꼬마전구, 풍선, 프레임 색 배경벽.
 *
 * 좌표계: 마스터 칸 바닥 중심이 원점, 입구가 +Z, 키오스크가 -Z, 폭은 X, 단위는 블록. 구조물은 x, z 모두 -3.5..3.5, y 0..5.
 * 실제 블록 렌더러와 설치 홀로그램이 함께 쓴다.
 */
public final class PhotoBoothModel {
    private static final Identifier T_CREAM = mc("smooth_quartz");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final Identifier T_BLACK = mc("black_concrete");
    private static final Identifier T_LEATHER = mc("black_wool");
    private static final Identifier T_WOOL = mc("white_wool");
    private static final Identifier T_PLINTH = mc("polished_blackstone");
    private static final Identifier T_CHROME = mc("iron_block");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final Identifier T_LAMP = mc("white_concrete");   // 등은 흰 바탕에 색을 입히고 최대 밝기로 그린다

    private static final int WHITE = 0xFFFFFFFF;
    private static final int CREAM = 0xFFFFF4E2;
    private static final int LEATHER = 0xFF4A4650;
    private static final int TILE_PINK = 0xFFF6BCCD;
    private static final int PANEL = 0xFFFFE6EE;
    private static final int PANEL_BACK = 0xFFFFF3F7;
    private static final int FULL = 0xF000F0;
    private static final int[] RAINBOW = {0xFFE8453C, 0xFFF5A623, 0xFFF8D347, 0xFF5CC96B, 0xFF4DA8E8};
    private static final int[] BULBS = {0xFFFF5A6E, 0xFFFFD25A, 0xFF7CE08A, 0xFF6EC3FF, 0xFFE58CFF};

    /** 바깥 몸체 띠 경계(y): 0 받침, 1 가죽, 2..6 무지개, 7 크림. */
    private static final float[] BANDS = {0f, 0.25f, 2.55f, 2.69f, 2.83f, 2.97f, 3.11f, 3.25f, 4.0f};
    private static final float S = 3.5f;          // 바깥 반폭
    private static final float IN = 2.5f;         // 실내 반폭
    private static final float TOP = 4.0f;        // 천장(지붕 아랫면)
    private static final float LENS_Y = 1.75f;    // 렌즈 링 중심 높이
    /** 몸체 앞면: 렌즈 링이 블록 경계(3.5) 안에서 튀어나오도록 0.3 들여 놓는다. */
    private static final float FRONT = 3.2f;

    private PhotoBoothModel() {}

    private static Identifier mc(String n) {
        return Identifier.withDefaultNamespace("textures/block/" + n + ".png");
    }

    /**
     * @param age        촬영 시작 후 지난 틱 (촬영 중이 아니면 아주 큰 값)
     * @param time       월드 시간 (꼬마전구 깜빡임·커튼 흔들림)
     * @param cameraView 이 부스의 촬영 시점으로 보는 중: 카메라가 뒷벽 안에 있으므로 뒷벽·키오스크를 그리지 않는다
     */
    public static void draw(GeoBuffers providers, PoseStack m, Font font, int light, int frame, float age, float time, boolean cameraView) {
        PhotoFrames.Frame f = PhotoFrames.get(frame);
        boolean shooting = age >= 0 && age < PhotoBooth.TOTAL;
        float flash = flash(age, shooting);
        BellModel.Painter p = new BellModel.Painter(providers, m, light, 0);
        shell(p, !cameraView);
        roofTop(p, m, providers, font, time, shooting, flash);
        lens(p, m);
        backPanel(p, m, providers, font, age, shooting, time);
        sides(p, m, providers, font);
        floor(p);
        interior(p, time, shooting, !cameraView);
        backdrop(p, m, providers, font, f, time);
        balloons(p, m, time);
        if (!cameraView) kiosk(p, m, providers, font, age, shooting, flash);
    }

    /** 설치 미리보기: 큰 덩어리만. */
    public static void drawGhost(GeoBuffers providers, PoseStack m, int argb) {
        BellModel.Painter p = new BellModel.Painter(providers, m, FULL, argb);
        shell(p, true);
        p.use(T_CREAM, CREAM);
        p.box(-S - 0.05f, TOP, -S - 0.05f, S + 0.05f, TOP + 0.3f, FRONT + 0.05f);
        p.box(1.2f, TOP + 0.35f, 1.7f, 3.0f, TOP + 0.97f, 3.2f);
        p.box(-0.9f, TOP + 0.35f, -1.0f, 0.9f, TOP + 0.9f, 0.6f);
        lens(p, m);
    }

    /** i 번째 컷을 찍는 틱 (PhotoBooth.captureTick 과 같다. 상수만 써서 렌더 코드가 블록 등록 클래스를 불러오지 않게). */
    private static int cap(int i) {
        return PhotoBooth.FIRST_SHOT + i * PhotoBooth.SHOT_GAP;
    }

    /** 찍는 순간 번쩍임 (0..1). */
    private static float flash(float age, boolean shooting) {
        if (!shooting) return 0f;
        float flash = 0f;
        for (int i = 0; i < PhotoBooth.SHOTS; i++) {
            float d = age - cap(i);
            if (d >= 0f && d < 6f) flash = Math.max(flash, 1f - d / 6f);
        }
        return flash;
    }

    // ------------------------------------------------------------------ 몸체

    /** 띠별로 나눠 그리는 상자: 받침·가죽·무지개·크림. */
    private static void banded(BellModel.Painter p, float x0, float z0, float x1, float z1, float y0, float y1) {
        for (int b = 0; b + 1 < BANDS.length; b++) {
            float a = Math.max(y0, BANDS[b]), c = Math.min(y1, BANDS[b + 1]);
            if (c - a < 1e-4f) continue;
            if (b == 0) p.use(T_PLINTH, WHITE);
            else if (b == 1) p.use(T_LEATHER, LEATHER);
            else if (b <= 6) p.use(T_WOOL, RAINBOW[b - 2]);
            else p.use(T_CREAM, CREAM);
            p.box(x0, a, z0, x1, c, z1);
        }
    }

    private static void shell(BellModel.Painter p, boolean back) {
        banded(p, -S, -S, -IN, FRONT, 0f, TOP);              // 왼벽
        banded(p, IN, -S, S, FRONT, 0f, TOP);                // 오른벽
        if (back) banded(p, -IN, -S, IN, -IN, 0f, TOP);      // 뒷벽
        banded(p, -IN, IN, -1.5f, FRONT, 0f, TOP);           // 앞벽 (문 양옆)
        banded(p, 1.5f, IN, IN, FRONT, 0f, TOP);
        banded(p, -1.5f, IN, 1.5f, FRONT, 3.0f, TOP);        // 문 위
        // 문 안쪽 테두리 (어두운 안감)
        p.use(T_BLACK, 0xFF222226);
        p.box(-1.5f, 0f, IN, -1.45f, 3.0f, FRONT);
        p.box(1.45f, 0f, IN, 1.5f, 3.0f, FRONT);
        p.box(-1.5f, 2.95f, IN, 1.5f, 3.0f, FRONT);
        // 모서리 크롬 몰딩
        p.use(T_CHROME, 0xFFE8E8EE);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float x = sx * S, z = sz > 0 ? FRONT : -S;
                p.box(x - 0.06f, 0.25f, z - 0.06f, x + 0.06f, TOP, z + 0.06f);
            }
        }
    }

    // ------------------------------------------------------------------ 지붕: 덮개, 플래시, 셔터 버튼, 뷰파인더, 띠 글씨

    private static void roofTop(BellModel.Painter p, PoseStack m, GeoBuffers providers, Font font, float time, boolean shooting, float flash) {
        float r = TOP + 0.3f;
        p.use(T_CREAM, CREAM);
        p.box(-S - 0.05f, TOP, -S - 0.05f, S + 0.05f, r, FRONT + 0.05f);
        p.use(T_CHROME, 0xFFDADAE2);
        p.box(-S - 0.07f, r, -S - 0.07f, S + 0.07f, r + 0.05f, FRONT + 0.07f);
        r += 0.05f;

        // 뷰파인더 (가운데 뒤쪽 혹)
        p.use(T_LEATHER, LEATHER);
        p.box(-0.9f, r, -1.0f, 0.9f, r + 0.55f, 0.6f);
        p.use(T_CHROME, WHITE);
        p.box(-0.95f, r + 0.55f, -1.05f, 0.95f, r + 0.6f, 0.65f);
        p.use(T_BLACK, 0xFF3A5A8C);
        p.lightOverride = FULL;
        p.box(-0.35f, r + 0.15f, 0.6f, 0.35f, r + 0.42f, 0.62f);
        p.lightOverride = -1;

        // 플래시 (앞 오른쪽): 찍는 순간 하얗게 번쩍인다
        p.use(T_CHROME, 0xFFEDEDF2);
        p.box(1.2f, r, 1.7f, 3.0f, r + 0.62f, FRONT);
        p.use(T_BLACK, 0xFF2A2A30);
        p.box(1.25f, r + 0.62f, 1.75f, 2.95f, r + 0.66f, FRONT - 0.05f);
        p.use(T_LAMP, flash > 0f ? WHITE : 0xFFFFF6DA);
        p.lightOverride = FULL;
        p.box(1.35f, r + 0.1f, FRONT, 2.85f, r + 0.52f, FRONT + 0.04f);
        p.lightOverride = -1;
        p.use(T_CHROME, 0xFFBFBFC8);
        for (int i = 0; i < 3; i++) {
            float y = r + 0.19f + i * 0.12f;
            p.box(1.35f, y, FRONT + 0.04f, 2.85f, y + 0.015f, FRONT + 0.05f);
        }
        if (flash > 0f) {
            p.setTranslucent(true);
            p.lightOverride = FULL;
            p.use(T_WHITE, ((int) (flash * 170) << 24) | 0xFFFFFF);
            p.box(1.05f, r - 0.15f, FRONT + 0.05f, 3.15f, r + 0.77f, FRONT + 0.4f);
            p.lightOverride = -1;
            p.setTranslucent(false);
        }

        // 셔터 버튼 (앞 왼쪽): 찍을 때 눌린다
        float press = flash > 0.5f ? 0.08f : 0f;
        p.use(T_CHROME, WHITE);
        octagonY(p, m, -2.2f, 2.3f, 0.48f, r, r + 0.14f);
        p.use(T_WHITE, 0xFFE8453C);
        octagonY(p, m, -2.2f, 2.3f, 0.33f, r + 0.14f, r + 0.4f - press);
        // 촬영 중 빨간 표시등
        boolean rec = shooting && ((int) (time / 6f)) % 2 == 0;
        p.use(T_LAMP, rec ? 0xFFFF3030 : 0xFF6A2020);
        p.lightOverride = rec ? FULL : -1;
        p.box(-1.4f, r, FRONT - 0.2f, -1.15f, r + 0.12f, FRONT + 0.05f);
        p.lightOverride = -1;

        // 지붕 앞 띠 글씨
        text(m, providers, font, "★ 2027 NEW YEAR PHOTO ★", 0f, TOP + 0.15f, FRONT + 0.06f, 0.026f, 0xFFB0305A);
    }

    // ------------------------------------------------------------------ 렌즈 링 입구

    private static void lens(BellModel.Painter p, PoseStack m) {
        p.use(T_CHROME, 0xFFE4E4EA);
        ring(p, m, 2.15f, 2.45f, FRONT - 0.05f, FRONT + 0.16f);
        p.use(T_LEATHER, 0xFF2B2A30);
        ring(p, m, 1.86f, 2.17f, FRONT - 0.05f, S);
        p.use(T_GOLD, 0xFFF0C860);
        ring(p, m, 1.74f, 1.88f, FRONT - 0.05f, S - 0.06f);
        // 초점 링 눈금 (검은 링 위 작은 은색 홈)
        p.use(T_CHROME, WHITE);
        for (int k = 0; k < 16; k++) {
            if (k >= 11 && k <= 13) continue;   // 아래쪽은 땅에 묻힌다
            m.pushPose();
            m.translate(0f, LENS_Y, 0f);
            m.rotate(Axis.ZP.rotationDegrees(k * 22.5f));
            p.box(1.98f, -0.03f, S, 2.1f, 0.03f, S + 0.02f);
            m.popPose();
        }
    }

    /** z 방향으로 놓인 정팔각 고리 (렌즈 중심 기준). 맨 아래 조각은 문턱을 막지 않게 뺀다. */
    private static void ring(BellModel.Painter p, PoseStack m, float rIn, float rOut, float z0, float z1) {
        float half = rOut * 0.4142f + 0.01f;
        for (int k = 0; k < 8; k++) {
            if (k == 6) continue;
            m.pushPose();
            m.translate(0f, LENS_Y, 0f);
            m.rotate(Axis.ZP.rotationDegrees(k * 45f));
            p.box(rIn, -half, z0, rOut, half, z1);
            m.popPose();
        }
    }

    /** 세로로 선 정팔각 기둥 (윗면이 팔각형). a = 중심에서 면까지 거리. */
    private static void octagonY(BellModel.Painter p, PoseStack m, float cx, float cz, float a, float y0, float y1) {
        float b = a * 0.4142f;
        for (int k = 0; k < 4; k++) {
            m.pushPose();
            m.translate(cx, 0f, cz);
            m.rotate(Axis.YP.rotationDegrees(k * 45f));
            float e = k * 0.0006f;
            p.box(-a, y0 + e, -b, a, y1 - e, b);
            m.popPose();
        }
    }

    // ------------------------------------------------------------------ 뒷면 LCD (바깥 사람도 카운트다운을 본다)

    private static void backPanel(BellModel.Painter p, PoseStack m, GeoBuffers providers, Font font, float age, boolean shooting, float time) {
        float z = -S - 0.03f;
        p.use(T_BLACK, 0xFF1C1C22);
        p.box(-2.3f, 0.95f, z - 0.02f, 2.3f, 3.05f, -S);
        p.use(T_WHITE, shooting ? 0xFF9AD4FF : 0xFFB8E2FF);
        p.lightOverride = FULL;
        p.box(-2.1f, 1.1f, z - 0.03f, 2.1f, 2.9f, z - 0.02f);
        p.lightOverride = -1;
        // 십자 버튼과 동그란 버튼
        p.use(T_CHROME, 0xFFD0D0D8);
        p.box(-3.1f, 1.75f, z - 0.04f, -2.5f, 1.95f, -S);
        p.box(-2.9f, 1.55f, z - 0.04f, -2.7f, 2.15f, -S);
        p.use(T_WHITE, 0xFFE8453C);
        p.box(2.6f, 2.2f, z - 0.04f, 2.9f, 2.5f, -S);
        p.use(T_WHITE, 0xFF4DA8E8);
        p.box(2.6f, 1.6f, z - 0.04f, 2.9f, 1.9f, -S);

        String big, small;
        if (shooting) {
            int shot = 0;
            while (shot < PhotoBooth.SHOTS - 1 && age >= cap(shot) + 6) shot++;
            float left = (cap(shot) - age) / 20f;
            big = left > 0f && left <= 3f ? Integer.toString((int) Math.ceil(left)) : (left > 3f ? "♥" : "★");
            small = "REC  " + (shot + 1) + " / " + PhotoBooth.SHOTS;
        } else {
            big = "2027";
            small = ((int) (time / 30f)) % 2 == 0 ? "SMILE :)" : "4 CUT PHOTO";
        }
        m.pushPose();
        m.translate(0f, 0f, z - 0.035f);
        m.rotate(Axis.YP.rotationDegrees(180f));
        text(m, providers, font, big, 0f, 2.15f, 0f, 0.07f, 0xFF10325A);
        text(m, providers, font, small, 0f, 1.4f, 0f, 0.025f, 0xFF10325A);
        m.popPose();
    }

    // ------------------------------------------------------------------ 옆면: 사진 띠 포스터 + 스트랩 고리

    private static void sides(BellModel.Painter p, PoseStack m, GeoBuffers providers, Font font) {
        for (int side = -1; side <= 1; side += 2) {
            m.pushPose();
            m.translate(side * (S + 0.01f), 0f, 0f);
            m.rotate(Axis.YP.rotationDegrees(side * 90f));
            // 사진 띠 두 장 (흰 띠에 색 네모 넷)
            for (int s = 0; s < 2; s++) {
                float x = -0.9f + s * 1.0f;
                p.use(T_WHITE, WHITE);
                p.box(x, 0.45f, 0f, x + 0.8f, 2.4f, 0.03f);
                for (int k = 0; k < 4; k++) {
                    p.use(T_WOOL, BULBS[(k + s * 2 + (side > 0 ? 1 : 0)) % BULBS.length]);
                    float y = 1.95f - k * 0.45f;
                    p.box(x + 0.08f, y, 0.03f, x + 0.72f, y + 0.38f, 0.05f);
                }
            }
            text(m, providers, font, "NEW YEAR 4CUT", 0f, 3.62f, 0.02f, 0.03f, 0xFFB0305A);
            // 스트랩 고리
            p.use(T_CHROME, WHITE);
            for (int e = -1; e <= 1; e += 2) {
                float x = e * 2.9f;
                p.box(x - 0.18f, 3.45f, 0f, x + 0.18f, 3.55f, 0.12f);
                p.box(x - 0.18f, 3.45f, 0.08f, x - 0.1f, 3.75f, 0.12f);
                p.box(x + 0.1f, 3.45f, 0.08f, x + 0.18f, 3.75f, 0.12f);
                p.box(x - 0.18f, 3.7f, 0.08f, x + 0.18f, 3.78f, 0.12f);
            }
            m.popPose();
        }
    }

    // ------------------------------------------------------------------ 실내

    private static void floor(BellModel.Painter p) {
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 12; j++) {
                float x = -IN + i * 0.5f, z = -IN + j * 0.5f;
                if (z >= IN && (x < -1.5f || x + 0.5f > 1.5f)) continue;   // 문 밖 칸은 문 폭만
                p.use(T_CREAM, ((i + j) & 1) == 0 ? WHITE : TILE_PINK);
                p.box(x, 0f, z, x + 0.5f, 0.125f, z + 0.5f);
            }
        }
    }

    private static void interior(BellModel.Painter p, float time, boolean shooting, boolean back) {
        p.use(T_WHITE, PANEL);
        p.box(-IN, 0.125f, -IN, -IN + 0.03f, TOP - 0.05f, IN);
        p.box(IN - 0.03f, 0.125f, -IN, IN, TOP - 0.05f, IN);
        p.use(T_WHITE, PANEL_BACK);
        if (back) p.box(-IN, 0.125f, -IN, IN, TOP - 0.05f, -IN + 0.03f);
        p.use(T_WHITE, 0xFFFFF8FA);
        p.box(-IN, TOP - 0.05f, -IN, IN, TOP, IN);   // 천장

        // 천장 조명 세 줄
        p.use(T_LAMP, 0xFFFFF6E8);
        p.lightOverride = FULL;
        for (float z : new float[]{-1.6f, 0f, 1.6f}) p.box(-1.8f, TOP - 0.07f, z - 0.08f, 1.8f, TOP - 0.05f, z + 0.08f);
        p.lightOverride = -1;

        // 양쪽 벽: 늘어진 꼬마전구 줄 두 가닥 + 네컷 액자
        for (int side = -1; side <= 1; side += 2) {
            float x = side * (IN - 0.045f);
            for (int line = 0; line < 2; line++) {
                float base = line == 0 ? 3.55f : 3.05f;
                p.use(T_BLACK, 0xFF3A3A3A);
                for (int k = 0; k < 40; k++) {
                    float z0 = -IN + k * 0.125f;
                    float y0 = sag(z0, base);
                    p.box(x - 0.008f, y0 - 0.008f, z0, x + 0.008f, y0 + 0.008f, z0 + 0.125f);
                }
                p.lightOverride = FULL;
                for (int k = 0; k < 12; k++) {
                    float z = -IN + 0.2f + k * 0.42f;
                    float y = sag(z, base) - 0.05f;
                    int c = BULBS[(k + line + (side > 0 ? 2 : 0)) % BULBS.length];
                    boolean on = shooting ? ((int) (time / 2f) + k + line) % 3 != 0 : ((int) (time / 10f) + k + line) % 5 != 0;
                    p.use(T_LAMP, on ? c : dim(c));
                    p.box(x - 0.035f, y - 0.06f, z - 0.035f, x + 0.035f, y + 0.02f, z + 0.035f);
                }
                p.lightOverride = -1;
            }
            for (int fr = 0; fr < 2; fr++) {
                float fz = (fr == 0 ? -1.2f : 0.6f) * (side < 0 ? 1 : -1);
                p.use(T_WHITE, WHITE);
                p.box(x - 0.015f, 0.95f, fz - 0.16f, x + 0.015f, 2.25f, fz + 0.16f);
                for (int k = 0; k < 4; k++) {
                    p.use(T_WOOL, BULBS[(k + fr + side + 2) % BULBS.length]);
                    float y = 1.95f - k * 0.31f;
                    p.box(x - 0.02f, y, fz - 0.12f, x + 0.02f, y + 0.25f, fz + 0.12f);
                }
            }
        }
    }

    private static float sag(float z, float base) {
        float t = (z + IN) / (2 * IN);
        return base - 0.18f * Mth.sin(t * Mth.PI * 3f) * Mth.sin(t * Mth.PI * 3f);
    }

    private static int dim(int c) {
        return 0xFF000000 | ((c >> 17) & 0x7F) << 16 | ((c >> 9) & 0x7F) << 8 | ((c >> 1) & 0x7F);
    }

    /** 사진 배경: 앞벽 안쪽은 프레임 색 패널, 문에는 같은 색 커튼(주름), 위에는 가랜드와 인사말. */
    private static void backdrop(BellModel.Painter p, PoseStack m, GeoBuffers providers, Font font, PhotoFrames.Frame f, float time) {
        float z = IN - 0.04f;
        p.use(T_WOOL, f.backdrop());
        p.box(-IN, 0.125f, z, -1.5f, TOP - 0.05f, IN);
        p.box(1.5f, 0.125f, z, IN, TOP - 0.05f, IN);
        p.box(-1.5f, 3.0f, z, 1.5f, TOP - 0.05f, IN);
        // 커튼 (문 안쪽, 셔터처럼): 주름 6장
        p.use(T_GOLD, 0xFFF2D27A);
        p.box(-1.55f, 2.9f, IN + 0.02f, 1.55f, 2.97f, IN + 0.12f);
        for (int i = 0; i < 6; i++) {
            float x0 = -1.5f + i * 0.5f;
            float sway = 0.012f * Mth.sin(time * 0.05f + i * 1.3f);
            boolean back = (i & 1) == 0;
            float z0 = (back ? IN + 0.06f : IN + 0.01f) + sway;
            p.use(T_WOOL, back ? f.pleat() : f.backdrop());
            p.box(x0, 0.125f, z0, x0 + 0.5f, 2.92f, z0 + 0.1f);
        }
        // 가랜드 전구 (사진 위쪽 가장자리에 걸리는 높이)
        p.lightOverride = FULL;
        for (int k = 0; k < 17; k++) {
            float x = -2.3f + k * (4.6f / 16f);
            float y = 3.24f - 0.12f * Mth.sin((k / 16f) * Mth.PI);
            int c = BULBS[k % BULBS.length];
            boolean on = ((int) (time / 8f) + k) % 4 != 0;
            p.use(T_LAMP, on ? c : dim(c));
            p.box(x - 0.04f, y - 0.06f, z - 0.035f, x + 0.04f, y + 0.02f, z);
        }
        p.lightOverride = -1;
        // 인사말: 커튼 윗부분 앞에 (머리 위, 사진에 찍히는 높이)
        m.pushPose();
        m.translate(0f, 0f, z - 0.01f);
        m.rotate(Axis.YP.rotationDegrees(180f));
        text(m, providers, font, "HAPPY NEW YEAR 2027", 0f, 2.62f, 0f, 0.022f, f.text() == 0xFFF5F5F0 ? 0xFFE8C15A : f.text());
        m.popPose();
    }

    /** 앞쪽 두 모서리의 풍선 다발 (사진 양옆 위를 꾸민다). */
    private static void balloons(BellModel.Painter p, PoseStack m, float time) {
        int[][] cols = {{0xFFFF8FB1, 0xFFF6C945, 0xFF7FD8C8}, {0xFF9AB8FF, 0xFFFF8FB1, 0xFFF6C945}};
        for (int side = -1; side <= 1; side += 2) {
            float bx = side * 2.05f, bz = 1.95f;
            for (int k = 0; k < 3; k++) {
                float ox = bx - (k - 1) * 0.28f * side + 0.03f * Mth.sin(time * 0.04f + k);
                float oy = 2.85f + (k == 1 ? 0.35f : 0f);
                float oz = bz - (k == 1 ? 0.15f : 0f);
                p.use(T_WOOL, cols[side < 0 ? 0 : 1][k]);
                octagonY(p, m, ox, oz, 0.16f, oy - 0.2f, oy + 0.2f);
                octagonY(p, m, ox, oz, 0.11f, oy - 0.28f, oy + 0.28f);
                p.box(ox - 0.03f, oy - 0.33f, oz - 0.03f, ox + 0.03f, oy - 0.27f, oz + 0.03f);
                p.use(T_WHITE, 0xFFEEEEEE);
                p.box(ox - 0.006f, 0.125f, oz - 0.006f, ox + 0.006f, oy - 0.33f, oz + 0.006f);
            }
        }
    }

    // ------------------------------------------------------------------ 키오스크 (뒷벽 안쪽)

    private static void kiosk(BellModel.Painter p, PoseStack m, GeoBuffers providers, Font font, float age, boolean shooting, float flash) {
        float z0 = -IN + 0.03f, z1 = -IN + 0.15f;
        p.use(T_BLACK, 0xFF2B2B30);
        p.box(-0.9f, 0.125f, z0, 0.9f, 2.75f, z1);
        p.use(T_WHITE, WHITE);
        p.box(-0.95f, 2.75f, z0, 0.95f, 2.85f, z1 + 0.02f);
        p.box(-0.95f, 0.125f, z0, 0.95f, 0.2f, z1 + 0.02f);

        // 화면 (스스로 빛난다)
        p.use(T_WHITE, 0xFF9AD4FF);
        p.lightOverride = FULL;
        p.box(-0.65f, 0.95f, z1, 0.65f, 1.6f, z1 + 0.01f);
        p.lightOverride = -1;

        // 카메라 후드 + 렌즈 (실제 촬영 시점은 이 렌즈 뒤, 벽 속)
        float cy = (float) PhotoBooth.CAM_Y;
        p.use(T_BLACK, 0xFF1A1A1E);
        p.box(-0.2f, cy - 0.17f, z1, 0.2f, cy + 0.17f, z1 + 0.07f);
        p.use(T_WHITE, 0xFF5A6A84);
        p.box(-0.09f, cy - 0.09f, z1 + 0.07f, 0.09f, cy + 0.09f, z1 + 0.075f);

        // 링 라이트
        p.use(T_LAMP, flash > 0f ? WHITE : 0xFFFFF1E0);
        p.lightOverride = FULL;
        m.pushPose();
        m.translate(0f, cy, z1 + 0.02f);
        for (int k = 0; k < 8; k++) {
            m.pushPose();
            m.rotate(Axis.ZP.rotationDegrees(k * 45f));
            p.box(-0.12f, 0.32f, -0.02f, 0.12f, 0.4f, 0.02f);
            m.popPose();
        }
        m.popPose();
        p.lightOverride = -1;

        // 출력구: 촬영이 끝나면 사진 띠가 스르륵 나온다
        p.use(T_BLACK, 0xFF0C0C0E);
        p.box(-0.22f, 0.5f, z1, 0.22f, 0.56f, z1 + 0.01f);
        float out = age - (PhotoBooth.TOTAL - 20);
        if (out > 0f && out < 220f) {
            float slide = Mth.clamp(out / 30f, 0f, 1f) * (1f - Mth.clamp((out - 190f) / 30f, 0f, 1f));
            p.use(T_WHITE, WHITE);
            p.box(-0.1f, 0.53f - 0.3f * slide, z1, 0.1f, 0.54f, z1 + 0.012f + 0.06f * slide);
        }

        // 화면 글자 (실내 쪽 +Z 를 향한다)
        String big, small;
        if (shooting) {
            int shot = 0;
            while (shot < PhotoBooth.SHOTS - 1 && age >= cap(shot) + 6) shot++;
            float left = (cap(shot) - age) / 20f;
            big = left > 0f && left <= 3f ? Integer.toString((int) Math.ceil(left)) : (left > 3f ? "♥" : "★");
            small = (shot + 1) + " / " + PhotoBooth.SHOTS;
        } else {
            big = "2027";
            small = "PHOTO";
        }
        text(m, providers, font, big, 0f, 1.32f, z1 + 0.012f, 0.026f, 0xFF10325A);
        text(m, providers, font, small, 0f, 1.06f, z1 + 0.012f, 0.011f, 0xFF10325A);
        text(m, providers, font, "SMILE!", 0f, 2.68f, z1 + 0.025f, 0.013f, 0xFFFF7FA6);
    }

    /** (x, y, z) 에 가운데 정렬 글자. +Z 를 향한다. */
    private static void text(PoseStack m, GeoBuffers providers, Font font, String s, float x, float y, float z, float scale, int color) {
        if (font == null) return;
        m.pushPose();
        m.translate(x, y, z);
        m.scale(scale, -scale, scale);
        Component c = Component.literal(s);
        providers.text(m, c, -font.width(c) / 2f, -4f, color, false, 0, FULL);
        m.popPose();
    }
}
