package dev.newyear.countdown.client;

import com.mojang.blaze3d.font.GlyphInfo;
import dev.newyear.countdown.discord.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.SingleSpriteSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class DiscordStickerLayoutTest {
    private static final DiscordMedia STICKER = new DiscordMedia("https://cdn.discordapp.com/stickers/123456789.png", "스티커 · 별", 160, 160, false, false);
    // Uses Minecraft's actual text splitter and object font advances, with no window or GPU.
    private static final Font FONT = new Font(new Font.Provider() {
        @Override public GlyphSource glyphs(FontDescription description) {
            if (description instanceof FontDescription.AtlasSprite sprite) {
                GlyphSource source = DiscordMediaGlyphs.source(sprite);
                if (source != null) return source;
                return fixed(8);
            }
            return fixed(6);
        }
        @Override public EffectGlyph effect() { return null; }
    });
    private static GlyphSource fixed(int width) {
        return new SingleSpriteSource(new BakedGlyph() {
            @Override public GlyphInfo info() { return GlyphInfo.simple(width); }
            @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) { return null; }
        });
    }
    private static Component prefix(String name) {
        return Component.empty().append(DiscordChat.discordIcon()).append(" ")
                .append(DiscordChat.avatar(new AvatarRef("123456789", AvatarRef.NO_AVATAR), Component.literal("프로필"))).append(" ")
                .append(Component.literal(name).withStyle(s -> s.withColor(0xAABBCC))).append(DiscordChat.separator());
    }
    private static List<FontDescription.AtlasSprite> sprites(FormattedText text) {
        var result = new ArrayList<FontDescription.AtlasSprite>();
        text.visit((style, value) -> {
            if (style.getFont() instanceof FontDescription.AtlasSprite sprite) result.add(sprite);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
    @Test void threeNativeRowsPlaceProfileOnlyInTheMiddleAndKeepLinksOnEveryImageSlice() {
        Component prefix = prefix("별빛");
        var block = DiscordStickerLayout.block(prefix, STICKER, FONT, 348, 0, 160, 160);
        var lines = FONT.splitIgnoringLanguage(block, 348);
        assertEquals(3, lines.size());
        assertEquals(List.of("gap", "rule", "sticker"), sprites(lines.get(0)).stream().map(s -> s.spriteId().getPath().split("/")[0]).toList());
        assertEquals(List.of(DiscordChat.DISCORD_ICON, DiscordChat.ATLAS, DiscordMediaGlyphs.ATLAS, DiscordMediaGlyphs.ATLAS),
                sprites(lines.get(1)).stream().map(FontDescription.AtlasSprite::atlasId).toList());
        assertEquals(List.of(FONT.width(lines.get(0)), FONT.width(lines.get(0)), FONT.width(lines.get(0))), lines.stream().map(FONT::width).toList());
        block.visit((style, value) -> {
            if (style.getFont() instanceof FontDescription.AtlasSprite sprite && sprite.spriteId().getPath().startsWith("sticker/"))
                assertInstanceOf(ClickEvent.OpenUrl.class, style.getClickEvent());
            return Optional.empty();
        }, Style.EMPTY);
        assertTrue(prefix.getString().contains("별빛"));
        assertTrue(sprites(prefix).stream().anyMatch(s -> s.atlasId().equals(DiscordChat.SEPARATOR_ATLAS)));
    }
    @Test void narrowChatAndLongStyledNamesNeverPushTheStickerOntoAnotherLine() {
        Component prefix = prefix("아주 긴 이름 ".repeat(8));
        for (int width : new int[]{20, 52, 90, 180, 348}) for (double spacing : new double[]{0, .5, 1}) {
            var block = DiscordStickerLayout.block(prefix, STICKER, FONT, width, spacing, 320, 100);
            var lines = FONT.splitIgnoringLanguage(block, width);
            assertEquals(3, lines.size(), "width=" + width + ", spacing=" + spacing);
            assertTrue(lines.stream().allMatch(line -> FONT.width(line) <= width));
        }
    }
    @Test void aspectRatioUsesDecodedDimensionsAndThreeChatLineHeights() {
        for (int[] size : new int[][]{{40, 40}, {120, 40}, {20, 40}}) for (double spacing : new double[]{0, 1}) {
            int height = MediaLayout.lineHeight(spacing);
            var block = DiscordStickerLayout.block(prefix("별"), STICKER, FONT, 348, spacing, size[0], size[1]);
            var image = sprites(block).stream().filter(s -> s.spriteId().getPath().startsWith("sticker/")).findFirst().orElseThrow();
            int width = Integer.parseInt(image.spriteId().getPath().split("/")[2]);
            assertEquals(Math.min(128, (int)Math.ceil(3.0 * height * size[0] / size[1])), width);
            float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY, drawnWidth = 0;
            for (int row = 0; row < 3; row++) {
                var slice = MediaLayout.slice(width, 3, row, height, size[0], size[1]);
                if (slice == null) continue;
                top = Math.min(top, row * height + slice.top()); bottom = Math.max(bottom, row * height + slice.bottom());
                drawnWidth = slice.right() - slice.left();
            }
            assertEquals((float)size[0] / size[1], drawnWidth / (bottom - top), .0001f);
            assertTrue(bottom - top <= 3 * height);
        }
    }
    @Test void onlyActualStickerUrlsReceiveTheStickerLayout() {
        assertTrue(STICKER.sticker());
        assertTrue(new DiscordMedia("https://media.discordapp.net/stickers/123456789.gif", "GIF", 160, 160, false, false).sticker());
        assertFalse(new DiscordMedia("https://cdn.discordapp.com/attachments/123/456/sticker.png", "스티커", 160, 160, false, false).sticker());
        assertFalse(new DiscordMedia("https://cdn.discordapp.com/emojis/123456789.gif", "emoji", 64, 64, true, false).sticker());
    }
    @Test void inlineEmojiAdvanceMatchesTheirImageAndMalformedLayoutIdsAreIgnored() {
        var source = DiscordMediaGlyphs.source(new FontDescription.AtlasSprite(DiscordMediaGlyphs.ATLAS,
                Identifier.fromNamespaceAndPath("newyearcountdown", STICKER.key() + "/0/0")));
        assertEquals(8, source.getGlyph(0).info().getAdvance());
        for (String path : new String[]{"gap/9999", "gap/-1", "rule/3", "sticker/" + STICKER.key() + "/0/0", "sticker/" + STICKER.key() + "/27/3"})
            assertNull(DiscordMediaGlyphs.source(new FontDescription.AtlasSprite(DiscordMediaGlyphs.ATLAS, Identifier.fromNamespaceAndPath("newyearcountdown", path))));
    }
}
