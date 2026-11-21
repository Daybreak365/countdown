package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DisplayNameStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.function.IntConsumer;

/** A local draft picker. Only accepting a color changes the parent editor. */
public final class NameColorPickerScreen extends Screen {
    private static final int PANEL_H = 236;
    private static final int TEXT = 0xFFE8EDF2, MUTED = 0xFF9BA8B5, ACCENT = 0xFF83C9CE;
    private static final int[] PRESETS = {0xFFFFFF, 0xAAB4C0, 0x202833, 0xEF6666, 0xF3BE74, 0xF1DF73,
            0xA3D2A5, 0x5EC9A3, 0x83C9CE, 0x9AB6F2, 0xB49BE3, 0xE994A5};
    private final Screen parent;
    private final IntConsumer accepted;
    private final int original;
    private final HsvColor color;
    private EditBox hexField;
    private ActionButton confirm;
    private ColorArea saturationValue, hue;
    private boolean syncing, valid = true;

    public NameColorPickerScreen(Screen parent, String label, int initialColor, IntConsumer accepted) {
        super(Component.literal(label + " 선택"));
        this.parent = parent; this.original = initialColor; this.accepted = accepted;
        color = new HsvColor(initialColor);
    }

    private static String hex(int rgb) { return String.format("#%06X", rgb); }
    private int panelWidth() { return Math.min(344, width - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - PANEL_H) / 2; }

