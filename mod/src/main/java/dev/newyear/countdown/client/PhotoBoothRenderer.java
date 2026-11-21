package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.photo.PhotoBoothBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** 포토부스 블록 엔티티 렌더러: 구조물 좌표계로 옮긴 뒤 PhotoBoothModel 을 그린다. */
public class PhotoBoothRenderer implements BlockEntityRenderer<PhotoBoothBlockEntity, BeState<PhotoBoothBlockEntity>> {
    private final Font font;

    public PhotoBoothRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.font();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public BeState<PhotoBoothBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(PhotoBoothBlockEntity be, BeState<PhotoBoothBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<PhotoBoothBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        PhotoBoothBlockEntity be = state.be;
        Level world = be == null ? null : be.getLevel();
        if (world == null) return;
        GeoBuffers buffers = new GeoBuffers();
        float time = world.getGameTime() + state.partialTicks;
        float age = (world.getGameTime() - be.animStart) + state.partialTicks;
        int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(world, be.getBlockPos().above()), LightCoordsUtil.pack(12, 0));
        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-be.facing().toYRot()));
        boolean cameraView = PhotoBoothSession.isViewing() && be.getBlockPos().equals(PhotoBoothSession.pos());
        PhotoBoothModel.draw(buffers, m, font, light, be.frame(), age, time, cameraView);
        m.popPose();
        buffers.flush(collector);
    }
}
