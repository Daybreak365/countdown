package dev.newyear.countdown.discord;

import com.google.gson.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordRichMessageTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrapChatCodecs() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static final String ID = "123456789012345678";
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void textOnlyAndImageOnlyMessagesBothSurviveRelay() {
        assertEquals("안녕", DiscordRichMessage.parse(json("{\"content\":\"안녕\"}")).plain());
        var message = DiscordRichMessage.parse(json("""
                {"content":"","attachments":[{"filename":"photo.png","url":"https://cdn.discordapp.com/attachments/123/456/photo.png?ex=abc&hm=def","width":800,"height":600}]}
                """));
        assertFalse(message.empty()); assertEquals(1, message.media().size());
        assertEquals(800, message.media().getFirst().width());
        assertTrue(message.media().getFirst().url().endsWith("?ex=abc&hm=def"));
        assertEquals(message, DiscordRichMessage.read(message.json()));
    }
    @Test void customAndUnicodeEmojiKeepTheirPositionAndAnimation() {
        JsonObject message = new JsonObject();
        message.addProperty("content", "안녕 <:wave:" + ID + "> <a:dance:" + ID + "> 😀 👨‍👩‍👧‍👦 🇰🇷 1️⃣ 끝");
        var parts = DiscordRichMessage.parse(message).parts();
        var images = parts.stream().filter(p -> p.image() != null).toList();
        assertEquals(6, images.size());
        assertTrue(images.get(0).image().url().contains(".png?size=64"));
        assertTrue(images.get(1).image().url().contains(".gif?size=64"));
        assertTrue(images.get(2).image().url().endsWith("1f600.png"));
        assertTrue(images.get(3).image().url().contains("200d"));
        assertTrue(images.get(4).image().url().endsWith("1f1f0-1f1f7.png"));
        assertTrue(images.get(5).image().url().endsWith("31-20e3.png"));
        assertTrue(DiscordRichMessage.parse(message).plain().startsWith("안녕 :wave: :dance: 😀"));
        assertFalse(DiscordRichMessage.unicodeEmoji("☀︎"));
    }
    @Test void pngApngGifStickersAndLottieFallbackAreNotDropped() {
        var message = json("{\"sticker_items\":[]}");
        for (int format = 1; format <= 4; format++) {
            var sticker = new JsonObject(); sticker.addProperty("id", ID + format); sticker.addProperty("name", "별"); sticker.addProperty("format_type", format);
            message.getAsJsonArray("sticker_items").add(sticker);
        }
        var parsed = DiscordRichMessage.parse(message);
        assertEquals(3, parsed.media().size()); assertFalse(parsed.empty());
        assertTrue(parsed.plain().contains("Lottie 스티커"));
        assertTrue(parsed.media().getLast().url().startsWith("https://media.discordapp.net/stickers/"));
        assertTrue(parsed.media().getLast().url().endsWith(".gif"));
    }
    @Test void spoilersAreHiddenAndEmbedDuplicatesAreRemoved() {
        String url = "https://cdn.discordapp.com/attachments/123/456/SPOILER_photo.gif";
        var message = new JsonObject(); message.addProperty("content", url);
        var attachments = new JsonArray(); var a = new JsonObject(); a.addProperty("url", url); a.addProperty("filename", "SPOILER_photo.gif"); attachments.add(a); message.add("attachments", attachments);
        var embeds = new JsonArray(); var embed = new JsonObject(); embed.add("image", a); embeds.add(embed); message.add("embeds", embeds);
        var parsed = DiscordRichMessage.parse(message);
        assertEquals(1, parsed.media().size()); assertTrue(parsed.media().getFirst().hidden());
    }
    @Test void unexpectedAttachmentsRemainReadableAndMediaCountsAreBounded() {
        var message = json("{\"content\":\"\",\"attachments\":[]}");
        for (int i = 0; i < 10; i++) {
            var a = new JsonObject(); a.addProperty("filename", "file" + i + ".png");
            a.addProperty("url", "https://cdn.discordapp.com/attachments/123/" + (456 + i) + "/file" + i + ".png");
            message.getAsJsonArray("attachments").add(a);
        }
        assertEquals(DiscordRichMessage.MAX_MEDIA, DiscordRichMessage.parse(message).media().size());
        var unknown = DiscordRichMessage.parse(json("{\"attachments\":[{\"filename\":\"document.pdf\",\"url\":\"https://example.com/test.pdf\"}]}"));
        assertTrue(unknown.plain().contains("document.pdf")); assertTrue(unknown.media().isEmpty());
    }
    @Test void onlyKnownPublicMediaHostsAndPathsCanBeDownloaded() {
        for (String bad : new String[]{"http://cdn.discordapp.com/attachments/a.png", "https://127.0.0.1/a.png", "file:///tmp/a.png",
                "https://cdn.discordapp.com.evil/attachments/a.png", "https://evil@cdn.discordapp.com/attachments/a.png",
                "https://cdn.discordapp.com:8443/attachments/a.png", "https://cdn.discordapp.com/attachments/../token",
                "https://media.discordapp.net/attachments/%2e%2e/token", "https://cdn.discordapp.com/api/v10/users/@me",
                "https://cdn.jsdelivr.net/gh/evil/file.png", "https://media.tenor.com/a.gif#fragment"}) assertFalse(DiscordMedia.allowedUrl(bad), bad);
        String signed = "https://cdn.discordapp.com/attachments/123/456/a.webp?ex=123&is=456&hm=abc&format=webp";
        String converted = DiscordMedia.downloadUrl(signed);
        assertTrue(converted.startsWith("https://media.discordapp.net/attachments/"));
        assertTrue(converted.contains("ex=123&is=456&hm=abc")); assertTrue(converted.contains("format=png"));
        assertFalse(converted.contains("format=webp"));
        assertThrows(RuntimeException.class, () -> DiscordRichMessage.read("{\"parts\":[],\"media\":[{\"url\":\"http://localhost/x.png\",\"label\":\"x\",\"width\":1,\"height\":1}]}"));
    }
    @Test void lateEmbedUpdateKeepsOriginalAuthorAndBodyAndDeduplicates() {
        var history = new DiscordMessageHistory();
        var initial = json("{\"id\":\"" + ID + "\",\"author\":{\"username\":\"user\"},\"content\":\"GIF\"}");
        assertNotNull(history.accept(initial, false)); assertNull(history.accept(initial, false));
        var update = json("{\"id\":\"" + ID + "\",\"embeds\":[{\"type\":\"gifv\",\"thumbnail\":{\"url\":\"https://media.tenor.com/abc/file.gif\",\"width\":320,\"height\":180}}]}");
        var merged = history.accept(update, true);
        assertEquals("user", merged.getAsJsonObject("author").get("username").getAsString());
        assertEquals("GIF", merged.get("content").getAsString());
        assertEquals(1, DiscordRichMessage.parse(merged).media().size()); assertNull(history.accept(update, true));
        assertNotNull(history.remove(ID)); assertNull(history.accept(update, true));
    }
    @Test void richMessagePacketRoundTripsWithoutARegistryOrSecrets() {
        var body = DiscordRichMessage.parse(json("{\"content\":\"😀 안녕하세요\"}"));
        var packet = new DiscordMediaPackets.Message(ID, Component.literal("이름 | "), body.json(), true);
        var bytes = Unpooled.buffer();
        try {
            DiscordMediaPackets.Message.CODEC.encode(bytes, packet);
            var decoded = DiscordMediaPackets.Message.CODEC.decode(bytes);
            assertEquals(packet, decoded); assertEquals(body, DiscordRichMessage.read(decoded.json()));
            assertFalse(packet.toString().contains("cdn"));
        } finally { bytes.release(); }
    }
}
