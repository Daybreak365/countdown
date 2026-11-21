package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 짠 판정: 한 번 부딪히면 한 번만 세고(두 사람 모두 1회), 떼었다가 다시 부딪혀야 다시 센다. 느리게 닿거나 둘 다 내려놓은 잔은 짠이 아니다. */
class JjanClinksTest {
    private static final JjanCatalog CAT = JjanCatalog.load();
    private static final JjanTables.Def SQUARE = JjanTables.byId("square");

    /** 한 테이블의 시뮬레이션: 자리별 변위를 주고 틱을 돌리며 소리·횟수를 센다. */
    private static final class Sim implements JjanClinks.Listener {
        final List<JjanSession> all = new ArrayList<>();
        final Map<Long, JjanClinks.Pair> pairs = new HashMap<>();
        final double[] dx = new double[4], dz = new double[4];
        final List<Double> strengths = new ArrayList<>();
        int sounds, counts;
        long now;

        JjanSession add(int seat, String glass, double x, double z, boolean raised) {
            JjanSession s = new JjanSession(UUID.randomUUID(), seat, 1000 + seat);
            s.ordered = true;
            s.state = JjanSession.ACTIVE;
            s.drink = CAT.drink("lager");
            s.glass = CAT.glass(glass);
            s.fill = 1;
            s.x = x;
            s.z = z;
            s.pose = raised ? JjanSession.RAISED : JjanSession.RESTING;
            s.y = raised ? Math.max(SQUARE.top + 0.02, SQUARE.top + JjanTableEntityHeights.RAISE_PLANE - s.glass.contactMid() * JjanRules.GLASS_SCALE) : SQUARE.top;
            all.add(s);
            return s;
        }

        void tick(int n) {
            for (int i = 0; i < n; i++) {
                List<JjanSession> moving = new ArrayList<>();
                for (JjanSession s : all) if (s.pose == JjanSession.RAISED && s.action == JjanSession.IDLE) moving.add(s);
                JjanClinks.step(SQUARE, all, moving, dx, dz, pairs, now++, this);
            }
        }

        @Override
        public void sound(JjanSession a, JjanSession b, double strength) {
            sounds++;
            strengths.add(strength);
        }

        @Override
        public void count(JjanSession a, JjanSession b) {
            counts++;
            a.clinks++;
            b.clinks++;
        }
    }

    /** 테스트가 Minecraft 클래스(블록 엔티티)를 불러오지 않도록 높이 상수만 따로 둔다. 값은 JjanTableEntity.RAISE_PLANE 과 같아야 한다. */
    private static final class JjanTableEntityHeights {
        static final double RAISE_PLANE = 0.30;
    }

