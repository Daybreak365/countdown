package dev.newyear.countdown.client;

import dev.newyear.countdown.jjan.Jjan;
import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanLayout;
import dev.newyear.countdown.jjan.JjanPackets;
import dev.newyear.countdown.jjan.JjanPour;
import dev.newyear.countdown.jjan.JjanRules;
import dev.newyear.countdown.jjan.JjanSession;
import dev.newyear.countdown.jjan.JjanTableEntity;
import dev.newyear.countdown.jjan.JjanTables;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 짠의 연출 상태와 소리·입자 (클라이언트). 자리마다 "지금 만드는 중인 술의 시간표"와 "다 따른 시각"을 기억해 두고,
 * 렌더러는 이를 보고 그리며, 매 틱 시간표에 맞춰 소리(따르기·얼음·셰이커·코르크·캔 …)와 입자(코르크 김, 풍덩, 불꽃)를 낸다.
 * 불붙은 술(B-52)은 마인크래프트의 작은 불꽃 입자와 부싯돌 소리로 붙고, 빨대로 다 마시면 치익 하고 연기를 내며 꺼진다.
 */
final class JjanFx {
    static final class State {
        int drink = -1, variant = -1, glass = -1;
        long pourStart = Long.MIN_VALUE;
        /** 만드는 중이거나 마지막으로 만든 술의 시간표. 본 적이 없으면 null (늦게 들어온 경우: 다 된 모습으로 그린다). */
        JjanPour plan;
        boolean fresh;
        /** 다 따른 시각(게임 틱). */
        long servedTick = Long.MIN_VALUE;
        /** 따르는 동안 장식을 숨겼는지 (다 따르면 톡 얹는다). */
        boolean hidGarnish;
        boolean flame;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static long lastTick = Long.MIN_VALUE;
    private static final float S = (float) JjanRules.GLASS_SCALE;

    private JjanFx() {}

    static void clear() {
        STATES.clear();
        lastTick = Long.MIN_VALUE;
    }

    static JjanCatalog.Look look(JjanCatalog cat, JjanPackets.SeatSnap s) {
        if (s.drink() < 0 || s.drink() >= cat.drinks().size()) return null;
        JjanCatalog.Drink d = cat.drinks().get(s.drink());
        return s.variant() >= 0 && s.variant() < d.variants().size() ? d.variants().get(s.variant()).look() : d.look();
    }

    static boolean pouring(JjanPackets.SeatSnap s) {
        return s.action() == JjanSession.PREPARING || s.action() == JjanSession.REFILLING;
    }

    static boolean drinking(JjanPackets.SeatSnap s) {
        return s.action() == JjanSession.SIPPING || s.action() == JjanSession.CHUGGING;
    }

    /** 불붙은 술을 빨대로 마시는 중인지 (잔은 들지 않는다). */
    static boolean straw(JjanPackets.SeatSnap s) {
        if (!drinking(s)) return false;
        JjanCatalog.Look look = look(JjanCatalog.get(), s);
        return look != null && look.flame();
    }

    /** 이 자리의 연출 상태. 스냅숏을 보고 시간표를 갱신한다. */
    static State state(JjanCatalog cat, JjanPackets.SeatSnap s, JjanCatalog.Look look) {
        State st = STATES.computeIfAbsent(s.player(), k -> new State());
        boolean same = st.drink == s.drink() && st.variant == s.variant() && st.glass == s.glass();
        if (pouring(s)) {
            if (!same || st.pourStart != s.actionStart() || st.plan == null) {
                st.drink = s.drink();
                st.variant = s.variant();
                st.glass = s.glass();
                st.pourStart = s.actionStart();
                st.fresh = s.action() == JjanSession.PREPARING;
                st.plan = JjanPour.of(cat, look, st.fresh, st.fresh ? 0 : s.fillFrom());
                st.servedTick = s.actionStart() + s.actionLen();
                st.hidGarnish = st.fresh || s.fillFrom() <= JjanRules.EMPTY;
                st.flame = false;
            }
        } else if (!same) {
            st.drink = s.drink();
            st.variant = s.variant();
            st.glass = s.glass();
            st.plan = null;
            st.servedTick = Long.MIN_VALUE;
            st.hidGarnish = false;
        }
        return st;
    }

