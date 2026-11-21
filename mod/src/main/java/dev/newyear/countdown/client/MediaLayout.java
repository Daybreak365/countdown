package dev.newyear.countdown.client;

/** One image split into consecutive chat rows, with aspect ratio and chat spacing preserved. */
final class MediaLayout {
    static int chatWidth(int chatWidth, double chatScale) { return Math.clamp((int)(chatWidth / Math.max(.1, chatScale)) - 12, 20, 4096); }
    static int width(int chatWidth, double chatScale) { return Math.min(128, chatWidth(chatWidth, chatScale)); }
    static int rows(int width, int imageWidth, int imageHeight) {
        return Math.clamp((int)Math.ceil(Math.min(72, (double)width * imageHeight / Math.max(1, imageWidth)) / 9), 1, 8);
    }
    static int lineHeight(double spacing) { return (int)(9 * (1 + spacing)); }
    static float topOffset(double spacing) { return Math.round(8 + 4 * spacing) - lineHeight(spacing); }
    // The middle row's native text occupies y..y+9. Centre all three sticker rows on it.
    static float stickerOffset(double spacing) { return (9 - lineHeight(spacing)) / 2f; }
    record Slice(float left, float top, float right, float bottom, float v0, float v1) {}
    static Slice slice(int width, int rows, int row, int lineHeight, int imageWidth, int imageHeight) {
        float scale = Math.min((float)width / imageWidth, (float)(rows * lineHeight) / imageHeight);
        float w = imageWidth * scale, h = imageHeight * scale, y = (rows * lineHeight - h) / 2;
        float top = Math.max(row * lineHeight, y), bottom = Math.min((row + 1) * lineHeight, y + h);
        if (bottom <= top) return null;
        return new Slice(0, top - row * lineHeight, w, bottom - row * lineHeight, (top - y) / h, (bottom - y) / h);
    }
}
