package dev.newyear.countdown.ai.openai;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.script.Textures;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/** Converts a generated PNG to the existing tiny, palette-based world texture format. Worker-thread only. */
public final class TextureImages {
    public static final int MAX_PNG_BYTES = 12 * 1024 * 1024;
    private TextureImages() {}

    public static String prompt(String subject, int pixels) {
        return "Create exactly one original Minecraft vanilla inventory ITEM SPRITE: " + subject + "\n"
                + "Mandatory art direction: draw on a strict " + pixels + "x" + pixels + " logical pixel grid, then enlarge it "
                + "with nearest-neighbor sampling to fill the square output. Use chunky square pixels, a readable simple silhouette, "
                + "restrained earthy/mineral colors, a maximum of 24 colors, 2-3 stepped shade levels per material and subtle top-left lighting. "
                + "Match the visual density of vanilla Minecraft items. Flat orthographic inventory icon, tools may run diagonally. "
                + "Keep the entire item centered with one logical pixel of transparent padding on every side. "
                + "True transparent alpha background, including any holes. No checkerboard or solid background. "
                + "No text, border, UI, multiple views, scenery, drop shadow, bloom, gradients, anti-aliasing, photorealism, "
                + "3D render, smooth vector curves, glossy illustration or details smaller than one logical pixel. "
                + "The subject description only specifies the object; retain these pixel-art constraints.";
    }

    public static JsonObject request(String model, String quality, String subject, int pixels, String safetyId) {
        JsonObject body = new JsonObject();
        body.addProperty("model",model); body.addProperty("prompt",prompt(subject,pixels));
        body.addProperty("n",1); body.addProperty("size","1024x1024");
        body.addProperty("quality",quality); body.addProperty("background","transparent");
        body.addProperty("output_format","png");
        if (safetyId != null) body.addProperty("safety_identifier",safetyId);
        return body;
    }

    public static Textures.Def decode(String base64, String id, int size) throws IOException {
        if (size != 16 && size != 32) throw new IllegalArgumentException("Texture grid must be 16 or 32");
        if (base64 == null || base64.length() > MAX_PNG_BYTES * 4L / 3 + 4) throw new IOException("Generated image is too large or missing");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException ex) { throw new IOException("Image response is not valid base64"); }
        if (bytes.length > MAX_PNG_BYTES || bytes.length < 8 || bytes[0] != (byte)137 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G')
            throw new IOException("Image response must be a bounded PNG");
        BufferedImage image;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Cannot decode generated PNG");
            var reader = readers.next();
            try {
                reader.setInput(input,true,true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < size || h < size || w > 2048 || h > 2048 || w != h) throw new IOException("Expected a square PNG at most 2048x2048");
                image = reader.read(0);
            } finally { reader.dispose(); }
        }
        return pixelate(image,id,size);
    }

    static Textures.Def pixelate(BufferedImage image, String id, int size) throws IOException {
        int[] pixels = new int[size * size];
        Map<Integer,Integer> histogram = new HashMap<>();
        int opaque = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int x0=x*image.getWidth()/size, x1=(x+1)*image.getWidth()/size;
            int y0=y*image.getHeight()/size, y1=(y+1)*image.getHeight()/size;
            int step=Math.max(1,Math.min(x1-x0,y1-y0)/8), total=0, solid=0;
            Map<Integer,Integer> colors=new TreeMap<>();
            for (int sy=y0+step/2;sy<y1;sy+=step) for (int sx=x0+step/2;sx<x1;sx+=step) {
                int c=image.getRGB(sx,sy); total++;
                if ((c>>>24)<128) continue;
                solid++;
                // Snap to a coarse color grid before picking the cell's dominant color; no blurred edge pixels.
                int rgb=(((c>>20)&15)*17<<16)|(((c>>12)&15)*17<<8)|(((c>>4)&15)*17);
                colors.merge(rgb,1,Integer::sum);
            }
            if (solid == 0 || solid*2 < total) continue;
            int rgb=colors.entrySet().stream().max(Comparator.<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparingInt(Map.Entry::getKey)).orElseThrow().getKey();
            pixels[y*size+x]=0xff000000|rgb; opaque++; histogram.merge(rgb,1,Integer::sum);
        }
        if (opaque < 2) throw new IOException("Generated sprite is empty or too small");
        if (opaque == pixels.length) throw new IOException("Generated sprite has no transparent background; regenerate it with alpha transparency");
        List<Integer> palette=histogram.entrySet().stream().sorted(Comparator.<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(Map.Entry::getKey)).limit(24).map(Map.Entry::getKey).toList();
        Textures.Def def=new Textures.Def(); def.id=id; def.w=size; def.h=size;
        StringBuilder mapping=new StringBuilder();
        for (int i=0;i<palette.size();i++) {
            if (i>0) mapping.append(';');
            mapping.append((char)('A'+i)).append('=').append(String.format(Locale.ROOT,"ff%06x",palette.get(i)));
        }
        def.palette=mapping.toString();
        for (int y=0;y<size;y++) {
            StringBuilder row=new StringBuilder();
            for (int x=0;x<size;x++) {
                int c=pixels[y*size+x];
                if (c==0) {row.append('.');continue;}
                int best=0,distance=Integer.MAX_VALUE;
                for (int i=0;i<palette.size();i++) {
                    int p=palette.get(i),dr=((c>>16)&255)-((p>>16)&255),dg=((c>>8)&255)-((p>>8)&255),db=(c&255)-(p&255);
                    int d=dr*dr+dg*dg+db*db;
                    if (d<distance) {distance=d;best=i;}
                }
                row.append((char)('A'+best));
            }
            def.rows.add(row.toString());
        }
        return def;
    }
}