    @Test
    void oneHitCountsOnceForBothEvenWhileStillPushing() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "pint", -0.5, 0, true), b = sim.add(1, "pint", 0.2, 0, false);
        sim.dx[0] = 0.05;   // 1 블록/초로 다가간다
        sim.tick(40);       // 닿은 뒤에도 계속 민다
        assertEquals(1, sim.counts, "붙어 있는 동안에는 다시 세지 않는다");
        assertEquals(1, sim.sounds);
        assertEquals(1, a.clinks);
        assertEquals(1, b.clinks);
        assertEquals((1.0 - 0.06) / 1.70, sim.strengths.get(0), 1e-6, "세기는 접근 속도로 정한다");
        double gap = Math.hypot(b.x - a.x, b.z - a.z);
        assertTrue(gap >= a.radius() + b.radius() - 1e-6, "든 잔은 겹치지 않게 밀려난다");
        assertEquals(0.2, b.x, 1e-9, "내려놓은 잔은 밀리지 않는다");
    }

    @Test
    void separatingAndComingBackCountsAgainAfterCooldown() {
        Sim sim = new Sim();
        sim.add(0, "pint", -0.5, 0, true);
        sim.add(1, "pint", 0.2, 0, false);
        sim.dx[0] = 0.05;
        sim.tick(12);
        assertEquals(1, sim.counts);
        sim.dx[0] = -0.05;
        sim.tick(8);        // 0.4 블록 물러난다 (재무장 거리 0.06 보다 멀리)
        sim.dx[0] = 0.05;
        sim.tick(12);
        assertEquals(2, sim.counts);
        assertEquals(2, sim.sounds);
    }

    @Test
    void quickBounceSoundsAgainButDoesNotCountWithinCooldown() {
        Sim sim = new Sim();
        sim.add(0, "pint", -0.2, 0, true);
        sim.add(1, "pint", 0.2, 0, false);
        sim.dx[0] = 0.05;
        sim.tick(4);        // 3번째 틱쯤 닿는다
        assertEquals(1, sim.counts);
        sim.dx[0] = -0.05;
        sim.tick(3);        // 0.15 블록 물러났다가
        sim.dx[0] = 0.05;
        sim.tick(4);        // 곧바로 다시 (횟수 쿨다운 13틱 안)
        assertEquals(1, sim.counts, "13틱 안의 재접촉은 횟수에 넣지 않는다");
        assertEquals(2, sim.sounds, "소리는 3틱 쿨다운이라 다시 난다");
    }

    @Test
    void slowTouchIsNotAClink() {
        Sim sim = new Sim();
        sim.add(0, "pint", -0.3, 0, true);
        sim.add(1, "pint", 0.0, 0, false);
        sim.dx[0] = 0.004;  // 0.08 블록/초 < 최소 접근 속도 0.10
        sim.tick(60);
        assertEquals(0, sim.counts);
        assertEquals(0, sim.sounds);
    }

    @Test
    void restingGlassesNeverClink() {
        Sim sim = new Sim();
        sim.add(0, "pint", 0.0, 0, false);
        sim.add(1, "pint", 0.1, 0, false);   // 겹쳐 놓여 있어도
        sim.tick(20);
        assertEquals(0, sim.counts);
        assertEquals(0, sim.sounds);
    }

    @Test
    void busyOrReturningGlassCannotClink() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "pint", -0.5, 0, true), b = sim.add(1, "pint", 0.2, 0, false);
        b.action = JjanSession.SIPPING;      // 마시는 중인 잔
        sim.dx[0] = 0.05;
        sim.tick(30);
        assertEquals(0, sim.counts);

        Sim sim2 = new Sim();
        JjanSession c = sim2.add(0, "pint", -0.5, 0, true);
        JjanSession d = sim2.add(1, "pint", 0.2, 0, false);
        d.pose = JjanSession.RETURNING;      // 제자리로 돌아가는 잔
        sim2.dx[0] = 0.05;
        sim2.tick(30);
        assertEquals(0, sim2.counts);
        assertFalse(a.busy());
        assertFalse(c.busy());
    }

    @Test
    void fastSmallGlassesDoNotTunnelThroughEachOther() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "soju", -0.6, 0, true), b = sim.add(1, "soju", 0.1, 0, false);
        sim.dx[0] = 0.12;   // 최대 속도 2.4 블록/초
        sim.tick(20);
        assertEquals(1, sim.counts);
        assertTrue(a.x < b.x, "작은 잔을 뚫고 지나가지 않는다");
    }

    @Test
    void twoRaisedGlassesMeetingHeadOnCountOnce() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "pint", -0.5, 0, true), b = sim.add(1, "pint", 0.5, 0, true);
        sim.dx[0] = 0.04;
        sim.dx[1] = -0.04;
        sim.tick(30);
        assertEquals(1, sim.counts);
        assertEquals(1, a.clinks);
        assertEquals(1, b.clinks);
        assertEquals((1.6 - 0.06) / 1.70, sim.strengths.get(0), 1e-6, "마주 오면 두 속도의 합");
    }

    @Test
    void threeGlassesCountPerPair() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "pint", 0.0, -0.5, true);
        JjanSession b = sim.add(1, "pint", -0.13, 0.2, false), c = sim.add(2, "pint", 0.13, 0.2, false);
        sim.dz[0] = 0.05;   // 가운데로 들어가 두 잔에 함께 닿는다
        sim.tick(30);
        assertEquals(2, sim.counts);
        assertEquals(2, a.clinks);
        assertEquals(1, b.clinks);
        assertEquals(1, c.clinks);
    }

    @Test
    void glassesAtDifferentHeightsDoNotTouch() {
        Sim sim = new Sim();
        JjanSession a = sim.add(0, "pint", -0.5, 0, true);
        sim.add(1, "soju", 0.2, 0, false);
        a.y = SQUARE.top + 0.5;              // 낮은 소주잔 위로 지나간다
        sim.dx[0] = 0.05;
        sim.tick(30);
        assertEquals(0, sim.counts);
    }

    @Test
    void followLandsExactlyOnTheCursor() {
        double[] st = {0, 0};
        JjanClinks.follow(st, 0.3, -0.2, 0.05);
        assertEquals(0.3, st[0], 0.0, "한 틱 안에 닿을 거리면 바로 커서 자리: 출렁임도 미끄러짐도 없다");
        assertEquals(-0.2, st[1], 0.0);
        JjanClinks.follow(st, 0.3, -0.2, 0.05);
        assertEquals(0.3, st[0], 0.0, "멈춘 커서에서는 움직이지 않는다");
        JjanClinks.follow(st, 0.31, -0.2, 0.004);
        assertEquals(0.31, st[0], 0.0, "한 프레임의 작은 움직임도 그대로");
    }

    @Test
    void followSpeedIsCapped() {
        double[] st = {0, 0};
        JjanClinks.follow(st, 30, 40, 0.05);
        assertEquals(JjanClinks.FOLLOW_SPEED * 0.05, Math.hypot(st[0], st[1]), 1e-9, "멀리 뛴 목표로는 최고 속도로만");
        assertEquals(0.75, st[1] / st[0] * 0.5625, 1e-9, "목표 방향으로 곧장");
        assertTrue(JjanClinks.FOLLOW_SPEED * 0.05 / JjanClinks.SUBSTEPS < 0.1, "서브스텝 한 번의 이동은 작은 잔 지름보다 작다(통과 방지)");
    }
}
