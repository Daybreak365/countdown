package dev.newyear.countdown.photo;

import dev.newyear.countdown.ModSounds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 포토부스. 서버는 고른 프레임(커튼 색과 사진 프레임)과 촬영 시간표만 관리한다.
 * 사진은 부스 안 플레이어 각자의 클라이언트가 같은 시각에 같은 카메라 시점으로 찍어 저장하므로 이미지를 주고받지 않는다.
 */
public class PhotoBoothBlockEntity extends BlockEntity {
    private int frame;

    // 서버: 촬영 진행
    private long shootStart = -1;
    private final Set<UUID> participants = new HashSet<>();
    private final List<String> names = new ArrayList<>();
    private int filter;

    // 클라이언트: 연출(링 라이트 깜빡임, 키오스크 화면) 시작 시각
    public long animStart = Long.MIN_VALUE / 2;

    public PhotoBoothBlockEntity(BlockPos pos, BlockState state) {
        super(PhotoBooth.BOOTH_BE, pos, state);
    }

    public int frame() {
        return frame;
    }

    public Direction facing() {
        return getBlockState().getValue(PhotoBoothBlock.FACING);
    }

    private boolean shooting(long now) {
        return shootStart >= 0 && now - shootStart < PhotoBooth.TOTAL;
    }

    /**
     * 우클릭: 부스 안이면 촬영 화면을 열고, 밖에서 입구(렌즈·커튼)를 눌렀으면 빈 자리로 들어가 카메라를 보고 선 뒤 화면을 연다.
     * 밖에서 입구가 아닌 벽을 누르면 입구를 알려 준다.
     */
    public void use(ServerPlayer p, boolean entrance) {
        if (!(level instanceof ServerLevel sw)) return;
        boolean inside = PhotoBoothLayout.isInside(p, worldPosition, facing());
        if (!inside && !entrance) {
            p.sendOverlayMessage(Component.translatable("photobooth.newyearcountdown.use_entrance").withStyle(ChatFormatting.GOLD));
            return;
        }
        if (shooting(sw.getGameTime())) {
            if (inside && participants.contains(p.getUUID())) {
                ServerPlayNetworking.send(p, new PhotoBooth.OpenS2C(worldPosition));
                ServerPlayNetworking.send(p, shootPacket());
            } else {
                p.sendOverlayMessage(Component.translatable("photobooth.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        if (!inside) {
            if (p.isPassenger()) p.stopRiding();
            Vec3 spot = PhotoBoothLayout.entrySpot(sw, worldPosition, facing());
            p.connection.teleport(spot.x, spot.y, spot.z, PhotoBoothLayout.cameraFacingYaw(facing()), 0f);
            sw.playSound(null, spot.x, spot.y, spot.z, SoundEvents.WOOL_STEP, SoundSource.BLOCKS, 0.8f, 1.1f);
        }
        ServerPlayNetworking.send(p, new PhotoBooth.OpenS2C(worldPosition));
    }

    /** 나가기 버튼: 입구 바로 바깥으로 옮겨 준다(부스 안에 있을 때만). */
    public void leave(ServerPlayer p) {
        if (!(level instanceof ServerLevel sw)) return;
        if (!PhotoBoothLayout.interior(worldPosition, facing()).inflate(0.6).contains(p.position())) return;
        Vec3 out = PhotoBoothLayout.exitSpot(worldPosition, facing());
        float yaw = PhotoBoothLayout.cameraFacingYaw(facing()) + 180f;
        p.connection.teleport(out.x, out.y, out.z, yaw, 0f);
        sw.playSound(null, out.x, out.y, out.z, SoundEvents.WOOL_STEP, SoundSource.BLOCKS, 0.8f, 0.9f);
    }

    public void setFrame(ServerPlayer p, int f) {
        if (!(level instanceof ServerLevel sw) || shooting(sw.getGameTime())) return;
        if (!PhotoBoothLayout.isInside(p, worldPosition, facing())) return;
        int next = Mth.clamp(f, 0, PhotoBooth.FRAMES - 1);
        if (next == frame) return;
        frame = next;
        setChanged();
        sw.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        sw.playSound(null, worldPosition.above(), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.6f, 1.3f);
    }

    /** 촬영 시작: 지금 부스 안에 있는 모두가 함께 찍힌다. */
    public void shoot(ServerPlayer p, int filterIndex) {
        if (!(level instanceof ServerLevel sw)) return;
        long now = sw.getGameTime();
        if (shooting(now)) {
            p.sendOverlayMessage(Component.translatable("photobooth.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!PhotoBoothLayout.isInside(p, worldPosition, facing())) return;
        participants.clear();
        names.clear();
        for (ServerPlayer o : sw.getEntitiesOfClass(ServerPlayer.class, PhotoBoothLayout.interior(worldPosition, facing()).inflate(0.1),
                o -> !o.isSpectator() && PhotoBoothLayout.isInside(o, worldPosition, facing()))) {
            participants.add(o.getUUID());
            if (names.size() < 16) names.add(o.getName().getString());
        }
        participants.add(p.getUUID());
        if (!names.contains(p.getName().getString()) && names.size() < 16) names.add(p.getName().getString());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        filter = Mth.clamp(filterIndex, 0, PhotoBooth.FILTERS - 1);
        shootStart = now;
        PhotoBooth.ShootS2C packet = shootPacket();
        for (UUID id : participants) {
            ServerPlayer o = sw.getServer().getPlayerList().getPlayer(id);
            if (o != null) ServerPlayNetworking.send(o, packet);
        }
        sw.blockEvent(worldPosition, getBlockState().getBlock(), 1, 0);
        sw.playSound(null, worldPosition.above(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8f, 1.5f);
    }

    private PhotoBooth.ShootS2C shootPacket() {
        return new PhotoBooth.ShootS2C(worldPosition, frame, filter, shootStart, List.copyOf(names));
    }

    @Override
    public boolean triggerEvent(int type, int data) {
        if (type == 1 && level != null) {
            animStart = level.getGameTime();
            return true;
        }
        return super.triggerEvent(type, data);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, PhotoBoothBlockEntity be) {
        if (!(world instanceof ServerLevel sw) || be.shootStart < 0) return;
        long age = sw.getGameTime() - be.shootStart;
        for (int i = 0; i < PhotoBooth.SHOTS; i++) {
            if (age == PhotoBooth.captureTick(i)) {
                Vec3 cam = PhotoBoothLayout.toWorld(pos, be.facing(), 0, PhotoBooth.CAM_Y, PhotoBooth.CAM_Z);
                sw.playSound(null, cam.x, cam.y, cam.z, ModSounds.PHOTO_SHUTTER, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
        }
        if (age >= PhotoBooth.TOTAL || age < 0) {
            be.shootStart = -1;
            be.participants.clear();
            be.names.clear();
        }
    }

    // ------------------------------------------------------------------ 저장·동기화

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("frame", frame);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        frame = Mth.clamp(in.getIntOr("frame", 0), 0, PhotoBooth.FRAMES - 1);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return saveWithoutMetadata(lookup);
    }
}
