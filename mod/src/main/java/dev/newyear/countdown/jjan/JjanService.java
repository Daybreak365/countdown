package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * 짠 서비스: 플레이어 ↔ 테이블·세션 연결, 행동 요청 처리(GUI·명령어 공통), 취기 시간 감소·딸꾹질·동기화, 프로필 저장.
 * 모든 호출은 서버 스레드에서 일어난다(패킷 수신도 Fabric 이 서버 스레드로 넘긴다).
 */
public final class JjanService {
    public record Ref(JjanTableEntity table, JjanSession session) {}

    private static final Map<UUID, Ref> SESSIONS = new HashMap<>();
    /** 온라인 플레이어의 취기 {목표, 표시}. */
    private static final Map<UUID, double[]> INTOX = new HashMap<>();
    private static final Set<JjanTableEntity> TABLES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<UUID, Double> SAVED = new HashMap<>();

    private JjanService() {}

    // ------------------------------------------------------------------ 연결

    static void register(ServerPlayer p, JjanTableEntity be, JjanSession ses) {
        SESSIONS.put(p.getUUID(), new Ref(be, ses));
    }

    static void unregister(UUID id, JjanTableEntity be) {
        Ref r = SESSIONS.get(id);
        if (r != null && r.table() == be) SESSIONS.remove(id);
    }

    public static Ref ref(UUID id) {
        return SESSIONS.get(id);
    }

    static void track(JjanTableEntity be) {
        TABLES.add(be);
    }

    static void forget(JjanTableEntity be) {
        TABLES.remove(be);
    }

    public static List<JjanTableEntity> tables() {
        List<JjanTableEntity> out = new ArrayList<>();
        for (JjanTableEntity t : TABLES) if (!t.isRemoved()) out.add(t);
        return out;
    }

    /** 의자에서 내렸다(Shift·사망·텔레포트·접속 종료·철거 등 모든 경로). 위치는 바닐라 내리기가 이미 정했다. */
    static void onDismounted(ServerPlayer p, JjanSeatEntity seat) {
        Ref r = SESSIONS.get(p.getUUID());
        if (r != null && r.table().owns(seat)) r.table().close(r.session(), false);
    }

    // ------------------------------------------------------------------ 행동 (GUI·명령어가 같은 경로)

    public static boolean action(ServerPlayer p, int action, int arg) {
        Ref r = SESSIONS.get(p.getUUID());
        if (r == null) {
            if (action != JjanPackets.MENU_CLOSED) p.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.not_seated").withStyle(ChatFormatting.GRAY));
            return false;
        }
        JjanTableEntity t = r.table();
        JjanSession s = r.session();
        switch (action) {
            case JjanPackets.MENU -> t.openMenu(p, s);
            case JjanPackets.SELECT -> {
                if (s.busy()) p.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
                else t.openSelect(p, s);
            }
            case JjanPackets.SIP -> t.drink(p, s, false);
            case JjanPackets.SHOT -> t.drink(p, s, true);
            case JjanPackets.REFILL -> t.refill(p, s);
            case JjanPackets.RAISE -> t.raise(p, s);
            case JjanPackets.EAT -> t.eat(s, arg);
            case JjanPackets.ANJU -> t.chooseAnju(s, arg);
            case JjanPackets.SAY -> {
                List<String> phrases = JjanCatalog.get().phrases();
                if (arg >= 0 && arg < phrases.size()) t.say(s, arg);
            }
            case JjanPackets.LEAVE -> p.stopRiding();
            default -> { }
        }
        return true;
    }

    /** 든 잔의 목표점. 앉아 있지 않으면 무시한다. */
    public static void aim(ServerPlayer p, double x, double z) {
        Ref r = SESSIONS.get(p.getUUID());
        if (r != null) r.table().aim(r.session(), x, z);
    }

    public static void order(ServerPlayer p, String drinkId, String variantId, String glassId) {
        Ref r = SESSIONS.get(p.getUUID());
        if (r == null) return;
        JjanCatalog cat = JjanCatalog.get();
        JjanCatalog.Drink d = cat.drink(drinkId);
        JjanCatalog.Glass g = cat.glass(glassId);
        if (d == null || g == null || g.hidden()) {
            p.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.bad_order").withStyle(ChatFormatting.RED));
            return;
        }
        JjanCatalog.Variant v = d.variant(variantId);
        if (variantId != null && !variantId.isEmpty() && v == null) {
            p.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.bad_order").withStyle(ChatFormatting.RED));
            return;
        }
        r.table().order(p, r.session(), d, v, g);
    }

    // ------------------------------------------------------------------ 취기

