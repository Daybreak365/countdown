package dev.newyear.countdown.client;

/** Mutable picker color; achromatic RGB edits retain the user's last hue. */
final class HsvColor {
    private double hue, saturation, value;

    HsvColor(int rgb) { setRgb(rgb); }
    double hue() { return hue; }
    double saturation() { return saturation; }
    double value() { return value; }
    int rgb() { return rgb(hue, saturation, value); }
    void setHue(double hue) { this.hue = unit(hue); }
    void setSaturationValue(double saturation, double value) {
        this.saturation = unit(saturation); this.value = unit(value);
    }

    void setRgb(int rgb) {
        double r = (rgb >> 16 & 255) / 255.0, g = (rgb >> 8 & 255) / 255.0, b = (rgb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        value = max;
        saturation = max == 0 ? 0 : delta / max;
        if (delta == 0) return;
        double sector = max == r ? (g - b) / delta : max == g ? (b - r) / delta + 2 : (r - g) / delta + 4;
        hue = (sector / 6 + 1) % 1;
    }

    static int rgb(double hue, double saturation, double value) {
        double h = unit(hue) * 6, s = unit(saturation), v = unit(value);
        double f = h - Math.floor(h), p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        return switch ((int) h % 6) {
            case 0 -> pack(v, t, p);
            case 1 -> pack(q, v, p);
            case 2 -> pack(p, v, t);
            case 3 -> pack(p, q, v);
            case 4 -> pack(t, p, v);
            default -> pack(v, p, q);
        };
    }

    private static double unit(double n) { return Double.isFinite(n) ? Math.clamp(n, 0, 1) : 0; }
    private static int pack(double r, double g, double b) {
        return (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8 | (int) Math.round(b * 255);
    }
}
