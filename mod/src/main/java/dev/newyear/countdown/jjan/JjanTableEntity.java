package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 짠 테이블 인스턴스(TableInstance): 자리 점유, 자리별 세션, 잔 이동·짠 판정, 주변 동기화.
 * 서버가 좌석·잔·음주·충돌을 확정하고, 클라이언트는 스냅숏을 받아 모델·화면·연출만 맡는다.
 * 같은 정의를 여러 번 설치해도 각자 독립이고, 다른 테이블의 잔과는 부딪히지 않는다.
 * 자리·세션은 저장하지 않는다(재시작하면 빈 테이블). 취기·선호·누적 짠 횟수는 JjanData 에 남는다.
 */
public class JjanTableEntity extends BlockEntity {
    /** 잔을 들면 잔 몸통 가운데(contactMid)가 이 높이(상판 위)에 온다. 앉은 눈높이보다 낮다. */
    public static final double RAISE_PLANE = 0.30;
    /** 탄 플레이어의 발 위치는 좌석 엔티티보다 이만큼 아래다 (바닐라 Avatar 탑승 부착점 0.6: 엉덩이가 좌석 높이에 온다). */
    public static final double SIT_DROP = 0.6;
    public static final int RETURN_TICKS = 8;

    private final JjanSession[] seats;
    private final JjanSeatEntity[] seatEntities;
    private final Map<Long, JjanClinks.Pair> pairs = new HashMap<>();
    private long lastSync = Long.MIN_VALUE / 2;
    private boolean dirty;
    private long clinkId;
    private int clinkTotal;

    /** 클라이언트: 마지막으로 받은 스냅숏과 그 직전 것(보간용). */
    public JjanPackets.TableS2C clientSnap, clientPrev;
    public long clientSnapNanos, clientPrevNanos;

    public JjanTableEntity(BlockPos pos, BlockState state) {
        super(Jjan.TABLE_BE, pos, state);
        int cap = state.getBlock() instanceof JjanTableBlock b ? b.def.capacity : 16;
        seats = new JjanSession[cap];
        seatEntities = new JjanSeatEntity[cap];
    }

    public JjanTables.Def def() {
        return ((JjanTableBlock) getBlockState().getBlock()).def;
    }

    public Direction facing() {
        return getBlockState().getValue(JjanTableBlock.FACING);
    }

    public int occupied() {
        int n = 0;
        for (JjanSession s : seats) if (s != null) n++;
        return n;
    }

    public int clinkTotal() {
        return clinkTotal;
    }

    JjanSession seatSession(int i) {
        return i >= 0 && i < seats.length ? seats[i] : null;
    }

    boolean owns(JjanSeatEntity e) {
        int i = e.seat();
        return i >= 0 && i < seatEntities.length && seatEntities[i] == e && seats[i] != null;
    }

    Vec3 local(double x, double y, double z) {
        return JjanLayout.toWorld(worldPosition, facing(), x, y, z);
    }

    // ------------------------------------------------------------------ 상호작용

