package dev.newyear.countdown.client;

import com.mojang.blaze3d.Blaze3D;
import dev.newyear.countdown.ModReg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 촬영이 끝나면 뜨는 화면: 방금 저장한 네컷 사진 띠를 보여 주고, 저장 폴더를 열 수 있다.
 * 부스 안에서 찍었으면(inBooth) 닫을 때 다시 촬영 화면으로 돌아간다(밖으로 나가는 건 나가기 버튼).
 */
public class PhotoResultScreen extends Screen {
    private static int serial;

    private final PhotoStrip.Result result;
    private final boolean inBooth;
    private Identifier texture;
    private DynamicTexture dynamic;
    private final long openNanos = System.nanoTime();

    public PhotoResultScreen(PhotoStrip.Result result, boolean inBooth) {
        super(Component.translatable("photobooth.newyearcountdown.result.title"));
        this.result = result;
        this.inBooth = inBooth;
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (inBooth && PhotoBoothSession.isActive()) PhotoBoothSession.switchScreen(mc, new PhotoBoothScreen());
        else super.onClose();
    }

    @Override
    protected void init() {
        if (texture == null && result.image() != null) {
            texture = ModReg.id("photobooth/result_" + (serial++));
            dynamic = new DynamicTexture(() -> "photobooth result", result.image());
            Minecraft.getInstance().getTextureManager().register(texture, dynamic);
        }
        int bx = width / 2 + 70, by = height / 2 - 22;
        addRenderableWidget(Button.builder(Component.translatable("photobooth.newyearcountdown.result.open"),
                b -> {
                    PhotoStrip.folder().mkdirs();
                    Blaze3D.openPath(PhotoStrip.folder().toPath());
                }).bounds(bx, by, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(inBooth ? "photobooth.newyearcountdown.result.back" : "photobooth.newyearcountdown.result.close"), b -> onClose())
                .bounds(bx, by + 26, 110, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);   // 이미지도 함께 닫힌다
        texture = null;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        float t = Mth.clamp((System.nanoTime() - openNanos) / 1.0e9f / 0.6f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t);
        int h = height - 30;
        int w = Math.round(h * PhotoStrip.W / (float) PhotoStrip.H);
        int x = width / 2 - 70 - w + 40;
        int y = 15 + Math.round((1f - ease) * -h);   // 출력구에서 나오듯 위에서 내려온다
        ctx.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF000000 | 0x202020);
        if (texture != null) ctx.blit(texture, x, y, x + w, y + h, 0f, 1f, 0f, 1f);
        ctx.centeredText(font, title, width / 2 + 125, height / 2 - 60, 0xFFFF7FA6);
        ctx.centeredText(font, Component.literal(result.file().getName()), width / 2 + 125, height / 2 - 44, 0xFFC8C8D0);
    }
}
