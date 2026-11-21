package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 짠 판정의 순수 계산부: 든 잔의 서브스텝 이동, 겹친 잔 떼어 놓기, 접촉·접근 속도·재접촉 거리·쿨다운으로 소리와 횟수를 결정한다.
 * Minecraft 월드와 무관해서 단위 테스트로 검증한다. 테이블 엔티티는 결과(Listener)를 받아 소리·파티클·통계만 처리한다.
 */
public final class JjanClinks {
    /** 한 틱을 나누는 횟수 (작은 잔끼리 한 틱에 통과하지 않게). */
    public static final int SUBSTEPS = 8;

    public interface Listener {
        /** 부딪히는 소리·연출. strength 0.05..1. */
        void sound(JjanSession a, JjanSession b, double strength);

        /** 짠 한 번 (두 사람 모두에게 1회). */
        void count(JjanSession a, JjanSession b);
    }

    /** 두 잔 사이의 재무장·쿨다운 상태. */
    public static final class Pair {
        final long a, b;
        boolean soundArmed = true, countArmed = true;
        long lastSound = Long.MIN_VALUE / 2, lastCount = Long.MIN_VALUE / 2;

        Pair(long a, long b) {
            this.a = a;
            this.b = b;
        }
    }

    private JjanClinks() {}

    static long pairKey(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return lo * 1_000_003L ^ Long.rotateLeft(hi, 29);
    }

    /**
     * 한 틱. moving 은 이번 틱에 움직이는 잔(든 상태, 동작 없음)이고 dispX/dispZ 는 자리 번호별로 이번 틱에 가려는 변위다.
     * 판정 조건: 같은 테이블(호출자가 한 테이블의 세션만 넘긴다), 서로 다른 세션, 둘 다 가능한 상태, 적어도 하나는 들고 있음,
     * 수직 구간 겹침 + 수평 접촉, 다가오는 상대속도 ≥ 최소값, 소리·횟수 각각의 재접촉 거리와 쿨다운.
     */
    public static void step(JjanTables.Def def, List<JjanSession> all, List<JjanSession> moving, double[] dispX, double[] dispZ,
                            Map<Long, Pair> pairs, long now, Listener out) {
        List<JjanSession> clinkable = new ArrayList<>();
        for (JjanSession s : all) if (s != null && s.canClink()) clinkable.add(s);
        Map<Long, double[]> contact = new HashMap<>();
        for (int step = 0; step < SUBSTEPS; step++) {
            for (JjanSession s : moving) {
                double[] c = def.clampRegion(s.x + dispX[s.seat] / SUBSTEPS, s.z + dispZ[s.seat] / SUBSTEPS);
                s.x = c[0];
                s.z = c[1];
            }
            for (int i = 0; i < clinkable.size(); i++) {
                for (int j = i + 1; j < clinkable.size(); j++) {
                    JjanSession a = clinkable.get(i), b = clinkable.get(j);
                    if (a.pose != JjanSession.RAISED && b.pose != JjanSession.RAISED) continue;
                    double dx = b.x - a.x, dz = b.z - a.z, d = Math.hypot(dx, dz), rs = a.radius() + b.radius();
                    if (!overlapsVertically(a, b) || d >= rs) continue;
                    double nx = d > 1e-6 ? dx / d : 1, nz = d > 1e-6 ? dz / d : 0;
                    contact.putIfAbsent(pairKey(a.sessionId, b.sessionId), new double[]{nx, nz});
                    separate(def, a, b, nx, nz, rs - d);
                }
            }
        }
        for (JjanSession s : moving) {
            s.vx = dispX[s.seat] * 20;   // 의도한 속도 (밀려난 만큼은 빼지 않는다: 부딪힌 세기를 잰다)
            s.vz = dispZ[s.seat] * 20;
        }
        for (int i = 0; i < clinkable.size(); i++) {
            for (int j = i + 1; j < clinkable.size(); j++) {
                JjanSession a = clinkable.get(i), b = clinkable.get(j);
                long key = pairKey(a.sessionId, b.sessionId);
                Pair pr = pairs.computeIfAbsent(key, k -> new Pair(a.sessionId, b.sessionId));
                double d = Math.hypot(b.x - a.x, b.z - a.z), rs = a.radius() + b.radius();
                boolean vert = overlapsVertically(a, b);
                double[] n = contact.get(key);
                if (n == null) {
                    if (!vert || d > rs + JjanRules.SOUND_REARM_GAP) pr.soundArmed = true;
                    if (!vert || d > rs + JjanRules.COUNT_REARM_GAP) pr.countArmed = true;
                    continue;
                }
                double approach = -((b.vx - a.vx) * n[0] + (b.vz - a.vz) * n[1]);
                if (approach < JjanRules.MIN_APPROACH) continue;
                if (pr.soundArmed && now - pr.lastSound >= JjanRules.SOUND_COOLDOWN) {
                    pr.soundArmed = false;
                    pr.lastSound = now;
                    out.sound(a, b, JjanRules.clinkStrength(approach));
                }
                if (pr.countArmed && now - pr.lastCount >= JjanRules.COUNT_COOLDOWN) {
                    pr.countArmed = false;
                    pr.lastCount = now;
                    out.count(a, b);
                }
            }
        }
    }

    static boolean overlapsVertically(JjanSession a, JjanSession b) {
        return Math.max(a.low(), b.low()) < Math.min(a.high(), b.high());
    }

    /** 겹친 잔을 떼어 놓는다(든 잔만 움직인다). 플레이어 몸에는 영향 없음. */
    private static void separate(JjanTables.Def def, JjanSession a, JjanSession b, double nx, double nz, double overlap) {
        boolean ma = a.pose == JjanSession.RAISED, mb = b.pose == JjanSession.RAISED;
        double push = overlap + 0.004;
        if (ma && mb) push /= 2;
        if (ma) { double[] c = def.clampRegion(a.x - nx * push, a.z - nz * push); a.x = c[0]; a.z = c[1]; }
        if (mb) { double[] c = def.clampRegion(b.x + nx * push, b.z + nz * push); b.x = c[0]; b.z = c[1]; }
    }

    /**
     * 든 잔이 목표점(마우스로 옮긴 점)을 따라가는 최고 속도 (블록/초). 스프링이나 가속 구간 없이 목표점으로 곧장 가고,
     * 이 속도보다 느린 움직임은 한 프레임(서버는 한 틱) 안에 그대로 따라붙는다. 상한은 순간 이동과 잔 통과를 막기 위한 것이다.
     */
    public static final double FOLLOW_SPEED = 12;

    /**
     * pos = {x, z} 를 dt 초 동안 목표점(tx, tz)으로 옮긴다(제자리에서 고친다). 닿을 수 있으면 정확히 목표점에 놓는다.
     * 서버는 한 틱(1/20초)씩, 클라이언트는 자기 잔을 프레임마다 같은 식으로 움직여 미리 보여 준다.
     */
    public static void follow(double[] pos, double tx, double tz, double dt) {
        double dx = tx - pos[0], dz = tz - pos[1], d = Math.hypot(dx, dz), max = FOLLOW_SPEED * dt;
        if (d <= max) {
            pos[0] = tx;
            pos[1] = tz;
        } else {
            pos[0] += dx / d * max;
            pos[1] += dz / d * max;
        }
    }
}
