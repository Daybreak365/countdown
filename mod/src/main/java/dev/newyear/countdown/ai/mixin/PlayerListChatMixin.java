package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.discord.DiscordChat;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Predicate;

/** 디스코드와 연동한 플레이어의 채팅에는 이름 앞에 프로필 사진을 붙여서 내보낸다. */
@Mixin(PlayerList.class)
public abstract class PlayerListChatMixin {
    @ModifyVariable(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), argsOnly = true)
    private ChatType.Bound newyearcountdown$discordAvatar(ChatType.Bound bound, PlayerChatMessage message, Predicate<ServerPlayer> filter, ServerPlayer sender) {
        return DiscordChat.decorate(bound, sender);
    }
}
