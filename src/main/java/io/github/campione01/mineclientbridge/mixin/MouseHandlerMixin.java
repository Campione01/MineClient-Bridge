package io.github.campione01.mineclientbridge.mixin;

import io.github.campione01.mineclientbridge.ClientInputIsolation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