    /** 다 따른 뒤 흐른 시간(초). 따르는 중이면 음수, 따르는 걸 본 적이 없으면 아주 큰 값. */
    static double served(State st, double time) {
        return st.servedTick == Long.MIN_VALUE ? 1e9 : (time - st.servedTick) / 20.0;
    }

    /** 따르는 동안 잔 속 술 색을 덮는 색 (여러 번 나눠 따를 때의 중간 색). -1 이면 원래 색. */
    static int tint(JjanPour plan, double time) {
        JjanPour.Step st = plan.at(time);
        double t = time - st.start;
        if (!st.tinted || t <= st.pour0) return st.tintBefore;
        if (t >= st.end) return st.tintAfter;
        if (t >= st.pour1) return st.tintTarget;
        return JjanGlassMesh.mixLinear(st.tintFrom & 0xFFFFFF, st.tintTarget & 0xFFFFFF, JjanPour.smooth((t - st.pour0) / (st.pour1 - st.pour0)));
    }

    /** 그레나딘처럼 무거운 시럽이 바닥부터 깔리며 위로 번지는 색 띠 (원작 refreshLUT). */
    static int[] sinkLut(int[] base, int from, double rel) {
        double k = JjanPour.smooth(rel / 2.6);
        int[] out = new int[17];
        for (int n = 0; n <= 16; n++) {
            double w = JjanPour.smooth((k * 1.5 - n / 16.0) / 0.5);
            out[n] = JjanGlassMesh.mixLinear(from & 0xFFFFFF, base[n], w);
        }
        return out;
    }

    /** 폭탄주의 샷잔: 다 따른 뒤 rel 초의 {높이, 기울기}. 위에서 떨어져 풍덩 하고 가라앉아 바닥에서 멈춘다. */
    static float[] dropShot(double rel, float startY, float level, float restY) {
        if (rel >= JjanPour.DROP_TIME) return new float[]{restY, 0};
        double hit = JjanPour.dropFall(startY - (level - 0.5f));
        if (rel < hit) return new float[]{(float) (startY - 130 * rel * rel), 0};
        double y = level - 0.5, vy = -260 * hit * 0.25, dt = 1 / 240.0;
        for (double t = hit; t < rel; t += dt) {
            vy += (-(y - restY) * 40 - vy * 7) * dt;
            y += vy * dt;
            if (y < restY) { y = restY; vy = 0; }
        }
        return new float[]{(float) y, (float) (Math.sin(rel * 9) * 0.08 * Math.exp(-rel * 2))};
    }

    /** 탄산 있는 맥주를 따르거나, 샷잔이 빠지거나, 회오리를 돌리면 거품이 잠깐 더 올라온다 (원작 foamBoost). */
    static double foamBoost(State st, JjanCatalog.Look look, double time, double rel, double dropHit) {
        if (look.foamHeight() <= 0 || st.plan == null) return 0;
        double now = rel < 0 ? time : st.plan.total + rel, boost = 0;
        for (JjanPour.Step s : st.plan.steps) {
            if (s.carb <= 0) continue;
            double t = now - s.start;
            if (t <= s.pour0) continue;
            double b = Math.min(2.6, 2.4 * (Math.min(t, s.pour1) - s.pour0));
            if (t > s.pour1) b *= Math.exp(-0.55 * (t - s.pour1));
            boost = Math.max(boost, b);
        }
        if (rel >= 0) {
            if (dropHit >= 0 && rel >= dropHit) boost = Math.max(boost, 3 * Math.exp(-0.55 * (rel - dropHit)));
            double[] tor = JjanPour.tornado(look);
            if (tor != null) boost = Math.max(boost, Math.min(3, 1.4 * tor[0] * Math.min(rel, tor[1])) * Math.exp(-0.55 * Math.max(0, rel - tor[1])));
        }
        return boost;
    }

