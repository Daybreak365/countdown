package dev.newyear.countdown.client;

import dev.newyear.countdown.photo.PhotoBooth;
import dev.newyear.countdown.photo.PhotoBoothBlockEntity;
import dev.newyear.countdown.photo.PhotoBoothLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 포토부스 촬영 화면. 배경은 비워 두어 카메라 시점(실시간 미리보기)이 그대로 보이고, 가운데 밝은 틀이 사진 한 컷에 찍히는 영역이다.
 * 왼쪽은 포즈, 오른쪽은 프레임·필터, 아래는 나가기·안내·촬영 시작. 촬영 중에도 컷 사이에 포즈를 바꾸고 자리를 옮길 수 있다.
 * 나가기 버튼과 Esc 는 부스 밖으로 나간다(그 전에는 입구 커튼이 막혀 있다). 셔터 순간에는 아무것도 그리지 않아 UI 가 사진에 찍히지 않는다.
 */
public class PhotoBoothScreen extends Screen {
    private static final int SHADE = 0xC8140F19;
    private static final int PINK = 0xFFFF7FA6;
    private static final int INK = 0xFF4A2433;
    private static final int ROW = 18;

    private float cx, cy, cw, ch;
    private final List<Chip> frameChips = new ArrayList<>();

    public PhotoBoothScreen() {
        super(Component.translatable("photobooth.newyearcountdown.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component t(String key, Object... args) {
        return Component.translatable("photobooth.newyearcountdown." + key, args);
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        int[] c = PhotoBoothSession.cropPixels(mc.getWindow().getWidth(), mc.getWindow().getHeight(), gs);
        cx = c[0] / (float) gs;
        cy = c[1] / (float) gs;
        cw = c[2] / (float) gs;
        ch = c[3] / (float) gs;
        frameChips.clear();

        int top = PhotoBoothSession.BAR + 16;
        // 왼쪽: 포즈 (두 줄)
        int leftW = Math.max(60, (int) cx - 12);
        int colW = (leftW - 4) / 2;
        for (int i = 0; i < PhotoBooth.POSES; i++) {
            int idx = i;
            addRenderableWidget(new Chip(6 + (i % 2) * (colW + 4), top + (i / 2) * ROW, colW, 16,
                    t("pose." + PhotoPose.KEYS[i]), () -> PhotoBoothSession.myPose() == idx, () -> true,
                    () -> PhotoBoothSession.choosePose(idx)));
        }
        // 오른쪽: 프레임 (견본 + 이름) + 필터
        int rx = (int) (cx + cw) + 6, rw = Math.max(60, width - rx - 6);
        for (int i = 0; i < PhotoBooth.FRAMES; i++) {
            int idx = i;
            Chip chip = new Chip(rx, top + i * ROW, rw, 16, Component.literal("   ").append(t("frame." + PhotoFrames.ALL[i].key())),
                    () -> frame() == idx, this::canEdit, () -> PhotoBoothSession.chooseFrame(idx));
            frameChips.add(addRenderableWidget(chip));
        }
        int fy = top + PhotoBooth.FRAMES * ROW + 14;
        int fw = (rw - 8) / 3;
        for (int i = 0; i < PhotoBooth.FILTERS; i++) {
            int idx = i;
            addRenderableWidget(new Chip(rx + i * (fw + 4), fy, fw, 16, t("filter." + PhotoFrames.FILTERS[i]),
                    () -> PhotoBoothSession.filter() == idx, this::canEdit,
                    () -> PhotoBoothSession.chooseFilter(idx)));
        }

        // 아래: 나가기 · 촬영 시작
        int by = height - PhotoBoothSession.BAR + 6;
        addRenderableWidget(new Chip(6, by, 62, 18, t("exit"), () -> false, () -> true, PhotoBoothSession::leave));
        addRenderableWidget(new Chip(width - 6 - 88, by, 88, 18, t("shoot"), () -> true,
                () -> !PhotoBoothSession.isShooting() && !PhotoBoothSession.saving(), PhotoBoothSession::requestShoot));
    }

    private boolean canEdit() {
        return !PhotoBoothSession.isShooting() && !PhotoBoothSession.saving();
    }

    private int frame() {
        PhotoBoothBlockEntity be = PhotoBoothSession.booth(Minecraft.getInstance());
        return be == null ? 0 : be.frame();
    }

    /** Esc 도 나가기 버튼과 같다: 부스 밖으로 나간다. */
    @Override
    public void onClose() {
        PhotoBoothSession.leave();
    }

    @Override
    public void removed() {
        if (!PhotoBoothSession.switching()) PhotoBoothSession.exit(false);
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // 비워 둔다: 카메라 시점이 그대로 보이는 미리보기
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        if (PhotoBoothSession.hidingForCapture()) {
            PhotoBoothSession.frameRendered();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x0 = Math.round(cx), y0 = Math.round(cy), x1 = Math.round(cx + cw), y1 = Math.round(cy + ch);
        ctx.fill(0, 0, width, y0, SHADE);
        ctx.fill(0, y1, width, height, SHADE);
        ctx.fill(0, y0, x0, y1, SHADE);
        ctx.fill(x1, y0, width, y1, SHADE);
        corners(ctx, x0, y0, x1, y1);

        // 위: 제목 · 상태 · 인원
        ctx.text(font, t("title"), 8, 11, PINK, true);
        Component status;
        if (PhotoBoothSession.isShooting()) {
            status = t("shot_n", Math.min(PhotoBooth.SHOTS, PhotoBoothSession.shotsTaken() + 1), PhotoBooth.SHOTS);
        } else if (PhotoBoothSession.saving()) {
            status = t("developing");
        } else {
            status = t("hint");
        }
        ctx.centeredText(font, status, width / 2, 11, 0xFFFFFFFF);
        Component people = t("people", people(mc));
        ctx.text(font, people, width - 8 - font.width(people), 11, 0xFFE8E0F0, true);

        // 패널 제목, 아래 안내
        int top = PhotoBoothSession.BAR + 16;
        ctx.text(font, t("poses"), 8, top - 11, 0xFFFFD6E2, true);
        int rx = x1 + 6;
        ctx.text(font, t("frames"), rx + 2, top - 11, 0xFFFFD6E2, true);
        ctx.text(font, t("filter"), rx + 2, top + PhotoBooth.FRAMES * ROW + 3, 0xFFFFD6E2, true);
        ctx.centeredText(font, t("move_hint"), width / 2, height - PhotoBoothSession.BAR + 11, 0xFFBFB3C8);

        super.extractRenderState(ctx, mouseX, mouseY, delta);

        for (int i = 0; i < frameChips.size(); i++) {
            Chip chip = frameChips.get(i);
            swatch(ctx, chip.getX() + 5, chip.getY() + 1, PhotoFrames.ALL[i]);
        }
        overlay(ctx, mc, delta, x0, y0, x1, y1);
    }

    /** 촬영 틀 네 모서리 표시. */
    private static void corners(GuiGraphicsExtractor ctx, int x0, int y0, int x1, int y1) {
        int c = 0xE0FFFFFF, l = 12;
        ctx.fill(x0 - 2, y0 - 2, x0 + l, y0, c);
        ctx.fill(x0 - 2, y0 - 2, x0, y0 + l, c);
        ctx.fill(x1 - l, y0 - 2, x1 + 2, y0, c);
        ctx.fill(x1, y0 - 2, x1 + 2, y0 + l, c);
        ctx.fill(x0 - 2, y1, x0 + l, y1 + 2, c);
        ctx.fill(x0 - 2, y1 - l, x0, y1 + 2, c);
        ctx.fill(x1 - l, y1, x1 + 2, y1 + 2, c);
        ctx.fill(x1, y1 - l, x1 + 2, y1 + 2, c);
    }

    /** 프레임 견본: 작은 사진 띠 (배경색 + 네 칸). */
    private static void swatch(GuiGraphicsExtractor ctx, int x, int y, PhotoFrames.Frame f) {
        ctx.fillGradient(x, y, x + 8, y + 14, f.top(), f.bottom());
        int cell = f.border() == f.top() ? 0xFFEEEEEE : f.border();
        for (int i = 0; i < 4; i++) ctx.fill(x + 1, y + 1 + i * 3, x + 7, y + 3 + i * 3, cell);
    }

    private int people(Minecraft mc) {
        if (mc.level == null || PhotoBoothSession.pos() == null) return 0;
        PhotoBoothBlockEntity be = PhotoBoothSession.booth(mc);
        if (be == null) return 0;
        AABB box = PhotoBoothLayout.interior(PhotoBoothSession.pos(), be.facing()).inflate(0.05);
        int n = 0;
        for (Player p : mc.level.players()) if (!p.isSpectator() && box.contains(p.position().add(0, 0.1, 0))) n++;
        return n;
    }

    /** 카운트다운 숫자, 안내, 셔터 번쩍임. */
    private void overlay(GuiGraphicsExtractor ctx, Minecraft mc, float delta, int x0, int y0, int x1, int y1) {
        float flash = PhotoBoothSession.flash();
        if (flash > 0f) ctx.fill(x0, y0, x1, y1, ((int) (flash * 220) << 24) | 0xFFFFFF);
        if (!PhotoBoothSession.isShooting()) return;
        int n = PhotoBoothSession.shotsTaken();
        if (n >= PhotoBooth.SHOTS) return;
        float age = PhotoBoothSession.age(mc, delta);
        float left = (PhotoBooth.captureTick(n) - age) / 20f;
        int midX = (x0 + x1) / 2, midY = (y0 + y1) / 2;
        if (left > 3f) {
            Component msg = n == 0 ? t("ready") : t("next");
            big(ctx, font, msg, midX, y0 + 14, 2f, 0xFFFFFFFF);
        } else if (left > 0f) {
            String num = Integer.toString((int) Math.ceil(left));
            float frac = left - (float) Math.floor(left);
            float s = 6f + 2.5f * frac;
            big(ctx, font, Component.literal(num), midX, midY - (int) (4 * s), s, PINK);
        }
        // 찍은 컷 수: 작은 점 네 개
        for (int i = 0; i < PhotoBooth.SHOTS; i++) {
            int dx = midX - 26 + i * 14;
            ctx.fill(dx, y1 - 12, dx + 8, y1 - 6, i < n ? PINK : 0x80FFFFFF);
        }
    }

    private static void big(GuiGraphicsExtractor ctx, Font font, Component c, int x, int y, float s, int color) {
        ctx.pose().pushMatrix();
        ctx.pose().translate(x, y);
        ctx.pose().scale(s, s);
        ctx.text(font, c, -font.width(c) / 2, 0, color, true);
        ctx.pose().popMatrix();
    }

    /** 둥근 느낌의 선택 칩. */
    static final class Chip extends AbstractWidget {
        private final BooleanSupplier selected, enabled;
        private final Runnable action;

        Chip(int x, int y, int w, int h, Component label, BooleanSupplier selected, BooleanSupplier enabled, Runnable action) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.enabled = enabled;
            this.action = action;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
            active = enabled.getAsBoolean();
            boolean sel = selected.getAsBoolean();
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int bg = !active && !sel ? 0x90606068 : sel ? PINK : isHovered() ? 0xF0FFFFFF : 0xD8FFF4F8;
            int fg = !active && !sel ? 0xFFB8B8C0 : sel ? 0xFFFFFFFF : INK;
            ctx.fill(x + 1, y, x + w - 1, y + h, bg);
            ctx.fill(x, y + 1, x + w, y + h - 1, bg);
            if (sel) ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, 0x60FFFFFF);
            Font font = Minecraft.getInstance().font;
            Component m = getMessage();
            ctx.text(font, m, x + (w - font.width(m)) / 2, y + (h - 8) / 2, fg, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            action.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {
            defaultButtonNarrationText(builder);
        }
    }
}
