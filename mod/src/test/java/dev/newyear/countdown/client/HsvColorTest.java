package dev.newyear.countdown.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HsvColorTest {
    @Test void hueStripPassesThroughPrimaryAndSecondaryColorsAndWrapsToRed() {
        int[] expected = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], HsvColor.rgb(i / 6.0, 1, 1));
    }

    @Test void saturationValueAreaHasWhiteHueAndBlackCorners() {
        assertEquals(0xFFFFFF, HsvColor.rgb(2 / 3.0, 0, 1));
        assertEquals(0x0000FF, HsvColor.rgb(2 / 3.0, 1, 1));
        assertEquals(0x000000, HsvColor.rgb(2 / 3.0, 0, 0));
        assertEquals(0x000000, HsvColor.rgb(2 / 3.0, 1, 0));
        assertEquals(0x404080, HsvColor.rgb(2 / 3.0, 0.5, 0.5));
    }

    @Test void openingAndAcceptingPickerDoesNotChangeExistingHexColors() {
        for (int r = 0; r <= 255; r += 17)
            for (int g = 0; g <= 255; g += 17)
                for (int b = 0; b <= 255; b += 17) {
                    int rgb = r << 16 | g << 8 | b;
                    assertEquals(rgb, new HsvColor(rgb).rgb(), String.format("#%06X", rgb));
                }
        for (int rgb : new int[]{0x83C9CE, 0xF3BE74, 0xE994A5, 0x9AB6F2, 0x010203, 0xFEFDFC})
            assertEquals(rgb, new HsvColor(rgb).rgb());
    }

    @Test void hueRemainsEditableAtBlackAndGray() {
        var color = new HsvColor(0xFFFFFF);
        color.setHue(2 / 3.0);
        assertEquals(0xFFFFFF, color.rgb());
        color.setRgb(0x000000);
        assertEquals(2 / 3.0, color.hue());
        color.setSaturationValue(1, 1);
        assertEquals(0x0000FF, color.rgb());
        color.setRgb(0x808080);
        assertEquals(2 / 3.0, color.hue());
        assertEquals(0x808080, color.rgb());
    }

    @Test void draggingOutsideTheAreaClampsToItsEdges() {
        var color = new HsvColor(0xFFFFFF);
        color.setHue(-5); assertEquals(0, color.hue());
        color.setHue(5); assertEquals(1, color.hue());
        color.setSaturationValue(4, -4);
        assertEquals(1, color.saturation()); assertEquals(0, color.value());
        assertEquals(0, color.rgb());
        color.setSaturationValue(-4, 4);
        assertEquals(0xFFFFFF, color.rgb());
    }
}
