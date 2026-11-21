package dev.newyear.countdown.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.net.AiPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 클라이언트 쪽 AI UI 상태. 서버가 보내는 선언형 UI 연산(JSON)을 받아 저장하고, 화면·HUD 는 이 상태만 그린다.
 * (재접속 시 서버의 sync 연산으로 전부 복구된다.)
 */
public final class AiClientState {
    private AiClientState() {}

    public static final class Line {
        public final String role, text, mood;
        public final long at;

        Line(String role, String text, String mood) {
            this.role = role;
            this.text = text;
            this.mood = mood;
            this.at = System.currentTimeMillis();
        }
    }

    public static final class Objective {
        public String id, text;
        public double progress, target;
        public boolean done, optional;
        /** 화면에서 부드럽게 따라가는 표시값. */
        public float shown;

        public float fraction() {
            return (float) (target <= 0 ? 1 : Math.min(1, progress / target));
        }
    }

    public static final class Quest {
        public String id, wishId, title, description, conditions, reward, failure, state, note, owner;
        public int priority;
        public long deadlineLocal = -1, totalMs = -1;
        public final List<Objective> objectives = new ArrayList<>();
        public long updatedAt = System.currentTimeMillis();
        public boolean expanded = true;

        public double fraction() {
            double done = 0, all = 0;
            for (Objective o : objectives) {
                if (o.optional) continue;
                all += 1;
                done += o.fraction();
            }
            return all == 0 ? 0 : done / all;
        }

        public boolean active() {
            return "ACTIVE".equals(state);
        }
    }

    public static final class Status {
        public String id, label, desc, kind, counter;
        public long deadlineLocal = -1, totalMs = -1;
        public String[] pixels;
        public Map<Character, Integer> palette = new LinkedHashMap<>();
        /** AI 가 그린 동적 텍스처 이름 (있으면 픽셀 아이콘 대신). */
        public String texture = "";
        public final long since = System.currentTimeMillis();
    }

    public static final class Interaction {
        public String id, kind, title, prompt, placeholder, wishId;
        public final List<String> options = new ArrayList<>();
        public long deadlineLocal = -1, totalMs = -1;
        public final long since = System.currentTimeMillis();
        public boolean sent;
    }

    public static final class Toast {
        public String title, text, kind;
        public long start = System.currentTimeMillis();
        public long durationMs;
    }

    public static final class Cinematic {
        public String title, subtitle, style;
        public long start = System.currentTimeMillis();
        public long durationMs;
    }

    public static final class PastWish {
        public String id, text, state, outcome;
        public long time;
    }

    public static String spiritName = "첫눈";
    public static boolean enabled = true;
    public static boolean synced;

    // 대화
    public static String wishId = "";
    public static String wishState = "";
    public static String wishText = "";
    public static final List<Line> lines = new ArrayList<>();
    public static boolean thinking;
    public static String thinkingLabel = "";
    public static long thinkingSince;
    public static final List<String> quick = new ArrayList<>();
    public static boolean expectReply;
    /** 화면이 닫혀 있는 동안 도착한 정령의 말 수. */
    public static int unread;

    public static final Map<String, Quest> quests = new LinkedHashMap<>();
    public static final Map<String, Status> statuses = new LinkedHashMap<>();
    public static final Map<String, Interaction> interactions = new LinkedHashMap<>();
    public static final List<Toast> toasts = new ArrayList<>();
    public static Cinematic cinematic;
    public static final List<PastWish> history = new ArrayList<>();

    private static int nonce = ThreadLocalRandom.current().nextInt(1, 1_000_000);

    public static void clear() {
        RuntimeHud.clear();
        synced = false;
        wishId = "";
        wishState = "";
        wishText = "";
        lines.clear();
        thinking = false;
        thinkingLabel = "";
        thinkingSince = 0;
        quick.clear();
        quests.clear();
        statuses.clear();
        interactions.clear();
        toasts.clear();
        cinematic = null;
        history.clear();
        BlockPreview.clear(null);
        AdminClient.clear();
        DynamicTextures.clear();
        unread = 0;
    }

    // ---------------------------------------------------------------- 보내기

