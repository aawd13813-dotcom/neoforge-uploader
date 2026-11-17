package com.essentialsync.client;

import com.essentialsync.EssentialSyncMod;
import com.essentialsync.SyncConfig;
import com.essentialsync.s3.S3Client;
import com.essentialsync.sync.LockManager;
import com.essentialsync.sync.WorldSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Handles smart save triggers (PauseScreen debounced 30s, periodic ServerTickEvent),
 * and the quit-world final save + non-blocking background upload flow.
 * Upload progress is shown via {@link SyncHudOverlay} in the top-left corner.
 */
@EventBusSubscriber(modid = EssentialSyncMod.MODID, value = Dist.CLIENT)
public final class SyncEventHandler {
    private static final long PAUSE_DEBOUNCE_MS = 30_000L;

    private static volatile long lastPauseSyncMs = 0L;
    private static volatile int tickCounter = 0;
    // Legacy fields kept to avoid compile errors in case of future usage
    private static volatile boolean quitSyncInProgress = false;
    private static volatile SyncProgressScreen activeQuitProgressScreen = null;

    private SyncEventHandler() {
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getScreen() instanceof SelectWorldScreen selectScreen && SyncConfig.isConfigured()) {
            Path savesDir = mc.gameDirectory.toPath().resolve("saves");

            // Find first synced world that doesn't exist locally — offer to download it
            String missingWorld = null;
            for (String folder : SyncConfig.SYNCED_WORLDS.get()) {
                if (!Files.exists(savesDir.resolve(folder))) {
                    missingWorld = folder;
                    break;
                }
            }

            if (missingWorld != null) {
                final String worldToDownload = missingWorld;
                Button downloadBtn = Button.builder(
                        Component.literal("Download: " + worldToDownload), btn -> {
                    SyncProgressScreen progress = new SyncProgressScreen(
                            "Downloading " + worldToDownload + "...",
                            "Connecting to cloud..."
                    );
                    mc.setScreen(progress);
                    CompletableFuture.runAsync(() -> {
                        try {
                            WorldSyncService.syncDownstreamAndAcquireLock(
                                    savesDir,
                                    worldToDownload,
                                    progress::updateProgress
                            );
                            S3Client s3 = WorldSyncService.createS3Client(worldToDownload);
                            LockManager.releaseLock(s3, WorldSyncService.getLocalPlayerName(), worldToDownload);
                            mc.execute(() -> mc.setScreen(new SelectWorldScreen(new TitleScreen())));
                        } catch (WorldSyncService.WorldLockedException locked) {
                            mc.execute(() -> mc.setScreen(new SyncBlockedScreen(
                                    selectScreen,
                                    locked.getLockInfo()
                            )));
                        } catch (Exception e) {
                            EssentialSyncMod.LOGGER.error("Failed to download initial world: {}", e.getMessage(), e);
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            mc.execute(() -> mc.setScreen(new AlertScreen(
                                    () -> mc.setScreen(selectScreen),
                                    Component.literal("Download Failed"),
                                    Component.literal(msg)
                            )));
                        }
                    });
                }).bounds(selectScreen.width - 158, 8, 150, 20).build();
                event.addListener(downloadBtn);
            } else {
                // All synced worlds exist locally — silently pull any updates in the background.
                // By the time the user clicks Play, files will already be fresh.
                WorldSyncService.triggerGuestDownloadAsync();
            }
            return;
        }

        if (!(event.getScreen() instanceof PauseScreen screen)) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        String currentFolder = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        boolean isSyncedWorld = SyncConfig.isSyncedWorld(currentFolder);
        int syncedCount = SyncConfig.SYNCED_WORLDS.get().size();
        boolean atLimit = syncedCount >= SyncConfig.MAX_SYNCED_WORLDS;

