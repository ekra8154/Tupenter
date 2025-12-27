package net.tupenter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

public class TupenterModClient implements ClientModInitializer {
	public static String lastMessage = "";
	private static KeyMapping resendKey;

	@Override
	public void onInitializeClient() {
		resendKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.resend",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath(TupenterMod.MOD_ID, "general"))
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (resendKey.consumeClick()) {
				if (!lastMessage.isEmpty() && client.player != null) {
					if (lastMessage.startsWith("/")) {
						client.player.connection.sendCommand(lastMessage.substring(1));
					} else {
						client.player.connection.sendChat(lastMessage);
					}
				}
			}
		});
	}
}