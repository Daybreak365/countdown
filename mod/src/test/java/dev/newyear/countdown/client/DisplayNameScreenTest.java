package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.TextInputManager;
import dev.newyear.countdown.discord.DisplayNamePackets;
import dev.newyear.countdown.discord.DisplayNameStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real editor widgets and mouse dispatch without starting a window, audio or GPU. */
class DisplayNameScreenTest {
    private Minecraft previousClient;

    @BeforeEach void headlessClient() throws Exception {
        previousClient = Minecraft.getInstance();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        set(Minecraft.class, null, "instance", client);
        set(Minecraft.class, client, "lastInputType", InputType.MOUSE);
        set(Minecraft.class, client, "font", new Font(null) {
            @Override public int width(String text) { return text.length() * 7; }
            @Override public int width(FormattedText text) { return width(text.getString()); }
            @Override public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width / 7)));
            }
            @Override public String plainSubstrByWidth(String text, int width, boolean fromEnd) {
                int length = Math.min(text.length(), Math.max(0, width / 7));
                return fromEnd ? text.substring(text.length() - length) : text.substring(0, length);
            }
        });
        set(Minecraft.class, client, "textInputManager", new TextInputManager(null) {
            @Override public void onTextInputFocusChange(Object owner, boolean focused) {}
        });
        set(Minecraft.class, client, "gui", unsafe.allocateInstance(Gui.class));
    }

    @AfterEach void restoreClient() throws Exception { set(Minecraft.class, null, "instance", previousClient); }

    @Test void reverseSelectionKeepsPickedStartAndEndColorsAfterReturningAndMovingMouse() throws Exception {
        var initial = new DisplayNameStyle("새벽 별 빛", 0xFFFFFF, 0x83C9CE, true, false);
        var screen = screen(initial);
        select(screen, initial.text().length(), 0);
        returnFromPicker(screen, "startField", "#EF6666");
        assertSelection(screen, 0, initial.text().length(), 0);
        assertEquals("#EF6666", field(screen, "startField", EditBox.class).getValue());
        returnFromPicker(screen, "endField", "#9AB6F2");
        assertSelection(screen, 0, initial.text().length(), 0);
        invoke(screen, "applySelection");
        var model = field(screen, "document", DisplayNameEditorModel.class);
        assertEquals(0xEF6666, model.at(0).startColor());
        assertEquals(0x9AB6F2, model.at(0).endColor());
        assertTrue(model.at(0).gradient());
        model.undo(); assertEquals(initial, model.value());
    }

    @Test void bothDirectionsPreservePartialSelectionWithSpacesAndEmoji() throws Exception {
        for (boolean reverse : new boolean[]{false, true}) {
            var screen = screen(DisplayNameStyle.plain("앞 새🌟 별 뒤"));
            select(screen, reverse ? 7 : 2, reverse ? 2 : 7);
            returnFromPicker(screen, "startField", "#E994A5");
            assertSelection(screen, 2, 7, reverse ? 2 : 7);
            invoke(screen, "applySelection");
            var model = field(screen, "document", DisplayNameEditorModel.class);
            assertEquals(0xFFFFFF, model.at(0).startColor());
            assertEquals(0xE994A5, model.at(2).startColor());
            assertEquals(0xFFFFFF, model.at(7).startColor());
            assertEquals("앞 새🌟 별 뒤", model.value().checked().text());
        }
    }

    @Test void cancellingPickerAndResizingPreserveAnUnappliedColorDraft() throws Exception {
        var screen = screen(DisplayNameStyle.plain("새벽 별빛"));
        select(screen, 5, 0);
        field(screen, "startField", EditBox.class).setValue("#A3D2A5");
        // Cancel does not call the picker callback, but still returns to the same editor.
        returnFromPicker(screen, null, null);
        screen.width = 640;
        rebuild(screen);
        invoke(screen, "selectionChanged");
        assertSelection(screen, 0, 5, 0);
        assertEquals("#A3D2A5", field(screen, "startField", EditBox.class).getValue());
        invoke(screen, "applySelection");
        assertEquals(0xA3D2A5, field(screen, "document", DisplayNameEditorModel.class).at(0).startColor());
    }

    @Test void colorChosenBeforeTheNextRenderIsNotOverwrittenBySelectionPolling() throws Exception {
        var screen = screen(DisplayNameStyle.plain("새벽 별빛"));
        var name = field(screen, "nameField", RichNameEditBox.class);
        name.moveCursorToEnd(false);
        name.moveCursorToStart(true);
        // No render/selectionChanged between the final drag and the color click.
        field(screen, "startField", EditBox.class).setValue("#F3BE74");
        invoke(screen, "selectionChanged");
        assertEquals("#F3BE74", field(screen, "startField", EditBox.class).getValue());
        returnFromPicker(screen, null, null);
        invoke(screen, "applySelection");
        assertEquals(0xF3BE74, field(screen, "document", DisplayNameEditorModel.class).at(0).startColor());
    }

    private static DisplayNameScreen screen(DisplayNameStyle style) {
        var screen = new DisplayNameScreen(new DisplayNamePackets.OpenS2C(UUID.randomUUID(), "Player", style, ""));
        screen.width = 480; screen.height = 300;
        screen.init();
        return screen;
    }

    private static void select(DisplayNameScreen screen, int anchor, int cursor) throws Exception {
        var field = field(screen, "nameField", RichNameEditBox.class);
        field.moveCursorTo(anchor, false);
        field.moveCursorTo(cursor, true);
        invoke(screen, "selectionChanged");
    }

    private static void returnFromPicker(DisplayNameScreen screen, String colorField, String hex) throws Exception {
        // ContainerEventHandler sets this AFTER the palette button opens another screen.
        // Its mouse-up goes to the picker, so the parent never receives that release.
        screen.setDragging(true);
        if (colorField != null) field(screen, colorField, EditBox.class).setValue(hex);
        rebuild(screen);
        // The confirm click can return while the mouse is still held. It must not extend the name selection.
        screen.mouseDragged(new MouseButtonEvent(450, 270, new MouseButtonInfo(1, 0)), 2, 0);
        invoke(screen, "selectionChanged");
    }

    private static void rebuild(DisplayNameScreen screen) throws Exception {
        var action = Screen.class.getDeclaredMethod("rebuildWidgets");
        action.setAccessible(true); action.invoke(screen);
    }

    private static void assertSelection(DisplayNameScreen screen, int from, int to, int cursor) throws Exception {
        var field = field(screen, "nameField", RichNameEditBox.class);
        assertEquals(from, field.selectionStart());
        assertEquals(to, field.selectionEnd());
        assertEquals(cursor, field.getCursorPosition());
        assertFalse(screen.isDragging());
    }

    private static <T> T field(DisplayNameScreen screen, String name, Class<T> type) throws Exception {
        Field field = DisplayNameScreen.class.getDeclaredField(name); field.setAccessible(true);
        return type.cast(field.get(screen));
    }
    private static void invoke(DisplayNameScreen screen, String method) throws Exception {
        var action = DisplayNameScreen.class.getDeclaredMethod(method); action.setAccessible(true); action.invoke(screen);
    }
    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
