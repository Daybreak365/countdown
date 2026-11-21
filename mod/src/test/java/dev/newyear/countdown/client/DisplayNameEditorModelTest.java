package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DisplayNameStyle;
import dev.newyear.countdown.discord.DisplayNameStyle.Run;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DisplayNameEditorModelTest {
    @Test void gradientAcrossSelectedSpacesPreservesTextSelectionAndHistory() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.plain("새벽 별 빛"));
        var before = model.value();
        model.apply(1, 6, 0xF3BE74, 0x83C9CE, true, false);
        assertEquals("새벽 별 빛", model.value().checked().text());
        assertEquals("벽 별 빛", model.value().effectiveRuns().getLast().text());
        assertTrue(model.at(2).gradient());
        assertTrue(model.at(4).gradient());
        var formatted = model.value();
        model.undo(); assertEquals(before, model.value());
        model.redo(); assertEquals(formatted, model.value());
    }

    @Test void deletingTheFirstOfTwoIdenticalLettersPreservesTheSecondLettersStyle() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.ofRuns(List.of(
                new Run("가", 0xFF0000, 0xFF0000, false, false),
                new Run("가", 0x0000FF, 0x0000FF, false, false))));
        model.replace(0, 1, "");
        assertEquals("가", model.value().text());
        assertEquals(0x0000FF, model.at(0).startColor());
        model.undo();
        model.replace(1, 2, "");
        assertEquals(0xFF0000, model.at(0).startColor());
    }

    @Test void independentlyStylesKoreanSelectionsAndKeepsSurroundingText() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.plain("새벽 별빛"));
        model.apply(0, 2, 0xFF0000, 0x0000FF, true, true);
        model.apply(3, 5, 0x00FF00, 0x00FF00, false, false);
        assertEquals("새벽 별빛", model.value().checked().text());
        assertEquals(3, model.value().effectiveRuns().size());
        assertTrue(model.at(0).gradient());
        assertTrue(model.at(0).bold());
        assertEquals(0xFFFFFF, model.at(2).startColor());
        assertEquals(0x00FF00, model.at(3).startColor());
    }

    @Test void insertionInsideGradientExpandsOneRunAndDeletionPreservesOtherRuns() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.ofRuns(List.of(
                new Run("새벽", 0xFF0000, 0x0000FF, true, false),
                new Run("별", 0x00FF00, 0x00FF00, false, false))));
        model.setText("새하벽별");
        assertEquals(2, model.value().effectiveRuns().size());
        assertEquals("새하벽", model.value().effectiveRuns().getFirst().text());
        assertEquals(0x0000FF, model.at(1).endColor());
        model.setText("새벽별");
        assertEquals("새벽", model.value().effectiveRuns().getFirst().text());
        assertEquals(0x00FF00, model.at(2).startColor());
    }

    @Test void replacingAcrossRunsRetainsUnselectedPrefixAndSuffix() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.ofRuns(List.of(
                new Run("AB", 0xFF0000, 0xFF0000, false, true),
                new Run("CD", 0x00FF00, 0x00FF00, false, false))));
        model.setText("A별D");
        assertEquals("A별D", model.value().checked().text());
        assertEquals(0xFF0000, model.at(0).startColor());
        assertEquals(0x00FF00, model.at(2).startColor());
    }

    @Test void splittingAGradientKeepsItsRemainingColorIntervals() {
        var model = new DisplayNameEditorModel(new DisplayNameStyle("abcd", 0, 0xFFFFFF, true, false));
        model.apply(1, 3, 0xFF0000, 0xFF0000, false, false);
        var runs = model.value().effectiveRuns();
        assertEquals(0, runs.getFirst().startColor());
        assertEquals(0x404040, runs.getFirst().endColor());
        assertEquals(0xBFBFBF, runs.getLast().startColor());
        assertEquals(0xFFFFFF, runs.getLast().endColor());
    }

    @Test void emojiSelectionCannotSplitASurrogatePairAndUndoRestoresFormatting() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.plain("별🌟빛"));
        assertThrows(IllegalArgumentException.class, () -> model.apply(1, 2, 0, 0xFFFFFF, true, false));
        model.apply(1, 3, 0xFF0000, 0x0000FF, true, true);
        var formatted = model.value();
        assertEquals("🌟", model.value().effectiveRuns().get(1).text());
        model.setText("별🌟달빛");
        model.undo();
        assertEquals(formatted, model.value());
        model.undo();
        assertEquals(1, model.value().effectiveRuns().size());
        model.redo();
        assertEquals(formatted, model.value());
        model.setText("별🌟");
        assertFalse(model.canRedo());
    }

    @Test void emptyDocumentCanBeRetypedAndHistoryIsBounded() {
        var model = new DisplayNameEditorModel(DisplayNameStyle.plain("가"));
        model.setText("");
        model.setText("새");
        assertEquals("새", model.value().checked().text());
        for (int i = 0; i < 100; i++) model.setText("이름" + i);
        int count = 0;
        while (model.canUndo()) { model.undo(); count++; }
        assertEquals(64, count);
    }
}
