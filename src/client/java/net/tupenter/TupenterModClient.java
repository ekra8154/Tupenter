package net.tupenter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.tupenter.config.TupenterConfig;
import net.tupenter.compat.ModMenuIntegration;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

public class TupenterModClient implements ClientModInitializer {
	public static String lastMessage = "";
	private static KeyMapping resendKey;
	private static KeyMapping configKey;
    public static boolean isFiring = false; // Public for Mixin access
    private static long lastChatCloseTime = 0;
    private static boolean isToggledOn = false;
    private static boolean wasKeyDown = false; // For edge detectioncraft.client.gui.screens.Screen lastScreen = null;
    private static int keyHoldTicks = 0;

    // History tracking
    public static String lastChat = "";
    public static String lastCommand = "";
    public static String lockedMessage = ""; // For toggle mode locking

    public static void updateLastMessage(String msg) {
        lastMessage = msg;
        if (msg.startsWith("/")) {
            lastCommand = msg;
        } else {
            lastChat = msg;
        }
    }

	@Override
	public void onInitializeClient() {
		KeyMapping.Category tupenterCategory = new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath("tupenter", "general"));

		resendKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.resend",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			tupenterCategory
		));

		configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.config",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			tupenterCategory
		));

		// Load Config
		TupenterConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Grace period check
             if (client.screen instanceof ChatScreen) {
                lastChatCloseTime = 0; // Reset while chat is open
            } else if (lastChatCloseTime == 0 && client.screen == null) { // Corrected client.currentScreen to client.screen
                // Chat just closed
                lastChatCloseTime = System.currentTimeMillis();
            }

            boolean gracePeriodActive = (System.currentTimeMillis() - lastChatCloseTime) < (TupenterConfig.INSTANCE.gracePeriod * 50L); /* 50ms per tick approx */

            boolean isKeyDown = resendKey.isDown();
            boolean shouldSend = false;

            // Determine the target message to use
            String targetMessage = lastMessage;
            if (TupenterConfig.INSTANCE.rememberLastValid) {
                switch (TupenterConfig.INSTANCE.resendFilter) {
                    case CHAT_ONLY:
                        targetMessage = lastChat;
                        break;
                    case COMMANDS_ONLY:
                        targetMessage = lastCommand;
                        break;
                    case BOTH:
                    default:
                        targetMessage = lastMessage;
                        break;
                }
            }

            // Toggle/Mode Logic
            if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                 if (isKeyDown && !wasKeyDown) {
                    // Toggle on/off on fresh press
                    isToggledOn = !isToggledOn;
                    
                    // Lock message on start if configured
                    if (isToggledOn && !TupenterConfig.INSTANCE.updateInToggle) {
                        lockedMessage = targetMessage;
                    }
                 }
                 
                 // HUD Notification on state change (edge detection for visual only)
                 if (isKeyDown && !wasKeyDown && client.player != null) {
                     Component status = isToggledOn 
                        ? Component.translatable("tupenter.toggle.on").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("tupenter.toggle.off").withStyle(ChatFormatting.RED);
                     
                     Component msg = Component.translatable("tupenter.toggle.prefix")
                        .withStyle(ChatFormatting.WHITE)
                        .append(status);

                     client.player.displayClientMessage(msg, true);
                 }

                 shouldSend = isToggledOn;
            } else if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                // Press and Hold mode
                shouldSend = isKeyDown;
                isToggledOn = false;
            } else {
                // OFF mode
                shouldSend = false;
                isToggledOn = false;
            }

            // Apply Grace Period (Ignore if Toggled On)
            if (gracePeriodActive && !isToggledOn) {
                 keyHoldTicks = 0;
                 while (resendKey.consumeClick()) { /* no-op */ }
                 return;
            }

            // Apply Lock overrides
            if (shouldSend && TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE && !TupenterConfig.INSTANCE.updateInToggle) {
                // Late Latch: If matched empty, try to grab the first valid message sent
                if (lockedMessage.isEmpty() && !targetMessage.isEmpty()) {
                    lockedMessage = targetMessage;
                }
                targetMessage = lockedMessage;
            }

            wasKeyDown = isKeyDown; // Track previous state for edge detection
            isFiring = shouldSend; // Update public state for Mixin

            if (shouldSend) {
                keyHoldTicks++;
                boolean isToggleMode = TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE;
                boolean shouldFireNow = false;

                if (isToggleMode) {
                    // Toggle Mode logic: Fire every (resendDelay + 1) ticks
                    // keyHoldTicks increments every tick while active
                    shouldFireNow = (keyHoldTicks - 1) % (TupenterConfig.INSTANCE.resendDelay + 1) == 0;
                } else {
                    // Press and Hold:
                    // 1. Fire on first tick
                    // 2. Wait for rapidResendDelay (initial delay)
                    // 3. Then fire every (resendDelay + 1) ticks
                    if (keyHoldTicks == 1) {
                        shouldFireNow = true;
                    } else if (keyHoldTicks > TupenterConfig.INSTANCE.rapidResendDelay) {
                        long activeTicks = keyHoldTicks - TupenterConfig.INSTANCE.rapidResendDelay;
                        shouldFireNow = (activeTicks - 1) % (TupenterConfig.INSTANCE.resendDelay + 1) == 0;
                    }
                }

                if (shouldFireNow) {
                   if (TupenterConfig.INSTANCE.usePermanentMessage) {
                        // Permanent Message Mode
                        int packets = Math.max(1, TupenterConfig.INSTANCE.resendAmount);
                         for (int i = 0; i < packets; i++) {
                            for (String msg : TupenterConfig.INSTANCE.permanentMessages) {
                                if (msg == null || msg.trim().isEmpty()) continue;
                                
                                if (msg.startsWith("/")) {
                                    client.player.connection.sendCommand(msg.substring(1));
                                } else {
                                    client.player.connection.sendChat(msg);
                                }
                            }
                         }
                   } else if (!targetMessage.isEmpty()) {
                        // Standard History Mode
                        boolean isCommand = targetMessage.startsWith("/");
                        boolean allowed = true;

                        switch (TupenterConfig.INSTANCE.resendFilter) {
                            case CHAT_ONLY:
                                if (isCommand) allowed = false;
                                break;
                            case COMMANDS_ONLY:
                                if (!isCommand) allowed = false;
                                break;
                            case BOTH:
                            default:
                                allowed = true;
                                break;
                        }

                        if (allowed) {
                            int packets = Math.max(1, TupenterConfig.INSTANCE.resendAmount);
                            for (int i = 0; i < packets; i++) {
                                if (isCommand) {
                                    client.player.connection.sendCommand(targetMessage.substring(1));
                                } else {
                                    client.player.connection.sendChat(targetMessage);
                                }
                            }
                        }
                    }
                }
            } else {
                keyHoldTicks = 0;
            }
            
            // Consume any buffered clicks
            while (resendKey.consumeClick()) { /* no-op */ }

            while (configKey.consumeClick()) {
                client.setScreen(ModMenuIntegration.createScreen(client.screen));
            }
        });
	}
}