package dev.newyear.countdown.discord;

import io.netty.buffer.Unpooled;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisplayNameTest {
    @Test void discordOriginIconPrecedesPortraitAndSurvivesHiddenAvatars() {
        boolean before = DiscordConfig.get().showAvatarInChat;
        try {
            for (boolean show : new boolean[]{true, false}) {
                DiscordConfig.get().showAvatarInChat = show;
                var line = DiscordChat.fromDiscord(AvatarRef.of("123456789012345678", ""), "이름", "handle", "본문");
                var first = line.getSiblings().getFirst();
                var icon = (net.minecraft.network.chat.contents.objects.AtlasSprite) ((net.minecraft.network.chat.contents.ObjectContents) first.getContents()).contents();
                assertEquals(DiscordChat.DISCORD_ICON, icon.atlas());
                assertTrue(line.getString().endsWith("이름: 본문"));
            }
        } finally { DiscordConfig.get().showAvatarInChat = before; }
    }
    @TempDir Path dir;

    @Test void koreanNamesAreNormalizedAndPersistWithTheirStyle() throws Exception {
        UUID id = UUID.randomUUID();
        DisplayNameStyle style = new DisplayNameStyle("  \u1112\u1161\u11AB글 닉네임  ", 0x123456, 0xAABBCC, true, true).checked();
        assertEquals("한글 닉네임", style.text());
        DisplayNameStore store = new DisplayNameStore(dir.resolve("names.json"));
        store.load();
        store.set(id, style);
        DisplayNameStore reloaded = new DisplayNameStore(dir.resolve("names.json"));
        reloaded.load();
        assertEquals(style, reloaded.get(id));
        reloaded.set(id, null);
        store.load();
        assertNull(store.get(id));
    }

    @Test void gradientUsesUnicodeCodePointsAndDoesNotSplitSurrogatePairs() {
        var style = new DisplayNameStyle("별🌟빛", 0x000000, 0xFFFFFF, true, false).checked();
        assertEquals(0, style.colorAt(0, 3));
        assertEquals(0x808080, style.colorAt(1, 3));
        assertEquals(0xFFFFFF, style.colorAt(2, 3));
        var component = style.component();
        assertEquals("별🌟빛", component.getString());
        assertEquals(3, component.getSiblings().size());
        assertEquals("🌟", component.getSiblings().get(1).getString());
        assertEquals(0x808080, component.getSiblings().get(1).getStyle().getColor().getValue());
        assertEquals(0x123456, new DisplayNameStyle("별", 0x123456, 0xFFFFFF, true, false).colorAt(0, 1));
    }

    @Test void invalidNamesAndColorsAreRejected() {
        for (String name : new String[]{"", "   ", "가".repeat(33), "닉\n네임", "닉\t네임", "닉\u2028네임", "닉\u202E네임", "§a닉네임", "닉\uD800"})
            assertThrows(IllegalArgumentException.class, () -> DisplayNameStyle.plain(name).checked(), name);
        assertDoesNotThrow(() -> DisplayNameStyle.plain("🌟".repeat(32)).checked());
        assertEquals(0xAABBCC, DisplayNameStyle.parseColor("#aAbBcC"));
        for (String color : new String[]{"abc", "#ABC", "#GG0000", "#FFFFFFFF", "-1", "FFFFFF"})
            assertThrows(IllegalArgumentException.class, () -> DisplayNameStyle.parseColor(color));
        assertThrows(IllegalArgumentException.class, () -> new DisplayNameStyle("이름", -1, 0, false, false).checked());
    }

    @Test void badFileCannotBeSilentlyOverwritten() throws Exception {
        Path file = dir.resolve("names.json");
        Files.writeString(file, "{broken");
        DisplayNameStore store = new DisplayNameStore(file);
        assertThrows(java.io.IOException.class, store::load);
        assertThrows(java.io.IOException.class, () -> store.set(UUID.randomUUID(), DisplayNameStyle.plain("이름")));
        assertEquals("{broken", Files.readString(file));
    }

    @Test void failedWriteDoesNotChangeLiveName() throws Exception {
        Path file = dir.resolve("names.json");
        UUID id = UUID.randomUUID();
        DisplayNameStore store = new DisplayNameStore(file);
        store.load();
        store.set(id, DisplayNameStyle.plain("원래 이름"));
        Files.createDirectory(dir.resolve("names.json.tmp"));
        assertThrows(java.io.IOException.class, () -> store.set(id, DisplayNameStyle.plain("새 이름")));
        assertEquals("원래 이름", store.get(id).text());
    }

    @Test void styledNamePreservesColorWhenInheritingPlayerMetadata() {
        var component = DisplayNameStyle.plain("이름").component().copy()
                .withStyle(s -> s.applyTo(Style.EMPTY.withColor(0xFF0000).withInsertion("account")));
        assertEquals(0xFFFFFF, component.getStyle().getColor().getValue());
        assertEquals("account", component.getStyle().getInsertion());
    }

    @Test void editorPacketsRoundTripUnicodeAndReset() {
        var payload = new DisplayNamePackets.SaveC2S(UUID.randomUUID(),
                new DisplayNameStyle("한글 이름 🌟", 0x112233, 0xAABBCC, true, true), true);
        var bytes = Unpooled.buffer();
        try {
            DisplayNamePackets.SaveC2S.CODEC.encode(bytes, payload);
            assertEquals(payload, DisplayNamePackets.SaveC2S.CODEC.decode(bytes));
            assertEquals(0, bytes.readableBytes());
        } finally { bytes.release(); }
    }

    @Test void tabIdentityContainsExactlyOneExtractableAvatar() {
        Component avatar = DiscordChat.avatar(AvatarRef.of("123456789012345678", ""), null);
        Component name = new DisplayNameStyle("새벽", 0x123456, 0xABCDEF, true, true).component();
        Component identity = Component.empty().append(avatar).append(" ").append(name);
        assertEquals(avatar, DiscordChat.tabAvatar(identity));
        assertEquals("새벽", DiscordChat.withoutTabAvatar(identity).getString());
        assertEquals(name, DiscordChat.withoutTabAvatar(identity).getSiblings().getFirst());
        assertNull(DiscordChat.tabAvatar(name));
        assertNull(DiscordChat.withoutTabAvatar(null));
    }

    @Test void graphicalSeparatorDoesNotUseAPipeAndLeavesMessageStyleAlone() {
        Component prefix = Component.empty().append(DisplayNameStyle.plain("한글").component()).append(DiscordChat.separator());
        Component body = Component.literal("hello <world> | 본문").withColor(0x123456);
        assertEquals("한글: hello <world> | 본문", Component.translatable("%s%s", prefix, body).getString());
        assertEquals(0x123456, body.getStyle().getColor().getValue());
        assertInstanceOf(net.minecraft.network.chat.contents.ObjectContents.class, DiscordChat.separator().getContents());
    }

    @Test void legacyNameFilesMigrateWithoutLosingTheirAppearance() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = dir.resolve("names.json");
        Files.writeString(file, "{\"" + id + "\":{\"text\":\"새벽\",\"startColor\":16711680,\"endColor\":255,\"gradient\":true,\"bold\":true}}");
        var store = new DisplayNameStore(file);
        store.load();
        var old = store.get(id).effectiveRuns().getFirst();
        assertEquals("새벽", old.text());
        assertEquals(0xFF0000, old.startColor());
        assertEquals(0x0000FF, old.endColor());
        assertTrue(old.gradient());
        assertTrue(old.bold());
        var editor = new dev.newyear.countdown.client.DisplayNameEditorModel(store.get(id));
        editor.apply(0, 1, 0x00FF00, 0xFFFFFF, true, false);
        store.set(id, editor.value());
        store.load();
        assertEquals(editor.value(), store.get(id));
        assertEquals(2, store.get(id).effectiveRuns().size());
    }

    @Test void styledRunsRoundTripAndCannotSpoofVisibleText() {
        var style = DisplayNameStyle.ofRuns(java.util.List.of(
                new DisplayNameStyle.Run("새", 0x123456, 0x654321, true, false),
                new DisplayNameStyle.Run("벽", 0xABCDEF, 0xABCDEF, false, true))).checked();
        var bytes = Unpooled.buffer();
        try {
            DisplayNameStyle.CODEC.encode(bytes, style);
            assertEquals(style, DisplayNameStyle.CODEC.decode(bytes));
        } finally { bytes.release(); }
        assertThrows(IllegalArgumentException.class, () -> new DisplayNameStyle("다른 이름", 0, 0, false, false, style.runs()).checked());
        assertThrows(IllegalArgumentException.class, () -> DisplayNameStyle.ofRuns(java.util.List.of(
                new DisplayNameStyle.Run("가", 0xFFFFFFFF, 0, false, false))).checked());
        assertThrows(IllegalArgumentException.class, () -> DisplayNameStyle.ofRuns(java.util.List.of(
                new DisplayNameStyle.Run("\uD83C", 0, 0, false, false),
                new DisplayNameStyle.Run("\uDF1F", 0, 0, false, false))).checked());
    }

    @Test void gradientMetadataIsBoundedAndUnicodeSafe() {
        for (String name : new String[]{"한", "새벽", "🌟".repeat(32)}) {
            var font = new GradientFont(name, 0x123456, 0xABCDEF, true, 0);
            assertEquals(font, GradientFont.parse(font.id()));
        }
        assertNull(GradientFont.parse(net.minecraft.resources.Identifier.parse("newyearcountdown:name_gradient/ffffff/000000/0/99/61")));
        assertNull(GradientFont.parse(net.minecraft.resources.Identifier.parse("newyearcountdown:name_gradient/ffffff/000000/0/0/ff")));
        assertNull(GradientFont.parse(net.minecraft.resources.Identifier.parse("newyearcountdown:name_gradient/ffffff/000000/0/0/" + "61".repeat(500))));
        assertNull(GradientFont.parse(net.minecraft.resources.Identifier.parse("minecraft:default")));
    }
}
