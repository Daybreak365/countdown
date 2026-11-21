package dev.newyear.countdown.discord;

/** 아바타 이미지를 채팅 글리프 크기로 줄이고 동그랗게 깎는다. 픽셀은 모두 ARGB. */
public final class AvatarRaster {
    private AvatarRaster() {}

    /** 면적 평균으로 정사각형 n x n 으로 줄인다(늘리는 경우에도 동작). 알파는 미리 곱해서 평균한다. */
    public static int[] resample(int[] src, int w, int h, int n) {
        if (w <= 0 || h <= 0 || n <= 0 || src.length < w * h) throw new IllegalArgumentException("bad image size");
        int[] out = new int[n * n];
        for (int y = 0; y < n; y++) {
            double y0 = (double) y * h / n, y1 = (double) (y + 1) * h / n;
            for (int x = 0; x < n; x++) {
                double x0 = (double) x * w / n, x1 = (double) (x + 1) * w / n;
                double a = 0, r = 0, g = 0, b = 0, area = 0;
                for (int sy = (int) y0; sy < Math.min(h, (int) Math.ceil(y1)); sy++) {
                    double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
                    for (int sx = (int) x0; sx < Math.min(w, (int) Math.ceil(x1)); sx++) {
                        double weight = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
                        if (weight <= 0) continue;
                        int p = src[sy * w + sx];
                        double pa = (p >>> 24) / 255.0 * weight;
                        a += pa;
                        r += ((p >> 16) & 255) * pa;
                        g += ((p >> 8) & 255) * pa;
                        b += (p & 255) * pa;
                        area += weight;
                    }
                }
                if (area <= 0 || a <= 0) continue;
                out[y * n + x] = (clamp(a / area * 255) << 24) | (clamp(r / a) << 16) | (clamp(g / a) << 8) | clamp(b / a);
            }
        }
        return out;
    }

    /** 원 밖을 투명하게 만든다. 가장자리 한 픽셀은 부드럽게 처리한다. */
    public static void circleMask(int[] pixels, int n) {
        double c = n / 2.0, radius = n / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dist = Math.hypot(x + 0.5 - c, y + 0.5 - c);
                double cover = Math.max(0, Math.min(1, radius - dist + 0.5));
                int p = pixels[y * n + x];
                int alpha = (int) Math.round((p >>> 24) * cover);
                pixels[y * n + x] = alpha == 0 ? 0 : (alpha << 24) | (p & 0xFFFFFF);
            }
        }
    }

    /** 아바타를 받기 전이나 받지 못했을 때 쓰는 단색 원. */
    public static int[] solidCircle(int n, int argb) {
        int[] out = new int[n * n];
        java.util.Arrays.fill(out, argb);
        circleMask(out, n);
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
