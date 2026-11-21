package dev.newyear.countdown.jjan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 짠 도메인 규칙: 카탈로그 수량·제공량, 한 모금·원샷, 취기, 짠 세기. 명세(MINECRAFT_PORT_SPEC)의 검증 수치를 그대로 쓴다. */
class JjanRulesTest {
    private static final JjanCatalog CAT = JjanCatalog.load();

    private static int capacity(String drink, String glass) {
        return CAT.glass(glass).capacity(CAT.drink(drink).look().fill());
    }

    @Test
    void catalogCounts() {
        assertEquals(64, CAT.drinks().size());
        assertEquals(7, CAT.categories().size());
        assertEquals(22, CAT.publicGlasses().size());
        assertEquals(23, CAT.glasses().size());
        assertTrue(CAT.glass("dropshot").hidden());
        assertFalse(CAT.phrases().isEmpty());
        int inCategories = 0;
        for (JjanCatalog.Category c : CAT.categories()) inCategories += CAT.drinksIn(c.id()).size();
        assertEquals(64, inCategories, "모든 술이 분류 하나에 들어 있다");
        for (JjanCatalog.Drink d : CAT.drinks()) {
            JjanCatalog.Glass g = CAT.glass(d.glass());
            assertNotNull(g, d.id() + " 의 추천 잔");
            assertFalse(g.hidden(), d.id() + " 의 추천 잔은 메뉴에 보이는 잔");
            assertEquals(d, CAT.drinks().get(d.index()));
            for (JjanCatalog.Variant v : d.variants()) assertEquals(v, d.variants().get(v.index()));
        }
        for (JjanCatalog.Glass g : CAT.glasses()) {
            assertEquals(g, CAT.glasses().get(g.index()));
            assertTrue(g.voxels().length > 0 && g.voxels().length % 4 == 0);
            assertTrue(g.contactR() > 0);
        }
    }

    @Test
    void servingCapacitiesMatchTheOriginal() {
        assertEquals(264, capacity("lager", "pint"));
        assertEquals(52, capacity("soju", "soju"));
        assertEquals(104, capacity("red", "wine"));
        assertEquals(228, capacity("makgeolli", "bowl"));
        assertEquals(224, capacity("highball", "highball"));
        assertEquals(208, capacity("cola", "highball"));
        assertEquals(5.0, CAT.drink("lager").look().abv());
        assertEquals(16.5, CAT.drink("soju").look().abv());
        assertEquals(0.0, CAT.drink("cola").look().abv());
    }

    @Test
    void sipSequenceEmptiesInThree() {
        double f = 1.0;
        f = JjanRules.afterSip(f, false);
        assertEquals(0.66, f, 1e-9);
        f = JjanRules.afterSip(f, false);
        assertEquals(0.32, f, 1e-9);
        f = JjanRules.afterSip(f, false);
        assertEquals(0.0, f, 1e-9);
        assertTrue(JjanRules.isEmpty(f));
        assertFalse(JjanRules.isEmpty(0.32));
    }

    @Test
    void shotRule() {
        assertTrue(JjanRules.sipEmpties(false, "soju"));
        assertTrue(JjanRules.sipEmpties(false, "shot"));
        assertTrue(JjanRules.sipEmpties(true, "pint"));
        assertFalse(JjanRules.sipEmpties(false, "pint"));
        assertEquals(0.0, JjanRules.afterSip(1.0, true));
        assertEquals(0.0, JjanRules.afterSip(0.5, true));
    }

    @Test
    void lagerFirstSip() {
        int cap = capacity("lager", "pint");
        double ml = JjanRules.consumedMl(1.0, JjanRules.afterSip(1.0, false), cap);
        assertEquals(89.76, ml, 1e-6);
        double alcohol = JjanRules.alcoholMl(ml, 5);
        assertEquals(4.488, alcohol, 1e-6);
        assertEquals(0.0748, JjanRules.addIntox(0, alcohol), 1e-6);
    }

    @Test
    void softDrinksAndRefillsAddNothing() {
        double ml = JjanRules.consumedMl(1.0, 0.0, capacity("cola", "highball"));
        assertEquals(0.0, JjanRules.addIntox(0.3, JjanRules.alcoholMl(ml, 0)) - 0.3, 1e-12);
        assertEquals(0.0, JjanRules.consumedMl(0.2, 1.0, 264), "채우기는 마신 양이 아니다");
    }

