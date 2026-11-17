package com.essentialsync.sync;

import com.essentialsync.EssentialSyncMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Swaps the singleplayer host's Data.Player NBT in level.dat with playerdata/<uuid>.dat
 * so each friend who hosts via Essential retains their own inventory, position, and Ender Chest.
 */
public final class PlayerDataSwapper {
    private PlayerDataSwapper() {
    }

    /**
     * Called after downstream sync and before the integrated server boots.
     * Injects playerdata/<myUuid>.dat into level.dat's Data.Player, or removes Data.Player
     * if this player has not hosted before so they spawn fresh at world spawn.
     */
    public static void prepareHostPlayerData(Path worldDir, String myUuid) throws IOException {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return;
        }

        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        Optional<CompoundTag> dataOpt = root.getCompound("Data");
        if (dataOpt.isEmpty()) {
            return;
        }
        CompoundTag dataTag = dataOpt.get();

        Path myPlayerDat = worldDir.resolve("playerdata").resolve(myUuid + ".dat");
        if (Files.isRegularFile(myPlayerDat)) {
            CompoundTag playerTag = NbtIo.readCompressed(myPlayerDat, NbtAccounter.unlimitedHeap());
            dataTag.put("Player", playerTag);
            EssentialSyncMod.LOGGER.info("Injected playerdata/{}.dat into level.dat Data.Player", myUuid);
        } else if (dataTag.contains("Player")) {
            dataTag.remove("Player");
            EssentialSyncMod.LOGGER.info("Removed previous host's Data.Player from level.dat for fresh host {}", myUuid);
        }

        NbtIo.writeCompressed(root, levelDat);
    }

    /**
     * Called before upstream upload.
     * Extracts level.dat's Data.Player into playerdata/<myUuid>.dat.
     */
    public static void extractHostPlayerData(Path worldDir, String myUuid) throws IOException {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return;
        }

        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        Optional<CompoundTag> dataOpt = root.getCompound("Data");
        if (dataOpt.isEmpty()) {
            return;
        }
        Optional<CompoundTag> playerOpt = dataOpt.get().getCompound("Player");
        if (playerOpt.isEmpty()) {
            return;
        }

        Path playerDataDir = worldDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);
        Path myPlayerDat = playerDataDir.resolve(myUuid + ".dat");
        NbtIo.writeCompressed(playerOpt.get(), myPlayerDat);
        EssentialSyncMod.LOGGER.info("Extracted level.dat Data.Player into playerdata/{}.dat", myUuid);
    }
}
