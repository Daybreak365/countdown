package dev.newyear.countdown.discord;

import io.netty.buffer.Unpooled;
import com.sun.net.httpserver.HttpServer;
import dev.newyear.countdown.client.DiscordBrowserAuth;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordWebOAuthTest {
    private static final String ID = "123456789012345678";
    @TempDir Path directory;

    @Test void browserCallbackExchangesServerVerifierAndPersistsVerifiedIdentity() throws Exception {
        String redirect;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            redirect = "http://127.0.0.1:" + socket.getLocalPort() + "/callback";
        }
        var sessions = new DiscordSocialSessions();
        UUID player = UUID.randomUUID(); Object connection = new Object();
        var session = sessions.begin(player, connection, "Minecraft player", ID, redirect, 0, true);
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/oauth2/token", exchange -> {
            var form = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean valid = "POST".equals(exchange.getRequestMethod()) && ID.equals(form.get("client_id"))
                    && "server-secret".equals(form.get("client_secret")) && "approved-code".equals(form.get("code"))
                    && "authorization_code".equals(form.get("grant_type")) && session.redirect.equals(form.get("redirect_uri"))
                    && session.webPacket().challenge().equals(DiscordSocialSessions.challenge(form.getOrDefault("code_verifier", "")));
            byte[] body = (valid ? "{\"access_token\":\"mock-token\",\"token_type\":\"Bearer\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        mock.createContext("/users/@me", exchange -> {
            boolean valid = "Bearer mock-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"id\":\"" + ID + "\",\"username\":\"verified-user\",\"global_name\":\"검증된 이름\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 401, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        mock.start();
        try (var browser = DiscordBrowserAuth.listen(session.webPacket()); var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            String state = params(browser.authorizationUri().getRawQuery()).get("state");
            var response = http.send(HttpRequest.newBuilder(URI.create(redirect + "?state=" + state + "&code=approved-code"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var reply = browser.result.get(2, TimeUnit.SECONDS);
            assertTrue(session.acceptsRedirect(reply.redirect()));
            assertSame(session, sessions.claim(player, connection, session.id, 1));
            var oauth = new DiscordOAuth(http, "http://127.0.0.1:" + mock.getAddress().getPort());
            var profile = oauth.profile(ID, "server-secret", session.verifier, reply.code(), reply.redirect());
            var path = directory.resolve("links.json");
            var links = new DiscordLinks(path);
            links.linkVerified(player, session.name, DiscordText.str(profile, "id"), DiscordText.str(profile, "username"),
                    DiscordText.str(profile, "global_name"), "", 2);
            sessions.finish(session);
            assertNull(sessions.claim(player, connection, session.id, 3));
            var loaded = new DiscordLinks(path); loaded.load();
            assertEquals(ID, loaded.get(player).discordId);
            assertEquals("검증된 이름", loaded.get(player).shownName());
            String saved = Files.readString(path);
            for (String privateValue : new String[]{"server-secret", "mock-token", "approved-code", session.verifier})
                assertFalse(saved.contains(privateValue));
        } finally { mock.stop(0); }
    }

    private static Map<String, String> params(String query) {
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test void redirectRequiresExactLoopbackWithUnprivilegedRegisteredPort() {
        assertTrue(DiscordWebOAuth.validRedirect(DiscordWebOAuth.DEFAULT_REDIRECT));
        assertTrue(DiscordWebOAuth.validRedirect("http://127.0.0.1:65535/callback"));
        for (String bad : new String[]{null, "", "http://127.0.0.1/callback", "http://127.0.0.1:0/callback", "http://127.0.0.1:80/callback",
                "http://127.0.0.1:65536/callback", "http://localhost:53137/callback", "http://0.0.0.0:53137/callback",
                "http://127.0.0.1:53137/%63allback", "http://evil@127.0.0.1:53137/callback", "https://127.0.0.1:53137/callback",
                DiscordWebOAuth.DEFAULT_REDIRECT + "?query=1", DiscordWebOAuth.DEFAULT_REDIRECT + "#fragment"})
            assertFalse(DiscordWebOAuth.validRedirect(bad), bad);
    }

    @Test void webSessionsRequireExactRedirectAndRemainSingleUseAcrossConnectionChanges() {
        var sessions = new DiscordSocialSessions();
        UUID player = UUID.randomUUID(); Object connection = new Object();
        var s = sessions.begin(player, connection, "player", ID, DiscordWebOAuth.DEFAULT_REDIRECT, 0, true);
        assertTrue(s.web);
        assertEquals(s.redirect, s.webPacket().redirectUri());
        assertNotEquals(s.verifier, s.webPacket().challenge());
        assertTrue(s.acceptsRedirect(s.redirect));
        assertFalse(s.acceptsRedirect("http://127.0.0.1:53138/callback"));
        assertFalse(s.acceptsRedirect("http://127.0.0.1/callback"));
        assertNull(sessions.claim(player, new Object(), s.id, 1));
        assertSame(s, sessions.claim(player, connection, s.id, 1));
        assertNull(sessions.claim(player, connection, s.id, 2));
        sessions.cancel(player, connection);
        assertFalse(sessions.current(s, 3));
        var next = sessions.begin(player, connection, "player", ID, s.redirect, 4, true);
        assertNull(sessions.claim(player, connection, s.id, 5));
        sessions.clear();
        assertFalse(sessions.current(next, 6));
    }

    @Test void webPacketsRoundTripWithBounds() {
        var bytes = Unpooled.buffer();
        try {
            var status = new DiscordSocialPackets.WebStatus(ID, false, "웹 인증 준비됨");
            DiscordSocialPackets.WebStatus.CODEC.encode(bytes, status);
            assertEquals(status, DiscordSocialPackets.WebStatus.CODEC.decode(bytes));
            var c = new DiscordSocialPackets.WebChallenge("s".repeat(43), ID, "c".repeat(43), DiscordWebOAuth.DEFAULT_REDIRECT);
            DiscordSocialPackets.WebChallenge.CODEC.encode(bytes, c);
            assertEquals(c, DiscordSocialPackets.WebChallenge.CODEC.decode(bytes));
            assertThrows(RuntimeException.class, () -> DiscordSocialPackets.WebChallenge.CODEC.encode(bytes,
                    new DiscordSocialPackets.WebChallenge(c.session(), ID, c.challenge(), "a".repeat(257))));
        } finally { bytes.release(); }
    }

    @Test void webConfigurationCanBeDisabledIndependentlyAndRejectsInvalidRedirect() {
        var cfg = new DiscordConfig();
        cfg.oauthEnabled = false;
        assertFalse(cfg.webReady());
        cfg.oauthEnabled = true; cfg.oauthClientId = ID; cfg.oauthClientSecret = "test-only-secret";
        assertTrue(cfg.webReady());
        cfg.socialSdkEnabled = false;
        assertTrue(cfg.authenticationReady());
        cfg.oauthRedirectUri = "https://remote.example/callback";
        assertFalse(cfg.webReady());
        assertFalse(cfg.authenticationReady());
        cfg.oauthRedirectUri = DiscordWebOAuth.DEFAULT_REDIRECT; cfg.enabled = false;
        assertFalse(cfg.webReady());
    }

    @Test void botTokenAloneReportsMissingOAuthCredentialsInsteadOfSilentlyDisablingConnect() {
        var cfg = new DiscordConfig();
        cfg.token = "private-bot-token";
        var status = DiscordSocialLink.webStatus(cfg, true, "");
        assertEquals("", status.clientId());
        assertTrue(status.codeAvailable());
        assertTrue(status.message().contains("Client ID"));
        assertTrue(status.message().contains("Client Secret"));
        assertFalse(status.message().contains("Redirect URI"));
        assertFalse(status.toString().contains(cfg.token));

        cfg.oauthClientId = ID;
        status = DiscordSocialLink.webStatus(cfg, true, "");
        assertFalse(status.message().contains("Client ID"));
        assertTrue(status.message().contains("Client Secret"));

        cfg.oauthClientSecret = "private-client-secret";
        status = DiscordSocialLink.webStatus(cfg, true, "설정 갱신됨");
        assertEquals(ID, status.clientId());
        assertEquals("설정 갱신됨", status.message());
        assertFalse(status.toString().contains(cfg.oauthClientSecret));
    }

    @Test void invalidRedirectAndDisabledAuthenticationNeverAdvertiseAReadyClient() {
        var cfg = new DiscordConfig();
        cfg.oauthClientId = ID; cfg.oauthClientSecret = "private-client-secret";
        cfg.oauthRedirectUri = "https://private.example/credentials";
        var status = DiscordSocialLink.webStatus(cfg, false, "설정 갱신됨");
        assertEquals("", status.clientId());
        assertTrue(status.message().contains("Redirect URI"));
        assertFalse(status.toString().contains(cfg.oauthRedirectUri));
        assertFalse(status.codeAvailable());
        cfg.oauthRedirectUri = DiscordWebOAuth.DEFAULT_REDIRECT;
        cfg.oauthEnabled = false;
        assertTrue(cfg.webSetupProblem().contains("꺼 두었습니다"));
        assertEquals("", DiscordSocialLink.webStatus(cfg, true, "").clientId());
        cfg.oauthEnabled = true; cfg.enabled = false;
        assertEquals("", DiscordSocialLink.webStatus(cfg, false, "").clientId());
    }

    @Test void existingSocialCredentialsAlsoMakeBrowserConnectAvailableWithoutTheNativeSdk() {
        var cfg = new DiscordConfig();
        cfg.socialSdkClientId = ID; cfg.socialSdkClientSecret = "legacy-secret";
        cfg.socialSdkEnabled = false;
        assertTrue(cfg.webReady());
        assertEquals(ID, DiscordSocialLink.webStatus(cfg, false, "").clientId());
        assertEquals("", cfg.webSetupProblem());
        assertFalse(DiscordSocialLink.webStatus(cfg, false, "").toString().contains(cfg.socialSdkClientSecret));
    }
}
