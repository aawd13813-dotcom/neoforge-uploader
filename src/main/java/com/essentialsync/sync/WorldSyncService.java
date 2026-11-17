package com.essentialsync.sync;

import com.essentialsync.merge.WorldMerger;
import com.essentialsync.EssentialSyncMod;
import com.essentialsync.SyncConfig;
import com.essentialsync.s3.S3Client;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Core synchronization engine handling downstream sync, delta uploads,
 * bandwidth-safe thread pooling, and active session state.
 */
public final class WorldSyncService {
    private static final AtomicBoolean UPLOAD_IN_PROGRESS = new AtomicBoolean(false);
    private static volatile CompletableFuture<Void> currentUploadFuture = CompletableFuture.completedFuture(null);

    /** Shared HUD status — null means no active upload. Read by SyncHudOverlay on the render thread. */
    private static volatile UploadStatus hudStatus = null;

    private static volatile String activeWorldFolder = null;
    private static volatile Path activeWorldDir = null;
    private static volatile ManifestManager.Manifest lastSyncedManifest = null;

    private WorldSyncService() {
    }

    /**
     * Live status shown in the top-left HUD overlay during any upload.
     * {@code null} means no upload is running.
     */
    public record UploadStatus(
            String phase,
            long completedBytes,
            long totalBytes,
            int completedFiles,
            int totalFiles
    ) {
        public float progress() {
            if (totalBytes > 0) return Math.min(1f, (float) completedBytes / (float) totalBytes);
            if (totalFiles > 0) return Math.min(1f, (float) completedFiles / (float) totalFiles);
            return 0f;
        }
    }

