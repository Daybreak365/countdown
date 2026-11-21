package dev.newyear.countdown.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 포토부스 포즈. 서버가 알려 준 플레이어별 포즈를 보관하고, 플레이어(와 갑옷) 모델에 자세를 입힌다.
 *
 * 팔은 Limbs 가 위팔/아래팔 두 마디로 나눠 두었으므로 팔꿈치를 굽혀 손을 얼굴·턱·가슴 앞으로 가져갈 수 있다.
 * 각 팔 값 {xRot, yRot, zRot, 팔꿈치}는 손·팔꿈치 목표 위치(모델 픽셀)에 맞춰 수치로 찾은 값이다
 * (예: 브이 = 오른손이 오른쪽 눈 옆, 꽃받침 = 두 손이 턱 아래, 손하트 = 오른손이 가슴 앞).
 * 포즈를 바꾸면 0.22초 동안 부드럽게 이어진다.
 */
public final class PhotoPose {
    /** 포즈 이름(번역 키 접미사). PhotoBooth.POSES 와 같은 순서. */
    public static final String[] KEYS = {"none", "v", "heart", "chin", "cat", "banzai", "thumbs", "salute", "cross", "point", "akimbo", "jump", "squat"};

    // 값 배열: 0 엉덩이 높이(px, 서 있으면 12), 1 상체 숙임, 2 고개 들기(+숙임), 3 고개 갸웃,
    //          4..7 오른팔 {x, y, z, 팔꿈치}, 8..11 왼팔, 12 허벅지, 13 무릎(+뒤로 접힘), 14 다리 벌림
    private static final float[] RELAX_R = {0f, 0f, 0.06f, 0f}, RELAX_L = {0f, 0f, -0.06f, 0f};
    private static final float[] AKIMBO_R = {0.79f, -1.04f, -0.2f, -1.18f}, AKIMBO_L = {0.79f, 1.04f, 0.2f, -1.18f};
    private static final float[] CHIN_R = {-0.73f, -0.68f, 0.05f, -1.85f}, CHIN_L = {-0.73f, 0.68f, -0.05f, -1.85f};
    private static final float[][] POSES = {
            p(12, 0, 0, 0, RELAX_R, RELAX_L, 0, 0, 0.03f),                                                   // 기본
            p(12, 0, 0, -0.2f, a(-1.26f, 0.81f, 0.83f, -1.47f), AKIMBO_L, 0, 0, 0.05f),                     // 브이
            p(12, 0, 0, 0.12f, a(-1.27f, -0.5f, 0.64f, -1.07f), RELAX_L, 0, 0, 0.03f),                      // 손하트
            p(12, 0, -0.1f, 0.12f, CHIN_R, CHIN_L, 0, 0, 0.03f),                                            // 꽃받침
            p(12, 0, 0, -0.15f, a(-0.97f, 0.04f, 0.49f, -1.87f), a(-0.97f, -0.04f, -0.49f, -1.87f), 0, 0, 0.05f), // 냥냥
            p(12, 0, -0.2f, 0, a(-2.95f, 0f, -0.45f, 0f), a(-2.95f, 0f, 0.45f, 0f), 0, 0, 0.12f),            // 만세
            p(12, 0, 0, 0.06f, a(-1.12f, -0.07f, -0.03f, -1.09f), a(-1.12f, 0.07f, 0.03f, -1.09f), 0, 0, 0.06f), // 쌍따봉
            p(12, 0, -0.05f, 0, a(-1.74f, 0.64f, 0.91f, -0.93f), a(0f, 0f, -0.04f, 0f), 0, 0, 0.02f),        // 경례
            p(12, 0, -0.12f, 0, a(-0.61f, -0.96f, 0.42f, -1.5f), a(-0.69f, 0.88f, -0.39f, -1.48f), 0, 0, 0.08f), // 팔짱
            p(12, 0, 0, 0.1f, a(-1.6f, -0.07f, -0.55f, -0.01f), AKIMBO_L, 0, 0, 0.06f),                     // 가리키기
            p(12, 0, -0.1f, 0, AKIMBO_R, AKIMBO_L, 0, 0, 0.1f),                                             // 당당 (허리에 손)
            p(8, 0, -0.15f, 0, a(-2.7f, 0f, -0.7f, 0f), a(-2.7f, 0f, 0.7f, 0f), -1.0f, 1.9f, 0.1f),         // 점프
            p(17, 0.3f, -0.3f, 0.1f, CHIN_R, CHIN_L, -1.4f, 1.4f, 0.14f),                                   // 앉기 (앞줄)
    };
    private static final long BLEND_NANOS = 220_000_000L;

