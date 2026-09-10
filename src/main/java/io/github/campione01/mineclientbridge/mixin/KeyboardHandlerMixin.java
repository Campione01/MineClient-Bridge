package io.github.campione01.mineclientbridge.mixin;

import io.github.campione01.mineclientbridge.ClientInputIsolation;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$filterNativeKey(
            long windowPointer, int key, int scanCode, int action, int modifiers, CallbackInfo callback) {
        if (!ClientInputIsolation.acceptsInputCallback()) {
            callback.cancel();
        }
    }

    @Inject(method = "charTyped(JII)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$filterNativeCharacter(
            long windowPointer, int codePoint, int modifiers, CallbackInfo callback) {
        if (!ClientInputIsolation.acceptsInputCallback()) {
            callback.cancel();
        }
    }

    @Inject(method = "getClipboard()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$readVirtualClipboard(CallbackInfoReturnable<String> callback) {
        if (ClientInputIsolation.enabled()) {
            callback.setReturnValue(ClientInputIsolation.getClipboard());
        }
    }

    @Inject(method = "setClipboard(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void mineclientBridge$writeVirtualClipboard(String value, CallbackInfo callback) {
        if (ClientInputIsolation.enabled()) {
            if (!value.isEmpty()) {
                ClientInputIsolation.setClipboard(value);
            }
            callback.cancel();
        }
    }
}
