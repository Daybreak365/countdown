package dev.newyear.countdown.client.ai;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.ai.Josa;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 소원 정령과의 대화 화면. 대화 기록, 응답 대기 애니메이션, 빠른 답, AI 가 띄운 선택지·계약·입력 카드를
 * 한 화면에서 통일된 모양으로 보여 준다. 탭: 대화 / 과제 / 기록.
 */
public class WishChatScreen extends Screen {
    private enum Tab { CHAT, QUESTS, HISTORY }

    private static final int SEND_W = 50, BOTTOM_BUTTON_W = 70;

    private final Hits hits = new Hits();
    private final SimpleDateFormat historyTime = new SimpleDateFormat("MM.dd HH:mm");
    private final long openedAt = System.currentTimeMillis();
    private Tab tab = Tab.CHAT;
    private EditBox input;
    private int scroll;          // 위로 올린 픽셀 수 (0 = 맨 아래)
    private int lastLineCount = -1;
    private int questScroll;
    private int x0, y0, x1, y1;

    public WishChatScreen() {
        super(Component.literal("소원 대화"));
    }

    @Override
    protected void init() {
        int w = Math.min(400, width - 16), h = Math.min(280, height - 16);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        input = new EditBox(font, x0 + 12, y1 - 46, w - 24 - 56, 14, Component.literal("소원"));
        input.setMaxLength(300);
        input.setBordered(false);
        input.setTextColor(WishUi.TEXT);
        addRenderableWidget(input);
        setInitialFocus(input);
        AiClientState.unread = 0;
        AiClientState.action("sync", "");
        updateHint();
    }

    private static boolean wishOpen() {
        return !AiClientState.wishId.isEmpty() && !AiClientState.wishState.isEmpty() && !WishUi.terminalState(AiClientState.wishState);
    }

    private void updateHint() {
        input.setEditable(!AiClientState.thinking);
        AiClientState.Interaction it = AiClientState.firstInteraction();
        String spirit = AiClientState.spiritName;
        String hint;
        if (AiClientState.thinking) hint = Josa.iGa(spirit) + " 대답을 준비하고 있어요…";
        else if (it != null && it.kind.equals("input")) hint = it.placeholder.isEmpty() ? "답을 적어라…" : it.placeholder;
        else if (!wishOpen()) hint = spirit + "에게 소원을 빌어 보세요…";
        else hint = "대답하기…";
        input.setHint(Component.literal(hint).withColor(WishUi.TEXT_FAINT));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        updateHint();
    }

