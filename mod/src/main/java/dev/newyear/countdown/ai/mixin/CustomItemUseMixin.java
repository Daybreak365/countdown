package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.content.ItemAppearance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-consuming hold pose for custom ability items built on inert vanilla items. */
@Mixin(Item.class)
public class CustomItemUseMixin {
    @Inject(method="getUseAnimation", at=@At("HEAD"), cancellable=true)
    private void wishAnimation(ItemStack stack, CallbackInfoReturnable<ItemUseAnimation> cir) {
        var animation=ItemAppearance.useAnimation(stack);
        if (animation!=ItemUseAnimation.NONE) cir.setReturnValue(animation);
    }
    @Inject(method="getUseDuration", at=@At("HEAD"), cancellable=true)
    private void wishDuration(ItemStack stack, LivingEntity user, CallbackInfoReturnable<Integer> cir) {
        if (ItemAppearance.useAnimation(stack)!=ItemUseAnimation.NONE) cir.setReturnValue(72000);
    }
    @Inject(method="use", at=@At("HEAD"), cancellable=true)
    private void wishUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (ItemAppearance.useAnimation(player.getItemInHand(hand))==ItemUseAnimation.NONE) return;
        player.startUsingItem(hand);
        cir.setReturnValue(InteractionResult.CONSUME);
    }
}
