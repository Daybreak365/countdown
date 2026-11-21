package dev.newyear.countdown.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 서버 안에서 도는 디스코드 봇.
 * <ul>
 *   <li>계정 연동: 게임의 /discord link 로 받은 코드를 디스코드의 /연동 명령에 넣는다.</li>
 *   <li>채팅 잇기: 설정한 채널과 게임 채팅을 양쪽으로 전달한다.</li>
 * </ul>
 * 네트워크 일은 모두 전용 스레드에서 하고, 게임 상태는 server.execute 로만 건드린다.
 */
public final class DiscordBot implements DiscordGateway.Handler {
    private static final int MESSAGE_LIMIT = 1900;
    private static final int OUTBOX_LIMIT = 200;
    private static final Pattern INTERACTION_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,400}");

    private static volatile DiscordLinks links;
    private static volatile DiscordBot running;
    private static volatile String status = "꺼짐";
    private static final Set<String> ONLINE = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    public static void register() {
        DiscordMediaPackets.register();
        DiscordConfig.load();
        DiscordCommands.register();
        DiscordLinkGate.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> loadLinks());
        ServerLifecycleEvents.SERVER_STARTED.register(DiscordBot::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            stop(true);
            ONLINE.clear();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler.player));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, bound) -> onGameChat(sender, message.signedContent()));
    }

    /** 연동 기록. 서버가 떠 있지 않으면 null. */
    public static DiscordLinks links() {
        return links;
    }

    /** 봇이 켜져 있고 쓸 수 있는 상태인지. 토큰 오류 등으로 멈춘 봇은 꺼진 것으로 본다. */
    public static boolean isRunning() {
        DiscordBot bot = running;
        return bot != null && !bot.dead;
    }

    public static String status() {
        return status;
    }

    /** 연결된 봇의 이름. 아직 모르면 빈 문자열. */
    public static String botName() {
        DiscordBot bot = running;
        return bot == null ? "" : bot.botName;
    }

    private static void loadLinks() {
        DiscordLinks next = new DiscordLinks(DiscordConfig.dir().resolve("newyearcountdown-discord-links.json"));
        try {
            next.load();
        } catch (RuntimeException e) {
            NewYearCountdown.LOGGER.error("[디스코드] {}", e.getMessage());
        }
        links = next;
    }

    public static synchronized void start(MinecraftServer server) {
        if (running != null) return;
        if (links == null) loadLinks();
        DiscordConfig cfg = DiscordConfig.get();
        if (cfg.enabled && cfg.oauthEnabled)
            NewYearCountdown.LOGGER.info("[디스코드] 웹 OAuth: {}", cfg.webReady() ? "서버 설정 완료" : cfg.webSetupProblem());
        if (!cfg.enabled) {
            setStatus("꺼짐 (설정에서 enabled=false)");
            return;
        }
        String token = cfg.resolveToken();
        if (token.isEmpty()) {
            setStatus("봇 토큰이 없어 꺼져 있음 (config/newyearcountdown-discord.json 의 token 또는 환경 변수 " + DiscordConfig.TOKEN_ENV + ")");
            return;
        }
        DiscordBot bot = new DiscordBot(server, token);
        running = bot;
        bot.gateway.start();
        if (cfg.relayServerStatus) bot.enqueue(":green_circle: 서버가 열렸습니다.");
    }

    public static synchronized void stop(boolean announce) {
        DiscordSocialLink.clear();
        DiscordBot bot = running;
        running = null;
        if (bot != null) bot.shutdown(announce && DiscordConfig.get().relayServerStatus);
        DiscordLinks l = links;
        if (l != null) {
            try {
                l.save();
            } catch (IOException e) {
                NewYearCountdown.LOGGER.error("[디스코드] 연동 기록을 저장하지 못했습니다: {}", e.toString());
            }
        }
        if (bot != null) setStatus("꺼짐");
    }

    /** 설정을 다시 읽고 봇을 다시 켠다. */
    public static synchronized void reload(MinecraftServer server) {
        stop(false);
        DiscordConfig.load();
        start(server);
        DiscordLinkGate.refreshStatuses(server);
    }

    /** 채팅 채널에 알림 한 줄을 보낸다 (봇이 꺼져 있거나 채널이 없으면 아무 일도 없다). */
    public static void announce(String text) {
        DiscordBot bot = running;
        if (bot != null && text != null && !text.isBlank()) bot.enqueue(text);
    }

    /** 새해 카운트다운이 끝났을 때. */
    public static void announceCountdown(String chatMessage) {
        if (!DiscordConfig.get().relayCountdown) return;
        String text = DiscordText.stripFormatting(chatMessage).trim();
        announce(":tada: " + (text.isEmpty() ? "새해가 밝았습니다!" : DiscordText.escapeMarkdown(text)));
    }

    private static void setStatus(String next) {
        if (next.equals(status)) return;
        status = next;
        NewYearCountdown.LOGGER.info("[디스코드] {}", next);
    }

    // ------------------------------------------------------------ 게임 → 디스코드

    private static void onJoin(ServerPlayer player) {
        String name = player.getScoreboardName();
        ONLINE.add(name);
        DiscordLinks l = links;
        DiscordBot bot = running;
        if (l != null) l.rememberPlayerName(player.getUUID(), name);
        if (bot == null) return;
        if (DiscordConfig.get().relayJoinLeave) bot.enqueue(":arrow_right: **" + DiscordText.escapeMarkdown(name) + "** 님이 접속했습니다.");
        DiscordLinks.Link link = l == null ? null : l.get(player.getUUID());
        if (link != null) bot.refreshProfile(link.discordId);
    }

    private static void onLeave(ServerPlayer player) {
        String name = player.getScoreboardName();
        if (!ONLINE.remove(name)) return;
        DiscordBot bot = running;
        if (bot != null && DiscordConfig.get().relayJoinLeave) bot.enqueue(":arrow_left: **" + DiscordText.escapeMarkdown(name) + "** 님이 나갔습니다.");
    }

    private static void onGameChat(ServerPlayer sender, String content) {
        DiscordBot bot = running;
        if (bot == null || sender == null || content == null || content.isBlank() || !DiscordConfig.get().relayGameChat) return;
        String text = DiscordText.escapeMarkdown(content);
        if (text.length() > MESSAGE_LIMIT) text = text.substring(0, text.offsetByCodePoints(0, Math.min(MESSAGE_LIMIT - 1, text.codePointCount(0, text.length())))) + "…";
        String name = DisplayNames.name(sender, sender.getName()).getString();
        String fallback = "**" + DiscordText.escapeMarkdown(name) + "**: " + text;
        JsonObject profile = DiscordConfig.get().relayUsingLinkedProfiles
                ? DiscordWebhook.message(links == null ? null : links.get(sender.getUUID()), name, text) : null;
        bot.enqueue(new Outgoing(fallback, profile));
    }

    // ------------------------------------------------------------ 인스턴스

    private final MinecraftServer server;
    private final DiscordMessageHistory messageHistory = new DiscordMessageHistory();
    private final DiscordRest rest;
    private final DiscordGateway gateway;
    private final ExecutorService io;
    private record Outgoing(String content, JsonObject profile) {}
    private final ConcurrentLinkedQueue<Outgoing> outbox = new ConcurrentLinkedQueue<>();
    private final DiscordWebhook webhook;
    private final AtomicInteger outboxSize = new AtomicInteger();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final ConcurrentHashMap<String, Long> lastWarn = new ConcurrentHashMap<>();
    private volatile String botName = "";
    private volatile boolean dead;

    private DiscordBot(MinecraftServer server, String token) {
        this.server = server;
        this.rest = new DiscordRest(token);
        this.webhook = new DiscordWebhook(rest);
        AtomicInteger n = new AtomicInteger();
        this.io = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "NewYear-Discord-IO-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        DiscordConfig cfg = DiscordConfig.get();
        int intents = 0;
        if (cfg.hasChannel() && cfg.relayDiscordChat) intents = DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT;
        this.gateway = new DiscordGateway(token, intents, this);
    }

    private void shutdown(boolean announce) {
        if (announce && DiscordConfig.get().hasChannel()) {
            outbox.clear();
            outboxSize.set(0);
            try {
                Future<?> sent = io.submit(() -> post(":red_circle: 서버가 닫혔습니다."));
                sent.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 닫히는 중에는 알림 실패를 기다리지 않는다
            }
        }
        gateway.stop();
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 꺼지는 중
        }
    }

    /** 채널로 보낼 글을 줄 세운다. 짧은 시간에 몰린 글은 한 메시지로 묶여 나간다. */
    private void enqueue(String line) {
        if (line.length() > MESSAGE_LIMIT) line = line.substring(0, MESSAGE_LIMIT) + "…";
        enqueue(new Outgoing(line, null));
    }

    private void enqueue(Outgoing outgoing) {
        if (!DiscordConfig.get().hasChannel()) return;
        outbox.add(outgoing);
        if (outboxSize.incrementAndGet() > OUTBOX_LIMIT && outbox.poll() != null) outboxSize.decrementAndGet();
        if (flushing.compareAndSet(false, true)) submit(this::flush);
    }

    private void flush() {
        try {
            Outgoing line;
            while ((line = outbox.poll()) != null) {
                outboxSize.decrementAndGet();
                if (line.profile() != null) {
                    try {
                        webhook.send(DiscordConfig.get().channel(), line.profile());
                        continue;
                    } catch (IOException e) {
                        warn("webhook", "프로필 채팅을 보내지 못해 봇 메시지로 보냅니다. 봇의 웹훅 관리 권한을 확인하세요.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                post(line.content());
            }
        } finally {
            flushing.set(false);
            if (!outbox.isEmpty() && flushing.compareAndSet(false, true)) submit(this::flush);
        }
    }

    private void post(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.add("allowed_mentions", noMentions());
        try {
            rest.post("/channels/" + DiscordConfig.get().channel() + "/messages", body);
        } catch (DiscordRest.RestException e) {
            if (e.status == 401 || e.status == 403 || e.status == 404)
                warn("post", "채널에 글을 쓰지 못했습니다 (" + e.getMessage() + "). channelId 와 봇의 채널 권한(채널 보기, 메시지 보내기)을 확인하세요.");
            else warn("post", "채널에 글을 쓰지 못했습니다: " + e.getMessage());
        } catch (IOException e) {
            warn("post", "채널에 글을 쓰지 못했습니다: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject noMentions() {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        return allowed;
    }

    /** 같은 종류의 경고는 1분에 한 번만 남긴다. */
    private void warn(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && now - last < 60_000) return;
        lastWarn.put(key, now);
        NewYearCountdown.LOGGER.warn("[디스코드] {}", message);
    }

    private void saveLinks() {
        submit(() -> {
            try {
                links.save();
            } catch (IOException e) {
                warn("save", "연동 기록을 저장하지 못했습니다: " + e);
            }
        });
    }

    /** 연동된 계정의 이름·사진이 바뀌었는지 디스코드에 물어 갱신한다. */
    private void refreshProfile(String discordId) {
        submit(() -> {
            try {
                JsonElement res = rest.get("/users/" + discordId);
                if (res.isJsonObject() && rememberProfile(res.getAsJsonObject())) links.save();
            } catch (IOException e) {
                warn("profile", "디스코드 프로필을 확인하지 못했습니다: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private boolean rememberProfile(JsonObject user) {
        String id = DiscordText.str(user, "id");
        if (id == null) return false;
        return links.updateProfile(id, DiscordText.str(user, "username"), orEmpty(DiscordText.str(user, "global_name")), orEmpty(DiscordText.str(user, "avatar")));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ 디스코드 → 게임

    @Override public void onStatus(String next) {
        setStatus(next.equals("연결됨") && !botName.isEmpty() ? "연결됨 (" + botName + ")" : next);
    }

    @Override public void onFatal(String reason) {
        dead = true;
        setStatus("멈춤: " + reason);
        NewYearCountdown.LOGGER.error("[디스코드] {} 고친 뒤 /discord reload 를 실행하세요.", reason);
    }

    @Override public void onMessageContentUnavailable() {
        NewYearCountdown.LOGGER.warn("[디스코드] 봇의 Message Content Intent 가 꺼져 있어 디스코드 채팅을 게임으로 가져올 수 없습니다. "
                + "개발자 포털 > Bot > Privileged Gateway Intents 에서 켠 뒤 /discord reload 를 실행하세요. (계정 연동과 게임→디스코드 전달은 그대로 동작합니다)");
    }

    @Override public void onDispatch(String type, JsonObject d) {
        switch (type) {
            case "READY" -> ready(d);
            case "MESSAGE_CREATE" -> message(d);
            case "MESSAGE_UPDATE" -> message(d, true);
            case "MESSAGE_DELETE" -> deletedMessage(d);
            case "INTERACTION_CREATE" -> interaction(d);
            default -> { }
        }
    }

    private void ready(JsonObject d) {
        JsonObject user = DiscordText.obj(d, "user");
        botName = orEmpty(DiscordText.str(user, "username"));
        setStatus("연결됨 (" + botName + ")");
        String applicationId = DiscordText.str(DiscordText.obj(d, "application"), "id");
        if (!AvatarRef.isUserId(applicationId)) return;
        submit(() -> {
            try {
                rest.put("/applications/" + applicationId + "/commands", slashCommands());
            } catch (IOException e) {
                warn("commands", "디스코드 명령어(/연동 등)를 등록하지 못했습니다: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 디스코드에 등록하는 명령어 이름. 디스코드 언어 설정과 상관없이 누구에게나 이 이름으로 보인다. */
    public static final String CMD_LINK = "연동", CMD_UNLINK = "연동해제", CMD_ONLINE = "접속자", OPT_CODE = "코드";

    static JsonArray slashCommands() {
        JsonArray commands = new JsonArray();
        JsonObject link = command(CMD_LINK, "게임에 표시된 코드로 마인크래프트 계정을 연동합니다");
        JsonObject code = new JsonObject();
        code.addProperty("type", 3);
        code.addProperty("name", OPT_CODE);
        code.addProperty("description", "게임에 표시된 6자리 코드");
        code.addProperty("required", true);
        code.addProperty("min_length", 6);
        code.addProperty("max_length", 12);
        JsonArray options = new JsonArray();
        options.add(code);
        link.add("options", options);
        commands.add(link);
        commands.add(command(CMD_UNLINK, "마인크래프트 계정 연동을 해제합니다"));
        commands.add(command(CMD_ONLINE, "마인크래프트 서버에 접속 중인 플레이어를 봅니다"));
        return commands;
    }

    private static JsonObject command(String name, String description) {
        JsonObject c = new JsonObject();
        c.addProperty("type", 1);
        c.addProperty("name", name);
        c.addProperty("description", description);
        return c;
    }

    private void message(JsonObject d) {
        message(d, false);
    }

    private void message(JsonObject event, boolean update) {
        DiscordConfig cfg = DiscordConfig.get();
        if (!cfg.relayDiscordChat || !cfg.hasChannel() || !cfg.channel().equals(DiscordText.str(event, "channel_id"))) return;
        if (!update) {
            JsonObject author = DiscordText.obj(event, "author");
            if (author == null || event.has("webhook_id") || isTrue(author, "bot")) return;
        }
        JsonObject d = messageHistory.accept(event, update);
        if (d == null) return;
        JsonObject author = DiscordText.obj(d, "author");
        if (author == null || d.has("webhook_id") || isTrue(author, "bot")) return;
        int kind = d.has("type") && d.get("type").isJsonPrimitive() ? d.get("type").getAsInt() : 0;
        if (kind != 0 && kind != 19) return; // 일반 글과 답글만. 고정·입장 알림 같은 시스템 메시지는 뺀다.
        if (rememberProfile(author)) saveLinks();

        DiscordRichMessage rich = DiscordRichMessage.parse(d);
        if (rich.empty()) return;
        relayRich(d, rich, update);
    }

    private void deletedMessage(JsonObject d) {
        if (!DiscordConfig.get().relayDiscordChat || !DiscordConfig.get().channel().equals(DiscordText.str(d, "channel_id"))) return;
        JsonObject previous = messageHistory.remove(DiscordText.str(d, "id"));
        if (previous != null && DiscordText.obj(previous, "author") != null)
            relayRich(previous, new DiscordRichMessage(List.of(new DiscordRichMessage.Part("[삭제된 Discord 메시지]", null)), List.of()), true);
    }

    private void relayRich(JsonObject d, DiscordRichMessage rich, boolean update) {
        JsonObject author = DiscordText.obj(d, "author");
        AvatarRef ref = AvatarRef.of(DiscordText.str(author, "id"), DiscordText.str(author, "avatar"));
        String name = DiscordText.displayName(author, DiscordText.obj(d, "member"));
        String id = DiscordText.str(d, "id");
        server.execute(() -> {
            if (running != this || !DiscordConfig.get().enabled || !DiscordConfig.get().relayDiscordChat) return;
            Component prefix = DiscordChat.fromDiscord(ref, name, DiscordText.str(author, "username"), "");
            var packet = new DiscordMediaPackets.Message(id, prefix, rich.json(), update);
            Component fallback = prefix.copy().append(rich.plain());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (DiscordConfig.get().showMediaInChat && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, DiscordMediaPackets.Message.ID))
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, packet);
                else if (!update) player.sendSystemMessage(fallback);
            }
            if (!update) server.sendSystemMessage(fallback);
        });
    }

    private static boolean isTrue(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private void interaction(JsonObject d) {
        String id = DiscordText.str(d, "id");
        String token = DiscordText.str(d, "token");
        JsonObject data = DiscordText.obj(d, "data");
        if (!AvatarRef.isUserId(id) || token == null || !INTERACTION_TOKEN.matcher(token).matches() || data == null) return;
        if (!d.has("type") || d.get("type").getAsInt() != 2) return;
        JsonObject member = DiscordText.obj(d, "member");
        JsonObject user = member != null ? DiscordText.obj(member, "user") : DiscordText.obj(d, "user");
        String userId = DiscordText.str(user, "id");
        if (!AvatarRef.isUserId(userId)) return;

        String reply = switch (orEmpty(DiscordText.str(data, "name"))) {
            case CMD_LINK, "link" -> link(user, userId, optionValue(data));
            case CMD_UNLINK, "unlink" -> {
                DiscordLinks.Link removed = links.unlinkDiscord(userId);
                if (removed != null) saveLinks();
                yield removed == null ? "연동된 마인크래프트 계정이 없습니다."
                        : "마인크래프트 계정 **" + DiscordText.escapeMarkdown(removed.playerName) + "** 연동을 해제했습니다.";
            }
            case CMD_ONLINE, "online" -> {
                List<String> names = new ArrayList<>(ONLINE);
                yield names.isEmpty() ? "지금 접속 중인 플레이어가 없습니다."
                        : "접속 중 " + names.size() + "명: " + DiscordText.escapeMarkdown(String.join(", ", names));
            }
            default -> "알 수 없는 명령입니다.";
        };
        respond(id, token, reply);
    }

    private String link(JsonObject user, String userId, String code) {
        DiscordLinks.Redeemed result = links.redeem(code, userId, DiscordText.str(user, "username"),
                DiscordText.str(user, "global_name"), DiscordText.str(user, "avatar"), System.currentTimeMillis());
        switch (result.status()) {
            case TOO_MANY_ATTEMPTS -> { return "코드를 너무 여러 번 틀렸습니다. 10분 뒤에 다시 시도하세요."; }
            case BAD_CODE -> { return "코드가 올바르지 않거나 만료되었습니다. 게임 화면에 표시된 코드를 다시 확인하세요."; }
            default -> { }
        }
        saveLinks();
        server.execute(() -> {
            DiscordLinks.Link linked = links.get(result.player());
            if (linked != null) DiscordLinkGate.onLinked(server, result.player(), linked);
        });
        return "마인크래프트 계정 **" + DiscordText.escapeMarkdown(result.playerName()) + "** 와(과) 연동했습니다.";
    }

    /** 명령의 첫 번째 옵션 값 (/연동 의 코드). */
    private static String optionValue(JsonObject data) {
        if (!data.has("options") || !data.get("options").isJsonArray()) return "";
        for (JsonElement e : data.getAsJsonArray("options")) {
            if (e.isJsonObject()) return orEmpty(DiscordText.str(e.getAsJsonObject(), "value"));
        }
        return "";
    }

    /** 명령을 친 사람에게만 보이는 답. 디스코드는 3초 안에 답을 받아야 한다. */
    private void respond(String interactionId, String token, String content) {
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("flags", 64);
        data.add("allowed_mentions", noMentions());
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        submit(() -> {
            try {
                rest.postUnauthenticated("/interactions/" + interactionId + "/" + token + "/callback", body);
            } catch (IOException e) {
                warn("respond", "디스코드 명령에 답하지 못했습니다: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
