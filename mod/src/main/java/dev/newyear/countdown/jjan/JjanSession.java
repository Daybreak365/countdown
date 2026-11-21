package dev.newyear.countdown.jjan;

import java.util.UUID;

/**
 * 한 자리에 앉은 플레이어의 술자리 상태 (서버). 잔 위치는 테이블 로컬 좌표(블록), 잔 바닥 기준.
 * 좌석·세션·잔 위치·잔 동작·잔량을 서로 다른 값으로 둔다 (빈 잔을 든 상태가 가능하도록).
 */
public final class JjanSession {
    // 세션 상태
    public static final int SELECTING = 0, ACTIVE = 1, LEAVING = 2, CLOSED = 3;
    // 잔 위치
    public static final int RESTING = 0, RAISED = 1, RETURNING = 2;
    // 잔 동작
    public static final int IDLE = 0, PREPARING = 1, SIPPING = 2, CHUGGING = 3, REFILLING = 4;

    final UUID player;
    final int seat;
    final long sessionId;
    int state = SELECTING;
    int actionId;

    // 주문 (ordered 가 false 면 아직 잔이 없다)
    boolean ordered;
    JjanCatalog.Drink drink;
    JjanCatalog.Variant variant;
    JjanCatalog.Glass glass;

    int pose = RESTING;
    int action = IDLE;
    long actionStart;
    int actionLen;
    long returnStart;
    double returnFromX, returnFromZ, returnFromY;

    double fill;        // 남은 비율 0..1 (확정값)
    double fillFrom;    // 연출 시작 때의 값
    double x, y, z;     // 잔 바닥 위치 (로컬)
    double vx, vz;      // 블록/초
    double targetX, targetZ;
    boolean hasTarget;

    int clinks;
    double consumedMl;
    int say = -1;

    // 안주 접시 (술과 함께 나온다): 남은 조각 수, 다 먹으면 다시 채워질 시각, 마지막으로 집어 간 자리와 그 시각
    int anjuLeft;
    int anjuChoice = -1;   // 직접 고른 안주 번호 (-1 = 술에 어울리는 것으로)
    long anjuRefill = -1;
    int anjuEater = -1;
    long anjuTick;
    long lastEat = -100;
    long sayTick;

    JjanSession(UUID player, int seat, long sessionId) {
        this.player = player;
        this.seat = seat;
        this.sessionId = sessionId;
    }

    boolean busy() {
        return action != IDLE;
    }

    /** 짠을 할 수 있는 상태: 잔이 있고, 아무 동작도 하지 않으며, 제자리로 돌아가는 중이 아니다. */
    boolean canClink() {
        return state == ACTIVE && ordered && action == IDLE && pose != RETURNING;
    }

    double radius() {
        return glass.contactR() * JjanRules.GLASS_SCALE;
    }

    double low() {
        return y + glass.contactLow() * JjanRules.GLASS_SCALE;
    }

    double high() {
        return y + glass.height() * JjanRules.GLASS_SCALE;
    }
}
