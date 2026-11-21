package dev.newyear.countdown.jjan;

/**
 * 짠: 원본과 같아야 하는 수치 규칙(잔량, 음주량, 취기, 짠 강도). Minecraft 와 무관한 순수 계산이라 단위 테스트로 검증한다.
 * 취기는 게임 속 숫자이며 실제 혈중알코올농도와 관계없다.
 */
public final class JjanRules {
    /** 한 모금은 최초 제공량의 34%. */
    public static final double SIP = 0.34;
    /** 이 값보다 적게 남으면 빈 잔. */
    public static final double EMPTY = 0.02;
    /** 이 값보다 많이 남아 있으면 리필 불가(원본 UI). */
    public static final double REFILL_FULL = 0.97;

    public static final double INTOX_MAX = 1.6;
    public static final double INTOX_PER_ML = 1.0 / 60.0;
    public static final double INTOX_DECAY_PER_SEC = 0.0035;
    public static final double DISPLAY_UP_PER_SEC = 0.06;
    public static final double DISPLAY_DOWN_PER_SEC = 0.12;

    /** 짠 판정 (블록·초·틱). 원본 cm 단위 상수를 환산한 초기값. */
    public static final double MIN_APPROACH = 0.10;
    public static final int SOUND_COOLDOWN = 3;
    public static final int COUNT_COOLDOWN = 13;
    public static final double SOUND_REARM_GAP = 0.02;
    public static final double COUNT_REARM_GAP = 0.06;

    /** 연출 시간 (원본 초 → 틱, 올림). */
    public static final double SIP_SECONDS = 1.55;

    /** 원샷으로 이어지는 마시기 버튼 누름 시간(초). */
    public static final double HOLD_FOR_SHOT = 0.85;

    private JjanRules() {}

    /** 한 모금이 남은 양을 모두 비우는 잔인지: 술의 shot 속성, 소주잔, 샷잔. */
    public static boolean sipEmpties(boolean drinkShot, String glassId) {
        return drinkShot || "soju".equals(glassId) || "shot".equals(glassId);
    }

    /** 한 모금 뒤 남은 양. */
    public static double afterSip(double before, boolean empties) {
        return empties ? 0 : Math.max(0, before - SIP);
    }

    public static boolean isEmpty(double fill) {
        return fill < EMPTY;
    }

    public static int sipTicks() {
        return (int) Math.ceil(SIP_SECONDS * 20);
    }

    /** 원샷 연출: 1.55 + 남은 양(초). */
    public static int chugTicks(double before) {
        return (int) Math.ceil((SIP_SECONDS + before) * 20);
    }

    /** 마신 양(ml)과 그 안의 알코올(ml, 게임 수치). */
    public static double consumedMl(double before, double after, int capacityMl) {
        return Math.max(0, before - after) * capacityMl;
    }

    public static double alcoholMl(double consumedMl, double abv) {
        return consumedMl * abv / 100.0;
    }

    /** 마신 알코올만큼 취기 목표를 올린다 (최대 1.6). */
    public static double addIntox(double target, double alcoholMl) {
        return Math.min(INTOX_MAX, target + alcoholMl * INTOX_PER_ML);
    }

    /** 시간이 지나 깬다. 시계가 뒤로 가면(음수) 적용하지 않는다. */
    public static double decay(double target, double elapsedSeconds) {
        if (!(elapsedSeconds > 0)) return target;
        return Math.max(0, target - INTOX_DECAY_PER_SEC * elapsedSeconds);
    }

    /** 표시 값은 목표를 향해 오를 때 초당 0.06, 내릴 때 초당 0.12 움직인다. */
    public static double approachDisplayed(double displayed, double target, double seconds) {
        if (displayed < target) return Math.min(target, displayed + DISPLAY_UP_PER_SEC * seconds);
        return Math.max(target, displayed - DISPLAY_DOWN_PER_SEC * seconds);
    }

    /** 취기 단계 0 말짱, 1 기분 좋음, 2 알딸딸, 3 어질어질, 4 만취. */
    public static int stage(double displayed) {
        if (displayed < 0.1) return 0;
        if (displayed < 0.3) return 1;
        if (displayed < 0.55) return 2;
        if (displayed < 0.8) return 3;
        return 4;
    }

    public static final String[] STAGE_KEYS = {"sober", "good", "tipsy", "dizzy", "drunk"};

    /** 짠 강도 0.05..1. 접근 속도(블록/초)가 MIN_APPROACH 이하면 짠이 아니다. */
    public static double clinkStrength(double approachSpeed) {
        return Math.max(0.05, Math.min(1.0, (approachSpeed - 0.06) / 1.70));
    }

    /** 원본 cm 복셀 → Minecraft 블록. 잔은 보기 좋게 실제보다 약 3배 크게 (1cm = 1/32 블록). */
    public static final double GLASS_SCALE = 1.0 / 32.0;

    /**
     * 취기 → 화면 어지러움(바닐라 멀미 세기 0..1). 말짱(0.1 미만)이면 0, 취할수록 커져 1.4 에서 최대.
     * 원본 drunkCamera 의 흔들림 곡선(취기^1.3)을 따른다. 대략 알딸딸 0.09~0.25, 어질어질 0.25~0.45, 만취 0.45~1.
     */
    public static double nausea(double displayed, boolean reduced) {
        double d = (Math.min(1.4, displayed) - 0.1) / 1.3;
        if (!(d > 0)) return 0;
        return Math.pow(d, 1.3) * (reduced ? 0.4 : 1.0);
    }

    /** 든 잔을 마우스로 옮기는 감도: 시선이 1도 돌 만큼의 마우스 이동에 잔이 움직이는 거리(블록). 탁자 어디서나 같다. */
    public static final double DRAG_PER_DEGREE = 0.02;

    /**
     * 마우스 이동(시선이 돌았을 각도: 오른쪽 +yaw, 아래 +pitch)을 탁자 위 이동(월드 x, z)으로 바꾼다.
     * 바라보는 방향(viewYaw, Minecraft 각도) 기준으로 마우스 오른쪽 = 화면 오른쪽, 마우스 위 = 멀리, 아래 = 내 쪽.
     * 거리에 상관없이 같은 마우스 이동은 같은 거리를 움직인다 (큰 탁자의 먼 쪽에서 잔이 튀지 않는다).
     */
    public static double[] drag(double yawDegrees, double pitchDegrees, double viewYawDegrees) {
        double y = Math.toRadians(viewYawDegrees), fx = -Math.sin(y), fz = Math.cos(y);
        double right = yawDegrees * DRAG_PER_DEGREE, forward = -pitchDegrees * DRAG_PER_DEGREE;
        return new double[]{-fz * right + fx * forward, fx * right + fz * forward};
    }

    /** 취했을 때 든 잔이 흔들리는 폭(블록). 서버와 클라이언트 예측이 같은 값을 쓴다. */
    public static double wobble(double intox, boolean reduced) {
        return 0.035 * intox * (reduced ? 0.4 : 1.0);
    }
}
