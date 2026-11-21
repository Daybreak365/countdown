package dev.newyear.countdown.ai;

/**
 * 한국어 조사 붙이기. 정령 이름은 관리자가 바꿀 수 있으므로 "첫눈이" / "루돌프가" 처럼 받침에 맞춰 고른다.
 */
public final class Josa {
    private Josa() {}

    /** 마지막 글자에 받침이 있는가. 한글이 아니면 숫자만 읽는 소리로 판단하고, 나머지는 받침 없음으로 본다. */
    public static boolean hasBatchim(String word) {
        if (word == null) return false;
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return (c - 0xAC00) % 28 != 0;
            if (c >= '0' && c <= '9') return "013678".indexOf(c) >= 0; // 영 일 삼 육 칠 팔
            if (Character.isLetter(c)) return false;
            // 따옴표·괄호·공백 같은 기호는 건너뛰고 그 앞 글자를 본다
        }
        return false;
    }

    public static String attach(String word, String withBatchim, String withoutBatchim) {
        String w = word == null ? "" : word;
        return w + (hasBatchim(w) ? withBatchim : withoutBatchim);
    }

    public static String iGa(String word) { return attach(word, "이", "가"); }
    public static String eunNeun(String word) { return attach(word, "은", "는"); }
    public static String eulReul(String word) { return attach(word, "을", "를"); }
    public static String gwaWa(String word) { return attach(word, "과", "와"); }
}
