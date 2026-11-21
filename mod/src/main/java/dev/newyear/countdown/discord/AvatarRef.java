package dev.newyear.countdown.discord;

import java.util.regex.Pattern;

/**
 * 디스코드 프로필 사진 하나를 가리키는 값. 채팅 컴포넌트의 스프라이트 경로({@code u/<유저 ID>/<아바타 해시>})로
 * 서버에서 클라이언트로 전달된다. 클라이언트는 이 값으로만 주소를 만들기 때문에 받는 곳은 항상 디스코드 CDN 이다.
 */
public record AvatarRef(String userId, String hash) {
    /** 프로필 사진을 올리지 않은 계정. 디스코드 기본 아바타를 쓴다. */
    public static final String NO_AVATAR = "0";

    private static final Pattern USER_ID = Pattern.compile("[0-9]{5,20}");
    private static final Pattern HASH = Pattern.compile("(a_)?[0-9a-f]{32}");
    private static final String PREFIX = "u/";

    /** 형식이 맞지 않으면 null. 해시가 없거나 이상하면 기본 아바타로 본다. */
    public static AvatarRef of(String userId, String hash) {
        if (userId == null || !USER_ID.matcher(userId).matches()) return null;
        return new AvatarRef(userId, hash != null && HASH.matcher(hash).matches() ? hash : NO_AVATAR);
    }

    public static boolean isUserId(String s) {
        return s != null && USER_ID.matcher(s).matches();
    }

    public String spritePath() {
        return PREFIX + userId + "/" + hash;
    }

    /** {@link #spritePath()} 의 역. 형식이 조금이라도 다르면 null. */
    public static AvatarRef parsePath(String path) {
        if (path == null || !path.startsWith(PREFIX)) return null;
        int slash = path.indexOf('/', PREFIX.length());
        if (slash < 0) return null;
        String id = path.substring(PREFIX.length(), slash);
        String hash = path.substring(slash + 1);
        if (!USER_ID.matcher(id).matches()) return null;
        if (!NO_AVATAR.equals(hash) && !HASH.matcher(hash).matches()) return null;
        return new AvatarRef(id, hash);
    }

    public boolean hasCustomAvatar() {
        return !NO_AVATAR.equals(hash);
    }

    /** 디스코드 CDN 주소. size 는 2의 거듭제곱(16~4096). 움직이는 아바타도 PNG 로 받으면 정지 화면이 온다. */
    public String url(int size) {
        if (!hasCustomAvatar()) {
            long index = (Long.parseUnsignedLong(userId) >>> 22) % 6;
            return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
        }
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + hash + ".png?size=" + size;
    }
}
