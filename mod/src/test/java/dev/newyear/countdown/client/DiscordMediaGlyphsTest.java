package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscordMediaGlyphsTest {
    @Test void inlineEmojiUsesTheTextCentreAndChatFadeWithoutTintOrShadow() {
        var view = new DiscordMediaCache.View(null, null, null, 72, 72);
        var glyph = new DiscordMediaGlyphs.ImageGlyph(view, 10, 20, 0x80FF0000, Style.EMPTY, 0, 0, 8, 9, 0);
        var output = new Vertices(); glyph.render(new Matrix4f(), output, 0, false);
        assertEquals(List.of(0x80FFFFFF, 0x80FFFFFF, 0x80FFFFFF, 0x80FFFFFF), output.colors);
        assertEquals(List.of(20.5f, 28.5f, 28.5f, 20.5f), output.y);
        assertEquals(List.of(0f, 1f, 1f, 0f), output.v);
        assertEquals(List.of(10f, 10f, 18f, 18f), output.x);
    }
    @Test void adjacentEmojiHaveNoAddedHorizontalPadding() {
        var view = new DiscordMediaCache.View(null, null, null, 64, 64);
        var first = new Vertices(); var second = new Vertices();
        new DiscordMediaGlyphs.ImageGlyph(view, 0, 0, -1, Style.EMPTY, 0, 0, 8, 9, 0).render(new Matrix4f(), first, 0, false);
        new DiscordMediaGlyphs.ImageGlyph(view, 8, 0, -1, Style.EMPTY, 0, 0, 8, 9, 0).render(new Matrix4f(), second, 0, false);
        assertEquals(first.x.get(2), second.x.getFirst());
    }
    @Test void stickerAndRuleStayCentredOnProfileForEveryChatSpacing() {
        for (double spacing : new double[]{0, .25, .5, .75, 1}) {
            int height = MediaLayout.lineHeight(spacing);
            float offset = MediaLayout.stickerOffset(spacing);
            float firstTop = Float.NaN, lastBottom = Float.NaN;
            for (int row = 0; row < 3; row++) {
                var image = new Vertices(); var rule = new Vertices();
                var view = new DiscordMediaCache.View(null, null, null, 60, 60);
                new DiscordMediaGlyphs.ImageGlyph(view, 80, row * height, -1, Style.EMPTY, 3, row, height * 3, height, offset)
                        .render(new Matrix4f(), image, 0, false);
                new DiscordMediaGlyphs.ImageGlyph(view, 72, row * height, -1, Style.EMPTY, -1, row, 8, height, offset)
                        .render(new Matrix4f(), rule, 0, false);
                assertEquals(image.y, rule.y);
                assertEquals(1f, rule.x.get(2) - rule.x.getFirst());
                if (row == 0) firstTop = image.y.getFirst();
                else assertEquals(lastBottom, image.y.getFirst());
                lastBottom = image.y.get(1);
            }
            assertEquals(3f * height, lastBottom - firstTop);
            assertEquals(height + 4.5f, (firstTop + lastBottom) / 2); // Native middle-row profile centre.
        }
    }
    @Test void thumbnailRowsDrawConsecutiveTextureSlicesInsideTheirReservedChatLines() {
        var view = new DiscordMediaCache.View(null, null, null, 128, 72);
        for (int row = 0; row < 8; row++) {
            var glyph = new DiscordMediaGlyphs.ImageGlyph(view, 4, 10 + row * 9, -1, Style.EMPTY, 8, row, 128, 9, -1);
            var output = new Vertices(); glyph.render(new Matrix4f(), output, 0, false);
            assertEquals(List.of(9f + row * 9, 18f + row * 9, 18f + row * 9, 9f + row * 9), output.y);
            assertEquals(row / 8f, output.v.getFirst()); assertEquals((row + 1) / 8f, output.v.get(1));
            assertEquals(glyph.top(), output.y.getFirst()); assertEquals(glyph.bottom(), output.y.get(1));
        }
    }
    private static final class Vertices implements VertexConsumer {
        final List<Float> x = new ArrayList<>(), y = new ArrayList<>(), v = new ArrayList<>(); final List<Integer> colors = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { this.x.add(x); this.y.add(y); return this; }
        @Override public VertexConsumer setColor(int color) { colors.add(color); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
        @Override public VertexConsumer setUv(float u, float v) { this.v.add(v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
