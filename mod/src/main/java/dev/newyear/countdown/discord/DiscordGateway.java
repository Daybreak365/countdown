package dev.newyear.countdown.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 디스코드 게이트웨이(웹소켓) 연결. 로그인, 하트비트, 끊겼을 때 이어 받기(resume)와 재접속을 맡고,
 * 받은 이벤트는 {@link Handler} 로 넘긴다. 모든 상태는 전용 스레드 하나에서만 바뀐다.
 */
final class DiscordGateway {
    /** 시스템 속성은 로컬 가짜 서버로 시험할 때만 쓴다. */
    static final String DEFAULT_URL = System.getProperty("newyearcountdown.discord.gateway", "wss://gateway.discord.gg");
    static final int INTENT_GUILD_MESSAGES = 1 << 9;
    /** 메시지 본문을 읽는 권한. 개발자 포털에서 따로 켜야 하는 특권 인텐트다. */
    static final int INTENT_MESSAGE_CONTENT = 1 << 15;
    private static final long MAX_BACKOFF_MS = 60_000;

    interface Handler {
        /** 게이트웨이 이벤트 (READY, MESSAGE_CREATE, INTERACTION_CREATE ...). 게이트웨이 스레드에서 불린다. */
        void onDispatch(String type, JsonObject data);

        /** 연결 상태가 바뀜. 사람이 읽을 설명. */
        void onStatus(String status);

        /** 다시 시도해도 소용없는 오류 (잘못된 토큰 등). 이후 재접속하지 않는다. */
        void onFatal(String reason);

        /** Message Content Intent 가 꺼져 있어 본문 없이 접속함. */
        void onMessageContentUnavailable();
    }

    /** 실제 소켓. 테스트에서는 가짜로 바꾼다. */
    interface Transport {
        Connection connect(String url, Listener listener);
    }

    interface Connection {
        void send(String text);

        /** 세션을 남겨 둔 채 끊는다(이어 받기 가능). */
        void abort();

        /** 정상 종료. 세션도 끝난다. */
        void close();
    }

    interface Listener {
        void onText(String message);

        /** code 는 웹소켓 종료 코드, 오류로 끊겼으면 -1. */
        void onClosed(int code, String reason);
    }

    private final String token;
    private final Handler handler;
    private final Transport transport;
    private final ScheduledExecutorService exec;

    private int intents;
    private boolean stopped;
    /** 연결을 새로 만들 때마다 올린다. 옛 연결에서 늦게 온 콜백을 버리는 데 쓴다. */
    private int generation;
    private Connection connection;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> reconnect;
    private boolean acked = true;
    private JsonElement sequence = JsonNull.INSTANCE;
    private String sessionId;
    private String resumeUrl;
    private int failures;

    DiscordGateway(String token, int intents, Handler handler) {
        this(token, intents, handler, new JdkTransport());
    }

    DiscordGateway(String token, int intents, Handler handler, Transport transport) {
        this.token = token;
        this.intents = intents;
        this.handler = handler;
        this.transport = transport;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NewYear-Discord-Gateway");
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        run(this::connect);
    }

    void stop() {
        run(() -> {
            stopped = true;
            generation++;
            cancelTimers();
            if (connection != null) connection.close();
            connection = null;
        });
        exec.shutdown();
    }

    private void run(Runnable task) {
        try {
            exec.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    handler.onStatus("내부 오류: " + e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 이미 멈춘 뒤에 온 콜백
        }
    }

    private void connect() {
        if (stopped) return;
        reconnect = null;
        int gen = ++generation;
        boolean resuming = sessionId != null && resumeUrl != null;
        String url = (resuming ? resumeUrl : DEFAULT_URL) + "/?v=10&encoding=json";
        handler.onStatus(resuming ? "다시 연결하는 중" : "연결하는 중");
        acked = true;
        try {
            connection = transport.connect(url, new Listener() {
                @Override public void onText(String message) {
                    run(() -> { if (gen == generation) receive(message); });
                }

                @Override public void onClosed(int code, String reason) {
                    run(() -> { if (gen == generation) closed(code, reason); });
                }
            });
        } catch (RuntimeException e) {
            closed(-1, e.toString());
        }
    }

    private void receive(String message) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (!payload.has("op")) return;
        switch (payload.get("op").getAsInt()) {
            case 0 -> dispatch(payload);
            case 1 -> sendHeartbeat();
            case 7 -> dropAndReconnect(true, 0);
            case 9 -> {
                // 세션이 무효가 됨. d 가 true 일 때만 이어 받을 수 있다. 디스코드 권고대로 1~5초 쉰다.
                JsonElement d = payload.get("d");
                boolean resumable = d != null && d.isJsonPrimitive() && d.getAsBoolean();
                dropAndReconnect(resumable, 1000 + ThreadLocalRandom.current().nextLong(4000));
            }
            case 10 -> hello(DiscordText.obj(payload, "d"));
            case 11 -> acked = true;
            default -> { }
        }
    }

    private void hello(JsonObject d) {
        long interval = d != null && d.has("heartbeat_interval") ? d.get("heartbeat_interval").getAsLong() : 41_250;
        interval = Math.max(1000, interval);
        if (heartbeat != null) heartbeat.cancel(false);
        int gen = generation;
        long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
        heartbeat = exec.scheduleAtFixedRate(() -> { if (gen == generation) beat(); }, first, interval, TimeUnit.MILLISECONDS);

        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        JsonObject out = new JsonObject();
        if (sessionId != null) {
            data.addProperty("session_id", sessionId);
            data.add("seq", sequence);
            out.addProperty("op", 6);
        } else {
            data.addProperty("intents", intents);
            JsonObject props = new JsonObject();
            props.addProperty("os", "minecraft");
            props.addProperty("browser", "fabric-mod");
            props.addProperty("device", "fabric-mod");
            data.add("properties", props);
            out.addProperty("op", 2);
        }
        out.add("d", data);
        send(out);
    }