    public static boolean sendChat(String text) {
        if (thinking || text.isBlank() || !ClientPlayNetworking.canSend(AiPackets.ChatC2S.ID)) return false;
        beginInput();
        quick.clear();
        ClientPlayNetworking.send(new AiPackets.ChatC2S(wishId == null ? "" : wishId, text, ++nonce));
        return true;
    }

    public static boolean answer(Interaction it, int index, String value) {
        if (thinking || it.sent || !ClientPlayNetworking.canSend(AiPackets.InteractC2S.ID)) return false;
        beginInput();
        it.sent = true;
        ClientPlayNetworking.send(new AiPackets.InteractC2S(it.id, index, value == null ? "" : value, ++nonce));
        return true;
    }

    private static void beginInput() {
        thinking = true; // lock immediately, including the round trip before the server responds
        thinkingSince = System.currentTimeMillis();
        thinkingLabel = "대답을 준비하는 중";
    }

    public static void action(String action, String arg) {
        if (ClientPlayNetworking.canSend(AiPackets.ActionC2S.ID)) ClientPlayNetworking.send(new AiPackets.ActionC2S(action, arg == null ? "" : arg));
    }

    public static void newWish() {
        if (thinking) return;
        action("new", "");
        wishId = "";
        wishState = "";
        wishText = "";
        lines.clear();
        quick.clear();
        expectReply = false;
    }

    // ---------------------------------------------------------------- 받기

