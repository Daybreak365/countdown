package dev.newyear.countdown.client.ai;

import dev.newyear.countdown.ai.Josa;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 소원 HUD: 진행 중인 과제·능력·저주·타이머, 알림 토스트, 시네마틱 연출.
 * 화면 오른쪽 가장자리에 붙어 바닐라 HUD(체력·핫바·상태효과 아이콘)와 겹치지 않게 하고, GUI 배율에 맞춰 그린다.
 */
public final class AiHud {
    private AiHud() {}

    /** J 키로 접기/펼치기. */
    public static boolean collapsed;
    public static String openKeyName = "G";

    private static final int MAX_OBJECTIVES = 5;

    public static void render(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        RuntimeHud.render(g);
        CooldownHud.render(g);
        BuildHologram.renderHud(g);
        Font f = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        boolean screenOpen = mc.gui.screen() != null;

        drawCinematic(g, f, sw, sh);
        // 오른쪽 한 줄: 바닐라 토스트(오른쪽 위)와 겹치지 않도록 화면 1/3 지점부터 알림 → 능력·과제 순으로 쌓는다
        int top = Math.max(110, Math.round(sh * 0.38f));
        int y = drawToasts(g, f, sw, top);
        if (!screenOpen) drawSidebar(g, f, sw, sh, y);
        if (!screenOpen) drawIndicators(g, f, sw, sh);
    }

    // ---------------------------------------------------------------- 오른쪽 패널

    private static void drawSidebar(GuiGraphicsExtractor g, Font f, int sw, int sh, int startY) {
        List<AiClientState.Status> statuses = new ArrayList<>(AiClientState.statuses.values());
        List<AiClientState.Quest> quests = AiClientState.sortedQuests(true);
        if (statuses.isEmpty() && quests.isEmpty()) return;
        int w = Math.min(160, sw / 3);
        int x1 = sw - 4, x0 = x1 - w;
        int y = startY;

        // 머리글
        WishUi.twinkle(g, x0 + 6, y + 4, WishUi.now() * 2.5f, WishUi.SNOW);
        g.text(f, AiClientState.spiritName + (collapsed ? " (접힘)" : ""), x0 + 13, y, WishUi.GOLD, true);
        y += 11;

        for (AiClientState.Status s : statuses) {
            if (y > sh - 70) break;
            y = drawStatus(g, f, s, x0, x1, y) + 2;
        }
        boolean first = true;
        for (AiClientState.Quest q : quests) {
            if (y > sh - 70) break;
            y = drawQuest(g, f, q, x0, x1, y, first) + 2;
            first = false;
        }
    }

    /** 능력·저주·타이머 한 줄. 반환: 아래쪽 y. */
    private static int drawStatus(GuiGraphicsExtractor g, Font f, AiClientState.Status s, int x0, int x1, int y) {
        int w = x1 - x0;
        int accent = WishUi.kindColor(s.kind);
        int rowH = collapsed ? 12 : 14;
        WishUi.softPanel(g, x0, y, x1, y + rowH, accent, 1f);
        DynamicTextures.Tex tex = DynamicTextures.get(s.texture);
        if (tex != null) g.blit(tex.location, x0 + 4, y + 2, x0 + 13, y + 11, 0f, 1f, 0f, 1f);
        else WishUi.pixelIcon(g, s.pixels, s.palette, x0 + 4, y + 2, 9, accent, 1f);
        g.text(f, f.plainSubstrByWidth(s.label, w - 52), x0 + 16, y + 2, WishUi.TEXT, false);
        String right = s.counter == null ? "" : s.counter;
        long rem = AiClientState.remaining(s.deadlineLocal);
        if (rem >= 0) right = (right.isEmpty() ? "" : right + " ") + WishUi.time(rem);
        if (!right.isEmpty()) g.text(f, right, x1 - 4 - f.width(right), y + 2, rem >= 0 && rem < 10_000 ? WishUi.DANGER : WishUi.TEXT_DIM, false);
        if (!collapsed && rem >= 0 && s.totalMs > 0) WishUi.bar(g, x0 + 16, y + 11, w - 20, 2, (float) rem / s.totalMs, accent, 1f);
        if ("curse".equals(s.kind)) WishUi.twinkle(g, x0 + 8, y + 6, WishUi.now() * 2, WishUi.CURSE);
        return y + rowH;
    }

