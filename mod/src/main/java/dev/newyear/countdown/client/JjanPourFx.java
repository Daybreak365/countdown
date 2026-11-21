package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanPour;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * 술 만드는 과정을 그린다. 웹 원작(glass.js 의 startPour)의 동작을 그대로 옮겼다:
 * 그릇이 잔 왼쪽에 나타나고 → 셰이커·믹싱 글라스·블렌더는 재료 병이 하나씩 와서 붓고, 얼음이 떨어지고, 뚜껑을 덮고,
 * 흔들거나(셰이크) 바 스푼으로 젓거나(스터) 갈고(블렌드) → 마개를 빼거나 스트레이너를 얹고 → 들어 올려 잔 위에서 기울여 따르고 → 내려놓는다.
 * 샴페인은 코르크를 따고, 캔은 딸깍 따고, 막걸리는 뒤집어 섞고, 초록 소주병은 휘휘 돌려 바닥을 툭 친다.
 * 층을 쌓는 술(B-52, 아이리시 커피의 크림)은 수면에 바 스푼을 대고 그 위로 가늘게 따른다.
 * 좌표는 잔 복셀 단위, 잔의 +Z 가 앉은 사람 쪽이다. 시간표는 {@link JjanPour}.
 */
final class JjanPourFx {
    private static final float ING_S = 0.9f;
    private static final JjanCatalog.Bottle PLAIN = new JjanCatalog.Bottle("", 0, -1, -1, -1, -1, 0xFFFFFF);

    private JjanPourFx() {}

    private static float sm(double t) {
        return (float) JjanPour.smooth(t);
    }

    private static boolean started(JjanPour.Phase p, double tp) {
        return p != null && tp >= p.t0();
    }

    private static float u(JjanPour.Phase p, double tp) {
        return (float) Mth.clamp((tp - p.t0()) / p.dur(), 0, 1);
    }

    private static VertexConsumer cutout(GeoBuffers buffers) {
        return buffers.getBuffer(RenderTypes.entityCutout(JjanTableRenderer.WHITE));
    }

    private static VertexConsumer translucent(GeoBuffers buffers) {
        return buffers.getBuffer(RenderTypes.entityTranslucent(JjanTableRenderer.WHITE));
    }

    /** 원작 BoxBuilder.cbox: x·z 는 가운데, y 는 바닥. */
    private static void cbox(VertexConsumer vc, PoseStack.Pose e, int light, float cx, float y, float cz, float w, float h, float d, int rgb) {
        JjanGlassMesh.box(vc, e, cx - w / 2, y, cz - d / 2, cx + w / 2, y + h, cz + d / 2, 0xFF000000 | rgb, light);
    }

    /** 바 스푼: 손잡이 위쪽이 +y, 숟가락이 원점. */
    static void spoon(VertexConsumer vc, PoseStack.Pose e, int light) {
        cbox(vc, e, light, 0, 1, 0, 0.45f, 26, 0.45f, 0xD5D8DA);
        for (int y = 0; y < 26; y += 3) cbox(vc, e, light, 0, 1 + y, 0, 0.5f, 1, 0.5f, 0xB9BCBF);
        cbox(vc, e, light, 0, 0, 0, 1.6f, 0.5f, 2.2f, 0xD5D8DA);
        cbox(vc, e, light, 0, 27, 0, 0.9f, 0.9f, 0.9f, 0xB9BCBF);
    }

    /** 빨대(불붙은 술용): 아래 끝이 원점, 파랑·하양 줄무늬. */
    static void straw(VertexConsumer vc, PoseStack.Pose e, int light) {
        for (int y = 0; y < 22; y += 2) cbox(vc, e, light, 0, y, 0, 0.7f, 2, 0.7f, (y / 2) % 2 == 1 ? 0xFFFFFF : 0x2F7FD8);
    }

