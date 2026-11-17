package com.essentialsync.watchdog;

import com.essentialsync.s3.MiniJson;
import com.essentialsync.s3.S3Client;
import com.essentialsync.sync.ManifestManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Detached background JVM process spawned when the host lock is acquired.
 * Uses only Java 21 standard library + pure-Java S3Client/ManifestManager/StandaloneNbtPlayerExtractor
 * (zero Minecraft/NeoForge imports) so it starts in <150ms and recovers the world if Minecraft crashes.
 */
public final class CrashWatchdogMain {
    private CrashWatchdogMain() {
    }

    public static void main(String[] args) {
        try {
            byte[] stdinBytes = System.in.readAllBytes();
            String payloadJson = new String(stdinBytes, StandardCharsets.UTF_8).trim();
            if (payloadJson.isEmpty()) {
                return;
            }

            Map<String, Object> cfg = MiniJson.parseObject(payloadJson);
            long parentPid = MiniJson.getLong(cfg, "parentPid", -1L);
            Path worldPath = Path.of(MiniJson.getString(cfg, "worldPath", ""));
            String myUuid = MiniJson.getString(cfg, "myUuid", "");
            String myName = MiniJson.getString(cfg, "myName", "");
            String endpointUrl = MiniJson.getString(cfg, "endpointUrl", "");
            String region = MiniJson.getString(cfg, "region", "auto");
            String bucketName = MiniJson.getString(cfg, "bucketName", "");
            String accessKeyId = MiniJson.getString(cfg, "accessKeyId", "");
            String secretAccessKey = MiniJson.getString(cfg, "secretAccessKey", "");
            String discordWebhookUrl = MiniJson.getString(cfg, "discordWebhookUrl", "");
            int maxSnapshots = (int) MiniJson.getLong(cfg, "maxSnapshots", 8L);

            Map<String, String> modsMap = new TreeMap<>();
            for (Map.Entry<String, Object> e : MiniJson.getObject(cfg, "modsMap").entrySet()) {
                if (e.getValue() != null) {
                    modsMap.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }

            if (parentPid <= 0 || !Files.isDirectory(worldPath)) {
                return;
            }

            // 1. Block passively until the parent Minecraft JVM exits
            ProcessHandle.of(parentPid).ifPresent(handle -> handle.onExit().join());

            // 2. Sleep 2,000 ms so Windows releases file locks on .mca files and session.lock
            Thread.sleep(2_000L);

            String worldFolderName = worldPath.getFileName().toString();
            S3Client s3 = new S3Client(endpointUrl, region, bucketName, worldFolderName, accessKeyId, secretAccessKey);

            // 3. Fetch lock.json from S3; if missing or holderUuid != myUuid, exit immediately (clean quit already handled)
            Optional<String> lockJsonOpt = s3.getString("lock.json");
            if (lockJsonOpt.isEmpty()) {
                return;
            }
            Map<String, Object> lockMap = MiniJson.parseObject(lockJsonOpt.get());
            String holderUuid = MiniJson.getString(lockMap, "holderUuid", "");
            if (!myUuid.equals(holderUuid)) {
                return;
            }

            // 4. Corruption Guard on level.dat / level.dat_old
            Path levelDat = worldPath.resolve("level.dat");
            Path levelDatOld = worldPath.resolve("level.dat_old");
            boolean levelDatValid = StandaloneNbtPlayerExtractor.isValidGzipNbt(levelDat);
            if (!levelDatValid) {
                if (StandaloneNbtPlayerExtractor.isValidGzipNbt(levelDatOld)) {
                    Files.copy(levelDatOld, levelDat, StandardCopyOption.REPLACE_EXISTING);
                    levelDatValid = true;
                }
            }

            // 5. Extract Data.Player from level.dat into playerdata/<myUuid>.dat
            if (levelDatValid && !myUuid.isEmpty()) {
                Path playerDat = worldPath.resolve("playerdata").resolve(myUuid + ".dat");
                try {
                    StandaloneNbtPlayerExtractor.extractPlayerToFile(levelDat, playerDat);
                } catch (Exception ignored) {
                }
            }

            // 6. Content-addressable delta upload
            ManifestManager.Manifest previousManifest = null;
            Optional<String> latestJson = s3.getString("latest.json");
            if (latestJson.isPresent()) {
                try {
                    previousManifest = ManifestManager.fromJson(latestJson.get());
                } catch (Exception ignored) {
                }
            }

            Map<String, ManifestManager.FileEntry> localFiles = ManifestManager.scanLocalWorld(worldPath);
            if (!levelDatValid) {
                // Never upload a corrupted level.dat
                localFiles.remove("level.dat");
                if (previousManifest != null && previousManifest.files().containsKey("level.dat")) {
                    localFiles.put("level.dat", previousManifest.files().get("level.dat"));
                }
            }

            Map<String, String> knownHashes = new LinkedHashMap<>();
            if (previousManifest != null) {
                for (ManifestManager.FileEntry entry : previousManifest.files().values()) {
                    knownHashes.put(entry.sha256(), entry.sha256());
                }
            }

            for (Map.Entry<String, ManifestManager.FileEntry> entry : localFiles.entrySet()) {
                String relPath = entry.getKey();
                ManifestManager.FileEntry fileEntry = entry.getValue();
                String hash = fileEntry.sha256();
                if (knownHashes.containsKey(hash)) {
                    continue;
                }
                String objKey = "objects/" + hash;
                if (!s3.headObject(objKey)) {
                    s3.putFile(objKey, worldPath.resolve(relPath), hash);
                }
                knownHashes.put(hash, hash);
            }

            long now = System.currentTimeMillis();
            ManifestManager.Manifest newManifest = new ManifestManager.Manifest(
                    now,
                    myUuid,
                    myName,
                    modsMap,
                    localFiles
            );
            String newManifestJson = ManifestManager.toJson(newManifest);
            s3.putString("manifests/" + now + ".json", newManifestJson, "application/json");
            s3.putString("latest.json", newManifestJson, "application/json");

            try {
                ManifestManager.pruneSnapshotsAndObjects(s3, maxSnapshots);
            } catch (IOException | InterruptedException ignored) {
            }

            // Delete lock.json and post crash-recovery notice
            s3.deleteObject("lock.json");
            s3.postDiscordWebhookBlocking(
                    discordWebhookUrl,
                    "⚠️ **" + myName + "**'s game exited unexpectedly while hosting **" + worldFolderName
                            + "**. CrashWatchdog recovered and uploaded the latest world state and released the host lock."
            );
        } catch (Throwable t) {
            System.err.println("[EssentialSync CrashWatchdog] Error: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }
}
