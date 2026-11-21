package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.discord.DisplayNames;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PlayerTabNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void newyearcountdown$profile(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(DisplayNames.tabName((ServerPlayer) (Object) this, cir.getReturnValue()));
    }
}
