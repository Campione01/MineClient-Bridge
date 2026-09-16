package io.github.campione01.mineclientbridge.mixin;

import io.github.campione01.mineclientbridge.ClientInputIsolation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$filterNativeButton(
            long windowPointer, int button, int action, int modifiers, CallbackInfo callback) {
        if (!ClientInputIsolation.acceptsInputCallback()) {
            callback.cancel();
        }
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$filterNativeMotion(
            long windowPointer, double xPos, double yPos, CallbackInfo callback) {
        if (!ClientInputIsolation.acceptsInputCallback()) {
            callback.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$filterNativeScroll(
            long windowPointer, double xOffset, double yOffset, CallbackInfo callback) {
        if (!ClientInputIsolation.acceptsInputCallback()) {
            callback.cancel();
        }
    }

    /**
     * An isolated client owns its own input and never takes operating system focus, so it must be
     * able to grab the mouse without it. Minecraft refuses to continue an attack or to turn the
     * player while the mouse is ungrabbed. This redirect is scoped to grabMouse alone, so nothing
     * else that reads window focus changes, and the native cursor is still never captured: the
     * mixin on InputConstants.grabOrReleaseMouse suppresses that call under the same condition.
     */
    @Redirect(
            method = "grabMouse()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
    private boolean mineclientBridge$grabWithoutNativeFocus(Minecraft minecraft) {
        return minecraft.isWindowActive() || ClientInputIsolation.enabled();
    }
}
