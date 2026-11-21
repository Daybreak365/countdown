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
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4fc;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses the active resource-pack font and interpolates vertex colors inside each glyph. */
public final class SmoothNameGlyphs {
    private SmoothNameGlyphs() {}
    private record Key(Identifier id, GlyphSource base) {}
    private static final Map<Key, GlyphSource> CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static long generation;

    public static void clear() { CACHE.clear(); generation++; }
    public static long generation() { return generation; }

    public static GlyphSource source(GradientFont spec, GlyphSource base) {
        return source(spec.id(), base);
    }

    public static GlyphSource source(Identifier id, GlyphSource base) {
        Key key = new Key(id, base);
        GlyphSource found = CACHE.get(key);
        if (found != null) return found;
        GradientFont spec = GradientFont.parse(id);
        if (spec == null) return null;
        GlyphSource source = create(spec, base);
        CACHE.put(key, source);
        while (CACHE.size() > 1024) CACHE.remove(CACHE.keySet().iterator().next());
        return source;
    }

    private static GlyphSource create(GradientFont spec, GlyphSource base) {
        int[] letters = spec.text().codePoints().toArray();
        float offset = 0, currentOffset = 0, left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        Style style = Style.EMPTY.withBold(spec.bold());
        for (int i = 0; i < letters.length; i++) {
            BakedGlyph glyph = base.getGlyph(letters[i]);
            if (i == spec.index()) currentOffset = offset;
            TextRenderable.Styled shape = glyph.createGlyph(offset, 0, -1, 0, style, glyph.info().getBoldOffset(), 0);
            if (shape != null && !Character.isWhitespace(letters[i])) {
                left = Math.min(left, shape.left());
                right = Math.max(right, shape.right());
            }
            offset += glyph.info().getAdvance(spec.bold());
        }
        float start = Float.isFinite(left) ? left : 0;
        float span = Math.max(0.001f, Float.isFinite(right) ? right - start : offset);
        float localOffset = currentOffset;
        return new GlyphSource() {
            @Override public BakedGlyph getGlyph(int codePoint) {
                BakedGlyph original = base.getGlyph(codePoint);
                if (codePoint != letters[spec.index()]) return original;
                return new BakedGlyph() {
                    @Override public GlyphInfo info() { return original.info(); }
                    @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor,
                                                                       Style actualStyle, float boldOffset, float shadowOffset) {
                        int white = color | 0x00FFFFFF;
                        var glyph = original.createGlyph(x, y, white, shadowColor, actualStyle, boldOffset, shadowOffset);
                        // Spaces and some resource-pack glyphs only advance the pen. Vanilla
                        // expects null here; wrapping it would crash while reading GUI bounds.
                        if (glyph == null) return null;
                        return new GradientGlyph(glyph, x - localOffset + start, span, white, spec.startColor(), spec.endColor());
                    }
                };
            }
            @Override public BakedGlyph getRandomGlyph(RandomSource random, int codePoint) { return getGlyph(codePoint); }
        };
    }

    private record GradientGlyph(TextRenderable.Styled delegate, float origin, float width, int bodyColor,
                                 int startColor, int endColor) implements TextRenderable.Styled {
        @Override public void render(Matrix4fc pose, VertexConsumer buffer, int light, boolean noDepth) {
            delegate.render(pose, new GradientVertices(buffer, origin, width, bodyColor, startColor, endColor), light, noDepth);
        }
        @Override public Style style() { return delegate.style(); }
        @Override public float left() { return delegate.left(); }
        @Override public float right() { return delegate.right(); }
        @Override public float top() { return delegate.top(); }
        @Override public float bottom() { return delegate.bottom(); }
        @Override public float activeLeft() { return delegate.activeLeft(); }
        @Override public float activeRight() { return delegate.activeRight(); }
        @Override public float activeTop() { return delegate.activeTop(); }
        @Override public float activeBottom() { return delegate.activeBottom(); }
        @Override public RenderType renderType(Font.DisplayMode mode) { return delegate.renderType(mode); }
        @Override public GpuTextureView textureView() { return delegate.textureView(); }
        @Override public RenderPipeline guiPipeline() { return delegate.guiPipeline(); }
    }

    /** Capture local X before the pose transform, so GUI scale/translation never changes the gradient. */
    private static final class GradientVertices implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float origin, width;
        private final int bodyColor, start, end;
        private float x;
        GradientVertices(VertexConsumer delegate, float origin, float width, int bodyColor, int start, int end) {
            this.delegate = delegate;
            this.origin = origin;
            this.width = width;
            this.bodyColor = bodyColor;
            this.start = start;
            this.end = end;
        }
        @Override public VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
            this.x = x;
            delegate.addVertex(pose, x, y, z);
            return this;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            delegate.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int color) {
            delegate.setColor(color == bodyColor ? (color & 0xFF000000) | DisplayNameStyle.mix(start, end, (x - origin) / width) : color);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setUv3(float u, float v) { delegate.setUv3(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    }
}
