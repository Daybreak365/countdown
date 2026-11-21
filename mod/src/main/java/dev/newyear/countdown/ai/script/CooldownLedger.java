package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Server-tick cooldowns, saved with the script. No wall-clock expiry or client authority. */
public final class CooldownLedger {
    public static final int LIMIT = 16, READY_TICKS = 40;
    private CooldownLedger() {}

    private static JsonObject entries(ScriptRecord record) {
        if (record.runtime == null) record.runtime = new JsonObject();
        if (!record.runtime.has("cooldowns")) record.runtime.add("cooldowns", new JsonObject());
        return record.runtime.getAsJsonObject("cooldowns");
    }

    private static String key(String player, String id) {
        if (player == null || !id.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid cooldown id");
        return player + ":" + id;
    }

    public static int ticks(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.05 || seconds > 86400)
            throw new IllegalArgumentException("Cooldown duration must be 0.05..86400 seconds");
        return (int) Math.ceil(seconds * 20);
    }

    public static JsonObject register(ScriptRecord record, String player, String id, String label, String texture) {
        String key = key(player, id);
        JsonObject all = entries(record), entry = all.getAsJsonObject(key);
        if (entry == null) {
            if (all.size() >= LIMIT) throw new IllegalArgumentException("At most 16 cooldowns per script; clear unused ids");
            entry = new JsonObject();
            entry.addProperty("player", player); entry.addProperty("id", id);
            entry.addProperty("remaining", 0); entry.addProperty("duration", 0); entry.addProperty("readyTicks", 0);
            all.add(key, entry);
        }
        entry.addProperty("label", label == null || label.isBlank() ? id : label.substring(0, Math.min(40, label.length())));
        entry.addProperty("texture", texture == null ? "" : texture);
        return entry;
    }

    public static boolean tryUse(ScriptRecord record, String player, String id, double seconds, String label, String texture) {
        int duration = ticks(seconds);
        if (remaining(record, player, id) > 0) return false;
        JsonObject entry = entries(record).getAsJsonObject(key(player, id));
        if (entry == null) entry = register(record, player, id, label, texture);
        else if (label != null || texture != null) register(record, player, id,
                label == null ? entry.get("label").getAsString() : label,
                texture == null ? entry.get("texture").getAsString() : texture);
        entry.addProperty("duration", duration); entry.addProperty("remaining", duration); entry.addProperty("readyTicks", 0);
        return true;
    }

    public static int remaining(ScriptRecord record, String player, String id) {
        JsonObject entry = entries(record).getAsJsonObject(key(player, id));
        return entry == null ? 0 : Math.max(0, entry.get("remaining").getAsInt());
    }

    public static void clear(ScriptRecord record, String player, String id) { entries(record).remove(key(player, id)); }

    /** Returns recipients needing a refresh, including expiry and removal transitions. */
    public static Set<String> advance(ScriptRecord record, boolean heartbeat) {
        return advance(record,1,heartbeat);
    }

    /** Batched time advance also used by dry-run timer simulation. */
    public static Set<String> advance(ScriptRecord record, int elapsed, boolean heartbeat) {
        if (elapsed < 0) throw new IllegalArgumentException("Cannot move cooldown time backwards");
        Set<String> dirty = new LinkedHashSet<>();
        if (elapsed == 0) return dirty;
        for (var value : entries(record).asMap().values()) {
            JsonObject entry = value.getAsJsonObject();
            int remaining = entry.get("remaining").getAsInt(), ready = entry.get("readyTicks").getAsInt();
            if (remaining > 0) {
                int next = Math.max(0,remaining-elapsed);
                entry.addProperty("remaining", next);
                if (next == 0) entry.addProperty("readyTicks", Math.max(0,READY_TICKS-Math.max(0,elapsed-remaining)));
                if (heartbeat || next == 0) dirty.add(entry.get("player").getAsString());
            } else if (ready > 0) {
                int next = Math.max(0,ready-elapsed);
                entry.addProperty("readyTicks", next);
                if (heartbeat || next == 0) dirty.add(entry.get("player").getAsString());
            }
        }
        return dirty;
    }

    public static com.google.gson.JsonArray visible(ScriptRecord record, String player) {
        var out = new com.google.gson.JsonArray();
        if (!record.state.live()) return out;
        for (var value : entries(record).asMap().values()) {
            JsonObject entry = value.getAsJsonObject();
            if (entry.get("player").getAsString().equals(player)
                    && (entry.get("remaining").getAsInt() > 0 || entry.get("readyTicks").getAsInt() > 0)) out.add(entry.deepCopy());
        }
        return out;
    }

    public static boolean active(ScriptRecord record) {
        return entries(record).asMap().values().stream().anyMatch(e -> e.getAsJsonObject().get("remaining").getAsInt() > 0
                || e.getAsJsonObject().get("readyTicks").getAsInt() > 0);
    }
}
