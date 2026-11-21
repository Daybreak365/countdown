package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bridges authenticated Minecraft connections to the server-side Discord OAuth exchange. */
public final class DiscordSocialLink {
    private DiscordSocialLink() {}
    private static final DiscordSocialSessions SESSIONS = new DiscordSocialSessions();
    private static final DiscordOAuth OAUTH = new DiscordOAuth();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "NewYear-Discord-OAuth"); t.setDaemon(true); return t; });
    public static void register() {
        DiscordSocialPackets.register();
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.cancel(handler.getOwner().id(), handler));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.cancel(handler.player.getUUID(), handler));
        ServerConfigurationNetworking.registerGlobalReceiver(DiscordSocialPackets.Response.ID, (payload, context) -> {
            var handler = context.packetListener();
            context.server().execute(() -> {
                if (DiscordLinkGate.isWaiting(handler)) respond(context.server(), handler.getOwner().id(), handler, payload,
                        p -> ServerConfigurationNetworking.send(handler, p));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(DiscordSocialPackets.Response.ID, (payload, context) ->
                respond(context.server(), context.player().getUUID(), context.player().connection, payload,
                        p -> ServerPlayNetworking.send(context.player(), p)));
    }
    static DiscordSocialPackets.Status status(String message) {
        DiscordConfig cfg = DiscordConfig.get();
        return new DiscordSocialPackets.Status(cfg.socialReady() ? cfg.socialClientId() : "", message);
    }
    private static CustomPacketPayload status(boolean web, String message) {
        DiscordConfig cfg = DiscordConfig.get();
        return web ? webStatus(cfg, DiscordBot.isRunning(), message) : status(message);
    }
    static DiscordSocialPackets.WebStatus webStatus(DiscordConfig cfg, boolean codeAvailable, String message) {
        String problem = cfg.webSetupProblem();
        return new DiscordSocialPackets.WebStatus(problem.isEmpty() ? cfg.oauthClientId() : "", codeAvailable,
                problem.isEmpty() ? message : problem);
    }
    static void offer(ServerConfigurationPacketListenerImpl handler) {
        offer(handler, "");
    }
    static void offer(ServerConfigurationPacketListenerImpl handler, String message) {
        if (ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.Status.ID))
            ServerConfigurationNetworking.send(handler, status(message));
        if (ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.WebStatus.ID))
            ServerConfigurationNetworking.send(handler, status(true, message));
    }
    static void offer(ServerPlayer player) {
        offer(player, "");
    }
    static void offer(ServerPlayer player, String message) {
        if (ServerPlayNetworking.canSend(player, DiscordSocialPackets.Status.ID)) ServerPlayNetworking.send(player, status(message));
        if (ServerPlayNetworking.canSend(player, DiscordSocialPackets.WebStatus.ID)) ServerPlayNetworking.send(player, status(true, message));
    }
    static void action(ServerConfigurationPacketListenerImpl handler, int action) {
        if (!DiscordLinkGate.isWaiting(handler)) return;
        if (action == DiscordSocialPackets.CANCEL) SESSIONS.cancel(handler.getOwner().id(), handler);
        else if (action == DiscordSocialPackets.START && ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.Challenge.ID))
            begin(handler.getOwner().id(), handler, handler.getOwner().name(), false, p -> ServerConfigurationNetworking.send(handler, p));
        else if (action == DiscordSocialPackets.START_WEB && ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.WebChallenge.ID))
            begin(handler.getOwner().id(), handler, handler.getOwner().name(), true, p -> ServerConfigurationNetworking.send(handler, p));
    }
    static void action(ServerPlayer player, int action) {
        if (action == DiscordSocialPackets.CANCEL) SESSIONS.cancel(player.getUUID(), player.connection);
        else if (action == DiscordSocialPackets.START && ServerPlayNetworking.canSend(player, DiscordSocialPackets.Challenge.ID))
            begin(player.getUUID(), player.connection, player.getScoreboardName(), false, p -> ServerPlayNetworking.send(player, p));
        else if (action == DiscordSocialPackets.START_WEB && ServerPlayNetworking.canSend(player, DiscordSocialPackets.WebChallenge.ID))
            begin(player.getUUID(), player.connection, player.getScoreboardName(), true, p -> ServerPlayNetworking.send(player, p));
    }
    private static void begin(UUID player, Object connection, String name, boolean web, Consumer<CustomPacketPayload> send) {
        DiscordConfig cfg = DiscordConfig.get();
        if (!(web ? cfg.webReady() : cfg.socialReady())) { send.accept(status(web, "서버의 Discord 인증 설정이 필요합니다.")); return; }
        if (DiscordBot.links() == null || DiscordBot.links().get(player) != null) {
            send.accept(status(web, "이미 연결된 계정입니다. 연동을 먼저 해제하세요.")); return;
        }
        var session = SESSIONS.begin(player, connection, name, web ? cfg.oauthClientId() : cfg.socialClientId(),
                web ? cfg.oauthRedirectUri : cfg.socialSdkRedirectUri, System.currentTimeMillis(), web);
        if (session == null) { send.accept(status(web, "인증을 처리 중입니다. 잠시 후 다시 시도하세요.")); return; }
        send.accept(web ? session.webPacket() : session.packet());
    }
    private static void respond(MinecraftServer server, UUID player, Object connection, DiscordSocialPackets.Response payload,
                                Consumer<CustomPacketPayload> send) {
        var session = SESSIONS.claim(player, connection, payload.session(), System.currentTimeMillis());
        if (session == null) return; // stale/replayed responses must not affect a newer attempt
        if (payload.code().isBlank() || !session.acceptsRedirect(payload.redirectUri())) {
            SESSIONS.finish(session); send.accept(status(session.web, "유효하지 않은 인증 결과입니다. 다시 연결하세요.")); return;
        }
        DiscordConfig cfg = DiscordConfig.get();
        if (!(session.web ? cfg.webReady() : cfg.socialReady()) || !session.clientId.equals(session.web ? cfg.oauthClientId() : cfg.socialClientId())) {
            SESSIONS.finish(session); send.accept(status(session.web, "서버 인증 설정이 변경되었습니다. 다시 연결하세요.")); return;
        }
        String secret = session.web ? cfg.oauthClientSecret() : cfg.socialClientSecret();
        try {
            IO.execute(() -> {
                JsonObject profile;
                try { profile = OAUTH.profile(session.clientId, secret, session.verifier, payload.code(), payload.redirectUri()); }
                catch (IOException | RuntimeException e) {
                    server.execute(() -> fail(session, send, "디스코드 인증에 실패했습니다. 다시 연결하세요.")); return;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                server.execute(() -> {
                    if (!SESSIONS.current(session, System.currentTimeMillis())) return;
                    try {
                        if (DiscordBot.links() == null || DiscordBot.links().get(player) != null) {
                            fail(session, send, "이미 연결된 계정입니다. 연동을 먼저 해제하세요."); return;
                        }
                        DiscordLinks.Link link = DiscordBot.links().linkVerified(player, session.name, DiscordText.str(profile, "id"),
                                DiscordText.str(profile, "username"), DiscordText.str(profile, "global_name"), DiscordText.str(profile, "avatar"), System.currentTimeMillis());
                        SESSIONS.finish(session);
                        DiscordLinkGate.onLinked(server, player, link);
                    } catch (IllegalArgumentException e) { fail(session, send, e.getMessage()); }
                    catch (IOException | RuntimeException e) { fail(session, send, "연동 기록을 저장하지 못했습니다. 서버 관리자에게 알려주세요."); }
                });
            });
        } catch (RejectedExecutionException e) { fail(session, send, "인증 요청이 많습니다. 잠시 후 다시 시도하세요."); }
    }
    private static void fail(DiscordSocialSessions.Session session, Consumer<CustomPacketPayload> send, String message) {
        if (!SESSIONS.current(session, System.currentTimeMillis())) return;
        SESSIONS.finish(session); send.accept(status(session.web, message));
    }
    public static void clear() { SESSIONS.clear(); IO.getQueue().clear(); }
}
