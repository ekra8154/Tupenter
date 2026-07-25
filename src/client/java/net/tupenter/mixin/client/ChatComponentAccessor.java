package net.tupenter.mixin.client;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Chat internals for two features: layout mapping (ChatSelection turns mouse
 * coordinates into chat lines) and help-page navigation (the /tupenter help
 * pager deletes the previous page's messages so pages REPLACE each other
 * instead of piling up).
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> tupenter$trimmedMessages();

    @Accessor("allMessages")
    List<GuiMessage> tupenter$allMessages();

    @Invoker("refreshTrimmedMessages")
    void tupenter$refreshTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int tupenter$scrollPos();

    @Invoker("getLineHeight")
    int tupenter$lineHeight();

    // getScale/getWidth were public through 1.21.10 and went private at 1.21.11.
    // Reaching them through the accessor works on both, so the call sites don't
    // have to care which version they're compiled against.
    @Invoker("getScale")
    double tupenter$scale();

    @Invoker("getWidth")
    int tupenter$width();
}