    /** 탄산 방울이 평소의 몇 배로 이는지: 탄산을 따르는 동안, 샷잔이 빠진 직후, 회오리가 도는 동안. */
    static float bubbleBoost(State st, JjanCatalog.Look look, double time, double rel, double dropHit, double swirl) {
        double boost = 1;
        if (rel < 0 && st.plan != null) {
            JjanPour.Step s = st.plan.at(time);
            double t = time - s.start;
            if (s.carb > 0 && t > s.pour0) boost = 1 + 1.8 * Math.min(1, (t - s.pour0) / 0.4) * Math.exp(-3 * Math.max(0, t - s.pour1));
        } else if (rel >= 0) {
            if (dropHit >= 0 && rel >= dropHit) boost = Math.max(boost, 1 + 5 * Math.exp(-2.5 * (rel - dropHit)));
            boost = Math.max(boost, 1 + 5 * swirl);
        }
        return (float) boost;
    }

    // ------------------------------------------------------------------ 소리·입자

    private record Spot(ClientLevel level, JjanTableEntity be, JjanPackets.SeatSnap s, float dx, float dz) {
        /** 잔 좌표(복셀, +Z 가 앉은 사람 쪽) → 월드. */
        Vec3 at(double ox, double oy, double oz) {
            double lx = s.x() + (ox * -dz + oz * -dx) * S, lz = s.z() + (ox * dx + oz * -dz) * S;
            return JjanLayout.toWorld(be.getBlockPos(), be.facing(), lx, s.y() + oy * S, lz);
        }

        void sound(SoundEvent e, float volume, float pitch, double ox, double oy, double oz) {
            Vec3 p = at(ox, oy, oz);
            level.playLocalSound(p.x, p.y, p.z, e, SoundSource.PLAYERS, volume, pitch, false);
        }

        void particle(ParticleOptions type, double ox, double oy, double oz, double vx, double vy, double vz) {
            Vec3 p = at(ox, oy, oz);
            level.addParticle(type, p.x, p.y, p.z, vx, vy, vz);
        }
    }

    /** 이번 틱 구간 (a, b] 에 시각 t 가 들어 있는지. */
    private static boolean hit(double a, double b, double t) {
        return t > a && t <= b;
    }

