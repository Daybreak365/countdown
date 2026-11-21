package dev.newyear.countdown.client;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import org.w3c.dom.Node;

/** Bounded PNG/JPEG/GIF/APNG decoding and animation composition, off the render thread. */
final class MediaImage {
    static final int MAX_BYTES = 8 * 1024 * 1024, MAX_FRAMES = 200, MAX_FRAME_PIXELS = 2 * 1024 * 1024;
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    record Frame(int[] pixels, int millis) {}
    record Animation(int width, int height, List<Frame> frames) {
        Animation { frames = List.copyOf(frames); if (frames.isEmpty()) throw new IllegalArgumentException("No frames"); }
        long bytes() { return (long)width * height * 4 * frames.size(); }
        int frameAt(long time) {
            long total = frames.stream().mapToLong(Frame::millis).sum();
            long at = Math.floorMod(time, total);
            for (int i = 0; i < frames.size(); i++) { at -= frames.get(i).millis; if (at < 0) return i; }
            return 0;
        }
    }
    static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Media exceeds size limit");
        return bytes;
    }
    static Animation decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid media size");
        if (bytes.length >= 8 && Arrays.equals(PNG, Arrays.copyOf(bytes, 8))) {
            Animation animated = apng(bytes);
            if (animated != null) return animated;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (reader.getFormatName().equalsIgnoreCase("gif")) return gif(reader);
                dimensions(reader.getWidth(0), reader.getHeight(0));
                return scaled(reader.read(0), 1, List.of(), 1000);
            } finally { reader.dispose(); }
        } catch (RuntimeException e) { throw new IOException("Invalid image", e); }
    }
    private static void dimensions(int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long)w * h > 4 * 1024 * 1024) throw new IOException("Image dimensions exceed limit");
    }
    private static int maxSide(int count) { return Math.min(192, (int)Math.sqrt(MAX_FRAME_PIXELS / Math.max(1, count))); }
    private static Animation scaled(BufferedImage image, int count, List<Frame> previous, int delay) {
        double factor = Math.min(1, (double)maxSide(count) / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int)Math.round(image.getWidth() * factor)), h = Math.max(1, (int)Math.round(image.getHeight() * factor));
        var target = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = target.createGraphics();
        g.setComposite(AlphaComposite.Src); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, null); g.dispose();
        var frames = new ArrayList<>(previous);
        frames.add(new Frame(target.getRGB(0, 0, w, h, null, 0, w), Math.clamp(delay, 20, 10000)));
        return new Animation(w, h, frames);
    }
    private static Animation gif(ImageReader reader) throws IOException {
        Node root = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
        Node descriptor = child(root, "LogicalScreenDescriptor");
        int w = attr(descriptor, "logicalScreenWidth", reader.getWidth(0)), h = attr(descriptor, "logicalScreenHeight", reader.getHeight(0));
        dimensions(w, h);
        int count = reader.getNumImages(true);
        if (count < 1) throw new IOException("Empty GIF");
        if (count > MAX_FRAMES) count = 1; // Oversized animation still has a useful static preview.
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Animation result = null;
        for (int i = 0; i < count; i++) {
            Node meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node frame = child(meta, "ImageDescriptor"), control = child(meta, "GraphicControlExtension");
            int x = attr(frame, "imageLeftPosition", 0), y = attr(frame, "imageTopPosition", 0);
            int fw = reader.getWidth(i), fh = reader.getHeight(i);
            region(w, h, x, y, fw, fh);
            String disposal = attribute(control, "disposalMethod", "none");
            int[] previous = disposal.equals("restoreToPrevious") ? canvas.getRGB(0, 0, w, h, null, 0, w) : null;
            var g = canvas.createGraphics(); g.drawImage(reader.read(i), x, y, null); g.dispose();
            result = scaled(canvas, count, result == null ? List.of() : result.frames(), attr(control, "delayTime", 10) * 10);
            if (disposal.equals("restoreToBackgroundColor")) clear(canvas, x, y, fw, fh);
            else if (previous != null) canvas.setRGB(0, 0, w, h, previous, 0, w);
        }
        return result;
    }
    private record Chunk(String type, byte[] data) {}
    private record PngFrame(int w, int h, int x, int y, int delay, int dispose, int blend, ByteArrayOutputStream data) {}
    private static Animation apng(byte[] bytes) throws IOException {
        List<Chunk> chunks = new ArrayList<>(); int offset = 8, count = 0; byte[] header = null;
        while (offset < bytes.length) {
            if (offset + 12 > bytes.length) throw new IOException("Truncated PNG");
            int length = integer(bytes, offset);
            if (length < 0 || length > bytes.length - offset - 12) throw new IOException("Invalid PNG chunk");
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if ((int)crc.getValue() != integer(bytes, offset + 8 + length)) throw new IOException("PNG checksum mismatch");
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            byte[] data = Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length);
            chunks.add(new Chunk(type, data));
            if (type.equals("IHDR")) { if (length != 13 || header != null) throw new IOException("Invalid PNG header"); header = data; }
            if (type.equals("acTL")) { if (length != 8) throw new IOException("Invalid APNG control"); count = integer(data, 0); }
            offset += length + 12;
            if (chunks.size() > 4096) throw new IOException("Too many PNG chunks");
            if (type.equals("IEND")) break;
        }
        if (header == null) throw new IOException("Missing PNG header");
        int w = integer(header, 0), h = integer(header, 4); dimensions(w, h);
        if (count == 0 || count > MAX_FRAMES) return null;
        if (count < 0) throw new IOException("Invalid APNG frame count");
        List<Chunk> palette = new ArrayList<>(); List<PngFrame> frames = new ArrayList<>(); PngFrame current = null;
        for (Chunk chunk : chunks) {
            byte[] d = chunk.data;
            switch (chunk.type) {
                case "PLTE", "tRNS" -> palette.add(chunk);
                case "fcTL" -> {
                    if (d.length != 26 || frames.size() >= MAX_FRAMES) throw new IOException("Invalid APNG frame");
                    if (current != null) frames.add(current);
                    int fw = integer(d, 4), fh = integer(d, 8), x = integer(d, 12), y = integer(d, 16);
                    region(w, h, x, y, fw, fh);
                    int numerator = (d[20] & 255) * 256 + (d[21] & 255), denominator = (d[22] & 255) * 256 + (d[23] & 255);
                    int dispose = d[24] & 255, blend = d[25] & 255;
                    if (dispose > 2 || blend > 1) throw new IOException("Invalid APNG blend");
                    current = new PngFrame(fw, fh, x, y, (int)(1000L * numerator / (denominator == 0 ? 100 : denominator)), dispose, blend, new ByteArrayOutputStream());
                }
                case "IDAT" -> { if (current != null) current.data.write(d); }
                case "fdAT" -> { if (current == null || d.length < 4) throw new IOException("Invalid APNG data"); current.data.write(d, 4, d.length - 4); }
            }
        }
        if (current != null) frames.add(current);
        if (frames.size() != count) throw new IOException("Missing APNG frames");
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); Animation result = null;
        for (PngFrame f : frames) {
            var out = new ByteArrayOutputStream(); out.write(PNG); byte[] fh = header.clone();
            ByteBuffer.wrap(fh).putInt(f.w).putInt(f.h); writeChunk(out, "IHDR", fh);
            for (Chunk p : palette) writeChunk(out, p.type, p.data);
            writeChunk(out, "IDAT", f.data.toByteArray()); writeChunk(out, "IEND", new byte[0]);
            BufferedImage image;
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Invalid APNG frame format");
                var reader = readers.next();
                try { reader.setInput(input); image = reader.read(0); }
                finally { reader.dispose(); }
            }
            if (image == null) throw new IOException("Invalid APNG pixels");
            int[] previous = f.dispose == 2 ? canvas.getRGB(0, 0, w, h, null, 0, w) : null;
            if (f.blend == 0) clear(canvas, f.x, f.y, f.w, f.h);
            var g = canvas.createGraphics(); g.drawImage(image, f.x, f.y, null); g.dispose();
            result = scaled(canvas, count, result == null ? List.of() : result.frames(), f.delay);
            if (f.dispose == 1) clear(canvas, f.x, f.y, f.w, f.h);
            else if (previous != null) canvas.setRGB(0, 0, w, h, previous, 0, w);
        }
        return result;
    }
    static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        var stream = new DataOutputStream(out); byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        stream.writeInt(data.length); stream.write(t); stream.write(data); CRC32 crc = new CRC32(); crc.update(t); crc.update(data); stream.writeInt((int)crc.getValue());
    }
    private static int integer(byte[] bytes, int offset) { return ByteBuffer.wrap(bytes, offset, 4).getInt(); }
    private static void region(int w, int h, int x, int y, int fw, int fh) throws IOException {
        if (x < 0 || y < 0 || fw < 1 || fh < 1 || (long)x + fw > w || (long)y + fh > h) throw new IOException("Frame outside image");
    }
    private static void clear(BufferedImage image, int x, int y, int w, int h) {
        var g = image.createGraphics(); g.setComposite(AlphaComposite.Clear); g.fillRect(x, y, w, h); g.dispose();
    }
    private static Node child(Node root, String name) {
        if (root == null) return null;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeName().equals(name)) return n;
        return null;
    }
    private static String attribute(Node n, String key, String fallback) {
        Node a = n == null || n.getAttributes() == null ? null : n.getAttributes().getNamedItem(key);
        return a == null ? fallback : a.getNodeValue();
    }
    private static int attr(Node n, String key, int fallback) {
        try { return Integer.parseInt(attribute(n, key, Integer.toString(fallback))); } catch (NumberFormatException e) { return fallback; }
    }
}
