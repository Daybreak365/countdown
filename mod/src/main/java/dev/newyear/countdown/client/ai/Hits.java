package dev.newyear.countdown.client.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 프레임 동안 그린 클릭 영역 목록. 화면은 그리면서 영역을 등록하고, 클릭이 오면 맨 먼저 등록된 영역부터 찾는다.
 */
final class Hits {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final List<Hit> list = new ArrayList<>();

    void clear() {
        list.clear();
    }

    void add(int x, int y, int w, int h, Runnable action) {
        list.add(new Hit(x, y, w, h, action));
    }

    /** 커서 아래 첫 영역의 동작을 실행한다. 반환: 실행했는지. */
    boolean click(double mx, double my) {
        // 동작이 화면을 바꾸며 목록을 건드릴 수 있으므로 복사본을 돈다
        for (Hit h : new ArrayList<>(list)) {
            if (WishUi.inside(mx, my, h.x, h.y, h.w, h.h)) {
                h.action.run();
                return true;
            }
        }
        return false;
    }
}
