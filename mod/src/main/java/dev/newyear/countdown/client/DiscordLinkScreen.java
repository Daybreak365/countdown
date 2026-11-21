package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.AvatarRef;
import dev.newyear.countdown.discord.DiscordBot;
import dev.newyear.countdown.discord.DiscordLinkPackets;
import dev.newyear.countdown.discord.DiscordSocialPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.Blaze3D;

/**
 * 디스코드 계정 연동 화면.
 * 연동하지 않은 채 접속하면 월드에 들어가기 전에 뜨고(접속은 서버가 붙잡아 둔다), 게임 안에서는 /discord link 로 연다.
 * 코드를 보여 주고 기다리다가 서버가 연동 완료를 알리면 프로필을 보여 준 뒤 닫힌다.
 */
public class DiscordLinkScreen extends Screen {
    private static final int PANEL_W = 264, PANEL_H = 206;
    private static final int BLURPLE = 0xFF5865F2, BLURPLE_HOVER = 0xFF7983F5, BLURPLE_DARK = 0xFF4752C4;
    private static final int PANEL = 0xFF313338, PANEL_DARK = 0xFF1E1F22, BORDER = 0xFF111214;
    private static final int TEXT = 0xFFF2F3F5, MUTED = 0xFFB5BAC1, GREEN = 0xFF57F287, GREY = 0xFF4E5058, GREY_HOVER = 0xFF6D6F78;
    private static final long DONE_HOLD_MS = 2500;

    private final Screen parent;
    /** 접속 중(설정 단계)에 뜬 화면인지. 이때는 닫으면서 서버에 접속을 계속하라고 알려야 한다. */
    private final boolean joining;
    private final boolean required;
    private final String botName;
    /** 시간이 다 되어 서버와의 연결을 끊는다 (접속 중일 때만 있음). */
    private final Runnable leave;
    private String code;
    private long expiresAt;
    private long copiedAt;
    private boolean finished;
    private boolean codeMode;
    private boolean socialBusy;
    private String socialClientId = "";
    private String socialMessage = "서버의 연결 설정을 확인하고 있습니다.";
    private long socialStarted;
    private DiscordSocialBridge.Attempt socialAttempt;
    private DiscordBrowserAuth browserAttempt;
    private String webClientId = "";
    private boolean webBusy;
    private boolean webStatusReceived;
    private boolean codeAvailable = true;
    private final boolean sdkAvailable = DiscordSocialBridge.available();

    private DiscordLinkPackets.DoneS2C done;
    private long doneAt;

    public DiscordLinkScreen(Screen parent, boolean joining, DiscordLinkPackets.PromptS2C prompt, Runnable leave) {
        super(Component.literal("디스코드 연동"));
        this.parent = parent;
        this.joining = joining;
        this.required = joining && prompt.required();
        this.botName = prompt.botName();
        this.leave = leave;
        setCode(prompt);
    }

    /** 서버가 새 코드를 줌 (만료되어 다시 받았을 때). */
    public void setCode(DiscordLinkPackets.PromptS2C prompt) {
        this.code = prompt.code();
        this.expiresAt = System.currentTimeMillis() + prompt.secondsLeft() * 1000L;
    }

    /** 서버가 연동 완료를 알림. */
    public void setDone(DiscordLinkPackets.DoneS2C payload) {
        stopSocial(false);
        this.done = payload;
        this.doneAt = System.currentTimeMillis();
        rebuildWidgets();
    }

    private String command() {
        return "/" + DiscordBot.CMD_LINK + " " + DiscordBot.OPT_CODE + ":" + code;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
    }

