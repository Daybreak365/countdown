package dev.newyear.countdown.client;

import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanPour;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 술 따르는 그릇의 자세: 잔 옆에 서 있다가, 따를 땐 입구가 잔 위에 오고, 그릇이 잔을 긁지 않는다. */
class JjanPourFxTest {
    private static final JjanCatalog CAT = JjanCatalog.load();

    @Test
    void everyVesselRestsBesideTheGlassAndPoursAboveIt() {
        for (JjanCatalog.Drink d : CAT.drinks()) {
            JjanCatalog.Glass glass = CAT.glass(d.glass());
            JjanPour plan = JjanPour.of(CAT, d.look(), true, 0);
            for (JjanPour.Step st : plan.steps) {
                JjanCatalog.Vessel v = CAT.vessels().get(st.def.bottle().vessel());
                float[] sp = JjanPourFx.spots(glass, v, st);
                String where = d.id() + "/" + v.kind();
                // 쉬는 자리: 그릇 오른쪽 끝이 잔 입구 바깥, 밑동이 탁자 위
                assertTrue(sp[0] + v.maxX() <= -glass.rimR() - 1.5, where + " 쉬는 자리가 잔과 겹친다");
                assertTrue(sp[1] + v.minY() >= -1e-6, where + " 밑동이 탁자 아래");
                float[] rest = JjanPourFx.pose(glass, v, st, 0.1, 0f);
                assertArrayEquals(new float[]{sp[0], sp[1], sp[2], 0, 0, 0}, rest, 1e-5f, where + " 처음엔 가만히 서 있다");
                // 따르는 중: 입구가 잔 입구 위, 잔 안쪽, 정해진 각도로 기운다
                float[] pour = JjanPourFx.pose(glass, v, st, (st.pour0 + st.pour1) / 2, 0f);
                assertEquals(sp[3], pour[0], 1e-3f, where);
                assertEquals(sp[4], pour[1], 1e-3f, where);
                assertEquals(0f, pour[2], 1e-3f, where);
                assertTrue(pour[1] > glass.height() + 3, where + " 입구가 잔보다 위");
                assertTrue(Math.abs(pour[0]) < glass.rimR(), where + " 입구가 잔 안쪽");
                assertEquals(-v.angle() * (st.def.layer() ? 0.85 : 1), pour[5], 1e-3, where);
                // 다 따르면 제자리
                float[] end = JjanPourFx.pose(glass, v, st, st.end - 0.01, 0f);
                assertEquals(sp[0], end[0], 0.05f, where);
                assertEquals(sp[1], end[1], 0.05f, where);
                // 준비 동작 내내 그릇의 가장 낮은 점이 탁자를 뚫지 않는다
                for (double t = 0; t < st.t0; t += 0.02) {
                    float[] p = JjanPourFx.pose(glass, v, st, t, (float) t);
                    for (float f : p) assertTrue(Float.isFinite(f), where);
                    // 기울어진 그릇의 가장 낮은 모서리 (입구가 원점, z 축 회전)
                    double low = 1e9;
                    for (double x : new double[]{v.minX(), v.maxX()}) {
                        for (double y : new double[]{v.minY(), v.maxY()}) low = Math.min(low, p[1] + x * Math.sin(p[5]) + y * Math.cos(p[5]));
                    }
                    assertTrue(low > -3, where + " 준비 동작 중 탁자 아래로 " + low + " (t=" + t + ")");
                }
            }
        }
    }
}