    /** 과제 한 칸 (맨 위 과제나 펼친 과제는 목표까지). 반환: 아래쪽 y. */
    private static int drawQuest(GuiGraphicsExtractor g, Font f, AiClientState.Quest q, int x0, int x1, int y, boolean first) {
        int w = x1 - x0;
        boolean expand = !collapsed && (first || q.expanded) && q.active();
        int accent = q.active() ? WishUi.GOLD : "COMPLETED".equals(q.state) ? WishUi.SUCCESS : WishUi.DANGER;
        int h = 13 + (expand ? Math.min(MAX_OBJECTIVES, q.objectives.size()) * 11 + 2 : 0);
        WishUi.softPanel(g, x0, y, x1, y + h, accent, 1f);
        g.text(f, f.plainSubstrByWidth(WishUi.GLYPH + " " + q.title, w - 44), x0 + 4, y + 2, accent, false);
        long rem = AiClientState.remaining(q.deadlineLocal);
        String right = !q.active() ? WishUi.stateKo(q.state) : rem >= 0 ? WishUi.time(rem) : Math.round(q.fraction() * 100) + "%";
        g.text(f, right, x1 - 4 - f.width(right), y + 2, rem >= 0 && rem < 30_000 && q.active() ? WishUi.DANGER : WishUi.TEXT_DIM, false);
        if (expand) {
            int oy = y + 12;
            for (int n = 0; n < q.objectives.size() && n < MAX_OBJECTIVES; n++) {
                AiClientState.Objective o = q.objectives.get(n);
                o.shown += (o.fraction() - o.shown) * 0.12f;
                g.text(f, f.plainSubstrByWidth((o.done ? "■ " : "□ ") + o.text, w - 44), x0 + 7, oy + 1, o.done ? WishUi.SUCCESS : WishUi.TEXT, false);
                String num = (int) o.progress + "/" + (int) o.target;
                g.text(f, num, x1 - 4 - f.width(num), oy + 1, WishUi.TEXT_DIM, false);
                WishUi.bar(g, x0 + 7, oy + 9, w - 12, 2, o.shown, o.done ? WishUi.SUCCESS : WishUi.GOLD, 1f);
                oy += 11;
            }
        } else if (!collapsed && q.active()) {
            WishUi.bar(g, x0 + 4, y + 10, w - 8, 2, (float) q.fraction(), accent, 1f);
        }
        return y + h;
    }

    // ---------------------------------------------------------------- 알림

    /** 반환: 알림 아래 다음 y. */
    private static int drawToasts(GuiGraphicsExtractor g, Font f, int sw, int startY) {
        long now = System.currentTimeMillis();
        int y = startY;
        Iterator<AiClientState.Toast> it = AiClientState.toasts.iterator();
        while (it.hasNext()) {
            AiClientState.Toast t = it.next();
            long age = now - t.start;
            if (age > t.durationMs + 400) {
                it.remove();
                continue;
            }
            float in = WishUi.easeOut(age / 300f);
            float out = age > t.durationMs ? 1 - (age - t.durationMs) / 400f : 1;
            float a = Math.min(in, out);
            int w = Math.min(200, sw / 2);
            List<FormattedCharSequence> body = WishUi.wrap(f, t.text, w - 16);
            int lines = Math.min(4, body.size());
            boolean titled = t.title != null && !t.title.isEmpty();
            int h = 13 + (titled ? 11 : 0) + lines * 10;
            int x1 = sw - 6 + Math.round((1 - in) * (w + 10)), x0 = x1 - w;
            WishUi.panel(g, x0, y, x1, y + h, a);
            int ty = y + 6;
            if (titled) {
                g.text(f, WishUi.kindGlyph(t.kind) + " " + t.title, x0 + 7, ty, WishUi.textAlpha(WishUi.kindColor(t.kind), a), false);
                ty += 11;
            }
            for (int i = 0; i < lines; i++) {
                g.text(f, body.get(i), x0 + 7, ty, WishUi.textAlpha(WishUi.TEXT, a), false);
                ty += 10;
            }
            y += h + 6; // 다음 알림 위에 쌓인 눈(2px)이 들어갈 틈
        }
        return y;
    }

