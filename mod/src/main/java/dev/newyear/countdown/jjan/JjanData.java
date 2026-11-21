package dev.newyear.countdown.jjan;

import dev.newyear.countdown.ModReg;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 짠 플레이어 프로필 (월드 저장 데이터): 술·브랜드·잔 선호, 취기 목표값과 마지막 계산 시각, 누적 짠 횟수, 효과 줄이기 설정.
 * 자리 점유와 진행 중 연출은 저장하지 않는다. 플레이어는 UUID 로 구분한다.
 */
public class JjanData extends SavedData {
    public static final class Profile {
        public String drink = "lager", variant = "", glass = "pint";
        public double target;
        public long updatedAt;
        public int totalClinks;
        public double consumedMl;
        public boolean reduced;
    }

    private static final SavedDataType<JjanData> TYPE = new SavedDataType<>(ModReg.id("jjan"), JjanData::new,
            CompoundTag.CODEC.xmap(JjanData::fromNbt, JjanData::toNbt), null);

    private final Map<UUID, Profile> profiles = new HashMap<>();

    public static JjanData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Profile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new Profile());
    }

    public void setPreference(UUID id, String drink, String variant, String glass) {
        Profile p = profile(id);
        p.drink = drink;
        p.variant = variant;
        p.glass = glass;
        setDirty();
    }

    public void touch() {
        setDirty();
    }

    private CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            CompoundTag c = new CompoundTag();
            c.store("Id", UUIDUtil.CODEC, e.getKey());
            c.putString("Drink", p.drink);
            c.putString("Variant", p.variant);
            c.putString("Glass", p.glass);
            c.putDouble("Target", p.target);
            c.putLong("At", p.updatedAt);
            c.putInt("Clinks", p.totalClinks);
            c.putDouble("Ml", p.consumedMl);
            c.putBoolean("Reduced", p.reduced);
            list.add(c);
        }
        nbt.put("Profiles", list);
        return nbt;
    }

    private static JjanData fromNbt(CompoundTag nbt) {
        JjanData data = new JjanData();
        ListTag list = nbt.getListOrEmpty("Profiles");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompoundOrEmpty(i);
            UUID id = c.read("Id", UUIDUtil.CODEC).orElse(null);
            if (id == null) continue;
            Profile p = new Profile();
            p.drink = c.getStringOr("Drink", "lager");
            p.variant = c.getStringOr("Variant", "");
            p.glass = c.getStringOr("Glass", "pint");
            p.target = Math.max(0, Math.min(JjanRules.INTOX_MAX, c.getDoubleOr("Target", 0)));
            p.updatedAt = c.getLongOr("At", 0);
            p.totalClinks = c.getIntOr("Clinks", 0);
            p.consumedMl = c.getDoubleOr("Ml", 0);
            p.reduced = c.getBooleanOr("Reduced", false);
            data.profiles.put(id, p);
        }
        return data;
    }
}
