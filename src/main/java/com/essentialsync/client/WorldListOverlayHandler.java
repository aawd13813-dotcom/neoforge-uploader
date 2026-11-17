package com.essentialsync.client;

import com.essentialsync.SyncConfig;
import com.essentialsync.s3.S3Client;
import com.essentialsync.sync.LockManager;
import com.essentialsync.sync.ManifestManager;
import com.essentialsync.sync.WorldSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches remote lock.json and latest.json per synced world asynchronously (10-second cache)
 * and renders the status badge on each managed world entry in the world list.
 *
 * <p>Each synced world has its own S3 prefix so their statuses are fetched independently.
 */
public final class WorldListOverlayHandler {
    private static final long CACHE_TTL_MS = 10_000L;
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    /** Per-world-folder status cache. Key = local folder name. */
    private static volatile Map<String, StatusSnapshot> cachedStatuses = new ConcurrentHashMap<>();
    private static volatile long lastFetchMs = 0L;

    private WorldListOverlayHandler() {
    }

    public record StatusSnapshot(
            String badgeText,
            int badgeColor,
            String modWarningText
    ) {
    }

    public static void refreshAsyncIfNeeded() {
        if (!SyncConfig.isConfigured()) {
            cachedStatuses = new ConcurrentHashMap<>();
            return;
        }
        long now = System.currentTimeMillis();
        if (!cachedStatuses.isEmpty() && (now - lastFetchMs) < CACHE_TTL_MS) {
            return;
        }
        if (!FETCHING.compareAndSet(false, true)) {
            return;
        }

        List<? extends String> syncedWorlds = SyncConfig.SYNCED_WORLDS.get();
        if (syncedWorlds.isEmpty()) {
            FETCHING.set(false);
            return;
        }

        CompletableFuture.runAsync(() -> {
            Map<String, StatusSnapshot> results = new ConcurrentHashMap<>();
            try {
                // Fetch each world's status in parallel
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (String worldFolder : syncedWorlds) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            S3Client s3 = WorldSyncService.createS3Client(worldFolder);
                            Optional<LockManager.LockInfo> lockOpt = LockManager.fetchLock(s3);
                            Optional<String> latestJsonOpt = s3.getString("latest.json");

                            ManifestManager.Manifest latest = latestJsonOpt.isPresent()
                                    ? ManifestManager.fromJson(latestJsonOpt.get())
                                    : null;

                            String modWarning = computeModDriftWarning(latest);

                            if (lockOpt.isPresent() && lockOpt.get().isActive(System.currentTimeMillis())) {
                                LockManager.LockInfo lock = lockOpt.get();
                                results.put(worldFolder, new StatusSnapshot(
                                        "LIVE: " + lock.holderName() + " is hosting • Join via Essential",
                                        0xFFFFAA00,
                                        modWarning
                                ));
                            } else if (latest == null) {
                                results.put(worldFolder, new StatusSnapshot(
                                        "Synced • Ready",
                                        0xFF55FF55,
                                        null
                                ));
                            } else {
                                Path worldDir = Minecraft.getInstance().gameDirectory.toPath()
                                        .resolve("saves")
                                        .resolve(worldFolder);
                                Map<String, ManifestManager.FileEntry> localFiles =
                                        ManifestManager.scanLocalWorld(worldDir);
                                int behindCount = 0;
                                for (Map.Entry<String, ManifestManager.FileEntry> entry : latest.files().entrySet()) {
                                    ManifestManager.FileEntry local = localFiles.get(entry.getKey());
                                    if (local == null || !local.sha256().equals(entry.getValue().sha256())) {
                                        behindCount++;
                                    }
                                }

                                if (behindCount == 0) {
                                    results.put(worldFolder, new StatusSnapshot(
                                            "Synced • Ready to Host",
                                            0xFF55FF55,
                                            modWarning
                                    ));
                                } else {
                                    results.put(worldFolder, new StatusSnapshot(
                                            "Update Available • " + behindCount + " file(s) behind",
                                            0xFFFFFF55,
                                            modWarning
                                    ));
                                }
                            }
                        } catch (Exception e) {
                            results.put(worldFolder, new StatusSnapshot(
                                    "Sync Offline • Check S3 Config",
                                    0xFFFF5555,
                                    null
                            ));
                        }
                    }));
                }
                // Wait for all worlds
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                cachedStatuses = results;
                lastFetchMs = System.currentTimeMillis();
            } finally {
                FETCHING.set(false);
            }
        });
    }

    public static void renderWorldEntryOverlay(GuiGraphics graphics, Font font, String folderName, int left, int top, int width) {
        if (!WorldSyncService.isManagedWorld(folderName)) {
            return;
        }
        StatusSnapshot status = cachedStatuses.get(folderName);
        if (status == null) {
            String checking = "Checking Sync...";
            int w = font.width(checking);
            graphics.drawString(font, checking, left + width - w - 4, top + 1, 0xFFAAAAAA, false);
            return;
        }

        int badgeWidth = font.width(status.badgeText());
        graphics.drawString(font, status.badgeText(), left + width - badgeWidth - 4, top + 1, status.badgeColor(), false);

        if (status.modWarningText() != null && !status.modWarningText().isEmpty()) {
            int warnWidth = font.width(status.modWarningText());
            graphics.drawString(font, status.modWarningText(), left + width - warnWidth - 4, top + 12, 0xFFFF5555, false);
        }
    }

    private static String computeModDriftWarning(ManifestManager.Manifest latest) {
        if (latest == null || latest.mods().isEmpty()) {
            return null;
        }
        Map<String, String> localMods = WorldSyncService.collectLocalMods();
        Map<String, String> remoteMods = latest.mods();

        Set<String> allIds = new TreeSet<>();
        allIds.addAll(localMods.keySet());
        allIds.addAll(remoteMods.keySet());

        List<String> mismatched = new ArrayList<>();
        for (String modId : allIds) {
            String localVer = localMods.get(modId);
            String remoteVer = remoteMods.get(modId);
            if (localVer == null || !localVer.equals(remoteVer)) {
                mismatched.add(modId);
            }
        }
        if (mismatched.isEmpty()) {
            return null;
        }
        if (mismatched.size() > 4) {
            return "Mod mismatch: " + String.join(", ", mismatched.subList(0, 4)) + " (+" + (mismatched.size() - 4) + ")";
        }
        return "Mod mismatch: " + String.join(", ", mismatched);
    }
}

