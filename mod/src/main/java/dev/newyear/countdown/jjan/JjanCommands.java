package dev.newyear.countdown.jjan;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * /jjan 명령어: GUI 와 같은 서비스 경로를 부른다(메뉴·한 모금·원샷·채우기·일어나기·효과 줄이기).
 * 관리자용: /jjan table list|inspect, /jjan reload (카탈로그를 다시 읽어 검증. 진행 중인 자리는 건드리지 않는다).
 * 점검용(관리자): /jjan table sit <자리>, /jjan table order <술> [잔]. 화면 없이 같은 서비스 경로로 앉히고 주문한다
 * (/execute as <플레이어> at @s run jjan table sit 2 처럼 다른 플레이어에게도 쓸 수 있다).
 */
public final class JjanCommands {
    private JjanCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, selection) -> d.register(Commands.literal("jjan")
                .then(Commands.literal("menu").executes(c -> act(c, JjanPackets.MENU)))
                .then(Commands.literal("order").executes(c -> act(c, JjanPackets.SELECT)))
                .then(Commands.literal("sip").executes(c -> act(c, JjanPackets.SIP)))
                .then(Commands.literal("shot").executes(c -> act(c, JjanPackets.SHOT)))
                .then(Commands.literal("refill").executes(c -> act(c, JjanPackets.REFILL)))
                .then(Commands.literal("raise").executes(c -> act(c, JjanPackets.RAISE)))
                .then(Commands.literal("leave").executes(c -> act(c, JjanPackets.LEAVE)))
                .then(Commands.literal("status").executes(JjanCommands::status))
                .then(Commands.literal("effects")
                        .then(Commands.literal("reduced").executes(c -> effects(c, true)))
                        .then(Commands.literal("normal").executes(c -> effects(c, false))))
                .then(Commands.literal("table").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("list").executes(JjanCommands::list))
                        .then(Commands.literal("inspect").executes(JjanCommands::inspect))
                        .then(Commands.literal("sit").then(Commands.argument("seat", IntegerArgumentType.integer(0, 15)).executes(JjanCommands::sit)))
                        .then(Commands.literal("order").then(Commands.argument("drink", StringArgumentType.word())
                                .executes(c -> order(c, ""))
                                .then(Commands.argument("glass", StringArgumentType.word())
                                        .executes(c -> order(c, StringArgumentType.getString(c, "glass")))))))
                .then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(JjanCommands::reload))));
    }

    private static int act(CommandContext<CommandSourceStack> c, int action) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        return JjanService.action(p, action, 0) ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        double shown = JjanService.displayed(p.getUUID());
        JjanData.Profile pr = JjanData.get(c.getSource().getServer()).profile(p.getUUID());
        Component stage = Component.translatable("jjan.newyearcountdown.stage." + JjanRules.STAGE_KEYS[JjanRules.stage(shown)]);
        c.getSource().sendSuccess(() -> Component.translatable("jjan.newyearcountdown.status", stage,
                String.format("%.2f", shown), pr.totalClinks, Math.round(pr.consumedMl)), false);
        return 1;
    }

    private static int effects(CommandContext<CommandSourceStack> c, boolean reduced) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        JjanService.setReduced(p, reduced);
        c.getSource().sendSuccess(() -> Component.translatable(reduced ? "jjan.newyearcountdown.effects_reduced" : "jjan.newyearcountdown.effects_normal"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<JjanTableEntity> tables = JjanService.tables();
        c.getSource().sendSuccess(() -> Component.literal("짠 테이블 " + tables.size() + "개 (불러온 것만)"), false);
        for (JjanTableEntity t : tables) {
            String line = t.describe().get(0) + (t.getLevel() == null ? "" : " [" + t.getLevel().dimension().identifier() + "]");
            c.getSource().sendSuccess(() -> Component.literal(" " + line), false);
        }
        return tables.size();
    }

    /** 가장 가까운(24블록 안) 테이블. 없으면 실패 메시지를 보내고 null. */
    private static JjanTableEntity nearest(CommandContext<CommandSourceStack> c) {
        Vec3 at = c.getSource().getPosition();
        JjanTableEntity best = null;
        double bestD = 24 * 24;
        for (JjanTableEntity t : JjanService.tables()) {
            if (t.getLevel() != c.getSource().getLevel()) continue;
            double d = t.getBlockPos().distToCenterSqr(at);
            if (d < bestD) { bestD = d; best = t; }
        }
        if (best == null) c.getSource().sendFailure(Component.literal("가까이에 짠 테이블이 없습니다"));
        return best;
    }

    /** 가장 가까운 테이블의 기준 위치·방향·점유 칸·좌석 상태. */
    private static int inspect(CommandContext<CommandSourceStack> c) {
        JjanTableEntity best = nearest(c);
        if (best == null) return 0;
        for (String line : best.describe()) c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** 점검용: 가장 가까운 테이블의 지정한 자리에 앉힌다 (의자 우클릭과 같은 경로). */
    private static int sit(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        JjanTableEntity best = p == null ? null : nearest(c);
        if (best == null) return 0;
        int seat = IntegerArgumentType.getInteger(c, "seat");
        if (seat >= best.def().capacity) {
            c.getSource().sendFailure(Component.literal("이 테이블의 자리는 0~" + (best.def().capacity - 1) + "번입니다"));
            return 0;
        }
        if (JjanService.ref(p.getUUID()) != null) {
            c.getSource().sendFailure(Component.translatable("jjan.newyearcountdown.leave_first"));
            return 0;
        }
        best.sit(p, seat);
        return JjanService.ref(p.getUUID()) != null ? 1 : 0;
    }

    /** 점검용: 화면 없이 주문한다 (주문 화면과 같은 검증 경로). 잔을 비우면 추천 잔. */
    private static int order(CommandContext<CommandSourceStack> c, String glass) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        JjanCatalog.Drink d = JjanCatalog.get().drink(StringArgumentType.getString(c, "drink"));
        if (d == null) {
            c.getSource().sendFailure(Component.translatable("jjan.newyearcountdown.bad_order"));
            return 0;
        }
        JjanService.order(p, d.id(), "", glass.isEmpty() ? d.glass() : glass);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        try {
            JjanCatalog cat = JjanCatalog.reload();
            c.getSource().sendSuccess(() -> Component.literal("짠 카탈로그: 술 " + cat.drinks().size() + "종, 잔 " + cat.publicGlasses().size()
                    + "종. 진행 중인 자리는 그대로 둡니다."), true);
            return 1;
        } catch (RuntimeException e) {
            c.getSource().sendFailure(Component.literal("카탈로그 오류, 이전 것을 계속 씁니다: " + e.getMessage()));
            return 0;
        }
    }
}