    private void submit() {
        if (AiClientState.thinking) return;
        String t = input.getValue().strip();
        if (t.isEmpty()) return;
        AiClientState.Interaction it = AiClientState.firstInteraction();
        if (it != null && it.kind.equals("input")) {
            if (!AiClientState.answer(it, 0, t)) return;
        } else {
            if (WishUi.terminalState(AiClientState.wishState)) AiClientState.newWish();
            if (!AiClientState.sendChat(t)) return;
        }
        input.setValue("");
        scroll = 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) && tab == Tab.CHAT) {
            submit();
            return true;
        }
        if (k == InputConstants.KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
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
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (tab == Tab.CHAT) scroll = Math.max(0, scroll + (int) (sy * 18));
        else questScroll = Math.max(0, questScroll - (int) (sy * 18));
        return true;
    }

    // ---------------------------------------------------------------- 그리기

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hits.clear();
        float open = WishUi.easeOut((System.currentTimeMillis() - openedAt) / 220f);
        float t = WishUi.now();
        int slide = Math.round((1 - open) * 10);
        int py0 = y0 + slide, py1 = y1 + slide;
        WishUi.panel(g, x0, py0, x1, py1, open);

        // 머리글
        WishUi.header(g, font, AiClientState.spiritName, x0, py0, open);
        if (!AiClientState.wishState.isEmpty()) {
            String st = WishUi.stateKo(AiClientState.wishState);
            int cw = font.width(st) + 8;
            WishUi.chip(g, font, st, x1 - 12 - cw, py0 + 7, WishUi.stateColor(AiClientState.wishState), open);
        }
        if (!AiClientState.enabled) g.text(font, "AI 꺼짐", x1 - 60, py0 + 20, WishUi.textAlpha(WishUi.DANGER, open), false);

        // 탭
        int tx = x0 + 12, ty = py0 + 21;
        int activeQuests = 0;
        for (AiClientState.Quest q : AiClientState.quests.values()) if (q.active()) activeQuests++;
        String[] names = {"대화", "과제" + (activeQuests > 0 ? " " + activeQuests : ""), "기록"};
        for (Tab target : Tab.values()) {
            int w = WishUi.tab(g, font, names[target.ordinal()], tx, ty, tab == target, mx, my, open);
            hits.add(tx, ty, w, 12, () -> tab = target);
            tx += w + 3;
        }
        if (BuildHologram.available()) {
            int bw = font.width("건축 위치") + 12, bx = x1 - 12 - bw;
            if (bx > tx) {
                WishUi.button(g, font, "건축 위치", bx, ty, bw, 12, mx, my, true, WishUi.FROST, open);
                hits.add(bx, ty, bw, 12, BuildHologram::begin);
            }
        }
        WishUi.garland(g, x0 + 8, x1 - 8, ty + 12, t, open);

        int top = ty + 19;
        // 내용 뒤로 조용히 내리는 눈
        WishUi.snowfall(g, x0 + 4, top, x1 - 4, py1 - 6, t, 40, open * 0.8f);
        switch (tab) {
            case CHAT -> drawChat(g, mx, my, top, py1, open, t);
            case QUESTS -> drawQuests(g, mx, my, top, py1, open);
            case HISTORY -> drawHistory(g, mx, my, top, py1, open);
        }
        input.visible = tab == Tab.CHAT;
        super.extractRenderState(g, mx, my, delta);
    }

    private void closeButton(GuiGraphicsExtractor g, int mx, int my, int bottom, float open) {
        int bx = x1 - 12 - BOTTOM_BUTTON_W, by = bottom - 22;
        WishUi.button(g, font, "닫기", bx, by, BOTTOM_BUTTON_W, 14, mx, my, true, WishUi.GOLD, open);
        hits.add(bx, by, BOTTOM_BUTTON_W, 14, this::onClose);
    }

    // ---------------------------------------------------------------- 대화 탭

    private void drawChat(GuiGraphicsExtractor g, int mx, int my, int top, int bottom, float open, float t) {
        int inputY = bottom - 48;
        // 상호작용 카드 높이 먼저 계산
        AiClientState.Interaction it = AiClientState.firstInteraction();
        int cardH = it == null ? 0 : interactionHeight(it);
        int quickH = AiClientState.quick.isEmpty() || it != null ? 0 : 14;
        int areaBottom = inputY - 6 - cardH - quickH;

        drawLines(g, top, areaBottom, open, t);
        if (quickH > 0 && !AiClientState.thinking) drawQuickReplies(g, mx, my, areaBottom + 2, open);
        if (it != null) drawInteraction(g, it, mx, my, inputY - 4 - cardH, cardH, open);
        drawInputRow(g, mx, my, inputY, it != null && it.kind.equals("input"), open);

        // 아래 버튼
        int by = bottom - 22, bw = BOTTOM_BUTTON_W;
        WishUi.button(g, font, "새 소원", x0 + 12, by, bw, 14, mx, my, !AiClientState.thinking, WishUi.FROST, open);
        hits.add(x0 + 12, by, bw, 14, () -> {
            AiClientState.newWish();
            updateHint();
        });
        boolean canCancel = wishOpen();
        WishUi.button(g, font, "소원 취소", x0 + 16 + bw, by, bw, 14, mx, my, canCancel, WishUi.DANGER, open);
        if (canCancel) hits.add(x0 + 16 + bw, by, bw, 14, () -> AiClientState.action("cancel", AiClientState.wishId));
        closeButton(g, mx, my, bottom, open);
        g.text(font, "Tab: 탭 전환", x0 + 24 + bw * 2, by + 3, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
    }

    /** 대화 기록: 말풍선을 아래에서 위로 쌓고, 응답을 기다리는 동안 눈송이 세 개가 반짝인다. */
    private void drawLines(GuiGraphicsExtractor g, int top, int areaBottom, float open, float t) {
        int maxBubble = (x1 - x0) - 70;
        List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
        int contentHeight = 0;
        for (AiClientState.Line l : AiClientState.lines) {
            List<FormattedCharSequence> ws = WishUi.wrap(font, l.text, maxBubble);
            wrapped.add(ws);
            contentHeight += bubbleHeight(ws) + 5;
        }
        if (AiClientState.thinking) contentHeight += 22;
        if (lastLineCount != AiClientState.lines.size()) {
            lastLineCount = AiClientState.lines.size();
            scroll = 0;
        }
        int viewH = areaBottom - top;
        scroll = Math.min(scroll, Math.max(0, contentHeight - viewH));
        int cx = (x0 + x1) / 2;
        g.enableScissor(x0 + 4, top, x1 - 4, areaBottom);
        if (AiClientState.lines.isEmpty() && !AiClientState.thinking) {
            int my = top + viewH / 2;
            WishUi.flake(g, cx, my - 24, 3, WishUi.alpha(WishUi.SNOW, open * (0.7f + 0.3f * (float) Math.sin(t * 2))));
            g.centeredText(font, "눈 내리는 밤, 무엇을 바라느냐?", cx, my - 10, WishUi.textAlpha(WishUi.TEXT_DIM, open));
            g.centeredText(font, "아래에 소원을 적고 Enter — 들어줄지는 내가 정한다.", cx, my + 4, WishUi.textAlpha(WishUi.TEXT_FAINT, open));
        }
        int y = areaBottom - contentHeight + scroll;
        for (int i = 0; i < AiClientState.lines.size(); i++) {
            List<FormattedCharSequence> ws = wrapped.get(i);
            int h = bubbleHeight(ws);
            if (y + h > top - 20 && y < areaBottom + 20) drawBubble(g, AiClientState.lines.get(i), ws, y, h, open);
            y += h + 5;
        }
        if (AiClientState.thinking) {
            int bx = x0 + 12;
            String label = AiClientState.thinkingLabel.isEmpty() ? "눈송이를 세는 중" : AiClientState.thinkingLabel;
            int w = font.width(label) + 44;
            g.fill(bx, y, bx + w, y + 16, WishUi.alpha(WishUi.SPIRIT_BUBBLE, open));
            for (int k = 0; k < 3; k++) WishUi.twinkle(g, bx + 8 + k * 8, y + 8, t * 5 - k * 0.9f, WishUi.SNOW);
            g.text(font, label, bx + 32, y + 4, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
            long secs = (System.currentTimeMillis() - AiClientState.thinkingSince) / 1000;
            if (secs > 4) g.text(font, secs + "s", bx + w + 4, y + 4, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
        }
        g.disableScissor();
        if (scroll > 0) g.centeredText(font, "▼ 최근 대화로", cx, areaBottom - 10, WishUi.textAlpha(WishUi.TEXT_FAINT, open));
    }

    private static int bubbleHeight(List<FormattedCharSequence> lines) {
        return lines.size() * 10 + 8;
    }

    /** 정령은 왼쪽(전나무색, 눈빛 띠), 플레이어는 오른쪽(붉은색, 등불빛 띠). 새 말풍선은 옆에서 미끄러져 들어온다. */
    private void drawBubble(GuiGraphicsExtractor g, AiClientState.Line line, List<FormattedCharSequence> ws, int y, int h, float open) {
        int w = 12;
        for (FormattedCharSequence s : ws) w = Math.max(w, font.width(s) + 12);
        float age = WishUi.easeOut((System.currentTimeMillis() - line.at) / 260f);
        float a = open * age;
        boolean spirit = line.role.equals("spirit");
        int bx = spirit ? x0 + 12 - Math.round((1 - age) * 6) : x1 - 12 - w + Math.round((1 - age) * 6);
        int bg = spirit ? WishUi.SPIRIT_BUBBLE : WishUi.PLAYER_BUBBLE;
        g.fill(bx + 1, y, bx + w - 1, y + h, WishUi.alpha(bg, a));
        g.fill(bx, y + 1, bx + 1, y + h - 1, WishUi.alpha(bg, a));
        g.fill(bx + w - 1, y + 1, bx + w, y + h - 1, WishUi.alpha(bg, a));
        int stripeX = spirit ? bx : bx + w - 2;
        g.fill(stripeX, y + 2, stripeX + 2, y + h - 2, WishUi.alpha(spirit ? WishUi.SNOW : WishUi.LANTERN, a * 0.85f));
        int ly = y + 4;
        for (FormattedCharSequence s : ws) {
            g.text(font, s, bx + 6, ly, WishUi.textAlpha(spirit ? WishUi.TEXT : WishUi.PLAYER_TEXT, a), false);
            ly += 10;
        }
    }

    private void drawQuickReplies(GuiGraphicsExtractor g, int mx, int my, int qy, float open) {
        int qx = x0 + 12;
        for (String q : AiClientState.quick) {
            int w = font.width(q) + 10;
            if (qx + w > x1 - 12) break;
            boolean hover = WishUi.inside(mx, my, qx, qy, w, 12);
            WishUi.well(g, qx, qy, w, 12, hover ? WishUi.TAB_SELECTED : WishUi.TAB_HOVER, hover ? WishUi.EDGE_GOLD : WishUi.EDGE_GOLD_DIM, open);
            g.text(font, q, qx + 5, qy + 2, WishUi.textAlpha(hover ? WishUi.GOLD : WishUi.TEXT, open), false);
            hits.add(qx, qy, w, 12, () -> {
                AiClientState.sendChat(q);
                scroll = 0;
            });
            qx += w + 4;
        }
    }

    private void drawInputRow(GuiGraphicsExtractor g, int mx, int my, int inputY, boolean answering, float open) {
        int fieldW = (x1 - x0) - 24 - (SEND_W + 6);
        WishUi.well(g, x0 + 10, inputY - 3, fieldW + 4, 16, WishUi.FIELD_BG, input.isFocused() ? WishUi.EDGE_GOLD : WishUi.EDGE_GOLD_DIM, open);
        input.setX(x0 + 14);
        input.setY(inputY + 1);
        input.setWidth(fieldW - 4);
        int sendX = x1 - 12 - SEND_W;
        String label = AiClientState.thinking ? "준비 중" : answering ? "답하기" : "보내기";
        WishUi.button(g, font, label, sendX, inputY - 3, SEND_W, 16, mx, my, !AiClientState.thinking && !input.getValue().isBlank(), WishUi.GOLD, open);
        hits.add(sendX, inputY - 3, SEND_W, 16, this::submit);
        g.text(font, input.getValue().length() + "/300", x0 + 12, inputY + 16, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
    }

    // ---------------------------------------------------------------- 선택지·계약·입력 카드

    private int interactionHeight(AiClientState.Interaction it) {
        int lines = WishUi.wrap(font, it.prompt, (x1 - x0) - 40).size();
        int base = 18 + lines * 10;
        return switch (it.kind) {
            case "input" -> base + 14;
            case "choices" -> base + ((it.options.size() + 1) / 2) * 17 + 4;
            default -> base + 22;
        };
    }

    private void drawInteraction(GuiGraphicsExtractor g, AiClientState.Interaction it, int mx, int my, int y, int h, float open) {
        int cx0 = x0 + 10, cx1 = x1 - 10;
        int accent = it.kind.equals("contract") ? WishUi.WARNING : it.kind.equals("consent") ? WishUi.INFO : WishUi.FROST;
        g.fillGradient(cx0, y, cx1, y + h, WishUi.alpha(WishUi.CARD_TOP, open), WishUi.alpha(WishUi.CARD_BOTTOM, open));
        g.outline(cx0, y, cx1 - cx0, h, WishUi.alpha(accent, open));
        String head = !it.title.isEmpty() ? it.title : switch (it.kind) {
            case "contract" -> "소원의 계약";
            case "input" -> AiClientState.spiritName + "의 물음";
            case "consent" -> "동의 요청";
            default -> "운명의 갈림길";
        };
        g.text(font, WishUi.GLYPH + " " + head, cx0 + 6, y + 4, WishUi.textAlpha(accent, open), false);
        long rem = AiClientState.remaining(it.deadlineLocal);
        boolean locked = AiClientState.thinking;
        String corner = "";
        int cornerColor = WishUi.TEXT_DIM;
        if (locked) {
            corner = "대답 후 선택 가능";
        } else if (rem >= 0) {
            corner = WishUi.time(rem);
            boolean urgent = rem < 10_000;
            if (urgent) cornerColor = WishUi.DANGER;
            float frac = it.totalMs > 0 ? (float) rem / it.totalMs : 0;
            WishUi.bar(g, cx0 + 6, y + 14, cx1 - cx0 - 12, 3, frac, urgent ? WishUi.DANGER : accent, open);
        } else if (it.kind.equals("contract")) {
            corner = "말로 조건을 바꾸자고 해도 된다";
            cornerColor = WishUi.TEXT_FAINT;
        }
        if (!corner.isEmpty()) g.text(font, corner, cx1 - 6 - font.width(corner), y + 4, WishUi.textAlpha(cornerColor, open), false);
        int ty = y + 19;
        for (FormattedCharSequence s : WishUi.wrap(font, it.prompt, cx1 - cx0 - 20)) {
            g.text(font, s, cx0 + 8, ty, WishUi.textAlpha(WishUi.TEXT, open), false);
            ty += 10;
        }
        switch (it.kind) {
            case "input" -> g.text(font, "↓ 아래 입력칸에 답을 적고 Enter", cx0 + 8, ty + 1, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
            case "choices" -> {
                int colW = (cx1 - cx0 - 20) / 2;
                for (int i = 0; i < it.options.size(); i++) {
                    int idx = i;
                    answerButton(g, it, idx, it.options.get(i), cx0 + 8 + (i % 2) * (colW + 4), ty + 2 + (i / 2) * 17, colW, accent, mx, my, open);
                }
            }
            default -> {
                int bw = 80, bx = (cx0 + cx1) / 2 - bw - 4;
                String yes = it.options.isEmpty() ? "수락" : it.options.get(0);
                String no = it.options.size() > 1 ? it.options.get(1) : "거절";
                answerButton(g, it, 0, yes, bx, ty + 3, bw, WishUi.SUCCESS, mx, my, open);
                answerButton(g, it, 1, no, bx + bw + 8, ty + 3, bw, WishUi.DANGER, mx, my, open);
            }
        }
    }

    private void answerButton(GuiGraphicsExtractor g, AiClientState.Interaction it, int index, String label, int x, int y, int w, int accent, int mx, int my, float open) {
        WishUi.button(g, font, label, x, y, w, 15, mx, my, !AiClientState.thinking, accent, open);
        hits.add(x, y, w, 15, () -> AiClientState.answer(it, index, label));
    }

    // ---------------------------------------------------------------- 과제 탭

    private void drawQuests(GuiGraphicsExtractor g, int mx, int my, int top, int bottom, float open) {
        List<AiClientState.Quest> list = AiClientState.sortedQuests(false);
        int areaBottom = bottom - 26;
        g.enableScissor(x0 + 4, top, x1 - 4, areaBottom);
        int y = top - questScroll;
        if (list.isEmpty()) g.centeredText(font, "아직 받은 과제가 없다.", (x0 + x1) / 2, top + 40, WishUi.textAlpha(WishUi.TEXT_DIM, open));
        for (AiClientState.Quest q : list) y = drawQuest(g, q, y, open) + 6;
        int total = y + questScroll - top;
        questScroll = Math.min(questScroll, Math.max(0, total - (areaBottom - top)));
        g.disableScissor();
        closeButton(g, mx, my, bottom, open);
    }

    /** 과제 하나를 그린다. 반환: 아래 구분선의 y. */
    private int drawQuest(GuiGraphicsExtractor g, AiClientState.Quest q, int startY, float open) {
        int w = x1 - x0 - 24, left = x0 + 18, right = x1 - 18;
        int accent = q.active() ? WishUi.GOLD : "COMPLETED".equals(q.state) ? WishUi.SUCCESS : "FAILED".equals(q.state) ? WishUi.DANGER : WishUi.TEXT_FAINT;
        int y = startY + 4;
        g.text(font, WishUi.GLYPH + " " + q.title, left, y, WishUi.textAlpha(accent, open), false);
        String st = q.active() ? "진행 중" : WishUi.stateKo(q.state);
        g.text(font, st, right - font.width(st), y, WishUi.textAlpha(accent, open), false);
        y += 12;
        long rem = AiClientState.remaining(q.deadlineLocal);
        if (rem >= 0 && q.active()) {
            g.text(font, "남은 시간 " + WishUi.time(rem), left, y, WishUi.textAlpha(rem < 30_000 ? WishUi.DANGER : WishUi.TEXT_DIM, open), false);
            WishUi.bar(g, x0 + 100, y + 2, w - 92, 4, q.totalMs > 0 ? (float) rem / q.totalMs : 0, WishUi.WARNING, open);
            y += 11;
        }
        for (String section : new String[]{q.description, q.conditions.isEmpty() ? "" : "조건: " + q.conditions}) {
            if (section == null || section.isEmpty()) continue;
            for (FormattedCharSequence s : WishUi.wrap(font, section, w - 16)) {
                g.text(font, s, left, y, WishUi.textAlpha(WishUi.TEXT, open), false);
                y += 10;
            }
        }
        for (AiClientState.Objective o : q.objectives) {
            o.shown += (o.fraction() - o.shown) * 0.15f;
            g.text(font, (o.done ? "■ " : "□ ") + o.text + (o.optional ? " (선택)" : ""), x0 + 22, y, WishUi.textAlpha(o.done ? WishUi.SUCCESS : WishUi.TEXT, open), false);
            String num = (int) o.progress + "/" + (int) o.target;
            g.text(font, num, right - font.width(num), y, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
            y += 10;
            WishUi.bar(g, x0 + 22, y, w - 12, 4, o.shown, o.done ? WishUi.SUCCESS : WishUi.GOLD, open);
            y += 8;
        }
        String[][] notes = {{"보상: ", q.reward}, {"실패: ", q.failure}, {"", q.note}};
        int[] noteColors = {WishUi.SUCCESS, WishUi.WARNING, WishUi.TEXT_DIM};
        for (int i = 0; i < notes.length; i++) {
            if (notes[i][1].isEmpty()) continue;
            g.text(font, notes[i][0] + notes[i][1], left, y, WishUi.textAlpha(noteColors[i], open), false);
            y += 10;
        }
        y += 4;
        g.fill(x0 + 12, startY, x0 + 14, y, WishUi.alpha(accent, open));
        g.fill(x0 + 14, y, x1 - 12, y + 1, WishUi.alpha(WishUi.INNER_LINE, open));
        return y;
    }

    // ---------------------------------------------------------------- 기록 탭

    private void drawHistory(GuiGraphicsExtractor g, int mx, int my, int top, int bottom, float open) {
        int y = top;
        if (AiClientState.history.isEmpty()) g.centeredText(font, "아직 빈 소원이 없다.", (x0 + x1) / 2, top + 40, WishUi.textAlpha(WishUi.TEXT_DIM, open));
        for (AiClientState.PastWish p : AiClientState.history) {
            if (y > bottom - 40) break;
            g.text(font, historyTime.format(new Date(p.time)), x0 + 14, y, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
            String st = WishUi.stateKo(p.state);
            g.text(font, st, x1 - 14 - font.width(st), y, WishUi.textAlpha(WishUi.stateColor(p.state), open), false);
            String text = p.text.startsWith("(자율)") ? "❅ " + AiClientState.spiritName + "의 이야기" : "\"" + p.text + "\"";
            g.text(font, font.plainSubstrByWidth(text, x1 - x0 - 140), x0 + 74, y, WishUi.textAlpha(WishUi.TEXT, open), false);
            y += 10;
            if (p.outcome != null && !p.outcome.isEmpty()) {
                g.text(font, font.plainSubstrByWidth("  └ " + p.outcome, x1 - x0 - 90), x0 + 74, y, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
                y += 10;
            }
            y += 3;
        }
        closeButton(g, mx, my, bottom, open);
    }
}
