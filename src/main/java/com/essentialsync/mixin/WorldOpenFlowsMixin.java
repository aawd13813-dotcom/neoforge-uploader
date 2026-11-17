package com.essentialsync.mixin;

import com.essentialsync.EssentialSyncMod;
import com.essentialsync.client.SyncBlockedScreen;
import com.essentialsync.client.SyncProgressScreen;
import com.essentialsync.sync.WorldSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract void openWorld(String levelId, Runnable onFail);

    @Unique
    private static final ThreadLocal<Boolean> essentialsync$bypass = ThreadLocal.withInitial(() -> false);

    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void essentialsync$onOpenWorld(String levelId, Runnable onFail, CallbackInfo ci) {
        if (Boolean.TRUE.equals(essentialsync$bypass.get())) {
            return;
        }
        if (!WorldSyncService.isManagedWorld(levelId)) {
            return;
        }

        ci.cancel();

        Screen previousScreen = this.minecraft.screen;
        SyncProgressScreen progressScreen = new SyncProgressScreen(
                "Syncing World from Cloud",
                "Connecting to S3..."
        );
        this.minecraft.setScreen(progressScreen);

        Path savesDir = this.minecraft.gameDirectory.toPath().resolve("saves");
        CompletableFuture.runAsync(() -> {
            try {
                WorldSyncService.syncDownstreamAndAcquireLock(
                        savesDir,
                        levelId,
                        progressScreen::updateProgress
                );
                this.minecraft.execute(() -> {
                    essentialsync$bypass.set(true);
                    try {
                        this.openWorld(levelId, onFail);
                    } finally {
                        essentialsync$bypass.set(false);
                    }
                });
            } catch (WorldSyncService.WorldLockedException locked) {
                this.minecraft.execute(() ->
                        this.minecraft.setScreen(new SyncBlockedScreen(previousScreen, locked.getLockInfo()))
                );
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.error("Downstream sync failed for {}: {}", levelId, e.getMessage(), e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                this.minecraft.execute(() -> this.minecraft.setScreen(new AlertScreen(
                        () -> this.minecraft.setScreen(previousScreen),
                        Component.literal("EssentialSync Failed"),
                        Component.literal(msg)
                )));
            }
        });
    }
}
