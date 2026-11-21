package dev.newyear.countdown.client;

/**
 * 2027 네컷 프레임 디자인. 사진 띠의 배경·글자·장식 색과, 부스 커튼(사진 배경) 색을 함께 정한다.
 * 순서는 PhotoBooth.FRAMES 와 같고, 이름은 번역 키 photobooth.newyearcountdown.frame.<key>.
 */
public final class PhotoFrames {
    public enum Deco { SPARKLE, GOLD, SNOW, HEART, CONFETTI, FIREWORK, SAEKDONG }

    /**
     * @param top/bottom 띠 배경 세로 그라데이션, text 글자, accent 포인트(그림자·장식), border 사진 테두리,
     *                   backdrop 부스 커튼(사진 속 배경), pleat 커튼 주름 그늘
     */
    public record Frame(String key, int top, int bottom, int text, int accent, int border, int backdrop, int pleat, Deco deco) {}

    public static final Frame[] ALL = {
            new Frame("classic", 0xFF17171A, 0xFF2A2A30, 0xFFF5F5F0, 0xFFE8C15A, 0xFF0B0B0D, 0xFF34343B, 0xFF26262C, Deco.SPARKLE),
            new Frame("red", 0xFFC0242F, 0xFF8A1420, 0xFFFFF3D6, 0xFFF6C945, 0xFFF6C945, 0xFFC22B36, 0xFF9E1E28, Deco.GOLD),
            new Frame("snow", 0xFFF8FBFF, 0xFFD8E8FA, 0xFF2B3D66, 0xFF6FA8E8, 0xFFFFFFFF, 0xFFE6F0FA, 0xFFC9DAEE, Deco.SNOW),
            new Frame("pink", 0xFFFFDDE7, 0xFFF5B2C8, 0xFFB0305A, 0xFFFF6F98, 0xFFFFFFFF, 0xFFF7C3D3, 0xFFE9A3BA, Deco.HEART),
            new Frame("gold", 0xFFFFF3CC, 0xFFEFCF72, 0xFF2D2416, 0xFFC8921E, 0xFFFFFFFF, 0xFFF2DDA0, 0xFFD9BE73, Deco.CONFETTI),
            new Frame("night", 0xFF121A42, 0xFF3D1F5E, 0xFFFFE27A, 0xFFFF8FD0, 0xFF0A0E2A, 0xFF1F2756, 0xFF151B40, Deco.FIREWORK),
            new Frame("saekdong", 0xFFFCF6E8, 0xFFF1E3C6, 0xFFB3202E, 0xFF1F6F5C, 0xFFFFFFFF, 0xFFF3E9D2, 0xFFDCCDAE, Deco.SAEKDONG),
    };

    /** 필터 이름(번역 키 접미사). PhotoBooth.FILTERS 와 같은 순서. */
    public static final String[] FILTERS = {"none", "bright", "mono"};

    private PhotoFrames() {}

    public static Frame get(int i) {
        return ALL[Math.floorMod(i, ALL.length)];
    }
}