    /** Returns the current HUD status, or {@code null} if no upload is in progress. */
    public static UploadStatus getHudStatus() {
        return hudStatus;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String status, long completedBytes, long totalBytes, int completedFiles, int totalFiles);
    }

    public static final class WorldLockedException extends Exception {
        private final LockManager.LockInfo lockInfo;

        public WorldLockedException(LockManager.LockInfo lockInfo) {
            super("World is locked by " + lockInfo.holderName());
            this.lockInfo = lockInfo;
        }

        public LockManager.LockInfo getLockInfo() {
            return lockInfo;
        }
    }

    /** Creates an S3 client scoped to a specific world folder prefix. */
    public static S3Client createS3Client(String worldFolderName) {
        return new S3Client(
                SyncConfig.ENDPOINT_URL.get(),
                SyncConfig.REGION.get(),
                SyncConfig.BUCKET_NAME.get(),
                worldFolderName,
                SyncConfig.ACCESS_KEY_ID.get(),
                SyncConfig.SECRET_ACCESS_KEY.get()
        );
    }

    /**
     * Creates an S3 client scoped to the currently active world.
     * Only valid while a world is open (activeWorldFolder != null).
     */
    public static S3Client createS3Client() {
        String folder = activeWorldFolder != null
                ? activeWorldFolder
                : (!SyncConfig.SYNCED_WORLDS.get().isEmpty() ? SyncConfig.SYNCED_WORLDS.get().get(0) : "");
        return createS3Client(folder);
    }

    public static Map<String, String> collectLocalMods() {
        Map<String, String> mods = new TreeMap<>();
        for (IModInfo info : ModList.get().getMods()) {
            mods.put(info.getModId(), info.getVersion().toString());
        }
        return mods;
    }

    public static String getLocalPlayerUuid() {
        return Minecraft.getInstance().getUser().getProfileId().toString();
    }

    public static String getLocalPlayerName() {
        return Minecraft.getInstance().getUser().getName();
    }

    public static boolean isManagedWorld(String folderName) {
        return SyncConfig.isSyncedWorld(folderName);
    }

    public static boolean isActiveManagedWorld() {
        return activeWorldFolder != null && activeWorldDir != null && LockManager.isHoldingLock();
    }

    public static String getActiveWorldFolder() {
        return activeWorldFolder;
    }

    public static Path getActiveWorldDir() {
        return activeWorldDir;
    }

    /**
     * Performs lock check, rolling local backup, downstream differential download (4 threads),
     * Data.Player NBT swap, and acquires the distributed host lock + CrashWatchdog.
     */
    public static void syncDownstreamAndAcquireLock(
            Path savesDir,
            String worldFolderName,
            ProgressListener listener
    ) throws Exception {
        S3Client s3 = createS3Client(worldFolderName);
        String myUuid = getLocalPlayerUuid();
        String myName = getLocalPlayerName();

        listener.onProgress("Checking host lock...", 0, 0, 0, 0);
        Optional<LockManager.LockInfo> lockOpt = LockManager.fetchLock(s3);
        if (lockOpt.isPresent()) {
            LockManager.LockInfo lock = lockOpt.get();
            if (lock.isActive(System.currentTimeMillis()) && !myUuid.equals(lock.holderUuid())) {
                throw new WorldLockedException(lock);
            }
        }

        Path worldDir = savesDir.resolve(worldFolderName);
        Files.createDirectories(worldDir);

        listener.onProgress("Fetching latest world manifest...", 0, 0, 0, 0);
        Optional<String> latestJsonOpt = s3.getString("latest.json");

        if (latestJsonOpt.isPresent()) {
            ManifestManager.Manifest latest = ManifestManager.fromJson(latestJsonOpt.get());

            listener.onProgress("Creating rolling local backup...", 0, 0, 0, 0);
            LocalBackupManager.createRollingBackup(savesDir, worldFolderName);

            listener.onProgress("Scanning local world files...", 0, 0, 0, 0);
            Map<String, ManifestManager.FileEntry> localFiles = ManifestManager.scanLocalWorld(worldDir);

            // Delete local tracked files not present in latest.json
            for (String localRel : localFiles.keySet()) {
                if (!latest.files().containsKey(localRel)) {
                    deleteFileAndEmptyParents(worldDir, worldDir.resolve(localRel));
                }
            }

            // Determine files needing download
            List<Map.Entry<String, ManifestManager.FileEntry>> toDownload = new ArrayList<>();
            long totalBytes = 0L;
            for (Map.Entry<String, ManifestManager.FileEntry> entry : latest.files().entrySet()) {
                String rel = entry.getKey();
                ManifestManager.FileEntry remoteFile = entry.getValue();
                ManifestManager.FileEntry localFile = localFiles.get(rel);
                if (localFile == null || !localFile.sha256().equals(remoteFile.sha256())) {
                    toDownload.add(entry);
                    totalBytes += Math.max(0L, remoteFile.size());
                }
            }

            int totalFiles = toDownload.size();
            if (totalFiles > 0) {
                AtomicLong downloadedBytes = new AtomicLong(0);
                AtomicInteger completedFiles = new AtomicInteger(0);
                long finalTotalBytes = Math.max(1L, totalBytes);
                listener.onProgress("Downloading world files...", 0, finalTotalBytes, 0, totalFiles);

                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    List<Future<?>> futures = new ArrayList<>();
                    for (Map.Entry<String, ManifestManager.FileEntry> entry : toDownload) {
                        futures.add(pool.submit(() -> {
                            String rel = entry.getKey();
                            ManifestManager.FileEntry remoteFile = entry.getValue();
                            Path dest = worldDir.resolve(rel);
                            boolean ok = s3.downloadFile("objects/" + remoteFile.sha256(), dest);
                            if (!ok) {
                                throw new IOException("Missing object blob in S3: objects/" + remoteFile.sha256() + " for " + rel);
                            }
                            long bytesNow = downloadedBytes.addAndGet(Math.max(0L, remoteFile.size()));
                            int filesNow = completedFiles.incrementAndGet();
                            listener.onProgress("Downloading " + rel, bytesNow, finalTotalBytes, filesNow, totalFiles);
                            return null;
                        }));
                    }
                    for (Future<?> f : futures) {
                        try {
                            f.get();
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof Exception ex) {
                                throw ex;
                            }
                            throw new RuntimeException(cause);
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            }

            lastSyncedManifest = latest;
        } else {
            EssentialSyncMod.LOGGER.info("No remote latest.json found; treating {} as fresh initial world.", worldFolderName);
            lastSyncedManifest = null;
        }

        listener.onProgress("Preparing host player data...", 0, 0, 0, 0);
        PlayerDataSwapper.prepareHostPlayerData(worldDir, myUuid);

        // Re-check lock right before acquiring — the download above could have taken minutes,
        // during which another player may have grabbed the lock.
        listener.onProgress("Acquiring distributed host lock...", 0, 0, 0, 0);
        Optional<LockManager.LockInfo> preLockCheck = LockManager.fetchLock(s3);
        if (preLockCheck.isPresent()) {
            LockManager.LockInfo live = preLockCheck.get();
            if (live.isActive(System.currentTimeMillis()) && !myUuid.equals(live.holderUuid())) {
                throw new WorldLockedException(live);
            }
        }
        LockManager.acquireLock(s3, worldDir, myUuid, myName, collectLocalMods());

        activeWorldFolder = worldFolderName;
        activeWorldDir = worldDir;
    }

    /**
     * Called when the local player joins (or is a guest on) an Essential-hosted world and then
     * leaves. Downloads the latest world state from S3 into local saves so it appears
     * immediately in the world list — without acquiring the host lock.
     *
     * <p>Safe to call from the client thread; runs entirely on a background virtual thread.
     * Progress is shown via the top-left {@link com.essentialsync.client.SyncHudOverlay}.
     */
    public static void triggerGuestDownloadAsync() {
        if (!SyncConfig.isConfigured()) return;
        if (UPLOAD_IN_PROGRESS.get()) return;   // upload in progress — don't interfere
        if (isActiveManagedWorld()) return;      // we ARE the host right now — don't overwrite live session

        List<? extends String> syncedWorlds = SyncConfig.SYNCED_WORLDS.get();
        if (syncedWorlds.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            Path savesDir = mc.gameDirectory.toPath().resolve("saves");

            // Download each synced world in sequence (worlds are independent, different S3 prefixes)
            for (String worldFolder : syncedWorlds) {
                try {
                    S3Client s3 = createS3Client(worldFolder);
                    Path worldDir = savesDir.resolve(worldFolder);

                    hudStatus = new UploadStatus("Fetching manifest: " + worldFolder, 0, 0, 0, 0);

                    Optional<String> latestJsonOpt = s3.getString("latest.json");
                    if (latestJsonOpt.isEmpty()) {
                        EssentialSyncMod.LOGGER.info("No remote manifest for '{}' — skipping.", worldFolder);
                        continue;
                    }

                    ManifestManager.Manifest remote = ManifestManager.fromJson(latestJsonOpt.get());
                    Files.createDirectories(worldDir);
                    Map<String, ManifestManager.FileEntry> localFiles = ManifestManager.scanLocalWorld(worldDir);

                    List<Map.Entry<String, ManifestManager.FileEntry>> toDownload = new ArrayList<>();
                    long totalBytes = 0L;
                    for (Map.Entry<String, ManifestManager.FileEntry> entry : remote.files().entrySet()) {
                        ManifestManager.FileEntry localFile = localFiles.get(entry.getKey());
                        if (localFile == null || !localFile.sha256().equals(entry.getValue().sha256())) {
                            toDownload.add(entry);
                            totalBytes += Math.max(0L, entry.getValue().size());
                        }
                    }

                    if (toDownload.isEmpty()) {
                        EssentialSyncMod.LOGGER.info("'{}' is already up-to-date.", worldFolder);
                        continue;
                    }

                    int totalFiles = toDownload.size();
                    long finalTotal = Math.max(1L, totalBytes);
                    AtomicLong downloadedBytes = new AtomicLong(0);
                    AtomicInteger completedFiles = new AtomicInteger(0);
                    hudStatus = new UploadStatus("Syncing " + worldFolder + "...", 0, finalTotal, 0, totalFiles);
                    EssentialSyncMod.LOGGER.info("Guest sync '{}': {} file(s), {} bytes", worldFolder, totalFiles, totalBytes);

                    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                    try {
                        List<Future<?>> futures = new ArrayList<>();
                        for (Map.Entry<String, ManifestManager.FileEntry> entry : toDownload) {
                            futures.add(pool.submit(() -> {
                                String rel = entry.getKey();
                                ManifestManager.FileEntry fe = entry.getValue();
                                Path dest = worldDir.resolve(rel);
                                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                                s3.downloadFile("objects/" + fe.sha256(), dest);
                                long b = downloadedBytes.addAndGet(Math.max(0L, fe.size()));
                                int f = completedFiles.incrementAndGet();
                                hudStatus = new UploadStatus("Syncing " + rel, b, finalTotal, f, totalFiles);
                                return null;
                            }));
                        }
                        for (Future<?> f : futures) {
                            try { f.get(); } catch (ExecutionException e) {
                                Throwable cause = e.getCause();
                                if (cause instanceof Exception ex) throw ex;
                                throw new RuntimeException(cause);
                            }
                        }
                    } finally {
                        pool.shutdownNow();
                    }

                    EssentialSyncMod.LOGGER.info("Guest sync complete — '{}' updated in saves.", worldFolder);
                } catch (Exception e) {
                    EssentialSyncMod.LOGGER.warn("Guest sync failed for '{}': {}", worldFolder, e.getMessage());
                }
            }
            hudStatus = null;
        });
    }


    /**
     * Removes a world from the synced list AND permanently deletes all its data from S3.
     * Local saves folder is NOT touched — only the cloud copy is wiped.
     * Safe to call from any thread; runs on a background virtual thread.
     * Shows HUD progress while deleting.
     *
     * @param worldFolder the exact local folder name to desync
     */
    public static void desyncWorldAsync(String worldFolder) {
        // Remove from local config immediately
        SyncConfig.removeSyncedWorld(worldFolder);

        CompletableFuture.runAsync(() -> {
            try {
                EssentialSyncMod.LOGGER.info("Desyncing '{}' — deleting all cloud data...", worldFolder);
                S3Client s3 = createS3Client(worldFolder);

                hudStatus = new UploadStatus("Desyncing " + worldFolder + "...", 0, 0, 0, 0);

                // List every object under this world's prefix and delete them all
                List<String> allKeys = s3.listObjects("");
                int total = allKeys.size() + 2; // +2 for lock.json and latest.json
                int done = 0;

                for (String key : allKeys) {
                    s3.deleteObject(key);
                    done++;
                    hudStatus = new UploadStatus("Deleting cloud data: " + key,
                            done, total, done, total);
                }

                // Explicitly delete the top-level index files (may already be gone via listObjects)
                try { s3.deleteObject("lock.json"); } catch (Exception ignored) {}
                try { s3.deleteObject("latest.json"); } catch (Exception ignored) {}

                EssentialSyncMod.LOGGER.info("Desync complete — '{}' removed from cloud.", worldFolder);
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.error("Desync failed for '{}': {}", worldFolder, e.getMessage(), e);
            } finally {
                hudStatus = null;
            }
        });
    }

    /**
     * Triggers an asynchronous delta upload during active gameplay (1 thread, no server.saveEverything()).
     */
    public static void triggerBackgroundUploadAsync() {
        if (!isActiveManagedWorld()) {
            return;
        }
        if (!UPLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        currentUploadFuture = CompletableFuture.runAsync(() -> {
            try {
                performUpstreamSync(false, (status, cb, tb, cf, tf) ->
                        hudStatus = new UploadStatus(status, cb, tb, cf, tf));
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.warn("Background delta upload failed: {}", e.getMessage());
            } finally {
                UPLOAD_IN_PROGRESS.set(false);
                hudStatus = null;
            }
        });
    }

    /**
     * Starts the final quit upload asynchronously and returns immediately.
     * The upload runs in the background; progress is shown via {@link #getHudStatus()}.
     * The host lock is released when the upload finishes (or fails).
     * Safe to call from the server-stopped event — does NOT block the render thread.
     */
    public static void startQuitUploadAsync() {
        if (!isActiveManagedWorld()) {
            return;
        }

        // Cancel any in-flight periodic upload and take ownership
        if (!UPLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            // A background upload is running — wait for it briefly then proceed
            try {
                currentUploadFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            UPLOAD_IN_PROGRESS.set(true);
        }

        // Snapshot quit-time world state now (before activeWorldDir is cleared)
        final String myName = getLocalPlayerName();
        final String folder = activeWorldFolder;

        currentUploadFuture = CompletableFuture.runAsync(() -> {
            try {
                performUpstreamSync(true, (status, cb, tb, cf, tf) ->
                        hudStatus = new UploadStatus(status, cb, tb, cf, tf));
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.error("Quit upload failed: {}", e.getMessage(), e);
            } finally {
                try {
                    if (folder != null) {
                        hudStatus = new UploadStatus("Releasing host lock...", 0, 0, 0, 0);
                        S3Client s3 = createS3Client();
                        LockManager.releaseLock(s3, myName, folder);
                    }
                } catch (Exception e) {
                    EssentialSyncMod.LOGGER.error("Lock release after quit upload failed: {}", e.getMessage(), e);
                } finally {
                    activeWorldFolder = null;
                    activeWorldDir = null;
                    lastSyncedManifest = null;
                    UPLOAD_IN_PROGRESS.set(false);
                    hudStatus = null;
                }
            }
        });
    }

    private static void performUpstreamSync(boolean isQuitFinal, ProgressListener listener)
            throws Exception {
        Path worldDir = activeWorldDir;
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return;
        }

        S3Client s3 = createS3Client();
        String myUuid = getLocalPlayerUuid();
        String myName = getLocalPlayerName();

        listener.onProgress("Extracting host player data...", 0, 0, 0, 0);
        try {
            PlayerDataSwapper.extractHostPlayerData(worldDir, myUuid);
        } catch (Exception e) {
            EssentialSyncMod.LOGGER.warn("Failed to extract Data.Player before upload: {}", e.getMessage());
        }

        listener.onProgress("Hashing world files...", 0, 0, 0, 0);
        Map<String, ManifestManager.FileEntry> localFiles = ManifestManager.scanLocalWorld(worldDir);

        // ----------------------------------------------------------------
        // Drift detection: if another player uploaded since we started playing,
        // run a 3-way merge so both sessions' changes are preserved.
        // ----------------------------------------------------------------
        if (lastSyncedManifest != null) {
            listener.onProgress("Checking for concurrent changes...", 0, 0, 0, 0);
            Optional<String> remoteLatestJson = s3.getString("latest.json");
            if (remoteLatestJson.isPresent()) {
                ManifestManager.Manifest remoteLatest = ManifestManager.fromJson(remoteLatestJson.get());
                if (remoteLatest.timestamp() != lastSyncedManifest.timestamp()) {
                    // Another player uploaded while we were playing — merge their changes in
                    EssentialSyncMod.LOGGER.info(
                            "Drift detected: remote manifest timestamp {} differs from base {}. Running 3-way merge.",
                            remoteLatest.timestamp(), lastSyncedManifest.timestamp());
                    listener.onProgress("Merging concurrent changes...", 0, 0, 0, 0);
                    WorldMerger.MergeReport report = WorldMerger.merge(
                            s3, worldDir, lastSyncedManifest, remoteLatest, listener);
                    EssentialSyncMod.LOGGER.info("Merge complete: {}", report);

                    // Re-scan after merge — file contents changed
                    listener.onProgress("Re-hashing merged world files...", 0, 0, 0, 0);
                    localFiles = ManifestManager.scanLocalWorld(worldDir);

                    // Update knownRemoteHashes to include everything from the merged-in session
                    // so we don't re-upload blobs that were part of User1's upload
                    lastSyncedManifest = remoteLatest;
                }
            }
        }

        Set<String> knownRemoteHashes = new HashSet<>();
        if (lastSyncedManifest != null) {
            for (ManifestManager.FileEntry entry : lastSyncedManifest.files().values()) {
                knownRemoteHashes.add(entry.sha256());
            }
        }

        List<Map.Entry<String, ManifestManager.FileEntry>> candidates = new ArrayList<>();
        long totalBytes = 0L;
        for (Map.Entry<String, ManifestManager.FileEntry> entry : localFiles.entrySet()) {
            String hash = entry.getValue().sha256();
            if (!knownRemoteHashes.contains(hash)) {
                candidates.add(entry);
                totalBytes += Math.max(0L, entry.getValue().size());
            }
        }

        int totalFiles = candidates.size();
        if (totalFiles > 0) {
            AtomicLong uploadedBytes = new AtomicLong(0);
            AtomicInteger completedFiles = new AtomicInteger(0);
            long finalTotalBytes = Math.max(1L, totalBytes);
            listener.onProgress("Uploading changed world files...", 0, finalTotalBytes, 0, totalFiles);

            // Use virtual-thread executor — each upload blocks on I/O, virtual threads handle
            // thousands of concurrent I/O-bound tasks without consuming OS threads.
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Map.Entry<String, ManifestManager.FileEntry> entry : candidates) {
                    futures.add(pool.submit(() -> {
                        String rel = entry.getKey();
                        ManifestManager.FileEntry fileEntry = entry.getValue();
                        String objKey = "objects/" + fileEntry.sha256();
                        // Skip headObject — knownRemoteHashes already tells us what's on S3.
                        // If the hash isn't in our last manifest we don't know if it's on S3,
                        // so we PUT; Cloudflare R2 handles idempotent PUTs cheaply.
                        s3.putFile(objKey, worldDir.resolve(rel), fileEntry.sha256());
                        long bytesNow = uploadedBytes.addAndGet(Math.max(0L, fileEntry.size()));
                        int filesNow = completedFiles.incrementAndGet();
                        listener.onProgress("Uploading " + rel, bytesNow, finalTotalBytes, filesNow, totalFiles);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw new RuntimeException(cause);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        listener.onProgress("Committing world manifest...", totalBytes, totalBytes, totalFiles, totalFiles);
        long now = System.currentTimeMillis();
        ManifestManager.Manifest newManifest = new ManifestManager.Manifest(
                now,
                myUuid,
                myName,
                collectLocalMods(),
                localFiles
        );
        String manifestJson = ManifestManager.toJson(newManifest);
        s3.putString("manifests/" + now + ".json", manifestJson, "application/json");
        s3.putString("latest.json", manifestJson, "application/json");
        lastSyncedManifest = newManifest;

        if (isQuitFinal) {
            listener.onProgress("Pruning old snapshots...", totalBytes, totalBytes, totalFiles, totalFiles);
            try {
                ManifestManager.pruneSnapshotsAndObjects(s3, SyncConfig.MAX_SNAPSHOTS.get());
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.warn("Snapshot pruning failed: {}", e.getMessage());
            }
        }
    }

    private static void deleteFileAndEmptyParents(Path rootDir, Path file) {
        try {
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            while (parent != null && !parent.equals(rootDir) && Files.isDirectory(parent)) {
                try (Stream<Path> entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(parent);
                        parent = parent.getParent();
                    } else {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            EssentialSyncMod.LOGGER.warn("Failed to delete orphaned file or empty directory {}: {}", file, e.getMessage());
        }
    }
}
