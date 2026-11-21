package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.AvatarRef;
import dev.newyear.countdown.discord.DiscordChat;
import dev.newyear.countdown.discord.DisplayNamePackets;
import dev.newyear.countdown.discord.DisplayNameStyle;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A one-line rich-text editor: select, style, preview, save. */
public final class DisplayNameScreen extends Screen {
    private static final int PANEL_H = 232;
    private static final int TEXT = 0xFFE8EDF2, MUTED = 0xFF9BA8B5, ACCENT = 0xFF83C9CE;
    private static final int[] PALETTE = {0xFFFFFF, 0xF3BE74, 0xE994A5, 0xA3D2A5, 0x83C9CE, 0x9AB6F2};
    private final DisplayNamePackets.OpenS2C data;
    private final DisplayNameEditorModel document;
    private String start, end;
    private boolean gradient, bold, pending, restoring, formatDirty;
    private String serverError = "";
    private long submittedAt;
    private RichNameEditBox nameField;
    private EditBox startField, endField;
    private FlatButton save, reset, mode, weight, apply, undo, redo, startPicker, endPicker;
    private int selectionFrom = -1, selectionTo = -1;
    private DisplayNameStyle draftPreview;

    public DisplayNameScreen(DisplayNamePackets.OpenS2C data) {
        super(Component.literal("표시 이름 편집"));
        this.data = data;
        document = new DisplayNameEditorModel(data.style());
        var first = document.at(0);
        start = hex(first.startColor());
        end = hex(first.endColor());
        gradient = first.gradient();
        bold = first.bold();
    }

    public java.util.UUID target() { return data.target(); }
    private static String hex(int color) { return String.format("#%06X", color); }
    private int panelWidth() { return Math.min(380, width - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - PANEL_H) / 2; }

