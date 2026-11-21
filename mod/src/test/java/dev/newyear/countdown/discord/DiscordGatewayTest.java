package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiscordGatewayTest {
    private static final String HELLO = "{\"op\":10,\"d\":{\"heartbeat_interval\":600000}}";
    private static final String READY = "{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"sess\",\"resume_gateway_url\":\"wss://resume.example/\",\"user\":{\"username\":\"bot\"}}}";
    private static final String FRESH_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** 가짜 소켓: 열린 연결과 보낸 글을 큐로 내준다. */
    private static final class FakeTransport implements DiscordGateway.Transport {
        final BlockingQueue<Conn> opened = new LinkedBlockingQueue<>();

        @Override public DiscordGateway.Connection connect(String url, DiscordGateway.Listener listener) {
            Conn c = new Conn(url, listener);
            opened.add(c);
            return c;
        }
    }

    private static final class Conn implements DiscordGateway.Connection {
        final String url;
        final DiscordGateway.Listener listener;
        final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        volatile boolean aborted;

        Conn(String url, DiscordGateway.Listener listener) {
            this.url = url;
            this.listener = listener;
        }

        @Override public void send(String text) { sent.add(text); }
        @Override public void abort() { aborted = true; }
        @Override public void close() { }

        JsonObject nextSent() throws InterruptedException {
            String s = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(s, "gateway sent nothing");
            return JsonParser.parseString(s).getAsJsonObject();
        }
    }

    private static final class Recorder implements DiscordGateway.Handler {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override public void onDispatch(String type, JsonObject data) { events.add("dispatch:" + type); }
        @Override public void onStatus(String status) { }
        @Override public void onFatal(String reason) { events.add("fatal"); }
        @Override public void onMessageContentUnavailable() { events.add("no-content"); }

        String next() throws InterruptedException {
            String e = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(e, "no gateway event");
            return e;
        }
    }

    private final FakeTransport transport = new FakeTransport();
    private final Recorder recorder = new Recorder();
    private DiscordGateway gateway;

    @AfterEach void stop() {
        if (gateway != null) gateway.stop();
    }

    private Conn nextConnection() throws InterruptedException {
        Conn c = transport.opened.poll(10, TimeUnit.SECONDS);
        assertNotNull(c, "gateway did not connect");
        return c;
    }

    private void start(int intents) {
        gateway = new DiscordGateway("secret-token", intents, recorder, transport);
        gateway.start();
    }

    @Test void identifiesAfterHelloAndDispatchesEvents() throws Exception {
        start(DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT);
        Conn c = nextConnection();
        assertEquals(FRESH_URL, c.url);
        c.listener.onText(HELLO);
        JsonObject identify = c.nextSent();
        assertEquals(2, identify.get("op").getAsInt());
        assertEquals("secret-token", identify.getAsJsonObject("d").get("token").getAsString());
        assertEquals((1 << 9) | (1 << 15), identify.getAsJsonObject("d").get("intents").getAsInt());

        c.listener.onText(READY);
        assertEquals("dispatch:READY", recorder.next());
        c.listener.onText("{\"op\":0,\"s\":2,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"hi\"}}");
        assertEquals("dispatch:MESSAGE_CREATE", recorder.next());
        c.listener.onText("not json");
        c.listener.onText("{\"op\":1}");
        JsonObject beat = c.nextSent();
        assertEquals(1, beat.get("op").getAsInt());
        assertEquals(2, beat.get("d").getAsInt());
    }

    @Test void resumesOnTheResumeUrlAfterAReconnectRequest() throws Exception {
        start(0);
        Conn first = nextConnection();
        first.listener.onText(HELLO);
        first.nextSent();
        first.listener.onText(READY);
        recorder.next();
        first.listener.onText("{\"op\":7}");

        Conn second = nextConnection();
        assertTrue(first.aborted);
        assertEquals("wss://resume.example/?v=10&encoding=json", second.url);
        second.listener.onText(HELLO);
        JsonObject resume = second.nextSent();
        assertEquals(6, resume.get("op").getAsInt());
        assertEquals("sess", resume.getAsJsonObject("d").get("session_id").getAsString());
        assertEquals(1, resume.getAsJsonObject("d").get("seq").getAsInt());
        // 옛 연결에서 늦게 온 글은 무시한다
        first.listener.onText("{\"op\":0,\"s\":9,\"t\":\"MESSAGE_CREATE\",\"d\":{}}");
        second.listener.onText("{\"op\":0,\"s\":3,\"t\":\"RESUMED\",\"d\":{}}");
        assertEquals("dispatch:RESUMED", recorder.next());
    }

    @Test void dropsMessageContentIntentWhenDiscordDisallowsIt() throws Exception {
        start(DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT);
        Conn first = nextConnection();
        first.listener.onText(HELLO);
        first.nextSent();
        first.listener.onClosed(4014, "Disallowed intent(s).");
        assertEquals("no-content", recorder.next());

        Conn second = nextConnection();
        second.listener.onText(HELLO);
        JsonObject identify = second.nextSent();
        assertEquals(2, identify.get("op").getAsInt());
        assertEquals(1 << 9, identify.getAsJsonObject("d").get("intents").getAsInt());
    }

    @Test void badTokenIsFatalAndDoesNotReconnect() throws Exception {
        start(0);
        Conn c = nextConnection();
        c.listener.onText(HELLO);
        c.nextSent();
        c.listener.onClosed(4004, "Authentication failed.");
        assertEquals("fatal", recorder.next());
        assertNull(transport.opened.poll(1500, TimeUnit.MILLISECONDS));
    }

    @Test void invalidSessionStartsAFreshIdentify() throws Exception {
        start(0);
        Conn first = nextConnection();
        first.listener.onText(HELLO);
        first.nextSent();
        first.listener.onText(READY);
        recorder.next();
        first.listener.onText("{\"op\":9,\"d\":false}");

        Conn second = nextConnection();
        assertEquals(FRESH_URL, second.url);
        second.listener.onText(HELLO);
        assertEquals(2, second.nextSent().get("op").getAsInt());
    }

    @Test void ordinaryDisconnectRetriesAndKeepsTheSession() throws Exception {
        start(0);
        Conn first = nextConnection();
        first.listener.onText(HELLO);
        first.nextSent();
        first.listener.onText(READY);
        recorder.next();
        first.listener.onClosed(1006, "");
        first.listener.onClosed(-1, "duplicate close callback");

        Conn second = nextConnection();
        assertEquals("wss://resume.example/?v=10&encoding=json", second.url);
        second.listener.onText(HELLO);
        assertEquals(6, second.nextSent().get("op").getAsInt());
        assertNull(transport.opened.poll(300, TimeUnit.MILLISECONDS), "a duplicate close must not open a second connection");
    }
}