    /** 줄렙 스트레이너: 구멍 무늬 판 + 손잡이. 원점이 판 가운데. */
    private static void strainer(VertexConsumer vc, PoseStack.Pose e, int light) {
        for (int x = -3; x < 3; x++) {
            for (int z = -3; z < 3; z++) {
                if (Math.hypot(x + 0.5, z + 0.5) >= 3.3) continue;
                JjanGlassMesh.box(vc, e, x, -0.4f, z, x + 1, 0.1f, z + 1, 0xFF000000 | (Math.floorMod(x + z, 2) != 0 ? 0xD5D8DA : 0x9EA2A5), light);
            }
        }
        for (int i = 0; i < 6; i++) JjanGlassMesh.box(vc, e, -3.3f - i, -0.3f + i * 0.25f, -0.5f, -2.3f - i, 0.2f + i * 0.25f, 0.5f, 0xFFC4C7C9, light);
    }

    private static void blenderLid(VertexConsumer vc, PoseStack.Pose e, int light) {
        for (int x = -5; x < 5; x++) {
            for (int z = -5; z < 5; z++) {
                double d = Math.hypot(x + 0.5, z + 0.5);
                if (d < 4.4) JjanGlassMesh.box(vc, e, x, 0, z, x + 1, 1.4f, z + 1, 0xFF2B2B2B, light);
                if (d < 1.6) JjanGlassMesh.box(vc, e, x, 1.4f, z, x + 1, 2.6f, z + 1, 0xFF3A3A3A, light);
            }
        }
    }

    private static void blenderBase(VertexConsumer vc, PoseStack.Pose e, int light) {
        cbox(vc, e, light, 0, 0, 0, 9, 5, 9, 0x2F2F33);
        cbox(vc, e, light, 0, 5, 0, 7, 1, 7, 0x1F1F22);
        cbox(vc, e, light, 0, 1.5f, 4.6f, 2, 2, 0.5f, 0xD84A3A);
        cbox(vc, e, light, -2.6f, 2, 4.6f, 1.2f, 1.2f, 0.5f, 0xD8D8D8);
        cbox(vc, e, light, 2.6f, 2, 4.6f, 1.2f, 1.2f, 0.5f, 0xD8D8D8);
    }

    private static void vessel(GeoBuffers buffers, PoseStack m, int light, JjanCatalog.Vessel v, JjanCatalog.Bottle b) {
        JjanGlassMesh.vessel(v.glassy() ? translucent(buffers) : cutout(buffers), m.last(), light, v, b, v.glassy() ? 0x9E : 0xFF);
    }

    /** 그릇이 쉬는 자리(잔 왼쪽 탁자 위)와 따르는 입구 자리. {restX, restY, restZ, mouthX, mouthY} */
    static float[] spots(JjanCatalog.Glass glass, JjanCatalog.Vessel v, JjanPour.Step st) {
        float rimR = (float) glass.rimR();
        float baseH = st.kind.equals("blend") ? 6 : 0;
        float mouthX = st.def.layer() ? -0.6f : v.spout() ? -rimR * 0.2f : -rimR * 0.35f;
        return new float[]{-rimR - 1.6f - (float) v.maxX(), (float) -v.minY() + baseH, -1.5f, mouthX,
                glass.height() + (v.spout() ? 5 : st.def.layer() ? 4 : 6)};
    }

