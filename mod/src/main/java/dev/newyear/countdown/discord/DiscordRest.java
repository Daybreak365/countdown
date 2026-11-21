package dev.newyear.countdown.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 디스코드 REST API 호출. 호출하는 스레드를 막으므로 게임 스레드에서 부르지 않는다. */
final class DiscordRest {
    /** 시스템 속성은 로컬 가짜 서버로 시험할 때만 쓴다. */
    static final String BASE = System.getProperty("newyearcountdown.discord.api", "https://discord.com/api/v10");
    static final String USER_AGENT = "DiscordBot (https://fabricmc.net, 1.0)";
    private static final int MAX_TRIES = 3;

    /** 디스코드가 요청을 거절함. status 는 HTTP 상태. */
    static final class RestException extends IOException {
        final int status;

        RestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpClient http;
    private final String token;
    private final String base;

    DiscordRest(String token) {
        this(token, BASE);
    }

    DiscordRest(String token, String base) {
        this.token = token;
        this.base = base;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Plain HTTP is only used by local test/proxy endpoints, which do not support h2c upgrades.
                .version(base.startsWith("http://") ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    HttpClient http() {
        return http;
    }

    JsonElement get(String path) throws IOException, InterruptedException {
        return request("GET", path, null, true);
    }

    JsonElement post(String path, JsonElement body) throws IOException, InterruptedException {
        return request("POST", path, body, true);
    }

    JsonElement put(String path, JsonElement body) throws IOException, InterruptedException {
        return request("PUT", path, body, true);
    }

    /** 상호작용 응답은 주소에 든 토큰으로 인증하므로 봇 토큰을 붙이지 않는다. */
    JsonElement postUnauthenticated(String path, JsonElement body) throws IOException, InterruptedException {
        return request("POST", path, body, false);
    }

    private JsonElement request(String method, String path, JsonElement body, boolean auth) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT);
            if (auth) b.header("Authorization", "Bot " + token);
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = res.statusCode();
            if (status == 429 && attempt < MAX_TRIES) {
                Thread.sleep(retryAfterMs(res));
                continue;
            }
            if (status >= 500 && attempt < MAX_TRIES) {
                Thread.sleep(1000L * attempt);
                continue;
            }
            if (status < 200 || status >= 300) throw new RestException(status, describe(status, res.body()));
            String text = res.body();
            if (text == null || text.isBlank()) return JsonNull.INSTANCE;
            try {
                return JsonParser.parseString(text);
            } catch (RuntimeException e) {
                throw new IOException("디스코드 응답을 해석하지 못했습니다");
            }
        }
    }

    private static long retryAfterMs(HttpResponse<String> res) {
        double seconds = 1;
        try {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            if (o.has("retry_after")) seconds = o.get("retry_after").getAsDouble();
        } catch (RuntimeException ignored) {
            seconds = res.headers().firstValue("Retry-After").map(v -> {
                try { return Double.parseDouble(v); } catch (NumberFormatException e) { return 1.0; }
            }).orElse(1.0);
        }
        return (long) (Math.max(0.1, Math.min(30, seconds)) * 1000) + 100;
    }

    private static String describe(int status, String body) {
        String message = "";
        try {
            message = DiscordText.str(JsonParser.parseString(body).getAsJsonObject(), "message");
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드만 알린다
        }
        return "HTTP " + status + (message == null || message.isBlank() ? "" : " " + message);
    }
}
