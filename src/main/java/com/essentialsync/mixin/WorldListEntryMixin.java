package com.essentialsync.mixin;

import com.essentialsync.client.WorldListOverlayHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin extends WorldSelectionList.Entry {
    @Shadow
    @Final
    LevelSummary summary;

    @Inject(method = "renderContent", at = @At("TAIL"))
    private void essentialsync$onRender(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            boolean hovering,
            float partialTick,
            CallbackInfo ci
    ) {
        WorldListOverlayHandler.refreshAsyncIfNeeded();
        WorldListOverlayHandler.renderWorldEntryOverlay(
                graphics,
                Minecraft.getInstance().font,
                this.summary.getLevelId(),
                this.getX(),
                this.getY(),
                this.getWidth()
        );
    }
}
