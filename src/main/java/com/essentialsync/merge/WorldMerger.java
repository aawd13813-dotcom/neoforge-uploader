package com.essentialsync.merge;

import com.essentialsync.EssentialSyncMod;
import com.essentialsync.s3.S3Client;
import com.essentialsync.sync.ManifestManager;
import com.essentialsync.sync.WorldSyncService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * 3-way world merger: reconciles User2's local changes with User1's already-uploaded
 * session, using the shared base snapshot as the common ancestor.
 *
 * <h3>How it integrates with the upload flow</h3>
 * <pre>
 *   User2 starts playing → saves lastSyncedManifest (snapshot S)
 *   User1 plays, quits  → uploads Session A → latest.json = A
 *   User2 quits         → performUpstreamSync detects drift (latest != S)
 *                       → WorldMerger.merge(s3, worldDir, S, A, listener)
 *                       → localFiles re-scanned
 *                       → merged result uploaded as new latest.json
 * </pre>
 *
 * <h3>Per-file merge strategy</h3>
 * <table>
 *   <tr><th>File type</th><th>Strategy</th></tr>
 *   <tr><td>playerdata/, stats/, advancements/ (by UUID)</td>
 *       <td>Always keep both — no UUID collision possible between two players</td></tr>
 *   <tr><td>region/*.mca, entities/*.mca, poi/*.mca</td>
 *       <td>Chunk-level 3-way merge via {@link RegionFileMerger}</td></tr>
 *   <tr><td>Everything else</td>
 *       <td>Only A changed → apply A.  Both changed → take newer mtime.</td></tr>
 * </table>
 */
public final class WorldMerger {

    /** Matches any region-format binary file (region, entities, poi sub-dirs). */
    private static final Pattern REGION_FILE =
            Pattern.compile("^(region|entities|poi)/r\\.-?\\d+\\.-?\\d+\\.mc[ar]$");

    /** Matches per-UUID player files that can never conflict between two different players. */
    private static final Pattern PLAYER_UUID_FILE =
            Pattern.compile("^(playerdata|stats|advancements)/[0-9a-f-]{36}(\\.|$)");

    private WorldMerger() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Full merge result summary returned to the caller.
     *
     * @param filesFromA       files whose content was taken entirely from User1's session
     * @param chunkConflicts   individual chunks where both users edited the same chunk
     * @param mcaMerged        region files that went through chunk-level 3-way merge
     * @param genericConflicts non-region files where both changed; resolved by mtime
     */
    public record MergeReport(
            int filesFromA,
            int chunkConflicts,
            int mcaMerged,
            int genericConflicts
    ) {
        public boolean hadAnyConflicts() { return chunkConflicts > 0 || genericConflicts > 0; }

        @Override
        public String toString() {
            return String.format(
                    "MergeReport{filesFromA=%d, mcaMerged=%d, chunkConflicts=%d, genericConflicts=%d}",
                    filesFromA, mcaMerged, chunkConflicts, genericConflicts);
        }
    }

