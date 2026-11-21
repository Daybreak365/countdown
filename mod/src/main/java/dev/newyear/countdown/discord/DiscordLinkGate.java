package dev.newyear.countdown.discord;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 접속 중 디스코드 연동 화면.
 * 연동하지 않은 플레이어는 월드에 들어가기 전(설정 단계)에 붙잡아 두고 클라이언트에 연동 화면을 띄운다.
 * 연동이 끝나거나(또는 필수가 아닐 때 "나중에"를 누르면) 접속을 마저 진행한다.
 */
public final class DiscordLinkGate {
    private DiscordLinkGate() {}

    private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type("newyearcountdown:discord_link");
    /** 연동 화면을 보고 있는(접속이 멈춰 있는) 플레이어. */
    private static final Map<UUID, ServerConfigurationPacketListenerImpl> WAITING = new ConcurrentHashMap<>();

    public static void register() {
        DiscordLinkPackets.register();
        DiscordSocialLink.register();
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (shouldPrompt(handler)) handler.addTask(new Task(handler));
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> WAITING.remove(handler.getOwner().id(), handler));
        ServerConfigurationNetworking.registerGlobalReceiver(DiscordLinkPackets.ActionC2S.ID, (payload, context) -> {
            ServerConfigurationPacketListenerImpl handler = context.packetListener();
            context.server().execute(() -> onConfigurationAction(handler, payload.action()));
        });
        ServerPlayNetworking.registerGlobalReceiver(DiscordLinkPackets.ActionC2S.ID, (payload, context) -> {
            // 게임 안에서 연 화면은 닫아도 서버가 할 일이 없다. 만료된 코드만 새로 준다.
            if (payload.action() == DiscordLinkPackets.ACTION_NEW_CODE) openInGame(context.player());
            else DiscordSocialLink.action(context.player(), payload.action());
        });
    }

    private static boolean shouldPrompt(ServerConfigurationPacketListenerImpl handler) {
        DiscordConfig cfg = DiscordConfig.get();
        DiscordLinks links = DiscordBot.links();
        if (!cfg.enabled || !cfg.promptLinkOnJoin || links == null) return false;
        // 지원되는 연동 수단이 없으면 접속 화면에 가두지 않는다.
        if ((!DiscordBot.isRunning() && !cfg.authenticationReady()) || ServerConfigurationNetworking.isReconfiguring(handler)) return false;
        if (!ServerConfigurationNetworking.canSend(handler, DiscordLinkPackets.PromptS2C.ID)) return false;
        if (!DiscordBot.isRunning() && !(cfg.webReady() && ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.WebChallenge.ID))
                && !(cfg.socialReady() && ServerConfigurationNetworking.canSend(handler, DiscordSocialPackets.Challenge.ID))) return false;
        return links.get(handler.getOwner().id()) == null;
    }

    private record Task(ServerConfigurationPacketListenerImpl handler) implements ConfigurationTask {
        @Override public void start(Consumer<Packet<?>> sender) {
            WAITING.put(handler.getOwner().id(), handler);
            sendPrompt(handler);
        }

        @Override public Type type() {
            return TASK;
        }
    }

    private static DiscordLinkPackets.PromptS2C prompt(UUID player, String name, boolean required) {
        String code = DiscordBot.links().createCode(player, name, System.currentTimeMillis());
        return new DiscordLinkPackets.PromptS2C(code, required, DiscordBot.botName(), (int) (DiscordLinks.CODE_TTL_MS / 1000));
    }

    private static void sendPrompt(ServerConfigurationPacketListenerImpl handler) {
        GameProfile owner = handler.getOwner();
        ServerConfigurationNetworking.send(handler, prompt(owner.id(), owner.name(), DiscordConfig.get().requireLink));
        DiscordSocialLink.offer(handler);
    }

    private static void onConfigurationAction(ServerConfigurationPacketListenerImpl handler, int action) {
        UUID id = handler.getOwner().id();
        if (WAITING.get(id) != handler) return;
        if (action == DiscordSocialPackets.START || action == DiscordSocialPackets.START_WEB || action == DiscordSocialPackets.CANCEL) {
            DiscordSocialLink.action(handler, action);
            return;
        }
        DiscordLinks links = DiscordBot.links();
        boolean linked = links != null && links.get(id) != null;
        if (action == DiscordLinkPackets.ACTION_NEW_CODE) {
            if (!linked && links != null) sendPrompt(handler);
            return;
        }
        if (action != DiscordLinkPackets.ACTION_CONTINUE) return;
        // 필수이면 연동을 기다리되, 모든 인증 수단이 꺼졌으면 가둬 두지 않는다.
        if (!linked && DiscordConfig.get().requireLink && (DiscordBot.isRunning() || DiscordConfig.get().authenticationReady())) return;
        DiscordSocialLink.action(handler, DiscordSocialPackets.CANCEL);
        WAITING.remove(id);
        handler.completeTask(TASK);
    }

    /** 게임 안에서 /discord link: 같은 화면을 연다. 화면을 못 여는 클라이언트면 false. */
    public static boolean openInGame(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, DiscordLinkPackets.PromptS2C.ID)) return false;
        ServerPlayNetworking.send(player, prompt(player.getUUID(), player.getScoreboardName(), false));
        DiscordSocialLink.offer(player);
        return true;
    }

    /** 디스코드에서 코드를 맞게 입력함. 서버 스레드에서 불린다. */
    static void onLinked(MinecraftServer server, UUID id, DiscordLinks.Link link) {
        AvatarRef ref = link.avatarRef();
        DiscordLinkPackets.DoneS2C done = new DiscordLinkPackets.DoneS2C(clip(link.shownName()), clip(link.username), ref == null ? "" : ref.spritePath());
        ServerConfigurationPacketListenerImpl waiting = WAITING.get(id);
        if (waiting != null) {
            // 완료 화면을 잠깐 보여 준 뒤 클라이언트가 ACTION_CONTINUE 를 보내면 접속을 마저 진행한다
            ServerConfigurationNetworking.send(waiting, done);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) return;
        if (ServerPlayNetworking.canSend(player, DiscordLinkPackets.DoneS2C.ID)) {
            ServerPlayNetworking.send(player, done);
        } else {
            player.sendSystemMessage(Component.literal("디스코드 계정 " + link.shownName() + " 와(과) 연동했습니다.").withStyle(ChatFormatting.GREEN));
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.codePointCount(0, s.length()) <= 32 ? s : s.substring(0, s.offsetByCodePoints(0, 32));
    }

    static boolean isWaiting(ServerConfigurationPacketListenerImpl handler) { return WAITING.get(handler.getOwner().id()) == handler; }

    /** Reload also refreshes open linking screens, including players still joining the server. */
    static void refreshStatuses(MinecraftServer server) {
        String message = "서버 설정이 갱신되었습니다. 다시 연결하세요.";
        WAITING.values().forEach(handler -> DiscordSocialLink.offer(handler, message));
        server.getPlayerList().getPlayers().forEach(player -> DiscordSocialLink.offer(player, message));
    }
}
