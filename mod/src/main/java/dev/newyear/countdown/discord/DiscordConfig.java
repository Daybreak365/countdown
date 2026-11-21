package dev.newyear.countdown.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 디스코드 연동 설정 (config/newyearcountdown-discord.json).
 * 봇 토큰은 이 파일의 token 이나 환경 변수 DISCORD_BOT_TOKEN 으로 준다(환경 변수가 우선). 토큰은 절대 로그에 남기지 않는다.
 */
public final class DiscordConfig {
    public static final String TOKEN_ENV = "DISCORD_BOT_TOKEN";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile DiscordConfig instance = new DiscordConfig();

    /** false 면 봇도 켜지 않고 채팅 아바타도 붙이지 않는다. */
    public boolean enabled = true;
    /** 디스코드 봇 토큰. 비워 두면 환경 변수 DISCORD_BOT_TOKEN 을 쓴다. */
    public String token = "";
    /** 게임 채팅과 이어 줄 디스코드 채널 ID. 비워 두면 채팅은 잇지 않고 계정 연동만 한다. */
    public String channelId = "";
    /** 게임 채팅을 디스코드로 보낸다. */
    public boolean relayGameChat = true;
    /** 한 웹훅에서 메시지마다 연동한 디스코드 프로필을 사용한다. 봇에 웹훅 관리 권한이 필요하다. */
    public boolean relayUsingLinkedProfiles = true;
    /** SDK 없이 플레이어의 브라우저로 인증한다. 비밀 키는 Minecraft 서버에만 둔다. */
    public boolean oauthEnabled = true;
    public String oauthClientId = "";
    public String oauthClientSecret = "";
    public String oauthRedirectUri = DiscordWebOAuth.DEFAULT_REDIRECT;
    /** Social SDK 인증. 비밀 키는 서버에만 두며 환경 변수를 우선한다. */
    public boolean socialSdkEnabled = true;
    public String socialSdkClientId = "";
    public String socialSdkClientSecret = "";
    public String socialSdkRedirectUri = "http://127.0.0.1/callback";
    /** 디스코드 채널의 글을 게임 채팅에 보인다. 봇 설정에서 Message Content Intent 를 켜야 한다. */
    public boolean relayDiscordChat = true;
    /** 이미지·GIF·스티커·이모지 미리보기를 지원하는 클라이언트에 보낸다. */
    public boolean showMediaInChat = true;
    /** 접속·퇴장을 디스코드에 알린다. */
    public boolean relayJoinLeave = true;
    /** 서버가 열리고 닫힐 때 디스코드에 알린다. */
    public boolean relayServerStatus = true;
    /** 새해 카운트다운이 끝났을 때 디스코드에도 알린다. */
    public boolean relayCountdown = true;
    /** 연동한 플레이어의 채팅 앞에 디스코드 프로필 사진을 작게 보인다. */
    public boolean showAvatarInChat = true;
    /** 탭리스트에도 같은 동그란 디스코드 프로필 사진을 보인다. */
    public boolean showAvatarInTab = true;
    /** 연동하지 않은 플레이어가 접속하면 월드에 들어가기 전에 연동 화면을 먼저 띄운다. */
    public boolean promptLinkOnJoin = true;
    /**
     * 연동해야만 들어올 수 있다(기본). 연동 화면은 건너뛸 수 없고, 코드 시간이 끝나면 접속이 끊긴다.
     * 봇·웹 OAuth·Social SDK 설정이 모두 없을 때는 막지 않는다. false 로 바꾸면 "나중에 하기"가 생긴다.
     */
    public boolean requireLink = true;

    public static DiscordConfig get() {
        return instance;
    }

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** 실제로 쓸 토큰. 없으면 빈 문자열. */
    public String resolveToken() {
        String env = System.getenv(TOKEN_ENV);
        if (env != null && !env.isBlank()) return env.trim();
        return token == null ? "" : token.trim();
    }

    public boolean hasChannel() {
        return AvatarRef.isUserId(channelId == null ? "" : channelId.trim());
    }

    public String socialClientId() { return environmentOr("DISCORD_CLIENT_ID", socialSdkClientId); }
    public String socialClientSecret() { return environmentOr("DISCORD_CLIENT_SECRET", socialSdkClientSecret); }
    public String oauthClientId() { return environmentOr("DISCORD_CLIENT_ID", oauthClientId == null || oauthClientId.isBlank() ? socialSdkClientId : oauthClientId); }
    public String oauthClientSecret() { return environmentOr("DISCORD_CLIENT_SECRET", oauthClientSecret == null || oauthClientSecret.isBlank() ? socialSdkClientSecret : oauthClientSecret); }
    public boolean webReady() {
        return webSetupProblem().isEmpty();
    }
    /** Safe to send to players or logs: field names only, never credentials or configured values. */
    public String webSetupProblem() {
        if (!enabled) return "서버에서 Discord 연동을 꺼 두었습니다.";
        if (!oauthEnabled) return "서버에서 웹 OAuth 인증을 꺼 두었습니다.";
        var missing = new java.util.ArrayList<String>();
        if (!AvatarRef.isUserId(oauthClientId())) missing.add("Client ID");
        if (oauthClientSecret().isBlank()) missing.add("Client Secret");
        if (!DiscordWebOAuth.validRedirect(oauthRedirectUri)) missing.add("Redirect URI");
        return missing.isEmpty() ? "" : "서버 OAuth 설정 필요: " + String.join(", ", missing);
    }
    public boolean authenticationReady() { return webReady() || socialReady(); }
    public boolean socialReady() {
        return enabled && socialSdkEnabled && AvatarRef.isUserId(socialClientId()) && !socialClientSecret().isBlank();
    }
    private static String environmentOr(String name, String fallback) {
        String env = System.getenv(name);
        return env != null && !env.isBlank() ? env.trim() : fallback == null ? "" : fallback.trim();
    }

    public String channel() {
        return channelId == null ? "" : channelId.trim();
    }

    public static void load() {
        Path path = dir().resolve("newyearcountdown-discord.json");
        DiscordConfig next = new DiscordConfig();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                DiscordConfig loaded = GSON.fromJson(r, DiscordConfig.class);
                if (loaded != null) next = loaded;
            } catch (Exception e) {
                // 읽지 못한 파일을 기본값으로 덮어쓰면 적어 둔 토큰이 사라지므로 그대로 둔다
                NewYearCountdown.LOGGER.error("디스코드 설정을 읽지 못해 기본값을 씁니다 (파일은 그대로 둡니다): {}", e.toString());
                instance = next;
                return;
            }
        }
        instance = next;
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(next, w);
        } catch (IOException e) {
            NewYearCountdown.LOGGER.error("디스코드 설정을 저장하지 못했습니다: {}", e.toString());
        }
    }
}
