package dev.newyear.countdown.discord;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** Small, bounded representation shared by the relay and the client. */
public record DiscordRichMessage(List<Part> parts, List<DiscordMedia> media) {
    public static final int MAX_MEDIA = 6, MAX_INLINE = 24, MAX_JSON = 49152;
    private static final int ATTACHMENT_IS_SPOILER = 1 << 3;
    private static final Gson GSON = new Gson();
    private static final Pattern TOKENS = Pattern.compile("<(a?):([A-Za-z0-9_]{1,32}):([0-9]{5,20})>|\\X");
    public record Part(String text, DiscordMedia image) {
        public Part { if (text == null || text.length() > 1024 || image != null && !image.inline()) throw new IllegalArgumentException("Invalid chat part"); }
    }
    public DiscordRichMessage {
        if (parts == null || media == null || parts.size() > 64 || media.size() > MAX_MEDIA
                || parts.stream().anyMatch(Objects::isNull) || media.stream().anyMatch(Objects::isNull)
                || parts.stream().mapToInt(p -> p.text().length()).sum() > 2048
                || parts.stream().filter(p -> p.image() != null).count() > MAX_INLINE
                || media.stream().anyMatch(DiscordMedia::inline)) throw new IllegalArgumentException("Invalid rich chat message");
        parts = List.copyOf(parts); media = List.copyOf(media);
    }
    public String json() { return GSON.toJson(this); }
    public static DiscordRichMessage read(String json) {
        if (json == null || json.length() > MAX_JSON) throw new IllegalArgumentException("Message too large");
        var result = GSON.fromJson(json, DiscordRichMessage.class);
        if (result == null) throw new IllegalArgumentException("Empty message");
        return result;
    }
    public boolean empty() { return parts.stream().allMatch(p -> p.text().isBlank()) && media.isEmpty(); }
    public String plain() {
        StringBuilder s = new StringBuilder();
        parts.forEach(p -> s.append(p.text()));
        media.forEach(m -> s.append(" [").append(m.hidden() ? "스포일러 · " : "").append(m.label()).append(']'));
        return s.toString();
    }
    public static DiscordRichMessage parse(JsonObject message) {
        List<Part> parts = new ArrayList<>(); List<DiscordMedia> media = new ArrayList<>();
        Set<String> spoilerSources = new HashSet<>();
        String text = DiscordText.inboundRich(DiscordText.str(message, "content"), array(message, "mentions"));
        var matcher = TOKENS.matcher(text); StringBuilder plain = new StringBuilder(); int inline = 0;
        while (matcher.find()) {
            String token = matcher.group(), url = null, label = token;
            if (inline < MAX_INLINE && matcher.group(3) != null) {
                url = "https://cdn.discordapp.com/emojis/" + matcher.group(3) + ("a".equals(matcher.group(1)) ? ".gif" : ".png") + "?size=64";
                label = ":" + matcher.group(2) + ":";
            } else if (inline < MAX_INLINE && unicodeEmoji(token)) {
                String normalized = token.contains("\u200D") ? token : token.replace("\uFE0F", "");
                url = DiscordMedia.EMOJI_BASE + normalized.codePoints().mapToObj(Integer::toHexString).collect(java.util.stream.Collectors.joining("-")) + ".png";
                if (!DiscordMedia.allowedUrl(url)) url = null;
            }
            if (url == null) plain.append(token);
            else {
                if (!plain.isEmpty()) { parts.add(new Part(plain.toString(), null)); plain.setLength(0); }
                parts.add(new Part(label, new DiscordMedia(url, label, 72, 72, true, false))); inline++;
            }
        }
        if (!plain.isEmpty()) parts.add(new Part(plain.toString(), null));
        for (JsonElement e : array(message, "attachments")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject(); String url = DiscordText.str(a, "url"), file = label(DiscordText.str(a, "filename"), "첨부 이미지");
            boolean hidden = spoilerAttachment(a);
            if (hidden) {
                if (url != null) spoilerSources.add(url);
                String proxy = DiscordText.str(a, "proxy_url");
                if (proxy != null) spoilerSources.add(proxy);
            }
            String type = DiscordText.str(a, "content_type");
            if (DiscordMedia.imageUrl(url) || type != null && type.startsWith("image/") && DiscordMedia.allowedUrl(url))
                add(media, url, file, number(a, "width", 128), number(a, "height", 72), hidden);
            else if (parts.size() < 60) parts.add(new Part(" [첨부: " + file + "]", null));
        }
        for (JsonElement e : array(message, "sticker_items")) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject(); String id = DiscordText.str(s, "id"), name = label(DiscordText.str(s, "name"), "스티커");
            if (!AvatarRef.isUserId(id)) continue;
            int format = number(s, "format_type", 1);
            if (format == 3) { if (parts.size() < 60) parts.add(new Part(" [Lottie 스티커: " + name + "]", null)); }
            else add(media, (format == 4 ? "https://media.discordapp.net/stickers/" : "https://cdn.discordapp.com/stickers/")
                    + id + (format == 4 ? ".gif" : ".png"), "스티커 · " + name, 160, 160, false);
        }
        for (JsonElement e : array(message, "embeds")) {
            if (!e.isJsonObject()) continue;
            JsonObject embed = e.getAsJsonObject();
            JsonObject image = DiscordText.obj(embed, "image");
            if (image == null) image = DiscordText.obj(embed, "thumbnail");
            if (image == null) continue;
            String original = DiscordText.str(image, "url"), proxy = DiscordText.str(image, "proxy_url");
            String url = DiscordMedia.allowedUrl(original) ? original : DiscordMedia.allowedUrl(proxy) ? proxy : null;
            if (url != null) add(media, url, "gifv".equals(DiscordText.str(embed, "type")) ? "GIF" : "이미지",
                    number(image, "width", 128), number(image, "height", 72),
                    spoilerSource(spoilerSources, original, proxy, DiscordText.str(embed, "url")));
        }
        // Direct CDN GIF/image links can arrive before Discord generates an embed.
        for (String word : text.split("\\s+")) if (DiscordMedia.imageUrl(word))
            add(media, word, "이미지", 128, 72, spoilerSource(spoilerSources, word));
        return new DiscordRichMessage(parts, media);
    }
    private static boolean spoilerAttachment(JsonObject attachment) {
        // Modern Discord uploads use the flag even when filename has no legacy SPOILER_ prefix.
        JsonElement explicit = attachment.get("is_spoiler");
        String filename = DiscordText.str(attachment, "filename");
        return (number(attachment, "flags", 0) & ATTACHMENT_IS_SPOILER) != 0
                || explicit != null && explicit.isJsonPrimitive() && explicit.getAsJsonPrimitive().isBoolean() && explicit.getAsBoolean()
                || filename != null && filename.startsWith("SPOILER_")
                || spoilerUrl(DiscordText.str(attachment, "url")) || spoilerUrl(DiscordText.str(attachment, "proxy_url"));
    }
    private static boolean spoilerUrl(String value) {
        if (value == null) return false;
        try {
            String path = java.net.URI.create(value).getPath();
            return path != null && path.substring(path.lastIndexOf('/') + 1).startsWith("SPOILER_");
        } catch (IllegalArgumentException e) { return false; }
    }
    private static boolean spoilerSource(Set<String> sources, String... urls) {
        for (String url : urls) if (spoilerUrl(url) || sources.stream().anyMatch(source -> sameAsset(source, url))) return true;
        return false;
    }
    private static void add(List<DiscordMedia> media, String url, String label, int w, int h, boolean hidden) {
        if (!DiscordMedia.allowedUrl(url)) return;
        for (int i = 0; i < media.size(); i++) {
            DiscordMedia previous = media.get(i);
            if (!sameAsset(previous.url(), url)) continue;
            // A later source may carry the spoiler metadata missing from an earlier duplicate.
            if (hidden && !previous.hidden()) media.set(i, new DiscordMedia(previous.url(), previous.label(), previous.width(), previous.height(), false, true));
            return;
        }
        if (media.size() >= MAX_MEDIA) return;
        media.add(new DiscordMedia(url, label, Math.clamp(w, 1, 8192), Math.clamp(h, 1, 8192), false, hidden));
    }
    private static boolean sameAsset(String first, String second) {
        if (first == null || second == null) return false;
        try {
            var a = java.net.URI.create(first); var b = java.net.URI.create(second);
            if (!Objects.equals(a.getScheme(), b.getScheme())) return false;
            if (discordCdn(a.getHost()) && discordCdn(b.getHost())) {
                if (Objects.equals(a.getRawPath(), b.getRawPath())) return true;
                // Proxies/edits can change the filename while retaining the same attachment ID.
                String id = attachmentPath(a.getPath());
                return id != null && id.equals(attachmentPath(b.getPath()));
            }
            return Objects.equals(a.getHost(), b.getHost()) && Objects.equals(a.getRawPath(), b.getRawPath())
                    && Objects.equals(a.getRawQuery(), b.getRawQuery());
        } catch (IllegalArgumentException e) { return false; }
    }
    private static boolean discordCdn(String host) { return "cdn.discordapp.com".equals(host) || "media.discordapp.net".equals(host); }
    private static String attachmentPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("/", 5);
        return parts.length == 5 && parts[1].equals("attachments") && parts[2].matches("[0-9]{1,20}") && parts[3].matches("[0-9]{1,20}")
                ? parts[2] + "/" + parts[3] : null;
    }
    static boolean unicodeEmoji(String token) {
        if (token.contains("\uFE0E")) return false;
        return token.codePoints().anyMatch(cp -> cp >= 0x1F000 && cp <= 0x1FAFF || cp >= 0x2600 && cp <= 0x27BF
                || cp == 0x20E3 || cp >= 0x2300 && cp <= 0x23FF && token.contains("\uFE0F"))
                || token.contains("\uFE0F") && (token.startsWith("©") || token.startsWith("®") || token.startsWith("™"));
    }
    private static String label(String value, String fallback) {
        String s = DiscordText.inbound(value, null, 0);
        return s.isBlank() ? fallback : s.substring(0, s.offsetByCodePoints(0, Math.min(64, s.codePointCount(0, s.length()))));
    }
    private static JsonArray array(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
    private static int number(JsonObject o, String key, int fallback) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback; }
        catch (RuntimeException e) { return fallback; }
    }
}
