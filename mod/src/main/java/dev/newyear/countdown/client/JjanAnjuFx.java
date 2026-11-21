package dev.newyear.countdown.client;

import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanLayout;
import dev.newyear.countdown.jjan.JjanPackets;
import dev.newyear.countdown.jjan.JjanTableEntity;
import dev.newyear.countdown.jjan.JjanTables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 안주 접시의 연출 (원작 anju.js 의 AnjuPlate): 내 접시에서 집어 먹으면 조각이 사라지며 부스러기가 튀고,
 * 남의 접시에서 뺏어 먹으면 조각이 먹는 사람 입까지 0.6초 동안 포물선으로 날아간다. 뜨거운 안주는 김이 난다.
 * 몇 조각 남았는지·누가 먹었는지는 서버가 정하고, 여기서는 스냅숏이 바뀐 것을 보고 그림과 소리만 낸다.
 */
final class JjanAnjuFx {
    /** 안주 복셀 하나의 크기(블록). 탁자가 원작보다 조금 작아 잔(1/32)보다 살짝 줄인다. */
    static final float K = 0.85f / 32f;
    /** 뺏긴 조각이 날아가는 시간(초)과 솟는 높이(복셀). */
    static final double FLY_SECONDS = 0.6, FLY_ARC = 22;

    private record Key(BlockPos pos, int seat) {}

    /** 날아가는 조각 하나. */
    record Fly(int piece, int eater, long startNanos) {
        double u() {
            return (System.nanoTime() - startNanos) / 1e9 / FLY_SECONDS;
        }
    }

    private static final Map<Key, Long> SEEN = new HashMap<>();
    private static final Map<Key, List<Fly>> FLYING = new HashMap<>();

    /** 그 자리 접시에 놓인 안주. 없으면 null. */
    static JjanCatalog.Anju of(JjanCatalog cat, JjanPackets.SeatSnap s) {
        return s.anjuId() >= 0 && s.anjuId() < cat.anju().size() ? cat.anju().get(s.anjuId()) : null;
    }

    static void clear() {
        SEEN.clear();
        FLYING.clear();
    }

    static List<Fly> flies(BlockPos pos, int seat) {
        List<Fly> l = FLYING.get(new Key(pos, seat));
        return l == null ? List.of() : l;
    }

