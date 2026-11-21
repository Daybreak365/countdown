package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanPackets;
import dev.newyear.countdown.jjan.JjanPour;
import dev.newyear.countdown.jjan.JjanRules;
import dev.newyear.countdown.jjan.JjanSession;
import dev.newyear.countdown.jjan.JjanTableEntity;
import dev.newyear.countdown.jjan.JjanTables;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 짠 테이블 렌더러: 테이블·의자(JjanTables 의 조각 그대로), 잔 받침, 자리별 잔·술·거품·얼음, 따르는 병, 마시는 동작, 말풍선, 테이블 이름표.
 * 잔 위치는 서버 스냅숏 두 개 사이를 보간한다. 술이 줄어드는 모습은 서버가 정한 시작·끝 값 사이를 동작 진행에 맞춰 보여 줄 뿐이다.
 */
public class JjanTableRenderer implements BlockEntityRenderer<JjanTableEntity, BeState<JjanTableEntity>> {
    static final Identifier WHITE = ModReg.id("textures/block/jjan_white.png");
    private static final float S = (float) JjanRules.GLASS_SCALE;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final Font font;

    public JjanTableRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.font();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public BeState<JjanTableEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(JjanTableEntity be, BeState<JjanTableEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<JjanTableEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        JjanTableEntity be = state.be;
        Level world = be == null ? null : be.getLevel();
        if (world == null) return;
        JjanTables.Def def = be.def();
        GeoBuffers buffers = new GeoBuffers();
        float time = world.getGameTime() + state.partialTicks;
        int light = LightCoordsUtil.getLightCoords(world, be.getBlockPos().above());
        float yaw = be.facing().toYRot();
        JjanClient.Seated mine = JjanClient.mine();
        int mySeat = mine != null && mine.pos().equals(be.getBlockPos()) ? mine.snap().seat() : -1;

        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-yaw));
        drawTable(new BellModel.Painter(buffers, m, light, 0), def, mySeat);

        JjanPackets.TableS2C snap = be.clientSnap;
        List<JjanPackets.SeatSnap> seats = snap == null ? List.of() : snap.seats();
        if (!seats.isEmpty()) {
            float alpha = 1f;
            if (be.clientPrev != null && be.clientSnapNanos > be.clientPrevNanos) {
                alpha = Mth.clamp((System.nanoTime() - be.clientSnapNanos) / (float) (be.clientSnapNanos - be.clientPrevNanos), 0f, 1f);
            }
            JjanCatalog cat = JjanCatalog.get();
            // 액체를 먼저, 잔 껍데기를 나중에 (같은 반투명 버퍼 안에서 유리 너머로 술이 보이게)
            for (int pass = 0; pass < 2; pass++) {
                for (JjanPackets.SeatSnap s : seats) {
                    if (s.glass() < 0 || s.glass() >= cat.glasses().size() || s.drink() < 0 || s.drink() >= cat.drinks().size()) continue;
                    if (s.seat() < 0 || s.seat() >= def.seats.size()) continue;
                    boolean ownView = s.seat() == mySeat && Minecraft.getInstance().options.getCameraType().isFirstPerson();
                    double[] own = s.seat() == mySeat ? JjanClient.ownGlass(be, seats, s) : null;
                    drawGlass(buffers, m, light, def, cat, s, previous(be.clientPrev, s), alpha, time, pass, ownView, own);
                }
            }
            for (JjanPackets.SeatSnap s : seats) {
                if (s.drink() >= 0 && s.drink() < cat.drinks().size() && s.seat() >= 0 && s.seat() < def.seats.size()) drawAnju(buffers, m, light, def, cat, s, be.getBlockPos());
            }
            for (JjanPackets.SeatSnap s : seats) {
                if (s.say() < 0 || time - s.sayTick() > 60 || s.seat() < 0 || s.seat() >= def.seats.size()) continue;
                JjanTables.Seat seat = def.seats.get(s.seat());
                List<String> phrases = cat.phrases();
                Component text = s.say() < phrases.size() ? Component.literal(phrases.get(s.say()))
                        : Component.translatable(s.say() == 19 ? "jjan.newyearcountdown.say.shot" : "jjan.newyearcountdown.say.hiccup");
                label(buffers, m, camera, yaw, text, (float) seat.chairX(), (float) seat.seatTop() + 1.62f, (float) seat.chairZ(), 0.022f, 0xFF3B2A1F, 0xE6F4E7D0);
            }
        }

        if (mySeat < 0 && camera.pos.distanceToSqr(Vec3.atCenterOf(be.getBlockPos())) < 14 * 14) {
            int n = seats.size();
            Component text = Component.translatable("jjan.newyearcountdown.label", Component.translatable("jjan.newyearcountdown.table." + def.id), n, def.capacity);
            label(buffers, m, camera, yaw, text, 0f, (float) def.top + 1.15f, 0f, 0.02f, n >= def.capacity ? 0xFFFF9A9A : 0xFFFFE9A8, 0x60000000);
        }
        m.popPose();
        buffers.flush(collector);
    }

    private static JjanPackets.SeatSnap previous(JjanPackets.TableS2C prev, JjanPackets.SeatSnap now) {
        if (prev == null) return now;
        for (JjanPackets.SeatSnap p : prev.seats()) if (p.seat() == now.seat() && p.player().equals(now.player())) return p;
        return now;
    }

    private void label(GeoBuffers buffers, PoseStack m, CameraRenderState camera, float yaw, Component text,
                       float x, float y, float z, float scale, int color, int background) {
        m.pushPose();
        m.translate(x, y, z);
        m.rotate(Axis.YP.rotationDegrees(yaw));   // 구조물 회전을 되돌리고
        m.rotate(camera.orientation);             // 카메라를 향하게
        m.scale(scale, -scale, scale);
        buffers.text(m, text, -font.width(text) / 2f, 0f, color, false, background, FULL_BRIGHT);
        m.popPose();
    }

    // ------------------------------------------------------------------ 테이블·의자

    static Identifier tex(String name) {
        // jjan_ 으로 시작하면 이 모드의 결 텍스처(회색조, 원작 색을 곱해 쓴다)
        return name.startsWith("jjan_") ? ModReg.id("textures/block/" + name + ".png") : Identifier.withDefaultNamespace("textures/block/" + name + ".png");
    }

    static void drawTable(BellModel.Painter p, JjanTables.Def def, int mySeat) {
        for (JjanTables.Part part : def.parts) {
            p.use(tex(part.texture()), part.color());
            if (part.yaw() != 0f) {
                p.m.pushPose();
                p.m.translate(part.pivotX(), 0, part.pivotZ());
                p.m.rotate(Axis.YP.rotation(part.yaw()));
                p.m.translate(-part.pivotX(), 0, -part.pivotZ());
                p.box(part.x0(), part.y0(), part.z0(), part.x1(), part.y1(), part.z1());
                p.m.popPose();
            } else {
                p.box(part.x0(), part.y0(), part.z0(), part.x1(), part.y1(), part.z1());
            }
        }
        // 잔 받침: 자기 것은 금색
        float top = (float) def.top;
        for (JjanTables.Seat s : def.seats) {
            boolean me = s.index() == mySeat;
            p.use(tex(me ? "jjan_metal" : "jjan_fabric"), me ? 0xFFE8C46A : 0xFF8A6A4A);
            float x = (float) s.homeX(), z = (float) s.homeZ();
            p.box(x - 0.13f, top, z - 0.13f, x + 0.13f, top + 0.012f, z + 0.13f);
        }
    }

    public static void drawGhost(GeoBuffers buffers, PoseStack m, int argb, JjanTables.Def def) {
        drawTable(new BellModel.Painter(buffers, m, FULL_BRIGHT, argb), def, -1);
    }

    // ------------------------------------------------------------------ 안주

    private static void boxes(VertexConsumer vc, PoseStack.Pose e, float[] b, int light) {
        for (int i = 0; i + 6 < b.length; i += 7) {
            JjanGlassMesh.box(vc, e, b[i], b[i + 1], b[i + 2], b[i + 3], b[i + 4], b[i + 5], 0xFF000000 | (int) b[i + 6], light);
        }
    }

    /** 안주 접시: 접시 + 아직 남은 조각들, 그리고 누가 뺏어 가는 중이면 날아가는 조각. 앉은 사람 쪽을 보게 돌려 놓는다 (원작 placeAnju). */
    private void drawAnju(GeoBuffers buffers, PoseStack m, int light, JjanTables.Def def, JjanCatalog cat, JjanPackets.SeatSnap s, net.minecraft.core.BlockPos pos) {
        JjanCatalog.Anju a = JjanAnjuFx.of(cat, s);
        if (a == null) return;
        double[] at = def.anju(s.seat());
        float angle = (float) def.seats.get(s.seat()).angle(), k = JjanAnjuFx.K;
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityCutout(WHITE));
        m.pushPose();
        m.translate(at[0], def.top, at[1]);
        m.rotate(Axis.YP.rotation(angle));
        m.scale(k, k, k);
        boxes(vc, m.last(), a.plate(), light);
        int n = a.pieces().size();
        for (int i = n - Mth.clamp(s.anju(), 0, n); i < n; i++) boxes(vc, m.last(), a.pieces().get(i), light);
        m.popPose();
        for (JjanAnjuFx.Fly f : JjanAnjuFx.flies(pos, s.seat())) {
            if (f.piece() >= n || f.eater() >= def.seats.size()) continue;
            float u = (float) Mth.clamp(f.u(), 0, 1), e = smooth(u), sec = (float) (u * JjanAnjuFx.FLY_SECONDS);
            float[] piece = a.pieces().get(f.piece()), c = JjanAnjuFx.center(piece);
            double[] from = JjanAnjuFx.pieceAt(def, s.seat(), piece), to = JjanAnjuFx.mouth(def, f.eater());
            float spin = ((float) JjanGlassMesh.hash3(f.piece(), 3, s.seat()) - 0.5f) * 9f;
            float shrink = u < 0.75f ? 1f : Math.max(0.001f, 1f - (u - 0.75f) / 0.25f);   // 다 와 가면 입에 쏙
            m.pushPose();
            m.translate(Mth.lerp(e, from[0], to[0]), Mth.lerp(e, from[1], to[1]) + Math.sin(u * Math.PI) * JjanAnjuFx.FLY_ARC * k, Mth.lerp(e, from[2], to[2]));
            m.rotate(Axis.YP.rotation(angle + spin * sec));
            m.rotate(Axis.XP.rotation(spin * 0.6f * sec));
            m.scale(k * shrink, k * shrink, k * shrink);
            m.translate(-c[0], -c[1], -c[2]);
            boxes(vc, m.last(), piece, light);
            m.popPose();
        }
    }

    // ------------------------------------------------------------------ 잔·술

    private static float smooth(float k) {
        k = Mth.clamp(k, 0f, 1f);
        return k * k * (3f - 2f * k);
    }

    /** 자리마다 다른, 그러나 늘 같은 값 [0, 1) (원본의 Math.random 자리). */
    private static float rnd(int i, int salt, int seat) {
        return (float) JjanGlassMesh.hash3(i * 7 + 1, salt, seat * 13 + 5);
    }

    private void drawGlass(GeoBuffers buffers, PoseStack m, int light, JjanTables.Def def, JjanCatalog cat, JjanPackets.SeatSnap s,
                           JjanPackets.SeatSnap prev, float alpha, float time, int pass, boolean ownView, double[] own) {
        JjanCatalog.Glass glass = cat.glasses().get(s.glass());
        JjanCatalog.Drink drink = cat.drinks().get(s.drink());
        JjanCatalog.Look look = s.variant() >= 0 && s.variant() < drink.variants().size() ? drink.variants().get(s.variant()).look() : drink.look();
        JjanTables.Seat seat = def.seats.get(s.seat());
        JjanGlassMesh mesh = JjanGlassMesh.of(glass);

        float x = Mth.lerp(alpha, prev.x(), s.x()), y = Mth.lerp(alpha, prev.y(), s.y()), z = Mth.lerp(alpha, prev.z(), s.z());
        if (own != null) {   // 내가 든 잔은 예측 위치로 (입력 지연 없이 커서를 따라온다)
            x = (float) own[0];
            z = (float) own[1];
        }
        float t = JjanClient.progress(s, time);
        float sec = time / 20f;
        int action = s.action();
        boolean drinking = action == JjanSession.SIPPING || action == JjanSession.CHUGGING;
        boolean pouring = action == JjanSession.PREPARING || action == JjanSession.REFILLING;

        // 만드는 과정의 시간표와 다 따른 뒤 흐른 시간 (원작과 같은 순서·길이)
        JjanFx.State fx = JjanFx.state(cat, s, look);
        double act = (time - s.actionStart()) / 20.0;   // 지금 동작이 시작된 뒤 흐른 시간(초)
        double rel = pouring ? -1 : JjanFx.served(fx, time);
        // 불붙은 술은 잔을 들지 않고 빨대를 꽂아 마신다
        boolean straw = drinking && look.flame();

        // 보여 줄 잔량: 서버가 정한 시작값 → 끝값
        float fill = s.fill();
        if (straw) fill = (float) (s.fillFrom() * JjanPour.strawLeft(act));
        else if (drinking) fill = Mth.lerp(smooth((t - 0.2f) / 0.6f), s.fillFrom(), s.fill());
        else if (pouring) fill = fx.plan != null ? (float) fx.plan.fill(act) : Mth.lerp(smooth((t - 0.15f) / 0.7f), s.fillFrom(), s.fill());

        // 앉은 사람이 탁자를 보는 방향
        float dx = (float) (seat.homeX() - seat.chairX()), dz = (float) (seat.homeZ() - seat.chairZ());
        float len = Mth.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f) { dx = 0; dz = -1; } else { dx /= len; dz /= len; }

        float tilt = 0f;
        if (drinking && !straw) {
            float k = JjanClient.lift(t);
            float hs = glass.height() * S;
            // 1인칭으로 내 잔을 볼 때는 조금 더 앞·아래에 둔다: 잔이 화면을 다 가리지 않고 잔 입구 너머로 앞이 보이게
            float reach = ownView ? 0.46f : 0.34f;
            float mx = (float) seat.chairX() + dx * reach, mz = (float) seat.chairZ() + dz * reach;
            float my = (float) seat.seatTop() + (ownView ? 0.66f : 0.84f) - hs * 0.85f;
            x = Mth.lerp(k, x, mx);
            y = Mth.lerp(k, y, my);
            z = Mth.lerp(k, z, mz);
            tilt = k * (action == JjanSession.CHUGGING ? 1.5f : 0.85f) * (0.6f + 0.4f * (1f - fill));
        }

        m.pushPose();
        m.translate(x, y, z);
        // 원본처럼 잔을 앉은 사람 쪽으로 돌려 놓는다: 잔의 +Z 가 앉은 사람, +X 가 그 사람의 오른쪽(손잡이)
        m.rotate(Axis.YP.rotation((float) Math.atan2(-dx, -dz)));
        if (tilt != 0f) {
            float pivot = glass.height() * S * 0.6f;
            m.translate(0, pivot, 0);
            m.rotate(Axis.XP.rotation(tilt));   // 잔 입구가 앉은 사람 쪽으로 기운다
            m.translate(0, -pivot, 0);
        }
        m.scale(S, S, S);
        PoseStack.Pose e = m.last();
        VertexConsumer translucent = buffers.getBuffer(RenderTypes.entityTranslucent(WHITE));
        int cs = glass.cavityStart(), H = glass.height();
        double fullTop = glass.level(look.fill(), 1.0);
        double level = glass.level(look.fill(), fill);
        boolean wet = fill > JjanRules.EMPTY;
        // 폭탄주: 다 따른 뒤 샷잔이 위에서 떨어진다 (고진감래는 처음부터 들어 있다)
        boolean drops = JjanPour.drops(drink.id(), look);
        double dropHit = drops ? JjanPour.dropFall(H + 14 - (fullTop - 0.5)) : -1;
        double foamH = look.foamHeight() > 0 && fill > 0.03f
                ? look.foamHeight() * (0.55 + 0.45 * fill) + JjanFx.foamBoost(fx, look, act, rel, dropHit) : 0;
        // 술을 다 따른 뒤에 장식을 톡 얹는다 (원본: 소금 테두리만 처음부터 묻어 있다. 살짝 커졌다가 제자리)
        boolean served = !(pouring && fx.hidGarnish);
        float pop = 1f;
        if (fx.hidGarnish && rel >= 0 && rel < JjanPour.GARNISH_POP) {
            float u = (float) (rel / JjanPour.GARNISH_POP);
            pop = Math.max(0.001f, u < 0.7f ? smooth(u / 0.7f) * 1.12f : 1.12f - 0.12f * smooth((u - 0.7f) / 0.3f));
        }
        // 다 따른 뒤: 회오리·스월링·젓기로 술이 돈다
        double swirl = rel >= 0 ? JjanPour.swirl(look, rel) : 0;
        // 술이 돌면 얼음과 건더기도 함께 돈다 (원작: 얼음 swirl × 3 rad/s, 건더기는 평소의 1 + swirl × 10 배)
        float turn = rel >= 0 ? (float) JjanPour.swirlTurn(look, rel) : 0f;

        if (pass == 0) {
            VertexConsumer solid = buffers.getBuffer(RenderTypes.entityCutout(WHITE));
            JjanGarnish garnish = JjanGarnish.of(glass, look.garnish());
            garnish.draw(solid, e, light, JjanGarnish.SALT, 0f);
            if (served) {
                m.pushPose();
                m.scale(pop, pop, pop);
                PoseStack.Pose ge = m.last();
                garnish.draw(solid, ge, light, JjanGarnish.PROP, 0f);
                // 먹는 장식(과일·올리브·체리)은 마지막 모금에 먹는다
                if (wet || (drinking && t < 0.7f)) garnish.draw(solid, ge, light, JjanGarnish.EDIBLE, 0f);
                if (fill > 0.03f) garnish.draw(solid, ge, light, JjanGarnish.FLOATING, (float) (level + foamH - 0.15));
                m.popPose();
            }
            drawFloaters(solid, m, light, cat, glass, look, s.seat(), sec + turn * 10f, (float) level, fill);
            if (look.bomb().equals("drop") && !(drops && pouring)) {
                float[] shot = drops ? JjanFx.dropShot(rel, H + 14, (float) level, cs) : new float[]{cs, 0};
                drawDropShot(buffers, m, light, cat, glass, look, shot[0], shot[1]);
            }
            drawIce(translucent, m, light, glass, look, s.seat(), sec, (float) level, action == JjanSession.PREPARING ? act : -1, turn * 3f);
            // 올드 패션드·모히토: 다 따른 뒤 잔 안을 바 스푼으로 젓는다
            if (look.after().equals("stir") && rel >= 0 && rel < JjanPour.STIR_TIME) {
                float k = (float) JjanPour.stirIn(rel), a = (float) rel * Mth.TWO_PI * 1.6f, r = (float) glass.cavityRadius(cs + 1) * 0.55f;
                if (k > 0.01f) {
                    m.pushPose();
                    m.translate(Mth.cos(a) * r, cs + 0.6f + (1 - k) * 10, Mth.sin(a) * r);
                    m.rotate(new Quaternionf().rotationXYZ(Mth.sin(a) * 0.15f, 0, -Mth.cos(a) * 0.15f));
                    m.scale(k, k, k);
                    JjanPourFx.spoon(solid, m.last(), light);
                    m.popPose();
                }
            }
            // 빨대: 위에서 쏙 꽂았다가, 다 마시면 뽑는다
            if (straw) {
                float down = smooth((float) act / 0.35f) * (1 - smooth(((float) act - 1.7f) / 0.35f));
                if (down > 0.02f) {
                    m.pushPose();
                    m.translate(-0.6f, cs + 0.3f + (1 - down) * 18, 0);
                    m.rotate(Axis.ZP.rotation(0.22f));
                    JjanPourFx.straw(solid, m.last(), light);
                    m.popPose();
                }
            }

            if (wet) {
                // 샴페인: 잔 바닥의 세 점에서 줄지어 오르는 기포 (술보다 먼저 그려 술 색에 물든다)
                if (look.carb() > 0 && look.bubble().equals("stream") && glass.transparent()) {
                    int[] bottom = glass.cavity()[cs];
                    float span = (float) level - cs;
                    for (int i = 0; i < 12 && bottom.length >= 2 && span > 0.8f; i++) {
                        int k = (int) (JjanGlassMesh.hash3(i % 3, 7, glass.id().length()) * (bottom.length / 2)) * 2;
                        float cycle = sec * 0.75f / Math.max(1f, span / 7.5f) + i * 0.0833f;
                        float phase = cycle - Mth.floor(cycle);
                        float by = cs + 0.2f + phase * (span - 0.45f), size = 0.3f + 0.2f * rnd(i, 3, s.seat());
                        float bx = bottom[k] + 0.5f + Mth.sin(sec * 9f + i) * 0.06f, bz = bottom[k + 1] + 0.5f;
                        JjanGlassMesh.box(translucent, e, bx - size / 2, by - size / 2, bz - size / 2, bx + size / 2, by + size / 2, bz + size / 2,
                                0xD8000000 | bubbleColor(look), light);
                    }
                }
                int a = look.opacity() >= 0.98 ? 255 : (int) (255 * Mth.clamp(look.opacity(), 0.24, 1.0));
                // 층으로 쌓은 술(B-52, 아이리시 커피)은 잔 높이 기준으로, 그 외 색 띠는 지금 액면 기준으로 (원본 rebuildLiquid)
                double span = look.layered() ? fullTop - cs : level - cs;
                int[] lut = JjanGlassMesh.lut(look);
                if (pouring && fx.plan != null) {
                    // 여러 술을 나눠 따르는 동안은 지금까지 섞인 색 한 가지로 보인다
                    int tint = JjanFx.tint(fx.plan, act);
                    if (tint >= 0) lut = uniform(tint & 0xFFFFFF);
                } else if (fx.plan != null && rel >= 0 && rel < JjanPour.SINK_TIME) {
                    // 데킬라 선라이즈: 그레나딘이 바닥에 깔리며 노을이 번진다
                    JjanCatalog.Step lastStep = look.pour().get(look.pour().size() - 1);
                    if (lastStep.sink()) lut = JjanFx.sinkLut(lut, lastStep.sinkFrom() >= 0 ? lastStep.sinkFrom() : look.color(), rel);
                }
                mesh.liquid(translucent, e, light, level, lut, span, a, look.cloudy() ? 0.07 : 0.03,
                        foamH, look.foamColor() & 0xFFFFFF, foamH > 0 ? Mth.floor(sec * 2.5f) : 0, swirl);
            }
            // 탄산: 잔 벽을 타고 오르는 작은 방울 (투명한 잔에서만 보인다). 흑맥주는 벽을 따라 내려간다.
            if (look.carb() > 0 && wet && glass.transparent() && !look.bubble().equals("stream")) {
                float span = (float) level - cs;
                // 탄산을 따르는 중이거나, 샷잔이 빠졌거나, 회오리가 돌면 방울이 확 늘어난다 (원작 bubbleBurst)
                float burst = JjanFx.bubbleBoost(fx, look, act, rel, dropHit, swirl);
                int n = Math.min(72, Math.round((float) look.carb() * 13f * burst * Math.max(1f, span) / 5.5f));
                boolean cascade = look.bubble().equals("cascade");
                int color = bubbleColor(look);
                for (int i = 0; i < n && span > 0.6f; i++) {
                    float cycle = time * (0.022f + 0.009f * (i % 4)) + i * 0.618f + s.seat() * 0.31f;
                    boolean down = cascade && rnd(i, 11, s.seat()) < 0.65f;
                    if (down) cycle *= 0.45f;
                    float phase = cycle - Mth.floor(cycle);
                    float by = down ? (float) level - 0.6f - phase * (span - 0.9f) : cs + 0.3f + phase * (span - 0.5f);
                    int layer = Mth.floor(by), count = mesh.sideCount(layer);
                    if (count == 0) continue;
                    int pick = Math.floorMod((i * 7919 + Mth.floor(cycle) * 104729 + s.seat() * 31) * 0x9E3779B1, count);
                    int ba = (int) (0xD9 * Mth.clamp(Math.min(phase * 6f, (1f - phase) * 5f), 0f, 1f));
                    mesh.bubble(translucent, e, layer, pick, by, 0.28f + 0.22f * rnd(i, 5, s.seat()), (ba << 24) | color, light);
                }
            }
            // 술 만드는 과정: 그릇·재료 병·얼음·셰이커·스푼·물줄기 (원작과 같은 동작)
            if (pouring && fx.plan != null) JjanPourFx.draw(buffers, m, light, cat, glass, fx.plan, act, (float) level, sec);
        } else {
            if (glass.transparent()) {
                mesh.shell(translucent, e, light, JjanGlassMesh.glassAlpha(glass), 0, false, wet ? level - 0.3 : -1, JjanGlassMesh.glassDim(look));
            } else {
                mesh.shell(buffers.getBuffer(RenderTypes.entityCutout(WHITE)), e, light, 1f, 0, true, -1, 1f);
            }
            // 뜨거운 음료: 잔 위로 피어오르며 옅어지는 김 (마시는 동안에는 잠깐 숨긴다)
            if (look.hot() && wet && !drinking) {
                float top = H - 0.5f;
                for (int i = 0; i < 4; i++) {
                    float cycle = time * 0.021f + i * 0.27f + s.seat() * 0.13f;
                    float phase = cycle - Mth.floor(cycle);
                    float sy = top + phase * 9f, size = 0.9f + phase * 1.3f;
                    float sx = Mth.sin(cycle * 6.3f + i * 2.1f) * (0.5f + phase * 1.2f), sz = Mth.cos(cycle * 5.1f + i * 1.3f) * (0.5f + phase * 1.2f);
                    int a = (int) (0x58 * Mth.clamp(Math.min(phase * 5f, 1f - phase), 0f, 1f));
                    if (a < 4) continue;
                    JjanGlassMesh.box(translucent, e, sx - size / 2, sy, sz - size / 2, sx + size / 2, sy + size, sz + size / 2, (a << 24) | 0xFAF6F0, light);
                }
            }
        }
        m.popPose();
    }

    /** 기포 색: 정해 둔 색이 없으면 흰색에 술 색을 조금 섞는다 (원본 glass.js). */
    private static int bubbleColor(JjanCatalog.Look look) {
        return look.bubbleColor() >= 0 ? look.bubbleColor() & 0xFFFFFF : JjanGlassMesh.mixLinear(0xFFFFFF, look.color() & 0xFFFFFF, 0.2);
    }

    /** 얼음: 네모 얼음은 잔 벽을 따라 둥글게, 동그란 얼음은 가운데 하나. 수면에 살짝 떠서 까딱거린다 (원본 updateIce). */
    private void drawIce(VertexConsumer vc, PoseStack m, int light, JjanCatalog.Glass glass, JjanCatalog.Look look, int seat, float sec, float level,
                         double dropT, float turn) {
        int n = look.ice();
        if (n <= 0) return;
        int cs = glass.cavityStart();
        boolean sphere = look.iceSphere();
        float size = sphere ? 4.9f : glass.cavityRadius(cs) >= 3 ? 2.6f : 2f;
        float cr = (float) glass.cavityRadius(cs + 1);
        if (cr < size * 0.5f) return;
        float ring = n == 1 ? 0f : Math.max(0f, cr - size * 0.72f);
        for (int i = 0; i < n; i++) {
            float a = (float) i / n * Mth.TWO_PI + 0.6f + turn, ph = i * 1.7f;
            float ix = Mth.cos(a) * ring, iz = Mth.sin(a) * ring;
            float floor = cs + size / 2 + i * 0.15f;
            float iy = Math.max(floor, level - size * 0.38f + Mth.sin(sec * 1.6f + ph) * 0.12f);
            float spin = 0f;
            if (dropT >= 0) {
                // 새 잔: 얼음이 위에서 하나씩 떨어진다 (원작 dropIce: 0.12초 뒤부터 0.17초 간격, 중력 320)
                double ft = dropT - (0.12 + i * 0.17);
                if (ft < 0) continue;
                float fy = (float) (glass.height() + 6 + i * 2.5 - 160 * ft * ft);
                if (fy > iy) {
                    iy = fy;
                    spin = fy * 0.15f;
                }
            }
            m.pushPose();
            m.translate(ix, iy, iz);
            m.rotate(new Quaternionf().rotationXYZ(Mth.sin(sec + ph) * 0.05f + spin, ph, 0f));
            PoseStack.Pose e = m.last();
            if (sphere) {
                JjanGlassMesh.iceSphere(vc, e, light, 0x90);
            } else {
                float h = size / 2;
                JjanGlassMesh.box(vc, e, -h, -h, -h, h, h, h, 0x90E4F5FB, light);
                JjanGlassMesh.box(vc, e, -h + 0.3f, h - 0.2f, -h + 0.3f, h - 0.3f, h + 0.1f, h - 0.3f, 0xB0FFFFFF, light);
            }
            m.popPose();
        }
    }

    /** 술에 떠다니는 것: 밥알·마시멜로·유자 껍질·민트·과일 조각·막걸리 앙금 (원본 updateFloaters). */
    private void drawFloaters(VertexConsumer vc, PoseStack m, int light, JjanCatalog cat, JjanCatalog.Glass glass, JjanCatalog.Look look,
                              int seat, float sec, float level, float fill) {
        JjanCatalog.Floater fl = cat.floater(look.floaters());
        if (fl == null || fill <= 0.03f) return;
        int cs = glass.cavityStart();
        float cr = (float) glass.cavityRadius(Mth.clamp(Mth.floor(level), cs, glass.height() - 1));
        float sx = (float) fl.scale()[0], sy = (float) fl.scale()[1], sz = (float) fl.scale()[2];
        float reach = Math.max(0.2f, cr - sx * 0.6f);
        for (int i = 0; i < fl.count(); i++) {
            float rot = rnd(i, 23, seat) * Mth.PI;
            float speed = (float) fl.speed() * (0.6f + 0.8f * rnd(i, 29, seat)) * (rnd(i, 31, seat) < 0.5f ? -1f : 1f);
            float a = rnd(i, 37, seat) * Mth.TWO_PI + speed * sec;
            float r = Mth.sqrt(rnd(i, 41, seat)) * 0.85f * reach;
            float depth = (float) Mth.lerp(rnd(i, 43, seat), fl.depthMin(), fl.depthMax());
            float fx = Mth.cos(a) * r, fz = Mth.sin(a) * r;
            float fy = Math.max(cs + sy * 0.5f, level - depth - sy * 0.3f + Mth.sin(sec * 1.3f + rot) * 0.08f);
            m.pushPose();
            m.translate(fx, fy, fz);
            m.rotate(Axis.YP.rotation(a + rot));
            int rgb = JjanGlassMesh.jitter(fl.colors()[i % fl.colors().length], i, seat, 3, 0.03);
            JjanGlassMesh.box(vc, m.last(), -sx / 2, -sy / 2, -sz / 2, sx / 2, sy / 2, sz / 2, 0xFF000000 | rgb, light);
            m.popPose();
        }
    }

    /** 폭탄주(양폭·소폭·예거밤·고진감래): 잔 바닥에 빠뜨린 작은 샷잔과 그 안의 술. */
    private void drawDropShot(GeoBuffers buffers, PoseStack m, int light, JjanCatalog cat, JjanCatalog.Glass glass, JjanCatalog.Look look,
                              float y, float rot) {
        if (!look.bomb().equals("drop") || look.bombShot() < 0) return;
        JjanCatalog.Glass shot = cat.glass("dropshot");
        if (shot == null || shot == glass) return;
        int cs = glass.cavityStart();
        float fit = (float) ((glass.cavityRadius(cs) - 0.2) / shot.rimR());
        if (fit <= 0.55f) return;
        fit = Math.min(1f, fit);
        m.pushPose();
        m.translate(0, y, 0);
        m.rotate(Axis.ZP.rotation(rot));
        m.scale(fit, fit, fit);
        PoseStack.Pose e = m.last();
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucent(WHITE));
        JjanGlassMesh mesh = JjanGlassMesh.of(shot);
        int rgb = look.bombShot() & 0xFFFFFF;
        mesh.liquid(vc, e, light, shot.height() - 1, new int[]{rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb, rgb},
                1, 255, 0.03, 0, 0, 0, 0);
        mesh.shell(vc, e, light, JjanGlassMesh.glassAlpha(shot), 0, false, -1, 1f);
        m.popPose();
    }

    private static int[] uniform(int rgb) {
        int[] lut = new int[17];
        java.util.Arrays.fill(lut, rgb);
        return lut;
    }
}
