package dev.newyear.countdown.discord;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

/** Server-authoritative display names and live tab-list refresh. */
public final class DisplayNames {
    private DisplayNames() {}
    private static DisplayNameStore store;
    private static final Map<UUID, UUID> EDITING = new HashMap<>();
    private static final Map<UUID, Component> TAB_NAMES = new HashMap<>();
    private static int ticks;

    public static void register() {
        DisplayNamePackets.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            EDITING.clear();
            TAB_NAMES.clear();
            ticks = 0;
            store = new DisplayNameStore(DiscordConfig.dir().resolve("newyearcountdown-display-names.json"));
            try { store.load(); }
            catch (IOException e) { NewYearCountdown.LOGGER.error("표시 이름을 읽지 못했습니다", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            store = null;
            EDITING.clear();
            TAB_NAMES.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            EDITING.remove(handler.player.getUUID());
            TAB_NAMES.remove(handler.player.getUUID());
        });
        // Also catches asynchronous Discord profile refresh, unlinking and /discord reload.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 20 == 0) refreshTabs(server);
        });
        ServerPlayNetworking.registerGlobalReceiver(DisplayNamePackets.SaveC2S.ID,
                (payload, context) -> save(context.player(), payload));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            var command = dispatcher.register(Commands.literal("displayname")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(c -> open(c.getSource(), c.getSource().getPlayerOrException()))
                    .then(Commands.argument("대상", StringArgumentType.greedyString())
                            .suggests((c, builder) -> {
                                Set<String> names = new LinkedHashSet<>();
                                for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                                    names.add(p.getScoreboardName());
                                    DisplayNameStyle style = get(p.getUUID());
                                    if (style != null) names.add(style.text());
                                    var link = DiscordBot.links() == null ? null : DiscordBot.links().get(p.getUUID());
                                    if (link != null) names.add(link.shownName());
                                }
                                return SharedSuggestionProvider.suggest(names, builder);
                            })
                            .executes(c -> openTarget(c.getSource(), StringArgumentType.getString(c, "대상")))));
            dispatcher.register(Commands.literal("표시이름")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(c -> open(c.getSource(), c.getSource().getPlayerOrException())).redirect(command));
        });
    }

    public static DisplayNameStyle get(UUID id) { return store == null ? null : store.get(id); }

    public static Component name(ServerPlayer player, Component fallback) {
        DisplayNameStyle style = get(player.getUUID());
        return style == null ? fallback : style.component().copy().withStyle(s -> s.applyTo(fallback.getStyle()));
    }

    public static Component tabName(ServerPlayer player, Component original) {
        Component fallback = original == null ? player.getDisplayName() : original;
        Component name = name(player, fallback);
        Component decorated = DiscordChat.withAvatar(player, name, true);
        return original == null && name == fallback && decorated == name ? null : decorated;
    }

    private static int openTarget(CommandSourceStack source, String raw) throws CommandSyntaxException {
        String target = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        if (target.length() >= 2 && target.startsWith("\"") && target.endsWith("\""))
            target = target.substring(1, target.length() - 1);
        List<ServerPlayer> matches = new ArrayList<>();
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (player.getScoreboardName().equalsIgnoreCase(target) || player.getUUID().toString().equalsIgnoreCase(target))
                return open(source, player);
            DisplayNameStyle style = get(player.getUUID());
            var link = DiscordBot.links() == null ? null : DiscordBot.links().get(player.getUUID());
            if ((style != null && style.text().equalsIgnoreCase(target))
                    || (link != null && (link.shownName().equalsIgnoreCase(target) || link.username.equalsIgnoreCase(target))))
                matches.add(player);
        }
        if (matches.size() == 1) return open(source, matches.getFirst());
        source.sendFailure(Component.literal(matches.isEmpty() ? "접속 중인 대상을 찾지 못했습니다. 계정 이름·표시 이름·디스코드 닉네임을 입력하세요."
                : "같은 닉네임의 플레이어가 여러 명입니다. 마인크래프트 계정 이름 또는 UUID를 입력하세요."));
        return 0;
    }

    private static int open(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer editor = source.getPlayerOrException();
        if (!ServerPlayNetworking.canSend(editor, DisplayNamePackets.OpenS2C.ID)) {
            source.sendFailure(Component.literal("표시 이름 편집기를 사용하려면 클라이언트 모드를 업데이트하세요."));
            return 0;
        }
        DisplayNameStyle style = get(target.getUUID());
        var link = DiscordBot.links() == null ? null : DiscordBot.links().get(target.getUUID());
        AvatarRef avatar = link == null ? null : link.avatarRef();
        EDITING.put(editor.getUUID(), target.getUUID());
        ServerPlayNetworking.send(editor, new DisplayNamePackets.OpenS2C(target.getUUID(), target.getScoreboardName(),
                style == null ? DisplayNameStyle.plain(target.getScoreboardName()) : style,
                DiscordConfig.get().enabled && DiscordConfig.get().showAvatarInChat && avatar != null ? avatar.spritePath() : ""));
        return 1;
    }

    private static void save(ServerPlayer editor, DisplayNamePackets.SaveC2S payload) {
        String error = null;
        if (!Commands.hasPermission(Commands.LEVEL_ADMINS).test(editor.createCommandSourceStack()))
            error = "표시 이름 변경은 OP만 할 수 있습니다.";
        else if (!payload.target().equals(EDITING.get(editor.getUUID())))
            error = "/displayname 명령으로 편집기를 다시 열어 주세요.";
        else if (store == null) error = "서버의 표시 이름 저장소가 준비되지 않았습니다.";
        else {
            try { store.set(payload.target(), payload.reset() ? null : payload.style().checked()); }
            catch (IllegalArgumentException e) { error = e.getMessage(); }
            catch (IOException e) {
                error = "저장하지 못했습니다. 서버 로그를 확인하세요.";
                NewYearCountdown.LOGGER.error("표시 이름을 저장하지 못했습니다", e);
            }
        }
        boolean success = error == null;
        if (success) {
            EDITING.remove(editor.getUUID());
            refreshTabs(editor.level().getServer());
        }
        ServerPlayNetworking.send(editor, new DisplayNamePackets.ResultS2C(payload.target(), success,
                success ? (payload.reset() ? "기본 이름으로 되돌렸습니다." : "표시 이름을 저장했습니다.") : error));
    }

    private static void refreshTabs(MinecraftServer server) {
        List<ServerPlayer> changed = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Component current = player.getTabListDisplayName();
            if (!TAB_NAMES.containsKey(player.getUUID()) || !Objects.equals(TAB_NAMES.get(player.getUUID()), current)) {
                TAB_NAMES.put(player.getUUID(), current);
                changed.add(player);
            }
        }
        if (!changed.isEmpty()) server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), changed));
    }
}
