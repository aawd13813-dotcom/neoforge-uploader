package com.essentialsync.sync;

import com.essentialsync.EssentialSyncMod;
import com.essentialsync.SyncConfig;
import com.essentialsync.s3.MiniJson;
import com.essentialsync.s3.S3Client;
import com.essentialsync.watchdog.CrashWatchdogMain;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the distributed S3 host lock (lock.json), 60-second heartbeat thread,
 * and detached CrashWatchdog JVM lifecycle.
 */
public final class LockManager {
    public static final long LOCK_TTL_MS = 180_000L; // 3 minutes
    public static final long HEARTBEAT_INTERVAL_SEC = 60L;

    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "EssentialSync-LockHeartbeat");
        t.setDaemon(true);
        return t;
    });

    private static volatile ScheduledFuture<?> heartbeatTask;
    private static volatile Process watchdogProcess;
    private static volatile boolean holdingLock = false;
    private static volatile long acquiredAtMs = 0L;

    private LockManager() {
    }

    public record LockInfo(String holderUuid, String holderName, long acquiredAt, long expiresAt) {
        public boolean isActive(long nowMs) {
            return expiresAt > nowMs;
        }
    }

    public static Optional<LockInfo> fetchLock(S3Client s3) throws IOException, InterruptedException {
        Optional<String> json = s3.getString("lock.json");
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> map = MiniJson.parseObject(json.get());
            return Optional.of(new LockInfo(
                    MiniJson.getString(map, "holderUuid", ""),
                    MiniJson.getString(map, "holderName", "Unknown"),
                    MiniJson.getLong(map, "acquiredAt", 0L),
                    MiniJson.getLong(map, "expiresAt", 0L)
            ));
        } catch (Exception e) {
            EssentialSyncMod.LOGGER.warn("Failed to parse remote lock.json: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static synchronized void acquireLock(
            S3Client s3,
            Path worldDir,
            String myUuid,
            String myName,
            Map<String, String> modsMap
    ) throws IOException, InterruptedException, WorldSyncService.WorldLockedException {
        long now = System.currentTimeMillis();
        acquiredAtMs = now;
        uploadLock(s3, myUuid, myName, acquiredAtMs, now + LOCK_TTL_MS);

        // Race-condition guard: wait 1.5 s then re-read the lock.
        // If two clients PUT lock.json at the same instant, the last PUT wins.
        // The loser will see a different holderUuid and throw WorldLockedException.
        try { Thread.sleep(1_500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
        Optional<LockInfo> verify = fetchLock(s3);
        if (verify.isPresent()) {
            LockInfo live = verify.get();
            if (!myUuid.equals(live.holderUuid())) {
                // Someone else won the race — treat as locked
                throw new WorldSyncService.WorldLockedException(live);
            }
        }

        holdingLock = true;

        stopHeartbeatOnly();
        heartbeatTask = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            if (!holdingLock) {
                return;
            }
            try {
                long t = System.currentTimeMillis();
                uploadLock(s3, myUuid, myName, acquiredAtMs, t + LOCK_TTL_MS);
            } catch (Exception e) {
                EssentialSyncMod.LOGGER.warn("Lock heartbeat update failed: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        spawnWatchdog(worldDir, myUuid, myName, modsMap);

        s3.postDiscordWebhookAsync(
                SyncConfig.DISCORD_WEBHOOK_URL.get(),
                "🟢 **" + myName + "** acquired the host lock for **" + worldDir.getFileName() + "**."
        );
    }

    public static synchronized void releaseLock(S3Client s3, String myName, String worldFolderName) {
        holdingLock = false;
        stopHeartbeatOnly();

        try {
            s3.deleteObject("lock.json");
            EssentialSyncMod.LOGGER.info("Released remote lock.json for {}", worldFolderName);
        } catch (Exception e) {
            EssentialSyncMod.LOGGER.error("Failed to delete lock.json on quit: {}", e.getMessage());
        }

        destroyWatchdog();

        s3.postDiscordWebhookAsync(
                SyncConfig.DISCORD_WEBHOOK_URL.get(),
                "⚪ **" + myName + "** finished syncing and released the host lock for **" + worldFolderName + "**."
        );
    }

    public static boolean isHoldingLock() {
        return holdingLock;
    }

    private static void uploadLock(S3Client s3, String uuid, String name, long acquiredAt, long expiresAt)
            throws IOException, InterruptedException {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("holderUuid", uuid);
        lock.put("holderName", name);
        lock.put("acquiredAt", acquiredAt);
        lock.put("expiresAt", expiresAt);
        s3.putString("lock.json", MiniJson.stringifyPretty(lock), "application/json");
    }

    private static void stopHeartbeatOnly() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private static void destroyWatchdog() {
        if (watchdogProcess != null) {
            try {
                watchdogProcess.destroy();
            } catch (Exception ignored) {
            }
            watchdogProcess = null;
        }
    }

    private static void spawnWatchdog(Path worldDir, String myUuid, String myName, Map<String, String> modsMap) {
        destroyWatchdog();
        try {
            String javaBin = ProcessHandle.current().info().command().orElse("java");
            String classpath = resolveWatchdogClasspath();

            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-Xmx64m",
                    "-XX:+UseSerialGC",
                    "-cp",
                    classpath,
                    CrashWatchdogMain.class.getName()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("parentPid", ProcessHandle.current().pid());
            payload.put("worldPath", worldDir.toAbsolutePath().toString());
            payload.put("myUuid", myUuid);
            payload.put("myName", myName);
            payload.put("modsMap", modsMap);
            payload.put("endpointUrl", SyncConfig.ENDPOINT_URL.get());
            payload.put("region", SyncConfig.REGION.get());
            payload.put("bucketName", SyncConfig.BUCKET_NAME.get());
            payload.put("accessKeyId", SyncConfig.ACCESS_KEY_ID.get());
            payload.put("secretAccessKey", SyncConfig.SECRET_ACCESS_KEY.get());
            payload.put("discordWebhookUrl", SyncConfig.DISCORD_WEBHOOK_URL.get());
            payload.put("maxSnapshots", SyncConfig.MAX_SNAPSHOTS.get());

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(MiniJson.stringify(payload).getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            watchdogProcess = proc;
            EssentialSyncMod.LOGGER.info("Spawned CrashWatchdog process (PID {})", proc.pid());
        } catch (Exception e) {
            EssentialSyncMod.LOGGER.error("Failed to spawn CrashWatchdog process: {}", e.getMessage());
        }
    }

    private static String resolveWatchdogClasspath() {
        try {
            URI location = EssentialSyncMod.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String raw = location.toString();
            // Handle ModLauncher union:/... or jar:file:/... URIs if present
            if (raw.startsWith("union:")) {
                raw = raw.substring("union:".length());
                int hashIdx = raw.indexOf('#');
                if (hashIdx > 0) {
                    raw = raw.substring(0, hashIdx);
                }
                if (!raw.startsWith("file:")) {
                    raw = "file:" + raw;
                }
                location = URI.create(raw);
            }
            Path codePath = Path.of(location);
            if (Files.exists(codePath)) {
                return codePath.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
        }
        return System.getProperty("java.class.path", "");
    }
}
