package dev.newyear.countdown.discord;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class DiscordSocialPackets {
    private DiscordSocialPackets() {}
    public static final int START = 2, CANCEL = 3, START_WEB = 4;
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("newyearcountdown", "discord_social_" + name));
    }
    public record Status(String clientId, String message) implements CustomPacketPayload {
        public static final Type<Status> ID = id("status");
        public static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(24), Status::clientId, ByteBufCodecs.stringUtf8(256), Status::message, Status::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Challenge(String session, String clientId, String challenge) implements CustomPacketPayload {
        public static final Type<Challenge> ID = id("challenge");
        public static final StreamCodec<ByteBuf, Challenge> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Challenge::session, ByteBufCodecs.stringUtf8(24), Challenge::clientId,
                ByteBufCodecs.stringUtf8(64), Challenge::challenge, Challenge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Response(String session, String code, String redirectUri) implements CustomPacketPayload {
        public static final Type<Response> ID = id("response");
        public static final StreamCodec<ByteBuf, Response> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Response::session, ByteBufCodecs.stringUtf8(2048), Response::code,
                ByteBufCodecs.stringUtf8(256), Response::redirectUri, Response::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
        @Override public String toString() { return "DiscordSocialResponse[authorization code redacted]"; }
    }
    public record WebStatus(String clientId, boolean codeAvailable, String message) implements CustomPacketPayload {
        public static final Type<WebStatus> ID = id("web_status");
        public static final StreamCodec<ByteBuf, WebStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(24), WebStatus::clientId, ByteBufCodecs.BOOL, WebStatus::codeAvailable,
                ByteBufCodecs.stringUtf8(256), WebStatus::message, WebStatus::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record WebChallenge(String session, String clientId, String challenge, String redirectUri) implements CustomPacketPayload {
        public static final Type<WebChallenge> ID = id("web_challenge");
        public static final StreamCodec<ByteBuf, WebChallenge> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), WebChallenge::session, ByteBufCodecs.stringUtf8(24), WebChallenge::clientId,
                ByteBufCodecs.stringUtf8(64), WebChallenge::challenge, ByteBufCodecs.stringUtf8(256), WebChallenge::redirectUri, WebChallenge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(Status.ID, Status.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(Challenge.ID, Challenge.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(Response.ID, Response.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Status.ID, Status.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Challenge.ID, Challenge.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(WebStatus.ID, WebStatus.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(WebChallenge.ID, WebChallenge.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WebStatus.ID, WebStatus.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WebChallenge.ID, WebChallenge.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Response.ID, Response.CODEC);
    }
}
