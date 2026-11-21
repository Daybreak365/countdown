package dev.newyear.countdown.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;

/** Keeps vanilla editing/clipboard/IME input but measures and paints the styled document. */
public final class RichNameEditBox extends EditBox {
    private final Font nameFont;
    private final DisplayNameEditorModel document;
    private final Runnable undoAction, redoAction;
    private int anchor, scroll;
    private IMEPreeditOverlay preedit;
    private boolean editable = true;
    private dev.newyear.countdown.discord.DisplayNameStyle measuredDocument;
    private long fontGeneration = -1;
    private Component renderedText = Component.empty();
    private int[] advances = {0};
    private int editFrom = -1, editTo;

    public RichNameEditBox(Font font, int x, int y, int width, DisplayNameEditorModel document, Runnable undo, Runnable redo) {
        super(font, x, y, width, 14, Component.literal("표시 이름 · 드래그 또는 Shift+방향키로 부분 선택"));
        this.nameFont = font;
        this.document = document;
        this.undoAction = undo;
        this.redoAction = redo;
        setBordered(false);
        setTextShadow(false);
        setMaxLength(128);
    }

    @Override public void setHighlightPos(int position) {
        super.setHighlightPos(position);
        anchor = Math.clamp(position, 0, getValue().length());
    }
    @Override public void setEditable(boolean enabled) { super.setEditable(enabled); editable = enabled; }
    public int selectionStart() { return Math.min(getCursorPosition(), anchor); }
    public int selectionEnd() { return Math.max(getCursorPosition(), anchor); }
    public void selectAll() { moveCursorToStart(false); moveCursorToEnd(true); }
    public boolean composing() { return preedit != null; }

    public void updateDocument(String next) {
        String before = document.value().text();
        int insertedLength = next.length() - (before.length() - (editTo - editFrom));
        if (editFrom >= 0 && editTo <= before.length() && insertedLength >= 0
                && next.startsWith(before.substring(0, editFrom)) && next.endsWith(before.substring(editTo)))
            document.replace(editFrom, editTo, next.substring(editFrom, editFrom + insertedLength));
        else document.setText(next);
    }

    @Override public void insertText(String text) {
        int previousFrom = editFrom, previousTo = editTo;
        editFrom = selectionStart();
        editTo = selectionEnd();
        try { super.insertText(text); }
        finally { editFrom = previousFrom; editTo = previousTo; }
    }

    @Override public void deleteCharsToPos(int position) {
        int previousFrom = editFrom, previousTo = editTo;
        editFrom = Math.clamp(Math.min(position, getCursorPosition()), 0, getValue().length());
        editTo = Math.clamp(Math.max(position, getCursorPosition()), 0, getValue().length());
        try { super.deleteCharsToPos(position); }
        finally { editFrom = previousFrom; editTo = previousTo; }
    }

    private int advance(int utf16) {
        measure();
        return advances[Math.clamp(utf16, 0, advances.length - 1)];
    }

    private void measure() {
        if (measuredDocument == document.value() && fontGeneration == SmoothNameGlyphs.generation()) return;
        measuredDocument = document.value();
        fontGeneration = SmoothNameGlyphs.generation();
        renderedText = measuredDocument.component();
        advances = new int[measuredDocument.text().length() + 1];
        var prefix = Component.empty();
        int[] used = {0};
        renderedText.visit((style, part) -> {
            for (int i = 0; i < part.length();) {
                int count = Character.charCount(part.codePointAt(i));
                for (int j = 1; j < count; j++) advances[used[0] + j] = advances[used[0]];
                prefix.append(Component.literal(part.substring(i, i + count)).setStyle(style));
                used[0] += count;
                advances[used[0]] = nameFont.width(prefix);
                i += count;
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
    }

    @Override public int getScreenX(int character) { return getX() + advance(character) - scroll; }

    private int clicked(double mouseX) {
        int offset = 0, previousWidth = 0;
        double position = mouseX - getX() + scroll;
        String text = getValue();
        while (offset < text.length()) {
            int next = offset + Character.charCount(text.codePointAt(offset));
            int width = advance(next);
            if (position < (previousWidth + width) / 2.0) return offset;
            offset = next;
            previousWidth = width;
        }
        return offset;
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int position = clicked(event.x());
        if (doubleClick) {
            String text = getValue();
            int a = position, b = position;
            while (a > 0 && !Character.isWhitespace(text.codePointBefore(a))) a -= Character.charCount(text.codePointBefore(a));
            while (b < text.length() && !Character.isWhitespace(text.codePointAt(b))) b += Character.charCount(text.codePointAt(b));
            moveCursorTo(a, false);
            moveCursorTo(b, true);
        } else moveCursorTo(position, event.hasShiftDown());
    }
    @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) { moveCursorTo(clicked(event.x()), true); }

    @Override public boolean keyPressed(KeyEvent event) {
        if (canConsumeInput() && !composing() && event.hasControlDown()) {
            if (event.key() == 90) { (event.hasShiftDown() ? redoAction : undoAction).run(); return true; }
            if (event.key() == 89) { redoAction.run(); return true; }
        }
        return super.keyPressed(event);
    }

    @Override public boolean preeditUpdated(PreeditEvent event) {
        preedit = event == null ? null : new IMEPreeditOverlay(event, nameFont, 10);
        return true;
    }

    @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int caret = advance(getCursorPosition()), total = advance(getValue().length());
        if (caret < scroll) scroll = caret;
        if (caret > scroll + getWidth() - 2) scroll = caret - getWidth() + 2;
        scroll = Math.clamp(scroll, 0, Math.max(0, total - getWidth() + 2));
        int y = getY() + 2, cursorX = getX() + caret - scroll;
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        if (selectionStart() != selectionEnd())
            g.fill(getScreenX(selectionStart()), y - 1, getScreenX(selectionEnd()), y + 10, isFocused() ? 0xFF334F68 : 0xFF2D3D4D);
        g.text(nameFont, renderedText, getX() - scroll, y, editable ? 0xFFFFFFFF : 0xFF99A4AF, false);
        if (isFocused() && editable && System.currentTimeMillis() / 500 % 2 == 0)
            g.fill(cursorX, y - 1, cursorX + 1, y + 10, 0xFFE8EDF2);
        g.disableScissor();
        if (isHovered()) g.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.IBEAM);
        if (canConsumeInput()) {
            if (preedit == null) Minecraft.getInstance().textInputManager().setTextInputArea(cursorX, y, cursorX + 1, y + 10);
            else {
                preedit.updateInputPosition(cursorX, y);
                g.setPreeditOverlay(preedit);
            }
        }
    }
}
