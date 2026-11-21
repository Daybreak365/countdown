package dev.newyear.countdown.client;

import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanPackets;
import dev.newyear.countdown.jjan.JjanRules;
import dev.newyear.countdown.jjan.JjanSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * 짠 개인 메뉴 "내 잔" (자기 잔 받침 우클릭 또는 메뉴 키). 바닐라 버튼 대신 직접 그린 한 장짜리 판이다:
 * 왼쪽에는 지금 내 잔(남은 양이 그대로 보인다)과 취기, 오른쪽에는 그림이 붙은 큰 칸 넷(한 모금·원샷·채우기·잔 들기)과 술 바꾸기,
 * 아래에는 안주 14종이 그림으로 늘어서 있어 눌러서 바로 고른다. 할 수 없는 칸은 흐리게 보인다.
 * 모든 칸은 요청만 보낸다. 가능한지는 서버가 판단한다.
 */
public class JjanMenuScreen extends Screen {
    private static final int W = 268, H = 178;
    // 술집 나무색 판
    private static final int PANEL = 0xF2241A14, BORDER = 0xFF6F4A2E, EDGE = 0xFF9A6A43, INSET = 0xFF140E0A;
    private static final int CREAM = 0xFFF4E7D0, DIM = 0xFFA89880, AMBER = 0xFFE0A02A;
    private static final int TILE = 0xFF3A281C, TILE_HOVER = 0xFF54392A, TILE_OFF = 0xFF2A211C;

    private static final String[] ICON_SIP = {"#.....#..", "#.....#..", "#ooooo#..", "#ooooo#..", ".#ooo#...", ".#ooo#...", "..###....", "...#.....", "..###...."};
    private static final String[] ICON_SHOT = {"#.....#.o", "#.....#.o", "#.....#.o", "#.....#..", ".#ooo#..o", ".#ooo#...", "..###....", "...#.....", "..###...."};
    private static final String[] ICON_REFILL = {"...##....", "...##....", "..####...", "..#oo#...", "..#oo#...", "..#oo#...", "..#oo#...", "..#oo#...", "..####..."};
    private static final String[] ICON_UP = {"....#....", "...###...", "..#####..", ".#######.", "...###...", "...###...", "...###...", "...###...", "...###..."};
    private static final String[] ICON_DOWN = {"...###...", "...###...", "...###...", "...###...", "...###...", ".#######.", "..#####..", "...###...", "....#...."};
    private static final String[] ICON_CHANGE = {"..#......", ".#######.", "..#......", ".........", "......#..", ".#######.", "......#..", ".........", "........."};

    /** 누를 수 있는 칸 하나. */
    private static final class Tile {
        final int x, y, w, h;
        final Runnable action;
        final BooleanSupplier active;

        Tile(int x, int y, int w, int h, BooleanSupplier active, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.active = active;
            this.action = action;
        }