    static void tick(Minecraft mc, Collection<JjanClient.Seated> seated) {
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        long now = level.getGameTime();
        long prev = lastTick == Long.MIN_VALUE || now - lastTick > 5 || now < lastTick ? now - 1 : lastTick;
        lastTick = now;
        if (now == prev) return;
        JjanCatalog cat = JjanCatalog.get();
        RandomSource rnd = level.getRandom();
        for (JjanClient.Seated seat : seated) {
            JjanPackets.SeatSnap s = seat.snap();
            JjanCatalog.Look look = look(cat, s);
            if (look == null || s.glass() < 0 || s.glass() >= cat.glasses().size()) continue;
            if (!(level.getBlockEntity(seat.pos()) instanceof JjanTableEntity be)) continue;
            JjanTables.Def def = be.def();
            if (s.seat() < 0 || s.seat() >= def.seats.size()) continue;
            JjanTables.Seat sd = def.seats.get(s.seat());
            float dx = (float) (sd.homeX() - sd.chairX()), dz = (float) (sd.homeZ() - sd.chairZ());
            float len = Mth.sqrt(dx * dx + dz * dz);
            if (len < 1e-4f) { dx = 0; dz = -1; } else { dx /= len; dz /= len; }
            Spot spot = new Spot(level, be, s, dx, dz);
            JjanCatalog.Glass glass = cat.glasses().get(s.glass());
            JjanCatalog.Drink drink = cat.drinks().get(s.drink());
            State st = state(cat, s, look);
            int cs = glass.cavityStart(), H = glass.height();

            if (pouring(s) && st.plan != null) {
                double a = (prev - s.actionStart()) / 20.0, b = (now - s.actionStart()) / 20.0;
                pourSounds(cat, spot, glass, look, st, a, b, now, rnd);
            }
            double ra = served(st, prev), rb = served(st, now);
            float level1 = (float) glass.level(look.fill(), 1.0);
            if (st.servedTick != Long.MIN_VALUE && rb >= 0 && rb < 6 && !pouring(s)) {
                if (hit(ra, rb, 0)) {
                    // 장식을 톡 얹는다
                    if (st.hidGarnish && look.garnish().stream().anyMatch(g -> !g.equals("salt"))) spot.sound(SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.25f, 1.7f, 0, H, 0);
                    if (look.bomb().equals("tornado")) spot.sound(Jjan.FIZZ, 0.7f, 1.0f, 0, level1, 0);
                    else if (!look.bomb().isEmpty() && !look.bomb().equals("drop") && !look.bomb().equals("stir")) spot.sound(Jjan.FIZZ, 0.45f, 1.1f, 0, level1, 0);
                }
                if (JjanPour.drops(drink.id(), look)) {
                    double fall = JjanPour.dropFall(H + 14 - (level1 - 0.5));
                    if (hit(ra, rb, fall)) {
                        // 풍덩
                        spot.sound(SoundEvents.FISHING_BOBBER_SPLASH, 0.35f, 1.3f, 0, level1, 0);
                        spot.sound(Jjan.FIZZ, 0.7f, 1.0f, 0, level1, 0);
                        for (int i = 0; i < 8; i++) {
                            double ang = rnd.nextDouble() * Math.PI * 2, r = glass.rimR() * (0.3 + 0.6 * rnd.nextDouble());
                            spot.particle(ParticleTypes.SPLASH, Math.cos(ang) * r, level1 + 0.5, Math.sin(ang) * r, 0, 0, 0);
                        }
                    }
                }
                // 잔 안을 바 스푼으로 젓는 소리 (올드 패션드, 모히토)
                if (look.after().equals("stir") && rb > 0.25 && rb < 1.6 && now % 6 == 0) {
                    spot.sound(SoundEvents.AMETHYST_BLOCK_STEP, 0.12f, 1.5f + rnd.nextFloat() * 0.3f, 0, level1, 0);
                }
            }

            // 빨대로 쭉 빨아 마시는 소리
            if (drinking(s) && look.flame() && hit((prev - s.actionStart()) / 20.0, (now - s.actionStart()) / 20.0, 0.42)) {
                spot.sound(SoundEvents.GENERIC_DRINK.value(), 0.5f, 1.35f, 0, H, 0);
            }
            // 불: 다 따르면 붙고, 빨대로 다 마시면 꺼진다
            double shown = s.fill();
            if (drinking(s)) shown = look.flame() ? s.fillFrom() * JjanPour.strawLeft((now - s.actionStart()) / 20.0) : s.fillFrom();
            boolean flame = look.flame() && !pouring(s) && shown > 0.03;
            float lv = (float) glass.level(look.fill(), shown);
            float cr = (float) glass.cavityRadius(Mth.clamp(Mth.floor(lv), cs, H - 1)) * 0.8f;
            if (flame != st.flame) {
                st.flame = flame;
                if (flame && st.servedTick != Long.MIN_VALUE && Math.abs(rb) < 0.5) {
                    // 부싯돌로 붙인다: 찰칵 소리와 함께 불꽃이 확 인다
                    spot.sound(SoundEvents.FLINTANDSTEEL_USE, 0.6f, 1.1f + rnd.nextFloat() * 0.2f, 0, lv, 0);
                    for (int i = 0; i < 6; i++) {
                        double ang = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * cr;
                        spot.particle(ParticleTypes.SMALL_FLAME, Math.cos(ang) * r, lv + 0.6, Math.sin(ang) * r, 0, 0.012 + rnd.nextDouble() * 0.01, 0);
                    }
                    spot.particle(ParticleTypes.LAVA, 0, lv + 0.5, 0, 0, 0, 0);
                } else if (!flame && drinking(s)) {
                    spot.sound(SoundEvents.FIRE_EXTINGUISH, 0.2f, 1.9f, 0, lv, 0);
                    for (int i = 0; i < 5; i++) {
                        spot.particle(ParticleTypes.SMOKE, (rnd.nextDouble() - 0.5) * cr * 2, lv + 0.8, (rnd.nextDouble() - 0.5) * cr * 2, 0, 0.02, 0);
                    }
                }
            }
            if (flame) {
                // 양초처럼 작은 불꽃이 술 위에서 일렁인다. 가끔 연기 한 줄
                if (now % 2 == 0) {
                    double ang = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * cr;
                    spot.particle(ParticleTypes.SMALL_FLAME, Math.cos(ang) * r, lv + 0.7, Math.sin(ang) * r, 0, 0.004 + rnd.nextDouble() * 0.006, 0);
                }
                if (rnd.nextInt(24) == 0) spot.particle(ParticleTypes.SMOKE, 0, lv + 2.2, 0, 0, 0.01, 0);
            }
        }
        if (STATES.size() > 256) STATES.clear();
    }

