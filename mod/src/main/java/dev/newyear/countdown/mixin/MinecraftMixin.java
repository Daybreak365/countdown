package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.JjanClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 짠 의자에 앉아 있는 동안 좌클릭은 공격·블록 파괴 대신 한 모금이다. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$sip(CallbackInfoReturnable<Boolean> cir) {
        if (JjanClient.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$noBreak(boolean down, CallbackInfo ci) {
        if (JjanClient.seated()) ci.cancel();
    }
}
