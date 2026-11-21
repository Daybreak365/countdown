package dev.newyear.countdown.client;

import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordAvatarGlyphsTest {
    @Test void portraitStaysInsideChatAndTabLineBoxesAtEveryGuiScale() {
        for (int scale : new int[]{1, 2, 3, 5, 8}) {
            for (int row : new int[]{0, 9, 18, 72}) {
                var glyph = new DiscordAvatarGlyphs.Instance(null, 12, row, -1, 0, 0, Style.EMPTY, 8);
                assertTrue(glyph.top() * scale >= row * scale, "portrait protrudes into the previous row");
                assertTrue(glyph.bottom() * scale <= (row + 9) * scale, "portrait protrudes into the next row");
                assertEquals((row + 4.5f) * scale, (glyph.top() + glyph.bottom()) / 2 * scale);
                assertEquals(8 * scale, (glyph.right() - glyph.left()) * scale);
            }
        }
    }

    @Test void separatorIsInsetAndSharesPortraitCentreWithoutChangingHorizontalSpacing() {
        var portrait = new DiscordAvatarGlyphs.Instance(null, 12, 30, -1, 0, 0, Style.EMPTY, 8);
        var separator = new DiscordAvatarGlyphs.Instance(null, 12, 30, -1, 0, 0, Style.EMPTY, 6);
        assertEquals(portrait.top() + 1, separator.top());
        assertEquals(portrait.bottom() - 1, separator.bottom());
        assertEquals(portrait.top() + portrait.bottom(), separator.top() + separator.bottom());
        assertEquals(portrait.left(), separator.left());
        assertEquals(portrait.right(), separator.right());
    }
}