    /** 지금 그릇의 자세 {x, y, z, rotX, rotY, rotZ}. 소리·입자 위치를 잡을 때도 쓴다. */
    static float[] pose(JjanCatalog.Glass glass, JjanCatalog.Vessel v, JjanPour.Step st, double t, float sec) {
        float[] sp = spots(glass, v, st);
        float restX = sp[0], restY = sp[1], restZ = sp[2];
        float[] p = {restX, restY, restZ, 0, 0, 0};
        float cx = (float) (v.minX() + v.maxX()) / 2, cy = (float) (v.minY() + v.maxY()) / 2, h = (float) (v.maxY() - v.minY());
        if (t < st.t0) {
            JjanPour.Phase cur = st.current(t - JjanPour.APPEAR);
            if (cur == null || !cur.name().equals("act")) return p;
            float u = u(cur, t - JjanPour.APPEAR), act = (float) st.act;
            switch (st.kind) {
                case "shake" -> {
                    // 셰이커를 들어 위아래로 흔든다 (잔에서 조금 떨어져서)
                    float up = sm(u / 0.18) * (1 - sm((u - 0.86) / 0.14));
                    float w = Mth.sin(u * act * Mth.TWO_PI * 6.5f);
                    p[0] += up * (w * 0.9f - 4.5f);
                    p[1] += up * (8 + w * 2.6f);
                    spin(p, up * (0.3f + w * 0.2f), cx, cy, 0);
                }
                case "blend" -> {
                    // 위잉 갈리는 동안 통이 덜덜 떨린다
                    if (u > 0.08f && u < 0.88f) {
                        p[0] += Mth.sin(sec * 173f) * 0.25f;
                        p[3] = Mth.sin(sec * 131f) * 0.025f;
                        p[5] = Mth.cos(sec * 157f) * 0.025f;
                    }
                }
                case "cork" -> {
                    // 살살 비틀다가 펑
                    float wig = u < 0.45f ? Mth.sin(u * 60) * 0.06f * sm(u / 0.45) : 0;
                    float kick = u > 0.45f ? (float) Math.exp(-(u - 0.45) * 14) * -1.2f : 0;
                    p[1] += kick;
                    float lean = sm(u / 0.2) * (1 - sm((u - 0.82) / 0.18));
                    spin(p, lean * 0.32f + wig, cx, (float) v.minY(), wig * 3);
                }
                case "can" -> {
                    // 딸깍, 치익
                    float hop = u > 0.3f ? (float) (Math.exp(-(u - 0.3) * 12) * Math.sin((u - 0.3) * 40) * 0.8) : 0;
                    p[1] += Math.max(0, hop);
                    p[5] = hop * 0.05f;
                }
                case "flip" -> {
                    // 막걸리: 거꾸로 뒤집어 가라앉은 걸 섞는다 (사발에 닿지 않게 옆으로 비켜서)
                    float up = sm(u / 0.15) * (1 - sm((u - 0.88) / 0.12));
                    p[0] += -up * h * 0.4f;
                    p[1] += up * 10;
                    float k = Mth.clamp((u - 0.12f) / 0.76f, 0, 1);
                    spin(p, Mth.PI * (1 - Mth.cos(k * Mth.TWO_PI)) / 2, cx, cy, 0);
                }
                case "swirl" -> {
                    // 소주: 병목을 쥐고 휘휘 돌린 다음 바닥을 툭
                    float up = sm(u / 0.15) * (1 - sm((u - 0.9) / 0.1));
                    float spin = sm((u - 0.1) / 0.6);
                    float jolt = u > 0.76f ? (float) Math.exp(-(u - 0.76) * 26) * 3 : 0;
                    p[0] += -up * (3 + Math.max(0, h * 0.4f - 4));
                    p[1] += up * 9 + jolt;
                    p[4] = spin * Mth.PI * 5;
                    p[5] = 0.4f * Mth.sin(spin * Mth.PI);
                }
                default -> { }
            }
            return p;
        }
        // 들어 올리고 → 잔 위로 옮겨 → 기울여 따르고 → 바로 세워 → 비켜난 다음 → 내려놓는다
        float back = sm((t - st.pour1 + 0.05) / 0.4);
        float hy = sm((t - st.t0) / 0.2) * (1 - sm((back - 0.72) / 0.28));
        float hx = sm((t - st.t0 - 0.12) / 0.26) * (1 - sm((back - 0.4) / 0.35));
        float tilt = sm((t - st.t0 - 0.12) / 0.3) * (1 - sm(back / 0.6));
        float aimX = sp[3], aimY = sp[4];
        p[0] = restX + (aimX - restX) * hx;
        p[1] = restY + (aimY - restY) * hy;
        p[2] = restZ + (0 - restZ) * hx;
        // 세운 채로는 그릇 밑동이 잔 입구보다 높이 있어야 한다. 기울이면서 천천히 내린다
        float clear = Math.max(6, glass.height() + 1.5f - (aimY + (float) v.minY()) + Math.max(0, restY - aimY));
        p[1] += hy * (1 - tilt * tilt) * clear;
        p[5] = (float) (-v.angle() * tilt * (st.def.layer() ? 0.85 : 1));
        return p;
    }

