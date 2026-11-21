package dev.newyear.countdown.jjan;

import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.NewYearCountdown;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * 짠(술자리 건배): 테이블 6종의 블록·아이템, 좌석 엔티티, 소리, 패킷, 서버 이벤트 등록.
 * 웹 원작(jjan)의 규칙을 서버 권위로 옮긴 것으로, 인벤토리 아이템은 지급하지 않는다(잔·술은 테이블 위 연출).
 */
public final class Jjan {
    public static final List<Block> MASTERS = new ArrayList<>();
    public static final List<Block> PARTS = new ArrayList<>();
    public static final List<Item> ITEMS = new ArrayList<>();

    private static BlockBehaviour.Properties settings() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(1.5f, 6.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .noLootTable()
                .dynamicShape()
                .pushReaction(PushReaction.IMMOVEABLE);
    }

    static {
        for (JjanTables.Def def : JjanTables.ALL) {
            Block master = ModReg.block("jjan_table_" + def.id, p -> new JjanTableBlock(p, def), settings());
            Block part = ModReg.block("jjan_table_" + def.id + "_part", p -> new JjanPartBlock(p, def), settings());
            MASTERS.add(master);
            PARTS.add(part);
            ITEMS.add(ModReg.blockItem("jjan_table_" + def.id, p -> new JjanTableItem(master, p, def), new Item.Properties()));
        }
    }

    public static final BlockEntityType<JjanTableEntity> TABLE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            ModReg.id("jjan_table"), new BlockEntityType<>(JjanTableEntity::new, Set.copyOf(new HashSet<>(MASTERS))));

    public static final EntityType<JjanSeatEntity> SEAT = ModReg.entity("jjan_seat",
            EntityType.Builder.<JjanSeatEntity>of(JjanSeatEntity::new, MobCategory.MISC)
                    .sized(0.1f, 0.1f).noSummon()   // noSave 를 붙이면 바닐라 startRiding 이 거부한다 (canSerialize 검사). 남은 좌석은 tick 에서 스스로 정리한다
.passengerAttachments(0f).clientTrackingRange(8).updateInterval(20));

    public static final SoundEvent POUR = sound("jjan_pour");
    public static final SoundEvent FIZZ = sound("jjan_fizz");
    public static final SoundEvent GULP = sound("jjan_gulp");
    public static final SoundEvent HICCUP = sound("jjan_hiccup");
    private static final String[] MATERIALS = {"glass", "thick", "crystal", "metal", "ceramic", "wood"};
    private static final SoundEvent[] CLINKS = new SoundEvent[MATERIALS.length];

    static {
        for (int i = 0; i < MATERIALS.length; i++) CLINKS[i] = sound("jjan_clink_" + MATERIALS[i]);
    }

    private Jjan() {}

    private static SoundEvent sound(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** 잔 재질별 짠 소리 (모르는 재질은 유리). */
    public static SoundEvent clink(String material) {
        for (int i = 0; i < MATERIALS.length; i++) if (MATERIALS[i].equals(material)) return CLINKS[i];
        return CLINKS[0];
    }

    public static void init() {
        JjanCatalog.get();   // 잘못된 카탈로그는 시작할 때 바로 드러나게

        PayloadTypeRegistry.serverboundPlay().register(JjanPackets.ActionC2S.ID, JjanPackets.ActionC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JjanPackets.OrderC2S.ID, JjanPackets.OrderC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JjanPackets.AimC2S.ID, JjanPackets.AimC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JjanPackets.OpenS2C.ID, JjanPackets.OpenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JjanPackets.CloseS2C.ID, JjanPackets.CloseS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JjanPackets.TableS2C.ID, JjanPackets.TableS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JjanPackets.IntoxS2C.ID, JjanPackets.IntoxS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(JjanPackets.ActionC2S.ID,
                (payload, context) -> JjanService.action(context.player(), payload.action(), payload.arg()));
        ServerPlayNetworking.registerGlobalReceiver(JjanPackets.AimC2S.ID,
                (payload, context) -> JjanService.aim(context.player(), payload.x(), payload.z()));
        ServerPlayNetworking.registerGlobalReceiver(JjanPackets.OrderC2S.ID,
                (payload, context) -> JjanService.order(context.player(), payload.drink(), payload.variant(), payload.glass()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> JjanService.join(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> JjanService.disconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(JjanService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(JjanService::stopAll);
        JjanCommands.register();
    }
}
