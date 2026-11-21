package dev.newyear.countdown.jjan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 술 만드는 시간표: 웹 원작(glass.js startPour)의 순서와 길이. */
class JjanPourTest {
    private static final JjanCatalog CAT = JjanCatalog.load();

    private static JjanPour fresh(String id) {
        return JjanPour.of(CAT, CAT.drink(id).look(), true, 0);
    }

    @Test
    void plainBottleIsAppearLiftPourAndPutBack() {
        JjanPour p = fresh("lager");
        assertEquals(1, p.steps.size());
        JjanPour.Step s = p.steps.get(0);
        assertEquals("", s.kind);
        assertEquals(0.3, s.t0, 1e-9, "나타나자마자 들어 올린다");
        assertEquals(0.3 + 0.42, s.pour0, 1e-9);
        assertEquals(1.45, s.pour1 - s.pour0, 1e-9, "가득 따르는 데 1.45초");
        assertEquals(0.3 + 0.42 + 1.45 + 0.6, p.total, 1e-9);
        assertEquals(56, p.ticks());
        assertEquals(0, p.fill(0.5), 1e-9);
        assertEquals(0.5, p.fill(s.pour0 + 0.725), 1e-9);
        assertEquals(1, p.fill(p.total), 1e-9);
    }

    @Test
    void canAndCorkAreOpenedOnlyOnce() {
        JjanPour cola = fresh("cola");
        assertEquals("can", cola.steps.get(0).kind);
        assertEquals(0.3 + 0.6, cola.steps.get(0).t0, 1e-9, "캔 따는 0.6초");
        JjanPour again = JjanPour.of(CAT, CAT.drink("cola").look(), false, 0.3);
        assertEquals("", again.steps.get(0).kind, "리필할 땐 이미 딴 캔");
        assertEquals(Math.max(0.55, 1.45 * 0.7), again.steps.get(0).pour1 - again.steps.get(0).pour0, 1e-9);
        assertEquals(0.3, again.fill(0), 1e-9);
        assertEquals("swirl", fresh("soju").steps.get(0).kind, "초록 소주병은 휘휘 돌려 바닥을 툭");
    }

    @Test
    void shakerTakesEveryIngredientThenIceCapShakeAndUncap() {
        JjanCatalog.Drink d = null;
        for (JjanCatalog.Drink x : CAT.drinks()) {
            if (x.look().pour().size() == 1 && x.look().pour().get(0).prep().equals("shake")) { d = x; break; }
        }
        assertNotNull(d, "셰이커로만 만드는 칵테일");
        JjanPour p = JjanPour.of(CAT, d.look(), true, 0);
        JjanPour.Step s = p.steps.get(0);
        int n = s.def.build().size();
        assertTrue(n > 0);
        assertEquals(n + 4, s.phases.size(), "재료 n 개 + 얼음 + 뚜껑 + 흔들기 + 마개 빼기");
        double prep = 0;
        for (JjanCatalog.Ingredient ing : s.def.build()) prep += ing.dash() ? 0.8 : 1.1;
        prep += 0.75 + 0.45 + 1.15 + 0.45;
        assertEquals(0.3 + prep, s.t0, 1e-9);
        assertEquals("ing", s.phases.get(0).name());
        assertEquals("act", s.phase("act").name());
        assertSame(s.phase("ice"), s.current(s.phase("ice").t0() + 0.1));
        assertTrue(JjanPour.of(CAT, d.look(), false, 0.5).steps.get(0).mixing, "리필해도 다시 섞는다");
    }

    @Test
    void layeredShotIsPouredInThreeSlowLayers() {
        JjanPour p = fresh("b52");
        assertEquals(3, p.steps.size());
        assertEquals(0.33, p.steps.get(0).to, 1e-9);
        assertEquals(0.66, p.steps.get(1).to, 1e-9);
        assertEquals(1.0, p.steps.get(2).to, 1e-9);
        for (JjanPour.Step s : p.steps) {
            assertTrue(s.def.layer());
            assertFalse(s.tinted, "층을 쌓는 술은 색을 섞지 않는다");
            assertEquals(Math.max(0.55, 1.45 * (s.to - s.from)) * 1.7, s.pour1 - s.pour0, 1e-9, "층은 1.7배 천천히");
        }
        assertEquals(p.steps.get(0).end, p.steps.get(1).start, 1e-9);
        assertEquals(0.33, p.fill(p.steps.get(1).start + 0.1), 1e-9);
        assertSame(p.steps.get(2), p.at(p.total + 5));
    }

    @Test
    void mixedPoursTintTheGlassUntilTheLastOne() {
        JjanPour p = fresh("somaek");
        JjanPour.Step soju = p.steps.get(0), beer = p.steps.get(1);
        assertEquals(0.3, soju.to, 1e-9);
        assertTrue(soju.tinted);
        assertEquals(soju.def.bottle().stream(), soju.tintAfter, "소주만 따른 동안은 소주 색");
        assertEquals(soju.tintAfter, beer.tintBefore);
        assertEquals(CAT.drink("somaek").look().color(), beer.tintTarget, "맥주를 따르며 소맥 색으로");
        assertEquals(-1, beer.tintAfter, "다 따르면 원래 색");
        assertTrue(beer.carb > 0);
    }

    @Test
    void everyDrinkHasAFiniteTimetable() {
        for (JjanCatalog.Drink d : CAT.drinks()) {
            for (int v = -1; v < d.variants().size(); v++) {
                JjanCatalog.Look look = v < 0 ? d.look() : d.variants().get(v).look();
                JjanPour p = JjanPour.of(CAT, look, true, 0);
                assertTrue(p.total > 2 && p.total < 40, d.id() + " 준비 시간 " + p.total);
                assertEquals(1, p.fill(p.total), 1e-9, d.id());
                double last = 0;
                for (double t = 0; t <= p.total; t += 0.05) {
                    assertTrue(p.fill(t) >= last - 1e-9, d.id() + ": 잔량은 줄지 않는다");
                    last = p.fill(t);
                }
                assertTrue(JjanPour.of(CAT, look, false, 0.4).total <= p.total + 1e-9, d.id() + ": 리필이 더 길지 않다");
            }
        }
    }

    @Test
    void afterEffects() {
        JjanCatalog.Look somaek = CAT.drink("somaek").look(), red = CAT.drink("red").look(), lager = CAT.drink("lager").look();
        assertArrayEquals(new double[]{1, 2.8}, JjanPour.tornado(somaek));
        assertArrayEquals(new double[]{0.45, 2.0}, JjanPour.tornado(red));
        assertNull(JjanPour.tornado(lager));
        assertEquals(1, JjanPour.swirl(somaek, 1.0), 1e-9);
        assertEquals(0, JjanPour.swirl(somaek, 3.0), 1e-9);
        assertEquals(0, JjanPour.swirl(lager, 1.0), 1e-9);
        assertTrue(JjanPour.swirl(CAT.drink("oldfashioned").look(), 1.0) > 0.29, "잔 안을 젓는다");
        assertTrue(JjanPour.drops("sopok", CAT.drink("sopok").look()));
        assertFalse(JjanPour.drops("gojingamrae", CAT.drink("gojingamrae").look()), "고진감래는 처음부터 샷잔이 들어 있다");
        assertEquals(42, JjanPour.strawTicks());
        assertEquals(1, JjanPour.strawLeft(0.2), 1e-9);
        assertEquals(0.5, JjanPour.strawLeft(0.4 + 0.525), 1e-9);
        assertEquals(0, JjanPour.strawLeft(2.0), 1e-9);
    }
}
