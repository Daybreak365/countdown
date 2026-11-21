package dev.newyear.countdown.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("allMessages") List<GuiMessage> newyearcountdown$messages();
    @Accessor("trimmedMessages") List<GuiMessage.Line> newyearcountdown$lines();
    @Accessor("chatScrollbarPos") int newyearcountdown$scroll();
    @Accessor("chatScrollbarPos") void newyearcountdown$scroll(int value);
    @Accessor("newMessageSinceScroll") boolean newyearcountdown$unread();
    @Accessor("newMessageSinceScroll") void newyearcountdown$unread(boolean value);
    @Invoker("refreshTrimmedMessages") void newyearcountdown$refresh();
}
