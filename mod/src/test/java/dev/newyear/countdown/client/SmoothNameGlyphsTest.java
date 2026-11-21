package dev.newyear.countdown.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.newyear.countdown.discord.DisplayNameStyle;
import dev.newyear.countdown.discord.GradientFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmoothNameGlyphsTest {
    private static final int RED = 0xFF0000, BLUE = 0x0000FF;
    private static final GlyphSource BASE = new GlyphSource() {
        @Override public BakedGlyph getGlyph(int codePoint) {
            // Real Minecraft spacing glyphs have metrics but createGlyph returns null.
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x3164)
                return new EmptyGlyph(4).bake(null);
            int width = codePoint == 'i' ? 3 : 9;
            return new BakedGlyph() {
                @Override public GlyphInfo info() { return GlyphInfo.simple(width + 1); }
                @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float bold, float shadow) {
                    return new Shape(x, y, width + (style.isBold() ? bold : 0), color, shadowColor, style);
                }
            };
        }
        @Override public BakedGlyph getRandomGlyph(RandomSource random, int codePoint) { return getGlyph(codePoint); }
    };

    private record Shape(float x, float y, float width, int color, int shadowColor, Style style) implements TextRenderable.Styled {
        @Override public void render(Matrix4fc pose, VertexConsumer buffer, int light, boolean noDepth) {
            if (shadowColor != 0) quad(buffer, pose, x + 1, shadowColor);
            quad(buffer, pose, x, color);
        }
        private void quad(VertexConsumer buffer, Matrix4fc pose, float left, int tint) {
            buffer.addVertex(pose, left, y, 0).setColor(tint).setUv(0, 0).setLight(0);
            buffer.addVertex(pose, left, y + 8, 0).setColor(tint).setUv(0, 1).setLight(0);
            buffer.addVertex(pose, left + width, y + 8, 0).setColor(tint).setUv(1, 1).setLight(0);
            buffer.addVertex(pose, left + width, y, 0).setColor(tint).setUv(1, 0).setLight(0);
        }
        @Override public float left() { return x; }
        @Override public float right() { return x + width; }
        @Override public float top() { return y; }
        @Override public float bottom() { return y + 8; }
        @Override public RenderType renderType(Font.DisplayMode mode) { return null; }
        @Override public GpuTextureView textureView() { return null; }
        @Override public RenderPipeline guiPipeline() { return null; }
    }

    @Test void singleKoreanGlyphHasBothColorsInsideItsQuadAndPreservesShadowAndAlpha() {
        var source = SmoothNameGlyphs.source(new GradientFont("한", RED, BLUE, false, 0), BASE);
        var shape = source.getGlyph('한').createGlyph(50, 2, 0x80FFFFFF, 0x40000000, Style.EMPTY, 1, 1);
        var vertices = new Vertices();
        shape.render(new Matrix4f(), vertices, 0, false);
        assertEquals(List.of(0x40000000, 0x40000000, 0x40000000, 0x40000000,
                0x80FF0000, 0x80FF0000, 0x800000FF, 0x800000FF), vertices.colors);
    }

    @Test void twoDifferentWidthGlyphsShareOneContinuousGradient() {
        var left = SmoothNameGlyphs.source(new GradientFont("i한", RED, BLUE, false, 0), BASE);
        var right = SmoothNameGlyphs.source(new GradientFont("i한", RED, BLUE, false, 1), BASE);
        var a = new Vertices();
        var b = new Vertices();
        left.getGlyph('i').createGlyph(50, 0, -1, 0, Style.EMPTY, 1, 0).render(new Matrix4f(), a, 0, false);
        right.getGlyph('한').createGlyph(54, 0, -1, 0, Style.EMPTY, 1, 0).render(new Matrix4f(), b, 0, false);
        assertEquals(0xFFFF0000, a.colors.getFirst());
        assertEquals(0xFF000000 | DisplayNameStyle.mix(RED, BLUE, 3.0 / 13), a.colors.getLast());
        assertEquals(0xFF000000 | DisplayNameStyle.mix(RED, BLUE, 4.0 / 13), b.colors.getFirst());
        assertEquals(0xFF0000FF, b.colors.getLast());
    }

    @Test void guiScalingAndBoldDoNotChangeTheGradientEndpoints() {
        var source = SmoothNameGlyphs.source(new GradientFont("한", RED, BLUE, true, 0), BASE);
        var shape = source.getGlyph('한').createGlyph(50, 2, -1, 0, Style.EMPTY.withBold(true), 1, 0);
        var vertices = new Vertices();
        shape.render(new Matrix4f().translate(200, 100, 0).scale(3), vertices, 0, false);
        assertEquals(0xFFFF0000, vertices.colors.getFirst());
        assertEquals(0xFF0000FF, vertices.colors.getLast());
        assertEquals(60, shape.right());
    }

    @Test void spaceInSelectedGradientKeepsItsAdvanceWithoutCreatingADrawable() {
        var source = SmoothNameGlyphs.source(new GradientFont("새 벽", RED, BLUE, false, 1), BASE);
        var space = source.getGlyph(' ');
        assertEquals(4, space.info().getAdvance(false));
        assertNull(space.createGlyph(10, 0, -1, 0, Style.EMPTY, 1, 0));
    }

    @Test void emptyGlyphsAreSafeEvenWhenUnicodeDoesNotClassifyThemAsWhitespace() {
        for (String text : List.of("새\u00a0벽", "새\u3164벽", "   ", "\u3000")) {
            int[] letters = text.codePoints().toArray();
            for (int i = 0; i < letters.length; i++) {
                var spec = new GradientFont(text, RED, BLUE, true, i);
                var source = assertDoesNotThrow(() -> SmoothNameGlyphs.source(spec, BASE));
                var original = BASE.getGlyph(letters[i]).createGlyph(0, 0, -1, 0, Style.EMPTY, 1, 0);
                var rendered = source.getGlyph(letters[i]).createGlyph(0, 0, -1, 0, Style.EMPTY, 1, 0);
                if (original == null) assertNull(rendered);
                else assertDoesNotThrow(() -> rendered.render(new Matrix4f(), new Vertices(), 0, false));
            }
        }
    }

    @Test void innerSpacesContributeWidthWhileOuterSpacesDoNotDiluteGradientEndpoints() {
        String text = " 새 벽 ";
        var first = SmoothNameGlyphs.source(new GradientFont(text, RED, BLUE, false, 1), BASE);
        var last = SmoothNameGlyphs.source(new GradientFont(text, RED, BLUE, false, 3), BASE);
        var a = new Vertices(); var b = new Vertices();
        first.getGlyph('새').createGlyph(4, 0, -1, 0, Style.EMPTY, 1, 0).render(new Matrix4f(), a, 0, false);
        last.getGlyph('벽').createGlyph(18, 0, -1, 0, Style.EMPTY, 1, 0).render(new Matrix4f(), b, 0, false);
        assertEquals(0xFFFF0000, a.colors.getFirst());
        assertEquals(0xFF000000 | DisplayNameStyle.mix(RED, BLUE, 9.0 / 23), a.colors.getLast());
        assertEquals(0xFF000000 | DisplayNameStyle.mix(RED, BLUE, 14.0 / 23), b.colors.getFirst());
        assertEquals(0xFF0000FF, b.colors.getLast());
    }

    private static final class Vertices implements VertexConsumer {
        final List<Integer> colors = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int color) { colors.add(color); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
