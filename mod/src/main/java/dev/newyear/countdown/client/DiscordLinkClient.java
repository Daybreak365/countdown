package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordLinkPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 서버가 보낸 디스코드 연동 패킷을 연동 화면에 전달한다. */
public final class DiscordLinkClient {
    private DiscordLinkClient() {}

    public static void registerSocial() {
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.WebStatus.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof DiscordLinkScreen open) open.webStatus(payload);
                }));
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.WebChallenge.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof DiscordLinkScreen open) open.webChallenge(payload);
                }));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.WebStatus.ID, (payload, context) -> {
                    if (context.client().gui.screen() instanceof DiscordLinkScreen open) open.webStatus(payload);
                });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.WebChallenge.ID, (payload, context) -> {
                    if (context.client().gui.screen() instanceof DiscordLinkScreen open) open.webChallenge(payload);
                });
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.Status.ID,
                (payload, context) -> context.client().execute(() -> onSocialStatus(context.client(), payload)));
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.Challenge.ID,
                (payload, context) -> context.client().execute(() -> onSocialChallenge(context.client(), payload)));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.Status.ID, (payload, context) -> onSocialStatus(context.client(), payload));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                dev.newyear.countdown.discord.DiscordSocialPackets.Challenge.ID, (payload, context) -> onSocialChallenge(context.client(), payload));
    }
    private static void onSocialStatus(Minecraft mc, dev.newyear.countdown.discord.DiscordSocialPackets.Status payload) {
        if (mc.gui.screen() instanceof DiscordLinkScreen open) open.socialStatus(payload);
    }
    private static void onSocialChallenge(Minecraft mc, dev.newyear.countdown.discord.DiscordSocialPackets.Challenge payload) {
        if (mc.gui.screen() instanceof DiscordLinkScreen open) open.socialChallenge(payload);
    }

    /** leave 는 접속을 그만둘 때 연결을 끊는 동작 (게임 안에서 연 화면이면 null). */
    public static void onPrompt(Minecraft mc, boolean joining, DiscordLinkPackets.PromptS2C payload, Runnable leave) {
        if (mc.gui.screen() instanceof DiscordLinkScreen open) {
            open.setCode(payload);
            return;
        }
        // 접속 중에는 지금 보이는 "접속 중" 화면을 기억해 두었다가 닫을 때 되돌린다
        mc.gui.setScreen(new DiscordLinkScreen(joining ? mc.gui.screen() : null, joining, payload, leave));
    }

    public static void onDone(Minecraft mc, DiscordLinkPackets.DoneS2C payload) {
        if (mc.gui.screen() instanceof DiscordLinkScreen open) {
            open.setDone(payload);
        } else if (mc.player != null) {
            // 화면을 닫은 뒤에 연동이 끝났으면 화면 위쪽 알림으로만 알린다
            mc.player.sendOverlayMessage(Component.literal("디스코드 계정 " + payload.displayName() + " 와(과) 연동했습니다."));
        }
    }
}
