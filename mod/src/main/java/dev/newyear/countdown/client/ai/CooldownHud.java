package dev.newyear.countdown.client.ai;

import com.google.gson.JsonObject;
import dev.newyear.countdown.mixin.HudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Compact pixel-edged cards centered at the actionbar, shared by all scripted abilities. */
public final class CooldownHud {
    private static final CooldownHudState STATE = new CooldownHudState();
    private static final int CARD_H = 20, ROW_H = 23, SEGMENTS = 10;
    private static final int SEGMENT_EMPTY = 0xFF1B3A2C;

    private CooldownHud() {}

    public static void apply(JsonObject packet) { STATE.apply(packet); }
    public static void clear() { STATE.clear(); }
    public static boolean occupied() { return !STATE.entries().isEmpty(); }

    public static void tick(Minecraft mc) {
        if (mc.player == null) clear();
        else if (!mc.isPaused()) STATE.tick();
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || mc.player.isSpectator()) return;
        var entries = STATE.entries();
        if (entries.isEmpty()) return;
        var layout = CooldownHudState.layout(g.guiWidth(), g.guiHeight(), entries.size(),
                ((HudAccessor) mc.gui.hud).newyearcountdown$overlayMessageTime() > 0);
        for (int i = 0; i < layout.shown(); i++) {
            int x = layout.left() + (i % layout.columns()) * layout.cardWidth();
            int y = layout.bottom() - ROW_H * (layout.rows() - i / layout.columns());
            drawCard(g, mc.font, entries.get(i), x, y, layout.cardWidth() - 4);
        }
        if (entries.size() > layout.shown()) {
            String more = "+" + (entries.size() - layout.shown());
            g.text(mc.font, more, (g.guiWidth() - mc.font.width(more)) / 2, layout.bottom() - layout.rows() * ROW_H - 10, WishUi.TEXT_DIM, true);
        }
    }

    /** 아이콘 · 이름 · 남은 시간 + 10칸 진행 막대. 준비되면 초록, 식는 동안은 서리색. */
    private static void drawCard(GuiGraphicsExtractor g, Font font, CooldownHudState.Entry e, int x, int y, int width) {
        boolean ready = e.remaining() == 0;
        int color = ready ? WishUi.SUCCESS : WishUi.FROST;
        g.fill(x, y, x + width, y + CARD_H, WishUi.BG_TOP);
        g.fill(x, y, x + width, y + 1, WishUi.alpha(WishUi.SNOW, ready ? 0.9f : 0.45f)); // 위에 얹힌 눈
        g.fill(x, y + CARD_H - 1, x + width, y + CARD_H, WishUi.EDGE_DARK);
        g.fill(x, y + 1, x + 1, y + CARD_H - 1, WishUi.BERRY_DIM);
        g.fill(x + width - 1, y + 1, x + width, y + CARD_H - 1, WishUi.EDGE_DARK);

        var texture = DynamicTextures.get(e.texture());
        if (texture != null) g.blit(texture.location, x + 3, y + 3, x + 15, y + 15, 0f, 1f, 0f, 1f);
        else hourglass(g, x, y, color);

        String time = ready ? Component.translatable("gui.newyearcountdown.cooldown_ready").getString()
                : e.remaining() >= 1200 ? String.format(Locale.ROOT, "%d:%02d", (e.remaining() + 19) / 1200, ((e.remaining() + 19) / 20) % 60)
                : String.format(Locale.ROOT, "%.1fs", e.remaining() / 20.0);
        int timeWidth = font.width(time);
        g.text(font, font.plainSubstrByWidth(e.label(), Math.max(0, width - 24 - timeWidth)), x + 18, y + 5, WishUi.TEXT, true);
        g.text(font, time, x + width - timeWidth - 4, y + 5, color, true);

        int available = width - 8;
        double progress = e.duration() == 0 ? 1 : 1 - (double) e.remaining() / e.duration();
        int filled = (int) Math.floor(progress * SEGMENTS);
        for (int segment = 0; segment < SEGMENTS; segment++) {
            int sx = x + 4 + segment * available / SEGMENTS, end = x + 4 + (segment + 1) * available / SEGMENTS - 1;
            g.fill(sx, y + 16, end, y + 18, segment < filled ? color : SEGMENT_EMPTY);
        }
    }

    /** A small hourglass silhouette; no external texture dependency. */
    private static void hourglass(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 5, y + 3, x + 13, y + 5, color);
        g.fill(x + 7, y + 5, x + 11, y + 7, color);
        g.fill(x + 8, y + 7, x + 10, y + 10, color);
        g.fill(x + 7, y + 10, x + 11, y + 12, color);
        g.fill(x + 5, y + 12, x + 13, y + 14, color);
    }
}
