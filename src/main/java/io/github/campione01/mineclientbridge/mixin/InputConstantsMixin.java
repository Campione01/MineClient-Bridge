package io.github.campione01.mineclientbridge.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.campione01.mineclientbridge.ClientInputIsolation;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWCharModsCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;
import org.lwjgl.glfw.GLFWDropCallbackI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "setupKeyboardCallbacks", at = @At("HEAD"), cancellable = true)
    private static void mineclientBridge$omitNativeKeyboardCallbacks(long window,
            GLFWKeyCallbackI key, GLFWCharModsCallbackI character, CallbackInfo callback) {
        if (ClientInputIsolation.suppressNativeCallbackRegistration()) callback.cancel();
    }

    @Inject(method = "setupMouseCallbacks", at = @At("HEAD"), cancellable = true)
    private static void mineclientBridge$omitNativeMouseCallbacks(long window,
            GLFWCursorPosCallbackI position, GLFWMouseButtonCallbackI button,
            GLFWScrollCallbackI scroll, GLFWDropCallbackI drop, CallbackInfo callback) {
        // Reject hardware before it can enter a reentrant Minecraft task queue.
        if (ClientInputIsolation.suppressNativeCallbackRegistration()) callback.cancel();
    }

    @Inject(method = "grabOrReleaseMouse(JIDD)V", at = @At("HEAD"), cancellable = true)
    private static void mineclientBridge$suppressNativeCursor(
            long window, int cursorValue, double xPos, double yPos, CallbackInfo callback) {
        if (ClientInputIsolation.suppressCursorOperation()) {
            callback.cancel();
        }
    }

    @Inject(method = "updateRawMouseInput(JZ)V", at = @At("HEAD"), cancellable = true)
    private static void mineclientBridge$suppressNativeRawMouse(
            long window, boolean enableRawMouseMotion, CallbackInfo callback) {
        if (ClientInputIsolation.suppressRawMouseUpdate()) {
            callback.cancel();
        }
    }

    @Inject(method = "isKeyDown(JI)Z", at = @At("HEAD"), cancellable = true)
    private static void mineclientBridge$readVirtualKey(
            long window, int key, CallbackInfoReturnable<Boolean> callback) {
        if (ClientInputIsolation.enabled()) {
            callback.setReturnValue(ClientInputIsolation.isKeyDown(key));
        }
    }
}
