package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordSocialPackets;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordBrowserAuthTest {
    // Browsers use HTTP/1.1 for a plain loopback callback (no h2c upgrade).
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();

    private static DiscordSocialPackets.WebChallenge challenge() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return new DiscordSocialPackets.WebChallenge("s".repeat(43), "123456789012345678", "c".repeat(43),
                    "http://127.0.0.1:" + socket.getLocalPort() + "/callback");
        }
    }
    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String state(DiscordBrowserAuth auth) {
        return DiscordBrowserAuth.query(auth.authorizationUri().getRawQuery()).get("state");
    }

    @Test void browserAuthorizationUsesOnlyIdentifyAndBindsPkceAndState() throws Exception {
        var challenge = challenge();
        try (var auth = DiscordBrowserAuth.listen(challenge)) {
            assertEquals("https", auth.authorizationUri().getScheme());
            assertEquals("discord.com", auth.authorizationUri().getHost());
            var params = DiscordBrowserAuth.query(auth.authorizationUri().getRawQuery());
            assertEquals("identify", params.get("scope"));
            assertEquals("code", params.get("response_type"));
            assertEquals("consent", params.get("prompt"));
            assertEquals("S256", params.get("code_challenge_method"));
            assertEquals(challenge.challenge(), params.get("code_challenge"));
            assertEquals(challenge.redirectUri(), params.get("redirect_uri"));
            assertFalse(params.containsKey("client_secret"));
            assertFalse(params.containsKey("code_verifier"));
            assertNotEquals(challenge.session(), params.get("state"));
            var reply = get(challenge.redirectUri() + "?code=secret%2Bvalue&state=" + state(auth) + "&iss=https%3A%2F%2Fdiscord.com");
            assertEquals(200, reply.statusCode());
            assertEquals("no-store", reply.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("no-referrer", reply.headers().firstValue("Referrer-Policy").orElseThrow());
            assertFalse(reply.body().contains("secret"));
            var result = auth.result.get(2, TimeUnit.SECONDS);
            assertEquals("secret+value", result.code());
            assertEquals(challenge.redirectUri(), result.redirect());
            assertFalse(result.toString().contains(result.code()));
        }
    }

    @Test void rejectsForgedMalformedAndAmbiguousCallbacksWithoutConsumingAttempt() throws Exception {
        var challenge = challenge();
        try (var auth = DiscordBrowserAuth.listen(challenge)) {
            String state = state(auth);
            for (String query : new String[]{"code=bad", "code=bad&state=forged", "code=bad&state=" + state + "&state=" + state,
                    "code=bad&code=other&state=" + state, "code=bad&error=access_denied&state=" + state,
                    "code=&state=" + state, "state=" + state, "code=bad&state=" + state + "&iss=https%3A%2F%2Fevil.example",
                    "code=" + "x".repeat(2049) + "&state=" + state}) {
                assertEquals(400, get(challenge.redirectUri() + "?" + query).statusCode());
                assertFalse(auth.result.isDone());
            }
            assertEquals(404, get(challenge.redirectUri() + "/extra?code=bad&state=" + state).statusCode());
            var post = HTTP.send(HttpRequest.newBuilder(URI.create(challenge.redirectUri())).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
            assertEquals(200, get(challenge.redirectUri() + "?code=good&state=" + state).statusCode());
            assertEquals("good", auth.result.get(2, TimeUnit.SECONDS).code());
        }
    }

    @Test void denialIsSanitizedAndClosesAttempt() throws Exception {
        var challenge = challenge();
        try (var auth = DiscordBrowserAuth.listen(challenge)) {
            var response = get(challenge.redirectUri() + "?error=access_denied&error_description=private-detail&state=" + state(auth));
            assertFalse(response.body().contains("private-detail"));
            var error = assertThrows(ExecutionException.class, () -> auth.result.get(2, TimeUnit.SECONDS));
            assertFalse(error.getCause().getMessage().contains("private-detail"));
        }
    }

    @Test void cancellationReleasesPortAndNewAttemptRejectsOldState() throws Exception {
        var challenge = challenge();
        var old = DiscordBrowserAuth.listen(challenge);
        String oldState = state(old);
        old.close(); old.close();
        assertTrue(old.result.isCancelled());
        try (var fresh = DiscordBrowserAuth.listen(challenge)) {
            assertNotEquals(oldState, state(fresh));
            assertEquals(400, get(challenge.redirectUri() + "?code=stale&state=" + oldState).statusCode());
            assertFalse(fresh.result.isDone());
        }
    }

    @Test void timeoutCompletesAndReleasesListener() throws Exception {
        var challenge = challenge();
        try (var auth = new DiscordBrowserAuth(challenge, 50)) {
            var error = assertThrows(ExecutionException.class, () -> auth.result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, error.getCause());
        }
        try (var retry = DiscordBrowserAuth.listen(challenge)) { assertFalse(retry.result.isDone()); }
    }

    @Test void occupiedPortFailsWithoutReplacingExistingListener() throws Exception {
        var challenge = challenge();
        try (var auth = DiscordBrowserAuth.listen(challenge)) {
            assertThrows(java.io.IOException.class, () -> DiscordBrowserAuth.listen(challenge));
            assertFalse(auth.result.isDone());
        }
    }

    @Test void rejectsRemoteCallbacksBeforeOpeningAnyListener() {
        for (String bad : new String[]{"https://evil.example/callback", "http://0.0.0.0:53137/callback", "http://localhost:53137/callback"}) {
            var c = new DiscordSocialPackets.WebChallenge("s".repeat(43), "123456789012345678", "c".repeat(43), bad);
            assertThrows(IllegalArgumentException.class, () -> DiscordBrowserAuth.listen(c));
        }
    }

    @Test void queryParserRejectsInvalidEncodingAndOversizedRequests() {
        for (String bad : new String[]{null, "state=%", "state=%GG", "state=a&%73tate=b", "missingEquals", "code=" + "x".repeat(8192)})
            assertThrows(IllegalArgumentException.class, () -> DiscordBrowserAuth.query(bad));
    }
}
