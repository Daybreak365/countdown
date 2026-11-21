package dev.newyear.countdown.discord;

import dev.newyear.countdown.NewYearCountdown;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Bounded font metadata keeps the visible component as real, selectable Unicode text. */
public record GradientFont(String text, int startColor, int endColor, boolean bold, int index) {
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "name_gradient/"
                + String.format("%06x/%06x/", startColor, endColor) + (bold ? "1/" : "0/") + index + "/"
                + HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static GradientFont parse(Identifier id) {
        if (!id.getNamespace().equals(NewYearCountdown.MOD_ID) || !id.getPath().startsWith("name_gradient/")) return null;
        String path = id.getPath();
        if (path.length() > 300) return null;
        String[] parts = path.split("/", -1);
        if (parts.length != 6 || !parts[1].matches("[0-9a-f]{6}") || !parts[2].matches("[0-9a-f]{6}")
                || !parts[3].matches("[01]") || !parts[4].matches("[0-9]{1,2}")) return null;
        try {
            byte[] bytes = HexFormat.of().parseHex(parts[5]);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)).equals(parts[5])) return null;
            int count = text.codePointCount(0, text.length()), index = Integer.parseInt(parts[4]);
            if (count < 1 || count > DisplayNameStyle.MAX_CODE_POINTS || index >= count) return null;
            return new GradientFont(text, Integer.parseInt(parts[1], 16), Integer.parseInt(parts[2], 16), parts[3].equals("1"), index);
        } catch (IllegalArgumentException e) { return null; }
    }
}
