package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.newyear.countdown.discord.DiscordMedia;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;

/** Metadata and GPU objects are render-thread confined. Downloads/decodes use two bounded workers. */
final class DiscordMediaCache {
    static final long MAX_MEMORY = 32L * 1024 * 1024;
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(32, .75f, true);
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            r -> { Thread t = new Thread(r, "Discord-media"); t.setDaemon(true); return t; });
    private static long generation, memory, clock = System.nanoTime() / 1_000_000;
    private static View placeholder;
    private static View rule;
    record View(Identifier id, DynamicTexture texture, GlyphRenderTypes types, int width, int height) {}
    record Size(int width, int height) {}
    static Size size(DiscordMedia ref) {
        Entry entry = ENTRIES.get(ref.key());
        return entry != null && entry.animation != null ? new Size(entry.animation.width(), entry.animation.height())
                : new Size(ref.width(), ref.height());
    }
    static View rule() {
        if (rule == null) rule = upload(Identifier.fromNamespaceAndPath("newyearcountdown", "discord_media/rule"), 1, 1, new int[]{0xCC89939F});
        return rule;
    }
    private static final class Entry {
        final DiscordMedia ref;
        MediaImage.Animation animation;
        Future<?> task;
        View view;
        boolean requested, failed;
        int shown = -1;
        long started;
        Entry(DiscordMedia ref) { this.ref = ref; }
    }
    static void register(DiscordMedia ref) {
        if (ref.hidden()) return;
        Entry current = ENTRIES.computeIfAbsent(ref.key(), key -> new Entry(ref));
        if (current.failed && clock - current.started > 60000) { current.failed = false; current.requested = false; }
        while (ENTRIES.size() > 4096) {
            var it = ENTRIES.values().iterator(); Entry entry = it.next(); it.remove(); release(entry);
        }
    }
    static boolean failed(DiscordMedia ref) { Entry e = ENTRIES.get(ref.key()); return e != null && e.failed; }
    static View view(String key) {
        Entry e = ENTRIES.get(key);
        if (e == null || e.failed) return placeholder();
        if (!e.requested) request(key, e);
        if (e.animation == null) return placeholder();
        int index = e.animation.frameAt(clock - e.started);
        if (e.view == null) {
            Identifier id = Identifier.fromNamespaceAndPath("newyearcountdown", "discord_media/" + key);
            e.view = upload(id, e.animation.width(), e.animation.height(), e.animation.frames().get(index).pixels());
            e.shown = index;
        } else if (e.shown != index) {
            pixels(e.view.texture.getPixels(), e.animation.frames().get(index).pixels()); e.view.texture.upload(); e.shown = index;
        }
        return e.view;
    }
    static void tick() { clock = System.nanoTime() / 1_000_000; }
    private static void request(String key, Entry entry) {
        entry.requested = true; long expected = generation;
        try {
            entry.task = IO.submit(() -> {
                MediaImage.Animation decoded = null;
                try { decoded = MediaImage.decode(MediaDownload.fetch(entry.ref.url())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception ignored) { /* The chat keeps an accessible text/original-link fallback. */ }
                MediaImage.Animation result = decoded;
                Minecraft.getInstance().execute(() -> {
                    if (expected != generation || ENTRIES.get(key) != entry) return;
                    entry.failed = result == null; entry.animation = result; entry.started = clock;
                    if (result != null) memory += result.bytes();
                    for (Entry old : ENTRIES.values()) {
                        if (memory <= MAX_MEMORY) break;
                        if (old != entry && old.animation != null) { release(old); old.failed = true; }
                    }
                    DiscordMediaClient.refresh();
                });
            });
        } catch (RejectedExecutionException e) {
            // A full queue can retry on the next visible frame without retaining unbounded work.
            entry.requested = false;
        }
    }
    private static View placeholder() {
        if (placeholder == null) {
            int[] p = new int[16 * 16];
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                p[y * 16 + x] = x == 0 || y == 0 || x == 15 || y == 15 ? 0xFF536374 : ((x / 4 + y / 4) % 2 == 0 ? 0xFF253240 : 0xFF2D3B4A);
            placeholder = upload(Identifier.fromNamespaceAndPath("newyearcountdown", "discord_media/loading"), 16, 16, p);
        }
        return placeholder;
    }
    private static View upload(Identifier id, int w, int h, int[] pixels) {
        NativeImage image = new NativeImage(w, h, false); pixels(image, pixels);
        var texture = new DynamicTexture(() -> "Discord chat media", image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new View(id, texture, GlyphRenderTypes.createForColorTexture(id), w, h);
    }
    private static void pixels(NativeImage image, int[] pixels) {
        int w = image.getWidth();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < w; x++) image.setPixel(x, y, pixels[y * w + x]);
    }
    private static void release(Entry e) {
        if (e.task != null && !e.task.isDone()) e.task.cancel(true);
        if (e.animation != null) { memory -= e.animation.bytes(); e.animation = null; }
        if (e.view != null) { Minecraft.getInstance().getTextureManager().release(e.view.id); e.view = null; }
    }
    static void clear() {
        generation++; IO.getQueue().clear(); ENTRIES.values().forEach(DiscordMediaCache::release); ENTRIES.clear(); memory = 0;
        if (placeholder != null) { Minecraft.getInstance().getTextureManager().release(placeholder.id); placeholder = null; }
        if (rule != null) { Minecraft.getInstance().getTextureManager().release(rule.id); rule = null; }
    }
    static void reloadTextures() {
        for (Entry e : ENTRIES.values()) if (e.view != null) {
            Minecraft.getInstance().getTextureManager().release(e.view.id); e.view = null;
        }
        if (placeholder != null) { Minecraft.getInstance().getTextureManager().release(placeholder.id); placeholder = null; }
        if (rule != null) { Minecraft.getInstance().getTextureManager().release(rule.id); rule = null; }
    }
}
