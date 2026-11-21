package dev.newyear.countdown.client.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 관리자 코드 편집기. 서버에서 받은 스크립트 코드를 고쳐 "검증"(모의 실행, 세계는 그대로) 또는 "적용"(새 버전으로 교체;
 * 이전 버전은 기록에 남아 되돌릴 수 있다)한다. 검증 결과(오류 줄:칸, 경고, 예상 효과, 블록 변경 요약)를 옆에 보여 준다.
 */
public class AdminCodeScreen extends Screen {

    private final Screen parent;
    private final String scriptId, name, original;
    private final Hits hits = new Hits();
    private MultiLineEditBox editor;
    private String draft;
    private int x0, y0, x1, y1, split;
    private long lastSentAt;

    public AdminCodeScreen(Screen parent, String scriptId, String name, String code) {
        super(Component.literal("코드 편집"));
        this.parent = parent;
        this.scriptId = scriptId;
        this.name = name;
        this.original = code;
        this.draft = code;
        AdminClient.editResult = null;
    }

    @Override
    protected void init() {
        x0 = 6;
        y0 = 6;
        x1 = width - 6;
        y1 = height - 6;
        split = x0 + (int) ((x1 - x0) * 0.64);
        editor = MultiLineEditBox.builder().setX(x0 + 8).setY(y0 + 22).setTextColor(WishUi.CODE_TEXT).setCursorColor(WishUi.GOLD)
                .setShowBackground(true).setPlaceholder(Component.literal("// WishScript"))
                .build(font, split - x0 - 14, y1 - y0 - 50, Component.literal("code"));
        editor.setCharacterLimit(30_000);
        editor.setValue(draft);
        editor.setValueListener(v -> draft = v);
        addRenderableWidget(editor);
        setInitialFocus(editor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private void send(boolean apply) {
        if (System.currentTimeMillis() - lastSentAt < 500) return;
        lastSentAt = System.currentTimeMillis();
        AdminClient.edit(scriptId, draft, apply);
        AdminClient.message = apply ? "적용하는 중…" : "검증하는 중…";
        AdminClient.messageAt = lastSentAt;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+Enter: 검증, Ctrl+S: 적용
        if (event.hasControlDown() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            send(false);
            return true;
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_S) {
            send(true);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hits.click(event.x(), event.y())) {
            WishUi.clickSound();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hits.clear();
        WishUi.panel(g, x0, y0, x1, y1, 1f);
        g.text(font, WishUi.GLYPH + " 코드 편집 — " + name + " (" + scriptId + ")", x0 + 10, y0 + 8, WishUi.GOLD, false);
        boolean changed = !draft.equals(original);
        if (changed) g.text(font, "● 고침", split - 50, y0 + 8, WishUi.WARNING, false);
        int by = y1 - 22;
        int bx = x0 + 8;
        WishUi.button(g, font, "검증 (Ctrl+Enter)", bx, by, 100, 14, mx, my, true, WishUi.INFO, 1f);
        hits.add(bx, by, 100, 14, () -> send(false));
        bx += 104;
        WishUi.button(g, font, "적용 (Ctrl+S)", bx, by, 84, 14, mx, my, changed, WishUi.SUCCESS, 1f);
        if (changed) hits.add(bx, by, 84, 14, () -> send(true));
        bx += 88;
        WishUi.button(g, font, "원래대로", bx, by, 60, 14, mx, my, changed, WishUi.WARNING, 1f);
        if (changed) hits.add(bx, by, 60, 14, () -> editor.setValue(original));
        WishUi.button(g, font, "닫기", split - 66, by, 60, 14, mx, my, true, WishUi.GOLD, 1f);
        hits.add(split - 66, by, 60, 14, this::onClose);

        // 오른쪽: 검증 결과
        int rx = split + 6, rw = x1 - split - 14;
        g.fill(split, y0 + 20, split + 1, y1 - 6, WishUi.INNER_LINE);
        int y = y0 + 22;
        g.text(font, "검증 결과", rx, y, WishUi.GOLD, false);
        y += 12;
        JsonObject res = AdminClient.editResult;
        if (res == null || !scriptId.equals(Json.str(res, "id"))) {
            y = wrap(g, "검증하면 문법·정적 검사·모의 실행 결과가 여기 나온다. 세계는 바뀌지 않는다.", rx, y, rw, WishUi.TEXT_FAINT);
            y = wrap(g, "적용하면 새 버전이 되고, 실행 중이면 state 를 지킨 채 다시 시작한다. 이전 버전은 스크립트 화면의 '이전 버전'으로 되돌릴 수 있다.", rx, y + 4, rw, WishUi.TEXT_FAINT);
        } else {
            boolean ok = Json.bool(res, "ok"), applied = Json.bool(res, "applied");
            y = wrap(g, (ok ? "✔ " : "✘ ") + Json.str(res, "text"), rx, y, rw, ok ? (applied ? WishUi.SUCCESS : WishUi.INFO) : WishUi.DANGER);
            JsonObject v = res.has("validation") ? res.getAsJsonObject("validation") : new JsonObject();
            y = list(g, v, "errors", "오류", rx, y, rw, WishUi.DANGER);
            y = list(g, v, "warnings", "경고", rx, y, rw, WishUi.WARNING);
            if (v.has("block_preview")) {
                JsonObject bp = v.getAsJsonObject("block_preview");
                y = wrap(g, "블록 변경 " + bp.get("count").getAsInt() + "칸" + (bp.has("size") ? " (" + bp.get("size").getAsString() + ")" : "")
                        + (bp.has("replaces") ? " · 기존 블록을 바꿈!" : ""), rx, y + 2, rw, bp.has("replaces") ? WishUi.WARNING : WishUi.FROST);
            }
            if (v.has("subscribed_events")) y = wrap(g, "이벤트: " + Json.join(v, "subscribed_events"), rx, y + 2, rw, WishUi.TEXT_DIM);
            y = list(g, v, "simulated_effects", "예상 효과", rx, y, rw, WishUi.TEXT);
            list(g, v, "console", "콘솔", rx, y, rw, WishUi.TEXT_FAINT);
        }
        if (!AdminClient.message.isEmpty() && System.currentTimeMillis() - AdminClient.messageAt < 8000) {
            g.text(font, font.plainSubstrByWidth("» " + AdminClient.message, x1 - split - 14), rx, y1 - 18, WishUi.GOLD, false);
        }
        super.extractRenderState(g, mx, my, delta);
    }

    private int list(GuiGraphicsExtractor g, JsonObject v, String key, String title, int x, int y, int w, int color) {
        if (!v.has(key) || !v.get(key).isJsonArray() || v.getAsJsonArray(key).isEmpty()) return y;
        g.text(font, title, x, y + 3, WishUi.TEXT_DIM, false);
        y += 13;
        int n = 0;
        for (JsonElement e : v.getAsJsonArray(key)) {
            if (n++ >= 14 || y > y1 - 34) break;
            y = wrap(g, "· " + e.getAsString(), x, y, w, color);
        }
        return y;
    }

    private int wrap(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        for (var seq : WishUi.wrap(font, text, w)) {
            if (y > y1 - 30) return y;
            g.text(font, seq, x, y, color, false);
            y += 9;
        }
        return y + 1;
    }
}
