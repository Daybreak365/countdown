package dev.newyear.countdown.discord;

import java.net.URI;
import java.util.Map;

/** Shared policy for browser authorization. Never accepts a server-supplied browser URL. */
public final class DiscordWebOAuth {
    private DiscordWebOAuth() {}
    public static final String DEFAULT_REDIRECT = "http://127.0.0.1:53137/callback";

    public static boolean validRedirect(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            int port = uri.getPort();
            return port >= 1024 && port <= 65535 && value.equals("http://127.0.0.1:" + port + "/callback");
        } catch (IllegalArgumentException e) { return false; }
    }

    public static URI authorization(String clientId, String challenge, String redirect, String state) {
        if (!AvatarRef.isUserId(clientId) || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")
                || state == null || !state.matches("[A-Za-z0-9_-]{43}") || !validRedirect(redirect))
            throw new IllegalArgumentException("올바르지 않은 Discord 웹 인증 설정입니다.");
        return URI.create("https://discord.com/oauth2/authorize?" + DiscordOAuth.encode(Map.of(
                "client_id", clientId, "response_type", "code", "scope", "identify", "prompt", "consent",
                "redirect_uri", redirect, "state", state, "code_challenge", challenge, "code_challenge_method", "S256")));
    }
}