    /**
     * Merges User1's uploaded session into User2's local world directory.
     * After this call, {@code worldDir} contains the reconciled world ready for upload.
     *
     * @param s3        S3 client for downloading base and User1's object blobs
     * @param worldDir  User2's local world directory (modified in-place)
     * @param base      manifest of the snapshot User2 started playing from
     *                  (may be {@code null} if User2 started from a fresh world)
     * @param userA     manifest User1 uploaded (the new latest.json)
     * @param listener  progress callback
     * @return a {@link MergeReport} with merge statistics
     */
    public static MergeReport merge(
            S3Client s3,
            Path worldDir,
            ManifestManager.Manifest base,
            ManifestManager.Manifest userA,
            WorldSyncService.ProgressListener listener
    ) throws Exception {

        Map<String, ManifestManager.FileEntry> baseFiles = base  != null ? base.files()  : Map.of();
        Map<String, ManifestManager.FileEntry> aFiles    = userA != null ? userA.files() : Map.of();

        // Scan User2's current local state
        listener.onProgress("Merge: scanning local world...", 0, 0, 0, 0);
        Map<String, ManifestManager.FileEntry> bFiles = ManifestManager.scanLocalWorld(worldDir);

        // All relative paths across all three manifests
        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(baseFiles.keySet());
        allPaths.addAll(aFiles.keySet());
        allPaths.addAll(bFiles.keySet());

        // ----------------------------------------------------------------
        // Phase 1: classify each path
        // ----------------------------------------------------------------
        List<String> onlyAFiles    = new ArrayList<>(); // A changed, B didn't
        List<String> mcaConflicts  = new ArrayList<>(); // both changed, it's an .mca
        List<String> genericConflicts = new ArrayList<>(); // both changed, non-.mca

        for (String rel : allPaths) {
            ManifestManager.FileEntry baseEntry = baseFiles.get(rel);
            ManifestManager.FileEntry aEntry    = aFiles.get(rel);
            ManifestManager.FileEntry bEntry    = bFiles.get(rel);

            String baseHash = baseEntry != null ? baseEntry.sha256() : null;
            String aHash    = aEntry    != null ? aEntry.sha256()    : null;
            String bHash    = bEntry    != null ? bEntry.sha256()    : null;

            boolean aChanged = !Objects.equals(aHash, baseHash);
            boolean bChanged = !Objects.equals(bHash, baseHash);

            if (!aChanged) continue; // User1 didn't touch it — User2's local version is correct

            if (!bChanged) {
                // Only User1 changed it
                onlyAFiles.add(rel);
            } else {
                // Both changed
                if (isPlayerUuidFile(rel)) {
                    // Different UUID → no conflict possible; User2's local is already correct
                    // (User1's player-specific file will be fetched separately if it's a NEW player)
                    if (!Objects.equals(aHash, bHash)) {
                        // If the hashes differ but it's player data, they're different UUIDs — keep both
                        // by downloading User1's version under its own UUID path (already in aFiles)
                        onlyAFiles.add(rel); // download A's version too — it's a different player
                    }
                } else if (isRegionFile(rel)) {
                    mcaConflicts.add(rel);
                } else {
                    genericConflicts.add(rel);
                }
            }
        }

        int totalWork = onlyAFiles.size() + mcaConflicts.size() + genericConflicts.size();
        listener.onProgress("Merge: resolving " + totalWork + " file(s)...", 0, 0, 0, totalWork);

        Path tempDir = Files.createTempDirectory("essentialsync_merge_");
        int chunkConflictTotal = 0;
        int mergedMcaCount     = 0;

        try {
            // ----------------------------------------------------------------
            // Phase 2: apply "only A changed" files (download A's blob locally)
            // ----------------------------------------------------------------
            if (!onlyAFiles.isEmpty()) {
                listener.onProgress("Merge: applying " + onlyAFiles.size() + " file(s) from other player...",
                        0, 0, 0, totalWork);
                applyAFiles(s3, worldDir, aFiles, onlyAFiles, tempDir);
            }

            // ----------------------------------------------------------------
            // Phase 3: chunk-level MCA merge for region conflicts
            // ----------------------------------------------------------------
            for (String rel : mcaConflicts) {
                listener.onProgress("Merge: resolving region " + rel + "...", 0, 0, 0, totalWork);

                ManifestManager.FileEntry baseEntry = baseFiles.get(rel);
                ManifestManager.FileEntry aEntry    = aFiles.get(rel);

                Path baseTemp = downloadToTemp(s3, baseEntry, tempDir, "base_");
                Path aTemp    = downloadToTemp(s3, aEntry,    tempDir, "a_");
                Path localB   = worldDir.resolve(rel);

                RegionFileMerger.MergeResult result = RegionFileMerger.merge(baseTemp, aTemp, localB, localB);
                chunkConflictTotal += result.conflicts();
                mergedMcaCount++;

                EssentialSyncMod.LOGGER.info(
                        "MCA merge {}: +{}A +{}B {}conflict(s) {}unchanged",
                        rel, result.onlyA(), result.onlyB(), result.conflicts(), result.unchanged());
            }

            // ----------------------------------------------------------------
            // Phase 4: generic conflict resolution — take newer mtime
            // ----------------------------------------------------------------
            for (String rel : genericConflicts) {
                ManifestManager.FileEntry aEntry = aFiles.get(rel);
                ManifestManager.FileEntry bEntry = bFiles.get(rel);

                long aTime = aEntry != null ? aEntry.lastModified() : 0L;
                long bTime = bEntry != null ? bEntry.lastModified() : 0L;

                if (aTime > bTime) {
                    // User1's version is newer — overwrite User2's local copy
                    listener.onProgress("Merge: taking newer version of " + rel + "...", 0, 0, 0, totalWork);
                    downloadToLocal(s3, aEntry, worldDir.resolve(rel), tempDir);
                    EssentialSyncMod.LOGGER.info("Merge conflict resolved (newer mtime) → A wins: {}", rel);
                } else {
                    EssentialSyncMod.LOGGER.info("Merge conflict resolved (newer mtime) → B wins: {}", rel);
                }
            }

        } finally {
            // Clean up all temp files
            deleteTempDir(tempDir);
        }

        MergeReport report = new MergeReport(
                onlyAFiles.size(),
                chunkConflictTotal,
                mergedMcaCount,
                genericConflicts.size()
        );
        EssentialSyncMod.LOGGER.info("World merge complete: {}", report);
        return report;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Downloads all "only A changed" files in parallel using virtual threads. */
    private static void applyAFiles(
            S3Client s3,
            Path worldDir,
            Map<String, ManifestManager.FileEntry> aFiles,
            List<String> relPaths,
            Path tempDir
    ) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (String rel : relPaths) {
                ManifestManager.FileEntry entry = aFiles.get(rel);
                if (entry == null) {
                    // A deleted this file — if B still has it, leave it (B's newer local copy)
                    continue;
                }
                futures.add(pool.submit(() -> {
                    downloadToLocal(s3, entry, worldDir.resolve(rel), tempDir);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof Exception ex) throw ex;
                    throw new RuntimeException(cause);
                }
            }
        }
    }

