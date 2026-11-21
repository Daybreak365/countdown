package dev.newyear.countdown.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.newyear.countdown.discord.DiscordSocialPackets;
import dev.newyear.countdown.discord.DiscordWebOAuth;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A short-lived callback listener on the player's loopback interface, with no SDK dependency. */
public final class DiscordBrowserAuth implements AutoCloseable {
    public record Reply(String code, String redirect) {
        @Override public String toString() { return "DiscordBrowserReply[authorization code redacted]"; }
    }
    public final CompletableFuture<Reply> result = new CompletableFuture<>();
    private final HttpServer server;
    private final ExecutorService executor;
    private final String state, redirect;
    private final URI authorization;
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public static DiscordBrowserAuth listen(DiscordSocialPackets.WebChallenge challenge) throws IOException {
        return new DiscordBrowserAuth(challenge, 165_000);
    }

    DiscordBrowserAuth(DiscordSocialPackets.WebChallenge challenge, long timeoutMs) throws IOException {
        if (challenge.session() == null || !challenge.session().matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("올바르지 않은 인증 요청입니다.");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        redirect = challenge.redirectUri();
        authorization = DiscordWebOAuth.authorization(challenge.clientId(), challenge.challenge(), redirect, state);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", URI.create(redirect).getPort()), 4);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "NewYear-Discord-Browser"); t.setDaemon(true); return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::callback);
        // HttpServer inherits daemon status from its starting thread.
        Thread starter = new Thread(server::start, "NewYear-Discord-Callback");
        starter.setDaemon(true);
        starter.start();
        try { starter.join(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); release(); throw new IOException("인증 시작이 중단되었습니다.");
        }
        // Let the browser receive the last page before tearing down its keep-alive socket.
        // Cancellation before a callback releases the port immediately.
        result.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((reply, error) -> {
            if (consumed.get()) CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(this::release);
            else release();
        });
    }

    public URI authorizationUri() { return authorization; }

    private void callback(HttpExchange exchange) throws IOException {
        try {
            URI uri = exchange.getRequestURI();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                page(exchange, 405, "지원하지 않는 요청입니다."); return;
            }
            if (!"/callback".equals(uri.getRawPath()) || uri.isAbsolute()) {
                page(exchange, 404, "인증 페이지를 찾을 수 없습니다."); return;
            }
            if (!URI.create(redirect).getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
                page(exchange, 400, "올바르지 않은 인증 주소입니다."); return;
            }
            Map<String, String> params;
            try { params = query(uri.getRawQuery()); }
            catch (IllegalArgumentException e) { page(exchange, 400, "올바르지 않은 인증 응답입니다."); return; }
            if (!state.equals(params.get("state")) || (params.containsKey("iss") && !"https://discord.com".equals(params.get("iss")))) {
                page(exchange, 400, "인증 요청을 확인할 수 없습니다. 게임에서 연동을 다시 시작해 주세요."); return;
            }
            String code = params.get("code"), error = params.get("error");
            if ((code == null) == (error == null) || (code != null && (code.isBlank() || code.length() > 2048))) {
                page(exchange, 400, "올바르지 않은 인증 응답입니다."); return;
            }
            if (result.isDone() || !consumed.compareAndSet(false, true)) {
                page(exchange, 409, "이미 처리했거나 만료된 인증 요청입니다."); return;
            }
            if (error != null) {
                try { page(exchange, 200, "Discord 인증을 취소했습니다. 게임에서 다시 연결할 수 있습니다."); }
                finally { result.completeExceptionally(new IOException("Discord 인증이 취소되었거나 거부되었습니다.")); }
            } else {
                try { page(exchange, 200, "Discord 승인을 받았습니다. 게임으로 돌아가 연동 결과를 확인하세요. 이 창은 닫아도 됩니다."); }
                finally { result.complete(new Reply(code, redirect)); }
            }
        } finally { exchange.close(); }
    }

    static Map<String, String> query(String raw) {
        if (raw == null || raw.length() > 8192) throw new IllegalArgumentException();
        Map<String, String> params = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException();
            String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (params.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
        }
        return params;
    }

    private static void page(HttpExchange exchange, int status, String message) throws IOException {
        // Only our fixed messages are rendered. Never reflect query parameters, codes or tokens.
        String html = """
                <!doctype html><html lang="ko"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Discord 계정 연동</title><style>
                body{margin:0;background:#1e1f22;color:#f2f3f5;font:16px/1.7 system-ui,sans-serif;display:grid;min-height:100vh;place-items:center}
                main{max-width:440px;margin:24px;padding:36px;background:#313338;border-radius:12px}
                h1{font-size:24px;margin:0 0 16px}p{margin:0;color:#b5bac1}
                </style><main><h1>Discord 계정 연동</h1><p>""" + message + "</p></main></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }

    private synchronized void release() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(1);
        executor.shutdownNow();
    }
    @Override public void close() {
        result.cancel(false);
        if (!consumed.get()) release();
    }
}