    @Override protected void init() {
        int x = left(), y = top(), w = panelWidth();
        String value = hexField == null ? hex(color.rgb()) : hexField.getValue();
        saturationValue = addRenderableWidget(new ColorArea(x + 14, y + 39, w - 66, 96, false));
        hue = addRenderableWidget(new ColorArea(x + w - 38, y + 39, 24, 96, true));
        hexField = new EditBox(font, x + 70, y + 147, 82, 14, Component.literal("색상 코드 #RRGGBB"));
        hexField.setBordered(false); hexField.setTextShadow(false); hexField.setTextColor(TEXT);
        hexField.setMaxLength(7); hexField.setValue(value);
        hexField.setResponder(next -> {
            if (syncing) return;
            try { color.setRgb(DisplayNameStyle.parseColor(next)); valid = true; }
            catch (IllegalArgumentException e) { valid = false; }
            updateControls();
        });
        addRenderableWidget(hexField);
        addRenderableWidget(new ActionButton(x + w - 92, y + 143, 78, "원래 색", () -> { color.setRgb(original); syncHex(); }));
        int step = (w - 28) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) addRenderableWidget(new Swatch(x + 14 + step * i, y + 172, step - 3, PRESETS[i]));
        int half = (w - 34) / 2;
        addRenderableWidget(new ActionButton(x + 14, y + 207, half, "취소", this::onClose));
        confirm = addRenderableWidget(new ActionButton(x + 20 + half, y + 207, half, "이 색상 사용", () -> {
            if (!valid) return;
            accepted.accept(color.rgb());
            onClose();
        }));
        updateControls();
        setInitialFocus(saturationValue);
    }

    private void syncHex() {
        syncing = true;
        hexField.setValue(hex(color.rgb()));
        syncing = false;
        valid = true;
        updateControls();
    }

    private void updateControls() {
        if (confirm != null) confirm.active = valid;
        saturationValue.setMessage(Component.literal("채도와 밝기, " + hex(color.rgb()) + ". 방향키로 조절, Shift로 크게 조절"));
        hue.setMessage(Component.literal("색조, " + Math.round(color.hue() * 360) + "도. 위아래 방향키로 조절"));
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = left(), y = top(), w = panelWidth();
        g.fill(x, y, x + w, y + PANEL_H, 0xFF1D242D);
        g.outline(x - 1, y - 1, w + 2, PANEL_H + 2, 0xFF38434F);
        g.text(font, title, x + 14, y + 10, TEXT, false);
        g.text(font, "채도 · 밝기", x + 14, y + 26, MUTED, false);
        g.text(font, "색조", x + w - 38, y + 26, MUTED, false);
        g.fill(x + 14, y + 143, x + 34, y + 163, 0xFF000000 | original);
        g.fill(x + 34, y + 143, x + 54, y + 163, 0xFF000000 | color.rgb());
        g.outline(x + 13, y + 142, 42, 22, 0xFF617080);
        g.fill(x + 62, y + 143, x + 160, y + 163, 0xFF111820);
        g.outline(x + 62, y + 143, 98, 20, !valid ? 0xFFF0A7A7 : hexField.isFocused() ? ACCENT : 0xFF38434F);
        String hint = valid ? "드래그로 선택 · 방향키로 미세 조절" : "색상 코드를 #RRGGBB 형식으로 입력하세요";
        g.text(font, font.plainSubstrByWidth(hint, w - 28), x + 14, y + 193, valid ? MUTED : 0xFFF0A7A7, false);
        super.extractRenderState(g, mx, my, delta);
    }

    private final class ColorArea extends AbstractWidget {
        private final boolean hueOnly;
        ColorArea(int x, int y, int w, int h, boolean hueOnly) {
            super(x, y, w, h, Component.empty()); this.hueOnly = hueOnly;
        }
        private void pick(double mouseX, double mouseY) {
            double v = (mouseY - getY()) / (getHeight() - 1.0);
            if (hueOnly) color.setHue(v);
            else color.setSaturationValue((mouseX - getX()) / (getWidth() - 1.0), 1 - v);
            syncHex();
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { pick(event.x(), event.y()); }
        @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) { pick(event.x(), event.y()); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (!isFocused() || !active) return false;
            int key = event.key();
            if (key < 262 || key > 265) return false;
            if (hueOnly) color.setHue(color.hue() + (key == 262 || key == 264 ? 1 : -1) * (event.hasShiftDown() ? 10 : 1) / 360.0);
            else {
                double step = event.hasShiftDown() ? 0.05 : 0.01;
                color.setSaturationValue(color.saturation() + (key == 262 ? step : key == 263 ? -step : 0),
                        color.value() + (key == 265 ? step : key == 264 ? -step : 0));
            }
            syncHex(); return true;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            if (hueOnly) {
                for (int row = 0; row < h; row++) g.fill(x, y + row, x + w, y + row + 1, 0xFF000000 | HsvColor.rgb(row / (h - 1.0), 1, 1));
                int marker = y + (int) Math.round(color.hue() * (h - 1));
                g.fill(x - 2, marker - 2, x + w + 2, marker + 3, 0xFF111820);
                g.fill(x - 2, marker - 1, x + w + 2, marker + 2, TEXT);
            } else {
                for (int col = 0; col < w; col++) g.fillGradient(x + col, y, x + col + 1, y + h,
                        0xFF000000 | HsvColor.rgb(color.hue(), col / (w - 1.0), 1), 0xFF000000);
                int px = x + (int) Math.round(color.saturation() * (w - 1)), py = y + (int) Math.round((1 - color.value()) * (h - 1));
                g.outline(px - 3, py - 3, 7, 7, 0xFF111820);
                g.outline(px - 2, py - 2, 5, 5, TEXT);
            }
            g.outline(x - 1, y - 1, w + 2, h + 2, isFocused() ? ACCENT : 0xFF38434F);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class Swatch extends AbstractButton {
        private final int rgb;
        Swatch(int x, int y, int w, int rgb) {
            super(x, y, w, 14, Component.literal(hex(rgb))); this.rgb = rgb;
        }
        @Override public void onPress(InputWithModifiers input) { color.setRgb(rgb); syncHex(); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0xFF000000 | rgb);
            if (isHoveredOrFocused() || color.rgb() == rgb) g.outline(getX(), getY(), getWidth(), getHeight(), TEXT);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class ActionButton extends AbstractButton {
        private final Runnable action;
        ActionButton(int x, int y, int w, String label, Runnable action) { super(x, y, w, 20, Component.literal(label)); this.action = action; }
        @Override public void onPress(InputWithModifiers input) { action.run(); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            boolean primary = this == confirm;
            g.fill(getX(), getY(), getRight(), getBottom(), !active ? 0xFF27313D : primary ? ACCENT : isHoveredOrFocused() ? 0xFF3E4D5D : 0xFF2B3744);
            g.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + 6, !active ? 0xFF71808E : primary ? 0xFF14282C : TEXT);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
