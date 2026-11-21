package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.ai.BuildHologram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 건축 홀로그램 위치를 잡는 동안에는 마우스 휠이 핫바 대신 홀로그램 높이를 바꾼다. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$hologramScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || mc.gui.overlay() != null || mc.gui.screen() != null) return;
        // 일부 환경은 Shift+휠을 가로 스크롤로 보낸다.
        double amount = yOffset != 0 ? yOffset : xOffset;
        if (amount != 0 && BuildHologram.scroll(amount)) ci.cancel();
    }
}
