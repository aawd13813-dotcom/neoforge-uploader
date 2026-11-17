package com.essentialsync;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

public final class SyncConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable or disable EssentialSync world synchronization.")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> ENDPOINT_URL = BUILDER
            .comment("S3-compatible endpoint URL (e.g., https://<accountid>.r2.cloudflarestorage.com)")
            .define("endpointUrl", "");

    public static final ModConfigSpec.ConfigValue<String> REGION = BUILDER
            .comment("S3 region (use 'auto' for Cloudflare R2)")
            .define("region", "auto");

    public static final ModConfigSpec.ConfigValue<String> BUCKET_NAME = BUILDER
            .comment("S3 bucket name")
            .define("bucketName", "minecraft-sync");

    public static final ModConfigSpec.ConfigValue<String> ACCESS_KEY_ID = BUILDER
            .comment("S3 access key ID")
            .define("accessKeyId", "");

    public static final ModConfigSpec.ConfigValue<String> SECRET_ACCESS_KEY = BUILDER
            .comment("S3 secret access key")
            .define("secretAccessKey", "");

    /**
     * List of local world folder names (inside .minecraft/saves/) managed by EssentialSync.
     * Each world gets its own isolated S3 prefix — no collisions between worlds.
     * Add worlds via the in-game pause menu button, or edit this file directly.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SYNCED_WORLDS = BUILDER
            .comment("List of exact local folder names inside .minecraft/saves/ managed by EssentialSync.",
                     "Each world has its own isolated S3 prefix. Add via pause menu or edit directly.")
            .defineListAllowEmpty("syncedWorlds", List.of(), o -> o instanceof String s && !s.isBlank());

    public static final ModConfigSpec.IntValue AUTO_SYNC_INTERVAL_MINUTES = BUILDER
            .comment("Interval in minutes between periodic background delta uploads while hosting")
            .defineInRange("autoSyncIntervalMinutes", 10, 1, 60);

    public static final ModConfigSpec.IntValue MAX_SNAPSHOTS = BUILDER
            .comment("Number of historical world manifests to retain for rollback safety")
            .defineInRange("maxSnapshots", 8, 2, 30);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for host lock acquire, release, and crash-recovery notifications")
            .define("discordWebhookUrl", "");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SyncConfig() {
    }

    public static boolean isConfigured() {
        return ENABLED.get()
                && !ENDPOINT_URL.get().isBlank()
                && !BUCKET_NAME.get().isBlank()
                && !ACCESS_KEY_ID.get().isBlank()
                && !SECRET_ACCESS_KEY.get().isBlank()
                && !SYNCED_WORLDS.get().isEmpty();
    }

    /** Returns true if the given world folder name is in the synced list. */
    public static boolean isSyncedWorld(String folderName) {
        return isConfigured() && SYNCED_WORLDS.get().contains(folderName);
    }

    public static final int MAX_SYNCED_WORLDS = 3;

    /** Adds a world to the synced list and saves the config.
     *  @return {@code true} if added, {@code false} if already in list or limit reached. */
    public static boolean addSyncedWorld(String folderName) {
        if (folderName == null || folderName.isBlank()) return false;
        List<String> current = new ArrayList<>(SYNCED_WORLDS.get());
        if (current.contains(folderName)) return false;
        if (current.size() >= MAX_SYNCED_WORLDS) return false; // hard cap
        current.add(folderName);
        SYNCED_WORLDS.set(current);
        SPEC.save();
        return true;
    }

    /** Removes a world from the synced list and saves the config. */
    public static void removeSyncedWorld(String folderName) {
        List<String> current = new ArrayList<>(SYNCED_WORLDS.get());
        if (current.remove(folderName)) {
            SYNCED_WORLDS.set(current);
            SPEC.save();
        }
    }
}