    static void addAlcohol(ServerPlayer p, double alcoholMl, double consumedMl) {
        double[] v = INTOX.computeIfAbsent(p.getUUID(), k -> new double[2]);
        v[0] = JjanRules.addIntox(v[0], alcoholMl);
        JjanData data = JjanData.get(p.level().getServer());
        JjanData.Profile pr = data.profile(p.getUUID());
        pr.consumedMl += consumedMl;
        pr.target = v[0];
        pr.updatedAt = System.currentTimeMillis();
        data.touch();
    }

    public static double displayed(UUID id) {
        double[] v = INTOX.get(id);
        return v == null ? 0 : v[1];
    }

    public static double target(UUID id) {
        double[] v = INTOX.get(id);
        return v == null ? 0 : v[0];
    }

    static void addClink(MinecraftServer server, UUID id) {
        JjanData data = JjanData.get(server);
        data.profile(id).totalClinks++;
        data.touch();
    }

    /** 접속: 오프라인으로 지난 시간만큼 깬 값으로 시작한다(시계가 뒤로 갔으면 깎지 않는다). */
    public static void join(ServerPlayer p) {
        JjanData.Profile pr = JjanData.get(p.level().getServer()).profile(p.getUUID());
        long now = System.currentTimeMillis();
        double t = pr.updatedAt > 0 ? JjanRules.decay(pr.target, (now - pr.updatedAt) / 1000.0) : pr.target;
        if (t > 0) INTOX.put(p.getUUID(), new double[]{t, t});
        SAVED.put(p.getUUID(), t);
    }

    /** 접속 종료: 자리 반환(바로), 취기 저장. 재접속해도 다시 앉히지 않는다. */
    public static void disconnect(ServerPlayer p) {
        Ref r = SESSIONS.get(p.getUUID());
        if (r != null) r.table().close(r.session(), false);
        save(p.level().getServer(), p.getUUID());
        INTOX.remove(p.getUUID());
        SAVED.remove(p.getUUID());
    }

    private static void save(MinecraftServer server, UUID id) {
        double[] v = INTOX.get(id);
        JjanData data = JjanData.get(server);
        JjanData.Profile pr = data.profile(id);
        double t = v == null ? 0 : v[0];
        pr.target = t;
        pr.updatedAt = System.currentTimeMillis();
        SAVED.put(id, t);
        data.touch();
    }

    public static void stopAll(MinecraftServer server) {
        for (JjanTableEntity t : tables()) t.closeAll();
        for (UUID id : new ArrayList<>(INTOX.keySet())) save(server, id);
        SESSIONS.clear();
        INTOX.clear();
        SAVED.clear();
        TABLES.clear();
    }

    public static void setReduced(ServerPlayer p, boolean reduced) {
        JjanData data = JjanData.get(p.level().getServer());
        data.profile(p.getUUID()).reduced = reduced;
        data.touch();
    }

    /** 매 틱: 목표는 초당 0.0035 씩 줄고, 표시값은 목표를 따라간다. 1초마다 딸꾹질·이름표 색 동기화·저장 표시. */
    public static void tick(MinecraftServer server) {
        double dt = 1.0 / 20.0;
        for (Map.Entry<UUID, double[]> e : INTOX.entrySet()) {
            double[] v = e.getValue();
            v[0] = JjanRules.decay(v[0], dt);
            v[1] = JjanRules.approachDisplayed(v[1], v[0], dt);
        }
        if (server.getTickCount() % 20 != 0) return;
        List<UUID> ids = new ArrayList<>();
        List<Float> levels = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            double shown = displayed(p.getUUID());
            if (shown > 0.005) {
                ids.add(p.getUUID());
                levels.add((float) shown);
            }
            // 딸꾹질: 원본 기준 표시값 0.5 초과에서 가끔
            if (shown > 0.5 && p.getRandom().nextFloat() < 0.08f) {
                p.level().playSound(null, p.getX(), p.getEyeY(), p.getZ(), Jjan.HICCUP, SoundSource.PLAYERS, 0.8f, 0.9f + p.getRandom().nextFloat() * 0.2f);
                Ref r = SESSIONS.get(p.getUUID());
                if (r != null) r.table().say(r.session(), 20);
            }
            // 크게 바뀌었으면 저장 표시 (정상 종료·주기 저장은 기존 월드 저장을 따른다)
            Double last = SAVED.get(p.getUUID());
            if (last == null || Math.abs(last - target(p.getUUID())) > 0.02) save(server, p.getUUID());
        }
        INTOX.entrySet().removeIf(e -> e.getValue()[0] <= 0 && e.getValue()[1] <= 0 && server.getPlayerList().getPlayer(e.getKey()) == null);
        JjanData data = JjanData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, new JjanPackets.IntoxS2C(ids, levels, data.profile(p.getUUID()).reduced));
        }
    }
}
