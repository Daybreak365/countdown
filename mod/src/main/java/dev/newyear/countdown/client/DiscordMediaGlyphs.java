package dev.newyear.countdown.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.font.SingleSpriteSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

/** A thumbnail occupies real chat rows, so scrolling, wrapping, fading and click targets stay native. */
public final class DiscordMediaGlyphs {
    public static final Identifier ATLAS = Identifier.fromNamespaceAndPath("newyearcountdown", "discord_media");
    private DiscordMediaGlyphs() {}
    public static void reload() { DiscordMediaCache.reloadTextures(); }
    static int chatWidth() {
        var options = Minecraft.getInstance().options;
        return MediaLayout.chatWidth(ChatComponent.getWidth(options.chatWidth().get()), options.chatScale().get());
    }
    static int width() {
        var options = Minecraft.getInstance().options;
        return MediaLayout.width(ChatComponent.getWidth(options.chatWidth().get()), options.chatScale().get());
    }
    public static GlyphSource source(FontDescription.AtlasSprite sprite) {
        if (!ATLAS.equals(sprite.atlasId()) || !sprite.spriteId().getNamespace().equals("newyearcountdown")) return null;
        String[] path = sprite.spriteId().getPath().split("/");
        if (path.length == 2 && path[0].equals("gap") && validWidth(path[1], true)) {
            int advance = Integer.parseInt(path[1]);
            return new SingleSpriteSource(new BakedGlyph() {
                @Override public GlyphInfo info() { return GlyphInfo.simple(advance); }
                @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) { return null; }
            });
        }
        boolean rule = path.length == 2 && path[0].equals("rule") && path[1].matches("[0-2]");
        boolean sticker = path.length == 4 && path[0].equals("sticker") && path[1].matches("[a-f0-9]{64}")
                && validWidth(path[2], false) && path[3].matches("[0-2]");
        if (rule || sticker) {
            int advance = rule ? 8 : Integer.parseInt(path[2]);
            int tile = Integer.parseInt(path[rule ? 1 : 3]);
            return new SingleSpriteSource(new BakedGlyph() {
                @Override public GlyphInfo info() { return GlyphInfo.simple(advance); }
                @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
                    double spacing = Minecraft.getInstance().options.chatLineSpacing().get();
                    return new ImageGlyph(rule ? DiscordMediaCache.rule() : DiscordMediaCache.view(path[1]), x, y, color, style,
                            rule ? -1 : 3, tile, advance, MediaLayout.lineHeight(spacing), MediaLayout.stickerOffset(spacing));
                }
            });
        }
        if (path.length != 3 || !path[0].matches("[a-f0-9]{64}") || !path[1].matches("[0-8]") || !path[2].matches("[0-7]")) return null;
        String key = path[0]; int rows = Integer.parseInt(path[1]), row = Integer.parseInt(path[2]);
        if (rows == 0 ? row != 0 : row >= rows) return null;
        return new SingleSpriteSource(new BakedGlyph() {
            @Override public GlyphInfo info() { return GlyphInfo.simple(rows == 0 ? 8 : width()); }
            @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
                var view = DiscordMediaCache.view(key);
                double spacing = Minecraft.getInstance().options.chatLineSpacing().get();
                return new ImageGlyph(view, x, y, color, style, rows, row, rows == 0 ? 8 : width(),
                        rows == 0 ? 9 : MediaLayout.lineHeight(spacing), rows == 0 ? 0 : MediaLayout.topOffset(spacing));
            }
        });
    }
    private static boolean validWidth(String value, boolean zero) {
        return value.matches("[0-9]{1,4}") && Integer.parseInt(value) >= (zero ? 0 : 1) && Integer.parseInt(value) <= 4096;
    }
    record ImageGlyph(DiscordMediaCache.View view, float x, float y, int color, Style style,
                              int rows, int row, int width, int lineHeight, float offset) implements TextRenderable.Styled {
        @Override public float left() { return x; }
        @Override public float right() { return x + width; }
        @Override public float top() { return y + offset; }
        @Override public float bottom() { return top() + lineHeight; }
        @Override public void render(Matrix4fc pose, VertexConsumer buffer, int light, boolean noDepth) {
            MediaLayout.Slice s = rows == -1 ? new MediaLayout.Slice(3, 0, 4, lineHeight, 0, 1)
                    : rows == 0 ? new MediaLayout.Slice(0, .5f, 8, 8.5f, 0, 1)
                    : MediaLayout.slice(width, rows, row, lineHeight, view.width(), view.height());
            if (s == null) return;
            int tint = color | 0xFFFFFF; float x0 = x + s.left(), x1 = x + s.right(), y0 = top() + s.top(), y1 = top() + s.bottom();
            buffer.addVertex(pose, x0, y0, 0).setUv(0, s.v0()).setColor(tint).setLight(light);
            buffer.addVertex(pose, x0, y1, 0).setUv(0, s.v1()).setColor(tint).setLight(light);
            buffer.addVertex(pose, x1, y1, 0).setUv(1, s.v1()).setColor(tint).setLight(light);
            buffer.addVertex(pose, x1, y0, 0).setUv(1, s.v0()).setColor(tint).setLight(light);
        }
        @Override public RenderType renderType(Font.DisplayMode mode) { return view.types().select(mode); }
        @Override public GpuTextureView textureView() { return view.texture().getTextureView(); }
        @Override public RenderPipeline guiPipeline() { return view.types().guiPipeline(); }
    }
}