    @Override
    protected void init() {
        int x = left(), y = top() + PANEL_H - 30;
        if (done != null) {
            addRenderableWidget(new FlatButton(x + 72, y, 120, 20, Component.literal(joining ? "서버에 들어가기" : "닫기"), true, this::finish));
            return;
        }
        if (!codeMode) {
            FlatButton connect = new FlatButton(x + 12, top() + 70, 240, 24,
                    Component.literal(socialBusy ? "인증 취소" : webClientId.isEmpty() ? "Discord로 연결" : "브라우저로 Discord 연결"), true, this::startSocial);
            connect.active = socialBusy || !webClientId.isEmpty() || (!socialClientId.isEmpty() && sdkAvailable);
            if (!connect.active && webStatusReceived) {
                connect.setMessage(Component.literal("웹 인증 설정 필요"));
                connect.setTooltip(Tooltip.create(Component.literal(socialMessage)));
            }
            addRenderableWidget(connect);
            if (browserAttempt != null && !browserAttempt.result.isDone()) {
                addRenderableWidget(new FlatButton(x + 12, top() + 100, 240, 20, Component.literal("인증 링크 복사"), false, () -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(browserAttempt.authorizationUri().toString());
                    socialMessage = "링크를 복사했습니다. 이 PC의 브라우저에서 열어 주세요.";
                }));
            }
            FlatButton useCode = new FlatButton(x + 12, y, required ? 240 : 116, 20, Component.literal("코드로 연동"), false, () -> {
                stopSocial(true); codeMode = true; rebuildWidgets();
            });
            useCode.active = codeAvailable;
            addRenderableWidget(useCode);
            if (!required) addRenderableWidget(new FlatButton(x + 136, y, 116, 20,
                    Component.literal(joining ? "나중에 하기" : "닫기"), false, this::finish));
            return;
        }
        // 연동이 필수면 나갈 버튼도 건너뛸 버튼도 없다. 남은 시간이 끝날 때까지 이 화면에 머문다.
        addRenderableWidget(new FlatButton(x + 12, top() + 146, 240, 20, Component.literal("인증 화면으로 돌아가기"), false, () -> {
            codeMode = false; rebuildWidgets();
        }));
        addRenderableWidget(new FlatButton(x + 12, y, required ? 240 : 116, 20, Component.literal("명령어 복사"), true, () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(command());
            copiedAt = System.currentTimeMillis();
        }));
        if (!required) {
            addRenderableWidget(new FlatButton(x + 136, y, 116, 20, Component.literal(joining ? "나중에 하기" : "닫기"), false, this::finish));
        }
    }

    @Override
    public void tick() {
        // 접속 중에는 가려진 "접속 중" 화면이 서버 연결을 돌린다(킵얼라이브 응답, 끊김 처리).
        // 그 화면을 대신 틱해 주지 않으면 서버가 20초쯤 뒤 연결을 끊는데 이 화면은 그대로 남는다.
        if (joining && parent instanceof ConnectScreen) parent.tick();
        if (Minecraft.getInstance().gui.screen() != this) return;
        long now = System.currentTimeMillis();
        if (socialBusy && now - socialStarted > 170_000) {
            stopSocial(true); socialMessage = "인증 시간이 지났습니다. 다시 연결하세요."; rebuildWidgets();
        }
        if (done != null) {
            if (now - doneAt > DONE_HOLD_MS) finish();
        } else if (now >= expiresAt && required) {
            // 필수 연동: 시간 안에 연동하지 않으면 접속을 끝낸다
            finished = true;
            if (leave != null) leave.run();
        } else if (now >= expiresAt) {
            // 코드가 만료되면 조용히 새 코드를 받는다
            expiresAt = now + 5000;
            send(DiscordLinkPackets.ACTION_NEW_CODE);
        }
    }

    private void send(int action) {
        DiscordLinkPackets.ActionC2S payload = new DiscordLinkPackets.ActionC2S(action);
        try {
            if (joining) ClientConfigurationNetworking.send(payload);
            else if (ClientPlayNetworking.canSend(DiscordLinkPackets.ActionC2S.ID)) ClientPlayNetworking.send(payload);
        } catch (RuntimeException ignored) {
            // 이미 연결이 끊겼거나 단계가 넘어간 뒤
        }
    }

    /** 화면을 닫고, 접속 중이었다면 서버에 계속 진행하라고 알린다. */
    private void finish() {
        if (finished) return;
        finished = true;
        stopSocial(true);
        if (joining) send(DiscordLinkPackets.ACTION_CONTINUE);
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == this) mc.gui.setScreen(joining ? parent : null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !required || done != null;
    }

    @Override
    public void onClose() {
        finish();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = left(), y = top();
        g.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, BORDER);
        g.fill(x, y, x + PANEL_W, y + PANEL_H, PANEL);
        g.fill(x, y, x + PANEL_W, y + 26, BLURPLE);
        g.fill(x, y + 26, x + PANEL_W, y + 27, BLURPLE_DARK);
        g.text(font, title, x + 12, y + 9, 0xFFFFFFFF, false);
        if (done != null) extractDone(g, x, y);
        else extractWaiting(g, x, y);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void extractWaiting(GuiGraphicsExtractor g, int x, int y) {
        int cx = x + PANEL_W / 2;
        if (!codeMode) {
            g.centeredText(font, "Discord 계정으로 연결하세요", cx, y + 38, TEXT);
            boolean unavailable = webStatusReceived && webClientId.isEmpty() && (socialClientId.isEmpty() || !sdkAvailable);
            g.centeredText(font, unavailable ? "서버 관리자가 웹 인증 설정을 완료해야 합니다" : "열리는 인증창에서 계정을 확인하고 승인하세요", cx, y + 50, MUTED);
            String message = socialMessage;
            if (!webStatusReceived && !socialBusy && !sdkAvailable && !socialClientId.isEmpty())
                message = "서버의 웹 인증 설정이 필요합니다. 코드로 연동할 수 있습니다.";
            int row = y + (browserAttempt != null ? 128 : 108);
            for (var line : font.split(Component.literal(message), PANEL_W - 28)) {
                g.text(font, line, x + 14, row, MUTED, false);
                row += 11;
                if (row > y + (unavailable && codeAvailable ? 147 : 158)) break;
            }
            if (unavailable && codeAvailable) g.centeredText(font, "아래 코드 연동은 사용할 수 있습니다", cx, y + 158, MUTED);
            return;
        }
        String bot = botName == null || botName.isBlank() ? "서버 봇" : botName;
        g.centeredText(font, Component.literal("디스코드에서 ").append(Component.literal(bot).withStyle(s -> s.withColor(0x949CF7))).append("에게"), cx, y + 38, TEXT);
        g.centeredText(font, "아래 명령어를 입력하면 연동됩니다", cx, y + 50, TEXT);

        // 명령어 상자: "/연동 코드:" 는 작게, 코드는 크게
        int bx = x + 12, by = y + 66, bw = PANEL_W - 24, bh = 44;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, BORDER);
        g.fill(bx, by, bx + bw, by + bh, PANEL_DARK);
        g.centeredText(font, "/" + DiscordBot.CMD_LINK + " " + DiscordBot.OPT_CODE + ":", cx, by + 6, MUTED);
        String spaced = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
        g.pose().pushMatrix();
        g.pose().translate(cx, by + 20);
        g.pose().scale(2f, 2f);
        g.centeredText(font, spaced, 0, 0, TEXT);
        g.pose().popMatrix();

        long now = System.currentTimeMillis();
        if (now - copiedAt < 1500) {
            g.centeredText(font, "명령어를 복사했습니다", cx, y + 118, GREEN);
        } else {
            long left = Math.max(0, (expiresAt - now) / 1000);
            String dots = ".".repeat(1 + (int) (now / 500 % 3));
            g.centeredText(font, "연동을 기다리는 중" + dots + "  남은 시간 " + left / 60 + ":" + String.format("%02d", left % 60), cx, y + 118, MUTED);
        }
        if (required) g.centeredText(font, "이 서버는 디스코드 연동이 필요합니다", cx, y + 130, 0xFFF0B232);
    }

    private void extractDone(GuiGraphicsExtractor g, int x, int y) {
        int cx = x + PANEL_W / 2;
        int size = 48, ax = cx - size / 2, ay = y + 38;
        AvatarRef ref = AvatarRef.parsePath(done.avatarPath());
        Identifier avatar = DiscordAvatarGlyphs.largeTexture(ref);
        if (avatar != null) g.blit(avatar, ax, ay, ax + size, ay + size, 0f, 1f, 0f, 1f);
        else g.fill(ax + 8, ay + 8, ax + size - 8, ay + size - 8, BLURPLE);
        g.centeredText(font, "연동 완료!", cx, y + 94, GREEN);
        g.centeredText(font, done.displayName(), cx, y + 108, TEXT);
        if (!done.username().isBlank() && !done.username().equals(done.displayName()))
            g.centeredText(font, "@" + done.username(), cx, y + 120, MUTED);
    }

    public void socialStatus(DiscordSocialPackets.Status status) {
        socialClientId = status.clientId();
        if (!webClientId.isEmpty() || webBusy || (webStatusReceived && !socialBusy)) { rebuildWidgets(); return; }
        if (!status.message().isBlank()) {
            stopSocial(false); socialMessage = status.message();
        } else socialMessage = socialClientId.isEmpty() ? "서버에서 Discord 연결을 준비 중입니다. 코드로 연동할 수 있습니다." : "계정 이름과 프로필 사진을 연결합니다.";
        rebuildWidgets();
    }

    public void webStatus(DiscordSocialPackets.WebStatus status) {
        webStatusReceived = true;
        webClientId = status.clientId();
        codeAvailable = status.codeAvailable();
        if (!status.message().isBlank()) {
            stopSocial(false); socialMessage = status.message();
        } else if (!socialBusy) {
            socialMessage = !webClientId.isEmpty() ? "브라우저에서 계정 이름과 프로필 사진 사용에 동의하세요."
                    : (!socialClientId.isEmpty() && sdkAvailable) ? "계정 이름과 프로필 사진을 연결합니다."
                    : codeAvailable ? "서버의 웹 OAuth 설정이 필요합니다. 코드로 연동할 수 있습니다."
                    : "서버의 Discord 인증 설정이 필요합니다. 관리자에게 알려 주세요.";
        }
        rebuildWidgets();
    }

    private void startSocial() {
        if (socialBusy) {
            stopSocial(true); socialMessage = "인증을 취소했습니다. 다시 연결할 수 있습니다.";
        } else {
            socialBusy = true; socialStarted = System.currentTimeMillis();
            webBusy = !webClientId.isEmpty();
            socialMessage = "Discord 인증창을 여는 중입니다…";
            send(webBusy ? DiscordSocialPackets.START_WEB : DiscordSocialPackets.START);
        }
        rebuildWidgets();
    }

    public void socialChallenge(DiscordSocialPackets.Challenge challenge) {
        // A remote server cannot open native authorization windows without this player's button press.
        if (!socialBusy || webBusy || finished || done != null || !challenge.clientId().equals(socialClientId) || socialAttempt != null) return;
        socialMessage = "Discord에서 승인해 주세요. 앱이 없으면 브라우저가 열립니다.";
        DiscordSocialBridge.Attempt attempt = DiscordSocialBridge.authorize(challenge);
        socialAttempt = attempt;
        attempt.result.whenComplete((reply, error) -> Minecraft.getInstance().execute(() -> {
            if (finished || socialAttempt != attempt || Minecraft.getInstance().gui.screen() != this) return;
            if (error != null) {
                stopSocial(true); socialMessage = "인증을 완료하지 못했습니다. 다시 연결하거나 코드로 연동하세요."; rebuildWidgets(); return;
            }
            socialMessage = "계정을 확인하고 연결하는 중입니다…";
            var response = new DiscordSocialPackets.Response(challenge.session(), reply.code(), reply.redirect());
            try {
                if (joining) ClientConfigurationNetworking.send(response);
                else ClientPlayNetworking.send(response);
            } catch (RuntimeException e) {
                stopSocial(true); socialMessage = "서버 연결이 종료되었습니다."; rebuildWidgets();
            }
        }));
    }

    public void webChallenge(DiscordSocialPackets.WebChallenge challenge) {
        // Only the player's explicit click may open a browser or bind a local port.
        if (!socialBusy || !webBusy || finished || done != null || browserAttempt != null || !challenge.clientId().equals(webClientId)) return;
        final DiscordBrowserAuth attempt;
        try { attempt = DiscordBrowserAuth.listen(challenge); }
        catch (java.io.IOException e) {
            stopSocial(true); socialMessage = "인증용 포트를 열지 못했습니다. 다른 게임 창의 인증을 닫고 다시 시도하세요."; rebuildWidgets(); return;
        } catch (RuntimeException e) {
            stopSocial(true); socialMessage = "서버의 웹 인증 주소가 올바르지 않습니다."; rebuildWidgets(); return;
        }
        browserAttempt = attempt;
        socialMessage = "브라우저에서 승인하세요. 창이 열리지 않으면 인증 링크를 복사하세요.";
        attempt.result.whenComplete((reply, error) -> Minecraft.getInstance().execute(() -> {
            if (finished || browserAttempt != attempt || Minecraft.getInstance().gui.screen() != this) return;
            if (error != null) {
                stopSocial(true); socialMessage = "인증이 취소되었거나 시간이 지났습니다. 다시 연결하세요."; rebuildWidgets(); return;
            }
            socialMessage = "계정을 확인하고 연결하는 중입니다…";
            try {
                var response = new DiscordSocialPackets.Response(challenge.session(), reply.code(), reply.redirect());
                if (joining) ClientConfigurationNetworking.send(response);
                else ClientPlayNetworking.send(response);
            } catch (RuntimeException e) {
                stopSocial(true); socialMessage = "서버 연결이 종료되었습니다.";
            }
            rebuildWidgets();
        }));
        try { Blaze3D.openUri(attempt.authorizationUri()); }
        catch (RuntimeException e) { socialMessage = "인증 링크를 복사해 이 PC의 브라우저에서 열어 주세요."; }
        rebuildWidgets();
    }

    private void stopSocial(boolean notifyServer) {
        if (notifyServer && socialBusy) send(DiscordSocialPackets.CANCEL);
        socialBusy = false;
        webBusy = false;
        var browser = browserAttempt; browserAttempt = null;
        if (browser != null) browser.close();
        var attempt = socialAttempt; socialAttempt = null;
        if (attempt != null) attempt.close();
    }

    @Override public void removed() { stopSocial(true); super.removed(); }

    /** 디스코드풍 납작한 버튼. */
    private static final class FlatButton extends AbstractButton {
        private final boolean primary;
        private final Runnable action;

        FlatButton(int x, int y, int w, int h, Component label, boolean primary, Runnable action) {
            super(x, y, w, h, label);
            this.primary = primary;
            this.action = action;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            boolean hot = active && isHoveredOrFocused();
            int color = !active ? PANEL_DARK : primary ? (hot ? BLURPLE_HOVER : BLURPLE) : (hot ? GREY_HOVER : GREY);
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color);
            Minecraft mc = Minecraft.getInstance();
            g.centeredText(mc.font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, active ? TEXT : MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
