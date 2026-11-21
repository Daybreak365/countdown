package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordChat;
import dev.newyear.countdown.discord.DiscordMedia;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Three native chat lines: blank/profile/blank, a continuous rule, and three image slices. */
final class DiscordStickerLayout {
    private DiscordStickerLayout() {}

    static Component block(Component prefix, DiscordMedia ref, Font font, int chatWidth, double spacing, int imageWidth, int imageHeight) {
        int wanted = Math.clamp((int)Math.ceil(3.0 * MediaLayout.lineHeight(spacing) * imageWidth / Math.max(1, imageHeight)), 1, 128);
        int reserve = Math.min(wanted, Math.max(8, chatWidth / 3));
        Component profile = fit(profile(prefix), font, Math.max(0, chatWidth - 8 - reserve));
        int profileWidth = font.width(profile);
        int stickerWidth = Math.max(1, Math.min(wanted, chatWidth - profileWidth - 8));
        var result = Component.empty();
        for (int row = 0; row < 3; row++) {
            if (row != 0) result.append("\n");
            result.append(row == 1 ? profile : sprite("gap/" + profileWidth, Component.empty(), Style.EMPTY));
            result.append(sprite("rule/" + row, row == 1 ? Component.literal(": ") : Component.empty(), Style.EMPTY));
            result.append(sprite("sticker/" + ref.key() + "/" + stickerWidth + "/" + row,
                    row == 1 ? Component.literal("[" + ref.label() + "]") : Component.empty(), DiscordMediaClient.mediaStyle(ref)));
        }
        return result;
    }

    static Component profile(Component prefix) {
        var result = prefix.plainCopy().setStyle(prefix.getStyle());
        for (Component child : prefix.getSiblings()) {
            if (child.getContents() instanceof ObjectContents object && object.contents() instanceof AtlasSprite sprite
                    && DiscordChat.SEPARATOR_ATLAS.equals(sprite.atlas())) break;
            result.append(child);
        }
        return result;
    }

    private static Component fit(Component profile, Font font, int width) {
        if (font.width(profile) <= width) return profile;
        Component ellipsis = Component.literal("…").withStyle(s -> s.withColor(DiscordChat.BLURPLE)
                .withHoverEvent(new HoverEvent.ShowText(profile)));
        int ending = font.width(ellipsis);
        if (width < ending) return Component.empty();
        var clipped = Component.empty();
        font.substrByWidth(profile, width - ending).visit((style, text) -> {
            // Object glyphs keep their AtlasSprite font, including avatars and the Discord icon.
            clipped.append(Component.literal(text).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return clipped.append(ellipsis);
    }

    private static Component sprite(String path, Component alternative, Style style) {
        return Component.object(new AtlasSprite(DiscordMediaGlyphs.ATLAS, Identifier.fromNamespaceAndPath("newyearcountdown", path)), alternative)
                .setStyle(style.withColor(0xFFFFFF).withBold(false).withItalic(false).withoutShadow());
    }
}
