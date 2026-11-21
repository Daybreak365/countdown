package dev.newyear.countdown.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 두 채팅 사이를 오가는 글을 서로의 형식에 맞게 다듬는다. */
public final class DiscordText {
    private DiscordText() {}

    /** 디스코드에서 온 한 메시지를 게임 채팅에 보일 최대 길이. */
    public static final int MAX_INBOUND = 256;

    private static final Pattern USER_MENTION = Pattern.compile("<@!?([0-9]{5,20})>");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?(:[A-Za-z0-9_]{1,32}:)[0-9]{5,20}>");
    private static final Pattern FORMAT_CODE = Pattern.compile("§.?");

    /** 게임 채팅 글을 디스코드에 그대로 보이게 한다(마크다운 문자 무력화). */
    public static String escapeMarkdown(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\*_~`|>#[]()-".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** §a 같은 마인크래프트 색 코드를 지운다. */
    public static String stripFormatting(String s) {
        return s == null ? "" : FORMAT_CODE.matcher(s).replaceAll("");
    }

    /**
     * 디스코드 메시지 본문을 게임 채팅 한 줄로 만든다. 멘션은 이름으로 바꾸고, 줄바꿈과 제어 문자,
     * 색 코드를 없애고, 너무 길면 자른다.
     *
     * @param mentions 메시지의 mentions 배열 (없으면 null)
     */
    public static String inbound(String content, JsonArray mentions, int attachments) {
        return inbound(content, mentions, attachments, false);
    }

    public static String inboundRich(String content, JsonArray mentions) {
        return inbound(content, mentions, 0, true);
    }

    private static String inbound(String content, JsonArray mentions, int attachments, boolean keepEmoji) {
        // 색 코드는 뒤따르는 글자까지 통째로 지운다. 남은 § 는 아래에서 한 번 더 걸러진다.
        String text = stripFormatting(content);
        Matcher m = USER_MENTION.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (m.find()) m.appendReplacement(replaced, Matcher.quoteReplacement("@" + mentionName(mentions, m.group(1))));
        m.appendTail(replaced);
        text = keepEmoji ? replaced.toString() : CUSTOM_EMOJI.matcher(replaced).replaceAll("$1");

        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') continue;
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)) {
                space = out.length() > 0;
                continue;
            }
            if (space) out.append(' ');
            space = false;
            out.appendCodePoint(cp);
        }
        if (out.codePointCount(0, out.length()) > MAX_INBOUND) {
            out.setLength(out.offsetByCodePoints(0, MAX_INBOUND));
            out.append('…');
        }
        if (attachments > 0) {
            if (out.length() > 0) out.append(' ');
            out.append("[첨부 ").append(attachments).append("개]");
        }
        return out.toString();
    }

    private static String mentionName(JsonArray mentions, String id) {
        if (mentions != null) {
            for (JsonElement e : mentions) {
                if (!e.isJsonObject()) continue;
                JsonObject user = e.getAsJsonObject();
                if (id.equals(str(user, "id"))) return displayName(user, null);
            }
        }
        return "알 수 없는 사용자";
    }

    /** 서버 별명 > 표시 이름 > 사용자명 순으로 고른다. member 는 없으면 null. */
    public static String displayName(JsonObject user, JsonObject member) {
        String nick = member == null ? null : str(member, "nick");
        if (nick != null && !nick.isBlank()) return cleanName(nick);
        String global = str(user, "global_name");
        if (global != null && !global.isBlank()) return cleanName(global);
        String username = str(user, "username");
        return username == null || username.isBlank() ? "디스코드 사용자" : cleanName(username);
    }

    private static String cleanName(String name) {
        String cleaned = inbound(name, null, 0);
        if (cleaned.codePointCount(0, cleaned.length()) > 32) cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, 32));
        return cleaned.isEmpty() ? "디스코드 사용자" : cleaned;
    }

    /** 문자열 필드. 없거나 null 이면 null. */
    public static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
