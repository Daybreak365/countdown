package dev.newyear.countdown.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiscordLogicTest {
    private static final String ID = "123456789012345678";
    private static final String OTHER_ID = "223456789012345678";
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    @TempDir Path dir;

    @Test void avatarRefRoundTripsThroughSpritePath() {
        AvatarRef ref = AvatarRef.of(ID, HASH);
        assertEquals("u/" + ID + "/" + HASH, ref.spritePath());
        assertEquals(ref, AvatarRef.parsePath(ref.spritePath()));
        assertEquals("https://cdn.discordapp.com/avatars/" + ID + "/" + HASH + ".png?size=64", ref.url(64));
        AvatarRef animated = AvatarRef.of(ID, "a_" + HASH);
        assertEquals(animated, AvatarRef.parsePath(animated.spritePath()));
    }

    @Test void avatarRefFallsBackToDefaultAvatar() {
        AvatarRef ref = AvatarRef.of(ID, null);
        assertFalse(ref.hasCustomAvatar());
        assertEquals(ref, AvatarRef.parsePath(ref.spritePath()));
        assertEquals("https://cdn.discordapp.com/embed/avatars/" + ((Long.parseLong(ID) >>> 22) % 6) + ".png", ref.url(64));
        assertFalse(AvatarRef.of(ID, "../../evil").hasCustomAvatar());
    }

    @Test void avatarRefRejectsAnythingThatCouldChangeTheUrl() {
        assertNull(AvatarRef.of("12a45", HASH));
        assertNull(AvatarRef.of(null, HASH));
        for (String path : new String[]{"", "u/", "u/" + ID, "u/" + ID + "/", "u/" + ID + "/xyz", "u/" + ID + "/" + HASH + "/x",
                "u/" + ID + "/" + HASH + "?x", "u/abc/" + HASH, "x/" + ID + "/" + HASH, "u/" + ID + "/..", "u/" + ID + "/" + HASH.toUpperCase()})
            assertNull(AvatarRef.parsePath(path), path);
    }

    @Test void resampleAveragesAndKeepsSolidColors() {
        int[] solid = new int[16 * 16];
        java.util.Arrays.fill(solid, 0xFF336699);
        for (int n : new int[]{8, 12, 16, 24}) {
            int[] out = AvatarRaster.resample(solid, 16, 16, n);
            assertEquals(n * n, out.length);
            for (int p : out) assertEquals(0xFF336699, p);
        }
        int[] checker = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        assertEquals(0xFF808080, AvatarRaster.resample(checker, 2, 2, 1)[0]);
        // 투명한 픽셀의 색은 평균에 섞이지 않는다
        assertEquals(0x80FF0000, AvatarRaster.resample(new int[]{0xFFFF0000, 0x0000FF00}, 2, 1, 1)[0]);
    }

    @Test void circleMaskClearsCornersAndKeepsCentre() {
        int n = 16;
        int[] px = AvatarRaster.solidCircle(n, 0xFF5865F2);
        assertEquals(0, px[0]);
        assertEquals(0, px[n - 1]);
        assertEquals(0, px[n * n - 1]);
        assertEquals(0xFF5865F2, px[(n / 2) * n + n / 2]);
        // 가장자리 한가운데는 원 안이지만 경계라 살짝 부드럽게 깎인다
        assertTrue((px[(n / 2) * n] >>> 24) > 0xF0);
        assertEquals(0x5865F2, px[(n / 2) * n] & 0xFFFFFF);
    }

    @Test void linkingRedeemsACodeOnceAndPersists() throws Exception {
        Path file = dir.resolve("links.json");
        DiscordLinks links = new DiscordLinks(file, new Random(1));
        UUID player = UUID.randomUUID();
        String code = links.createCode(player, "Steve", 1000);
        assertTrue(code.matches("[0-9]{6}"));

        String wrong = "000000".equals(code) ? "111111" : "000000";
        assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(wrong, ID, "steve", "Steve D", HASH, 2000).status());
        DiscordLinks.Redeemed ok = links.redeem(" " + code.substring(0, 3) + "-" + code.substring(3) + " ", ID, "steve", "Steve D", HASH, 3000);
        assertEquals(DiscordLinks.RedeemStatus.OK, ok.status());
        assertEquals(player, ok.player());
        assertEquals("Steve", ok.playerName());
        assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(code, OTHER_ID, "x", "", "", 4000).status());

        assertEquals(player, links.playerOf(ID));
        assertEquals(AvatarRef.of(ID, HASH), links.get(player).avatarRef());
        assertEquals("Steve D", links.get(player).shownName());
        links.save();

        DiscordLinks reloaded = new DiscordLinks(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertEquals("steve", reloaded.get(player).username);
        assertEquals(HASH, reloaded.get(player).avatar);
    }

    @Test void codesExpireAndOnlyTheNewestCodeWorks() {
        DiscordLinks links = new DiscordLinks(null, new Random(2));
        UUID player = UUID.randomUUID();
        String first = links.createCode(player, "Alex", 0);
        String second = links.createCode(player, "Alex", 10);
        assertNotEquals(first, second);
        assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(first, ID, "a", "", "", 20).status());
        assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(second, ID, "a", "", "", 10 + DiscordLinks.CODE_TTL_MS).status());
        String third = links.createCode(player, "Alex", 0);
        assertEquals(DiscordLinks.RedeemStatus.OK, links.redeem(third, ID, "a", "", "", DiscordLinks.CODE_TTL_MS - 1).status());
    }

    @Test void repeatedWrongCodesAreLockedOutForAWhile() {
        DiscordLinks links = new DiscordLinks(null, new Random(3));
        UUID player = UUID.randomUUID();
        String code = links.createCode(player, "Alex", 0);
        String wrong = code.equals("999999") ? "888888" : "999999";
        for (int i = 0; i < DiscordLinks.MAX_FAILURES; i++)
            assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(wrong, ID, "a", "", "", i).status());
        assertEquals(DiscordLinks.RedeemStatus.TOO_MANY_ATTEMPTS, links.redeem(code, ID, "a", "", "", 100).status());
        assertNull(links.get(player));
        // 다른 디스코드 계정은 막히지 않는다
        assertEquals(DiscordLinks.RedeemStatus.OK, links.redeem(code, OTHER_ID, "b", "", "", 200).status());
        // 시간이 지나면 풀린다
        String again = links.createCode(player, "Alex", DiscordLinks.CODE_TTL_MS + 10);
        assertEquals(DiscordLinks.RedeemStatus.OK, links.redeem(again, ID, "a", "", "", DiscordLinks.CODE_TTL_MS + 20).status());
    }

    @Test void oneDiscordAccountLinksToOnePlayerAndProfilesUpdate() {
        DiscordLinks links = new DiscordLinks(null, new Random(4));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        links.redeem(links.createCode(a, "A", 0), ID, "user", "", "", 1);
        links.redeem(links.createCode(b, "B", 0), ID, "user", "", "", 2);
        assertNull(links.get(a));
        assertEquals(b, links.playerOf(ID));

        assertFalse(links.updateProfile(ID, "user", "", ""));
        assertFalse(links.updateProfile(ID, null, null, null));
        assertTrue(links.updateProfile(ID, "user", "Nick", HASH));
        assertEquals("Nick", links.get(b).shownName());
        assertFalse(links.updateProfile("999999999999999999", "x", "y", HASH));

        assertEquals("B", links.unlinkDiscord(ID).playerName);
        assertNull(links.unlink(b));
    }

    @Test void markdownIsEscapedOnTheWayOut() {
        assertEquals("\\*\\*hi\\*\\* \\_x\\_ \\`c\\` \\|\\|s\\|\\| \\[a\\]\\(b\\) \\> \\# \\-", DiscordText.escapeMarkdown("**hi** _x_ `c` ||s|| [a](b) > # -"));
        assertEquals("새해 복 많이", DiscordText.stripFormatting("§6§l새해 §f복 많이"));
    }

    @Test void inboundTextIsFlattenedAndSanitised() {
        JsonArray mentions = JsonParser.parseString("[{\"id\":\"" + ID + "\",\"username\":\"steve\",\"global_name\":\"Steve D\"}]").getAsJsonArray();
        assertEquals("hi @Steve D and @알 수 없는 사용자 :wave: bye",
                DiscordText.inbound("hi <@" + ID + "> and <@!" + OTHER_ID + ">\n\n<:wave:123456789012345678>\tbye", mentions, 0));
        assertEquals("red text", DiscordText.inbound("§cred\u0000 text", null, 0));
        assertEquals("[첨부 2개]", DiscordText.inbound("", null, 2));
        assertEquals("pic [첨부 1개]", DiscordText.inbound("pic", null, 1));
        assertEquals("", DiscordText.inbound("  \n ", null, 0));
        String longText = DiscordText.inbound("가".repeat(1000), null, 0);
        assertEquals(DiscordText.MAX_INBOUND + 1, longText.length());
        assertTrue(longText.endsWith("…"));
    }

    @Test void displayNamePrefersNickThenGlobalNameThenUsername() {
        JsonObject user = JsonParser.parseString("{\"username\":\"steve\",\"global_name\":\"Steve D\"}").getAsJsonObject();
        JsonObject member = JsonParser.parseString("{\"nick\":\"길드§c별명\"}").getAsJsonObject();
        assertEquals("길드별명", DiscordText.displayName(user, member));
        assertEquals("Steve D", DiscordText.displayName(user, new JsonObject()));
        user.add("global_name", com.google.gson.JsonNull.INSTANCE);
        assertEquals("steve", DiscordText.displayName(user, null));
        assertEquals("디스코드 사용자", DiscordText.displayName(new JsonObject(), null));
    }
}
