package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.jjan.Jjan;
import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanClinks;
import dev.newyear.countdown.jjan.JjanLayout;
import dev.newyear.countdown.jjan.JjanPackets;
import dev.newyear.countdown.jjan.JjanRules;
import dev.newyear.countdown.jjan.JjanSeatEntity;
import dev.newyear.countdown.jjan.JjanSession;
import dev.newyear.countdown.jjan.JjanTableEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 짠 클라이언트: 서버가 확정한 테이블 스냅숏을 받아 두고(그리기는 JjanTableRenderer), 입력 의도만 보낸다.
 * 좌클릭 = 마시기(공격·블록 파괴 대신. 웹 원작처럼 꾹 누르고 있으면 원샷), 잔 들기 키(기본 V, 조작 설정에서 변경),
 * 개인 메뉴 키(기본 R) 또는 자기 잔 받침 우클릭. 앉아 있는 동안의 HUD 는 원작 화면을 따른다(짠 횟수, 취기 칸, 아래 버튼 줄).
 * 취기는 바닐라 멀미(화면 일렁임)로도 나타난다: {@link #nausea}.
 */
public final class JjanClient {
    public record Seated(BlockPos pos, JjanPackets.SeatSnap snap) {}

    private static final Map<UUID, Float> INTOX = new HashMap<>();
    private static final Map<UUID, Seated> SEATED = new HashMap<>();
    private static final Map<BlockPos, List<UUID>> BY_TABLE = new HashMap<>();
    private static final int[] STAGE_COLORS = {0xFFFFFF, 0xFFE9A8, 0xFFB36B, 0xFF8A5C, 0xFF5A5A};

    public static boolean reduced;
    private static KeyMapping raiseKey, menuKey;

    /** 든 잔을 잡은 가상 커서(시선이 돌았다면 향했을 방향, 도). 화면은 그대로 두고 이 방향의 광선이 탁자 위 든 높이 평면과 만나는 점이 잔의 목표다. */
    private static boolean aiming, aimDirty;
    /** 마우스로 잔을 마지막으로 옮긴 시각. */
    private static long aimMovedNanos;
    private static double aimX, aimZ;
    private static BlockPos aimTable;
    /** 내 잔의 예측 위치(테이블 로컬). 서버와 같은 식으로 프레임마다 커서를 따라가 입력 지연 없이 보여 준다. */
    private static final double[] pred = new double[2];
    private static long predNanos;
    private static boolean predLive;
    /** 잔 들기 키를 누른 시각 (길게 누르고 있다가 떼면 내려놓는다: 웹의 누르고-끌고-놓기). */
    private static long raiseDownNanos;
    private static boolean raiseWasDown;
    private static final long HOLD_NANOS = 280_000_000L;
    /** 좌클릭(마시기)을 누르기 시작한 시각. 0 이면 누르고 있지 않다. 놓으면 한 모금, HOLD_FOR_SHOT 초를 넘기면 원샷. */
    private static long sipDownNanos;
    /** 취기에서 나온 화면 어지러움(바닐라 멀미 세기 0..1): 지난 틱과 이번 틱 값. */
    private static float nausea, nauseaPrev;
    /** HUD: 자리에 앉은 시각(안내문), 짠 횟수가 마지막으로 오른 시각(숫자가 튀는 연출). */
    private static long seatedNanos, bumpNanos;
    private static boolean wasSeated;
    private static int lastClinks = -1;

    private JjanClient() {}

    public static void init(KeyMapping.Category category) {
        BlockEntityRenderers.register(Jjan.TABLE_BE, JjanTableRenderer::new);
        EntityRendererRegistry.register(Jjan.SEAT, NoopRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(JjanPackets.TableS2C.ID, (payload, context) -> onTable(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(JjanPackets.IntoxS2C.ID, (payload, context) -> {
            INTOX.clear();
            int n = Math.min(payload.players().size(), payload.levels().size());
            for (int i = 0; i < n; i++) INTOX.put(payload.players().get(i), payload.levels().get(i));
            reduced = payload.reduced();
        });
        ClientPlayNetworking.registerGlobalReceiver(JjanPackets.OpenS2C.ID, (payload, context) -> {
            Minecraft mc = context.client();
            if (payload.screen() == 0) mc.gui.setScreen(new JjanSelectScreen(payload.drink(), payload.variant(), payload.glass()));
            else mc.gui.setScreen(new JjanMenuScreen());
        });
        ClientPlayNetworking.registerGlobalReceiver(JjanPackets.CloseS2C.ID, (payload, context) -> {
            Minecraft mc = context.client();
            if (mc.gui.screen() instanceof JjanSelectScreen || mc.gui.screen() instanceof JjanMenuScreen) mc.gui.setScreen(null);
        });

        raiseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.newyearcountdown.jjan_raise", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, category));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.newyearcountdown.jjan_menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, category));
        ClientTickEvents.END_CLIENT_TICK.register(JjanClient::tick);
        HudElementRegistry.addLast(ModReg.id("jjan"), JjanClient::renderHud);
    }

    public static void clear() {
        JjanFx.clear();
        JjanAnjuFx.clear();
        aiming = false;
        sipDownNanos = 0;
        nausea = nauseaPrev = 0f;
        INTOX.clear();
        SEATED.clear();
        BY_TABLE.clear();
        reduced = false;
    }

    // ------------------------------------------------------------------ 상태

    private static void onTable(Minecraft mc, JjanPackets.TableS2C msg) {
        if (mc.level == null) return;
        BlockPos pos = msg.pos().immutable();
        if (mc.level.getBlockEntity(pos) instanceof JjanTableEntity be) {
            if (be.clientSnap != null && msg.tick() < be.clientSnap.tick()) return;   // 늦게 온 오래된 것
            be.clientPrev = be.clientSnap;
            be.clientPrevNanos = be.clientSnapNanos;
            be.clientSnap = msg;
            be.clientSnapNanos = System.nanoTime();
            JjanAnjuFx.observe(mc.level, be, msg);
        }
        List<UUID> old = BY_TABLE.remove(pos);
        if (old != null) for (UUID id : old) {
            Seated s = SEATED.get(id);
            if (s != null && s.pos().equals(pos)) SEATED.remove(id);
        }
        List<UUID> now = new ArrayList<>();
        for (JjanPackets.SeatSnap s : msg.seats()) {
            SEATED.put(s.player(), new Seated(pos, s));
            now.add(s.player());
        }
        if (!now.isEmpty()) BY_TABLE.put(pos, now);
    }

    public static Seated seated(UUID id) {
        return SEATED.get(id);
    }

    /** 내가 지금 짠 의자에 앉아 있는지. */
    public static boolean seated() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof JjanSeatEntity;
    }

    public static Seated mine() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null || !seated() ? null : SEATED.get(mc.player.getUUID());
    }

    public static float intox(UUID id) {
        Float f = INTOX.get(id);
        return f == null ? 0f : f;
    }

    /** 이름표 색 (취기 단계). 말짱하면 -1. */
    public static int nameTint(UUID id) {
        int stage = JjanRules.stage(intox(id));
        return stage == 0 ? -1 : STAGE_COLORS[stage];
    }

    public static Component stageName(double level) {
        return Component.translatable("jjan.newyearcountdown.stage." + JjanRules.STAGE_KEYS[JjanRules.stage(level)]);
    }

    /** 잔 들기 키 이름 (메뉴의 안내용). */
    static Component raiseKeyName() {
        return raiseKey.getTranslatedKeyMessage();
    }

    public static void send(int action, int arg) {
        if (ClientPlayNetworking.canSend(JjanPackets.ActionC2S.ID)) ClientPlayNetworking.send(new JjanPackets.ActionC2S(action, arg));
    }

    // ------------------------------------------------------------------ 입력

    /**
     * 좌클릭: 앉아 있으면 공격·파괴 대신 마시기. true 면 바닐라 처리를 막는다.
     * 마실 수 있는 상태면 누른 시각만 적어 두고, 놓을 때 한 모금 / 꾹 누르면 원샷으로 보낸다(tick). 그 외에는 바로 보내 서버의 안내를 받는다.
     */
    public static boolean onAttack() {
        if (!seated()) return false;
        Seated me = mine();
        // 안주 접시를 보고 누르면 마시지 않고 한 입 먹는다 (남의 접시면 뺏어 먹기)
        int plate = me == null ? -1 : JjanAnjuFx.target(Minecraft.getInstance(), me, holding());
        if (plate >= 0) {
            send(JjanPackets.EAT, plate);
            return true;
        }
        if (me != null && me.snap().glass() >= 0 && me.snap().action() == JjanSession.IDLE && me.snap().fill() > JjanRules.EMPTY) {
            if (sipDownNanos == 0) sipDownNanos = System.nanoTime();
        } else {
            send(JjanPackets.SIP, 0);
        }
        return true;
    }

    /** 마시기를 꾹 누른 정도 0..1 (1 이면 원샷). */
    private static float sipHold() {
        return sipDownNanos == 0 ? 0f : (float) Math.min(1.0, (System.nanoTime() - sipDownNanos) / 1.0e9 / JjanRules.HOLD_FOR_SHOT);
    }

    /**
     * 취기에서 나온 화면 어지러움: 바닐라 멀미 효과의 세기(0..1)로 쓰인다 (DrunkNauseaMixin).
     * 취기가 높을수록 크고, 서버가 1초마다 보내는 취기를 틱마다 조금씩 따라가 화면이 갑자기 튀지 않는다.
     */
    public static float nausea(Object entity, float partialTicks) {
        if (nausea <= 0f && nauseaPrev <= 0f) return 0f;
        if (entity != Minecraft.getInstance().player) return 0f;
        return Mth.lerp(partialTicks, nauseaPrev, nausea);
    }

    /** 내 잔을 들고 있어 마우스가 잔을 움직이는 상태인지 (화면이 열려 있으면 아니다). */
    public static boolean holding() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return false;
        Seated me = mine();
        return me != null && me.snap().glass() >= 0 && me.snap().pose() == JjanSession.RAISED
                && me.snap().action() == JjanSession.IDLE;
    }

    /**
     * 마우스 이동(시선이 돌았을 각도, 도). 잔을 들고 있으면 시선 대신 잔의 목표점을 옮기고 true 를 돌려준다(바닐라 시선 회전을 막는다).
     * 마우스를 민 만큼 잔이 탁자 위에서 그대로 움직인다: 오른쪽 = 화면 오른쪽, 위 = 멀리, 아래 = 내 쪽. 감도는 탁자 어디서나 같다
     * (전에는 화면 속 커서 자리를 따라가서, 큰 탁자의 먼 쪽에서는 조금만 움직여도 잔이 크게 튀었다).
     * 영역 밖으로는 나가지 않고 가장자리를 따라 미끄러진다. 실제 이동·충돌·짠 판정은 서버가 정한다.
     */
    public static boolean onLook(double yawDegrees, double pitchDegrees) {
        if (!holding()) {
            aiming = false;
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Seated me = mine();
        if (mc.level == null || mc.player == null || !(mc.level.getBlockEntity(me.pos()) instanceof JjanTableEntity be)) return false;
        if (!aiming || !me.pos().equals(aimTable)) startAim(mc, be, me);
        // 한 번에 너무 크게 튄 입력은 잘라 낸다
        double[] w = JjanRules.drag(Mth.clamp(yawDegrees, -60.0, 60.0), Mth.clamp(pitchDegrees, -60.0, 60.0), mc.player.getYRot());
        double[] d = JjanLayout.toLocal(BlockPos.ZERO, be.facing(), new Vec3(0.5 + w[0], 0, 0.5 + w[1]));
        double[] c = be.def().clampRegion(aimX + d[0], aimZ + d[2]);
        if (c[0] != aimX || c[1] != aimZ) {
            aimX = c[0];
            aimZ = c[1];
            aimDirty = true;
            aimMovedNanos = System.nanoTime();
        }
        return true;
    }

    /** 잔을 막 들었을 때: 커서가 잔을 잡고 있는 것으로 시작한다. */
    private static void startAim(Minecraft mc, JjanTableEntity be, Seated me) {
        aiming = true;
        aimTable = me.pos();
        aimX = me.snap().x();
        aimZ = me.snap().z();
        pred[0] = aimX;
        pred[1] = aimZ;
        predNanos = System.nanoTime();
        predLive = true;
    }

    /**
     * 내 든 잔을 그릴 위치(테이블 로컬 x, z). 들고 있지 않으면 null (서버 스냅숏 그대로 그린다).
     * 잔은 커서 자리에 그대로 붙는다(스프링 없음). 서버와 같은 영역 제한·잔끼리 밀어내기를 프레임마다 계산하고, 마우스가 멈춰 있을 때만 서버가 정한 위치로 맞춘다.
     */
    public static double[] ownGlass(JjanTableEntity be, List<JjanPackets.SeatSnap> seats, JjanPackets.SeatSnap mine) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !holding()) {
            predLive = false;
            return null;
        }
        Seated me = mine();
        if (!aiming || !me.pos().equals(aimTable)) startAim(mc, be, me);
        if (!predLive) return null;
        long nowN = System.nanoTime();
        double dt = Math.min(0.1, (nowN - predNanos) / 1e9);
        predNanos = nowN;
        if (dt > 0 && !mc.isPaused()) {
            double time = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            double w = JjanRules.wobble(intox(mc.player.getUUID()), reduced);
            double tx = aimX + w * Math.sin(time * 0.37 + mine.seat() * 1.3), tz = aimZ + w * Math.cos(time * 0.29 + mine.seat() * 1.7);
            JjanClinks.follow(pred, tx, tz, dt);
            JjanCatalog cat = JjanCatalog.get();
            JjanCatalog.Glass g = cat.glasses().get(mine.glass());
            double r = g.contactR() * JjanRules.GLASS_SCALE, low = mine.y() + g.contactLow() * JjanRules.GLASS_SCALE, high = mine.y() + g.height() * JjanRules.GLASS_SCALE;
            for (JjanPackets.SeatSnap o : seats) {
                if (o.seat() == mine.seat() || o.glass() < 0 || o.glass() >= cat.glasses().size() || o.action() != JjanSession.IDLE || o.pose() == JjanSession.RETURNING) continue;
                JjanCatalog.Glass og = cat.glasses().get(o.glass());
                double oLow = o.y() + og.contactLow() * JjanRules.GLASS_SCALE, oHigh = o.y() + og.height() * JjanRules.GLASS_SCALE;
                if (Math.max(low, oLow) >= Math.min(high, oHigh)) continue;
                double dx = pred[0] - o.x(), dz = pred[1] - o.z(), d = Math.hypot(dx, dz), rs = r + og.contactR() * JjanRules.GLASS_SCALE;
                if (d >= rs) continue;
                double nx = d > 1e-6 ? dx / d : -1, nz = d > 1e-6 ? dz / d : 0;
                pred[0] = o.x() + nx * rs;
                pred[1] = o.z() + nz * rs;
            }
            double[] c = be.def().clampRegion(pred[0], pred[1]);
            pred[0] = c[0];
            pred[1] = c[1];
            // 서버 위치는 늘 몇 틱 늦다(전송 + 서버 틱). 마우스를 움직이는 동안 거기에 맞추면 빠르게 흔들 때 잔이 뒤로 튀었다가 돌아온다.
            // 그래서 움직이는 동안에는 전혀 맞추지 않고, 마우스가 한동안 멈춘 뒤에만 서버가 정한 위치로 부드럽게 맞춘다.
            if (nowN - aimMovedNanos > 350_000_000L) {
                double k = Math.min(1, dt * 6);
                pred[0] += (mine.x() - pred[0]) * k;
                pred[1] += (mine.z() - pred[1]) * k;
            }
        }
        return new double[]{pred[0], pred[1]};
    }

    private static void tick(Minecraft mc) {
        JjanFx.tick(mc, SEATED.values());
        JjanAnjuFx.tick(mc, SEATED.values());
        nauseaPrev = nausea;
        float want = mc.player == null ? 0f : (float) JjanRules.nausea(intox(mc.player.getUUID()), reduced);
        nausea = want > nausea ? Math.min(want, nausea + 0.004f) : Math.max(want, nausea - 0.008f);
        if (sipDownNanos != 0) {
            if (mc.gui.screen() != null || !seated()) {
                sipDownNanos = 0;   // 화면이 열리거나 일어나면 아직 보내지 않은 입력은 버린다
            } else if (sipHold() >= 1f) {
                sipDownNanos = 0;
                send(JjanPackets.SHOT, 0);
            } else if (!mc.options.keyAttack.isDown()) {
                sipDownNanos = 0;
                send(JjanPackets.SIP, 0);
            }
        }
        if (aiming && !holding()) aiming = false;
        if (aiming && aimDirty && ClientPlayNetworking.canSend(JjanPackets.AimC2S.ID)) {
            ClientPlayNetworking.send(new JjanPackets.AimC2S((float) aimX, (float) aimZ));
        }
        aimDirty = false;
        boolean free = mc.gui.screen() == null && seated();
        while (raiseKey.consumeClick()) {
            if (!free) continue;
            send(JjanPackets.RAISE, 0);
            raiseDownNanos = System.nanoTime();
        }
        // 길게 누르고 있다가 떼면 내려놓는다 (짧게 누르면 들기/내려놓기 전환)
        boolean down = raiseKey.isDown();
        if (raiseWasDown && !down && free && holding() && System.nanoTime() - raiseDownNanos > HOLD_NANOS) send(JjanPackets.RAISE, 0);
        raiseWasDown = down;
        while (menuKey.consumeClick()) if (free) send(JjanPackets.MENU, 0);
        if (mc.level == null) {
            if (!SEATED.isEmpty()) clear();
            return;
        }
        // 멀어져서 더는 갱신되지 않는 테이블의 기록을 지운다
        if (mc.level.getGameTime() % 40 == 0 && !BY_TABLE.isEmpty()) {
            for (BlockPos pos : new ArrayList<>(BY_TABLE.keySet())) {
                if (mc.level.getBlockEntity(pos) instanceof JjanTableEntity) continue;
                for (UUID id : BY_TABLE.remove(pos)) {
                    Seated s = SEATED.get(id);
                    if (s != null && s.pos().equals(pos)) SEATED.remove(id);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 연출 공용

    /** 마시는 동작의 진행(0..1) → 잔을 입으로 가져간 정도(0..1). */
    public static float lift(float t) {
        if (t <= 0f || t >= 1f) return 0f;
        float up = Mth.clamp(t / 0.22f, 0f, 1f), down = Mth.clamp((1f - t) / 0.18f, 0f, 1f);
        float k = Math.min(up, down);
        return k * k * (3f - 2f * k);
    }

    public static float progress(JjanPackets.SeatSnap s, float time) {
        return s.actionLen() <= 0 ? 1f : Mth.clamp((time - s.actionStart()) / s.actionLen(), 0f, 1f);
    }

    /** 앉은 플레이어 팔 자세: 잔을 들면 오른팔을 앞으로, 마시면 입으로. */
    public static void applyPose(HumanoidModel<?> model, Player player) {
        if (SEATED.isEmpty()) return;
        Seated s = SEATED.get(player.getUUID());
        if (s == null || s.snap().glass() < 0 || !(player.getVehicle() instanceof JjanSeatEntity)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        JjanPackets.SeatSnap snap = s.snap();
        float time = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        boolean drinking = snap.action() == JjanSession.SIPPING || snap.action() == JjanSession.CHUGGING;
        float k = drinking && !JjanFx.straw(snap) ? lift(progress(snap, time)) : 0f;
        float raised = snap.pose() == JjanSession.RAISED ? 1f : 0f;
        if (k <= 0f && raised <= 0f) return;
        float x = Mth.lerp(k, -1.2f * raised, -2.05f), y = Mth.lerp(k, -0.12f * raised, -0.45f);
        model.rightArm.xRot = x;
        model.rightArm.yRot = y;
        model.rightArm.zRot = 0f;
        if (snap.action() == JjanSession.CHUGGING) model.head.xRot -= 0.4f * k;
    }

    // ------------------------------------------------------------------ HUD

    /**
     * 앉아 있는 동안의 화면. 바닐라 HUD 처럼 글자 몇 줄만 쓴다:
     * 오른쪽 위에 짠 횟수 · 지금 술과 남은 양 · 취기, 조준점 아래에 조작 안내(앉은 직후 잠깐)와 원샷 게이지(좌클릭을 누르고 있을 때만).
     */
    private static void renderHud(GuiGraphicsExtractor ctx, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        Font font = mc.font;
        float level = intox(mc.player.getUUID());
        int w = ctx.guiWidth(), h = ctx.guiHeight();
        boolean seated = seated();
        if (seated != wasSeated) {
            wasSeated = seated;
            seatedNanos = System.nanoTime();
            lastClinks = -1;
        }
        if (mc.gui.screen() != null) return;
        if (!seated) {
            if (JjanRules.stage(level) > 0) JjanUi.tipsy(ctx, font, w - 8, 24, level);
            return;
        }
        Seated me = SEATED.get(mc.player.getUUID());
        JjanPackets.SeatSnap s = me == null ? null : me.snap();
        boolean ordered = s != null && s.glass() >= 0;
        long now = System.nanoTime();

        // 오른쪽 위
        int clinks = s == null ? 0 : s.clinks();
        if (lastClinks >= 0 && clinks > lastClinks) bumpNanos = now;
        lastClinks = clinks;
        boolean bump = now - bumpNanos < 400_000_000L;
        Component count = Component.translatable("jjan.newyearcountdown.hud.counter", clinks);
        int y = 22;
        ctx.text(font, count, w - 8 - font.width(count), y, bump ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        y += 11;
        if (ordered) {
            JjanCatalog cat = JjanCatalog.get();
            String name = "?";
            if (s.drink() < cat.drinks().size()) {
                JjanCatalog.Drink d = cat.drinks().get(s.drink());
                name = s.variant() >= 0 && s.variant() < d.variants().size() ? d.variants().get(s.variant()).name() : d.name();
            }
            Component line = Component.translatable("jjan.newyearcountdown.hud.drink", name, Math.round(s.fill() * 100));
            ctx.text(font, line, w - 8 - font.width(line), y, 0xFFFFFFFF, true);
            y += 11;
        }
        JjanUi.tipsy(ctx, font, w - 8, y, level);

        // 조준점 아래: 원샷 게이지, 안내 한 줄
        float hold = sipHold();
        if (hold > 0.12f) {
            int bx = w / 2 - 16, by = h / 2 + 10;
            ctx.fill(bx, by, bx + 32, by + 4, 0xFF000000);
            ctx.fill(bx + 1, by + 1, bx + 1 + Math.round(30 * hold), by + 3, hold >= 1f ? 0xFFFFFF55 : 0xFFFFFFFF);
        }
        Component hint = null;
        float alpha = 1f;
        int plate = me == null ? -1 : JjanAnjuFx.target(mc, me, holding());
        if (plate >= 0) {
            // 접시를 보고 있으면 무엇을 하게 되는지 알려 준다
            hint = Component.translatable(plate == s.seat() ? "jjan.newyearcountdown.hud.eat" : "jjan.newyearcountdown.hud.steal", mc.options.keyAttack.getTranslatedKeyMessage());
        } else if (!ordered) {
            hint = Component.translatable("jjan.newyearcountdown.hud.no_order", menuKey.getTranslatedKeyMessage());
        } else {
            alpha = Mth.clamp((10f - (now - seatedNanos) / 1e9f) / 1f, 0f, 1f);
            if (alpha > 0.05f) {
                hint = Component.translatable("jjan.newyearcountdown.hud.keys", mc.options.keyAttack.getTranslatedKeyMessage(),
                        raiseKey.getTranslatedKeyMessage(), menuKey.getTranslatedKeyMessage(), mc.options.keyShift.getTranslatedKeyMessage());
            }
        }
        if (hint != null) ctx.centeredText(font, hint, w / 2, h / 2 + 22, ((int) (255 * alpha) << 24) | 0xFFFFFF);
    }
}
