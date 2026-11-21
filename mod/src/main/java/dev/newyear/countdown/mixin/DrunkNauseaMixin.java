package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.JjanClient;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 짠의 취기를 바닐라 멀미(화면 일렁임)에 잇는다. 바닐라는 멀미 효과의 세기(0..1)를 이 함수로 읽어
 * 화면 왜곡(GameRenderer), 일렁이는 속도(LocalPlayer), 왜곡을 끈 사람의 초록 덧씌움(Hud)에 쓴다.
 * 내 플레이어의 취기가 높을수록 큰 값을 돌려주므로, 실제 멀미 효과 없이도 취한 만큼 화면이 어지럽다.
 * 접근성 설정의 "왜곡 효과" 배율은 바닐라가 그대로 적용한다.
 */
@Mixin(LivingEntity.class)
public abstract class DrunkNauseaMixin {
    @Inject(method = "getEffectBlendFactor", at = @At("RETURN"), cancellable = true)
    private void newyearcountdown$drunkNausea(Holder<MobEffect> effect, float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!effect.equals(MobEffects.NAUSEA)) return;
        float drunk = JjanClient.nausea(this, partialTicks);
        if (drunk > cir.getReturnValueF()) cir.setReturnValue(drunk);
    }
}
