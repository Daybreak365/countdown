package dev.newyear.countdown.client;

import org.junit.jupiter.api.Test;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaImageTest {
    private static BufferedImage solid(int w, int h, int color) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); int[] pixels = new int[w * h]; Arrays.fill(pixels, color);
        image.setRGB(0, 0, w, h, pixels, 0, w); return image;
    }
    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    @Test void pngPreservesTransparencyAndLargeImagesAreDownsampled() throws Exception {
        var image = MediaImage.decode(png(solid(800, 400, 0x8044AAFF)));
        assertEquals(192, image.width()); assertEquals(96, image.height()); assertEquals(1, image.frames().size());
        int pixel = image.frames().getFirst().pixels()[0]; assertEquals(128, pixel >>> 24);
        // Bilinear scaling rounds premultiplied alpha channels to the nearest byte.
        for (int shift : new int[]{0, 8, 16}) assertTrue(Math.abs(((pixel >> shift) & 255) - ((0x44AAFF >> shift) & 255)) <= 1);
    }
    @Test void gifAnimationUsesFrameDurationsAndLoops() throws Exception {
        byte[] bytes = gif(); var image = MediaImage.decode(bytes);
        assertEquals(3, image.frames().size());
        assertEquals(0xFFFF0000, image.frames().get(0).pixels()[0]);
        assertEquals(0xFF00FF00, image.frames().get(1).pixels()[0]);
        assertEquals(0xFF0000FF, image.frames().get(2).pixels()[0]);
        assertEquals(0, image.frameAt(99)); assertEquals(1, image.frameAt(100));
        assertEquals(2, image.frameAt(300)); assertEquals(0, image.frameAt(600));
    }
    private static byte[] gif() throws Exception {
        var bytes = new ByteArrayOutputStream(); var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out); writer.prepareWriteSequence(null);
            int index = 1;
            for (int color : new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF}) {
                var image = solid(4, 4, color); var param = writer.getDefaultWriteParam();
                var meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
                var tree = (IIOMetadataNode)meta.getAsTree("javax_imageio_gif_image_1.0");
                var control = (IIOMetadataNode)tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", Integer.toString(index++ * 10)); control.setAttribute("disposalMethod", "restoreToBackgroundColor");
                meta.setFromTree("javax_imageio_gif_image_1.0", tree);
                writer.writeToSequence(new IIOImage(image, null, meta), param);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
    @Test void apngComposesPartialFramesAndRestoresPreviousCanvas() throws Exception {
        var image = MediaImage.decode(apng());
        assertEquals(4, image.frames().size());
        assertArrayEquals(new int[]{0xFFFF0000, 0xFFFF0000}, image.frames().get(0).pixels());
        assertArrayEquals(new int[]{0xFF0000FF, 0xFFFF0000}, image.frames().get(1).pixels());
        // Frame 2 restores the red canvas before frame 3 draws on the right.
        assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00}, image.frames().get(2).pixels());
        // SOURCE blending with transparent pixels removes the underlying red pixel.
        assertArrayEquals(new int[]{0, 0xFF00FF00}, image.frames().get(3).pixels());
    }
    private static byte[] apng() throws Exception {
        byte[] base = png(solid(2, 1, 0xFFFF0000)); var out = new ByteArrayOutputStream(); out.write(base, 0, 8);
        MediaImage.writeChunk(out, "IHDR", Arrays.copyOfRange(base, 16, 29));
        MediaImage.writeChunk(out, "acTL", ByteBuffer.allocate(8).putInt(4).putInt(0).array());
        int sequence = 0;
        for (int i = 0; i < 4; i++) {
            int w = i == 0 ? 2 : 1, x = i == 2 ? 1 : 0;
            var control = ByteBuffer.allocate(26).putInt(sequence++).putInt(w).putInt(1).putInt(x).putInt(0)
                    .putShort((short)1).putShort((short)10).put((byte)(i == 1 ? 2 : 0)).put((byte)0).array();
            MediaImage.writeChunk(out, "fcTL", control);
            byte[] frame = png(solid(w, 1, new int[]{0xFFFF0000, 0xFF0000FF, 0xFF00FF00, 0}[i]));
            var compressed = new ByteArrayOutputStream();
            for (int off = 8; off < frame.length;) {
                int length = ByteBuffer.wrap(frame, off, 4).getInt();
                if (new String(frame, off + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")) compressed.write(frame, off + 8, length);
                off += length + 12;
            }
            if (i == 0) MediaImage.writeChunk(out, "IDAT", compressed.toByteArray());
            else MediaImage.writeChunk(out, "fdAT", ByteBuffer.allocate(4 + compressed.size()).putInt(sequence++).put(compressed.toByteArray()).array());
        }
        MediaImage.writeChunk(out, "IEND", new byte[0]); return out.toByteArray();
    }
    @Test void malformedAndOversizedInputsAreRejectedBeforeLargePixelAllocation() throws Exception {
        assertThrows(IOException.class, () -> MediaImage.decode(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, () -> MediaImage.readBounded(new ByteArrayInputStream(new byte[MediaImage.MAX_BYTES + 1])));
        byte[] damaged = png(solid(2, 2, -1)); damaged[20] ^= 1;
        assertThrows(IOException.class, () -> MediaImage.decode(damaged));
        var huge = new ByteArrayOutputStream(); huge.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        MediaImage.writeChunk(huge, "IHDR", ByteBuffer.allocate(13).putInt(100000).putInt(100000).put((byte)8).put((byte)6).put(new byte[3]).array());
        MediaImage.writeChunk(huge, "IEND", new byte[0]);
        assertThrows(IOException.class, () -> MediaImage.decode(huge.toByteArray()));
        assertThrows(IOException.class, () -> MediaImage.decode(Arrays.copyOf(apng(), 35)));
    }
    @Test void downloadSubscriberCancelsWhenAChunkWouldExceedItsBudget() {
        var subscriber = new MediaDownload.LimitedBody(5); var subscription = new java.util.concurrent.Flow.Subscription() {
            boolean cancelled;
            @Override public void request(long n) {}
            @Override public void cancel() { cancelled = true; }
        };
        subscriber.onSubscribe(subscription); subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        assertTrue(subscription.cancelled); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        var good = new MediaDownload.LimitedBody(5); good.onSubscribe(subscription); good.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3,4,5}))); good.onComplete();
        assertArrayEquals(new byte[]{1,2,3,4,5}, good.getBody().toCompletableFuture().join());
    }
    @Test void imageTilesJoinWithoutGapsAtEveryChatSpacingAndMaintainAspectRatio() {
        for (double spacing : new double[]{0, .25, .5, 1}) for (int width : new int[]{28, 64, 128}) {
            int rows = MediaLayout.rows(width, 640, 360), line = MediaLayout.lineHeight(spacing);
            float lastUv = 0, lastBottom = -1, shownWidth = 0, top = -1;
            for (int row = 0; row < rows; row++) {
                var tile = MediaLayout.slice(width, rows, row, line, 640, 360);
                if (tile == null) continue;
                assertEquals(lastUv, tile.v0(), .0001); lastUv = tile.v1();
                float absoluteTop = row * line + tile.top();
                if (lastBottom >= 0) assertEquals(lastBottom, absoluteTop, .0001); else top = absoluteTop;
                lastBottom = row * line + tile.bottom(); shownWidth = tile.right() - tile.left();
                assertTrue(tile.top() >= 0); assertTrue(tile.bottom() <= line); assertTrue(tile.right() <= width);
            }
            assertEquals(1, lastUv, .0001); assertEquals(640.0 / 360, shownWidth / (lastBottom - top), .0001);
        }
    }
}
