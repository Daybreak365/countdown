package dev.newyear.countdown.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/** One bot-owned webhook per channel; never creates a webhook per player or per message. */
final class DiscordWebhook {
    static final String NAME = "Minecraft Chat";
    private final DiscordRest rest;
    private String botId;
    private String channel;
    private String path;
    private long retryAt;

    DiscordWebhook(DiscordRest rest) { this.rest = rest; }

    void send(String channelId, JsonObject body) throws IOException, InterruptedException {
        if (System.currentTimeMillis() < retryAt) throw new IOException("웹훅 재시도 대기 중");
        try {
            if (!channelId.equals(channel) || path == null) resolve(channelId);
            rest.postUnauthenticated(path + "?wait=true", body);
        } catch (IOException e) {
            // Deleted webhook/changed permissions: re-resolve next time, without creating on every message.
            path = null;
            retryAt = System.currentTimeMillis() + 60_000;
            throw e;
        }
    }

    private void resolve(String channelId) throws IOException, InterruptedException {
        if (botId == null) botId = DiscordText.str(rest.get("/users/@me").getAsJsonObject(), "id");
        JsonElement found = rest.get("/channels/" + channelId + "/webhooks");
        if (found.isJsonArray()) for (JsonElement e : found.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject hook = e.getAsJsonObject();
            JsonObject owner = hook.has("user") && hook.get("user").isJsonObject() ? hook.getAsJsonObject("user") : null;
            if (owner != null && botId != null && botId.equals(DiscordText.str(owner, "id"))
                    && NAME.equals(DiscordText.str(hook, "name")) && setPath(channelId, hook)) return;
        }
        JsonObject create = new JsonObject();
        create.addProperty("name", NAME);
        JsonElement created = rest.post("/channels/" + channelId + "/webhooks", create);
        if (!created.isJsonObject() || !setPath(channelId, created.getAsJsonObject()))
            throw new IOException("웹훅 응답에 ID 또는 토큰이 없습니다");
    }

    private boolean setPath(String channelId, JsonObject hook) {
        String id = DiscordText.str(hook, "id"), token = DiscordText.str(hook, "token");
        if (!AvatarRef.isUserId(id) || token == null || !token.matches("[A-Za-z0-9._-]{16,256}")) return false;
        channel = channelId;
        path = "/webhooks/" + id + "/" + token;
        return true;
    }

    static JsonObject message(DiscordLinks.Link link, String playerName, String content) {
        String name = playerName;
        AvatarRef avatar = null;
        if (link != null) {
            name = link.shownName();
            if (link.username != null && !link.username.isBlank() && !link.username.equals(name)) name += " · @" + link.username;
            avatar = link.avatarRef();
        }
        name = name == null ? "Minecraft" : name.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (name.isBlank()) name = "Minecraft";
        if (name.codePointCount(0, name.length()) > 80) name = name.substring(0, name.offsetByCodePoints(0, 80));
        JsonObject body = new JsonObject();
        body.addProperty("username", name);
        if (avatar != null) body.addProperty("avatar_url", avatar.url(128));
        else body.addProperty("avatar_url", "https://cdn.discordapp.com/embed/avatars/0.png");
        body.addProperty("content", content);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        body.add("allowed_mentions", allowed);
        return body;
    }
}