    /**
     * Downloads an S3 object blob to User2's local world path, atomically.
     * Uses a temp file inside {@code tempDir} to avoid partial writes.
     */
    private static void downloadToLocal(
            S3Client s3,
            ManifestManager.FileEntry entry,
            Path dest,
            Path tempDir
    ) throws IOException, InterruptedException {
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(tempDir, "dl_", ".tmp");
        try {
            boolean ok = s3.downloadFile("objects/" + entry.sha256(), tmp);
            if (!ok) {
                EssentialSyncMod.LOGGER.warn("Merge: S3 blob missing for hash {}", entry.sha256());
                return;
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Downloads an S3 object blob to a temp file inside {@code tempDir} and returns the path.
     * Returns {@code null} if the entry is null (file absent in that snapshot).
     */
    private static Path downloadToTemp(
            S3Client s3,
            ManifestManager.FileEntry entry,
            Path tempDir,
            String prefix
    ) throws IOException, InterruptedException {
        if (entry == null) return null;
        Path tmp = Files.createTempFile(tempDir, prefix, ".mca.tmp");
        boolean ok = s3.downloadFile("objects/" + entry.sha256(), tmp);
        if (!ok) {
            Files.deleteIfExists(tmp);
            return null;
        }
        return tmp;
    }

    private static boolean isRegionFile(String rel) {
        return REGION_FILE.matcher(rel).matches();
    }

    private static boolean isPlayerUuidFile(String rel) {
        return PLAYER_UUID_FILE.matcher(rel).matches();
    }

    private static void deleteTempDir(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
