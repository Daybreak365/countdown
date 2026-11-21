package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
    private static final String USER = "123456789012345678", BOT = "223456789012345678", HOOK = "323456789012345678", CHANNEL = "423456789012345678";
    private static final String TOKEN = "test-only-webhook-token";

    @Test void eachMessageUsesLinkedDiscordIdentityWithoutFormattingInjectionOrMentions() {
        DiscordLinks.Link link = new DiscordLinks.Link();
        link.discordId = USER; link.username = "winter"; link.displayName = "겨울";
        link.avatar = "0123456789abcdef0123456789abcdef";
        JsonObject first = DiscordWebhook.message(link, "MinecraftNick", "@everyone 안녕");
        assertEquals("겨울 · @winter", first.get("username").getAsString());
        assertEquals(link.avatarRef().url(128), first.get("avatar_url").getAsString());
        assertEquals("@everyone 안녕", first.get("content").getAsString());
        assertTrue(first.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        JsonObject next = DiscordWebhook.message(null, "다른플레이어", "next");
        assertEquals("다른플레이어", next.get("username").getAsString());
        assertNotEquals(first.get("avatar_url"), next.get("avatar_url"));
        assertEquals(80, DiscordWebhook.message(null, "🌟".repeat(100), "hi").get("username").getAsString().codePointCount(0, 160));
    }

    @Test void reusesOwnedWebhookAndKeepsPerPlayerMessagesSeparate() throws Exception {
        List<String> routes = new ArrayList<>(), auth = new ArrayList<>(); List<JsonObject> sent = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String route = e.getRequestMethod() + " " + e.getRequestURI(); routes.add(route); auth.add(e.getRequestHeaders().getFirst("Authorization"));
            String reply;
            if (route.equals("GET /users/@me")) reply = "{\"id\":\"" + BOT + "\"}";
            else if (route.equals("GET /channels/" + CHANNEL + "/webhooks")) reply = "[" + hook(BOT) + "]";
            else { sent.add(JsonParser.parseString(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()); reply = "{\"id\":\"message\"}"; }
            byte[] data = reply.getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.start();
        try {
            var relay = new DiscordWebhook(new DiscordRest("fake-bot-token", "http://127.0.0.1:" + server.getAddress().getPort()));
            relay.send(CHANNEL, DiscordWebhook.message(null, "첫째", "one"));
            relay.send(CHANNEL, DiscordWebhook.message(null, "둘째", "two"));
            assertEquals(4, routes.size());
            assertEquals("POST /webhooks/" + HOOK + "/" + TOKEN + "?wait=true", routes.get(2));
            assertEquals("Bot fake-bot-token", auth.get(0)); assertNull(auth.get(2));
            assertEquals("첫째", sent.get(0).get("username").getAsString());
            assertEquals("둘째", sent.get(1).get("username").getAsString());
        } finally { server.stop(0); }
    }

    @Test void doesNotReuseAnotherOwnersWebhookAndCreatesOnlyOnce() throws Exception {
        AtomicInteger creates = new AtomicInteger(), executions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath(); String reply;
            if (path.equals("/users/@me")) reply = "{\"id\":\"" + BOT + "\"}";
            else if (path.endsWith("/webhooks") && e.getRequestMethod().equals("GET")) reply = "[" + hook(USER) + "]";
            else if (path.endsWith("/webhooks")) { creates.incrementAndGet(); reply = hook(BOT); }
            else { executions.incrementAndGet(); reply = "{}"; }
            byte[] data = reply.getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.start();
        try {
            var relay = new DiscordWebhook(new DiscordRest("fake", "http://127.0.0.1:" + server.getAddress().getPort()));
            relay.send(CHANNEL, DiscordWebhook.message(null, "one", "a")); relay.send(CHANNEL, DiscordWebhook.message(null, "two", "b"));
            assertEquals(1, creates.get()); assertEquals(2, executions.get());
        } finally { server.stop(0); }
    }

    @Test void permissionFailureDoesNotHammerWebhookCreation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> { calls.incrementAndGet(); byte[] data = "{}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(403, data.length); e.getResponseBody().write(data); e.close(); });
        server.start();
        try {
            var relay = new DiscordWebhook(new DiscordRest("fake", "http://127.0.0.1:" + server.getAddress().getPort()));
            for (int i = 0; i < 3; i++) assertThrows(IOException.class, () -> relay.send(CHANNEL, DiscordWebhook.message(null, "name", "a")));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    private static String hook(String owner) {
        return "{\"id\":\"" + HOOK + "\",\"name\":\"Minecraft Chat\",\"token\":\"" + TOKEN + "\",\"user\":{\"id\":\"" + owner + "\"}}";
    }
}
