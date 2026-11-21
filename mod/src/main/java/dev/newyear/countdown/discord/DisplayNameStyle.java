package dev.newyear.countdown.discord;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Plain Unicode text with explicit styling; never interprets formatting codes or commands. */
public record DisplayNameStyle(String text, int startColor, int endColor, boolean gradient, boolean bold, List<Run> runs) {
    public static final int MAX_CODE_POINTS = 32;
    public static final StreamCodec<ByteBuf, DisplayNameStyle> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(128), DisplayNameStyle::text,
            ByteBufCodecs.INT, DisplayNameStyle::startColor,
            ByteBufCodecs.INT, DisplayNameStyle::endColor,
            ByteBufCodecs.BOOL, DisplayNameStyle::gradient,
            ByteBufCodecs.BOOL, DisplayNameStyle::bold,
            Run.CODEC.apply(ByteBufCodecs.list(MAX_CODE_POINTS)), DisplayNameStyle::runs, DisplayNameStyle::new);

    public DisplayNameStyle {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /** Reads the 1.2.14 whole-name format without discarding its styling. */
    public DisplayNameStyle(String text, int startColor, int endColor, boolean gradient, boolean bold) {
        this(text, startColor, endColor, gradient, bold, List.of());
    }

    public record Run(String text, int startColor, int endColor, boolean gradient, boolean bold) {
        public static final StreamCodec<ByteBuf, Run> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), Run::text,
                ByteBufCodecs.INT, Run::startColor, ByteBufCodecs.INT, Run::endColor,
                ByteBufCodecs.BOOL, Run::gradient, ByteBufCodecs.BOOL, Run::bold, Run::new);

        public Run withText(String value) { return new Run(value, startColor, endColor, gradient, bold); }

        /** Keep the existing color interval when a selection cuts through a gradient. */
        public Run slice(int from, int to) {
            int count = text.codePointCount(0, text.length());
            int a = text.codePointCount(0, from), b = text.codePointCount(0, to);
            return new Run(text.substring(from, to), gradient ? mix(startColor, endColor, a / (double) count) : startColor,
                    gradient ? mix(startColor, endColor, b / (double) count) : endColor, gradient, bold);
        }
    }

    public List<Run> effectiveRuns() {
        return runs.isEmpty() ? (text == null || text.isEmpty() ? List.of()
                : List.of(new Run(text, startColor, endColor, gradient, bold))) : runs;
    }

    public static DisplayNameStyle ofRuns(List<Run> runs) {
        List<Run> nonempty = runs.stream().filter(r -> !r.text().isEmpty()).toList();
        String text = nonempty.stream().map(Run::text).collect(java.util.stream.Collectors.joining());
        Run first = nonempty.isEmpty() ? new Run("", 0xFFFFFF, 0x83C9CE, false, false) : nonempty.getFirst();
        return new DisplayNameStyle(text, first.startColor(), first.endColor(), first.gradient(), first.bold(), nonempty);
    }

    public static DisplayNameStyle plain(String name) {
        return new DisplayNameStyle(name, 0xFFFFFF, 0x83C9CE, false, false);
    }

    public DisplayNameStyle checked() {
        String name = text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > MAX_CODE_POINTS)
            throw new IllegalArgumentException("표시 이름은 1~32자로 입력하세요.");
        if (name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.SURROGATE || c == 0x2028 || c == 0x2029 || c == 0xA7))
            throw new IllegalArgumentException("줄바꿈·제어 문자·서식 코드 없이 이름만 입력하세요.");
        checkColors(startColor, endColor);
        if (runs.isEmpty()) return new DisplayNameStyle(name, startColor, endColor, gradient, bold);
        if (runs.size() > MAX_CODE_POINTS || !text.equals(runs.stream().map(Run::text).collect(java.util.stream.Collectors.joining())))
            throw new IllegalArgumentException("이름과 서식 구간이 일치하지 않습니다.");
        List<Run> normalized = new ArrayList<>();
        for (Run run : runs) {
            checkColors(run.startColor(), run.endColor());
            if (run.text().isEmpty()) throw new IllegalArgumentException("빈 서식 구간은 저장할 수 없습니다.");
            if (run.text().codePoints().anyMatch(c -> Character.getType(c) == Character.SURROGATE))
                throw new IllegalArgumentException("유니코드 글자 중간에서 서식을 나눌 수 없습니다.");
            normalized.add(run.withText(Normalizer.normalize(run.text(), Normalizer.Form.NFC)));
        }
        while (!normalized.isEmpty() && normalized.getFirst().text().stripLeading().isEmpty()) normalized.removeFirst();
        while (!normalized.isEmpty() && normalized.getLast().text().stripTrailing().isEmpty()) normalized.removeLast();
        if (!normalized.isEmpty()) {
            normalized.set(0, normalized.getFirst().withText(normalized.getFirst().text().stripLeading()));
            normalized.set(normalized.size() - 1, normalized.getLast().withText(normalized.getLast().text().stripTrailing()));
        }
        DisplayNameStyle result = ofRuns(normalized);
        if (!result.text().equals(name)) throw new IllegalArgumentException("조합 중인 글자의 서식을 나누지 말고 입력을 먼저 완료하세요.");
        return result;
    }

    private static void checkColors(int start, int end) {
        if ((start & 0xFF000000) != 0 || (end & 0xFF000000) != 0)
            throw new IllegalArgumentException("색상은 #RRGGBB 형식으로 입력하세요.");
    }

    public Component component() {
        List<Run> all = effectiveRuns();
        if (all.size() == 1 && !all.getFirst().gradient()) {
            Run run = all.getFirst();
            return Component.literal(run.text()).withStyle(s -> s.withColor(run.startColor()).withBold(run.bold()));
        }
        MutableComponent result = Component.empty();
        for (Run run : all) {
            if (!run.gradient() || run.text().codePointCount(0, run.text().length()) > MAX_CODE_POINTS) {
                result.append(Component.literal(run.text()).withStyle(s -> s.withColor(run.startColor()).withBold(run.bold())));
                continue;
            }
            int[] letters = run.text().codePoints().toArray();
            for (int i = 0; i < letters.length; i++) {
                int color = mix(run.startColor(), run.endColor(), letters.length == 1 ? 0.5 : i / (double) (letters.length - 1));
                var font = new net.minecraft.network.chat.FontDescription.Resource(
                        new GradientFont(run.text(), run.startColor(), run.endColor(), run.bold(), i).id());
                result.append(Component.literal(new String(Character.toChars(letters[i])))
                        .withStyle(s -> s.withColor(color).withBold(run.bold()).withFont(font)));
            }
        }
        return result;
    }

    public int colorAt(int index, int length) {
        if (!gradient || length < 2) return startColor;
        return mix(startColor, endColor, index / (double) (length - 1));
    }

    public static int mix(int startColor, int endColor, double position) {
        double t = Math.clamp(position, 0, 1);
        int color = 0;
        for (int shift : new int[]{16, 8, 0}) {
            int a = (startColor >> shift) & 255, b = (endColor >> shift) & 255;
            color |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return color;
    }

    public static int parseColor(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("색상은 #RRGGBB 형식으로 입력하세요.");
        return Integer.parseInt(value.substring(1), 16);
    }
}
