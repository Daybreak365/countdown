package dev.newyear.countdown.discord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** PKCE verifiers stay on the server. Sessions are single-use and tied to the exact connection. */
final class DiscordSocialSessions {
    static final long TTL = 180_000;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    static final class Session {
        final UUID player;
        final Object connection;
        final String name, id, verifier, clientId, redirect;
        final long created;
        final boolean web;
        boolean used;
        Session(UUID player, Object connection, String name, String id, String verifier, String clientId, String redirect, long created, boolean web) {
            this.player = player; this.connection = connection; this.name = name; this.id = id; this.verifier = verifier;
            this.clientId = clientId; this.redirect = redirect; this.created = created; this.web = web;
        }
        DiscordSocialPackets.Challenge packet() { return new DiscordSocialPackets.Challenge(id, clientId, challenge(verifier)); }
        DiscordSocialPackets.WebChallenge webPacket() { return new DiscordSocialPackets.WebChallenge(id, clientId, challenge(verifier), redirect); }
        boolean acceptsRedirect(String received) {
            return web ? DiscordWebOAuth.validRedirect(redirect) && redirect.equals(received) : DiscordOAuth.allowedRedirect(redirect, received);
        }
    }
    synchronized Session begin(UUID player, Object connection, String name, String clientId, String redirect, long now) {
        return begin(player, connection, name, clientId, redirect, now, false);
    }
    synchronized Session begin(UUID player, Object connection, String name, String clientId, String redirect, long now, boolean web) {
        sessions.values().removeIf(s -> now - s.created >= TTL);
        Session existing = sessions.get(player);
        if (existing != null && (existing.used || now - existing.created < 5000)) return null;
        if (sessions.size() >= 512) return null;
        Session s = new Session(player, connection, name, random(), random(), clientId, redirect, now, web);
        sessions.put(player, s);
        return s;
    }
    synchronized Session claim(UUID player, Object connection, String id, long now) {
        Session s = sessions.get(player);
        if (s == null || s.connection != connection || !s.id.equals(id) || s.used || now - s.created >= TTL) return null;
        s.used = true;
        return s;
    }
    synchronized boolean current(Session s, long now) { return sessions.get(s.player) == s && now - s.created < TTL; }
    synchronized void finish(Session s) { sessions.remove(s.player, s); }
    synchronized void cancel(UUID player, Object connection) {
        Session s = sessions.get(player);
        if (s != null && s.connection == connection) sessions.remove(player);
    }
    synchronized void clear() { sessions.clear(); }
    private String random() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
