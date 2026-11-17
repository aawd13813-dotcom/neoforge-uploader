package com.essentialsync.mixin;

import com.essentialsync.client.WorldListOverlayHandler;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin {
    /** Kick off the initial async fetch when the screen opens. */
    @Inject(method = "init", at = @At("HEAD"))
    private void essentialsync$onInit(CallbackInfo ci) {
        WorldListOverlayHandler.refreshAsyncIfNeeded();
    }
}