    /** 우클릭: 빈 의자면 앉고, 앉아 있으면 자기 잔 받침으로 개인 메뉴. */
    void interact(ServerPlayer sp, Vec3 hit) {
        if (!(level instanceof ServerLevel)) return;
        JjanTables.Def def = def();
        double[] l = JjanLayout.toLocal(worldPosition, facing(), hit);
        JjanService.Ref mine = JjanService.ref(sp.getUUID());
        if (mine != null && mine.table() == this) {
            int c = def.coasterAt(l[0], l[2]);
            if (c == mine.session().seat && l[1] >= def.top - 0.25) {
                openMenu(sp, mine.session());
            } else if (c >= 0) {
                sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.not_your_coaster").withStyle(ChatFormatting.GRAY));
            } else {
                sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.coaster_hint").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        if (mine != null) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.leave_first").withStyle(ChatFormatting.GRAY));
            return;
        }
        int s = def.seatAt(l[0], l[2]);
        if (s >= 0 && l[1] < def.seats.get(s).seatTop() + 0.7) {
            sit(sp, s);
        } else {
            sp.sendOverlayMessage(Component.translatable(occupied() >= def.capacity ? "jjan.newyearcountdown.full" : "jjan.newyearcountdown.sit_hint",
                    Component.translatable("jjan.newyearcountdown.table." + def.id), occupied(), def.capacity).withStyle(ChatFormatting.GOLD));
        }
    }

    /** 자리 예약 → 좌석 엔티티 → 탑승 → 세션 생성. 실패하면 되돌린다. 같은 자리를 동시에 누르면 먼저 확정한 한 명만 앉는다. */
    void sit(ServerPlayer sp, int s) {
        if (!(level instanceof ServerLevel sw)) return;
        if (seats[s] != null) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.taken").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (sp.isPassenger() || sp.isSleeping() || sp.isSpectator() || !sp.isAlive()) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.cannot_sit").withStyle(ChatFormatting.GRAY));
            return;
        }
        JjanTables.Seat seat = def().seats.get(s);
        JjanSession ses = new JjanSession(sp.getUUID(), s, sw.getRandom().nextLong());
        seats[s] = ses;   // 예약
        Vec3 at = local(seat.chairX(), seat.seatTop() + 0.01, seat.chairZ());
        float yaw = JjanLayout.worldYaw(facing(), seat.lookYaw());
        JjanSeatEntity e = new JjanSeatEntity(Jjan.SEAT, sw);
        e.setPos(at.x, at.y, at.z);
        e.setYRot(yaw);
        e.bind(worldPosition, s, sp.position());
        seatEntities[s] = e;
        if (!sw.addFreshEntity(e) || !sp.startRiding(e, true, true)) {
            seats[s] = null;
            seatEntities[s] = null;
            e.discard();
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.cannot_sit").withStyle(ChatFormatting.GRAY));
            return;
        }
        JjanService.register(sp, this, ses);
        sp.forceSetRotation(yaw, false, 25f, false);
        home(ses);
        dirty = true;
        openSelect(sp, ses);
    }

    /**
     * 퇴석 정리 (모든 경로가 여기로 모인다): 세션 무효화 → 메뉴 닫기 → 동작 취소 → 잔 정리 → 점유 해제 → 탑승 해제.
     * dismount 가 true 면 플레이어를 의자에서 내린다(바닐라 내리기 위치 = 안전한 출구). 이미 내린 경우(사망·텔레포트 등)는 위치를 건드리지 않는다.
     */
    void close(JjanSession ses, boolean dismount) {
        if (ses == null || ses.state >= JjanSession.LEAVING) return;
        ses.state = JjanSession.LEAVING;
        JjanService.unregister(ses.player, this);
        ServerPlayer p = level == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(ses.player);
        if (p != null) ServerPlayNetworking.send(p, new JjanPackets.CloseS2C(0));
        ses.action = JjanSession.IDLE;
        ses.actionId++;
        if (seats[ses.seat] == ses) seats[ses.seat] = null;
        JjanSeatEntity e = seatEntities[ses.seat];
        seatEntities[ses.seat] = null;
        pairs.values().removeIf(pr -> pr.a == ses.sessionId || pr.b == ses.sessionId);
        ses.state = JjanSession.CLOSED;
        if (e != null) {
            if (dismount && p != null && p.getVehicle() == e) p.stopRiding();
            e.ejectPassengers();
            e.retire();   // 다음 틱에 스스로 사라진다 (같은 틱에 지우면 클라이언트가 이미 없는 좌석의 탑승 갱신을 받아 경고를 남긴다)
        }
        dirty = true;
    }

    /** 테이블 철거·언로드: 앉은 사람을 모두 일으킨다. */
    void closeAll() {
        for (JjanSession s : seats) if (s != null) close(s, true);
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) closeAll();
        JjanService.forget(this);
        super.setRemoved();
    }

    /** 일어날 자리: 출구 후보 중 플레이어가 서 있을 공간이 빈 첫 곳. */
    Vec3 safeExit(int seatIndex, LivingEntity who) {
        if (level == null || seatIndex < 0 || seatIndex >= def().seats.size()) return null;
        for (double[] ex : def().seats.get(seatIndex).exits()) {
            for (double dy : new double[]{0, 1}) {
                Vec3 at = local(ex[0], dy, ex[1]);
                AABB box = who.getDimensions(Pose.STANDING).makeBoundingBox(at.x, at.y, at.z).deflate(0.02);
                if (level.noCollision(who, box)) return at;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 행동

    void openMenu(ServerPlayer sp, JjanSession ses) {
        if (ses.pose == JjanSession.RAISED) startReturn(ses);   // 메뉴를 열면 잔을 제자리로 (돌아가는 길에는 짠 없음)
        ServerPlayNetworking.send(sp, new JjanPackets.OpenS2C(worldPosition, ses.seat, 1, "", "", ""));
    }

    void openSelect(ServerPlayer sp, JjanSession ses) {
        if (ses.pose == JjanSession.RAISED) startReturn(ses);
        String d, v, g;
        if (ses.ordered) {
            d = ses.drink.id(); v = ses.variant == null ? "" : ses.variant.id(); g = ses.glass.id();
        } else {
            JjanData.Profile pr = JjanData.get(sp.level().getServer()).profile(sp.getUUID());
            d = pr.drink; v = pr.variant; g = pr.glass;
        }
        ServerPlayNetworking.send(sp, new JjanPackets.OpenS2C(worldPosition, ses.seat, 0, d, v, g));
    }

    /** 주문: 기존 잔은 버리고(취기에 넣지 않음) 새 술을 준비한다. 자기 잔만 바뀐다. */
    void order(ServerPlayer sp, JjanSession ses, JjanCatalog.Drink drink, JjanCatalog.Variant variant, JjanCatalog.Glass glass) {
        if (ses.busy()) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
            return;
        }
        ses.drink = drink;
        ses.variant = variant;
        ses.glass = glass;
        ses.ordered = true;
        // 안주도 새로 한 접시 나온다 (술에 어울리는 것으로)
        ses.anjuLeft = pieces(ses);
        ses.anjuRefill = -1;
        ses.anjuEater = -1;
        ses.state = JjanSession.ACTIVE;
        ses.fillFrom = 0;
        ses.fill = 1;
        ses.pose = JjanSession.RESTING;
        home(ses);
        // 만드는 과정(재료 넣기·섞기·따르기)의 길이는 원작 시간표 그대로. 소리와 그림은 클라이언트가 같은 시간표로 낸다
        startAction(ses, JjanSession.PREPARING, JjanPour.of(JjanCatalog.get(), drink.look(variant), true, 0).ticks());
        JjanData.get(sp.level().getServer()).setPreference(sp.getUUID(), drink.id(), variant == null ? "" : variant.id(), glass.id());
    }

    /** 한 모금 또는 원샷. 시작할 때 잔량·취기·통계를 한 번만 바꾸고, 애니메이션은 그 결과를 보여 준다. */
    void drink(ServerPlayer sp, JjanSession ses, boolean shot) {
        if (!ses.ordered || ses.state != JjanSession.ACTIVE) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.order_first").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (ses.busy()) return;   // 진행 중 동작이 있으면 무시 (연타 방지)
        if (JjanRules.isEmpty(ses.fill)) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        double before = ses.fill;
        JjanCatalog.Look look = ses.drink.look(ses.variant);
        // 불붙은 술(B-52)은 잔을 들면 데니까 빨대를 꽂아 단숨에 다 마신다 (원작 strawSip)
        boolean straw = look.flame();
        boolean empties = shot || straw || JjanRules.sipEmpties(ses.drink.shot(), ses.glass.id());
        double after = JjanRules.afterSip(before, empties);
        double ml = JjanRules.consumedMl(before, after, ses.glass.capacity(look.fill()));
        double alcohol = JjanRules.alcoholMl(ml, look.abv());
        JjanService.addAlcohol(sp, alcohol, ml);
        ses.consumedMl += ml;
        ses.fillFrom = before;
        ses.fill = after;
        if (straw) {
            ses.pose = JjanSession.RESTING;   // 잔은 탁자에 둔 채로
            home(ses);
            startAction(ses, JjanSession.SIPPING, JjanPour.strawTicks());
            return;
        }
        if (ses.pose == JjanSession.RETURNING) ses.pose = JjanSession.RAISED;
        startAction(ses, shot ? JjanSession.CHUGGING : JjanSession.SIPPING, shot ? JjanRules.chugTicks(before) : JjanRules.sipTicks());
        if (shot) say(ses, 19);
    }

    void refill(ServerPlayer sp, JjanSession ses) {
        if (!ses.ordered || ses.busy()) return;
        if (ses.fill > JjanRules.REFILL_FULL) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.full_glass").withStyle(ChatFormatting.GRAY));
            return;
        }
        ses.fillFrom = ses.fill;
        ses.fill = 1;
        ses.pose = JjanSession.RESTING;
        home(ses);
        startAction(ses, JjanSession.REFILLING, JjanPour.of(JjanCatalog.get(), ses.drink.look(ses.variant), false, ses.fillFrom).ticks());
    }

    /** 잔 들기 ↔ 내려놓기. */
    void raise(ServerPlayer sp, JjanSession ses) {
        if (!ses.ordered) {
            sp.sendOverlayMessage(Component.translatable("jjan.newyearcountdown.order_first").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (ses.busy()) return;
        if (ses.pose == JjanSession.RAISED) {
            startReturn(ses);
        } else if (ses.pose == JjanSession.RESTING) {
            ses.pose = JjanSession.RAISED;
            ses.targetX = ses.x;   // 든 자리에서 시작한다. 그 뒤로는 마우스로 옮긴 목표점(aim)을 따라간다
            ses.targetZ = ses.z;
            ses.hasTarget = true;
            ses.y = raisedY(ses);
            dirty = true;
        }
    }

    /** 든 잔을 옮길 목표점(테이블 로컬). 클라이언트는 마우스로 정한 점만 보내고, 영역 제한·속도·충돌은 서버가 정한다. */
    void aim(JjanSession ses, double x, double z) {
        if (ses.pose != JjanSession.RAISED || !Double.isFinite(x) || !Double.isFinite(z)) return;
        double[] c = def().clampRegion(x, z);
        ses.targetX = c[0];
        ses.targetZ = c[1];
        ses.hasTarget = true;
    }

    /** 다 먹은 접시가 다시 채워지기까지(틱)와, 한 사람이 연달아 집어 먹는 간격(틱). 원작: 4초 뒤 리필. */
    static final int ANJU_REFILL_TICKS = 80, ANJU_EAT_GAP = 4;

    /** 이 자리 접시의 안주: 직접 골랐으면 그것, 아니면 술에 어울리는 것 (원작 currentAnju). 술이 없으면 null. */
    private static JjanCatalog.Anju anjuOf(JjanSession ses) {
        if (!ses.ordered) return null;
        List<JjanCatalog.Anju> all = JjanCatalog.get().anju();
        return ses.anjuChoice >= 0 && ses.anjuChoice < all.size() ? all.get(ses.anjuChoice) : JjanCatalog.get().anjuFor(ses.drink);
    }

    private static int pieces(JjanSession ses) {
        JjanCatalog.Anju a = anjuOf(ses);
        return a == null ? 0 : a.pieces().size();
    }

    /** 안주 바꾸기 (원작 "오늘의 안주"): 새 접시가 나온다. */
    void chooseAnju(JjanSession ses, int which) {
        if (!ses.ordered || which < 0 || which >= JjanCatalog.get().anju().size()) return;
        JjanCatalog.Anju before = anjuOf(ses);
        if (before != null && before.index() == which) return;
        ses.anjuChoice = which;
        ses.anjuLeft = pieces(ses);
        ses.anjuRefill = -1;
        ses.anjuEater = -1;
        dirty = true;
    }

    /** 안주 한 입: target 자리의 접시에서 조각 하나를 집어 먹는다 (내 접시든 남의 접시든). 효과는 없고 재미로. */
    void eat(JjanSession ses, int target) {
        long now = level == null ? 0 : level.getGameTime();
        if (ses.state != JjanSession.ACTIVE || now - ses.lastEat < ANJU_EAT_GAP) return;
        JjanSession owner = target >= 0 && target < seats.length ? seats[target] : null;
        if (owner == null || !owner.ordered || owner.anjuLeft <= 0) return;
        ses.lastEat = now;
        owner.anjuLeft--;
        owner.anjuEater = ses.seat;
        owner.anjuTick = now;
        if (owner.anjuLeft == 0) owner.anjuRefill = now + ANJU_REFILL_TICKS;
        dirty = true;
    }

    void say(JjanSession ses, int phrase) {
        ses.say = phrase;
        ses.sayTick = level == null ? 0 : level.getGameTime();
        dirty = true;
    }

    private void startAction(JjanSession ses, int action, int ticks) {
        ses.action = action;
        ses.actionId++;
        ses.actionStart = level.getGameTime();
        ses.actionLen = ticks;
        ses.vx = ses.vz = 0;
        dirty = true;
    }

    private void startReturn(JjanSession ses) {
        ses.pose = JjanSession.RETURNING;
        ses.returnStart = level.getGameTime();
        ses.returnFromX = ses.x;
        ses.returnFromZ = ses.z;
        ses.returnFromY = ses.y;
        ses.vx = ses.vz = 0;
        dirty = true;
    }

    private void home(JjanSession ses) {
        JjanTables.Seat s = def().seats.get(ses.seat);
        ses.x = s.homeX();
        ses.z = s.homeZ();
        ses.y = def().top;
        ses.vx = ses.vz = 0;
    }

    private double raisedY(JjanSession ses) {
        double mid = ses.glass.contactMid() * JjanRules.GLASS_SCALE;
        return Math.max(def().top + 0.02, def().top + RAISE_PLANE - mid);
    }

    private void play(SoundEvent sound, JjanSession ses, float vol, float pitch) {
        Vec3 at = local(ses.x, ses.y + 0.2, ses.z);
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, vol, pitch);
    }

    // ------------------------------------------------------------------ 틱: 동작·이동·짠·동기화

    public static void serverTick(Level world, BlockPos pos, BlockState state, JjanTableEntity be) {
        if (world instanceof ServerLevel sw) be.tick(sw);
    }

    private void tick(ServerLevel sw) {
        JjanService.track(this);
        long now = sw.getGameTime();
        List<JjanSession> active = new ArrayList<>();
        double[] dispX = new double[seats.length], dispZ = new double[seats.length];
        for (JjanSession ses : seats) {
            if (ses == null) continue;
            ServerPlayer p = sw.getServer().getPlayerList().getPlayer(ses.player);
            JjanSeatEntity e = seatEntities[ses.seat];
            if (p == null || e == null || p.getVehicle() != e || p.level() != sw) {
                close(ses, false);
                continue;
            }
            if (ses.anjuRefill >= 0 && now >= ses.anjuRefill) {   // 다 먹은 접시는 잠시 뒤 다시 채워진다
                ses.anjuRefill = -1;
                ses.anjuLeft = pieces(ses);
                dirty = true;
            }
            if (ses.action != JjanSession.IDLE) {
                long t = now - ses.actionStart;
                boolean drinking = ses.action == JjanSession.SIPPING || ses.action == JjanSession.CHUGGING;
                if (drinking && !ses.drink.look(ses.variant).flame() && t > 0 && t % 10 == 6 && t < ses.actionLen - 4) play(Jjan.GULP, ses, 0.7f, 0.95f + sw.getRandom().nextFloat() * 0.1f);
                if (t >= ses.actionLen) {
                    if (drinking) ses.pose = JjanSession.RESTING;
                    if (ses.pose == JjanSession.RESTING) home(ses);
                    ses.action = JjanSession.IDLE;
                    dirty = true;
                }
            }
            if (ses.pose == JjanSession.RETURNING) {
                double k = (now - ses.returnStart) / (double) RETURN_TICKS;
                JjanTables.Seat s = def().seats.get(ses.seat);
                if (k >= 1) {
                    ses.pose = JjanSession.RESTING;
                    home(ses);
                } else {
                    ses.x = ses.returnFromX + (s.homeX() - ses.returnFromX) * k;
                    ses.z = ses.returnFromZ + (s.homeZ() - ses.returnFromZ) * k;
                    ses.y = ses.returnFromY + (def().top - ses.returnFromY) * k;
                }
            }
            if (ses.pose == JjanSession.RAISED && ses.action == JjanSession.IDLE) {
                double[] d = desiredStep(p, ses, now);
                dispX[ses.seat] = d[0];
                dispZ[ses.seat] = d[1];
                active.add(ses);
            } else {
                ses.vx = ses.vz = 0;
            }
        }

        // 이동·접촉·짠 판정 (순수 계산은 JjanClinks)
        List<JjanSession> all = new ArrayList<>();
        for (JjanSession s : seats) if (s != null) all.add(s);
        JjanClinks.step(def(), all, active, dispX, dispZ, pairs, now, new JjanClinks.Listener() {
            @Override
            public void sound(JjanSession a, JjanSession b, double strength) {
                clinkFx(sw, a, b, strength);
            }

            @Override
            public void count(JjanSession a, JjanSession b) {
                clinkId++;
                clinkTotal++;
                a.clinks++;
                b.clinks++;
                JjanService.addClink(sw.getServer(), a.player);
                JjanService.addClink(sw.getServer(), b.player);
                dirty = true;
            }
        });
        if (dirty || (occupied() > 0 && now - lastSync >= 1)) {
            sync(sw, now);
            lastSync = now;
            dirty = false;
        }
    }

    /** 든 잔이 목표점(마우스로 옮긴 점)으로 이번 틱에 움직일 양: 목표점으로 곧장(최고 속도 제한만) + 취기 떨림. 시선과는 무관하다. */
    private double[] desiredStep(ServerPlayer p, JjanSession ses, long now) {
        ses.y = raisedY(ses);
        if (!ses.hasTarget) { ses.targetX = ses.x; ses.targetZ = ses.z; ses.hasTarget = true; }
        double w = JjanRules.wobble(JjanService.displayed(p.getUUID()), JjanData.get(p.level().getServer()).profile(p.getUUID()).reduced);
        double[] st = {ses.x, ses.z};
        JjanClinks.follow(st, ses.targetX + w * Math.sin(now * 0.37 + ses.seat * 1.3), ses.targetZ + w * Math.cos(now * 0.29 + ses.seat * 1.7), 0.05);
        return new double[]{st[0] - ses.x, st[1] - ses.z};
    }

    /** 짠 소리(재질별, 크기·세기로 음높이·크기)와 튐·반짝임. 잔량은 줄지 않는다. */
    private void clinkFx(ServerLevel sw, JjanSession a, JjanSession b, double strength) {
        double cy = (Math.max(a.low(), b.low()) + Math.min(a.high(), b.high())) / 2;
        Vec3 at = local((a.x + b.x) / 2, cy, (a.z + b.z) / 2);
        double size = (a.glass.contactR() + b.glass.contactR()) / 2;
        float pitch = (float) Math.max(0.75, Math.min(1.5, 1.45 - size * 0.1)) + (sw.getRandom().nextFloat() - 0.5f) * 0.08f;
        float vol = (float) (0.35 + 0.65 * strength);
        sw.playSound(null, at.x, at.y, at.z, Jjan.clink(a.glass.material()), SoundSource.PLAYERS, vol, pitch);
        if (!b.glass.material().equals(a.glass.material())) {
            sw.playSound(null, at.x, at.y, at.z, Jjan.clink(b.glass.material()), SoundSource.PLAYERS, vol * 0.7f, pitch);
        }
        sw.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 1 + (int) (strength * 4), 0.04, 0.04, 0.04, 0.05);
        if (strength > 0.3) {
            for (JjanSession s : new JjanSession[]{a, b}) {
                if (JjanRules.isEmpty(s.fill)) continue;   // 빈 잔은 액체가 튀지 않는다
                Vec3 top = local(s.x, s.high(), s.z);
                sw.sendParticles(ParticleTypes.SPLASH, top.x, top.y, top.z, 2 + (int) (strength * 8), 0.05, 0.02, 0.05, 0.05);
            }
        }
    }

    private void sync(ServerLevel sw, long now) {
        List<JjanPackets.SeatSnap> list = new ArrayList<>();
        for (JjanSession s : seats) {
            if (s == null) continue;
            list.add(new JjanPackets.SeatSnap(s.seat, s.player,
                    s.ordered ? s.drink.index() : -1, s.ordered && s.variant != null ? s.variant.index() : -1,
                    s.ordered ? s.glass.index() : -1, (float) s.fill, (float) s.fillFrom,
                    (float) s.x, (float) s.y, (float) s.z, s.pose, s.action, s.actionStart, s.actionLen,
                    (float) JjanService.displayed(s.player), s.say, s.sayTick, s.clinks, s.ordered ? s.anjuLeft : 0, s.anjuEater, s.anjuTick, anjuOf(s) == null ? -1 : anjuOf(s).index()));
        }
        JjanPackets.TableS2C msg = new JjanPackets.TableS2C(worldPosition, now, list);
        for (ServerPlayer p : PlayerLookup.tracking(sw, worldPosition)) ServerPlayNetworking.send(p, msg);
    }

    /** 관리자 진단: 기준 위치·방향·점유 칸 수·좌석 상태. */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        JjanTables.Def def = def();
        out.add(def.id + " @ " + worldPosition.toShortString() + " facing " + facing().getName() + ", 칸 " + (def.partCells().size() + 1)
                + ", 자리 " + occupied() + "/" + def.capacity + ", 짠 " + clinkTotal);
        for (int i = 0; i < seats.length; i++) {
            JjanSession s = seats[i];
            if (s == null) continue;
            String who = level != null && level.getServer() != null && level.getServer().getPlayerList().getPlayer(s.player) != null
                    ? level.getServer().getPlayerList().getPlayer(s.player).getName().getString() : s.player.toString();
            out.add(" #" + i + " " + who + " " + (s.ordered ? s.drink.id() + "/" + s.glass.id() + " " + Math.round(s.fill * 100) + "%" : "(주문 전)")
                    + " pose=" + s.pose + " action=" + s.action + " 짠=" + s.clinks);
        }
        return out;
    }

    /** 이 테이블에 앉은 플레이어(세션) 목록. */
    List<UUID> seated() {
        List<UUID> out = new ArrayList<>();
        for (JjanSession s : seats) if (s != null) out.add(s.player);
        return out;
    }
}
