package dev.newyear.countdown.client.ai;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.ai.script.BuildTransform;
import dev.newyear.countdown.ai.script.ScriptRecord;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 건축 홀로그램 위치 잡기. 움직임은 이 클라이언트에서 바로 보이고, 서버가 똑같은 격자 변환으로 부지를 확인·확정한다.
 *
 * <p>조작은 마우스 중심이다: 바라보는 곳으로 홀로그램이 따라오고, 공격(좌클릭)으로 확정, 사용(우클릭)으로 고정/해제,
 * 휠로 높이, R 로 회전, 방향키로 한 칸씩 옮긴다. 예전 키(PageUp/PageDown/Home/Enter/Backspace)도 그대로 동작한다.
 */
public final class BuildHologram {
    private BuildHologram() {}

    private static final int MAX_LIFT = 32;
    /** 위치 잡기를 시작한 직후의 클릭은 화면을 닫던 클릭일 수 있으므로 잠깐 무시한다. */
    private static final int CLICK_GRACE_TICKS = 6;

    private static KeyMapping start, rotate, raise, lower, lock, confirm, cancel;
    private static KeyMapping nudgeForward, nudgeBack, nudgeLeft, nudgeRight;

    private record Transfer(int revision, BlockPreview.Preview[] chunks, boolean editable, boolean required, String wish) {}

    private static final Map<String, Transfer> TRANSFERS = new LinkedHashMap<>();
    private static BlockPreview.Preview saved, shown;
    private static List<ScriptRecord.BlockChange> source = List.of();
    private static String reason = "", notice = "", currentRequest = "", sent = "";
    private static int revision, x, y, z, turns, height, ticks, lastSent, blockedCount, noticeUntil, clicksFrom;
    private static boolean editable, placementRequired, active, locked, lockedBeforeCommit, valid, checked, saving, pendingStart;
    private static boolean attackWasDown, useWasDown;
    private static long savingSince;
    private static double scrollRemainder;
    private static int scrollSteps;
    private static final Set<Integer> blocked = new HashSet<>();

    public static void register() {
        var category = KeyMapping.Category.register(ModReg.id("building"));
        start = key("position", InputConstants.KEY_P, category);
        rotate = key("rotate", InputConstants.KEY_R, category);
        raise = key("raise", InputConstants.KEY_PAGEUP, category);
        lower = key("lower", InputConstants.KEY_PAGEDOWN, category);
        lock = key("lock", InputConstants.KEY_HOME, category);
        confirm = key("confirm", InputConstants.KEY_RETURN, category);
        cancel = key("cancel", InputConstants.KEY_BACKSPACE, category);
        nudgeForward = key("nudge_forward", InputConstants.KEY_UP, category);
        nudgeBack = key("nudge_back", InputConstants.KEY_DOWN, category);
        nudgeLeft = key("nudge_left", InputConstants.KEY_LEFT, category);
        nudgeRight = key("nudge_right", InputConstants.KEY_RIGHT, category);
        ClientTickEvents.END_CLIENT_TICK.register(BuildHologram::tick);
        // 위치를 잡는 동안의 클릭은 확정/고정에 쓰이므로 실제 블록을 캐거나 놓지 않게 막는다.
        AttackBlockCallback.EVENT.register((p, l, h, pos, face) -> swallow(l.isClientSide()));
        UseBlockCallback.EVENT.register((p, l, h, hit) -> swallow(l.isClientSide()));
        UseItemCallback.EVENT.register((p, l, h) -> swallow(l.isClientSide()));
        AttackEntityCallback.EVENT.register((p, l, h, e, hit) -> swallow(l.isClientSide()));
    }

    private static InteractionResult swallow(boolean clientSide) {
        return clientSide && active ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.newyearcountdown.build_" + name, InputConstants.Type.KEYBOARD, code, category));
    }

    public static boolean available() {
        return saved != null && editable;
    }

    public static boolean positioning(BlockPreview.Preview preview) {
        return active && preview == shown;
    }

    // ---------------------------------------------------------------- 서버 패킷