    @Override protected void init() {
        // Opening the picker on mouse-down leaves this screen's drag active:
        // the release is delivered to the picker. Do not let the confirm click
        // resume that old drag on the newly focused name field when we return.
        setDragging(false);
        boolean reopening = nameField != null;
        int oldCursor = nameField == null ? document.value().text().length() : nameField.getCursorPosition();
        int oldAnchor = nameField == null ? oldCursor : oldCursor == nameField.selectionStart() ? nameField.selectionEnd() : nameField.selectionStart();
        int x = left() + 14, y = top(), w = panelWidth() - 28, half = (w - 12) / 2;
        save = null;
        nameField = new RichNameEditBox(font, x + 6, y + 43, w - 12, document, () -> history(false), () -> history(true));
        nameField.setValue(document.value().text());
        nameField.setResponder(next -> {
            if (!restoring) { nameField.updateDocument(next); formatDirty = false; serverError = ""; changed(); }
        });
        addRenderableWidget(nameField);
        button(x, y + 66, 54, "전체 선택", () -> { nameField.selectAll(); setFocused(nameField); });
        undo = button(x + 58, y + 66, 44, "되돌림", () -> history(false));
        redo = button(x + 106, y + 66, 38, "다시", () -> history(true));
        apply = button(x + 148, y + 66, w - 148, "선택에 적용", this::applySelection);
        mode = button(x, y + 89, half, "", () -> { gradient = !gradient; controlChanged(); });
        weight = button(x + half + 12, y + 89, half, "", () -> { bold = !bold; controlChanged(); });
        startField = colorField(x + 26, y + 114, half - 68, "시작 색상 #RRGGBB", start, value -> start = value);
        endField = colorField(x + half + 38, y + 114, half - 68, "끝 색상 #RRGGBB", end, value -> end = value);
        startPicker = button(x + half - 38, y + 115, 38, "팔레트", () -> openPicker(false));
        endPicker = button(x + w - 38, y + 115, 38, "팔레트", () -> openPicker(true));
        startPicker.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("시작 색상을 직접 선택합니다")));
        endPicker.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("그라데이션 끝 색상을 직접 선택합니다")));
        for (int i = 0; i < PALETTE.length; i++) {
            int px = x + i * (half / PALETTE.length);
            addRenderableWidget(new Swatch(px, y + 139, PALETTE[i], false));
            addRenderableWidget(new Swatch(px + half + 12, y + 139, PALETTE[i], true));
        }
        int third = (w - 12) / 3;
        reset = button(x, y + 204, third, "기본 이름", () -> submit(true));
        button(x + third + 6, y + 204, third, "취소", this::onClose);
        save = button(x + 2 * (third + 6), y + 204, w - 2 * (third + 6), "저장", () -> submit(false));
        changed();
        setInitialFocus(nameField);
        nameField.moveCursorTo(oldCursor, false);
        nameField.setHighlightPos(oldAnchor);
        // Returning from the picker (or resizing) must retain the exact selection
        // and draft controls, rather than reload the old run's colors next frame.
        selectionFrom = reopening ? nameField.selectionStart() : -1;
        selectionTo = reopening ? nameField.selectionEnd() : -1;
    }

    private EditBox colorField(int x, int y, int w, String label, String value, java.util.function.Consumer<String> responder) {
        EditBox field = new EditBox(font, x + 6, y + 6, w - 12, 12, Component.literal(label));
        field.setBordered(false);
        field.setTextShadow(false);
        field.setTextColor(TEXT);
        field.setMaxLength(7);
        field.setValue(value);
        field.setResponder(next -> { responder.accept(next); if (!restoring) controlChanged(); });
        return addRenderableWidget(field);
    }

    private FlatButton button(int x, int y, int w, String label, Runnable action) {
        return addRenderableWidget(new FlatButton(x, y, w, label, action));
    }

    private void openPicker(boolean endpoint) {
        if (pending || nameField.composing()) return;
        // Capture the final drag range even if no render occurred before this click.
        selectionChanged();
        if (endpoint && !gradient) return;
        int initial;
        try { initial = DisplayNameStyle.parseColor(endpoint ? end : start); }
        catch (IllegalArgumentException e) {
            var style = document.at(nameField.selectionStart());
            initial = endpoint ? style.endColor() : style.startColor();
        }
        Minecraft.getInstance().gui.setScreen(new NameColorPickerScreen(this,
                endpoint ? "끝 색상" : gradient ? "시작 색상" : "색상", initial,
                color -> (endpoint ? endField : startField).setValue(hex(color))));
    }

    private void controlChanged() {
        // Bind this draft to the live selection now, rather than letting the next
        // render treat a just-finished drag as new and reload the old run's style.
        selectionFrom = nameField.selectionStart();
        selectionTo = nameField.selectionEnd();
        formatDirty = true;
        serverError = "";
        draftPreview = null;
        if (hasSelection() && colorError().isEmpty()) {
            var draft = new DisplayNameEditorModel(document.value());
            int first = DisplayNameStyle.parseColor(start);
            draft.apply(nameField.selectionStart(), nameField.selectionEnd(), first,
                    gradient ? DisplayNameStyle.parseColor(end) : first, gradient, bold);
            draftPreview = draft.value();
        }
        changed();
    }

    private String colorError() {
        try { DisplayNameStyle.parseColor(start); if (gradient) DisplayNameStyle.parseColor(end); return ""; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private String validation() {
        try { document.value().checked(); return ""; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private boolean hasSelection() { return nameField != null && nameField.selectionStart() < nameField.selectionEnd(); }

    private void selectionChanged() {
        int from = nameField.selectionStart(), to = nameField.selectionEnd();
        if (from == selectionFrom && to == selectionTo) return;
        selectionFrom = from;
        selectionTo = to;
        var style = document.at(from);
        restoring = true;
        start = hex(style.startColor());
        end = hex(style.endColor());
        gradient = style.gradient();
        bold = style.bold();
        startField.setValue(start);
        endField.setValue(end);
        restoring = false;
        formatDirty = false;
        changed();
    }

    private void applySelection() {
        if (pending || !hasSelection() || !colorError().isEmpty() || nameField.composing()) return;
        int first = DisplayNameStyle.parseColor(start);
        int last = gradient ? DisplayNameStyle.parseColor(end) : first;
        document.apply(nameField.selectionStart(), nameField.selectionEnd(), first, last, gradient, bold);
        formatDirty = false;
        serverError = "";
        changed();
    }

    private void history(boolean forward) {
        if (pending || nameField.composing()) return;
        if (forward) document.redo(); else document.undo();
        int cursor = nameField.getCursorPosition();
        restoring = true;
        nameField.setValue(document.value().text());
        nameField.moveCursorTo(Math.min(cursor, document.value().text().length()), false);
        restoring = false;
        formatDirty = false;
        selectionFrom = -1;
        selectionChanged();
        changed();
    }

    private void changed() {
        if (save == null || startField == null) return;
        save.active = !pending && validation().isEmpty() && (!formatDirty || !hasSelection() || colorError().isEmpty());
        reset.active = !pending;
        mode.active = weight.active = !pending;
        apply.active = !pending && hasSelection() && colorError().isEmpty() && !nameField.composing();
        undo.active = !pending && document.canUndo();
        redo.active = !pending && document.canRedo();
        mode.setMessage(Component.literal(gradient ? "색상  ·  그라데이션" : "색상  ·  단색"));
        weight.setMessage(Component.literal(bold ? "굵기  ·  굵게" : "굵기  ·  보통"));
        nameField.setEditable(!pending);
        startPicker.active = !pending && !nameField.composing();
        endPicker.active = !pending && gradient && !nameField.composing();
        startField.setEditable(!pending);
        endField.setEditable(!pending && gradient);
        endField.setTextColorUneditable(0xFF617080);
    }

    private void submit(boolean restore) {
        if (pending || nameField.composing()) return;
        if (!restore) {
            if (formatDirty && hasSelection()) {
                if (!colorError().isEmpty()) return;
                applySelection();
            }
            if (!validation().isEmpty()) return;
        }
        if (!ClientPlayNetworking.canSend(DisplayNamePackets.SaveC2S.ID)) {
            serverError = "서버 연결을 확인하고 편집기를 다시 열어 주세요.";
            return;
        }
        pending = true;
        submittedAt = System.currentTimeMillis();
        serverError = "";
        changed();
        ClientPlayNetworking.send(new DisplayNamePackets.SaveC2S(data.target(),
                restore ? data.style() : document.value().checked(), restore));
    }

    public void result(DisplayNamePackets.ResultS2C result) {
        if (!pending || !data.target().equals(result.target())) return;
        pending = false;
        if (result.success()) {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.sendOverlayMessage(Component.literal(result.message()));
            onClose();
        } else { serverError = result.message(); changed(); }
    }

    @Override public void tick() {
        if (pending && System.currentTimeMillis() - submittedAt > 10000) {
            pending = false;
            serverError = "서버 응답이 늦습니다. 다시 열어 저장 결과를 확인하세요.";
            changed();
        }
    }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (nameField.isFocused()) selectionChanged();
        changed();
        int x = left(), y = top(), w = panelWidth(), inner = w - 28, half = (inner - 12) / 2;
        g.fill(x - 1, y - 1, x + w + 1, y + PANEL_H + 1, 0xFF38434F);
        g.fill(x, y, x + w, y + PANEL_H, 0xFF1D242D);
        g.text(font, title, x + 14, y + 10, TEXT, false);
        g.text(font, font.plainSubstrByWidth("대상  " + data.accountName(), inner - 48), x + 14, y + 25, MUTED, false);
        String name = document.value().text(), count = name.codePointCount(0, name.length()) + "/32";
        g.text(font, count, x + w - 14 - font.width(count), y + 25, MUTED, false);
        fieldBackground(g, nameField, x + 14, y + 39, inner, true);
        g.text(font, gradient ? "시작" : "색상", x + 14, y + 121, MUTED, false);
        g.text(font, "끝", x + 26 + half, y + 121, gradient ? MUTED : 0xFF617080, false);
        fieldBackground(g, startField, x + 40, y + 114, half - 68, true);
        fieldBackground(g, endField, x + 52 + half, y + 114, half - 68, gradient);
        g.fill(x + 14, y + 157, x + w - 14, y + 183, 0xFF131A22);
        g.enableScissor(x + 18, y + 157, x + w - 18, y + 183);
        var preview = Component.empty();
        AvatarRef avatar = AvatarRef.parsePath(data.avatarPath());
        if (avatar != null) preview.append(DiscordChat.avatar(avatar, null)).append(" ");
        preview.append(name.isEmpty() ? Component.literal("표시 이름")
                : (formatDirty && draftPreview != null ? draftPreview : document.value()).component());
        preview.append(DiscordChat.separator()).append(Component.literal("안녕하세요!").withColor(0xDCE3EA));
        g.text(font, preview, x + 21, y + 166, TEXT, false);
        g.disableScissor();
        String message = pending ? "저장 중…" : !serverError.isEmpty() ? serverError : validation();
        if (message.isEmpty() && formatDirty) message = colorError();
        boolean error = !message.isEmpty() && !pending;
        if (message.isEmpty()) message = hasSelection()
                ? name.codePointCount(nameField.selectionStart(), nameField.selectionEnd()) + "자 선택 · 색을 고르고 선택에 적용"
                : "드래그로 부분 선택 · Ctrl+Z 되돌림 · Ctrl+Y 다시";
        g.text(font, font.plainSubstrByWidth(message, inner), x + 14, y + 190, error ? 0xFFF0A7A7 : MUTED, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void fieldBackground(GuiGraphicsExtractor g, EditBox field, int x, int y, int w, boolean enabled) {
        g.fill(x, y, x + w, y + 22, 0xFF111820);
        g.outline(x, y, w, 22, enabled && field.isFocused() ? ACCENT : 0xFF38434F);
    }

    private final class Swatch extends AbstractButton {
        private final int color;
        private final boolean endpoint;
        Swatch(int x, int y, int color, boolean endpoint) {
            super(x, y, 16, 14, Component.literal((endpoint ? "끝 색상 " : "시작 색상 ") + hex(color)));
            this.color = color; this.endpoint = endpoint;
        }
        @Override public void onPress(InputWithModifiers input) {
            if (!pending && (!endpoint || gradient)) (endpoint ? endField : startField).setValue(hex(color));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            active = !pending && (!endpoint || gradient);
            g.fill(getX() + 2, getY() + 2, getX() + 14, getY() + 12, (active ? 0xFF000000 : 0x44000000) | color);
            if (active && (isHoveredOrFocused() || hex(color).equalsIgnoreCase(endpoint ? end : start))) g.outline(getX(), getY(), 16, 14, TEXT);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class FlatButton extends AbstractButton {
        private final Runnable action;
        FlatButton(int x, int y, int w, String label, Runnable action) {
            super(x, y, w, 20, Component.literal(label)); this.action = action;
        }
        @Override public void onPress(InputWithModifiers input) { action.run(); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            boolean primary = this == save;
            int color = !active ? 0xFF27313D : primary ? (isHoveredOrFocused() ? 0xFFA5DBDF : ACCENT)
                    : isHoveredOrFocused() ? 0xFF3E4D5D : 0xFF2B3744;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color);
            g.text(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2, getY() + 6,
                    !active ? 0xFF71808E : primary ? 0xFF14282C : TEXT, false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
