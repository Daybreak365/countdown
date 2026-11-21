package dev.newyear.countdown.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 마인크래프트 계정과 디스코드 계정의 연동 기록, 그리고 연동 대기 중인 일회용 코드.
 * 게임 스레드와 디스코드 스레드가 함께 쓰므로 모든 접근을 동기화한다.
 */
public final class DiscordLinks {
    public static final long CODE_TTL_MS = 10 * 60_000L;
    /** 한 디스코드 계정이 틀릴 수 있는 횟수 (CODE_TTL_MS 동안). 코드 무작위 대입을 막는다. */
    static final int MAX_FAILURES = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 연동된 디스코드 계정의 프로필. 파일에 그대로 저장된다. */
    public static final class Link {
        public String discordId = "";
        public String username = "";
        public String displayName = "";
        /** 아바타 해시. 없으면 빈 문자열. */
        public String avatar = "";
        public String playerName = "";
        public long linkedAt;

        public AvatarRef avatarRef() {
            return AvatarRef.of(discordId, avatar);
        }

        public String shownName() {
            return displayName == null || displayName.isBlank() ? username : displayName;
        }
    }

    public record Pending(UUID player, String playerName, long expiresAt) {}

    private final Path file;
    private final Random random;
    private final Map<UUID, Link> byPlayer = new LinkedHashMap<>();
    private final Map<String, Pending> codes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> failures = new HashMap<>();

    public DiscordLinks(Path file) {
        this(file, new SecureRandom());
    }

    DiscordLinks(Path file, Random random) {
        this.file = file;
        this.random = random;
    }

    public synchronized void load() {
        byPlayer.clear();
        if (file == null || !Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Link> raw = GSON.fromJson(r, new TypeToken<Map<String, Link>>() {}.getType());
            if (raw == null) return;
            for (Map.Entry<String, Link> e : raw.entrySet()) {
                Link link = e.getValue();
                if (link == null || !AvatarRef.isUserId(link.discordId)) continue;
                try {
                    byPlayer.put(UUID.fromString(e.getKey()), link);
                } catch (IllegalArgumentException ignored) {
                    // 손으로 고치다 망가진 항목은 건너뛴다
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("연동 기록을 읽지 못했습니다: " + file, e);
        }
    }

    public synchronized void save() throws IOException {
        if (file == null) return;
        Map<String, Link> raw = new LinkedHashMap<>();
        byPlayer.forEach((uuid, link) -> raw.put(uuid.toString(), link));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(raw, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized Link get(UUID player) {
        return byPlayer.get(player);
    }

    public synchronized UUID playerOf(String discordId) {
        for (Map.Entry<UUID, Link> e : byPlayer.entrySet()) if (e.getValue().discordId.equals(discordId)) return e.getKey();
        return null;
    }

    public synchronized int size() {
        return byPlayer.size();
    }

    /** 플레이어에게 새 6자리 코드를 준다. 전에 받은 코드는 무효가 된다. */
    public synchronized String createCode(UUID player, String playerName, long now) {
        purge(now);
        codes.values().removeIf(p -> p.player().equals(player));
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new Pending(player, playerName, now + CODE_TTL_MS));
        return code;
    }

    public enum RedeemStatus { OK, BAD_CODE, TOO_MANY_ATTEMPTS }

    public record Redeemed(RedeemStatus status, UUID player, String playerName) {}

    /**
     * 디스코드 쪽에서 코드를 입력했을 때. 성공하면 연동을 기록한다(저장은 호출한 쪽이 한다).
     * 한 디스코드 계정은 한 플레이어에게만 연동되므로 다른 플레이어와의 기존 연동은 끊는다.
     */
    public synchronized Redeemed redeem(String code, String discordId, String username, String displayName, String avatar, long now) {
        purge(now);
        if (!AvatarRef.isUserId(discordId)) return new Redeemed(RedeemStatus.BAD_CODE, null, null);
        ArrayDeque<Long> failed = failures.computeIfAbsent(discordId, k -> new ArrayDeque<>());
        if (failed.size() >= MAX_FAILURES) return new Redeemed(RedeemStatus.TOO_MANY_ATTEMPTS, null, null);
        String digits = code == null ? "" : code.replaceAll("[^0-9]", "");
        Pending pending = codes.remove(digits);
        if (pending == null) {
            failed.add(now);
            return new Redeemed(RedeemStatus.BAD_CODE, null, null);
        }
        failures.remove(discordId);
        byPlayer.values().removeIf(l -> l.discordId.equals(discordId));
        Link link = new Link();
        link.discordId = discordId;
        link.username = nullToEmpty(username);
        link.displayName = nullToEmpty(displayName);
        link.avatar = nullToEmpty(avatar);
        link.playerName = pending.playerName();
        link.linkedAt = now;
        byPlayer.put(pending.player(), link);
        return new Redeemed(RedeemStatus.OK, pending.player(), pending.playerName());
    }

    public synchronized Link unlink(UUID player) {
        codes.values().removeIf(p -> p.player().equals(player));
        return byPlayer.remove(player);
    }

    /** Only call after exchanging a Discord authorization code on the server. Saves atomically with rollback. */
    synchronized Link linkVerified(UUID player, String playerName, String discordId, String username,
                                   String displayName, String avatar, long now) throws IOException {
        if (!AvatarRef.isUserId(discordId)) throw new IllegalArgumentException("올바르지 않은 디스코드 계정");
        UUID existing = playerOf(discordId);
        if (existing != null && !existing.equals(player)) throw new IllegalArgumentException("다른 플레이어에 연결된 계정입니다. 기존 연동을 먼저 해제하세요.");
        if (byPlayer.containsKey(player)) throw new IllegalArgumentException("이미 연동된 플레이어입니다. 기존 연동을 먼저 해제하세요.");
        Link link = new Link();
        link.discordId = discordId; link.username = nullToEmpty(username); link.displayName = nullToEmpty(displayName);
        link.avatar = nullToEmpty(avatar); link.playerName = playerName; link.linkedAt = now;
        byPlayer.put(player, link);
        try { save(); }
        catch (IOException e) { byPlayer.remove(player); throw e; }
        codes.values().removeIf(p -> p.player().equals(player));
        return link;
    }

    public synchronized Link unlinkDiscord(String discordId) {
        UUID player = playerOf(discordId);
        return player == null ? null : byPlayer.remove(player);
    }

    /** 디스코드에서 본 최신 프로필로 갱신한다. 바뀐 것이 있으면 true. null 인 값은 건드리지 않는다. */
    public synchronized boolean updateProfile(String discordId, String username, String displayName, String avatar) {
        UUID player = playerOf(discordId);
        if (player == null) return false;
        Link link = byPlayer.get(player);
        boolean changed = false;
        if (username != null && !username.equals(link.username)) { link.username = username; changed = true; }
        if (displayName != null && !displayName.equals(link.displayName)) { link.displayName = displayName; changed = true; }
        if (avatar != null && !avatar.equals(link.avatar)) { link.avatar = avatar; changed = true; }
        return changed;
    }

    public synchronized void rememberPlayerName(UUID player, String name) {
        Link link = byPlayer.get(player);
        if (link != null && name != null) link.playerName = name;
    }

    private void purge(long now) {
        codes.values().removeIf(p -> p.expiresAt() <= now);
        for (Iterator<ArrayDeque<Long>> it = failures.values().iterator(); it.hasNext(); ) {
            ArrayDeque<Long> times = it.next();
            while (!times.isEmpty() && times.peekFirst() + CODE_TTL_MS <= now) times.removeFirst();
            if (times.isEmpty()) it.remove();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