    public static void receive(JsonObject packet) {
        String id = packet.get("id").getAsString();
        int rev = packet.get("revision").getAsInt();
        int part = packet.get("part").getAsInt(), parts = packet.get("parts").getAsInt();
        if (parts < 1 || parts > 32 || part < 0 || part >= parts) return;
        Transfer transfer = TRANSFERS.get(id);
        if (transfer == null || transfer.revision != rev || transfer.chunks.length != parts) {
            boolean required = packet.has("placement_required") && packet.get("placement_required").getAsBoolean();
            transfer = new Transfer(rev, new BlockPreview.Preview[parts], packet.get("editable").getAsBoolean(), required,
                    packet.get("wish").getAsString());
            TRANSFERS.put(id, transfer);
            while (TRANSFERS.size() > 4) TRANSFERS.remove(TRANSFERS.keySet().iterator().next());
        }
        transfer.chunks[part] = BlockPreview.parse(packet);
        for (var chunk : transfer.chunks) if (chunk == null) return;

        List<BlockPreview.Cell> cells = new ArrayList<>();
        for (var chunk : transfer.chunks) cells.addAll(chunk.cells);
        TRANSFERS.remove(id);
        if (cells.isEmpty() || cells.size() > 4096) return;

        var first = transfer.chunks[0];
        var combined = BlockPreview.fromCells(id, first.title, first.dimension, cells);
        boolean keepEditing = active && saved != null && saved.id.equals(id) && revision == rev && transfer.editable;
        if (saved != null && !saved.id.equals(id)) BlockPreview.ACTIVE.remove(saved.id);
        saved = combined;
        revision = rev;
        editable = transfer.editable;
        placementRequired = transfer.required;
        source = new ArrayList<>();
        for (var c : cells) {
            var b = new ScriptRecord.BlockChange();
            b.dimension = first.dimension;
            b.x = c.x;
            b.y = c.y;
            b.z = c.z;
            b.after = c.state;
            b.before = "minecraft:air";
            source.add(b);
        }
        if (!keepEditing) {
            active = false;
            saving = false;
            shown = saved;
            BlockPreview.ACTIVE.put(id, saved);
        }
        if (pendingStart) {
            pendingStart = false;
            begin();
        }
    }

    public static void result(JsonObject result) {
        String request = result.get("request").getAsString();
        if (!active || currentRequest.isEmpty()) return;
        String commitRequest = currentRequest.substring(0, currentRequest.length() - 1) + "1";
        boolean commit = request.equals(commitRequest);
        if (!commit && !request.equals(currentRequest)) return;
        if (result.get("committed").getAsBoolean()) {
            active = false;
            saving = false;
            return;
        }
        checked = true;
        valid = result.get("valid").getAsBoolean();
        reason = result.has("reason") ? result.get("reason").getAsString() : "";
        blocked.clear();
        if (result.has("blocked")) for (var i : result.getAsJsonArray("blocked")) blocked.add(i.getAsInt());
        blockedCount = result.has("blocked_count") ? result.get("blocked_count").getAsInt() : blocked.size();
        if (commit) {
            // 확정이 거절됐다: 누르기 전의 따라오기/고정 상태로 돌아가 바로 다시 옮길 수 있게 한다.
            saving = false;
            locked = lockedBeforeCommit;
        }
    }

    public static void clear(String id) {
        if (id != null && id.isEmpty()) id = null;
        if (id == null || saved != null && saved.id.equals(id)) {
            saved = null;
            shown = null;
            source = List.of();
            active = false;
            editable = false;
            placementRequired = false;
            saving = false;
            pendingStart = false;
            blocked.clear();
            currentRequest = "";
            sent = "";
        }
        if (id == null) TRANSFERS.clear();
        else TRANSFERS.remove(id);
    }

    // ---------------------------------------------------------------- 시작/종료

