package net.tupenter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
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
    private static KeyMapping recordHistoryKey;
    public static boolean isFiring = false; // Public for Mixin access
    private static long lastChatCloseTime = 0;
    private static boolean isToggledOn = false;
    private static int keyHoldTicks = 0;

    // Queue System
    public static final Queue<String> pendingQueue = new LinkedList<>();
    public static int delayTimer = 0;

    // History tracking
    public static final List<String> messageHistory = new ArrayList<>();
    public static List<String> lockedBatch = new ArrayList<>(); // For toggle mode locking

    public static void updateLastMessage(String msg) {
        if (!TupenterConfig.INSTANCE.recordHistory) return;
        if (msg == null || msg.trim().isEmpty()) return;

        // Apply Resend Filter at recording time
        boolean isCommand = msg.startsWith("/");
        switch (TupenterConfig.INSTANCE.resendFilter) {
            case CHAT_ONLY:
                if (isCommand) return;
                break;
            case COMMANDS_ONLY:
                if (!isCommand) return;
                break;
            case BOTH:
            default:
                break;
        }
        
        // Avoid duplicates at top of stack (optional, but good UX)
        if (!messageHistory.isEmpty() && messageHistory.get(messageHistory.size() - 1).equals(msg)) return;

        messageHistory.add(msg);
        if (messageHistory.size() > 50) {
            messageHistory.remove(0);
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

		recordHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.tupenter.toggle_recording",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			tupenterCategory
		));

		// Load Config
		TupenterConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            
            // =========================================================
            // 0. GLOBAL INPUT HANDLING (Always runs)
            // =========================================================
            
            // Handle Config
            while (configKey.consumeClick()) {
                client.setScreen(ModMenuIntegration.createScreen(client.screen));
            }
            
            // Handle History Recording Toggle
            while (recordHistoryKey.consumeClick()) {
                TupenterConfig.INSTANCE.recordHistory = !TupenterConfig.INSTANCE.recordHistory;
                TupenterConfig.save();
                
                if (TupenterConfig.INSTANCE.recordHistory) {
                    messageHistory.clear();
                }
                
                Component status = TupenterConfig.INSTANCE.recordHistory
                    ? Component.translatable("tupenter.recording.on").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("tupenter.recording.off").withStyle(ChatFormatting.RED);
                 
                Component msg = Component.translatable("tupenter.recording.prefix")
                    .withStyle(ChatFormatting.WHITE)
                    .append(status);
                    
                client.player.displayClientMessage(msg, true);
            }

            // =========================================================
            // 1. STATE MANAGEMENT (Toggling)
            // =========================================================
            
            // Logic to calculate batch (needed for locking on toggle)
            // We lazily calculate this only if we trigger a toggle ON event to save perf?
            // Actually, we need it if we are engaging.
            // Let's keep logic cleaner: Toggle State Logic first.

            if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                 while (resendKey.consumeClick()) {
                    isToggledOn = !isToggledOn;
                    
                    if (isToggledOn) {
                         // Toggled ON: Lock current potential batch if needed
                         if (!TupenterConfig.INSTANCE.updateInToggle) {
                             // --- REPLICATED SEARCH LOGIC START ---
                             List<String> currentCandidates = new ArrayList<>();
                             if (TupenterConfig.INSTANCE.rememberLastValid) {
                                int depth = Math.max(1, TupenterConfig.INSTANCE.historyDepth);
                                int collected = 0;
                                for (int i = messageHistory.size() - 1; i >= 0 && collected < depth; i--) {
                                     String candidate = messageHistory.get(i);
                                     boolean isCommand = candidate.startsWith("/");
                                     boolean allowed = true;
                                     switch (TupenterConfig.INSTANCE.resendFilter) {
                                        case CHAT_ONLY: if (isCommand) allowed = false; break;
                                        case COMMANDS_ONLY: if (!isCommand) allowed = false; break;
                                        case BOTH: default: allowed = true; break;
                                     }
                                     if (allowed) {
                                         currentCandidates.add(candidate);
                                         collected++;
                                     }
                                }
                                if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.OLDEST_FIRST) {
                                    Collections.reverse(currentCandidates);
                                }
                             }
                             lockedBatch = new ArrayList<>(currentCandidates);
                             // --- REPLICATED SEARCH LOGIC END ---
                        }
                    }

                    // Notification
                    Component status = isToggledOn 
                        ? Component.translatable("tupenter.toggle.on").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("tupenter.toggle.off").withStyle(ChatFormatting.RED);
                    
                    Component msg = Component.translatable("tupenter.toggle.prefix")
                        .withStyle(ChatFormatting.WHITE)
                        .append(status);
                    client.player.displayClientMessage(msg, true);
                 }
            } else if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                 // For Hold mode, we rely on isDown() later, but we ensure ToggledOn is false
                 isToggledOn = false;
                 // Consume stray clicks to prevent buffer buildup
                 while (resendKey.consumeClick()) { } 
            } else {
                 isToggledOn = false;
                 while (resendKey.consumeClick()) { }
            }

            // Update Mixin State
            // Note: For toggle, isFiring is roughly isToggledOn (or isDown for hold)
            boolean wasFiring = isFiring;

            if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                isFiring = resendKey.isDown();
            } else {
                isFiring = isToggledOn;
            }
            
            // Check for Stop and optional Abort
            if (wasFiring && !isFiring) {
                if (TupenterConfig.INSTANCE.batchMode == TupenterConfig.BatchMode.INTERRUPT) {
                    pendingQueue.clear();
                    delayTimer = 0;
                }
            }


            // =========================================================
            // 2. DELAY MANAGEMENT
            // =========================================================
            // Grace Period
            if (client.screen instanceof ChatScreen) {
                lastChatCloseTime = 0;
            } else if (lastChatCloseTime == 0 && client.screen == null) {
                lastChatCloseTime = System.currentTimeMillis();
            }
            boolean gracePeriodActive = (System.currentTimeMillis() - lastChatCloseTime) < 500L;

            if (gracePeriodActive && !isToggledOn && TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                 // Block firing during grace period
                 keyHoldTicks = 0;
                 return;
            }
            
            // Active Delay Timer
            if (delayTimer > 0) {
                delayTimer--;
                return; // Busy waiting, but Input was handled above!
            }


            // =========================================================
            // 3. TRIGGER / BATCH POPULATION
            // =========================================================
            // Only populate if queue is empty
            
            if (pendingQueue.isEmpty()) {
                boolean shouldTrigger = false;
                
                if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                    shouldTrigger = isToggledOn;
                } else if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.PRESS_AND_HOLD) {
                    shouldTrigger = resendKey.isDown();
                }

                if (shouldTrigger) {
                    keyHoldTicks++;
                    
                    boolean fireNow = false;
                    if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE) {
                        fireNow = true; // Queue pacing handles the rest
                    } else {
                        // Hold Mode Pacing
                        if (keyHoldTicks == 1) {
                            fireNow = true;
                        } else if (keyHoldTicks > TupenterConfig.INSTANCE.rapidResendDelay) {
                            fireNow = true;
                        }
                    }

                    if (fireNow) {
                         List<String> batch = new ArrayList<>();
                        
                         if (TupenterConfig.INSTANCE.usePermanentMessage) {
                             List<String> permBatch = new ArrayList<>();
                             for (String msg : TupenterConfig.INSTANCE.permanentMessages) {
                                 if (msg != null && !msg.trim().isEmpty()) permBatch.add(msg);
                             }
                             if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.NEWEST_FIRST) {
                                 Collections.reverse(permBatch);
                             }
                             batch.addAll(permBatch);
                        } else {
                             // Determine source
                             List<String> source = new ArrayList<>();
                             if (TupenterConfig.INSTANCE.resendMode == TupenterConfig.ResendMode.TOGGLE && !TupenterConfig.INSTANCE.updateInToggle && !lockedBatch.isEmpty()) {
                                 source = lockedBatch;
                             } else {
                                  // Fresh calculation (copied logic from above for consistency/fallback)
                                  // --- REPLICATED SEARCH LOGIC START ---
                                     int depth = Math.max(1, TupenterConfig.INSTANCE.historyDepth);
                                     int collected = 0;
                                     for (int i = messageHistory.size() - 1; i >= 0 && collected < depth; i--) {
                                          String candidate = messageHistory.get(i);
                                          boolean isCommand = candidate.startsWith("/");
                                          boolean allowed = true;
                                          switch (TupenterConfig.INSTANCE.resendFilter) {
                                             case CHAT_ONLY: if (isCommand) allowed = false; break;
                                             case COMMANDS_ONLY: if (!isCommand) allowed = false; break;
                                             case BOTH: default: allowed = true; break;
                                          }
                                          if (allowed) {
                                              source.add(candidate);
                                              collected++;
                                          }
                                     }
                                     if (TupenterConfig.INSTANCE.resendOrder == TupenterConfig.ResendOrder.OLDEST_FIRST) {
                                         Collections.reverse(source);
                                     }
                                  // --- REPLICATED SEARCH LOGIC END ---
                             }
                             batch.addAll(source);
                        }

                        if (!batch.isEmpty()) {
                            int count = Math.max(1, TupenterConfig.INSTANCE.resendAmount);
                            for (int i=0; i<count; i++) {
                                pendingQueue.addAll(batch);
                            }
                        }
                    }
                } else {
                    keyHoldTicks = 0;
                }
            }


            // =========================================================
            // 4. PROCESS QUEUE
            // =========================================================
            
            if (!pendingQueue.isEmpty()) {
                // If we are not firing, we only continue if the mode is FINISH_BATCH
                // (PAUSE does nothing here, effectively pausing naturally. INTERRUPT cleared queue above.)
                if (!isFiring && TupenterConfig.INSTANCE.batchMode != TupenterConfig.BatchMode.FINISH_BATCH) {
                     return;
                }

                while (!pendingQueue.isEmpty()) {
                    String msg = pendingQueue.poll();
                    if (msg.startsWith("/")) {
                        client.player.connection.sendCommand(msg.substring(1));
                    } else {
                         client.player.connection.sendChat(msg);
                    }

                    if (!pendingQueue.isEmpty()) {
                        if (TupenterConfig.INSTANCE.messageDelay > 0) {
                            delayTimer = TupenterConfig.INSTANCE.messageDelay;
                            return; 
                        }
                    } else {
                        // Batch Finished
                        delayTimer = TupenterConfig.INSTANCE.resendDelay;
                        if (TupenterConfig.INSTANCE.messageDelay > 0) {
                             delayTimer = Math.max(TupenterConfig.INSTANCE.messageDelay, TupenterConfig.INSTANCE.resendDelay);
                        }
                        return;
                    }
                }
            }
        });
	}
}