    /** (px, py)를 축으로 z 회전 (그릇 입구가 원점이라 위치를 보정한다). */
    private static void spin(float[] p, float rz, float px, float py, float ry) {
        float c = Mth.cos(rz), s = Mth.sin(rz);
        p[0] += px - (px * c - py * s);
        p[1] += py - (px * s + py * c);
        p[4] = ry;
        p[5] = rz;
    }

    /** 재료 병 하나의 자세 {x, y, z, rotZ, scale, 붓는 중(1/0)}. */
    private static float[] ingredient(JjanCatalog.Vessel v, JjanCatalog.Vessel iv, float[] sp, float intakeY, float u) {
        float restX = sp[0], restY = sp[1], restZ = sp[2];
        float ix = restX + (float) v.minX() - 2.2f - (float) iv.maxX() * ING_S, iy = (float) -iv.minY() * ING_S, iz = restZ + 2;
        float mx = restX - 1.0f, my = restY + intakeY + 3.5f;
        float lifted = sm((u - 0.12) / 0.2) * (1 - sm((u - 0.72) / 0.2));
        float tilt = sm((u - 0.26) / 0.16) * (1 - sm((u - 0.66) / 0.14));
        float shown = sm(u / 0.12) * (1 - sm((u - 0.88) / 0.12));
        return new float[]{Mth.lerp(lifted, ix, mx), Mth.lerp(lifted, iy, my) + lifted * (1 - tilt) * 6, Mth.lerp(lifted, iz, restZ),
                (float) -iv.angle() * tilt, Math.max(0.001f, shown * ING_S), tilt > 0.85f ? 1 : 0};
    }