    @Test
    void intoxCapDecayAndDisplay() {
        assertEquals(JjanRules.INTOX_MAX, JjanRules.addIntox(1.5, 1000));
        assertEquals(1.0 - 0.0035 * 60, JjanRules.decay(1.0, 60), 1e-9);
        assertEquals(0.0, JjanRules.decay(0.1, 100000));
        assertEquals(0.7, JjanRules.decay(0.7, -500), "시계가 뒤로 가면 깎지 않는다");
        assertEquals(0.06, JjanRules.approachDisplayed(0, 1, 1), 1e-9);
        assertEquals(0.88, JjanRules.approachDisplayed(1, 0, 1), 1e-9);
        assertEquals(0.5, JjanRules.approachDisplayed(0.49, 0.5, 1), 1e-9);
        assertEquals(0.5, JjanRules.approachDisplayed(0.55, 0.5, 1), 1e-9);
    }

    @Test
    void nauseaFollowsIntox() {
        assertEquals(0.0, JjanRules.nausea(0.0, false));
        assertEquals(0.0, JjanRules.nausea(0.1, false));
        assertEquals(1.0, JjanRules.nausea(1.4, false), 1e-9);
        assertEquals(1.0, JjanRules.nausea(2.0, false), 1e-9);
        double prev = 0;
        for (double d = 0.15; d <= 1.4; d += 0.05) {
            double n = JjanRules.nausea(d, false);
            assertTrue(n > prev, "more drunk, more dizzy: " + d);
            assertEquals(n * 0.4, JjanRules.nausea(d, true), 1e-9);
            prev = n;
        }
    }

    @Test
    void dragMovesTheGlassTheSameDistanceEverywhere() {
        double g = JjanRules.DRAG_PER_DEGREE;
        // 남쪽(+Z)을 볼 때: 오른쪽은 -X, 마우스를 위로(pitch 음수) 밀면 멀리(+Z)
        assertArrayEquals(new double[]{-10 * g, 0}, JjanRules.drag(10, 0, 0), 1e-12);
        assertArrayEquals(new double[]{0, 10 * g}, JjanRules.drag(0, -10, 0), 1e-12);
        // 서쪽(-X, yaw 90)을 볼 때: 오른쪽은 북쪽(-Z), 위는 -X
        assertArrayEquals(new double[]{0, -10 * g}, JjanRules.drag(10, 0, 90), 1e-12);
        assertArrayEquals(new double[]{-10 * g, 0}, JjanRules.drag(0, -10, 90), 1e-12);
        for (double yaw = -180; yaw < 180; yaw += 37) {
            double[] d = JjanRules.drag(3, 4, yaw);
            assertEquals(5 * g, Math.hypot(d[0], d[1]), 1e-12, "어느 쪽을 보든 같은 거리");
        }
    }

    @Test
    void stages() {
        assertEquals(0, JjanRules.stage(0.0));
        assertEquals(0, JjanRules.stage(0.0999));
        assertEquals(1, JjanRules.stage(0.1));
        assertEquals(2, JjanRules.stage(0.3));
        assertEquals(3, JjanRules.stage(0.55));
        assertEquals(4, JjanRules.stage(0.8));
        assertEquals(4, JjanRules.stage(1.6));
        assertEquals(5, JjanRules.STAGE_KEYS.length);
    }

    @Test
    void clinkStrength() {
        assertEquals(0.05, JjanRules.clinkStrength(0.10), 1e-9);
        assertEquals((1.0 - 0.06) / 1.70, JjanRules.clinkStrength(1.0), 1e-9);
        assertEquals(1.0, JjanRules.clinkStrength(5.0));
        assertTrue(JjanRules.SOUND_COOLDOWN < JjanRules.COUNT_COOLDOWN);
        assertTrue(JjanRules.SOUND_REARM_GAP < JjanRules.COUNT_REARM_GAP);
    }

    @Test
    void durations() {
        assertEquals(31, JjanRules.sipTicks());
        assertEquals(51, JjanRules.chugTicks(1.0));
        assertTrue(JjanRules.chugTicks(0.3) < JjanRules.chugTicks(1.0));
    }
}
