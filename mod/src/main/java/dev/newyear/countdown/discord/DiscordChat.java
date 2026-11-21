package dev.newyear.countdown.discord;

import dev.newyear.countdown.NewYearCountdown;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

/**
 * 채팅에 넣는 디스코드 프로필 사진 컴포넌트.
 * 바닐라의 스프라이트 오브젝트 컴포넌트를 그대로 쓰되 아틀라스 이름만 이 모드 것으로 준다. 클라이언트는 그 이름을 보고
 * 아틀라스 대신 디스코드 CDN 에서 받은 사진을 글자 한 칸 크기로 그린다 (client.DiscordAvatarGlyphs).
 */
public final class DiscordChat {
    public static final Identifier DISCORD_ICON = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_icon");
    private DiscordChat() {}

    /** 실제 아틀라스가 아니라 "디스코드 아바타"라는 표시로만 쓰는 이름. */
    public static final Identifier ATLAS = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_avatar");
    public static final Identifier SEPARATOR_ATLAS = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "chat_separator");
    public static final ResourceKey<ChatType> PROFILE_CHAT = ResourceKey.create(Registries.CHAT_TYPE,
            Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "profile"));
    /** 디스코드 블러플. 디스코드에서 온 이름에 쓴다. */
    public static final int BLURPLE = 0x8EA1E1;

    /** 글자 한 칸 크기의 프로필 사진. hover 는 마우스를 올렸을 때 보일 글(없으면 null). */
    public static MutableComponent avatar(AvatarRef ref, Component hover) {
        Identifier sprite = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, ref.spritePath());
        // 대체 글을 비워 두어 서버 로그나 내레이터에 스프라이트 이름이 찍히지 않게 한다
        MutableComponent c = Component.object(new AtlasSprite(ATLAS, sprite), Component.empty());
        if (hover != null) c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
        return c;
    }

    public static Component profileHover(DiscordLinks.Link link) {
        MutableComponent c = Component.literal("디스코드 ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(link.shownName()).withStyle(s -> s.withColor(BLURPLE)));
        if (link.username != null && !link.username.isBlank() && !link.username.equals(link.shownName()))
            c.append(Component.literal(" @" + link.username).withStyle(ChatFormatting.DARK_GRAY));
        return c;
    }

    /** 연동한 플레이어면 "사진 + 이름", 아니면 이름 그대로. */
    public static Component withAvatar(ServerPlayer player, Component name) {
        return withAvatar(player, name, false);
    }

    public static Component withAvatar(ServerPlayer player, Component name, boolean tab) {
        if (player == null || !DiscordConfig.get().enabled
                || !(tab ? DiscordConfig.get().showAvatarInTab : DiscordConfig.get().showAvatarInChat)) return name;
        DiscordLinks links = DiscordBot.links();
        DiscordLinks.Link link = links == null ? null : links.get(player.getUUID());
        AvatarRef ref = link == null ? null : link.avatarRef();
        if (ref == null) return name;
        return Component.empty().append(avatar(ref, profileHover(link))).append(Component.literal(" ")).append(name);
    }

    /** A real graphical glyph with its own spacing; alternative text is for logs/narration. */
    public static MutableComponent separator() {
        return Component.object(new AtlasSprite(SEPARATOR_ATLAS, SEPARATOR_ATLAS), Component.literal(": "))
                .withStyle(s -> s.withColor(0xFFFFFF).withBold(false).withItalic(false).withoutShadow());
    }

    public static Component tabAvatar(Component name) {
        if (name == null || name.getSiblings().size() < 3) return null;
        Component first = name.getSiblings().getFirst();
        return first.getContents() instanceof ObjectContents o && o.contents() instanceof AtlasSprite sprite
                && ATLAS.equals(sprite.atlas()) ? first : null;
    }

    public static Component withoutTabAvatar(Component name) {
        if (tabAvatar(name) == null) return name;
        MutableComponent text = Component.empty().setStyle(name.getStyle());
        for (int i = 2; i < name.getSiblings().size(); i++) text.append(name.getSiblings().get(i));
        return text;
    }

    /** 채팅 한 줄의 보낸 사람 이름 앞에 프로필 사진을 붙인다. PlayerList 믹스인이 부른다. */
    public static ChatType.Bound decorate(ChatType.Bound bound, ServerPlayer sender) {
        if (sender == null || bound == null) return bound;
        try {
            Component name = withAvatar(sender, DisplayNames.name(sender, bound.name()));
            // Keep whisper/team/emote semantics and signed message contents intact.
            if (bound.chatType().is(ChatType.CHAT)) {
                Component prefix = Component.empty().append(name).append(separator());
                return ChatType.bind(PROFILE_CHAT, sender.registryAccess(), prefix);
            }
            return new ChatType.Bound(bound.chatType(), name, bound.targetName());
        } catch (RuntimeException e) {
            // 꾸밈 때문에 채팅 자체가 막히면 안 된다
            return bound;
        }
    }

    /** 디스코드 채널에서 온 글 한 줄. */
    public static Component fromDiscord(AvatarRef ref, String name, String username, String text) {
        MutableComponent hover = Component.literal("디스코드에서 보냄").withStyle(ChatFormatting.GRAY);
        if (username != null && !username.isBlank()) hover.append(Component.literal(" @" + username).withStyle(ChatFormatting.DARK_GRAY));
        MutableComponent line = Component.empty().append(discordIcon()).append(" ");
        if (ref != null && DiscordConfig.get().showAvatarInChat) line.append(avatar(ref, hover)).append(Component.literal(" "));
        var links = DiscordBot.links();
        var playerId = links == null || ref == null ? null : links.playerOf(ref.userId());
        DisplayNameStyle custom = playerId == null ? null : DisplayNames.get(playerId);
        MutableComponent shown = custom == null ? Component.literal(name).withStyle(s -> s.withColor(BLURPLE)) : custom.component().copy();
        line.append(shown.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover))));
        return line.append(separator()).append(Component.literal(text));
    }

    public static MutableComponent discordIcon() {
        return Component.object(new AtlasSprite(DISCORD_ICON, DISCORD_ICON), Component.literal("[Discord] "))
                .withStyle(s -> s.withColor(0xFFFFFF).withBold(false).withItalic(false).withoutShadow()
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("디스코드에서 보낸 메시지"))));
    }
}
