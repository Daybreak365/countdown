package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.*;
import dev.newyear.countdown.mixin.ChatComponentAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import java.net.URI;
import java.util.LinkedHashMap;

public final class DiscordMediaClient {
    private DiscordMediaClient() {}
    private static final LinkedHashMap<String, Stored> MESSAGES = new LinkedHashMap<>();
    private static int lastWidth;
    private static double lastSpacing;
    private static boolean lastEnabled = true;
    private static final class Stored {
        final String id;
        final DiscordSpoilers spoilers = new DiscordSpoilers();
        Component prefix, rendered;
        DiscordRichMessage body;
        Stored(String id, Component prefix, DiscordRichMessage body) { this.id = id; this.prefix = prefix; this.body = body; }
    }
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(DiscordMediaPackets.Message.ID, (payload, context) -> receive(payload));
    }
    private static void receive(DiscordMediaPackets.Message packet) {
        if (!AvatarRef.isUserId(packet.id())) return;
        DiscordRichMessage body;
        try { body = DiscordRichMessage.read(packet.json()); } catch (RuntimeException e) { return; }
        Stored old = MESSAGES.get(packet.id());
        if (packet.update() && old == null) return;
        if (old != null) { old.prefix = packet.prefix(); old.body = body; old.spoilers.retain(body); refresh(); return; }
        Stored next = new Stored(packet.id(), packet.prefix(), body); next.rendered = component(next);
        MESSAGES.put(packet.id(), next);
        while (MESSAGES.size() > 100) MESSAGES.remove(MESSAGES.keySet().iterator().next());
        Minecraft.getInstance().gui.hud.getChat().addServerSystemMessage(next.rendered);
    }
    public static boolean handleSpoilerClick(ClickEvent event) {
        return DiscordSpoilers.click(event, target -> {
            if (!ClientConfig.get().discordMedia) return;
            Stored stored = MESSAGES.get(target.messageId());
            if (stored != null && stored.spoilers.reveal(stored.body, target.mediaKey())) refresh();
        });
    }
    private static Component component(Stored stored) {
        var result = stored.prefix.copy();
        if (!ClientConfig.get().discordMedia) return result.append(stored.body.plain());
        var media = stored.body.media().stream().map(stored.spoilers::visible).toList();
        var stickers = media.stream().filter(ref -> ref.sticker() && !ref.hidden()).filter(ref -> {
            DiscordMediaCache.register(ref);
            return !DiscordMediaCache.failed(ref);
        }).toList();
        if (!stickers.isEmpty()) {
            result = Component.empty();
            for (DiscordMedia ref : stickers) {
                if (!result.getSiblings().isEmpty()) result.append("\n");
                var size = DiscordMediaCache.size(ref);
                var client = Minecraft.getInstance();
                result.append(DiscordStickerLayout.block(stored.prefix, ref, client.font, DiscordMediaGlyphs.chatWidth(),
                        client.options.chatLineSpacing().get(), size.width(), size.height()));
            }
            if (!stored.body.parts().isEmpty()) result.append("\n");
        }
        for (var part : stored.body.parts()) {
            DiscordMedia ref = part.image();
            if (ref == null) result.append(Component.literal(part.text()));
            else {
                DiscordMediaCache.register(ref);
                result.append(DiscordMediaCache.failed(ref) ? Component.literal(part.text()) : image(ref, 0, 0));
            }
        }
        for (DiscordMedia ref : media) {
            if (stickers.contains(ref)) continue;
            if (ref.hidden()) {
                result.append("\n").append(DiscordSpoilers.cover(stored.id, ref));
                continue;
            }
            DiscordMediaCache.register(ref);
            boolean failed = DiscordMediaCache.failed(ref);
            String label = (failed ? "미리보기 불가 · " : "") + ref.label() + "  ↗";
            result.append("\n").append(Component.literal(label).setStyle(mediaStyle(ref).withColor(0x9FAFBE)));
            if (failed) continue;
            int rows = MediaLayout.rows(DiscordMediaGlyphs.width(), ref.width(), ref.height());
            for (int row = 0; row < rows; row++) result.append("\n").append(image(ref, rows, row));
        }
        return result;
    }
    static Style mediaStyle(DiscordMedia ref) {
        return Style.EMPTY.withColor(0xFFFFFF).withBold(false).withItalic(false).withoutShadow()
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(ref.label() + " · 클릭하여 원본 열기"
                        + (ref.url().startsWith(DiscordMedia.EMOJI_BASE) ? "\nTwemoji · CC BY 4.0" : ""))))
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(ref.url())));
    }
    private static Component image(DiscordMedia ref, int rows, int row) {
        Identifier id = Identifier.fromNamespaceAndPath("newyearcountdown", ref.key() + "/" + rows + "/" + row);
        // Only the first tile has alternative text, avoiding repeated labels in the chat log/narrator.
        Component alternative = row == 0 ? Component.literal(ref.inline() ? ref.label() : "[" + ref.label() + "]") : Component.empty();
        return Component.object(new AtlasSprite(DiscordMediaGlyphs.ATLAS, id), alternative).setStyle(mediaStyle(ref));
    }
    public static void refresh() {
        var chat = Minecraft.getInstance().gui.hud.getChat();
        var access = (ChatComponentAccessor) chat;
        boolean changed = false;
        for (Stored stored : MESSAGES.values()) {
            var history = access.newyearcountdown$messages();
            for (int i = 0; i < history.size(); i++) {
                GuiMessage old = history.get(i);
                if (old.content() != stored.rendered) continue;
                Component next = component(stored);
                history.set(i, new GuiMessage(old.addedTime(), next, old.signature(), old.source(), old.tag()));
                stored.rendered = next; changed = true; break;
            }
        }
        if (changed) {
            int scroll = access.newyearcountdown$scroll(); boolean unread = access.newyearcountdown$unread();
            access.newyearcountdown$refresh();
            access.newyearcountdown$scroll(Math.clamp(scroll, 0, Math.max(0, access.newyearcountdown$lines().size() - chat.getLinesPerPage())));
            access.newyearcountdown$unread(unread);
        }
    }
    public static void tick() {
        DiscordMediaCache.tick();
        if (MESSAGES.isEmpty()) return;
        int width = DiscordMediaGlyphs.chatWidth(); double spacing = Minecraft.getInstance().options.chatLineSpacing().get();
        boolean enabled = ClientConfig.get().discordMedia;
        if (width != lastWidth || spacing != lastSpacing || enabled != lastEnabled) {
            lastWidth = width; lastSpacing = spacing; lastEnabled = enabled;
            if (!enabled) DiscordMediaCache.clear();
            refresh();
        }
    }
    public static void clear() { MESSAGES.clear(); DiscordMediaCache.clear(); }
}