    public static void apply(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = Json.str(o, "op");
        Minecraft mc = Minecraft.getInstance();
        switch (op) {
            case "runtime_ui" -> RuntimeHud.apply(o);
            case "cooldowns" -> CooldownHud.apply(o);
            case "sync" -> {
                String spirit = Json.str(o, "spirit");
                if (!spirit.isBlank()) spiritName = spirit;
                enabled = Json.bool(o, "enabled");
                quests.clear();
                for (JsonElement e : o.getAsJsonArray("quests")) putQuest(e.getAsJsonObject());
                statuses.clear();
                for (JsonElement e : o.getAsJsonArray("statuses")) putStatus(e.getAsJsonObject());
                if (o.has("conversation")) applyConversation(o.getAsJsonObject("conversation"));
                thinking = Json.bool(o, "thinking");
                if (thinking) {
                    if (thinkingSince == 0) thinkingSince = System.currentTimeMillis();
                    String activeWish = Json.str(o, "thinkingWishId");
                    if (!activeWish.isEmpty()) wishId = activeWish;
                }
                history.clear();
                if (o.has("wishes")) for (JsonElement e : o.getAsJsonArray("wishes")) {
                    JsonObject w = e.getAsJsonObject();
                    PastWish p = new PastWish();
                    p.id = Json.str(w, "id");
                    p.text = Json.str(w, "text");
                    p.state = Json.str(w, "state");
                    p.outcome = Json.str(w, "outcome");
                    p.time = Json.lng(w, "time", 0);
                    history.add(p);
                }
                interactions.clear();
                synced = true;
            }
            case "conversation" -> applyConversation(o.getAsJsonObject("conversation"));
            case "line" -> {
                String wid = Json.str(o, "wishId");
                String role = Json.str(o, "role");
                if (!wid.isEmpty() && !wid.equals(wishId)) {
                    // 새 소원의 대화가 시작됨
                    wishId = wid;
                    if (role.equals("spirit") && !lines.isEmpty() && !lines.get(lines.size() - 1).role.equals("player")) lines.clear();
                }
                if (o.has("state")) wishState = Json.str(o, "state");
                if (role.equals("player")) {
                    // 내가 보낸 말은 이미 화면에 있음
                    if (lines.isEmpty() || !lines.get(lines.size() - 1).text.equals(Json.str(o, "text"))) lines.add(new Line("player", Json.str(o, "text"), ""));
                    return;
                }
                lines.add(new Line("spirit", Json.str(o, "text"), Json.str(o, "mood")));
                while (lines.size() > 80) lines.remove(0);
                quick.clear();
                if (o.has("quick")) for (JsonElement e : o.getAsJsonArray("quick")) quick.add(e.getAsString());
                expectReply = Json.bool(o, "expect");
                if (!(mc.gui.screen() instanceof WishChatScreen)) {
                    unread++;
                    if (mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 1.4f);
                }
            }
            case "thinking" -> {
                boolean on = Json.bool(o, "on");
                if (on && !thinking) thinkingSince = System.currentTimeMillis();
                thinking = on;
                thinkingLabel = Json.str(o, "label");
                String wid = Json.str(o, "wishId");
                if (!wid.isEmpty()) wishId = wid;
            }
            case "open" -> {
                String wid = Json.str(o, "wishId");
                if (!wid.isEmpty()) wishId = wid;
                if (mc.gui.screen() == null) mc.gui.setScreen(new WishChatScreen());
            }
            case "wish_state" -> {
                if (Json.str(o, "wishId").equals(wishId)) {
                    wishState = Json.str(o, "state");
                    wishText = Json.str(o, "text");
                }
                for (PastWish p : history) if (p.id.equals(Json.str(o, "wishId"))) p.state = Json.str(o, "state");
            }
            case "quest" -> {
                Quest q = putQuest(o.getAsJsonObject("quest"));
                if (q != null && !q.active()) q.expanded = false;
            }
            case "status" -> putStatus(o.getAsJsonObject("status"));
            case "status_remove" -> statuses.remove(Json.str(o, "id"));
            case "interaction" -> {
                Interaction it = new Interaction();
                it.id = Json.str(o, "id");
                it.kind = Json.str(o, "kind");
                it.title = Json.str(o, "title");
                it.prompt = Json.str(o, "prompt");
                it.placeholder = Json.str(o, "placeholder");
                it.wishId = Json.str(o, "wishId");
                for (JsonElement e : o.getAsJsonArray("options")) it.options.add(e.getAsString());
                long rem = Json.lng(o, "remainingMs", -1);
                if (rem >= 0) {
                    it.deadlineLocal = System.currentTimeMillis() + rem;
                    it.totalMs = rem;
                }
                boolean fresh = !interactions.containsKey(it.id);
                interactions.put(it.id, it);
                if (fresh && mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
                // 다른 화면이 없으면 바로 대화창을 열어 응답하게 한다
                if (fresh && mc.gui.screen() == null) mc.gui.setScreen(new WishChatScreen());
            }
            case "interaction_close" -> interactions.remove(Json.str(o, "id"));
            case "admin" -> AdminClient.apply(o);
            case "block_preview" -> {
                BlockPreview.apply(o);
                Toast t = new Toast();
                t.title = "미리보기";
                t.text = (Json.str(o, "title").isEmpty() ? "블록 " : Json.str(o, "title") + " — ") + Json.lng(o, "total", 0) + "칸 (세계는 아직 그대로)";
                t.kind = "mystic";
                long secs = Json.lng(o, "seconds", 30);
                t.durationMs = Math.min(8, secs <= 0 ? 8 : secs) * 1000;
                toasts.add(t);
                while (toasts.size() > 4) toasts.remove(0);
            }
            case "block_preview_clear" -> BlockPreview.clear(Json.str(o, "id"));
            case "build_hologram" -> BuildHologram.receive(o);
            case "build_site_result" -> BuildHologram.result(o);
            case "texture" -> DynamicTextures.apply(o);
            case "texture_remove" -> DynamicTextures.remove(Json.str(o, "id"));
            case "notify" -> {
                Toast t = new Toast();
                t.title = Json.str(o, "title");
                t.text = Json.str(o, "text");
                t.kind = Json.str(o, "kind");
                t.durationMs = Json.lng(o, "seconds", 5) * 1000;
                toasts.add(t);
                while (toasts.size() > 4) toasts.remove(0);
            }
            case "cinematic" -> {
                Cinematic c = new Cinematic();
                c.title = Json.str(o, "title");
                c.subtitle = Json.str(o, "subtitle");
                c.style = Json.str(o, "style");
                c.durationMs = Json.lng(o, "seconds", 4) * 1000;
                cinematic = c;
                if (mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6f, 0.7f);
            }
            default -> { }
        }
    }

    private static void applyConversation(JsonObject c) {
        String wid = Json.str(c, "wishId");
        wishId = wid;
        wishState = Json.str(c, "state");
        wishText = Json.str(c, "text");
        if (Json.bool(c, "thinking")) {
            if (!thinking) thinkingSince = System.currentTimeMillis();
            thinking = true;
        }
        lines.clear();
        JsonArray arr = c.getAsJsonArray("lines");
        if (arr != null) for (JsonElement e : arr) {
            JsonObject l = e.getAsJsonObject();
            lines.add(new Line(Json.str(l, "role"), Json.str(l, "text"), ""));
        }
    }

    private static Quest putQuest(JsonObject j) {
        if (j == null) return null;
        String id = Json.str(j, "id");
        Quest q = quests.computeIfAbsent(id, k -> new Quest());
        q.id = id;
        q.wishId = Json.str(j, "wishId");
        q.title = Json.str(j, "title");
        q.description = Json.str(j, "description");
        q.conditions = Json.str(j, "conditions");
        q.reward = Json.str(j, "reward");
        q.failure = Json.str(j, "failure");
        q.state = Json.str(j, "state");
        q.note = Json.str(j, "note");
        q.owner = Json.str(j, "owner");
        q.priority = (int) Json.lng(j, "priority", 5);
        long rem = Json.lng(j, "remainingMs", -1);
        q.deadlineLocal = rem < 0 ? -1 : System.currentTimeMillis() + rem;
        q.totalMs = Json.lng(j, "totalMs", -1);
        Map<String, Float> shown = new LinkedHashMap<>();
        for (Objective o : q.objectives) shown.put(o.id, o.shown);
        q.objectives.clear();
        for (JsonElement e : j.getAsJsonArray("objectives")) {
            JsonObject oj = e.getAsJsonObject();
            Objective ob = new Objective();
            ob.id = Json.str(oj, "id");
            ob.text = Json.str(oj, "text");
            ob.progress = Json.dbl(oj, "progress");
            ob.target = Json.dbl(oj, "target");
            ob.done = Json.bool(oj, "done");
            ob.optional = Json.bool(oj, "optional");
            ob.shown = shown.getOrDefault(ob.id, 0f);
            q.objectives.add(ob);
        }
        q.updatedAt = System.currentTimeMillis();
        return q;
    }

    private static void putStatus(JsonObject j) {
        Status st = new Status();
        st.id = Json.str(j, "id");
        st.label = Json.str(j, "label");
        st.desc = Json.str(j, "desc");
        st.kind = Json.str(j, "kind");
        st.counter = Json.str(j, "counter");
        st.texture = Json.str(j, "texture");
        long rem = Json.lng(j, "remainingMs", -1);
        st.deadlineLocal = rem < 0 ? -1 : System.currentTimeMillis() + rem;
        st.totalMs = Json.lng(j, "totalMs", -1);
        if (j.has("icon")) {
            JsonObject icon = j.getAsJsonObject("icon");
            JsonArray rows = icon.getAsJsonArray("pixels");
            st.pixels = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) st.pixels[i] = rows.get(i).getAsString();
            for (String pair : Json.str(icon, "palette").split(";")) {
                if (pair.length() < 3 || pair.charAt(1) != '=') continue;
                try {
                    st.palette.put(pair.charAt(0), 0xFF000000 | Integer.parseInt(pair.substring(2), 16));
                } catch (NumberFormatException ignored) { }
            }
        }
        statuses.put(st.id, st);
    }

    /** 남은 시간 (ms). 없으면 -1. */
    public static long remaining(long deadlineLocal) {
        return deadlineLocal < 0 ? -1 : Math.max(0, deadlineLocal - System.currentTimeMillis());
    }

    public static Interaction firstInteraction() {
        Interaction first = null;
        for (Interaction it : interactions.values()) if (!it.sent) { first = it; break; }
        return first;
    }

    public static List<Quest> sortedQuests(boolean activeOnly) {
        List<Quest> list = new ArrayList<>();
        for (Quest q : quests.values()) if (!activeOnly || q.active() || System.currentTimeMillis() - q.updatedAt < 8000) list.add(q);
        list.sort((a, b) -> {
            if (a.active() != b.active()) return a.active() ? -1 : 1;
            if (a.priority != b.priority) return b.priority - a.priority;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        return list;
    }
}
