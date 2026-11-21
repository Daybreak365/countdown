package dev.newyear.countdown.jjan;

import dev.newyear.countdown.NewYearCountdown;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 짠 패킷. 클라이언트는 입력 의도(행동·주문)만 보내고, 서버가 확정한 상태(테이블 스냅숏)를 받아 그린다.
 * 술·잔은 카탈로그 번호로 보낸다(양쪽이 같은 모드 jar 의 같은 카탈로그를 쓴다).
 */
public final class JjanPackets {
    private JjanPackets() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, name));
    }

    // 행동 종류
    public static final int MENU = 0, SELECT = 1, SIP = 2, SHOT = 3, REFILL = 4, RAISE = 5, SAY = 6, LEAVE = 7, MENU_CLOSED = 8, EAT = 9, ANJU = 10;

    /** 클라이언트 → 서버: 행동 요청. arg 는 건배사 번호, 안주를 집어 먹을 접시의 자리(EAT) 등. */
    public record ActionC2S(int action, int arg) implements CustomPacketPayload {
        public static final Type<ActionC2S> ID = id("jjan_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ActionC2S::action, ByteBufCodecs.VAR_INT, ActionC2S::arg, ActionC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 든 잔을 옮길 목표점(테이블 로컬 좌표). 잔을 든 동안 마우스는 시선 대신 이 점을 움직인다. */
    public record AimC2S(float x, float z) implements CustomPacketPayload {
        public static final Type<AimC2S> ID = id("jjan_aim");
        public static final StreamCodec<RegistryFriendlyByteBuf, AimC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, AimC2S::x, ByteBufCodecs.FLOAT, AimC2S::z, AimC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 주문(술·브랜드·잔). 서버가 카탈로그로 검증한다. */
    public record OrderC2S(String drink, String variant, String glass) implements CustomPacketPayload {
        public static final Type<OrderC2S> ID = id("jjan_order");
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), OrderC2S::drink, ByteBufCodecs.stringUtf8(64), OrderC2S::variant,
                ByteBufCodecs.stringUtf8(64), OrderC2S::glass, OrderC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 화면 열기 (screen 0 = 술 선택, 1 = 개인 메뉴). 선택 화면에는 이전 선택을 기본값으로 준다. */
    public record OpenS2C(BlockPos pos, int seat, int screen, String drink, String variant, String glass) implements CustomPacketPayload {
        public static final Type<OpenS2C> ID = id("jjan_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenS2C::pos, ByteBufCodecs.VAR_INT, OpenS2C::seat, ByteBufCodecs.VAR_INT, OpenS2C::screen,
                ByteBufCodecs.stringUtf8(64), OpenS2C::drink, ByteBufCodecs.stringUtf8(64), OpenS2C::variant,
                ByteBufCodecs.stringUtf8(64), OpenS2C::glass, OpenS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 퇴석 등으로 술자리 화면을 닫는다. */
    public record CloseS2C(int reason) implements CustomPacketPayload {
        public static final Type<CloseS2C> ID = id("jjan_close");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CloseS2C::reason, CloseS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * 한 자리의 확정 상태. 술·잔은 카탈로그 번호(-1 = 없음), 위치는 테이블 로컬 좌표(잔 바닥).
     * anju = 접시에 남은 안주 조각 수, anjuEater·anjuTick = 마지막으로 집어 간 자리와 그 시각(-1 = 없음).
     * anjuId = 접시에 놓인 안주 번호(-1 = 없음).
     */
    public record SeatSnap(int seat, UUID player, int drink, int variant, int glass, float fill, float fillFrom,
                           float x, float y, float z, int pose, int action, long actionStart, int actionLen,
                           float drunk, int say, long sayTick, int clinks, int anju, int anjuEater, long anjuTick, int anjuId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SeatSnap> CODEC = StreamCodec.of((buf, s) -> {
            buf.writeVarInt(s.seat);
            UUIDUtil.STREAM_CODEC.encode(buf, s.player);
            buf.writeVarInt(s.drink + 1);
            buf.writeVarInt(s.variant + 1);
            buf.writeVarInt(s.glass + 1);
            buf.writeFloat(s.fill);
            buf.writeFloat(s.fillFrom);
            buf.writeFloat(s.x);
            buf.writeFloat(s.y);
            buf.writeFloat(s.z);
            buf.writeByte(s.pose);
            buf.writeByte(s.action);
            buf.writeVarLong(s.actionStart);
            buf.writeVarInt(s.actionLen);
            buf.writeFloat(s.drunk);
            buf.writeVarInt(s.say + 1);
            buf.writeVarLong(s.sayTick);
            buf.writeVarInt(s.clinks);
            buf.writeVarInt(s.anju);
            buf.writeVarInt(s.anjuEater + 1);
            buf.writeVarLong(s.anjuTick);
            buf.writeVarInt(s.anjuId + 1);
        }, buf -> new SeatSnap(buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt() - 1, buf.readVarInt() - 1,
                buf.readVarInt() - 1, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readByte(), buf.readByte(), buf.readVarLong(), buf.readVarInt(), buf.readFloat(), buf.readVarInt() - 1,
                buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readVarLong(), buf.readVarInt() - 1));
    }

    /** 서버 → 주변 클라이언트: 테이블 전체 스냅숏 (초당 10회, 바뀌었을 때). tick 이 오래된 것은 무시한다. */
    public record TableS2C(BlockPos pos, long tick, List<SeatSnap> seats) implements CustomPacketPayload {
        public static final Type<TableS2C> ID = id("jjan_table");
        public static final StreamCodec<RegistryFriendlyByteBuf, TableS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TableS2C::pos, ByteBufCodecs.VAR_LONG, TableS2C::tick,
                SeatSnap.CODEC.apply(ByteBufCodecs.list(32)), TableS2C::seats, TableS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 취기 표시값(이름표 색·HUD). reduced = 받는 사람의 효과 줄이기 설정. */
    public record IntoxS2C(List<UUID> players, List<Float> levels, boolean reduced) implements CustomPacketPayload {
        public static final Type<IntoxS2C> ID = id("jjan_intox");
        public static final StreamCodec<RegistryFriendlyByteBuf, IntoxS2C> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(1024)), IntoxS2C::players,
                ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(1024)), IntoxS2C::levels,
                ByteBufCodecs.BOOL, IntoxS2C::reduced, IntoxS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
