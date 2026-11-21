package dev.newyear.countdown.photo;

import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.NewYearCountdown;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * 새해 네컷 포토부스: 블록 등록, 공용 상수(촬영 시간표·카메라 위치·선택지 수), 패킷, 플레이어별 포즈 동기화.
 *
 * 흐름: 렌즈 모양 입구를 우클릭(또는 안에서 아무 곳이나 우클릭)하면 부스 안 촬영 화면이 열린다. 이때부터는 나가기 버튼(또는 Esc)을
 * 누를 때까지 이동키로 밖에 나갈 수 없다. 포즈·프레임·필터를 고르고 촬영 시작을 누르면 그 순간 부스 안의 모두에게 같은 시간표가
 * 전달되고, 각자의 클라이언트가 같은 카메라로 네 컷을 찍어 한 장의 PNG 로 저장한다.
 */
public final class PhotoBooth {
    public static final int POSES = 13;
    public static final int FRAMES = 7;
    public static final int FILTERS = 3;
    public static final int SHOTS = 4;

    /** 촬영 시간표(틱): 첫 컷까지 5초, 이후 6초마다 한 컷. 컷 사이에 포즈를 바꾼다. */
    public static final int FIRST_SHOT = 100;
    public static final int SHOT_GAP = 120;
    public static final int TOTAL = FIRST_SHOT + (SHOTS - 1) * SHOT_GAP + 30;

    /**
     * 카메라(구조물 좌표): 키오스크가 있는 뒷벽 칸 안쪽, 사람 키보다 조금 높은 곳에서 커튼 쪽을 내려다본다.
     * 실내가 5x5 라 여러 줄로 서게 되므로, 높여서 내려다봐야 앞줄(앉기) 뒤에 선 사람 얼굴도 보인다.
     * 촬영 시점의 클라이언트는 뒷벽·키오스크를 그리지 않는다.
     */
    public static final double CAM_Y = 2.25, CAM_Z = -3.2;
    public static final double LOOK_Y = 1.2, LOOK_Z = 2.5;
    /** 사진 한 컷의 세로 시야각 절반(도)과 가로:세로 비율. */
    public static final double PHOTO_HALF_FOV = 21.0;
    public static final double PHOTO_ASPECT = 1.45;

    public static int captureTick(int shot) {
        return FIRST_SHOT + shot * SHOT_GAP;
    }

    /**
     * 커튼이 이 엔티티를 막는지. 클라이언트가 "촬영 화면을 쓰는 중인 내 플레이어" 로 바꿔 끼운다.
     * 움직임은 클라이언트가 계산하므로 클라이언트 쪽 충돌만으로 이동키로 나가는 것을 막을 수 있다.
     */
    public static volatile Predicate<Entity> LOCKED = e -> false;

    // ------------------------------------------------------------------ 블록·아이템

