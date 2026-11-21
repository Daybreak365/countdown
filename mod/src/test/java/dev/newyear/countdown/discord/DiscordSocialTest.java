package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSocialTest {
    private static final String ID = "123456789012345678";
    @TempDir Path dir;

    @Test void pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DiscordSocialSessions.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
    @Test void sessionsRejectWrongPlayerConnectionReplayAndExpiry() {
        var sessions = new DiscordSocialSessions();
        UUID player = UUID.randomUUID(); Object connection = new Object();
        var s = sessions.begin(player, connection, "플레이어", ID, "http://127.0.0.1/callback", 1000);
        assertNotEquals(s.verifier, s.packet().challenge());
        assertNull(sessions.claim(UUID.randomUUID(), connection, s.id, 1001));
        assertNull(sessions.claim(player, new Object(), s.id, 1001));
        assertNull(sessions.claim(player, connection, "wrong", 1001));
        assertSame(s, sessions.claim(player, connection, s.id, 1001));
        assertNull(sessions.claim(player, connection, s.id, 1002));
        assertNull(sessions.begin(player, connection, "플레이어", ID, s.redirect, 7000));
        assertFalse(sessions.current(s, 1000 + DiscordSocialSessions.TTL));
    }
    @Test void disconnectAndNewSessionInvalidatePendingCompletion() {
        var sessions = new DiscordSocialSessions(); UUID player = UUID.randomUUID(); Object old = new Object(), fresh = new Object();
        var s = sessions.begin(player, old, "name", ID, "http://127.0.0.1/callback", 0);
        sessions.cancel(player, fresh); assertTrue(sessions.current(s, 1));
        sessions.cancel(player, old); assertFalse(sessions.current(s, 1));
        var next = sessions.begin(player, fresh, "name", ID, s.redirect, 2);
        assertNotEquals(s.id, next.id);
        sessions.finish(s); assertTrue(sessions.current(next, 3));
        sessions.clear(); assertFalse(sessions.current(next, 4));
    }
    @Test void startRequestsHaveCooldownAndBoundedLifetime() {
        var sessions = new DiscordSocialSessions(); UUID id = UUID.randomUUID(); Object connection = new Object();
        var first = sessions.begin(id, connection, "name", ID, "http://127.0.0.1/callback", 0);
        assertNull(sessions.begin(id, connection, "name", ID, first.redirect, 4999));
        assertNotNull(sessions.begin(id, connection, "name", ID, first.redirect, 5000));
        assertFalse(sessions.current(first, 5001));
    }
    @Test void redirectOnlyAllowsSdkLoopbackCallback() {
        String expected = "http://127.0.0.1/callback";
        assertTrue(DiscordOAuth.allowedRedirect(expected, expected));
        assertTrue(DiscordOAuth.allowedRedirect(expected, "http://127.0.0.1:48631/callback"));
        for (String bad : new String[]{"https://evil.example/callback", "http://127.0.0.1.evil.example/callback", "http://127.0.0.1@evil.example/callback",
                "http://evil@127.0.0.1/callback", expected + "?other=1", expected + "#fragment", "http://127.0.0.1/other", "http://127.0.0.1:99999/callback"})
            assertFalse(DiscordOAuth.allowedRedirect(expected, bad), bad);
    }
    @Test void verifiedLinkPersistsIdentityAndConsumesOldCodes() throws Exception {
        var links = new DiscordLinks(dir.resolve("links.json")); UUID player = UUID.randomUUID();
        String oldCode = links.createCode(player, "플레이어", 10);
        var link = links.linkVerified(player, "플레이어", ID, "handle", "한글 이름", "", 20);
        assertEquals("한글 이름", link.shownName());
        var reload = new DiscordLinks(dir.resolve("links.json")); reload.load();
        assertEquals(ID, reload.get(player).discordId);
        assertEquals(DiscordLinks.RedeemStatus.BAD_CODE, links.redeem(oldCode, ID, "handle", "", "", 30).status());
        assertThrows(IllegalArgumentException.class, () -> links.linkVerified(UUID.randomUUID(), "other", ID, "handle", "", "", 40));
        assertEquals(player, links.playerOf(ID));
    }
    @Test void failedSaveDoesNotLeaveAnUnpersistedVerifiedLink() {
        var links = new DiscordLinks(dir.resolve("missing/links.json")); UUID player = UUID.randomUUID();
        assertThrows(IOException.class, () -> links.linkVerified(player, "name", ID, "handle", "", "", 1));
        assertNull(links.get(player));
    }
    @Test void packetsRoundTripAndBoundAuthCodeSize() {
        var bytes = Unpooled.buffer();
        try {
            var c = new DiscordSocialPackets.Challenge("s".repeat(43), ID, "c".repeat(43));
            DiscordSocialPackets.Challenge.CODEC.encode(bytes, c);
            assertEquals(c, DiscordSocialPackets.Challenge.CODEC.decode(bytes));
            var r = new DiscordSocialPackets.Response(c.session(), "authorization", "http://127.0.0.1/callback");
            DiscordSocialPackets.Response.CODEC.encode(bytes, r);
            assertEquals(r, DiscordSocialPackets.Response.CODEC.decode(bytes));
            assertFalse(r.toString().contains("authorization,"));
            assertFalse(r.toString().contains(r.session()));
            assertThrows(RuntimeException.class, () -> DiscordSocialPackets.Response.CODEC.encode(bytes,
                    new DiscordSocialPackets.Response(c.session(), "a".repeat(2049), r.redirectUri())));
        } finally { bytes.release(); }
    }
    @Test void oauthExchangesPkceThenUsesBearerIdentityAndHidesFailureBody() throws Exception {
        List<String> paths = new ArrayList<>(), bodies = new ArrayList<>(), auth = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = paths.size() == 1 ? "{\"access_token\":\"test-only-token\",\"token_type\":\"Bearer\"}" : "{\"id\":\"" + ID + "\",\"username\":\"verified\"}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            var oauth = new DiscordOAuth(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("verified", oauth.profile(ID, "secret+&", "verifier", "code+&", "http://127.0.0.1/callback").get("username").getAsString());
            assertEquals(List.of("/oauth2/token", "/users/@me"), paths);
            assertTrue(bodies.getFirst().contains("client_secret=secret%2B%26"));
            assertTrue(bodies.getFirst().contains("code_verifier=verifier"));
            assertTrue(bodies.getFirst().contains("code=code%2B%26"));
            assertNull(auth.getFirst()); assertEquals("Bearer test-only-token", auth.get(1));
        } finally { server.stop(0); }
    }
    @Test void oauthErrorNeverExposesSecretResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Consume the POST before closing the exchange; an unread request can race the
            // JDK client's upload and turn the intended HTTP 400 into a timeout on Windows.
            exchange.getRequestBody().readAllBytes();
            byte[] data = "private-token-should-not-appear".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, data.length); exchange.getResponseBody().write(data); exchange.close(); });
        server.start();
        try {
            // This fixture is HTTP/1.1; do not negotiate a cleartext HTTP/2 upgrade on the error response.
            var oauth = new DiscordOAuth(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), "http://127.0.0.1:" + server.getAddress().getPort());
            IOException error = assertThrows(IOException.class, () -> oauth.profile(ID, "private", "verifier", "code", "http://127.0.0.1/callback"));
            assertEquals("Discord 인증 HTTP 400", error.getMessage());
        } finally { server.stop(0); }
    }
}
