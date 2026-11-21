package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.jjan.JjanCatalog;
import dev.newyear.countdown.jjan.JjanPackets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * 짠 주문 화면 "오늘의 한 잔". "내 잔" 메뉴와 같은 나무색 판에 직접 그린다 (바닐라 버튼 없음):
 * 위에 분류 탭, 왼쪽에 술 목록(잔 그림과 이름), 오른쪽에 고른 술이 고른 잔에 담긴 모습·도수·설명,
 * 그 아래 상품과 잔을 ◀ ▶ 로 넘기는 줄(잔 이름을 누르면 추천 잔으로), 맨 아래 큰 주문 칸과 취소.
 * 목록을 두 번 누르거나 Enter 를 눌러도 주문한다. 판 바깥을 누르거나 Esc 로 닫는다.
 * 서버가 카탈로그로 다시 검증하며, 주문하면 지금 잔은 버려지고(취기에 들어가지 않음) 새 술이 준비된다. 닫아도 자리는 그대로다.
 */
public class JjanSelectScreen extends Screen {
    private static final int ROW = 22;
    // "내 잔" 메뉴와 같은 색
    private static final int PANEL = 0xF2241A14, BORDER = 0xFF6F4A2E, EDGE = 0xFF9A6A43, INSET = 0xFF140E0A;
    private static final int CREAM = 0xFFF4E7D0, DIM = 0xFFA89880, AMBER = 0xFFE0A02A;
    private static final int TILE = 0xFF3A281C, TILE_HOVER = 0xFF54392A;

    private final JjanCatalog cat = JjanCatalog.get();
    private String category;
    private JjanCatalog.Drink drink;
    private JjanCatalog.Variant variant;
    private JjanCatalog.Glass glass;
    private float scroll;
    private boolean placed;
    private int left, top, panelW, panelH, tabsY, tabW, listX, listY, listW, listH, infoX, infoW, variantY, glassY, orderY, orderW;

    public JjanSelectScreen(String drinkId, String variantId, String glassId) {
        super(Component.translatable("jjan.newyearcountdown.select.title"));
        drink = cat.drink(drinkId);
        if (drink == null) drink = cat.drinks().get(0);
        variant = drink.variant(variantId);
        glass = cat.glass(glassId);
        if (glass == null || glass.hidden()) glass = recommended();
        category = drink.category();
    }

    private JjanCatalog.Glass recommended() {
        JjanCatalog.Glass g = cat.glass(drink.glass());
        return g == null || g.hidden() ? cat.publicGlasses().get(0) : g;
    }

    /** 잔 순서: 추천 잔이 맨 앞. */
    private List<JjanCatalog.Glass> glasses() {
        JjanCatalog.Glass rec = recommended();
        List<JjanCatalog.Glass> list = new ArrayList<>(cat.publicGlasses());
        list.remove(rec);
        list.add(0, rec);
        return list;
    }

