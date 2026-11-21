package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordMedia;
import dev.newyear.countdown.discord.DiscordRichMessage;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Reveal consent belongs to one message on this client, never to the shared image cache. */
final class DiscordSpoilers {
    static final Identifier REVEAL = Identifier.fromNamespaceAndPath("newyearcountdown", "reveal_discord_spoiler");
    private final Set<String> revealed = new HashSet<>();
    record Target(String messageId, String mediaKey) {}

    DiscordMedia visible(DiscordMedia ref) {
        return ref.hidden() && revealed.contains(ref.key())
                ? new DiscordMedia(ref.url(), ref.label(), ref.width(), ref.height(), ref.inline(), false) : ref;
    }

    boolean reveal(DiscordRichMessage body, String key) {
        return body.media().stream().anyMatch(ref -> ref.hidden() && ref.key().equals(key)) && revealed.add(key);
    }

    void retain(DiscordRichMessage body) {
        revealed.retainAll(body.media().stream().filter(DiscordMedia::hidden).map(DiscordMedia::key).collect(Collectors.toSet()));
    }

    static Component cover(String messageId, DiscordMedia ref) {
        return Component.literal("[스포일러 이미지 · 클릭하여 보기]").withStyle(style -> style
                .withColor(0x9FAFBE).withBold(false).withItalic(false).withUnderlined(true)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("이 이미지의 스포일러를 해제합니다.")))
                .withClickEvent(new ClickEvent.Custom(REVEAL, Optional.of(StringTag.valueOf(messageId + "/" + ref.key())))));
    }

    /** Consume our event even if stale/malformed; it must never become a server custom-click packet. */
    static boolean click(ClickEvent event, Consumer<Target> reveal) {
        if (!(event instanceof ClickEvent.Custom custom) || !REVEAL.equals(custom.id())) return false;
        if (custom.payload().orElse(null) instanceof StringTag payload) {
            String value = payload.value();
            if (value.length() <= 85 && value.matches("[0-9]{5,20}/[a-f0-9]{64}")) {
                int slash = value.indexOf('/');
                reveal.accept(new Target(value.substring(0, slash), value.substring(slash + 1)));
            }
        }
        return true;
    }
}
