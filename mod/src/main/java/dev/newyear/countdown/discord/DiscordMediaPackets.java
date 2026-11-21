package dev.newyear.countdown.discord;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class DiscordMediaPackets {
    private DiscordMediaPackets() {}
    public record Message(String id, Component prefix, String json, boolean update) implements CustomPacketPayload {
        public static final Type<Message> ID = new Type<>(Identifier.fromNamespaceAndPath("newyearcountdown", "discord_media_message"));
        public static final StreamCodec<ByteBuf, Message> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(24), Message::id, ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC, Message::prefix,
                ByteBufCodecs.stringUtf8(DiscordRichMessage.MAX_JSON), Message::json, ByteBufCodecs.BOOL, Message::update, Message::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
        @Override public String toString() { return "DiscordMediaMessage[" + id + "]"; }
    }
    public static void register() { PayloadTypeRegistry.clientboundPlay().register(Message.ID, Message.CODEC); }
}
