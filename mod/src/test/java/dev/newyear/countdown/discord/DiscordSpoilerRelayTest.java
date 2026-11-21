package dev.newyear.countdown.discord;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSpoilerRelayTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject attachment(int id, int flags) {
        var a = new JsonObject(); a.addProperty("id", String.valueOf(id)); a.addProperty("filename", "photo.png");
        a.addProperty("url", "https://cdn.discordapp.com/attachments/123/" + id + "/photo.png");
        a.addProperty("width", 800); a.addProperty("height", 600); a.addProperty("flags", flags); return a;
    }
    private static JsonObject message(JsonObject... attachments) {
        var m = new JsonObject(); var list = new JsonArray();
        for (var a : attachments) list.add(a);
        m.add("attachments", list); return m;
    }

    @Test void modernSpoilerFlagWorksWithoutRenamingTheFileIncludingCombinedFlags() {
        for (int flags : new int[]{8, 9, 40, 136}) {
            var parsed = DiscordRichMessage.parse(message(attachment(456, flags)));
            assertEquals(1, parsed.media().size());
            assertEquals("photo.png", parsed.media().getFirst().label());
            assertTrue(parsed.media().getFirst().hidden(), "flags=" + flags);
            assertTrue(DiscordRichMessage.read(parsed.json()).media().getFirst().hidden());
        }
    }

    @Test void unrelatedFlagsDoNotHideOrdinaryImages() {
        for (int flags : new int[]{0, 1, 2, 4, 16, 32, 64, 128})
            assertFalse(DiscordRichMessage.parse(message(attachment(456, flags))).media().getFirst().hidden(), "flags=" + flags);
    }

    @Test void explicitSpoilerAndLegacyFilenamesStillWorkWithMissingFlags() {
        var explicit = attachment(456, 0); explicit.remove("flags"); explicit.addProperty("is_spoiler", true);
        assertTrue(DiscordRichMessage.parse(message(explicit)).media().getFirst().hidden());
        explicit.addProperty("is_spoiler", false);
        assertFalse(DiscordRichMessage.parse(message(explicit)).media().getFirst().hidden());
        var legacy = attachment(456, 0); legacy.addProperty("filename", "SPOILER_photo.png");
        assertTrue(DiscordRichMessage.parse(message(legacy)).media().getFirst().hidden());
        var renamed = attachment(456, 0); renamed.addProperty("url", "https://cdn.discordapp.com/attachments/123/456/SPOILER_photo.png");
        assertTrue(DiscordRichMessage.parse(message(renamed)).media().getFirst().hidden());
    }

    @Test void attachmentFlagAlsoCoversItsCdnProxyAndEmbedCopiesButNotAnotherImage() {
        var a = attachment(456, 8);
        a.addProperty("proxy_url", "https://media.discordapp.net/external/proxy/photo.png?width=320");
        var m = message(a);
        m.addProperty("content", "https://media.discordapp.net/attachments/123/456/renamed.png?width=128");
        m.add("embeds", JsonParser.parseString("""
                [{"image":{"url":"https://media.discordapp.net/attachments/123/456/renamed.png?width=320"}},
                 {"thumbnail":{"url":"https://media.discordapp.net/external/proxy/photo.png?width=64"}},
                 {"image":{"url":"https://cdn.discordapp.com/attachments/123/457/public.png"}}]
                """));
        var parsed = DiscordRichMessage.parse(m);
        assertEquals(3, parsed.media().size());
        assertTrue(parsed.media().get(0).hidden()); assertTrue(parsed.media().get(1).hidden());
        assertFalse(parsed.media().get(2).hidden());
    }

    @Test void hiddenDuplicateAlwaysWinsEvenAfterPreviewLimitWasReached() {
        var m = message();
        for (int i = 0; i < DiscordRichMessage.MAX_MEDIA; i++) m.getAsJsonArray("attachments").add(attachment(456 + i, 0));
        m.getAsJsonArray("attachments").add(attachment(456, 8));
        var parsed = DiscordRichMessage.parse(m);
        assertEquals(DiscordRichMessage.MAX_MEDIA, parsed.media().size()); assertTrue(parsed.media().getFirst().hidden());
        assertEquals(1, parsed.media().stream().filter(DiscordMedia::hidden).count());
        var reversed = DiscordRichMessage.parse(message(attachment(456, 8), attachment(456, 0)));
        assertEquals(1, reversed.media().size()); assertTrue(reversed.media().getFirst().hidden());
    }

    @Test void messageUpdatesCanTurnSpoilerOnAndOffWithoutFilenameChanges() {
        String id = "123456789012345678";
        var history = new DiscordMessageHistory(); var initial = message(attachment(456, 0));
        initial.addProperty("id", id); initial.addProperty("content", "사진");
        assertFalse(DiscordRichMessage.parse(history.accept(initial, false)).media().getFirst().hidden());
        var update = message(attachment(456, 8)); update.addProperty("id", id);
        var merged = history.accept(update, true);
        assertEquals("사진", merged.get("content").getAsString());
        assertTrue(DiscordRichMessage.parse(merged).media().getFirst().hidden());
        update = message(attachment(456, 0)); update.addProperty("id", id);
        assertFalse(DiscordRichMessage.parse(history.accept(update, true)).media().getFirst().hidden());
    }
}