    private static BlockBehaviour.Properties settings() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PINK)
                .strength(1.5f, 6.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.IMMOVEABLE);
    }

    /** 마스터(실내 바닥 가운데)는 조명처럼 부스 안을 밝힌다: 사진이 어둡지 않게. */
    public static final Block BOOTH = ModReg.block("photo_booth", PhotoBoothBlock::new, settings().lightLevel(s -> 15));
    public static final Block BOOTH_PART = ModReg.block("photo_booth_part", PhotoBoothPartBlock::new, settings()
            .dynamicShape()
            .lightLevel(s -> PhotoBoothPartBlock.kind(s) == PhotoBoothLayout.Kind.FLOOR ? 12 : 0));
    public static final BlockEntityType<PhotoBoothBlockEntity> BOOTH_BE = ModReg.blockEntity("photo_booth", PhotoBoothBlockEntity::new, BOOTH);
    public static final Item BOOTH_ITEM = ModReg.item("photo_booth", p -> new PhotoBoothItem(BOOTH, p), new Item.Properties());

    // ------------------------------------------------------------------ 패킷

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> packetType(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, name));
    }

    /** 서버 → 클라이언트: 이 부스의 촬영 화면을 연다. */
    public record OpenS2C(BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenS2C> ID = packetType("photo_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenS2C::pos, OpenS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 내 포즈 (0 이면 해제). */
    public record LookC2S(int pose) implements CustomPacketPayload {
        public static final Type<LookC2S> ID = packetType("photo_look");
        public static final StreamCodec<RegistryFriendlyByteBuf, LookC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LookC2S::pose, LookC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 이 플레이어의 포즈. */
    public record LookS2C(UUID player, int pose) implements CustomPacketPayload {
        public static final Type<LookS2C> ID = packetType("photo_look_sync");
        public static final StreamCodec<RegistryFriendlyByteBuf, LookS2C> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, LookS2C::player, ByteBufCodecs.VAR_INT, LookS2C::pose, LookS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 프레임(커튼 색) 바꾸기. */
    public record FrameC2S(BlockPos pos, int frame) implements CustomPacketPayload {
        public static final Type<FrameC2S> ID = packetType("photo_frame");
        public static final StreamCodec<RegistryFriendlyByteBuf, FrameC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, FrameC2S::pos, ByteBufCodecs.VAR_INT, FrameC2S::frame, FrameC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 촬영 시작. */
    public record ShootC2S(BlockPos pos, int filter) implements CustomPacketPayload {
        public static final Type<ShootC2S> ID = packetType("photo_shoot");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShootC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ShootC2S::pos, ByteBufCodecs.VAR_INT, ShootC2S::filter, ShootC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 나가기 버튼. 입구 바깥으로 옮겨 준다. */
    public record LeaveC2S(BlockPos pos) implements CustomPacketPayload {
        public static final Type<LeaveC2S> ID = packetType("photo_leave");
        public static final StreamCodec<RegistryFriendlyByteBuf, LeaveC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LeaveC2S::pos, LeaveC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 참가자: 촬영 시간표(시작 월드 시간), 프레임, 필터, 함께 찍히는 사람 이름. */
    public record ShootS2C(BlockPos pos, int frame, int filter, long start, List<String> names) implements CustomPacketPayload {
        public static final Type<ShootS2C> ID = packetType("photo_shoot_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShootS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ShootS2C::pos,
                ByteBufCodecs.VAR_INT, ShootS2C::frame,
                ByteBufCodecs.VAR_INT, ShootS2C::filter,
                ByteBufCodecs.VAR_LONG, ShootS2C::start,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(16)), ShootS2C::names,
                ShootS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ 포즈 (서버 보관)

    private static final Map<UUID, Integer> POSE = new HashMap<>();

    private PhotoBooth() {}

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(OpenS2C.ID, OpenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LookS2C.ID, LookS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShootS2C.ID, ShootS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LookC2S.ID, LookC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FrameC2S.ID, FrameC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShootC2S.ID, ShootC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LeaveC2S.ID, LeaveC2S.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(LookC2S.ID, (payload, context) -> setPose(context.player(), payload.pose()));
        ServerPlayNetworking.registerGlobalReceiver(FrameC2S.ID, (payload, context) -> {
            PhotoBoothBlockEntity be = near(context.player(), payload.pos());
            if (be != null) be.setFrame(context.player(), payload.frame());
        });
        ServerPlayNetworking.registerGlobalReceiver(ShootC2S.ID, (payload, context) -> {
            PhotoBoothBlockEntity be = near(context.player(), payload.pos());
            if (be != null) be.shoot(context.player(), payload.filter());
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveC2S.ID, (payload, context) -> {
            PhotoBoothBlockEntity be = near(context.player(), payload.pos());
            if (be != null) be.leave(context.player());
        });

        // 나중에 보이기 시작한 사람에게도 지금 잡고 있는 포즈를 알려 준다
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            Integer pose = POSE.get(entity.getUUID());
            if (pose != null) ServerPlayNetworking.send(player, new LookS2C(entity.getUUID(), pose));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            if (POSE.remove(id) == null) return;
            LookS2C reset = new LookS2C(id, 0);
            for (ServerPlayer o : server.getPlayerList().getPlayers()) {
                if (o != handler.player) ServerPlayNetworking.send(o, reset);
            }
        });
    }

    private static PhotoBoothBlockEntity near(ServerPlayer p, BlockPos pos) {
        if (p.blockPosition().distSqr(pos) > 100) return null;
        return p.level().getBlockEntity(pos) instanceof PhotoBoothBlockEntity be ? be : null;
    }

    private static void setPose(ServerPlayer p, int pose) {
        pose = Mth.clamp(pose, 0, POSES - 1);
        Integer prev = pose == 0 ? POSE.remove(p.getUUID()) : POSE.put(p.getUUID(), pose);
        if (prev == null ? pose == 0 : prev == pose) return;
        LookS2C msg = new LookS2C(p.getUUID(), pose);
        ServerPlayNetworking.send(p, msg);
        for (ServerPlayer o : PlayerLookup.tracking(p)) {
            if (o != p) ServerPlayNetworking.send(o, msg);
        }
    }
}