    private void beat() {
        if (!acked) {
            // 지난 하트비트에 답이 없으면 죽은 연결이다
            dropAndReconnect(true, 0);
            return;
        }
        acked = false;
        sendHeartbeat();
    }

    private void sendHeartbeat() {
        JsonObject out = new JsonObject();
        out.addProperty("op", 1);
        out.add("d", sequence);
        send(out);
    }

    private void dispatch(JsonObject payload) {
        JsonElement s = payload.get("s");
        if (s != null && !s.isJsonNull()) sequence = s;
        String type = DiscordText.str(payload, "t");
        JsonObject d = DiscordText.obj(payload, "d");
        if (type == null || d == null) return;
        if (type.equals("READY")) {
            sessionId = DiscordText.str(d, "session_id");
            String url = DiscordText.str(d, "resume_gateway_url");
            resumeUrl = url != null && url.startsWith("wss://") ? stripTrailingSlash(url) : null;
            failures = 0;
            handler.onStatus("연결됨");
        } else if (type.equals("RESUMED")) {
            failures = 0;
            handler.onStatus("연결됨");
        }
        try {
            handler.onDispatch(type, d);
        } catch (RuntimeException e) {
            handler.onStatus("이벤트 처리 오류 (" + type + "): " + e);
        }
    }

    private void closed(int code, String reason) {
        cancelTimers();
        connection = null;
        generation++;
        if (stopped) return;
        switch (code) {
            case 4004 -> fatal("봇 토큰이 올바르지 않습니다. 디스코드 개발자 포털에서 토큰을 다시 확인하세요.");
            case 4010, 4011, 4012, 4013 -> fatal("디스코드가 연결을 거부했습니다 (코드 " + code + ").");
            case 4014 -> {
                if ((intents & INTENT_MESSAGE_CONTENT) != 0) {
                    intents &= ~INTENT_MESSAGE_CONTENT;
                    clearSession();
                    handler.onMessageContentUnavailable();
                    schedule(0);
                } else {
                    fatal("디스코드가 요청한 인텐트를 허용하지 않습니다 (코드 4014).");
                }
            }
            case 4007, 4009 -> {
                clearSession();
                retry(code, reason);
            }
            default -> retry(code, reason);
        }
    }

    private void retry(int code, String reason) {
        failures++;
        long backoff = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(6, failures - 1));
        backoff += ThreadLocalRandom.current().nextLong(1000);
        handler.onStatus("연결이 끊겼습니다" + (code > 0 ? " (코드 " + code + ")" : "") + ". " + (backoff / 1000 + 1) + "초 안에 다시 시도합니다."
                + (code <= 0 && reason != null && !reason.isBlank() ? " 원인: " + reason : ""));
        schedule(backoff);
    }

    private void dropAndReconnect(boolean keepSession, long delayMs) {
        cancelTimers();
        generation++;
        if (connection != null) connection.abort();
        connection = null;
        if (!keepSession) clearSession();
        if (!stopped) schedule(delayMs);
    }

    private void schedule(long delayMs) {
        if (reconnect != null) reconnect.cancel(false);
        reconnect = exec.schedule(() -> {
            try {
                connect();
            } catch (RuntimeException e) {
                handler.onStatus("내부 오류: " + e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void fatal(String reason) {
        stopped = true;
        clearSession();
        handler.onFatal(reason);
    }

    private void clearSession() {
        sessionId = null;
        resumeUrl = null;
        sequence = JsonNull.INSTANCE;
    }

    private void cancelTimers() {
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = null;
        if (reconnect != null) reconnect.cancel(false);
        reconnect = null;
    }

    private void send(JsonObject payload) {
        if (connection != null) connection.send(payload.toString());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** java.net.http 웹소켓. 보내기는 한 번에 하나씩만 허용되므로 줄을 세운다. */
    private static final class JdkTransport implements Transport {
        private static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        @Override public Connection connect(String url, Listener listener) {
            CompletableFuture<WebSocket> socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .header("User-Agent", DiscordRest.USER_AGENT)
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();

                        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (buffer.length() > MAX_MESSAGE_CHARS) {
                                buffer.setLength(0);
                                ws.abort();
                                listener.onClosed(-1, "message too large");
                                return null;
                            }
                            if (last) {
                                String message = buffer.toString();
                                buffer.setLength(0);
                                listener.onText(message);
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            listener.onClosed(statusCode, reason);
                            return null;
                        }

                        @Override public void onError(WebSocket ws, Throwable error) {
                            listener.onClosed(-1, String.valueOf(error));
                        }
                    });
            socket.whenComplete((ws, error) -> {
                if (error != null) listener.onClosed(-1, String.valueOf(error.getCause() != null ? error.getCause() : error));
            });
            return new Connection() {
                private CompletableFuture<?> tail = socket;

                @Override public synchronized void send(String text) {
                    tail = tail.handle((v, e) -> null).thenCompose(v -> socket.thenCompose(ws -> ws.sendText(text, true))).exceptionally(e -> null);
                }

                @Override public void abort() {
                    socket.whenComplete((ws, e) -> { if (ws != null) ws.abort(); });
                }

                @Override public void close() {
                    socket.whenComplete((ws, e) -> {
                        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(x -> null);
                    });
                }
            };
        }
    }
}
