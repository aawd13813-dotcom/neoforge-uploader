# EssentialSync

**EssentialSync** is a NeoForge Minecraft mod for Minecraft 1.21.11 that seamlessly synchronizes singleplayer and LAN worlds to S3-compatible cloud storage (Cloudflare R2, AWS S3, MinIO, etc.). It enables playing the same world across multiple machines without manual file transfers or dedicated server hosting.

---

## Features

- **S3 / Cloudflare R2 Sync**: Secure cloud synchronization using pure Java HTTP clients and AWS Signature Version 4.
- **Delta Uploads & Downloads**: Generates cryptographic manifests of world directories to only upload and download changed files.
- **Chunk-Level MCA Region Merging**: Advanced Anvil region file merger (`RegionFileMerger`) that compares and merges modified chunks individually rather than replacing entire 10MB+ `.mca` files.
- **Concurrency & Lock Safety**: Distributed locking (`LockManager`) prevents two players or sessions from simultaneously modifying and corrupting the same world state.
- **In-Game UI Integration**:
  - Cloud status indicators and badges in the world select screen.
  - Interactive sync progress screens with cancel support.
  - Conflict warning dialogs with options to force take over, merge, or cancel.
- **Crash Watchdog & Player Data Safeguard**: Independent crash watchdog (`CrashWatchdogMain`) and standalone NBT reader to ensure player inventories and locations are saved even if Minecraft crashes.
- **Local Backup Rotation**: Automatic rolling local backups prior to syncing to prevent data loss.

---

## Configuration

Configuration is managed via `config/essentialsync-client.toml` (or through the in-game config menu):

| Key | Description | Default |
|---|---|---|
| `enabled` | Enable or disable cloud synchronization | `true` |
| `endpointUrl` | S3-compatible endpoint URL (e.g. `https://<accountid>.r2.cloudflarestorage.com`) | `""` |
| `region` | S3 region (`auto` for Cloudflare R2) | `"auto"` |
| `bucketName` | Target S3 bucket name | `"minecraft-sync"` |
| `accessKeyId` | S3 Access Key ID | `""` |
| `secretAccessKey` | S3 Secret Access Key | `""` |
| `syncedWorlds` | List of local world folder names managed by EssentialSync | `[]` |
| `autoSyncIntervalMinutes` | Interval in minutes between periodic delta uploads while playing | `10` |
| `maxSnapshots` | Number of historical world manifests to retain for rollback safety | `5` |

---

## Building from Source

### Requirements
- **Java 21** or newer
- **Gradle** (included via `./gradlew`)

### Build Commands

```bash
# Build the mod jar
./gradlew build

# Run Minecraft client in development environment
./gradlew runClient
```

Output `.jar` files will be generated in `build/libs/`.

---

## Project Structure

```
src/main/java/com/essentialsync/
├── EssentialSyncMod.java          # Main mod initialization
├── SyncConfig.java                # Client & common configuration spec
├── client/                        # UI screens, event handlers, HUD overlays
│   ├── SyncBlockedScreen.java
│   ├── SyncEventHandler.java
│   ├── SyncHudOverlay.java
│   ├── SyncProgressScreen.java
│   └── WorldListOverlayHandler.java
├── merge/                         # Region & world conflict resolution
│   ├── RegionFileMerger.java
│   └── WorldMerger.java
├── mixin/                         # Mixins into Minecraft world selection & loading
│   ├── SelectWorldScreenMixin.java
│   ├── WorldListEntryMixin.java
│   └── WorldOpenFlowsMixin.java
├── s3/                            # S3 / R2 client, SigV4 signer, lightweight JSON parser
│   ├── MiniJson.java
│   ├── S3Client.java
│   └── S3Signer.java
├── sync/                          # Manifest hashing, delta sync, locks, backups
│   ├── LocalBackupManager.java
│   ├── LockManager.java
│   ├── ManifestManager.java
│   ├── PlayerDataSwapper.java
│   └── WorldSyncService.java
└── watchdog/                      # Independent crash watcher and standalone NBT parser
    ├── CrashWatchdogMain.java
    └── StandaloneNbtPlayerExtractor.java
```

---

## License

This project is licensed under the terms specified in [TEMPLATE_LICENSE.txt](file:///c:/Users/benlu/Desktop/Minecraft/neoforge-uploader/TEMPLATE_LICENSE.txt) and the mod source files.