    private static void drawIndicators(GuiGraphicsExtractor g, Font f, int sw, int sh) {
        String spirit = AiClientState.spiritName, key = "  [" + openKeyName + "]";
        String msg;
        int color = WishUi.GOLD;
        if (AiClientState.firstInteraction() != null) {
            msg = Josa.iGa(spirit) + " 대답을 기다린다" + key;
            color = WishUi.WARNING;
        } else if (AiClientState.unread > 0) {
            msg = spirit + "의 말 " + AiClientState.unread + key;
        } else if (AiClientState.thinking) {
            msg = Josa.iGa(spirit) + " 생각에 잠겼다…";
            color = WishUi.FROST;
        } else {
            return;
        }
        msg = WishUi.GLYPH + " " + msg;
        float pulse = 0.65f + 0.35f * (float) Math.sin(WishUi.now() * 3);
        int w = f.width(msg) + 10;
        int x = sw - w - 6, y = sh - 66;
        WishUi.well(g, x, y, w, 13, WishUi.BG_SOFT, WishUi.alpha(color, pulse), 1f);
        g.text(f, msg, x + 5, y + 3, WishUi.textAlpha(color, Math.max(0.6f, pulse)), false);
    }

    // ---------------------------------------------------------------- 시네마틱

    private static void drawCinematic(GuiGraphicsExtractor g, Font f, int sw, int sh) {
        AiClientState.Cinematic c = AiClientState.cinematic;
        if (c == null) return;
        long age = System.currentTimeMillis() - c.start;
        if (age > c.durationMs + 700) {
            AiClientState.cinematic = null;
            return;
        }
        float in = WishUi.easeOut(age / 600f);
        float out = age > c.durationMs ? 1 - (age - c.durationMs) / 700f : 1;
        float a = Math.min(in, out);
        String style = c.style == null ? "" : c.style;
        int bar = Math.round(sh * 0.11f * a);
        int barColor = switch (style) {
            case "ominous" -> 0xFF0A0408;
            case "dawn" -> 0xFF1A1020;
            case "festive" -> 0xFF0A1F15;
            default -> WishUi.EDGE_DARK;
        };
        int accent = switch (style) {
            case "celebrate" -> 0xFFFFD27A;
            case "mystic" -> WishUi.MYSTIC;
            case "ominous" -> 0xFFE07A9A;
            case "dawn" -> 0xFFF0B48A;
            case "festive" -> WishUi.BERRY;
            default -> WishUi.GOLD;
        };
        g.fill(0, 0, sw, bar, WishUi.alpha(barColor, 0.92f));
        g.fill(0, sh - bar, sw, sh, WishUi.alpha(barColor, 0.92f));
        g.fill(0, bar, sw, bar + 1, WishUi.alpha(accent, a * 0.6f));
        g.fill(0, sh - bar - 1, sw, sh - bar, WishUi.alpha(accent, a * 0.6f));
        float t = WishUi.now();
        if (style.equals("festive")) {
            // 위 띠에는 꼬마전구, 아래 띠에는 쌓인 눈
            WishUi.garland(g, 0, sw, bar + 1, t, a);
            WishUi.snowCap(g, 0, sw, sh - bar - 1, a);
        }
        // 띠 사이로 내리는 눈 (불길한 연출에서는 내리지 않는다)
        if (!style.equals("ominous")) WishUi.snowfall(g, 0, bar + 2, sw, sh - bar - 2, t, Math.max(24, sw / 9), a * 0.9f);
        // 제목 (2배 크기, 정수 배율로 픽셀 유지)
        int titleColor = style.equals("festive") ? WishUi.GOLD : accent;
        int cy = sh / 2 - 16;
        g.pose().pushMatrix();
        g.pose().translate(sw / 2f, cy);
        g.pose().scale(2f, 2f);
        g.centeredText(f, c.title, 0, 0, WishUi.textAlpha(titleColor, a));
        g.pose().popMatrix();
        if (c.subtitle != null && !c.subtitle.isEmpty()) g.centeredText(f, c.subtitle, sw / 2, cy + 24, WishUi.textAlpha(WishUi.TEXT, a));
        int half = f.width(c.title) + 14;
        WishUi.twinkle(g, sw / 2 - half, cy + 7, t * 3, WishUi.alpha(accent, a));
        WishUi.twinkle(g, sw / 2 + half, cy + 7, t * 3 + 1.6f, WishUi.alpha(accent, a));
    }
}
