package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Server-only code exchange. Tokens and authorization codes are neither persisted nor logged. */
final class DiscordOAuth {
    private final HttpClient http;
    private final String base;
    DiscordOAuth() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), "https://discord.com/api/v10"); }
    DiscordOAuth(HttpClient http, String base) { this.http = http; this.base = base; }

    JsonObject profile(String clientId, String secret, String verifier, String code, String redirect) throws IOException, InterruptedException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("client_id", clientId); data.put("client_secret", secret);
        data.put("grant_type", "authorization_code"); data.put("code", code);
        data.put("code_verifier", verifier); data.put("redirect_uri", redirect);
        JsonObject token = send(form("/oauth2/token", data));
        String access = DiscordText.str(token, "access_token");
        if (access == null || access.isBlank()) throw new IOException("Discord 인증 토큰 응답 오류");
        // The token is used once for identity. Do not revoke all grants: that would invalidate
        // other sessions belonging to this same Discord application. No token is retained here.
        JsonObject user = send(HttpRequest.newBuilder(URI.create(base + "/users/@me"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + access).GET().build());
        if (!AvatarRef.isUserId(DiscordText.str(user, "id")) || DiscordText.str(user, "username") == null)
            throw new IOException("Discord 프로필 응답 오류");
        return user;
    }
    private HttpRequest form(String path, Map<String, String> values) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(values))).build();
    }
    static String encode(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IOException("Discord 인증 HTTP " + res.statusCode());
        try { return JsonParser.parseString(res.body()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Discord 인증 응답 오류"); }
    }
    static boolean allowedRedirect(String configured, String received) {
        if (configured == null || received == null) return false;
        try {
            URI expected = URI.create(configured), actual = URI.create(received);
            if (actual.getUserInfo() != null || actual.getFragment() != null || actual.getQuery() != null) return false;
            if (!"http".equals(actual.getScheme()) || !"127.0.0.1".equals(actual.getHost()) || !"/callback".equals(actual.getPath())) return false;
            return expected.equals(actual) || ("http://127.0.0.1/callback".equals(configured)
                    && actual.getPort() > 0 && actual.getPort() <= 65535);
        } catch (IllegalArgumentException e) { return false; }
    }
}
