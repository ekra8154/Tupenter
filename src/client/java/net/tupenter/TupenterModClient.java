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
	private static int timeDown = 0;
	private static net.minecraft.client.gui.screens.Screen lastScreen = null;
	private static int gracePeriod = 0;

	@Override
	public void onInitializeClient() {
		resendKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.resend",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath(TupenterMod.MOD_ID, "general"))
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Check for screen transition from ChatScreen to null (gameplay)
			if (lastScreen instanceof net.minecraft.client.gui.screens.ChatScreen && client.screen == null) {
				gracePeriod = 10; // 0.5 seconds grace period
			}
			lastScreen = client.screen;

			if (gracePeriod > 0) {
				gracePeriod--;
				// Reset key state during grace period to prevent accidental triggers
				timeDown = 0;
				// Consume clicks during grace period so they don't buffer
				while (resendKey.consumeClick()) { /* no-op */ }
				return;
			}

			if (resendKey.isDown()) {
				timeDown++;
				if (timeDown == 1 || timeDown > 5) {
					if (!lastMessage.isEmpty() && client.player != null) {
						if (lastMessage.startsWith("/")) {
							client.player.connection.sendCommand(lastMessage.substring(1));
						} else {
							client.player.connection.sendChat(lastMessage);
						}
					}
				}
			} else {
				timeDown = 0;
			}
			
			// Consume any buffered clicks
			while (resendKey.consumeClick()) { /* no-op */ }
		});
	}
}