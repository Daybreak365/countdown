package dev.newyear.countdown.client.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/** 서버가 보낸 UI JSON 을 읽는 관대한 접근자. 없는 키·다른 타입은 기본값으로 읽는다. */
final class Json {
    private Json() {}

    private static JsonPrimitive primitive(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }

    static String str(JsonObject o, String k) {
        return str(o, k, "");
    }

    static String str(JsonObject o, String k, String def) {
        JsonPrimitive p = primitive(o, k);
        return p == null ? def : p.getAsString();
    }

    static long lng(JsonObject o, String k, long def) {
        JsonPrimitive p = primitive(o, k);
        return p != null && p.isNumber() ? p.getAsLong() : def;
    }

    static long lng(JsonObject o, String k) {
        return lng(o, k, 0);
    }

    static int num(JsonObject o, String k, int def) {
        JsonPrimitive p = primitive(o, k);
        return p != null && p.isNumber() ? p.getAsInt() : def;
    }

    static int num(JsonObject o, String k) {
        return num(o, k, 0);
    }

    static double dbl(JsonObject o, String k, double def) {
        JsonPrimitive p = primitive(o, k);
        return p != null && p.isNumber() ? p.getAsDouble() : def;
    }

    static double dbl(JsonObject o, String k) {
        return dbl(o, k, 0);
    }

    static boolean bool(JsonObject o, String k) {
        JsonPrimitive p = primitive(o, k);
        return p != null && p.getAsBoolean(); // "true" 문자열도 참으로 읽는다
    }

    /** 문자열 배열을 ", " 로 잇는다. 배열이 아니면 빈 문자열. */
    static String join(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonArray()) return "";
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) out.add(item.getAsString());
        return String.join(", ", out);
    }
}