    private JjanCatalog.Look look() {
        return drink.look(variant);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 12, 392);
        panelH = Math.min(height - 8, 236);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        tabsY = top + 22;
        tabW = (panelW - 16) / Math.max(1, cat.categories().size());
        listX = left + 8;
        listY = top + 44;
        listW = Math.min(150, panelW * 2 / 5);
        listH = Math.max(ROW * 2, panelH - 44 - 8);
        infoX = listX + listW + 8;
        infoW = left + panelW - 8 - infoX;
        orderY = top + panelH - 8 - 22;
        glassY = orderY - 5 - 18;
        variantY = glassY - 2 - 18;
        orderW = infoW - 56;
        if (!placed) {   // 처음 열 때: 지난번에 고른 술이 목록 가운데 오게
            placed = true;
            scroll = Mth.clamp(cat.drinksIn(category).indexOf(drink) * ROW - (listH - ROW) / 2f, 0f, maxScroll());
        }
    }

    private void stepGlass(int d) {
        List<JjanCatalog.Glass> list = glasses();
        glass = list.get(Math.floorMod(list.indexOf(glass) + d, list.size()));
    }

    /** 상품 순서: 기본, 그다음 브랜드들. */
    private void stepVariant(int d) {
        List<JjanCatalog.Variant> vs = drink.variants();
        int i = Math.floorMod((variant == null ? 0 : vs.indexOf(variant) + 1) + d, vs.size() + 1);
        variant = i == 0 ? null : vs.get(i - 1);
    }

    private void pick(JjanCatalog.Drink d) {
        if (d == drink) return;
        drink = d;
        variant = null;
        glass = recommended();
    }

    private void order() {
        if (ClientPlayNetworking.canSend(JjanPackets.OrderC2S.ID)) {
            ClientPlayNetworking.send(new JjanPackets.OrderC2S(drink.id(), variant == null ? "" : variant.id(), glass.id()));
        }
        Minecraft.getInstance().gui.setScreen(null);
    }

    private float maxScroll() {
        return Math.max(0, cat.drinksIn(category).size() * ROW - listH);
    }

    private boolean hasVariants() {
        return !drink.variants().isEmpty();
    }

    // ------------------------------------------------------------------ 입력

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /** ◀ ▶ 줄에서 누른 곳: -1 = 왼쪽 화살표, 1 = 오른쪽, 0 = 가운데 이름, 그 밖은 Integer.MIN_VALUE. */
    private int stepperAt(double mx, double my, int y) {
        if (my < y || my >= y + 18 || mx < infoX + 30 || mx >= infoX + infoW) return Integer.MIN_VALUE;
        if (mx < infoX + 30 + 18) return -1;
        if (mx >= infoX + infoW - 18) return 1;
        return 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        // 분류 탭
        List<JjanCatalog.Category> cats = cat.categories();
        if (my >= tabsY && my < tabsY + 18 && mx >= left + 8) {
            int i = (int) ((mx - left - 8) / tabW);
            if (i >= 0 && i < cats.size()) {
                if (!cats.get(i).id().equals(category)) {
                    click();
                    category = cats.get(i).id();
                    scroll = 0;
                }
                return true;
            }
        }
        // 술 목록
        if (JjanUi.inside(mx, my, listX, listY, listW, listH)) {
            List<JjanCatalog.Drink> list = cat.drinksIn(category);
            int i = (int) ((my - listY + scroll) / ROW);
            if (i >= 0 && i < list.size()) {
                boolean same = list.get(i) == drink;
                if (!same) click();
                pick(list.get(i));
                if (doubleClick && same) order();
            }
            return true;
        }
        // 상품·잔
        int v = hasVariants() ? stepperAt(mx, my, variantY) : Integer.MIN_VALUE;
        if (v != Integer.MIN_VALUE) {
            click();
            stepVariant(v == 0 ? 1 : v);
            return true;
        }
        int g = stepperAt(mx, my, glassY);
        if (g != Integer.MIN_VALUE) {
            click();
            if (g == 0) glass = recommended();
            else stepGlass(g);
            return true;
        }
        // 주문·취소
        if (JjanUi.inside(mx, my, infoX, orderY, orderW, 22)) {
            click();
            order();
            return true;
        }
        if (JjanUi.inside(mx, my, infoX + orderW + 4, orderY, infoW - orderW - 4, 22)) {
            click();
            onClose();
            return true;
        }
        if (!JjanUi.inside(mx, my, left, top, panelW, panelH)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        scroll = Mth.clamp(scroll - (float) dy * ROW, 0f, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            order();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ 그리기

    /** 작은 세모 화살표 (dir -1 = ◀, 1 = ▶) 를 칸 (x, y, 18, 18) 가운데에. */
    private static void arrow(GuiGraphicsExtractor ctx, int x, int y, int dir, int color) {
        for (int i = 0; i < 4; i++) {
            int col = dir < 0 ? x + 6 + i : x + 11 - i;
            ctx.fill(col, y + 8 - i, col + 1, y + 10 + i, color);
        }
    }

    /** "이름표  ◀  값  ▶" 한 줄. */
    private void stepper(GuiGraphicsExtractor ctx, int mx, int my, int y, Component label, Component value, boolean highlight) {
        int at = stepperAt(mx, my, y);
        ctx.text(font, label, infoX, y + 5, DIM, false);
        int x0 = infoX + 30, x1 = infoX + infoW;
        ctx.fill(x0, y, x1, y + 18, INSET);
        ctx.outline(x0, y, x1 - x0, 18, BORDER);
        ctx.fill(x0 + 1, y + 1, x0 + 18, y + 17, at == -1 ? TILE_HOVER : TILE);
        ctx.fill(x1 - 18, y + 1, x1 - 1, y + 17, at == 1 ? TILE_HOVER : TILE);
        arrow(ctx, x0, y, -1, at == -1 ? CREAM : DIM);
        arrow(ctx, x1 - 18, y, 1, at == 1 ? CREAM : DIM);
        if (at == 0) ctx.fill(x0 + 18, y + 1, x1 - 18, y + 17, 0xFF241A14);
        String text = font.plainSubstrByWidth(value.getString(), x1 - x0 - 44);
        ctx.centeredText(font, Component.literal(text), (x0 + x1) / 2, y + 5, highlight ? AMBER : CREAM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        // 판
        ctx.fill(left, top, left + panelW, top + panelH, PANEL);
        ctx.outline(left, top, panelW, panelH, BORDER);
        ctx.outline(left + 1, top + 1, panelW - 2, panelH - 2, 0x409A6A43);
        ctx.fill(left + 2, top + 2, left + panelW - 2, top + 17, 0xFF33241A);
        ctx.fill(left + 2, top + 17, left + panelW - 2, top + 18, EDGE);
        ctx.text(font, title, left + 8, top + 6, CREAM, false);
        Component esc = Component.translatable("jjan.newyearcountdown.menu.esc");
        ctx.text(font, esc, left + panelW - 8 - font.width(esc), top + 6, DIM, false);

        // 분류 탭: 고른 탭은 밝은 글씨와 호박색 밑줄
        List<JjanCatalog.Category> cats = cat.categories();
        for (int i = 0; i < cats.size(); i++) {
            int x = left + 8 + i * tabW;
            boolean sel = cats.get(i).id().equals(category), hov = JjanUi.inside(mouseX, mouseY, x, tabsY, tabW, 18);
            ctx.fill(x, tabsY, x + tabW - 2, tabsY + 18, sel ? TILE_HOVER : hov ? TILE : INSET);
            if (sel) ctx.fill(x, tabsY + 16, x + tabW - 2, tabsY + 18, AMBER);
            ctx.centeredText(font, Component.literal(font.plainSubstrByWidth(cats.get(i).name(), tabW - 4)), x + (tabW - 2) / 2, tabsY + 5, sel ? CREAM : hov ? CREAM : DIM);
        }

        // 왼쪽: 술 목록
        ctx.fill(listX, listY, listX + listW, listY + listH, INSET);
        List<JjanCatalog.Drink> list = cat.drinksIn(category);
        scroll = Mth.clamp(scroll, 0f, maxScroll());
        boolean over = JjanUi.inside(mouseX, mouseY, listX, listY, listW, listH);
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < list.size(); i++) {
            int y = listY + i * ROW - Math.round(scroll);
            if (y + ROW < listY || y > listY + listH) continue;
            JjanCatalog.Drink d = list.get(i);
            boolean sel = d == drink;
            if (sel) {
                ctx.fill(listX, y, listX + listW - 4, y + ROW, TILE_HOVER);
                ctx.fill(listX, y, listX + 2, y + ROW, AMBER);
            } else if (over && JjanUi.inside(mouseX, mouseY, listX, y, listW - 4, ROW)) {
                ctx.fill(listX, y, listX + listW - 4, y + ROW, TILE);
            }
            JjanCatalog.Glass g = cat.glass(d.glass());
            if (g != null) JjanUi.thumb(ctx, g, d.look(), listX + 14, y + ROW - 2, 16, ROW - 4);
            ctx.text(font, font.plainSubstrByWidth(d.name(), listW - 34), listX + 27, y + 7, sel ? CREAM : 0xFFD8C8B0, false);
        }
        ctx.disableScissor();
        ctx.outline(listX, listY, listW, listH, BORDER);
        if (maxScroll() > 0) {
            int barH = Math.max(12, listH * listH / (list.size() * ROW));
            int barY = listY + 1 + Math.round((listH - 2 - barH) * scroll / maxScroll());
            ctx.fill(listX + listW - 4, listY + 1, listX + listW - 1, listY + listH - 1, 0xFF241A14);
            ctx.fill(listX + listW - 4, barY, listX + listW - 1, barY + barH, EDGE);
        }

        // 오른쪽: 고른 술이 고른 잔에 담긴 모습, 이름, 도수·양, 설명
        JjanCatalog.Look look = look();
        int controlsTop = hasVariants() ? variantY : glassY;
        int thumbH = Mth.clamp(controlsTop - listY - 50 - 6, 0, 84);
        int y = listY;
        if (thumbH >= 24) {
            ctx.fill(infoX, y, infoX + infoW, y + thumbH + 4, INSET);
            ctx.outline(infoX, y, infoW, thumbH + 4, BORDER);
            // 탁자 한 줄
            ctx.fill(infoX + 1, y + thumbH - 1, infoX + infoW - 1, y + thumbH + 3, 0xFF5F3D26);
            ctx.fill(infoX + 1, y + thumbH - 1, infoX + infoW - 1, y + thumbH, 0xFF8F6240);
            JjanUi.thumb(ctx, glass, look, infoX + infoW / 2f, y + thumbH - 1, infoW - 16, thumbH - 6);
            y += thumbH + 8;
        }
        ctx.text(font, font.plainSubstrByWidth(variant == null ? drink.name() : variant.name(), infoW), infoX, y, CREAM, false);
        int ml = glass.capacity(look.fill());
        Component info = look.abv() <= 0 ? Component.translatable("jjan.newyearcountdown.select.info_soft", ml)
                : Component.translatable("jjan.newyearcountdown.select.info", trim(look.abv()), ml);
        ctx.text(font, info, infoX, y + 11, AMBER, false);
        List<FormattedCharSequence> lines = font.split(Component.literal(variant == null ? drink.desc() : variant.desc()), infoW);
        int room = Math.max(1, (controlsTop - 4 - (y + 23)) / 10);
        for (int l = 0; l < Math.min(room, lines.size()); l++) ctx.text(font, lines.get(l), infoX, y + 23 + l * 10, DIM, false);

        // 상품·잔 고르기
        if (hasVariants()) {
            Component v = variant == null ? Component.translatable("jjan.newyearcountdown.select.base") : Component.literal(variant.name());
            stepper(ctx, mouseX, mouseY, variantY, Component.translatable("jjan.newyearcountdown.select.variant_label"), v, false);
        }
        boolean rec = glass == recommended();
        Component g = Component.literal(glass.name());
        if (rec) g = Component.translatable("jjan.newyearcountdown.select.glass_recommended", g);
        stepper(ctx, mouseX, mouseY, glassY, Component.translatable("jjan.newyearcountdown.select.glass_label"), g, rec);

        // 주문 · 취소
        boolean hov = JjanUi.inside(mouseX, mouseY, infoX, orderY, orderW, 22);
        ctx.fill(infoX, orderY, infoX + orderW, orderY + 22, hov ? 0xFFF0B642 : AMBER);
        ctx.outline(infoX, orderY, orderW, 22, hov ? CREAM : 0xFF8A5A12);
        ctx.centeredText(font, Component.translatable("jjan.newyearcountdown.select.order_enter"), infoX + orderW / 2, orderY + 7, 0xFF2A1A08);
        int cx = infoX + orderW + 4, cw = infoW - orderW - 4;
        boolean chov = JjanUi.inside(mouseX, mouseY, cx, orderY, cw, 22);
        ctx.fill(cx, orderY, cx + cw, orderY + 22, chov ? TILE_HOVER : TILE);
        ctx.outline(cx, orderY, cw, 22, chov ? CREAM : BORDER);
        ctx.centeredText(font, CommonComponents.GUI_CANCEL, cx + cw / 2, orderY + 7, chov ? CREAM : DIM);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
