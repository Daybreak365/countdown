package dev.newyear.countdown.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.newyear.countdown.discord.DiscordChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** In online mode replace the skin face; offline mode keeps the inline avatar. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @ModifyExpressionValue(method = "getNameForDisplay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/PlayerInfo;getTabListDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component newyearcountdown$singleAvatar(Component name) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.onlineMode() ? DiscordChat.withoutTabAvatar(name) : name;
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerFaceExtractor;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IIIZZI)V"))
    private void newyearcountdown$discordFace(GuiGraphicsExtractor graphics, Identifier skin, int x, int y, int size,
                                             boolean hat, boolean upsideDown, int color, Operation<Void> original,
                                             @Local PlayerInfo player) {
        Component avatar = DiscordChat.tabAvatar(player.getTabListDisplayName());
        if (avatar == null) original.call(graphics, skin, x, y, size, hat, upsideDown, color);
        else graphics.text(Minecraft.getInstance().font, avatar, x, y, color, false);
    }
}
