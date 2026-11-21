package dev.newyear.countdown.discord;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/** /discord link · unlink · status (플레이어), /discord reload (관리자). */
public final class DiscordCommands {
    private DiscordCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> dispatcher.register(Commands.literal("discord")
                .executes(DiscordCommands::status)
                .then(Commands.literal("link").executes(DiscordCommands::link))
                .then(Commands.literal("unlink").executes(DiscordCommands::unlink))
                .then(Commands.literal("status").executes(DiscordCommands::status))
                .then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(c -> {
                    DiscordBot.reload(c.getSource().getServer());
                    say(c, Component.literal("디스코드 설정을 다시 읽었습니다. "
                            + (DiscordBot.isRunning() ? "봇을 다시 연결하는 중입니다. /discord status 로 확인하세요." : "상태: " + DiscordBot.status())));
                    return 1;
                }))));
    }

    private static void say(CommandContext<CommandSourceStack> c, Component message) {
        c.getSource().sendSuccess(() -> message, false);
    }

    private static int fail(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static int link(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        DiscordLinks links = DiscordBot.links();
        if (links == null || !DiscordConfig.get().enabled) return fail(c, "이 서버는 디스코드 연동을 쓰지 않습니다.");
        if (!DiscordBot.isRunning() && !DiscordConfig.get().authenticationReady()) return fail(c, "디스코드 연결이 설정되지 않았습니다. 서버 관리자에게 알려 주세요.");
        // 연동은 채팅이 아니라 전용 화면에서 진행한다. 화면을 열 수 없는 클라이언트에만 채팅으로 안내한다.
        if (DiscordLinkGate.openInGame(player)) return 1;
        if (!DiscordBot.isRunning()) return fail(c, "Discord 웹 연동 화면을 지원하는 최신 모드가 필요합니다.");
        String code = links.createCode(player.getUUID(), player.getScoreboardName(), System.currentTimeMillis());
        String command = "/" + DiscordBot.CMD_LINK + " " + DiscordBot.OPT_CODE + ":" + code;
        MutableComponent copy = Component.literal(command).withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("눌러서 복사"))));
        say(c, Component.literal("디스코드에서 봇에게 다음 명령을 입력하세요 (10분 안에): ").append(copy));
        say(c, Component.literal("연동 코드: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(code).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        return 1;
    }

    private static int unlink(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        DiscordLinks links = DiscordBot.links();
        DiscordLinks.Link removed = links == null ? null : links.unlink(player.getUUID());
        if (removed == null) return fail(c, "연동된 디스코드 계정이 없습니다.");
        try {
            links.save();
        } catch (IOException e) {
            return fail(c, "연동은 해제했지만 기록을 저장하지 못했습니다: " + e.getMessage());
        }
        say(c, Component.literal("디스코드 계정 " + removed.shownName() + " 연동을 해제했습니다."));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        DiscordLinks links = DiscordBot.links();
        ServerPlayer player = c.getSource().getPlayer();
        if (player != null) {
            DiscordLinks.Link link = links == null ? null : links.get(player.getUUID());
            if (link == null) {
                say(c, Component.literal("연동된 디스코드 계정이 없습니다. /discord link 로 연동하세요.").withStyle(ChatFormatting.GRAY));
            } else {
                AvatarRef ref = link.avatarRef();
                MutableComponent line = Component.empty();
                if (ref != null) line.append(DiscordChat.avatar(ref, DiscordChat.profileHover(link))).append(" ");
                say(c, line.append(Component.literal("연동된 디스코드 계정: "))
                        .append(Component.literal(link.shownName()).withStyle(s -> s.withColor(DiscordChat.BLURPLE)))
                        .append(Component.literal(" @" + link.username).withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
        if (Commands.hasPermission(Commands.LEVEL_ADMINS).test(c.getSource())) {
            DiscordConfig cfg = DiscordConfig.get();
            say(c, Component.literal("봇 상태: " + DiscordBot.status()).withStyle(ChatFormatting.GRAY));
            say(c, Component.literal("웹 OAuth 인증: " + (cfg.webReady() ? "서버 설정 완료" : cfg.webSetupProblem())).withStyle(ChatFormatting.GRAY));
            say(c, Component.literal("Social SDK 인증: " + (cfg.socialReady() ? "서버 설정 완료" : "Client ID / Client Secret 설정 필요")).withStyle(ChatFormatting.GRAY));
            say(c, Component.literal("채팅 채널: " + (cfg.hasChannel() ? cfg.channel() : "없음 (channelId 를 넣으면 채팅을 잇습니다)")
                    + " · 연동 " + (links == null ? 0 : links.size()) + "명").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