    public static void begin() {
        Minecraft mc = Minecraft.getInstance();
        if (!available() || mc.player == null || mc.level == null) return;
        if (!saved.dimension.equals(mc.level.dimension().identifier().toString())) return;
        active = true;
        locked = false;
        saving = false;
        turns = 0;
        height = 0;
        sent = "";
        notice = "";
        scrollRemainder = 0;
        scrollSteps = 0;
        clicksFrom = ticks + CLICK_GRACE_TICKS;
        int[] pivot = BuildTransform.pivot(source);
        x = pivot[0];
        y = pivot[1];
        z = pivot[2];
        rebuild();
        mc.gui.setScreen(null);
    }

    private static void stop() {
        active = false;
        saving = false;
        if (saved != null) {
            shown = saved;
            BlockPreview.ACTIVE.put(saved.id, saved);
        }
    }

    // ---------------------------------------------------------------- 입력

    /** 휠 입력(MouseHandlerMixin). 위치를 잡는 중이면 핫바 대신 홀로그램 높이를 바꾸고 true 를 돌려준다. */
    public static boolean scroll(double amount) {
        if (!active) return false;
        scrollRemainder += amount;
        int steps = (int) scrollRemainder;
        scrollRemainder -= steps;
        scrollSteps += steps;
        return true;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level == null || mc.player == null) {
            clear(null);
            return;
        }
        // 공격/사용 클릭은 바닐라가 먼저 소비하므로 눌림 상태의 변화로 읽는다.
        boolean attackDown = mc.options.keyAttack.isDown(), useDown = mc.options.keyUse.isDown();
        boolean attackClicked = attackDown && !attackWasDown, useClicked = useDown && !useWasDown;
        attackWasDown = attackDown;
        useWasDown = useDown;

        if (mc.gui.screen() != null) {
            drain(start);
            drainControls();
            return;
        }
        while (start.consumeClick()) {
            if (active && !saving) stop();
            else if (!active && available()) begin();
            else if (!active) {
                pendingStart = true;
                AiClientState.action("build_preview_get", "");
            }
        }
        if (!active) {
            drainControls();
            return;
        }
        if (saved == null || !saved.dimension.equals(mc.level.dimension().identifier().toString())) {
            stop();
            return;
        }
        if (saving && System.currentTimeMillis() - savingSince > 5000) {
            saving = false;
            locked = lockedBeforeCommit;
            say("응답이 늦습니다. 다시 확정해 보세요.");
        }
        if (saving) {
            drainControls();
            return;
        }
        if (cancel.consumeClick()) {
            stop();
            drainControls();
            return;
        }
        boolean mouse = ticks >= clicksFrom;
        boolean changed = false;

        while (rotate.consumeClick()) {
            turns = (turns + 1) % 4;
            changed = true;
        }
        int lift = scrollSteps;
        scrollSteps = 0;
        while (raise.consumeClick()) lift++;
        while (lower.consumeClick()) lift--;
        int target = Math.max(-MAX_LIFT, Math.min(MAX_LIFT, height + lift));
        if (target != height) {
            y += target - height;
            height = target;
            changed = true;
        } else if (lift != 0) {
            say("높이는 ±" + MAX_LIFT + "칸까지 조절할 수 있습니다.");
        }

        boolean toggleLock = mouse && useClicked;
        while (lock.consumeClick()) toggleLock = !toggleLock;
        if (toggleLock) {
            locked = !locked;
            WishUi.clickSound();
        }

        // 방향키: 바라보는 방향 기준으로 한 칸씩. 미세 조정이므로 자동으로 고정한다.
        Direction facing = mc.player.getDirection();
        changed |= nudge(nudgeForward, facing);
        changed |= nudge(nudgeBack, facing.getOpposite());
        changed |= nudge(nudgeRight, facing.getClockWise());
        changed |= nudge(nudgeLeft, facing.getCounterClockWise());

        if (!locked) {
            HitResult ray = mc.player.pick(48, 1, false);
            if (ray instanceof BlockHitResult hit && ray.getType() == HitResult.Type.BLOCK) {
                BlockPos looked = hit.getBlockPos();
                BlockPos at = mc.level.getBlockState(looked).canBeReplaced() ? looked : looked.relative(hit.getDirection());
                int nx = at.getX(), ny = at.getY() + height, nz = at.getZ();
                if (x != nx || y != ny || z != nz) {
                    x = nx;
                    y = ny;
                    z = nz;
                    changed = true;
                }
            }
        }
        if (changed) rebuild();
        if (ticks - lastSent >= 5 && (!currentRequest.equals(sent) || ticks - lastSent >= 20)) {
            AiClientState.action("build_site", currentRequest);
            sent = currentRequest;
            lastSent = ticks;
        }

