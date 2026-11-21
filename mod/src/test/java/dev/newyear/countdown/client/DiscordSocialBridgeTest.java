package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordSocialPackets;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSocialBridgeTest {
    @Test void acceptsOnlyBoundedSdkProtocolAndNeverTreatsLogsAsAuthentication() throws Exception {
        String redirect = "http://127.0.0.1:12345/callback";
        var reply = DiscordSocialBridge.parse("sdk log\nNYDS1 OK " + hex("code-123") + " " + hex(redirect) + "\n");
        assertEquals("code-123", reply.code()); assertEquals(redirect, reply.redirect());
        for (String bad : new String[]{"successful: code-123", "NYDS1 ERROR denied", "NYDS1 OK zz aa", "NYDS1 OK  " + hex(redirect),
                "NYDS1 OK " + hex("code\nforged") + " " + hex(redirect), "NYDS1 OK " + "61".repeat(2049) + " " + hex(redirect)})
            assertThrows(IOException.class, () -> DiscordSocialBridge.parse(bad));
    }
    @Test void refusesServerControlledArgumentsAndInvalidPkce() {
        assertTrue(DiscordSocialBridge.validChallenge(new DiscordSocialPackets.Challenge("s".repeat(43), "123456789012345678", "c".repeat(43))));
        assertFalse(DiscordSocialBridge.validChallenge(new DiscordSocialPackets.Challenge("s".repeat(43), "1\n2", "c".repeat(43))));
        assertFalse(DiscordSocialBridge.validChallenge(new DiscordSocialPackets.Challenge("../binary", "123456789012345678", "c".repeat(43))));
    }
    private static String hex(String s) { return HexFormat.of().formatHex(s.getBytes(StandardCharsets.UTF_8)); }
}