        if (isSyncedWorld) {
            // ── World IS synced: show "Sync Now" + "Desync" buttons ──

            Button syncNowBtn = Button.builder(Component.literal("EssentialSync: Sync Now"), btn -> {
                btn.setMessage(Component.literal("EssentialSync: Syncing..."));
                WorldSyncService.triggerBackgroundUploadAsync();
            }).bounds(screen.width - 148, 8, 140, 20).build();
            event.addListener(syncNowBtn);

            // Desync button — red-ish label; disabled while actively hosting (quit first)
            boolean activelyHosting = WorldSyncService.isActiveManagedWorld()
                    && currentFolder.equals(WorldSyncService.getActiveWorldFolder());

            Button desyncBtn = Button.builder(
                    Component.literal(activelyHosting ? "Quit first to Desync" : "⚠ Desync (delete cloud)"),
                    btn -> {
                        if (WorldSyncService.isActiveManagedWorld()
                                && currentFolder.equals(WorldSyncService.getActiveWorldFolder())) {
                            btn.setMessage(Component.literal("Save & quit first!"));
                            return;
                        }
                        btn.setMessage(Component.literal("Desyncing..."));
                        WorldSyncService.desyncWorldAsync(currentFolder);
                    }
            ).bounds(screen.width - 148, 32, 140, 20).build();
            event.addListener(desyncBtn);

        } else {
            // ── World is NOT synced: show "Add World" (or limit message) ──

            String addLabel = atLimit
                    ? "EssentialSync: Limit (" + syncedCount + "/" + SyncConfig.MAX_SYNCED_WORLDS + ")"
                    : "EssentialSync: Add World";

            Button addBtn = Button.builder(Component.literal(addLabel), btn -> {
                if (SyncConfig.isSyncedWorld(currentFolder)) {
                    btn.setMessage(Component.literal("Already synced!"));
                    return;
                }
                boolean added = SyncConfig.addSyncedWorld(currentFolder);
                if (added) {
                    btn.setMessage(Component.literal("EssentialSync: Added! ✓"));
                } else {
                    btn.setMessage(Component.literal("Limit: max " + SyncConfig.MAX_SYNCED_WORLDS + " worlds"));
                }
            }).bounds(screen.width - 148, 8, 140, 20).build();
            event.addListener(addBtn);
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (quitSyncInProgress && !(event.getNewScreen() instanceof SyncProgressScreen)) {
            event.setCanceled(true);
            return;
        }

        if (event.getNewScreen() instanceof PauseScreen && WorldSyncService.isActiveManagedWorld()) {
            long now = System.currentTimeMillis();
            if (now - lastPauseSyncMs >= PAUSE_DEBOUNCE_MS) {
                lastPauseSyncMs = now;
                // Zero-stutter: do NOT call server.saveEverything() here
                WorldSyncService.triggerBackgroundUploadAsync();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (!WorldSyncService.isActiveManagedWorld()) {
            tickCounter = 0;
            return;
        }
        int intervalTicks = SyncConfig.AUTO_SYNC_INTERVAL_MINUTES.get() * 60 * 20;
        tickCounter++;
        if (tickCounter >= intervalTicks) {
            tickCounter = 0;
            // Zero-stutter: do NOT call server.saveEverything() here
            WorldSyncService.triggerBackgroundUploadAsync();
        }
    }

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        // If we're joining someone else's server (not our own singleplayer), start
        // downloading the synced world to our saves immediately in the background.
        // By the time we finish playing and leave, the world will already be there.
        if (!mc.hasSingleplayerServer() && SyncConfig.isConfigured()) {
            WorldSyncService.triggerGuestDownloadAsync();
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft mc = Minecraft.getInstance();
        // If we were a GUEST (connected to someone else's Essential-hosted world, not our own
        // singleplayer server), auto-sync the latest world state to our local saves.
        // This makes the world appear immediately in the world list after leaving.
        if (!mc.hasSingleplayerServer() && SyncConfig.isConfigured()) {
            WorldSyncService.triggerGuestDownloadAsync();
        }
        // If we WERE the host, the quit flow is handled by onServerStopped.
    }

    /**
     * Fires on the integrated server when ANY player (including Essential guests) logs out.
     * Triggers a background save + upload so guests' progress is immediately preserved.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!WorldSyncService.isActiveManagedWorld()) return;

        // Determine if this was a guest (not the host player)
        Minecraft mc = Minecraft.getInstance();
        String loggedOutUuid = event.getEntity().getStringUUID();
        String hostUuid = mc.getUser().getProfileId().toString();
        boolean isGuest = !loggedOutUuid.equals(hostUuid);

        if (isGuest) {
            EssentialSyncMod.LOGGER.info("Guest {} left — triggering world save + upload.", event.getEntity().getName().getString());
            // Player data is auto-saved by Minecraft on disconnect; just trigger the upload.
            WorldSyncService.triggerBackgroundUploadAsync();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (!WorldSyncService.isActiveManagedWorld()) {
            return;
        }
        // Final save is permitted once on world quit
        event.getServer().saveEverything(false, true, true);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        quitSyncInProgress = false;
        activeQuitProgressScreen = null;
        tickCounter = 0;

        if (!WorldSyncService.isActiveManagedWorld()) {
            return;
        }

        // Start the upload in the background — the user can freely navigate menus.
        // The top-left HUD overlay (SyncHudOverlay) shows live progress on any screen.
        WorldSyncService.startQuitUploadAsync();

        // Return to title screen immediately (no blocking progress screen).
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new TitleScreen()));
    }
}
