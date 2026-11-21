package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordMedia;
import dev.newyear.countdown.discord.DiscordRichMessage;
import dev.newyear.countdown.mixin.ChatSpoilerMixin;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSpoilersTest {
    private static final String ID = "123456789012345678";
    private static DiscordMedia hidden(String file) {
        return new DiscordMedia("https://cdn.discordapp.com/attachments/123/456/SPOILER_" + file, "SPOILER_" + file, 320, 180, false, true);
    }
    private static DiscordRichMessage body(DiscordMedia... media) { return new DiscordRichMessage(List.of(), List.of(media)); }

    @Test void flaggedUploadStaysCoveredThroughRelaySerializationUntilTheUserClicks() {
        var gateway = com.google.gson.JsonParser.parseString("""
                {"attachments":[{"filename":"image.png","flags":8,"content_type":"image/png",
                "url":"https://cdn.discordapp.com/attachments/123/456/image.png","width":320,"height":180}]}
                """).getAsJsonObject();
        var received = DiscordRichMessage.read(DiscordRichMessage.parse(gateway).json());
        var image = received.media().getFirst(); var state = new DiscordSpoilers();
        assertTrue(state.visible(image).hidden());
        var cover = DiscordSpoilers.cover(ID, image);
        assertTrue(DiscordSpoilers.click(cover.getStyle().getClickEvent(), target -> {
            assertEquals(ID, target.messageId()); state.reveal(received, target.mediaKey());
        }));
        assertFalse(state.visible(image).hidden()); assertTrue(image.hidden());
    }

    @Test void onlyClickedAttachmentIsRevealedAndOriginalPayloadStaysHidden() {
        var image = hidden("photo.png"); var gif = hidden("dance.gif");
        var body = body(image, gif); var state = new DiscordSpoilers();
        assertSame(image, state.visible(image)); assertTrue(state.visible(image).hidden());
        assertTrue(state.reveal(body, image.key())); assertFalse(state.reveal(body, image.key()));
        var visible = state.visible(image);
        assertFalse(visible.hidden()); assertEquals(image.url(), visible.url()); assertEquals(image.key(), visible.key());
        assertEquals(image.width(), visible.width()); assertEquals(image.height(), visible.height());
        assertTrue(state.visible(gif).hidden()); assertTrue(body.media().getFirst().hidden());
        assertFalse(state.reveal(body, "0".repeat(64)));
    }

    @Test void sameImageInAnotherMessageAndANewConnectionStartCovered() {
        var image = hidden("shared.gif"); var first = new DiscordSpoilers(); var second = new DiscordSpoilers();
        first.reveal(body(image), image.key());
        assertFalse(first.visible(image).hidden()); assertTrue(second.visible(image).hidden());
        assertTrue(new DiscordSpoilers().visible(image).hidden());
    }

    @Test void editsKeepUnchangedConsentButDoNotRevealReplacementsOrDeletedAttachments() {
        var before = hidden("before.png"); var after = hidden("after.png"); var state = new DiscordSpoilers();
        state.reveal(body(before), before.key()); state.retain(body(before, after));
        assertFalse(state.visible(before).hidden()); assertTrue(state.visible(after).hidden());
        state.retain(body(after));
        assertTrue(state.visible(before).hidden()); // Removing then adding the same attachment does not restore consent.
        state.reveal(body(after), after.key()); state.retain(body());
        assertTrue(state.visible(after).hidden());
    }

    @Test void coverContainsOnlyALocalRevealActionAndDoesNotExposeFilenameOrUrl() {
        var image = hidden("plot-twist.png"); var cover = DiscordSpoilers.cover(ID, image);
        assertEquals("[스포일러 이미지 · 클릭하여 보기]", cover.getString());
        assertFalse(cover.toString().contains(image.url())); assertFalse(cover.toString().contains(image.label()));
        var clicked = new ArrayList<DiscordSpoilers.Target>();
        assertTrue(DiscordSpoilers.click(cover.getStyle().getClickEvent(), clicked::add));
        assertEquals(List.of(new DiscordSpoilers.Target(ID, image.key())), clicked);
        assertInstanceOf(ClickEvent.Custom.class, cover.getStyle().getClickEvent());
    }

    @Test void invalidAndStaleLocalActionsAreConsumedWithoutChangingOtherClickActions() {
        var clicked = new ArrayList<DiscordSpoilers.Target>();
        for (var payload : List.<Optional<net.minecraft.nbt.Tag>>of(Optional.empty(), Optional.of(IntTag.valueOf(1)),
                Optional.of(StringTag.valueOf("https://example.com/")), Optional.of(StringTag.valueOf(ID + "/not-a-key")),
                Optional.of(StringTag.valueOf("9".repeat(10000))))) {
            assertTrue(DiscordSpoilers.click(new ClickEvent.Custom(DiscordSpoilers.REVEAL, payload), clicked::add));
        }
        assertTrue(clicked.isEmpty());
        assertFalse(DiscordSpoilers.click(null, clicked::add));
        assertFalse(DiscordSpoilers.click(new ClickEvent.OpenUrl(URI.create("https://example.com/")), clicked::add));
        assertFalse(DiscordSpoilers.click(new ClickEvent.Custom(Identifier.fromNamespaceAndPath("example", "action"), Optional.empty()), clicked::add));
        // No Minecraft instance is installed in this test: a stale reveal must not open UI or send a packet.
        assertTrue(DiscordMediaClient.handleSpoilerClick(DiscordSpoilers.cover(ID, hidden("stale.png")).getStyle().getClickEvent()));
    }

    @Test void coveredImagesNeverEnterTheDownloaderCacheEvenIfAnotherMessageRevealedThem() throws Exception {
        var image = hidden("test-cache-gate.png"); var first = new DiscordSpoilers(); var second = new DiscordSpoilers();
        var entriesField = DiscordMediaCache.class.getDeclaredField("ENTRIES"); entriesField.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (Map<String, Object>) entriesField.get(null);
        assertFalse(entries.containsKey(image.key()));
        try {
            DiscordMediaCache.register(first.visible(image)); assertFalse(entries.containsKey(image.key()));
            first.reveal(body(image), image.key()); DiscordMediaCache.register(first.visible(image));
            Object entry = entries.get(image.key()); assertNotNull(entry);
            var requested = entry.getClass().getDeclaredField("requested"); requested.setAccessible(true);
            assertFalse(requested.getBoolean(entry)); // Fetch remains lazy until a revealed image is drawn.
            assertTrue(second.visible(image).hidden());
            DiscordMediaCache.register(second.visible(image));
            assertSame(entry, entries.get(image.key())); assertTrue(second.visible(image).hidden());
        } finally { entries.remove(image.key()); }
    }

    @Test void chatClickHookConsumesRevealBeforeVanillaCanSendACustomPacket() throws Exception {
        // The click hook needs no screen fields; bypass Screen's real Minecraft/font constructor.
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var hook = (ChatHook)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(ChatHook.class);
        var method = ChatSpoilerMixin.class.getDeclaredMethod("newyearcountdown$revealSpoiler", Style.class, boolean.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        var own = DiscordSpoilers.cover(ID, hidden("stale.png")).getStyle();
        var handled = new CallbackInfoReturnable<Boolean>("handleComponentClicked", true);
        method.invoke(hook, own, false, handled);
        assertTrue(handled.isCancelled()); assertTrue(handled.getReturnValue());
        var ordinary = new CallbackInfoReturnable<Boolean>("handleComponentClicked", true);
        method.invoke(hook, Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(URI.create("https://example.com/"))), false, ordinary);
        assertFalse(ordinary.isCancelled());
        var shift = new CallbackInfoReturnable<Boolean>("handleComponentClicked", true);
        method.invoke(hook, own, true, shift); assertFalse(shift.isCancelled());
    }
    private static final class ChatHook extends ChatSpoilerMixin {
        private ChatHook() { super(Component.empty()); }
    }
}