    private static void pourSounds(JjanCatalog cat, Spot spot, JjanCatalog.Glass glass, JjanCatalog.Look look, State state,
                                   double a, double b, long now, RandomSource rnd) {
        int H = glass.height(), cs = glass.cavityStart();
        // 잔에 얼음이 하나씩 떨어진다 (새 잔만)
        if (state.fresh) {
            float size = look.iceSphere() ? 4.9f : glass.cavityRadius(cs) >= 3 ? 2.6f : 2f;
            for (int i = 0; i < look.ice(); i++) {
                double land = 0.12 + i * 0.17 + Math.sqrt(Math.max(0, H + 6 + i * 2.5 - (cs + size / 2)) / 160.0);
                if (hit(a, b, land)) spot.sound(SoundEvents.GLASS_HIT, 0.35f, 1.6f + rnd.nextFloat() * 0.3f, 0, cs + 1, 0);
            }
        }
        for (JjanPour.Step st : state.plan.steps) {
            double la = a - st.start, lb = b - st.start;
            if (lb <= 0 || la > st.end) continue;
            JjanCatalog.Vessel v = cat.vessels().get(st.def.bottle().vessel());
            float[] sp = JjanPourFx.spots(glass, v, st);
            float[] pose = JjanPourFx.pose(glass, v, st, Math.min(lb, st.end), now / 20f);
            for (JjanPour.Phase ph : st.phases) {
                double p0 = JjanPour.APPEAR + ph.t0();
                switch (ph.name()) {
                    case "ing" -> {
                        boolean dash = st.def.build().get(ph.ing()).dash();
                        if (hit(la, lb, p0 + 0.38 * ph.dur())) spot.sound(Jjan.POUR, dash ? 0.2f : 0.35f, dash ? 1.7f : 1.35f, sp[0], sp[1], sp[2]);
                    }
                    case "ice" -> {
                        for (int k = 0; k < 4; k++) {
                            if (hit(la, lb, p0 + (k * 0.16 + 0.38) * ph.dur())) spot.sound(SoundEvents.GLASS_HIT, 0.3f, 1.7f + rnd.nextFloat() * 0.3f, sp[0], sp[1], sp[2]);
                        }
                    }
                    case "cap" -> {
                        if (hit(la, lb, p0 + ph.dur())) spot.sound(SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.3f, 1.3f, sp[0], sp[1], sp[2]);
                    }
                    case "act" -> act(spot, st, ph, la, lb, p0, pose, now, rnd, look);
                    default -> { }
                }
            }
            if (hit(la, lb, st.pour0)) {
                float dur = (float) (st.pour1 - st.pour0);
                spot.sound(Jjan.POUR, 0.7f, Mth.clamp(1.25f - dur * 0.15f, 0.85f, 1.25f), 0, H, 0);
                if (st.carb > 0) spot.sound(Jjan.FIZZ, 0.3f + 0.25f * (float) st.carb, 1.0f, 0, H, 0);
            }
            // 뜨거운 걸 따르면 주둥이에서도 김이 난다
            if (st.hot && lb > st.pour0 && lb < st.pour1 && now % 3 == 0) {
                spot.particle(ParticleTypes.WHITE_SMOKE, pose[0], pose[1] + 0.5, pose[2], 0, 0.015, 0);
            }
        }
    }

