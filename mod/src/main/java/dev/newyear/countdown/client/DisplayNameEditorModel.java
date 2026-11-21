package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DisplayNameStyle;
import dev.newyear.countdown.discord.DisplayNameStyle.Run;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Small rich-text document. All editing offsets are UTF-16, at Unicode code-point boundaries. */
public final class DisplayNameEditorModel {
    private DisplayNameStyle value;
    private final ArrayDeque<DisplayNameStyle> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    public DisplayNameEditorModel(DisplayNameStyle value) { this.value = value; }
    public DisplayNameStyle value() { return value; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void undo() { if (canUndo()) { redo.push(value); value = undo.pop(); } }
    public void redo() { if (canRedo()) { undo.push(value); value = redo.pop(); } }

    private void commit(List<Run> runs) {
        DisplayNameStyle next = DisplayNameStyle.ofRuns(runs);
        if (next.equals(value)) return;
        undo.push(value);
        while (undo.size() > 64) undo.removeLast();
        redo.clear();
        value = next;
    }

    public void setText(String next) {
        String old = value.text();
        if (old.equals(next)) return;
        int prefix = 0;
        while (prefix < old.length() && prefix < next.length() && old.codePointAt(prefix) == next.codePointAt(prefix))
            prefix += Character.charCount(old.codePointAt(prefix));
        int oldEnd = old.length(), newEnd = next.length();
        while (oldEnd > prefix && newEnd > prefix && old.codePointBefore(oldEnd) == next.codePointBefore(newEnd)) {
            int chars = Character.charCount(old.codePointBefore(oldEnd));
            oldEnd -= chars;
            newEnd -= chars;
        }
        replace(prefix, oldEnd, next.substring(prefix, newEnd));
    }

    /** Exact edit range avoids confusing identical letters that have different formatting. */
    public void replace(int prefix, int oldEnd, String insert) {
        String old = value.text();
        checkBoundary(old, prefix);
        checkBoundary(old, oldEnd);
        if (prefix > oldEnd) throw new IllegalArgumentException("잘못된 선택 범위입니다.");
        List<Run> original = value.effectiveRuns();
        int offset = 0;
        // Typing inside a run expands its existing gradient, instead of restarting it at each new letter.
        for (int i = 0; i < original.size(); i++) {
            Run run = original.get(i);
            int limit = offset + run.text().length();
            if (prefix >= offset && prefix <= limit && oldEnd <= limit) {
                List<Run> updated = new ArrayList<>(original);
                updated.set(i, run.withText(run.text().substring(0, prefix - offset) + insert + run.text().substring(oldEnd - offset)));
                commit(updated);
                return;
            }
            offset = limit;
        }
        List<Run> updated = new ArrayList<>(slice(0, prefix));
        Run inherited = at(oldEnd > prefix ? prefix : Math.max(0, prefix - 1));
        if (!insert.isEmpty()) updated.add(inherited.withText(insert));
        updated.addAll(slice(oldEnd, old.length()));
        commit(updated);
    }

    public Run at(int index) {
        int offset = 0;
        for (Run run : value.effectiveRuns()) {
            offset += run.text().length();
            if (index < offset) return run;
        }
        return value.effectiveRuns().isEmpty() ? new Run("", 0xFFFFFF, 0x83C9CE, false, false) : value.effectiveRuns().getLast();
    }

    public void apply(int from, int to, int start, int end, boolean gradient, boolean bold) {
        checkBoundary(value.text(), from);
        checkBoundary(value.text(), to);
        if (from >= to) throw new IllegalArgumentException("서식을 적용할 글자를 먼저 선택하세요.");
        List<Run> updated = new ArrayList<>(slice(0, from));
        updated.add(new Run(value.text().substring(from, to), start, end, gradient, bold));
        updated.addAll(slice(to, value.text().length()));
        commit(updated);
    }

    private List<Run> slice(int from, int to) {
        List<Run> selected = new ArrayList<>();
        int offset = 0;
        for (Run run : value.effectiveRuns()) {
            int a = Math.max(0, from - offset), b = Math.min(run.text().length(), to - offset);
            if (a < b) selected.add(a == 0 && b == run.text().length() ? run : run.slice(a, b));
            offset += run.text().length();
        }
        return selected;
    }

    private static void checkBoundary(String text, int index) {
        if (index < 0 || index > text.length() || (index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index))))
            throw new IllegalArgumentException("글자 중간을 나눌 수 없습니다.");
    }
}
