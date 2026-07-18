package net.tupenter.mixin.client;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Layout internals ChatSelection needs to map mouse coordinates to chat lines. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> tupenter$trimmedMessages();

    @Accessor("chatScrollbarPos")
    int tupenter$scrollPos();

    @Invoker("getLineHeight")
    int tupenter$lineHeight();
}
