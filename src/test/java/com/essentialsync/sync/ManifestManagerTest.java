package com.essentialsync.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ManifestManagerTest {
    @Test
    void testTrackedPaths() {
        // Files that should be tracked
        assertTrue(ManifestManager.isTrackedPath("level.dat"));
        assertTrue(ManifestManager.isTrackedPath("level.dat_old"));
        assertTrue(ManifestManager.isTrackedPath("icon.png"));
        assertTrue(ManifestManager.isTrackedPath("essential-world-settings.json"));
        assertTrue(ManifestManager.isTrackedPath("datapacks/my_pack.zip"));
        assertTrue(ManifestManager.isTrackedPath("datapacks/my_pack/pack.mcmeta"));
        assertTrue(ManifestManager.isTrackedPath("serverconfig/mod-server.toml"));
        assertTrue(ManifestManager.isTrackedPath("region/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("entities/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("poi/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("data/map_0.dat"));
        assertTrue(ManifestManager.isTrackedPath("data/idcounts.dat"));
        assertTrue(ManifestManager.isTrackedPath("playerdata/3cb41c28-1ebf-45fb-99e9-22cfc11f696b.dat"));
        assertTrue(ManifestManager.isTrackedPath("stats/3cb41c28-1ebf-45fb-99e9-22cfc11f696b.json"));
        assertTrue(ManifestManager.isTrackedPath("advancements/3cb41c28-1ebf-45fb-99e9-22cfc11f696b.json"));
        assertTrue(ManifestManager.isTrackedPath("DIM-1/region/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("DIM1/region/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("dimensions/twilightforest/twilight_forest/region/r.0.0.mca"));
        assertTrue(ManifestManager.isTrackedPath("ftbchunks/server/players.snbt"));
        assertTrue(ManifestManager.isTrackedPath("waystones/waystones.dat"));
        assertTrue(ManifestManager.isTrackedPath("forcedchunks.dat"));

        // Files that must NOT be tracked
        assertFalse(ManifestManager.isTrackedPath("session.lock"));
        assertFalse(ManifestManager.isTrackedPath("DIM-1/session.lock"));
        assertFalse(ManifestManager.isTrackedPath("level.dat_new"));
        assertFalse(ManifestManager.isTrackedPath("somefile.tmp"));
        assertFalse(ManifestManager.isTrackedPath("somefile.temp"));
        assertFalse(ManifestManager.isTrackedPath("somefile.part"));
        assertFalse(ManifestManager.isTrackedPath(".sync_backups/world_prev_1/level.dat"));
        assertFalse(ManifestManager.isTrackedPath("Thumbs.db"));
        assertFalse(ManifestManager.isTrackedPath(".DS_Store"));
        assertFalse(ManifestManager.isTrackedPath(".git/config"));
        assertFalse(ManifestManager.isTrackedPath(".gitignore"));
    }
}
