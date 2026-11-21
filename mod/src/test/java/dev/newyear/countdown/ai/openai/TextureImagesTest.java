package dev.newyear.countdown.ai.openai;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class TextureImagesTest {
    static String png(BufferedImage image) throws IOException {
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private BufferedImage sprite(int scale) {
        var image=new BufferedImage(16*scale,16*scale,BufferedImage.TYPE_INT_ARGB);
        for(int y=2*scale;y<14*scale;y++)for(int x=6*scale;x<10*scale;x++)image.setRGB(x,y,0xffffcc44);
        return image;
    }
    @Test void preservesPixelGridSilhouetteAndRealAlpha() throws Exception {
        var def=TextureImages.decode(png(sprite(64)),"img_wand",16);
        assertEquals(16,def.w);assertEquals(16,def.h);assertEquals(16,def.rows.size());
        assertEquals("................",def.rows.getFirst());
        assertEquals("......AAAA......",def.rows.get(8));
        assertTrue(def.palette.matches("A=ff[0-9a-f]{6}"));
    }
    @Test void quantizesDetailedImagesToAtMost24ColorsWithoutPartialAlpha() throws Exception {
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=1;y<31;y++)for(int x=1;x<31;x++)image.setRGB(x,y,0xff000000|((x*7)<<16)|((y*7)<<8)|((x+y)*3));
        var def=TextureImages.decode(png(image),"img",32);
        assertTrue(def.palette.split(";").length<=24);
        assertTrue(def.palette.matches("[A-X]=ff[0-9a-f]{6}(;[A-X]=ff[0-9a-f]{6})*"));
        assertTrue(def.rows.stream().allMatch(r->r.matches("[.A-X]{32}")));
    }
    @Test void rejectsInvalidOversizedOpaqueAndEmptyImages() throws Exception {
        assertThrows(IOException.class,()->TextureImages.decode("not-base64","x",16));
        assertThrows(IOException.class,()->TextureImages.decode("A".repeat(TextureImages.MAX_PNG_BYTES*4/3+5),"x",16));
        var opaque=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
        assertThrows(IOException.class,()->TextureImages.decode(png(opaque),"x",16));
        assertThrows(IOException.class,()->TextureImages.decode(png(new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB)),"x",16));
        assertThrows(IOException.class,()->TextureImages.decode(png(new BufferedImage(2049,16,BufferedImage.TYPE_INT_ARGB)),"x",16));
        assertThrows(IllegalArgumentException.class,()->TextureImages.decode(png(sprite(1)),"x",64));
    }
    @Test void requestKeepsTheSelectedImageModelAndOmitsLegacyResponseFormat() {
        var body=TextureImages.request("gpt-image-2.5-sunburst","high","oak wand with an emerald tip",16,"hashed-owner");
        assertEquals("gpt-image-2.5-sunburst",body.get("model").getAsString());
        assertEquals("transparent",body.get("background").getAsString());
        assertEquals("png",body.get("output_format").getAsString());assertEquals(1,body.get("n").getAsInt());
        assertEquals("1024x1024",body.get("size").getAsString());assertFalse(body.has("response_format"));
        assertTrue(body.get("prompt").getAsString().contains("16x16"));
    }
}
