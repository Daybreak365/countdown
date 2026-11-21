package dev.newyear.countdown.discord;

import dev.newyear.countdown.NewYearCountdown;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 디스코드 연동 화면용 패킷. 접속 중(설정 단계)에도, 게임 안에서도 같은 패킷을 쓴다.
 */
public final class DiscordLinkPackets {
    private DiscordLinkPackets() {}

    /** 화면을 닫고 계속 진행 (접속 중이면 접속을 마저 진행). */
    public static final int ACTION_CONTINUE = 0;
    /** 코드가 만료되어 새 코드를 요청. */
    public static final int ACTION_NEW_CODE = 1;

    /** 서버 → 클라: 연동 화면을 열고 이 코드를 보여라. required 면 연동 전에는 들어갈 수 없다. */
    public record PromptS2C(String code, boolean required, String botName, int secondsLeft) implements CustomPacketPayload {
        public static final Type<PromptS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_link_prompt"));
        public static final StreamCodec<ByteBuf, PromptS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), PromptS2C::code,
                ByteBufCodecs.BOOL, PromptS2C::required,
                ByteBufCodecs.stringUtf8(64), PromptS2C::botName,
                ByteBufCodecs.VAR_INT, PromptS2C::secondsLeft,
                PromptS2C::new);

        @Override public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 서버 → 클라: 연동이 끝났다. avatarPath 는 {@link AvatarRef#spritePath()} (없으면 빈 문자열). */
    public record DoneS2C(String displayName, String username, String avatarPath) implements CustomPacketPayload {
        public static final Type<DoneS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_link_done"));
        public static final StreamCodec<ByteBuf, DoneS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), DoneS2C::displayName,
                ByteBufCodecs.stringUtf8(64), DoneS2C::username,
                ByteBufCodecs.stringUtf8(96), DoneS2C::avatarPath,
                DoneS2C::new);

        @Override public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 클라 → 서버: 화면에서 누른 버튼. */
    public record ActionC2S(int action) implements CustomPacketPayload {
        public static final Type<ActionC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_link_action"));
        public static final StreamCodec<ByteBuf, ActionC2S> CODEC = ByteBufCodecs.VAR_INT.map(ActionC2S::new, ActionC2S::action);

        @Override public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(PromptS2C.ID, PromptS2C.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(DoneS2C.ID, DoneS2C.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(ActionC2S.ID, ActionC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PromptS2C.ID, PromptS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DoneS2C.ID, DoneS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionC2S.ID, ActionC2S.CODEC);
    }
}