        boolean wantConfirm = mouse && attackClicked;
        while (confirm.consumeClick()) wantConfirm = true;
        if (wantConfirm) commit();
    }

    private static boolean nudge(KeyMapping key, Direction direction) {
        boolean moved = false;
        while (key.consumeClick()) {
            x += direction.getStepX();
            z += direction.getStepZ();
            locked = true;
            moved = true;
        }
        return moved;
    }

    /** 확인 결과를 기다리지 않고 보내도 된다: 서버가 확정 요청에서도 모든 칸을 다시 검사한다. */
    private static void commit() {
        if (checked && !valid) {
            say("빨간 칸이 막혀 있습니다. 옮기거나 높이를 바꿔 보세요.");
            return;
        }
        lockedBeforeCommit = locked;
        locked = true;
        saving = true;
        savingSince = System.currentTimeMillis();
        WishUi.clickSound();
        AiClientState.action("build_site", new BuildTransform.Request(saved.id, revision, x, y, z, turns, true).encode());
    }

    private static void say(String text) {
        notice = text;
        noticeUntil = ticks + 60;
    }

    private static void drain(KeyMapping key) {
        if (key != null) while (key.consumeClick()) {}
    }

    private static void drainControls() {
        scrollSteps = 0;
        scrollRemainder = 0;
        for (var key : new KeyMapping[]{rotate, raise, lower, lock, confirm, cancel, nudgeForward, nudgeBack, nudgeLeft, nudgeRight})
            drain(key);
    }

    private static void rebuild() {
        var transformed = BuildTransform.apply(source, x, y, z, turns, s -> BuildTransform.rotateState(s, turns));
        var cells = new ArrayList<BlockPreview.Cell>();
        for (var c : transformed) cells.add(new BlockPreview.Cell(c.x, c.y, c.z, c.after, false));
        shown = BlockPreview.fromCells(saved.id, saved.title, saved.dimension, cells);
        BlockPreview.ACTIVE.put(saved.id, shown);
        currentRequest = new BuildTransform.Request(saved.id, revision, x, y, z, turns, false).encode();
        checked = false;
        valid = false;
        blocked.clear();
        blockedCount = 0;
    }

    // ---------------------------------------------------------------- 표시

    public static int tint(BlockPreview.Preview preview, int index) {
        if (!active || preview != shown) return 0xBBDDFF;
        if (!checked) return 0x88CCFF;
        if (blocked.contains(index) || !valid && blocked.isEmpty()) return 0xFF5555;
        return valid ? 0xCCFFDD : 0xAACCFF;
    }

    /** 위치를 잡는 동안 설계 전체를 두르는 테두리 색: 확인 중 / 가능 / 막힘. */
    public static int frameColor() {
        return !checked ? 0xA8DDF0 : valid ? 0x8FD9A4 : 0xF07F7F;
    }

    private static String name(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private record Hint(String key, String label) {}

    private static List<Hint> hints(Minecraft mc) {
        boolean arrows = nudgeForward.isDefault() && nudgeBack.isDefault() && nudgeLeft.isDefault() && nudgeRight.isDefault();
        String nudgeKeys = arrows ? "방향키"
                : name(nudgeForward) + "/" + name(nudgeBack) + "/" + name(nudgeLeft) + "/" + name(nudgeRight);
        return List.of(
                new Hint(name(mc.options.keyAttack), "확정"),
                new Hint(name(mc.options.keyUse), locked ? "다시 따라오기" : "여기에 고정"),
                new Hint("휠", "높이"),
                new Hint(name(rotate), "회전"),
                new Hint(nudgeKeys, "한 칸 이동"),
                new Hint(name(cancel), "취소"));
    }

    /** 키 모양 칩 + 설명을 줄바꿈하며 배치한다. draw 가 false 면 줄 수만 센다. */
    private static int layoutHints(GuiGraphicsExtractor g, Font font, List<Hint> hints, int left, int top, int width, boolean draw) {
        int cx = left, rows = 1;
        for (Hint hint : hints) {
            int w = font.width(hint.key) + 8 + 4 + font.width(hint.label) + 10;
            if (cx > left && cx + w - 10 > left + width) {
                cx = left;
                rows++;
            }
            if (draw) {
                int cy = top + (rows - 1) * 14;
                int kw = WishUi.chip(g, font, hint.key, cx, cy, WishUi.FROST, 1f);
                g.text(font, hint.label, cx + kw + 4, cy + 2, WishUi.TEXT, false);
            }
            cx += w;
        }
        return rows;
    }

    public static void renderHud(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        Font font = mc.font;
        if (!active) {
            // 위치를 정해야 계약을 수락할 수 있는 설계가 도착해 있으면 시작하는 법을 알려 준다.
            if (!available() || !placementRequired) return;
            String key = name(start), label = "건축 위치 잡기 — 홀로그램을 지을 곳에 놓아 주세요";
            int w = font.width(key) + 8 + 4 + font.width(label) + 14, left = (g.guiWidth() - w) / 2, top = 18;
            WishUi.softPanel(g, left, top, left + w, top + 17, WishUi.GOLD, 1f);
            int kw = WishUi.chip(g, font, key, left + 6, top + 3, WishUi.GOLD, 1f);
            g.text(font, label, left + 6 + kw + 4, top + 5, WishUi.TEXT, false);
            return;
        }

        int width = Math.min(g.guiWidth() - 16, 440), left = (g.guiWidth() - width) / 2, top = 18, inner = width - 16;
        boolean bad = checked && !valid, noticing = ticks < noticeUntil && !notice.isEmpty();
        int accent = bad ? WishUi.DANGER : checked ? WishUi.SUCCESS : WishUi.FROST;
        List<Hint> hints = hints(mc);
        int rows = layoutHints(g, font, hints, left + 9, 0, inner, false);
        WishUi.softPanel(g, left, top, left + width, top + 34 + rows * 14, accent, 1f);

        // 1행: 제목 + 지금 상태(크기·회전·높이)
        String title = WishUi.GLYPH + " 건축 위치 잡기" + (locked ? " · 고정됨" : "");
        g.text(font, title, left + 9, top + 5, WishUi.GOLD, false);
        String info = "";
        if (shown != null) {
            info = (shown.maxX - shown.minX + 1) + "×" + (shown.maxY - shown.minY + 1) + "×" + (shown.maxZ - shown.minZ + 1)
                    + "칸 · 회전 " + turns * 90 + "°" + (height == 0 ? "" : " · 높이 " + (height > 0 ? "+" : "") + height);
        }
        int infoRoom = inner - font.width(title) - 8;
        if (font.width(info) <= infoRoom) g.text(font, info, left + width - 7 - font.width(info), top + 5, WishUi.TEXT_DIM, false);

        // 2행: 지을 수 있는지
        String state;
        int stateColor;
        if (saving) {
            state = "건축 위치를 확정하는 중…";
            stateColor = WishUi.FROST;
        } else if (noticing) {
            state = notice;
            stateColor = WishUi.WARNING;
        } else if (!checked) {
            state = "부지를 확인하는 중…";
            stateColor = WishUi.FROST;
        } else if (valid) {
            state = "✔ 여기에 지을 수 있습니다 · " + x + ", " + y + ", " + z;
            stateColor = WishUi.SUCCESS;
        } else {
            state = "✖ " + (blockedCount > 0 ? "막힌 칸 " + blockedCount + "개 · " : "") + reason;
            stateColor = WishUi.DANGER;
        }
        g.text(font, font.plainSubstrByWidth(state, inner), left + 9, top + 17, stateColor, false);

        // 3행~: 조작 안내
        layoutHints(g, font, hints, left + 9, top + 30, inner, true);
    }
}
