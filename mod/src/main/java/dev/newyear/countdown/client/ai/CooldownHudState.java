package dev.newyear.countdown.client.ai;

import com.google.gson.*;
import java.util.*;

/** Pure presentation state. Prediction never declares an ability ready without server acknowledgement. */
public final class CooldownHudState {
    public record Entry(String script, String id, String label, String texture, int remaining, int duration, int readyTicks) {}
    private final Map<String,List<Entry>> scripts = new LinkedHashMap<>();
    public void clear() { scripts.clear(); }
    public void apply(JsonObject packet) {
        String script = packet.get("script").getAsString();
        var entries = new ArrayList<Entry>();
        for (JsonElement value : packet.getAsJsonArray("entries")) {
            if (entries.size() == 16) break;
            JsonObject e = value.getAsJsonObject();
            int duration = Math.clamp(e.get("duration").getAsInt(),0,1728000);
            int remaining = Math.clamp(e.get("remaining").getAsInt(),0,duration);
            int ready = Math.clamp(e.get("readyTicks").getAsInt(),0,40);
            if (remaining > 0 || ready > 0) entries.add(new Entry(script,e.get("id").getAsString(),
                    e.get("label").getAsString(),e.get("texture").getAsString(),remaining,duration,ready));
        }
        if (entries.isEmpty()) scripts.remove(script);
        else if (scripts.size() < 64 || scripts.containsKey(script)) scripts.put(script,entries);
    }
    public void tick() {
        for (var script : scripts.entrySet()) script.setValue(script.getValue().stream().map(e -> new Entry(
                e.script,e.id,e.label,e.texture,e.remaining > 0 ? Math.max(1,e.remaining - 1) : 0,e.duration,
                e.remaining > 0 ? 0 : Math.max(0,e.readyTicks - 1))).filter(e -> e.remaining > 0 || e.readyTicks > 0).toList());
        scripts.values().removeIf(List::isEmpty);
    }
    public List<Entry> entries() {
        return scripts.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparingInt((Entry e) -> e.remaining == 0 ? 1 : 0).thenComparingInt(Entry::remaining)).toList();
    }
    public record Layout(int columns, int cardWidth, int shown, int rows, int left, int bottom) {}
    public static Layout layout(int width, int height, int count, boolean vanillaMessage) {
        int columns = Math.max(1,Math.min(3,(width - 16) / 108));
        int shown = Math.min(count,columns * 2), rows = (shown + columns - 1) / columns;
        columns = Math.max(1,Math.min(columns,shown));
        int cardWidth = Math.max(48,Math.min(108,(width - 16) / columns));
        return new Layout(columns,cardWidth,shown,rows,(width - columns * cardWidth + 4) / 2,height - 60 - (vanillaMessage ? 22 : 0));
    }
}
