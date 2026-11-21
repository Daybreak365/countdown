package dev.newyear.countdown.jjan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 짠 의자 좌석: 보이지 않는 탈것. 플레이어가 타면 바닐라 앉은 자세가 되고, 고개는 자유롭게 돌릴 수 있으며 이동키로 밀리지 않는다.
 * 테이블 위치·자리 번호·앉기 전 위치를 저장해 둔다: 앉은 채로 게임이 꺼졌다가 다시 들어오면 좌석은 정리되지만 플레이어는 의자 옆 안전한 자리에 내린다.
 * 일어나기(Shift)·사망·텔레포트·접속 종료 등 모든 내리기는 removePassenger 로 모여 같은 퇴석 정리를 거친다.
 */
public class JjanSeatEntity extends Entity {
    private BlockPos table = BlockPos.ZERO;
    private int seat = -1;
    private Vec3 before;
    private boolean retired;

    public JjanSeatEntity(EntityType<? extends JjanSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
        setInvisible(true);
    }

    void bind(BlockPos table, int seat, Vec3 before) {
        this.table = table.immutable();
        this.seat = seat;
        this.before = before;
    }

    /** 주인이 떠난 좌석: 다음 틱에 정리된다. */
    void retire() {
        retired = true;
    }

    public BlockPos table() { return table; }
    public int seat() { return seat; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        table = BlockPos.of(in.getLongOr("table", 0L));
        seat = in.getIntOr("seat", -1);
        if (in.getIntOr("has_before", 0) != 0) before = new Vec3(in.getDoubleOr("bx", 0), in.getDoubleOr("by", 0), in.getDoubleOr("bz", 0));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.putLong("table", table.asLong());
        out.putInt("seat", seat);
        if (before != null) {
            out.putInt("has_before", 1);
            out.putDouble("bx", before.x);
            out.putDouble("by", before.y);
            out.putDouble("bz", before.z);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof net.minecraft.world.entity.player.Player;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        // 주인이 없거나 테이블이 사라진 좌석은 바로 정리한다 (서버 재시작 뒤 남은 것도)
        if (getPassengers().isEmpty() || retired || seat < 0 || !(level().getBlockEntity(table) instanceof JjanTableEntity be) || !be.owns(this)) {
            ejectPassengers();
            discard();
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && passenger instanceof ServerPlayer sp) JjanService.onDismounted(sp, this);
    }

    /** 일어날 자리: 의자 뒤·옆의 출구 후보 중 발밑과 머리 공간이 빈 곳 → 앉기 전 위치 → 월드 스폰. */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (level().getBlockEntity(table) instanceof JjanTableEntity be) {
            Vec3 exit = be.safeExit(seat, passenger);
            if (exit != null) return exit;
        }
        if (before != null) return before;
        if (level() instanceof ServerLevel sl) return Vec3.atBottomCenterOf(sl.getRespawnData().pos());
        return super.getDismountLocationForPassenger(passenger);
    }
}
