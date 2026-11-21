package dev.newyear.countdown.discord;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;

/** Discord often attaches GIF/link embeds in a later partial MESSAGE_UPDATE. */
final class DiscordMessageHistory {
    private final LinkedHashMap<String, JsonObject> messages = new LinkedHashMap<>();
    synchronized JsonObject accept(JsonObject event, boolean update) {
        String id = DiscordText.str(event, "id");
        if (!AvatarRef.isUserId(id)) return null;
        JsonObject previous = messages.get(id);
        if (update && previous == null) return null;
        JsonObject next = previous == null ? new JsonObject() : previous.deepCopy();
        for (String field : new String[]{"id", "channel_id", "type", "author", "member", "content", "mentions", "attachments", "embeds", "sticker_items", "webhook_id"})
            if (event.has(field)) next.add(field, event.get(field).deepCopy());
        if (next.toString().length() > 65536 || next.equals(previous)) return null;
        messages.put(id, next);
        while (messages.size() > 128) messages.remove(messages.keySet().iterator().next());
        return next;
    }
    synchronized JsonObject remove(String id) { return messages.remove(id); }
}