    /**
     * time(전체 시각, 초)의 모습을 그린다. level 은 지금 액면 높이(복셀). 현재 변환은 잔 좌표(기울지 않은 상태).
     */
    static void draw(GeoBuffers buffers, PoseStack m, int light, JjanCatalog cat, JjanCatalog.Glass glass, JjanPour plan,
                     double time, float level, float sec) {
        JjanPour.Step st = plan.at(time);
        double t = time - st.start;
        if (t < 0 || t >= st.end) return;
        JjanCatalog.Bottle b = st.def.bottle();
        JjanCatalog.Vessel v = cat.vessels().get(b.vessel());
        boolean shaker = v.kind().equals("shaker"), blender = v.kind().equals("blender"), mixglass = v.kind().equals("mixglass");
        List<JjanCatalog.Ingredient> build = st.mixing ? st.def.build() : List.of();
        float[] sp = spots(glass, v, st);
        float intakeY = shaker ? (float) cat.shaker().tinTop() : 0;
        float appear = sm(t / JjanPour.APPEAR) * (1 - sm((t - (st.end - 0.25)) / 0.25));
        double tp = t >= st.t0 ? 1e9 : t - JjanPour.APPEAR;
        JjanPour.Phase ice = st.phase("ice"), cap = st.phase("cap"), fin = st.phase("fin"), cur = st.current(tp);
        boolean acting = t < st.t0 && cur != null && cur.name().equals("act");
        boolean open = st.mixing && !build.isEmpty();
        float[] p = pose(glass, v, st, t, sec);

        m.pushPose();
        m.translate(p[0], p[1], p[2]);
        m.rotate(new Quaternionf().rotationXYZ(p[3], p[4], p[5]));
        float scale = Math.max(0.001f, appear * 0.95f);
        m.scale(scale, scale, scale);
        VertexConsumer solid = cutout(buffers);
        if (shaker) {
            // 통 + 거름망 뚜껑 + 마개: 재료를 다 넣은 뒤에 덮고, 따를 땐 마개만 뺀다
            vessel(buffers, m, light, cat.vessels().get(cat.shaker().tin()), PLAIN);
            if (!open || started(cap, tp)) {
                float lidY = started(cap, tp) ? (1 - sm(u(cap, tp))) * 9 : 0;
                float k = started(fin, tp) ? sm(u(fin, tp)) : 0;
                m.pushPose();
                m.translate(0, lidY, 0);
                vessel(buffers, m, light, cat.vessels().get(cat.shaker().lid()), PLAIN);
                m.popPose();
                if (k < 0.95f) {
                    m.pushPose();
                    m.translate(-k * 5, lidY + k * 7, 0);
                    vessel(buffers, m, light, cat.vessels().get(cat.shaker().cap()), PLAIN);
                    m.popPose();
                }
            }
        } else {
            vessel(buffers, m, light, v, b);
        }
        if (blender && (!open || started(cap, tp))) {
            float k = started(fin, tp) ? sm(u(fin, tp)) : 0;
            if (k < 0.95f) {
                m.pushPose();
                m.translate(-k * 6, (started(cap, tp) ? (1 - sm(u(cap, tp))) * 9 : 0) + k * 8, 0);
                blenderLid(solid, m.last(), light);
                m.popPose();
            }
        }
        if (mixglass && (st.kind.isEmpty() || started(fin, tp))) {
            m.pushPose();
            m.translate(0, started(fin, tp) ? (1 - sm(u(fin, tp))) * 8 : 0, 0);
            strainer(solid, m.last(), light);
            m.popPose();
        }
        if (st.kind.equals("cork") && (t < JjanPour.APPEAR || (acting && u(cur, tp) <= 0.45f))) {
            JjanGlassMesh.box(solid, m.last(), -0.9f, -0.1f, -0.9f, 0.9f, 1.5f, 0.9f, 0xFFC9A26B, light);
        }
        // 섞는 그릇에 넣는 얼음: 위에서 하나씩 떨어진다 (불투명한 셰이커 속으로 들어가면 안 보인다)
        if (st.mixing && started(ice, tp)) {
            VertexConsumer tr = translucent(buffers);
            float iu = u(ice, tp);
            for (int k = 0; k < 4; k++) {
                float tk = Mth.clamp((iu - k * 0.16f) / 0.38f, 0, 1);
                if (tk <= 0) continue;
                float dx = (k % 2 == 1 ? 0.9f : -0.9f) * (k < 2 ? 1 : 0.6f), dz = k < 2 ? 0.7f : -0.8f;
                float y0 = intakeY + 10, y1 = v.glassy() || blender ? (float) v.minY() + 1.6f + (k / 2) * 1.6f : intakeY - 1.5f;
                float y = y0 + (y1 - y0) * tk * tk;
                if (shaker && y <= intakeY - 0.5f) continue;
                m.pushPose();
                m.translate(dx * (1 - tk * 0.2f), y, dz);
                m.rotate(new Quaternionf().rotationXYZ(k + tk * 2, k * 0.7f, tk));
                JjanGlassMesh.box(tr, m.last(), -0.85f, -0.85f, -0.85f, 0.85f, 0.85f, 0.85f, 0xB3E4F5FB, light);
                m.popPose();
            }
        }
        // 믹싱 글라스: 바 스푼으로 얼음과 함께 빙글빙글 저은 뒤 스푼을 뺀다
        if (st.kind.equals("stir") && acting) {
            float su = u(cur, tp);
            float a = su * (float) st.act * Mth.TWO_PI * 2.4f;
            float inside = sm(su / 0.12) * (1 - sm((su - 0.82) / 0.18));
            if (inside > 0.05f) {
                m.pushPose();
                m.translate(Mth.cos(a) * 1.7f, (float) v.minY() + 1.2f + (1 - inside) * 22, Mth.sin(a) * 1.7f);
                m.rotate(new Quaternionf().rotationXYZ(Mth.sin(a) * 0.12f, 0, -Mth.cos(a) * 0.12f));
                spoon(solid, m.last(), light);
                m.popPose();
            }
        }
        m.popPose();

        if (st.kind.equals("cork")) {
            // 펑: 코르크가 병 입구에서 튀어 올라 탁자에 떨어져 구른다 (원작: 위로 95, 옆으로 22 복셀/초, 중력 260)
            JjanPour.Phase act = st.phase("act");
            double tc = t - (JjanPour.APPEAR + act.t0() + 0.45 * act.dur());
            if (tc > 0) {
                float[] at = pose(glass, v, st, JjanPour.APPEAR + act.t0() + 0.45 * act.dur(), sec);
                float cx = at[0] - 22f * (float) tc, cy = at[1] + 1 + 95f * (float) tc - 130f * (float) (tc * tc);
                if (cy < 0) {
                    // 탁자에 닿은 뒤: 닿은 자리에서 조금 더 구르다 멈춘다
                    double land = (95 + Math.sqrt(95 * 95 + 4 * 130 * (at[1] + 1))) / 260;
                    cx = at[0] - 22f * (float) land - 4f * (1 - (float) Math.exp(-(tc - land) * 3));
                    cy = 0;
                }
                float k = Math.max(0.001f, appear);
                m.pushPose();
                m.translate(cx, cy, at[2]);
                m.rotate(Axis.ZP.rotation((float) tc * 9f * (cy > 0 ? 1 : 0)));
                m.scale(k, k, k);
                JjanGlassMesh.box(solid, m.last(), -0.9f, 0, -0.9f, 0.9f, 1.6f, 0.9f, 0xFFC9A26B, light);
                m.popPose();
            }
        }
        if (st.kind.equals("blend")) {
            m.pushPose();
            m.translate(sp[0], 0, sp[2]);
            float k = Math.max(0.001f, appear);
            m.scale(k, k, k);
            blenderBase(solid, m.last(), light);
            m.popPose();
        }
        // 재료 하나: 병이 나와서 그릇 위로 기울여 붓고 돌아간다
        if (t < st.t0 && cur != null && cur.name().equals("ing")) {
            JjanCatalog.Ingredient ing = build.get(cur.ing());
            JjanCatalog.Vessel iv = cat.vessels().get(ing.bottle().vessel());
            float[] g = ingredient(v, iv, sp, intakeY, u(cur, tp));
            m.pushPose();
            m.translate(g[0], g[1], g[2]);
            m.rotate(Axis.ZP.rotation(g[3]));
            m.scale(g[4], g[4], g[4]);
            vessel(buffers, m, light, iv, ing.bottle());
            m.popPose();
            if (g[5] > 0) {
                float top = g[1] - 0.2f, bottom = sp[1] + intakeY - 1, w = (ing.dash() ? 0.35f : 0.55f) / 2;
                JjanGlassMesh.box(translucent(buffers), m.last(), g[0] + 0.2f - w, bottom, sp[2] - w, g[0] + 0.2f + w, Math.max(bottom + 0.1f, top), sp[2] + w,
                        0xBF000000 | (ing.bottle().stream() & 0xFFFFFF), light);
            }
        }
        // 층 쌓기: 수면 바로 위에 바 스푼을 엎어 대고 그 위로 따른다
        if (st.def.layer()) {
            float k = sm((t - st.t0) / 0.3) * (1 - sm((t - st.pour1) / 0.3)) * Mth.clamp(glass.height() / 16f, 0.45f, 0.8f);
            if (k > 0.01f) {
                m.pushPose();
                m.translate(0.2f, level + 0.6f, 0.2f);
                m.rotate(Axis.ZP.rotation(-0.55f));
                m.scale(k, k, k);
                spoon(solid, m.last(), light);
                m.popPose();
            }
        }
        // 물줄기: 그릇 입구에서 수면까지 (층을 쌓을 땐 가늘게, 스푼 위까지)
        if (t > st.pour0 && t < st.pour1) {
            float top = p[1] - 0.2f, sx = p[0] + 0.25f, sz = p[2];
            float bottom = st.def.layer() ? level + 1.2f : Math.max(glass.cavityStart(), level);
            float thin = st.def.layer() ? 0.4f : 0.7f, wob = Mth.sin((float) t * 40) * 0.08f;
            float wx = (thin + wob) / 2, wz = (thin - wob) / 2;
            if (top > bottom) {
                JjanGlassMesh.box(translucent(buffers), m.last(), sx - wx, bottom, sz - wz, sx + wx, top, sz + wz,
                        0xBF000000 | (b.stream() & 0xFFFFFF), light);
            }
        }
    }
}
