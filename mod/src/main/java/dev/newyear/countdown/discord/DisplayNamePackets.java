package dev.newyear.countdown.discord;

import dev.newyear.countdown.ModReg;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public final class DisplayNamePackets {
    private DisplayNamePackets() {}

    public record OpenS2C(UUID target, String accountName, DisplayNameStyle style, String avatarPath) implements CustomPacketPayload {
        public static final Type<OpenS2C> ID = new Type<>(ModReg.id("display_name_open_v2"));
        public static final StreamCodec<ByteBuf, OpenS2C> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, OpenS2C::target,
                ByteBufCodecs.stringUtf8(128), OpenS2C::accountName,
                DisplayNameStyle.CODEC, OpenS2C::style,
                ByteBufCodecs.stringUtf8(96), OpenS2C::avatarPath, OpenS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record SaveC2S(UUID target, DisplayNameStyle style, boolean reset) implements CustomPacketPayload {
        public static final Type<SaveC2S> ID = new Type<>(ModReg.id("display_name_save_v2"));
        public static final StreamCodec<ByteBuf, SaveC2S> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SaveC2S::target,
                DisplayNameStyle.CODEC, SaveC2S::style,
                ByteBufCodecs.BOOL, SaveC2S::reset, SaveC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record ResultS2C(UUID target, boolean success, String message) implements CustomPacketPayload {
        public static final Type<ResultS2C> ID = new Type<>(ModReg.id("display_name_result_v2"));
        public static final StreamCodec<ByteBuf, ResultS2C> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ResultS2C::target,
                ByteBufCodecs.BOOL, ResultS2C::success,
                ByteBufCodecs.stringUtf8(256), ResultS2C::message, ResultS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(OpenS2C.ID, OpenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResultS2C.ID, ResultS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SaveC2S.ID, SaveC2S.CODEC);
    }
}
