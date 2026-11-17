package com.essentialsync.sync;

import com.essentialsync.s3.MiniJson;
import com.essentialsync.s3.S3Client;
import com.essentialsync.s3.S3Signer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Handles content-addressable manifest serialization, streaming SHA-256 hashing,
 * tracked path filtering, and snapshot retention/garbage collection.
 * Zero Minecraft/NeoForge imports so it can be shared with CrashWatchdogMain.
 */
public final class ManifestManager {
    private static final int HASH_BUFFER_SIZE = 65536; // 64 KB — much faster than 8 KB for large region files

    /**
     * Cache: absolute path → (size, lastModified, sha256).
     * Avoids re-reading + re-hashing files that haven't changed between syncs.
     * Keyed by absolute path string so it works across world folders.
     */
    private record HashCacheEntry(long size, long lastModified, String sha256) {}
    private static final ConcurrentHashMap<String, HashCacheEntry> HASH_CACHE = new ConcurrentHashMap<>();

    private ManifestManager() {
    }

    public record FileEntry(String sha256, long size, long lastModified) {
    }

    public record Manifest(
            long timestamp,
            String hostUuid,
            String hostName,
            Map<String, String> mods,
            Map<String, FileEntry> files
    ) {
    }

    /**
     * Determines whether a relative unix path inside the world directory should be tracked and synchronized.
     * Tracks all world data (regions, custom dimensions, datapacks, serverconfig, playerdata, items, maps,
     * advancements, stats, icon.png, essential settings, and mod data) while excluding runtime lock files,
     * temporary files, OS metadata, and sync backup directories.
     */
    public static boolean isTrackedPath(String relativeUnixPath) {
        if (relativeUnixPath == null || relativeUnixPath.isEmpty()) {
            return false;
        }

        String path = relativeUnixPath.replace('\\', '/');
        int lastSlash = path.lastIndexOf('/');
        String fileName = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;

        // 1. Session lock and other runtime lock files must never be synced across instances
        if (fileName.equals("session.lock") || fileName.endsWith(".lock")) {
            return false;
        }

        // 2. Temporary and partial files
        if (fileName.equals("level.dat_new")
                || fileName.endsWith(".tmp")
                || fileName.endsWith(".temp")
                || fileName.endsWith(".part")
                || fileName.endsWith(".crdownload")) {
            return false;
        }

        // 3. Operating system junk/metadata files
        if (fileName.equalsIgnoreCase("thumbs.db")
                || fileName.equalsIgnoreCase("desktop.ini")
                || fileName.equalsIgnoreCase(".ds_store")
                || fileName.startsWith("._")) {
            return false;
        }

        // 4. Internal sync backups or temp directories (if created inside world folder)
        if (path.startsWith(".sync_") || path.contains("/.sync_")) {
            return false;
        }

        // 5. Version control metadata (if user initialized git in world folder)
        if (path.startsWith(".git/") || path.contains("/.git/") || fileName.equals(".gitignore")) {
            return false;
        }

        return true;
    }

    public static String sha256File(Path file) throws IOException {
        // Check cache first — if size + mtime match, return cached hash
        try {
            long size = Files.size(file);
            long lastMod = Files.getLastModifiedTime(file).toMillis();
            String key = file.toAbsolutePath().toString();
            HashCacheEntry cached = HASH_CACHE.get(key);
            if (cached != null && cached.size() == size && cached.lastModified() == lastMod) {
                return cached.sha256();
            }
            String hash = sha256FileRaw(file);
            HASH_CACHE.put(key, new HashCacheEntry(size, lastMod, hash));
            return hash;
        } catch (IOException e) {
            throw e;
        }
    }

