package dev.newyear.countdown.discord;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Validated public media references. No bot credentials are sent to clients. */
public record DiscordMedia(String url, String label, int width, int height, boolean inline, boolean hidden) {
    public static final String EMOJI_BASE = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@v16.0.1/assets/72x72/";
    public DiscordMedia {
        if (!allowedUrl(url) || label == null || label.length() > 160 || width < 1 || height < 1 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("Invalid Discord media reference");
    }
    public String key() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public boolean sticker() {
        URI uri = URI.create(url);
        return !inline && (uri.getHost().equals("cdn.discordapp.com") || uri.getHost().equals("media.discordapp.net"))
                && uri.getPath().startsWith("/stickers/");
    }
    public static boolean allowedUrl(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getPort() != -1 || u.getFragment() != null) return false;
            String host = u.getHost(), path = u.getRawPath();
            if (host == null || path == null || path.contains("..") || path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c|00).*")) return false;
            return switch (host) {
                case "cdn.discordapp.com", "media.discordapp.net" -> path.startsWith("/attachments/") || path.startsWith("/emojis/")
                        || path.startsWith("/stickers/") || path.startsWith("/external/");
                case "media.tenor.com", "c.tenor.com", "media.giphy.com", "i.giphy.com" -> path.length() > 1;
                case "cdn.jsdelivr.net" -> value.startsWith(EMOJI_BASE)
                        && value.substring(EMOJI_BASE.length()).matches("[0-9a-f]{2,6}(?:-[0-9a-f]{2,6}){0,15}\\.png");
                default -> false;
            };
        } catch (IllegalArgumentException e) { return false; }
    }
    public static boolean imageUrl(String value) {
        if (!allowedUrl(value)) return false;
        String path = URI.create(value).getPath().toLowerCase(Locale.ROOT);
        return path.matches(".*\\.(png|jpe?g|gif|webp|avif)$");
    }
    /** Discord's image proxy converts formats unavailable to the Java image reader. */
    public static String downloadUrl(String original) {
        if (!allowedUrl(original)) throw new IllegalArgumentException("Untrusted media URL");
        URI uri = URI.create(original);
        if (!uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(webp|avif)$")) return original;
        if (!uri.getHost().equals("cdn.discordapp.com") && !uri.getHost().equals("media.discordapp.net")) return original;
        String query = uri.getRawQuery();
        StringBuilder kept = new StringBuilder();
        if (query != null) for (String part : query.split("&"))
            if (!part.matches("(?i)(format|width|height|quality)=.*")) kept.append(part).append('&');
        return "https://media.discordapp.net" + uri.getRawPath() + "?" + kept + "format=png&width=384&height=384";
    }
    @Override public String toString() { return "DiscordMedia[" + label + "]"; }
}
