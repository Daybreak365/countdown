package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.DiscordMediaClient;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reveal in the existing chat screen without opening a browser, running a command or sending a packet. */
@Mixin(ChatScreen.class)
public abstract class ChatSpoilerMixin extends Screen {
    @Shadow private ChatComponent.DisplayMode displayMode;
    protected ChatSpoilerMixin(Component title) { super(title); }

    // 26.3 handles ordinary chat links on right-click. Preserve that route for our local action too.
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$revealSpoiler(Style style, boolean insertion, CallbackInfoReturnable<Boolean> cir) {
        if (!insertion && DiscordMediaClient.handleSpoilerClick(style.getClickEvent())) cir.setReturnValue(true);
    }

    // Also allow the natural left-click on the cover, after command suggestions had their turn.
    @Inject(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"), cancellable = true)
    private void newyearcountdown$leftClickSpoiler(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0 || minecraft.hasShiftDown()) return;
        var finder = new ActiveTextCollector.ClickableStyleFinder(getFont(), (int)event.x(), (int)event.y());
        minecraft.gui.hud.getChat().captureClickableText(finder, minecraft.getWindow().getGuiScaledHeight(), minecraft.gui.hud.getGuiTicks(), displayMode);
        Style style = finder.result();
        if (style != null && DiscordMediaClient.handleSpoilerClick(style.getClickEvent())) cir.setReturnValue(true);
    }
}
