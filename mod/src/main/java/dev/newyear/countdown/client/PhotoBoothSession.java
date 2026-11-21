package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.newyear.countdown.ModSounds;
import dev.newyear.countdown.photo.PhotoBooth;
import dev.newyear.countdown.photo.PhotoBoothBlockEntity;
import dev.newyear.countdown.photo.PhotoBoothLayout;
import java.io.File;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 포토부스 촬영 세션 (클라이언트).
 *
 * - 시점: 입구를 누르거나 부스 안에서 우클릭하면 카메라가 키오스크 쪽으로 부드럽게 옮겨 가(BellCamera) 실내를 비추는 미리보기가 된다.
 *   화면이 열려 있어도 WASD·점프·웅크리기로 부스 안에서 자리를 옮길 수 있지만, 입구 커튼이 막혀(PhotoBooth.LOCKED)
 *   나가기 버튼(또는 Esc)을 눌러야만 밖으로 나간다. 촬영이 끝나 결과를 본 뒤에도 부스 안 화면으로 돌아온다.
 *   HUD 와 이름표는 숨기고, 시야각은 화면의 촬영 틀(PhotoBoothScreen)이 사진 한 컷과 같은 화각이 되도록 맞춘다.
 * - 촬영: 서버가 보낸 시작 시각 기준으로 같은 시간표(PhotoBooth.captureTick)에 맞춰 각자의 클라이언트가 찍는다.
 *   찍는 순간에는 화면 UI 를 한두 프레임 숨긴 뒤 그 프레임을 캡처하고, 촬영 틀 영역만 잘라 사진 한 컷으로 만든다.
 * - 네 컷이 모이면 PhotoStrip 이 프레임을 입혀 한 장의 PNG 로 저장하고 결과 화면을 연다(닫으면 다시 촬영 화면).
 */
public final class PhotoBoothSession {
    /** 화면 위아래 막대와 좌우 패널 폭 (GUI 단위). 촬영 틀은 그 사이에 사진 비율로 놓인다. */
    public static final int BAR = 30, SIDE = 104;
    public static final int PHOTO_W = 540, PHOTO_H = 372;

    private static boolean active;
    private static boolean fovOverride;
    private static BlockPos pos;
    private static Direction facing = Direction.NORTH;
    private static boolean hidHud;

    // 내가 고른 것 (다음에 다시 들어와도 유지)
    private static int myPose, filter;
    /** 촬영 화면 ↔ 결과 화면을 바꾸는 중: 화면의 removed() 가 세션을 끝내지 않게 한다. */
    private static boolean switching;

    // 촬영
    private static boolean shooting;
    private static long shootStart;
    private static int shootFrame, shootFilter;
    private static List<String> names = List.of();
    private static final NativeImage[] SHOTS = new NativeImage[PhotoBooth.SHOTS];
    private static int nextShot;
    private static boolean hiding, grabbing;
    private static int hiddenFrames;
    private static long flashNanos;
    private static int lastBeep = -1;
    private static int afterTicks;
    private static PhotoStrip.Result pending;
    private static boolean saving;

    private PhotoBoothSession() {}

    public static boolean isActive() {
        return active;
    }

    /** 카메라·이름표·시야각을 바꾸고 있는 중인지 (나올 때 전환 포함). */
    public static boolean isViewing() {
        return active || fovOverride;
    }

    public static boolean isShooting() {
        return shooting;
    }

    public static BlockPos pos() {
        return pos;
    }

    public static int myPose() { return myPose; }
    public static int filter() { return filter; }
    public static int shotsTaken() { return nextShot; }
    public static boolean saving() { return saving || pending != null; }

    public static PhotoBoothBlockEntity booth(Minecraft mc) {
        if (mc.level == null || pos == null) return null;
        return mc.level.getBlockEntity(pos) instanceof PhotoBoothBlockEntity be ? be : null;
    }

    // ------------------------------------------------------------------ 들어가기 / 나오기