    private static final class State {
        int pose, prev;
        long changed = Long.MIN_VALUE / 2;
    }

    private static final Map<UUID, State> LOOKS = new HashMap<>();

    private PhotoPose() {}

    private static float[] a(float x, float y, float z, float bend) {
        return new float[]{x, y, z, bend};
    }

    private static float[] p(float hipY, float lean, float headX, float headZ, float[] r, float[] l, float thigh, float knee, float spread) {
        return new float[]{hipY, lean, headX, headZ, r[0], r[1], r[2], r[3], l[0], l[1], l[2], l[3], thigh, knee, spread};
    }

    public static void set(UUID id, int pose) {
        pose = Mth.clamp(pose, 0, POSES.length - 1);
        State s = LOOKS.get(id);
        if (s == null) {
            if (pose == 0) return;
            s = new State();
            LOOKS.put(id, s);
        }
        if (s.pose != pose) {
            s.prev = s.pose;
            s.pose = pose;
            s.changed = System.nanoTime();
        }
    }

    public static int pose(UUID id) {
        State s = LOOKS.get(id);
        return s == null ? 0 : s.pose;
    }

    public static void clear() {
        LOOKS.clear();
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    public static void apply(HumanoidModel<?> model, Player player) {
        State s = LOOKS.get(player.getUUID());
        if (s == null) return;
        float t = smooth((System.nanoTime() - s.changed) / (float) BLEND_NANOS);
        if (s.pose == 0 && t >= 1f) return;   // 기본 포즈로 돌아온 뒤에는 평소 움직임(걷기 등)을 그대로 둔다
        float[] from = POSES[s.prev], to = POSES[s.pose];
        float[] p = new float[to.length];
        for (int i = 0; i < p.length; i++) p[i] = Mth.lerp(t, from[i], to[i]);

        float hipY = p[0], lean = p[1];
        float neckY = hipY - 12f * Mth.cos(lean), neckZ = -12f * Mth.sin(lean);
        model.body.x = 0f;
        model.body.y = neckY;
        model.body.z = neckZ;
        model.body.xRot = lean;
        model.body.yRot = 0f;
        model.body.zRot = 0f;
        model.head.x = 0f;
        model.head.y = neckY;
        model.head.z = neckZ;
        model.head.xRot += p[2];
        model.head.zRot = p[3];

        float shY = neckY + 2f * Mth.cos(lean), shZ = neckZ + 2f * Mth.sin(lean);
        arm(model.rightArm, -5f, shY, shZ, p, 4);
        arm(model.leftArm, 5f, shY, shZ, p, 8);
        leg(model.rightLeg, -1.9f, hipY, p[12], p[13], p[14]);
        leg(model.leftLeg, 1.9f, hipY, p[12], p[13], -p[14]);
    }

    private static void arm(ModelPart arm, float x, float y, float z, float[] p, int i) {
        arm.x = x;
        arm.y = y;
        arm.z = z;
        arm.xRot = p[i];
        arm.yRot = p[i + 1];
        arm.zRot = p[i + 2];
        Limbs.bend(arm, p[i + 3]);
    }

    private static void leg(ModelPart leg, float x, float hipY, float thigh, float knee, float spread) {
        leg.x = x;
        leg.y = hipY;
        leg.z = 0f;
        leg.xRot = thigh;
        leg.yRot = 0f;
        leg.zRot = spread;
        Limbs.bend(leg, knee);
    }
}