        boolean over(double mx, double my) {
            return JjanUi.inside(mx, my, x, y, w, h);
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private Tile sip, shot, refill, raise, change;
    private int left, top, anjuX, anjuY;

    public JjanMenuScreen() {
        super(Component.translatable("jjan.newyearcountdown.menu.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void act(int action, int arg, boolean close) {
        JjanClient.send(action, arg);
        if (close) Minecraft.getInstance().gui.setScreen(null);
    }

    private static JjanPackets.SeatSnap snap() {
        JjanClient.Seated me = JjanClient.mine();
        return me == null ? null : me.snap();
    }

    private static boolean ordered() {
        JjanPackets.SeatSnap s = snap();
        return s != null && s.glass() >= 0;
    }

    private static boolean idle() {
        JjanPackets.SeatSnap s = snap();
        return s != null && s.glass() >= 0 && s.action() == JjanSession.IDLE;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(2, (height - H) / 2);
        tiles.clear();
        int x = left + 100, y = top + 22;
        sip = add(x, y, 78, 30, () -> idle() && snap().fill() > JjanRules.EMPTY, () -> act(JjanPackets.SIP, 0, true));
        shot = add(x + 82, y, 78, 30, () -> idle() && snap().fill() > JjanRules.EMPTY, () -> act(JjanPackets.SHOT, 0, true));
        refill = add(x, y + 34, 78, 30, () -> idle() && snap().fill() <= JjanRules.REFILL_FULL, () -> act(JjanPackets.REFILL, 0, true));
        raise = add(x + 82, y + 34, 78, 30, JjanMenuScreen::idle, () -> act(JjanPackets.RAISE, 0, true));
        change = add(x, y + 68, 160, 22, () -> !ordered() || idle(), () -> act(JjanPackets.SELECT, 0, false));
        anjuX = left + 8;
        anjuY = top + 152;
    }

    private Tile add(int x, int y, int w, int h, BooleanSupplier active, Runnable action) {
        Tile t = new Tile(x, y, w, h, active, action);
        tiles.add(t);
        return t;
    }

    @Override
    public void tick() {
        super.tick();
        if (!JjanClient.seated()) Minecraft.getInstance().gui.setScreen(null);
    }

    // ------------------------------------------------------------------ 입력

    private int anjuAt(double mx, double my) {
        int n = JjanCatalog.get().anju().size();
        if (n == 0 || !ordered() || my < anjuY || my >= anjuY + 18) return -1;
        int i = (int) Math.floor((mx - anjuX) / 18.0);
        return i >= 0 && i < n && mx - anjuX - i * 18 < 17 ? i : -1;
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        for (Tile t : tiles) {
            if (!t.over(mx, my)) continue;
            if (t.active.getAsBoolean()) {
                click();
                t.action.run();
            }
            return true;
        }
        int a = anjuAt(mx, my);
        if (a >= 0) {
            click();
            JjanClient.send(JjanPackets.ANJU, a);
            return true;
        }
        // 판 바깥을 누르면 닫는다
        if (!JjanUi.inside(mx, my, left, top, W, H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // ------------------------------------------------------------------ 그리기

    private static void icon(GuiGraphicsExtractor ctx, String[] rows, int x, int y, int line, int fill) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                if (ch != '.') ctx.fill(x + c * 2, y + r * 2, x + c * 2 + 2, y + r * 2 + 2, ch == '#' ? line : fill);
            }
        }
    }

    private void tile(GuiGraphicsExtractor ctx, Tile t, int mx, int my, String[] icon, Component label, Component hint) {
        boolean on = t.active.getAsBoolean(), hover = on && t.over(mx, my);
        ctx.fill(t.x, t.y, t.x + t.w, t.y + t.h, on ? (hover ? TILE_HOVER : TILE) : TILE_OFF);
        ctx.outline(t.x, t.y, t.w, t.h, hover ? CREAM : on ? BORDER : 0xFF3A2E26);
        int iy = t.y + (t.h - 18) / 2;
        icon(ctx, icon, t.x + 6, iy, on ? CREAM : 0xFF6A5E52, on ? AMBER : 0xFF5A4A36);
        int tx = t.x + 30;
        if (hint == null) {
            ctx.text(font, label, tx, t.y + (t.h - 8) / 2, on ? CREAM : 0xFF7A6E62, false);
        } else {
            ctx.text(font, label, tx, t.y + 6, on ? CREAM : 0xFF7A6E62, false);
            ctx.text(font, hint, tx, t.y + 17, on ? DIM : 0xFF5A5048, false);
        }
    }

    // 안주 그림: 위에서 내려다본 모습. 낮은 상자부터 칠해 높은 것이 위에 온다
    private static final Map<JjanCatalog.Anju, List<int[]>> TOP_DOWN = new HashMap<>();

    private static List<int[]> topDown(JjanCatalog.Anju a) {
        return TOP_DOWN.computeIfAbsent(a, k -> {
            List<float[]> boxes = new ArrayList<>();
            List<float[]> all = new ArrayList<>(a.pieces());
            all.add(a.plate());
            for (float[] b : all) for (int i = 0; i + 6 < b.length; i += 7) boxes.add(new float[]{b[i], b[i + 2], b[i + 3], b[i + 5], b[i + 4], b[i + 6]});
            boxes.sort(Comparator.comparingDouble(b -> b[4]));
            List<int[]> out = new ArrayList<>();
            for (float[] b : boxes) {
                int x0 = Math.round(b[0] * 4), z0 = Math.round(b[1] * 4), x1 = Math.round(b[2] * 4), z1 = Math.round(b[3] * 4);
                out.add(new int[]{x0, z0, Math.max(x1, x0 + 2), Math.max(z1, z0 + 2), 0xFF000000 | (int) b[5]});
            }
            return out;
        });
    }

    /** 안주를 (cx, cy) 가운데, 한 변 size 픽셀 안에 그린다. */
    static void anjuIcon(GuiGraphicsExtractor ctx, JjanCatalog.Anju a, float cx, float cy, float size) {
        float k = size / (24f * 4f);   // 접시는 지름 24복셀 안쪽, 좌표는 4배로 키운 정수
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx, cy);
        ctx.pose().scale(k, k);
        for (int[] r : topDown(a)) ctx.fill(r[0], r[1], r[2], r[3], r[4]);
        ctx.pose().popMatrix();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        JjanCatalog cat = JjanCatalog.get();
        JjanPackets.SeatSnap s = snap();
        boolean ordered = ordered();

        // 판
        ctx.fill(left, top, left + W, top + H, PANEL);
        ctx.outline(left, top, W, H, BORDER);
        ctx.outline(left + 1, top + 1, W - 2, H - 2, 0x409A6A43);
        ctx.fill(left + 2, top + 2, left + W - 2, top + 17, 0xFF33241A);
        ctx.fill(left + 2, top + 17, left + W - 2, top + 18, EDGE);
        ctx.text(font, title, left + 8, top + 6, CREAM, false);
        Component esc = Component.translatable("jjan.newyearcountdown.menu.esc");
        ctx.text(font, esc, left + W - 8 - font.width(esc), top + 6, DIM, false);

        // 왼쪽: 내 잔
        int cx = left + 8, cy = top + 22, cw = 84, ch = 90;
        ctx.fill(cx, cy, cx + cw, cy + ch, INSET);
        ctx.outline(cx, cy, cw, ch, BORDER);
        JjanCatalog.Drink drink = ordered && s.drink() >= 0 && s.drink() < cat.drinks().size() ? cat.drinks().get(s.drink()) : null;
        if (drink != null && s.glass() < cat.glasses().size()) {
            JjanCatalog.Glass glass = cat.glasses().get(s.glass());
            boolean v = s.variant() >= 0 && s.variant() < drink.variants().size();
            JjanCatalog.Look look = v ? drink.variants().get(s.variant()).look() : drink.look();
            JjanUi.thumb(ctx, glass, look, cx + cw / 2f, cy + 56, cw - 20, 48, s.fill());
            String name = v ? drink.variants().get(s.variant()).name() : drink.name();
            ctx.centeredText(font, Component.literal(font.plainSubstrByWidth(name, cw - 6)), cx + cw / 2, cy + 62, CREAM);
            // 남은 양
            int bx = cx + 8, by = cy + 76, bw = cw - 16 - 26;
            ctx.fill(bx, by, bx + bw, by + 6, 0xFF000000);
            int rgb = look.color() & 0xFFFFFF;
            ctx.fill(bx + 1, by + 1, bx + 1 + Math.round((bw - 2) * Math.max(0f, Math.min(1f, s.fill()))), by + 5, 0xFF000000 | rgb);
            String pct = Math.round(s.fill() * 100) + "%";
            ctx.text(font, pct, cx + cw - 8 - font.width(pct), by - 1, CREAM, false);
        } else {
            Component none = Component.translatable("jjan.newyearcountdown.menu.no_drink");
            int ty = cy + ch / 2 - 10;
            for (var line : font.split(none, cw - 10)) {
                ctx.text(font, line, cx + (cw - font.width(line)) / 2, ty, DIM, false);
                ty += 10;
            }
        }
        // 취기
        Minecraft mc = Minecraft.getInstance();
        float level = mc.player == null ? 0f : JjanClient.intox(mc.player.getUUID());
        JjanUi.tipsy(ctx, font, cx + cw, cy + ch + 5, level);

        // 오른쪽: 할 수 있는 일
        tile(ctx, sip, mouseX, mouseY, ICON_SIP, Component.translatable("jjan.newyearcountdown.menu.sip"), mc.options.keyAttack.getTranslatedKeyMessage());
        tile(ctx, shot, mouseX, mouseY, ICON_SHOT, Component.translatable("jjan.newyearcountdown.menu.shot"), Component.translatable("jjan.newyearcountdown.menu.shot_hint"));
        tile(ctx, refill, mouseX, mouseY, ICON_REFILL, Component.translatable("jjan.newyearcountdown.menu.refill"), null);
        boolean raised = idle() && s.pose() == JjanSession.RAISED;
        tile(ctx, raise, mouseX, mouseY, raised ? ICON_DOWN : ICON_UP,
                Component.translatable(raised ? "jjan.newyearcountdown.menu.lower_short" : "jjan.newyearcountdown.menu.raise"), JjanClient.raiseKeyName());
        tile(ctx, change, mouseX, mouseY, ICON_CHANGE, Component.translatable(ordered ? "jjan.newyearcountdown.menu.change" : "jjan.newyearcountdown.menu.order"), null);

        // 아래: 안주 고르기
        List<JjanCatalog.Anju> all = cat.anju();
        int hover = anjuAt(mouseX, mouseY);
        int current = ordered ? s.anjuId() : -1;
        ctx.fill(left + 2, anjuY - 16, left + W - 2, anjuY - 15, 0xFF4A3424);
        Component head = Component.translatable("jjan.newyearcountdown.menu.anju_head");
        ctx.text(font, head, anjuX, anjuY - 11, DIM, false);
        int shown = hover >= 0 ? hover : current;
        if (!ordered) {
            ctx.text(font, Component.translatable("jjan.newyearcountdown.menu.anju_none"), anjuX + font.width(head) + 6, anjuY - 11, 0xFF7A6E62, false);
        } else if (shown >= 0 && shown < all.size()) {
            ctx.text(font, all.get(shown).name(), anjuX + font.width(head) + 6, anjuY - 11, hover >= 0 && hover != current ? AMBER : CREAM, false);
        }
        for (int i = 0; i < all.size(); i++) {
            int x = anjuX + i * 18;
            boolean sel = i == current, hov = i == hover;
            ctx.fill(x, anjuY, x + 17, anjuY + 17, !ordered ? TILE_OFF : hov ? TILE_HOVER : sel ? 0xFF4A3424 : INSET);
            anjuIcon(ctx, all.get(i), x + 8.5f, anjuY + 8.5f, 15f);
            if (!ordered) ctx.fill(x, anjuY, x + 17, anjuY + 17, 0xA0241A14);
            ctx.outline(x, anjuY, 17, 17, sel ? CREAM : hov ? AMBER : 0xFF3A2E26);
        }
    }
}