    /** 서버가 열어 준 부스 (OpenS2C). */
    public static void open(Minecraft mc, BlockPos p) {
        if (mc.level == null || mc.player == null) return;
        if (!(mc.level.getBlockEntity(p) instanceof PhotoBoothBlockEntity be)) return;
        if (active && p.equals(pos)) {
            if (!(mc.gui.screen() instanceof PhotoBoothScreen)) mc.gui.setScreen(new PhotoBoothScreen());
            return;
        }
        if (active) exit(true);   // 이전 화면을 먼저 닫아야 그 화면의 removed() 가 새 세션을 끝내지 않는다
        if (BellSession.isActive()) return;
        pos = p.immutable();
        facing = be.facing();
        active = true;
        fovOverride = true;
        Vec3 cam = PhotoBoothLayout.toWorld(pos, facing, 0, PhotoBooth.CAM_Y, PhotoBooth.CAM_Z);
        Vec3 look = PhotoBoothLayout.toWorld(pos, facing, 0, PhotoBooth.LOOK_Y, PhotoBooth.LOOK_Z);
        BellCamera.begin(cam, look);
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
            hidHud = true;
        }
        faceCamera(mc.player);
        sendLook();
        mc.gui.setScreen(new PhotoBoothScreen());
    }

    private static void faceCamera(LocalPlayer p) {
        float yaw = PhotoBoothLayout.cameraFacingYaw(facing);
        p.setYRot(yaw);
        p.setXRot(0f);
        p.yRotO = yaw;
        p.xRotO = 0f;
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
        p.yHeadRotO = yaw;
        p.yBodyRotO = yaw;
    }

    /** 부스 시점에서 나온다. closeScreen 이면 촬영 화면도 닫는다. */
    public static void exit(boolean closeScreen) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        active = false;
        abortShoot();
        passMovement(mc, false);
        BellCamera.end();
        if (hidHud) {
            if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            hidHud = false;
        }
        if (mc.player != null) PhotoPose.set(mc.player.getUUID(), 0);
        if (ClientPlayNetworking.canSend(PhotoBooth.LookC2S.ID)) ClientPlayNetworking.send(new PhotoBooth.LookC2S(0));
        if (closeScreen && (mc.gui.screen() instanceof PhotoBoothScreen || mc.gui.screen() instanceof PhotoResultScreen)) mc.gui.setScreen(null);
    }

    /** 나가기 버튼(또는 Esc): 서버가 입구 바깥으로 옮겨 주고, 시점도 돌아온다. */
    public static void leave() {
        if (!active) return;
        BlockPos p = pos;
        exit(true);
        if (p != null && ClientPlayNetworking.canSend(PhotoBooth.LeaveC2S.ID)) ClientPlayNetworking.send(new PhotoBooth.LeaveC2S(p));
    }

    public static boolean switching() {
        return switching;
    }

    /** 촬영 화면과 결과 화면을 오간다 (세션은 그대로). */
    static void switchScreen(Minecraft mc, net.minecraft.client.gui.screens.Screen next) {
        switching = true;
        try {
            mc.gui.setScreen(next);
        } finally {
            switching = false;
        }
    }

    /** 접속 종료: 모두 초기화. */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (hidHud && mc.gui != null && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        hidHud = false;
        active = false;
        fovOverride = false;
        abortShoot();
        if (pending != null && pending.image() != null) pending.image().close();
        pending = null;
        saving = false;
        pos = null;
    }

    private static void abortShoot() {
        shooting = false;
        hiding = false;
        grabbing = false;
        nextShot = 0;
        lastBeep = -1;
        for (int i = 0; i < SHOTS.length; i++) {
            if (SHOTS[i] != null) SHOTS[i].close();
            SHOTS[i] = null;
        }
    }

    /**
     * 촬영 화면이 열려 있어도 이동·점프·웅크리기 키는 그대로 통하게 한다(화면이 키 입력을 가로채므로 직접 읽어 넣는다).
     * 미리보기를 보면서 자리를 옮기고 점프샷도 찍을 수 있다. 몸은 계속 카메라를 향한다.
     */
    private static void passMovement(Minecraft mc, boolean on) {
        Options o = mc.options;
        for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift}) {
            boolean down = false;
            if (on) {
                InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(k);
                down = key.getType() == InputConstants.Type.KEYBOARD && key.getValue() > 0 && InputConstants.isKeyDown(key.getValue());
            }
            k.setDown(down);
        }
    }

    // ------------------------------------------------------------------ 고르기

    public static void choosePose(int pose) {
        myPose = Mth.clamp(pose, 0, PhotoBooth.POSES - 1);
        sendLook();
    }

    public static void chooseFilter(int f) {
        filter = Math.floorMod(f, PhotoBooth.FILTERS);
    }

    public static void chooseFrame(int frame) {
        if (pos != null && !shooting) ClientPlayNetworking.send(new PhotoBooth.FrameC2S(pos, Math.floorMod(frame, PhotoBooth.FRAMES)));
    }

    public static void requestShoot() {
        if (pos != null && !shooting && !saving()) ClientPlayNetworking.send(new PhotoBooth.ShootC2S(pos, filter));
    }

    private static void sendLook() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) PhotoPose.set(mc.player.getUUID(), myPose);
        if (ClientPlayNetworking.canSend(PhotoBooth.LookC2S.ID)) ClientPlayNetworking.send(new PhotoBooth.LookC2S(myPose));
    }

    // ------------------------------------------------------------------ 촬영

    /** 서버: 촬영 시작 (부스 안의 참가자 모두에게). */
    public static void onShoot(Minecraft mc, PhotoBooth.ShootS2C s) {
        if (!active || !s.pos().equals(pos)) open(mc, s.pos());
        if (!active || !s.pos().equals(pos)) return;
        if (shooting && shootStart == s.start()) return;
        // 촬영 도중 다시 들어온 경우: 이미 지난 컷을 엉뚱한 순간에 찍지 않도록 이번 촬영은 보기만 한다
        if (mc.level != null && mc.level.getGameTime() - s.start() > PhotoBooth.FIRST_SHOT - 20) return;
        abortShoot();
        shooting = true;
        shootStart = s.start();
        shootFrame = s.frame();
        shootFilter = s.filter();
        names = List.copyOf(s.names());
        pending = null;
    }

    public static float age(Minecraft mc, float partial) {
        if (mc.level == null) return -1f;
        return mc.level.getGameTime() - shootStart + partial;
    }

    /** 찍는 순간: 촬영 화면이 아무것도 그리지 않아야 한다. */
    public static boolean hidingForCapture() {
        return hiding;
    }

    public static void frameRendered() {
        if (hiding) hiddenFrames++;
    }

    /** 마지막 셔터 뒤 흰 번쩍임 (0..1). */
    public static float flash() {
        float t = (System.nanoTime() - flashNanos) / 1.0e9f / 0.35f;
        return t >= 1f ? 0f : 1f - t;
    }

    public static void tick(Minecraft mc) {
        if (fovOverride && !active && BellCamera.blend() <= 0f) fovOverride = false;
        if (!active) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        PhotoBoothBlockEntity be = booth(mc);
        if (be == null || !player.isAlive() || !PhotoBoothLayout.interior(pos, facing).inflate(1.0).contains(player.position())) {
            exit(true);
            return;
        }
        boolean booth = mc.gui.screen() instanceof PhotoBoothScreen;
        if (!booth && !(mc.gui.screen() instanceof PhotoResultScreen)) {
            exit(false);
            return;
        }
        BellCamera.enforcePerspective();
        passMovement(mc, booth);

        if (pending != null && booth && ++afterTicks >= 18) {
            PhotoStrip.Result r = pending;
            pending = null;
            switchScreen(mc, new PhotoResultScreen(r, true));
            return;
        }
        if (!shooting) return;

        long age = mc.level.getGameTime() - shootStart;
        if (nextShot < PhotoBooth.SHOTS) {
            int cap = PhotoBooth.captureTick(nextShot);
            long left = cap - age;
            if (left > 0 && left <= 60) {
                int sec = (int) ((left + 19) / 20);
                if (sec != lastBeep) {
                    lastBeep = sec;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.PHOTO_BEEP, sec == 1 ? 1.5f : 1.0f, 0.8f));
                }
            }
            if (!hiding && !grabbing && age >= cap) {
                hiding = true;
                hiddenFrames = 0;
            }
        }
        if (hiding && !grabbing && hiddenFrames >= 2) {
            grabbing = true;
            int shot = nextShot;
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> mc.execute(() -> {
                try {
                    onCaptured(mc, shot, img);
                } finally {
                    img.close();
                }
            }));
        }
        if (age > PhotoBooth.TOTAL + 200) {   // 창이 최소화돼 프레임이 그려지지 않는 등 촬영이 끝나지 못한 경우
            abortShoot();
            player.sendSystemMessage(Component.translatable("photobooth.newyearcountdown.failed").withStyle(ChatFormatting.RED));
        }
    }

    private static void onCaptured(Minecraft mc, int shot, NativeImage img) {
        grabbing = false;
        hiding = false;
        if (!active || !shooting || shot != nextShot) return;
        int[] c = cropPixels(img.getWidth(), img.getHeight(), mc.getWindow().getGuiScale());
        NativeImage photo = new NativeImage(PHOTO_W, PHOTO_H, false);
        img.resizeSubRectTo(c[0], c[1], c[2], c[3], photo);
        SHOTS[shot] = photo;
        nextShot++;
        flashNanos = System.nanoTime();
        if (nextShot >= PhotoBooth.SHOTS) finishShoot(mc);
    }

    private static void finishShoot(Minecraft mc) {
        shooting = false;
        NativeImage[] shots = SHOTS.clone();
        for (int i = 0; i < SHOTS.length; i++) SHOTS[i] = null;
        saving = true;
        afterTicks = 0;
        PhotoStrip.saveAsync(shots, shootFrame, shootFilter, names, result -> {
            saving = false;
            if (result == null) {
                if (mc.player != null) mc.player.sendSystemMessage(Component.translatable("photobooth.newyearcountdown.failed").withStyle(ChatFormatting.RED));
                return;
            }
            File file = result.file();
            if (mc.player != null) {
                Component link = Component.literal(file.getName()).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.AQUA)
                        .withStyle(st -> st.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenFile(file.getAbsoluteFile())));
                mc.player.sendSystemMessage(Component.translatable("photobooth.newyearcountdown.saved", link).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            if (active) pending = result;
            else mc.gui.setScreen(new PhotoResultScreen(result, false));
        });
    }

    // ------------------------------------------------------------------ 화각

    /** 화면(프레임버퍼 픽셀)에서 촬영 틀 {x, y, w, h}. 화면 UI 와 캡처가 같은 계산을 쓴다. */
    public static int[] cropPixels(int w, int h, int guiScale) {
        int bar = BAR * guiScale, side = SIDE * guiScale;
        double availH = Math.max(40, h - 2.0 * bar), availW = Math.max(60, w - 2.0 * side);
        double ch = Math.min(availH, availW / PhotoBooth.PHOTO_ASPECT);
        double cw = ch * PhotoBooth.PHOTO_ASPECT;
        int x = (int) Math.round((w - cw) / 2), y = (int) Math.round((h - ch) / 2);
        return new int[]{x, y, (int) Math.round(cw), (int) Math.round(ch)};
    }

    /** 카메라 시야각 덮어쓰기: 촬영 틀 높이가 사진 한 컷의 세로 화각(50도)이 되게 화면 전체 화각을 키운다. */
    public static float fov(float base) {
        if (!fovOverride) return base;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) return base;
        int[] c = cropPixels(w, h, mc.getWindow().getGuiScale());
        double half = Math.toRadians(PhotoBooth.PHOTO_HALF_FOV);
        float target = (float) Math.toDegrees(2 * Math.atan(Math.tan(half) * h / Math.max(1, c[3])));
        return Mth.lerp(BellCamera.blend(), base, Math.min(target, 150f));
    }
}
