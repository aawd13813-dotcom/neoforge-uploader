package com.essentialsync.sync;

import com.essentialsync.EssentialSyncMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Maintains a 2-slot rotating local backup under saves/.sync_backups/
 * before downstream sync overwrites or deletes local world files.
 */
public final class LocalBackupManager {
    private LocalBackupManager() {
    }

    public static void createRollingBackup(Path savesDir, String worldFolderName) throws IOException {
        Path worldDir = savesDir.resolve(worldFolderName);
        if (!Files.isDirectory(worldDir)) {
            return;
        }
        try (Stream<Path> list = Files.list(worldDir)) {
            if (list.findAny().isEmpty()) {
                return;
            }
        }

        Path backupsRoot = savesDir.resolve(".sync_backups");
        Files.createDirectories(backupsRoot);

        Path prev1 = backupsRoot.resolve(worldFolderName + "_prev_1");
        Path prev2 = backupsRoot.resolve(worldFolderName + "_prev_2");

        if (Files.exists(prev2)) {
            deleteRecursively(prev2);
        }
        if (Files.exists(prev1)) {
            Files.move(prev1, prev2, StandardCopyOption.REPLACE_EXISTING);
        }

        copyDirectory(worldDir, prev1);
        EssentialSyncMod.LOGGER.info("Created rolling local backup at {}", prev1);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path srcPath : walk.toList()) {
                Path rel = source.relativize(srcPath);
                if (rel.toString().equals("session.lock")) {
                    continue;
                }
                Path destPath = target.resolve(rel);
                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(destPath);
                } else {
                    if (destPath.getParent() != null) {
                        Files.createDirectories(destPath.getParent());
                    }
                    Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    EssentialSyncMod.LOGGER.warn("Failed to delete backup path {}: {}", path, e.getMessage());
                }
            });
        }
    }
}