    private static String sha256FileRaw(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream dis = new DigestInputStream(in, md)) {
            while (dis.read(buffer) != -1) {
                // DigestInputStream updates md as bytes are read
            }
        }
        return S3Signer.toHex(md.digest());
    }

    public static Map<String, FileEntry> scanLocalWorld(Path worldDir) throws IOException {
        Map<String, FileEntry> result = new TreeMap<>();
        if (!Files.isDirectory(worldDir)) {
            return result;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(worldDir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }

        // Hash all files in parallel using virtual threads — I/O bound, so many can run concurrently
        record FileScanResult(String rel, FileEntry entry) {}
        List<java.util.concurrent.Future<FileScanResult>> futures = new ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path file : files) {
                String rel = worldDir.relativize(file).toString().replace('\\', '/');
                if (!isTrackedPath(rel)) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    long size = Files.size(file);
                    long lastMod = Files.getLastModifiedTime(file).toMillis();
                    String hash = sha256File(file);
                    return new FileScanResult(rel, new FileEntry(hash, size, lastMod));
                }));
            }
        }

        for (var f : futures) {
            try {
                FileScanResult r = f.get();
                result.put(r.rel(), r.entry());
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("Error hashing world file", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while scanning world", e);
            }
        }
        return result;
    }

    public static String toJson(Manifest manifest) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", manifest.timestamp());
        root.put("hostUuid", manifest.hostUuid());
        root.put("hostName", manifest.hostName());
        root.put("mods", new TreeMap<>(manifest.mods()));

        Map<String, Object> filesMap = new LinkedHashMap<>();
        for (Map.Entry<String, FileEntry> entry : new TreeMap<>(manifest.files()).entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sha256", entry.getValue().sha256());
            f.put("size", entry.getValue().size());
            f.put("lastModified", entry.getValue().lastModified());
            filesMap.put(entry.getKey(), f);
        }
        root.put("files", filesMap);
        return MiniJson.stringifyPretty(root);
    }

    public static Manifest fromJson(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        long timestamp = MiniJson.getLong(root, "timestamp", 0L);
        String hostUuid = MiniJson.getString(root, "hostUuid", "");
        String hostName = MiniJson.getString(root, "hostName", "");

        Map<String, String> mods = new TreeMap<>();
        for (Map.Entry<String, Object> e : MiniJson.getObject(root, "mods").entrySet()) {
            if (e.getValue() != null) {
                mods.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        Map<String, FileEntry> files = new TreeMap<>();
        for (Map.Entry<String, Object> e : MiniJson.getObject(root, "files").entrySet()) {
            if (e.getValue() instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fMap = (Map<String, Object>) raw;
                String sha256 = MiniJson.getString(fMap, "sha256", "");
                long size = MiniJson.getLong(fMap, "size", 0L);
                long lastMod = MiniJson.getLong(fMap, "lastModified", 0L);
                if (!sha256.isEmpty()) {
                    files.put(e.getKey(), new FileEntry(sha256, size, lastMod));
                }
            }
        }
        return new Manifest(timestamp, hostUuid, hostName, mods, files);
    }

    public static void pruneSnapshotsAndObjects(S3Client s3, int maxSnapshots) throws IOException, InterruptedException {
        List<String> manifestKeys = new ArrayList<>(s3.listObjects("manifests/"));
        manifestKeys.removeIf(k -> !k.endsWith(".json"));
        manifestKeys.sort(Comparator.comparingLong(ManifestManager::extractEpochFromKey).reversed());

        List<String> keptManifests = new ArrayList<>();
        for (int i = 0; i < manifestKeys.size(); i++) {
            String key = manifestKeys.get(i);
            if (i < maxSnapshots) {
                keptManifests.add(key);
            } else {
                s3.deleteObject(key);
            }
        }

        Set<String> referencedHashes = new HashSet<>();
        Optional<String> latestJson = s3.getString("latest.json");
        if (latestJson.isPresent()) {
            Manifest latest = fromJson(latestJson.get());
            for (FileEntry entry : latest.files().values()) {
                referencedHashes.add(entry.sha256());
            }
        }

        for (String manifestKey : keptManifests) {
            Optional<String> json = s3.getString(manifestKey);
            if (json.isPresent()) {
                Manifest m = fromJson(json.get());
                for (FileEntry entry : m.files().values()) {
                    referencedHashes.add(entry.sha256());
                }
            }
        }

        List<String> objectKeys = s3.listObjects("objects/");
        for (String objKey : objectKeys) {
            if (!objKey.startsWith("objects/")) {
                continue;
            }
            String hash = objKey.substring("objects/".length());
            if (!hash.isEmpty() && !referencedHashes.contains(hash)) {
                s3.deleteObject(objKey);
            }
        }
    }

    private static long extractEpochFromKey(String key) {
        String name = key;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