    private static void act(Spot spot, JjanPour.Step st, JjanPour.Phase ph, double la, double lb, double p0, float[] pose, long now,
                            RandomSource rnd, JjanCatalog.Look look) {
        double u = (lb - p0) / ph.dur();
        boolean in = u > 0 && u <= 1;
        switch (st.kind) {
            case "shake" -> {
                // 얼음이 짤랑짤랑
                if (in && u > 0.1 && u < 0.9 && now % 2 == 0) spot.sound(SoundEvents.AMETHYST_BLOCK_STEP, 0.35f, 1.3f + rnd.nextFloat() * 0.5f, pose[0], pose[1], pose[2]);
            }
            case "stir" -> {
                if (in && u > 0.1 && u < 0.85 && now % 5 == 0) spot.sound(SoundEvents.AMETHYST_BLOCK_STEP, 0.15f, 1.6f + rnd.nextFloat() * 0.3f, pose[0], pose[1], pose[2]);
            }
            case "blend" -> {
                if (hit(la, lb, p0 + 0.08 * ph.dur())) spot.sound(SoundEvents.GRINDSTONE_USE, 0.45f, 1.5f, pose[0], pose[1], pose[2]);
            }
            case "cork" -> {
                if (hit(la, lb, p0 + 0.45 * ph.dur())) {
                    // 펑: 코르크가 튀고 김과 거품이 솟는다
                    spot.sound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.3f, 1.7f, pose[0], pose[1], pose[2]);
                    spot.sound(SoundEvents.BUBBLE_POP, 0.6f, 0.8f, pose[0], pose[1], pose[2]);
                    for (int i = 0; i < 4; i++) spot.particle(ParticleTypes.POOF, pose[0], pose[1] + 1, pose[2], (rnd.nextDouble() - 0.5) * 0.03, 0.03 + rnd.nextDouble() * 0.03, (rnd.nextDouble() - 0.5) * 0.03);
                    for (int i = 0; i < 6; i++) spot.particle(ParticleTypes.SPLASH, pose[0] + rnd.nextDouble() - 0.5, pose[1] + 0.5, pose[2] + rnd.nextDouble() - 0.5, 0, 0, 0);
                }
            }
            case "can" -> {
                if (hit(la, lb, p0 + 0.3 * ph.dur())) {
                    // 딸깍, 치익
                    spot.sound(SoundEvents.LEVER_CLICK, 0.35f, 1.8f, pose[0], pose[1], pose[2]);
                    spot.sound(SoundEvents.FIRE_EXTINGUISH, 0.08f, 2.0f, pose[0], pose[1], pose[2]);
                    spot.particle(ParticleTypes.POOF, pose[0], pose[1] + 0.5, pose[2], 0, 0.02, 0);
                }
            }
            case "flip" -> {
                if (hit(la, lb, p0 + 0.2568 * ph.dur()) || hit(la, lb, p0 + 0.6368 * ph.dur())) spot.sound(SoundEvents.BOTTLE_FILL, 0.4f, 0.8f, pose[0], pose[1], pose[2]);
            }
            case "swirl" -> {
                if (hit(la, lb, p0 + 0.76 * ph.dur())) spot.sound(SoundEvents.WOOD_HIT, 0.5f, 1.4f, pose[0], pose[1], pose[2]);
            }
            default -> { }
        }
    }
}