    /** 조각 상자들의 가운데 (복셀). */
    static float[] center(float[] boxes) {
        float[] lo = {1e9f, 1e9f, 1e9f}, hi = {-1e9f, -1e9f, -1e9f};
        for (int i = 0; i + 6 < boxes.length; i += 7) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], boxes[i + k]);
                hi[k] = Math.max(hi[k], boxes[i + 3 + k]);
            }
        }
        return new float[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
    }

    /** 접시 위 조각의 가운데를 탁자 로컬 좌표(블록)로. */
    static double[] pieceAt(JjanTables.Def def, int seat, float[] boxes) {
        double[] at = def.anju(seat);
        float[] c = center(boxes);
        double a = def.seats.get(seat).angle(), cos = Math.cos(a), sin = Math.sin(a);
        return new double[]{at[0] + (c[0] * cos + c[2] * sin) * K, def.top + c[1] * K, at[1] + (-c[0] * sin + c[2] * cos) * K};
    }

    /** 먹는 사람의 입 근처 (탁자 로컬 좌표). */
    static double[] mouth(JjanTables.Def def, int seat) {
        JjanTables.Seat s = def.seats.get(seat);
        double dx = s.homeX() - s.chairX(), dz = s.homeZ() - s.chairZ(), len = Math.max(1e-6, Math.hypot(dx, dz));
        return new double[]{s.chairX() + dx / len * 0.28, s.seatTop() + 0.7, s.chairZ() + dz / len * 0.28};
    }

    /** 새 스냅숏이 왔다: 지난번과 견주어 누가 방금 먹었는지 본다. */
    static void observe(Level level, JjanTableEntity be, JjanPackets.TableS2C msg) {
        JjanCatalog cat = JjanCatalog.get();
        JjanTables.Def def = be.def();
        BlockPos pos = be.getBlockPos().immutable();
        for (JjanPackets.SeatSnap s : msg.seats()) {
            if (s.drink() < 0 || s.drink() >= cat.drinks().size() || s.seat() < 0 || s.seat() >= def.seats.size()) continue;
            Key key = new Key(pos, s.seat());
            Long before = SEEN.put(key, s.anjuTick());
            if (before == null || before == s.anjuTick() || s.anjuEater() < 0 || s.anjuEater() >= def.seats.size()) continue;
            if (level.getGameTime() - s.anjuTick() > 20) continue;   // 한참 전 일은 다시 보여 주지 않는다
            JjanCatalog.Anju a = of(cat, s);
            if (a == null) continue;
            int piece = a.pieces().size() - s.anju() - 1;   // 방금 없어진 조각
            if (piece < 0 || piece >= a.pieces().size()) continue;
            if (s.anjuEater() == s.seat()) {
                double[] p = pieceAt(def, s.seat(), a.pieces().get(piece));
                crunch(level, be, p, a.hot(), true);
            } else {
                FLYING.computeIfAbsent(key, k -> new ArrayList<>()).add(new Fly(piece, s.anjuEater(), System.nanoTime()));
            }
        }
    }

    /** 아삭: 소리와 부스러기. */
    private static void crunch(Level level, JjanTableEntity be, double[] local, boolean hot, boolean crumbs) {
        Vec3 w = JjanLayout.toWorld(be.getBlockPos(), be.facing(), local[0], local[1], local[2]);
        // 원작: 뜨거운 안주는 덜 바삭한 소리
        level.playLocalSound(w.x, w.y, w.z, SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 0.55f, hot ? 0.95f : 1.35f, false);
        if (!crumbs) return;
        ItemParticleOption crumb = new ItemParticleOption(ParticleTypes.ITEM, Items.BREAD);
        for (int i = 0; i < 4; i++) {
            level.addParticle(crumb, w.x, w.y + 0.02, w.z, (level.getRandom().nextDouble() - 0.5) * 0.08, 0.06 + level.getRandom().nextDouble() * 0.05,
                    (level.getRandom().nextDouble() - 0.5) * 0.08);
        }
    }

    static void tick(Minecraft mc, Collection<JjanClient.Seated> seated) {
        Level level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        // 다 날아간 조각: 입에 쏙, 아삭
        for (Iterator<Map.Entry<Key, List<Fly>>> it = FLYING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, List<Fly>> e = it.next();
            if (!(level.getBlockEntity(e.getKey().pos()) instanceof JjanTableEntity be)) {
                it.remove();
                continue;
            }
            for (Iterator<Fly> f = e.getValue().iterator(); f.hasNext(); ) {
                Fly fly = f.next();
                if (fly.u() < 1) continue;
                f.remove();
                if (fly.eater() < be.def().seats.size()) crunch(level, be, mouth(be.def(), fly.eater()), false, false);
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        // 뜨거운 안주는 김이 난다
        if (level.getGameTime() % 6 != 0) return;
        JjanCatalog cat = JjanCatalog.get();
        for (JjanClient.Seated s : seated) {
            JjanPackets.SeatSnap snap = s.snap();
            if (snap.drink() < 0 || snap.drink() >= cat.drinks().size() || snap.anju() <= 0) continue;
            JjanCatalog.Anju a = of(cat, snap);
            if (a == null || !a.hot() || !(level.getBlockEntity(s.pos()) instanceof JjanTableEntity be)) continue;
            if (snap.seat() >= be.def().seats.size() || level.getRandom().nextInt(2) != 0) continue;
            double[] at = be.def().anju(snap.seat());
            Vec3 w = JjanLayout.toWorld(be.getBlockPos(), be.facing(), at[0] + (level.getRandom().nextDouble() - 0.5) * 0.2, be.def().top + 0.14,
                    at[1] + (level.getRandom().nextDouble() - 0.5) * 0.2);
            level.addParticle(ParticleTypes.WHITE_SMOKE, w.x, w.y, w.z, 0, 0.012, 0);
        }
    }

    /**
     * 조준점이 가리키는 안주 접시의 자리. 접시 위가 아니면 -1. 원작처럼 접시를 눌러서 먹는다 (내 접시면 한 입, 남의 접시면 뺏어 먹기).
     * 잔을 들고 있으면 마우스가 잔을 움직이므로 접시를 가리킬 수 없다.
     */
    static int target(Minecraft mc, JjanClient.Seated me, boolean holding) {
        if (holding || mc.player == null || mc.level == null || !(mc.level.getBlockEntity(me.pos()) instanceof JjanTableEntity be)) return -1;
        JjanPackets.TableS2C snap = be.clientSnap;
        if (snap == null) return -1;
        JjanCatalog cat = JjanCatalog.get();
        Vec3 eye = mc.player.getEyePosition(), dir = mc.player.getViewVector(1f);
        // 접시 위 음식 높이쯤의 평면에서 본다 (접시 바닥보다 조금 위)
        double h = eye.y - (be.getBlockPos().getY() + be.def().top + 0.06);
        if (h <= 0 || dir.y >= -1e-3) return -1;
        double t = h / -dir.y;
        double[] l = JjanLayout.toLocal(be.getBlockPos(), be.facing(), new Vec3(eye.x + dir.x * t, 0, eye.z + dir.z * t));
        int best = -1;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (JjanPackets.SeatSnap s : snap.seats()) {
            if (of(cat, s) == null || s.anju() <= 0 || s.seat() < 0 || s.seat() >= be.def().seats.size()) continue;
            double[] at = be.def().anju(s.seat());
            double d = (at[0] - l[0]) * (at[0] - l[0]) + (at[1] - l[2]) * (at[1] - l[2]);
            if (d < bestD) {
                bestD = d;
                best = s.seat();
            }
        }
        return best;
    }

    /** 접시로 치는 반지름(블록): 접시(약 0.24)보다 조금 넉넉하게. */
    static final double PICK_RADIUS = 0.3;

    private JjanAnjuFx() {}
}
