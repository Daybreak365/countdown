package dev.newyear.countdown.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.discord.AvatarRaster;
import dev.newyear.countdown.discord.AvatarRef;
import dev.newyear.countdown.discord.DiscordChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.PlainTextRenderable;
import net.minecraft.client.gui.font.SingleSpriteSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 속 디스코드 프로필 사진을 글자 한 칸(8x8)으로 그린다.
 * 서버가 보낸 스프라이트 컴포넌트의 아틀라스 이름이 {@link DiscordChat#ATLAS} 이면 폰트 관리자 대신 여기서 글리프를 준다
 * (mixin.CachedFontProviderMixin). 사진은 디스코드 CDN 에서 한 번 받아 두고, 화면 배율에 맞는 크기로 줄여 선명하게 그린다.
 */
public final class DiscordAvatarGlyphs {
    private DiscordAvatarGlyphs() {}

    /** 받아 두는 원본 크기. 화면 배율 8배(64픽셀)까지 그대로 쓸 수 있다. */
    private static final int SOURCE_SIZE = 64;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final long RETRY_MS = 60_000;
    private static final int PLACEHOLDER_COLOR = 0xFF5865F2;
    private static final GlyphInfo GLYPH_INFO = GlyphInfo.simple(8.0f);
    private static final AvatarRef UNKNOWN = new AvatarRef("0", AvatarRef.NO_AVATAR);

    private static final Map<Identifier, GlyphSource> SOURCES = new ConcurrentHashMap<>();
    /** 렌더 스레드에서만 만진다. 오래 안 쓴 것부터 버린다. */
    private static final LinkedHashMap<AvatarRef, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Entry PLACEHOLDER = new Entry(UNKNOWN);
    private static HttpClient http;
    private static Tex separatorTexture;
    private static Tex discordIconTexture;
    private static final GlyphSource DISCORD_SOURCE = new SingleSpriteSource(new BakedGlyph() {
        @Override public GlyphInfo info() { return GLYPH_INFO; }
        @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            return new Instance(discordIconTexture(), x, y, color, 0, 0, style, 8);
        }
    });
    private static final GlyphSource SEPARATOR_SOURCE = new SingleSpriteSource(new BakedGlyph() {
        @Override public GlyphInfo info() { return GLYPH_INFO; }
        @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            return new Instance(separatorTexture(), x, y, color, 0, 0, style, 6);
        }
    });

    /** 이 모드의 아바타 스프라이트면 그 글리프를, 아니면 null(바닐라가 처리). */
    public static GlyphSource source(FontDescription.AtlasSprite description) {
        if (DiscordChat.DISCORD_ICON.equals(description.atlasId())) return DISCORD_SOURCE;
        if (DiscordChat.SEPARATOR_ATLAS.equals(description.atlasId())) return SEPARATOR_SOURCE;
        if (!DiscordChat.ATLAS.equals(description.atlasId())) return null;
        return SOURCES.computeIfAbsent(description.spriteId(), id -> {
            AvatarRef ref = NewYearCountdown.MOD_ID.equals(id.getNamespace()) ? AvatarRef.parsePath(id.getPath()) : null;
            return new SingleSpriteSource(new Glyph(ref == null ? UNKNOWN : ref));
        });
    }

    private static Tex separatorTexture() {
        if (separatorTexture == null) {
            // One-pixel bar, soft ends, three/four pixels of breathing room. No text pipe.
            NativeImage pixels = new NativeImage(8, 6, true);
            for (int y = 0; y < 6; y++) pixels.setPixel(3, y, (y == 0 || y == 5 ? 0x66 : 0xCC) << 24 | 0x89939F);
            Identifier id = DiscordChat.SEPARATOR_ATLAS;
            DynamicTexture texture = new DynamicTexture(() -> "chat separator", pixels);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            separatorTexture = new Tex(id, texture, GlyphRenderTypes.createForColorTexture(id), 8);
        }
        return separatorTexture;
    }

    private static Tex discordIconTexture() {
        if (discordIconTexture == null) {
            // Small Clyde silhouette, kept separate from the round user portrait.
            String[] mask = {
                    "................", "................", "...###....###...", "..############..",
                    "..############..", ".##############.", ".##############.", ".###..####..###.",
                    "####..####..####", "################", ".##############.", ".######..######.",
                    "..###......###..", "................", "................", "................"
            };
            NativeImage pixels = new NativeImage(16, 16, true);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                pixels.setPixel(x, y, mask[y].charAt(x) == '#' ? 0xFF8EA1E1 : 0);
            Identifier id = DiscordChat.DISCORD_ICON;
            DynamicTexture texture = new DynamicTexture(() -> "Discord icon", pixels);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            discordIconTexture = new Tex(id, texture, GlyphRenderTypes.createForColorTexture(id), 16);
        }
        return discordIconTexture;
    }

    private record Glyph(AvatarRef ref) implements BakedGlyph {
        @Override public GlyphInfo info() {
            return GLYPH_INFO;
        }

        @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            // Portraits have their own silhouette; text shadows shift their apparent centre.
            return new Instance(texture(ref), x, y, color, 0, 0, style, 8);
        }
    }

    /** GPU 에 올라간 한 장. 크기가 바뀌면 새 이름으로 다시 올린다(렌더 타입이 텍스처 이름으로 캐시되기 때문). */
    private record Tex(Identifier id, DynamicTexture texture, GlyphRenderTypes renderTypes, int size) {}

    record Instance(Tex tex, float x, float y, int color, int shadowColor, float shadowOffset, Style style, float height) implements PlainTextRenderable {
        // PlainTextRenderable defaults to y - 1, above the text and the tab row.
        // Centre images in the nine-pixel line box; the shorter separator shares that centre.
        @Override public float ascent() { return 7 - (9 - height) / 2; }
        @Override public void renderSprite(Matrix4fc pose, VertexConsumer buffer, int light, float offsetX, float offsetY, float z, int tint) {
            // 본체는 글자색에 물들지 않게 흰색으로 그리되, 채팅이 흐려질 때의 투명도는 따른다. 그림자 패스는 그대로 둔다.
            int c = tint == color ? tint | 0x00FFFFFF : tint;
            float x0 = offsetX + left(), x1 = offsetX + right(), y0 = offsetY + top(), y1 = offsetY + bottom();
            buffer.addVertex(pose, x0, y0, z).setUv(0, 0).setColor(c).setLight(light);
            buffer.addVertex(pose, x0, y1, z).setUv(0, 1).setColor(c).setLight(light);
            buffer.addVertex(pose, x1, y1, z).setUv(1, 1).setColor(c).setLight(light);
            buffer.addVertex(pose, x1, y0, z).setUv(1, 0).setColor(c).setLight(light);
        }

        @Override public RenderType renderType(Font.DisplayMode mode) {
            return tex.renderTypes().select(mode);
        }

        @Override public RenderPipeline guiPipeline() {
            return tex.renderTypes().guiPipeline();
        }

        @Override public GpuTextureView textureView() {
            return tex.texture().getTextureView();
        }
    }

    private static final class Entry {
        final AvatarRef ref;
        /** SOURCE_SIZE 정사각형 ARGB. 아직 못 받았으면 null. */
        int[] pixels;
        Tex tex;
        /** 화면에 크게 그릴 때 쓰는 원본 크기 텍스처. 필요할 때 만든다. */
        Tex large;
        boolean loading;
        long retryAt;

        Entry(AvatarRef ref) {
            this.ref = ref;
        }
    }

    private static Tex texture(AvatarRef ref) {
        int size = Math.max(8, Math.min(SOURCE_SIZE, 8 * Minecraft.getInstance().getWindow().getGuiScale()));
        Entry entry = ref == UNKNOWN ? PLACEHOLDER : ENTRIES.computeIfAbsent(ref, Entry::new);
        if (entry != PLACEHOLDER && entry.pixels == null) {
            request(entry);
            entry = PLACEHOLDER;
        }
        if (entry.tex == null || entry.tex.size() != size) {
            Tex old = entry.tex;
            entry.tex = upload(entry, size);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old.id());
            trim();
        }
        return entry.tex;
    }

    /**
     * 화면에 크게 그릴 동그란 프로필 사진(64픽셀) 텍스처. 아직 받는 중이면 null 이고, 받는 대로 다음 호출부터 돌려준다.
     * 렌더 스레드에서만 부른다.
     */
    public static Identifier largeTexture(AvatarRef ref) {
        if (ref == null) return null;
        Entry entry = ENTRIES.computeIfAbsent(ref, Entry::new);
        if (entry.pixels == null) {
            request(entry);
            return null;
        }
        if (entry.large == null) {
            entry.large = upload(entry, SOURCE_SIZE, "large_");
            trim();
        }
        return entry.large.id();
    }

    private static Tex upload(Entry entry, int size) {
        return upload(entry, size, "");
    }

    private static Tex upload(Entry entry, int size, String prefix) {
        int[] pixels;
        if (entry.pixels == null) {
            pixels = AvatarRaster.solidCircle(size, PLACEHOLDER_COLOR);
        } else {
            pixels = AvatarRaster.resample(entry.pixels, SOURCE_SIZE, SOURCE_SIZE, size);
            AvatarRaster.circleMask(pixels, size);
        }
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setPixel(x, y, pixels[y * size + x]);
        String name = prefix + entry.ref.userId() + "_" + entry.ref.hash() + "_" + size;
        Identifier id = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "discord_avatar/" + name);
        DynamicTexture texture = new DynamicTexture(() -> "discord avatar " + name, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new Tex(id, texture, GlyphRenderTypes.createForColorTexture(id), size);
    }

    private static void trim() {
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (ENTRIES.size() > MAX_ENTRIES && it.hasNext()) {
            Entry oldest = it.next();
            it.remove();
            if (oldest.tex != null) Minecraft.getInstance().getTextureManager().release(oldest.tex.id());
            if (oldest.large != null) Minecraft.getInstance().getTextureManager().release(oldest.large.id());
        }
    }

    private static void request(Entry entry) {
        long now = System.currentTimeMillis();
        if (entry.loading || now < entry.retryAt) return;
        entry.loading = true;
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        // 주소는 AvatarRef 가 숫자 ID 와 16진수 해시로만 만든다. 서버가 다른 곳으로 요청을 보내게 할 수 없다.
        HttpRequest req = HttpRequest.newBuilder(URI.create(entry.ref.url(SOURCE_SIZE))).timeout(Duration.ofSeconds(15)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(res -> {
                    if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                    return decode(res.body());
                })
                .whenComplete((pixels, error) -> Minecraft.getInstance().execute(() -> {
                    entry.loading = false;
                    if (pixels == null) {
                        entry.retryAt = System.currentTimeMillis() + RETRY_MS;
                        NewYearCountdown.LOGGER.debug("Discord avatar download failed for {}: {}", entry.ref.userId(), String.valueOf(error));
                    } else {
                        entry.pixels = pixels;
                    }
                }));
    }

    private static int[] decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalStateException("unexpected image size");
        try (NativeImage image = NativeImage.read(bytes)) {
            int w = image.getWidth(), h = image.getHeight();
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) throw new IllegalStateException("unexpected image dimensions");
            return AvatarRaster.resample(image.getPixels(), w, h, SOURCE_SIZE);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
