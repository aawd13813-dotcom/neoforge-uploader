package com.essentialsync.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Chunk-level 3-way merge of Minecraft region (.mca / .mcr) files.
 *
 * <h3>MCA file layout</h3>
 * <pre>
 *   Bytes    0 –  4095  Location table  (1024 × 4 bytes)
 *                         [0..2] = sector offset from file start (big-endian 24-bit)
 *                         [3]    = sector count (1 sector = 4096 bytes)
 *                         All-zero entry → chunk absent
 *   Bytes 4096 –  8191  Timestamp table (1024 × 4 bytes, big-endian int32, seconds)
 *   Bytes 8192+         Chunk data, packed in 4096-byte sectors
 *                         First 4 bytes = data length (big-endian int32)
 *                         Next 1 byte   = compression type (1=GZip, 2=Zlib, 3=None)
 *                         Remaining     = compressed NBT
 * </pre>
 *
 * <h3>Merge strategy (per chunk position 0..1023)</h3>
 * <ul>
 *   <li>Only A changed from base → use A</li>
 *   <li>Only B changed from base → use B</li>
 *   <li>Neither changed         → use base</li>
 *   <li>Both changed (conflict) → use whichever has the newer timestamp;
 *       ties go to A</li>
 * </ul>
 *
 * Zero external dependencies — safe to use from the detached watchdog JVM.
 */
public final class RegionFileMerger {

    private static final int SECTOR_SIZE    = 4096;
    private static final int HEADER_SECTORS = 2;      // location table + timestamp table
    static final int         CHUNK_COUNT    = 1024;   // 32 × 32

    private RegionFileMerger() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result of a 3-way region merge.
     *
     * @param onlyA      chunks taken from A (User1 changed, User2 didn't)
     * @param onlyB      chunks taken from B (User2 changed, User1 didn't)
     * @param conflicts  chunks where both changed; resolved by newer timestamp
     * @param unchanged  chunks identical in both A and B relative to base
     */
    public record MergeResult(int onlyA, int onlyB, int conflicts, int unchanged) {
        public int total() { return onlyA + onlyB + conflicts + unchanged; }
    }

    /**
     * 3-way merge of region files, writing the result to {@code dest}.
     *
     * @param base path to the base snapshot .mca (may be {@code null} if the
     *             region didn't exist in the base snapshot)
     * @param a    path to User1's .mca (may be {@code null})
     * @param b    path to User2's .mca (may be {@code null})
     * @param dest output path (may be the same as {@code b} — safe, written atomically)
     * @return merge statistics
     */
    public static MergeResult merge(Path base, Path a, Path b, Path dest) throws IOException {
        byte[][] baseChunks = base != null && Files.exists(base) ? readAllChunks(base) : new byte[CHUNK_COUNT][];
        byte[][] aChunks    = a    != null && Files.exists(a)    ? readAllChunks(a)    : new byte[CHUNK_COUNT][];
        byte[][] bChunks    = b    != null && Files.exists(b)    ? readAllChunks(b)    : new byte[CHUNK_COUNT][];
        int[]    baseTs     = base != null && Files.exists(base) ? readTimestamps(base) : new int[CHUNK_COUNT];
        int[]    aTs        = a    != null && Files.exists(a)    ? readTimestamps(a)    : new int[CHUNK_COUNT];
        int[]    bTs        = b    != null && Files.exists(b)    ? readTimestamps(b)    : new int[CHUNK_COUNT];

        byte[][] merged   = new byte[CHUNK_COUNT][];
        int[]    mergedTs = new int[CHUNK_COUNT];

        int cntOnlyA = 0, cntOnlyB = 0, cntConflict = 0, cntUnchanged = 0;

        for (int i = 0; i < CHUNK_COUNT; i++) {
            byte[] bc = baseChunks[i];
            byte[] ac = aChunks[i];
            byte[] bch = bChunks[i];

            boolean aChanged = !Arrays.equals(ac, bc);
            boolean bChanged = !Arrays.equals(bch, bc);

            if (!aChanged && !bChanged) {
                // Neither changed — use base (may be null = absent)
                merged[i]   = bc;
                mergedTs[i] = baseTs[i];
                cntUnchanged++;
            } else if (aChanged && !bChanged) {
                // Only A changed
                merged[i]   = ac;
                mergedTs[i] = aTs[i];
                cntOnlyA++;
            } else if (!aChanged) {
                // Only B changed
                merged[i]   = bch;
                mergedTs[i] = bTs[i];
                cntOnlyB++;
            } else {
                // Both changed — resolve by timestamp (newer wins; ties → A)
                if (aTs[i] >= bTs[i]) {
                    merged[i]   = ac;
                    mergedTs[i] = aTs[i];
                } else {
                    merged[i]   = bch;
                    mergedTs[i] = bTs[i];
                }
                cntConflict++;
            }
        }

        // Check if the entire merged region is empty (all chunks absent)
        boolean anyChunk = false;
        for (byte[] chunk : merged) {
            if (chunk != null) { anyChunk = true; break; }
        }

        if (anyChunk) {
            writeMca(merged, mergedTs, dest);
        } else {
            // No chunks — delete the file if it existed (clean up)
            Files.deleteIfExists(dest);
        }

        return new MergeResult(cntOnlyA, cntOnlyB, cntConflict, cntUnchanged);
    }

    // -------------------------------------------------------------------------
    // MCA reading
    // -------------------------------------------------------------------------

    /**
     * Reads all 1024 chunk payloads from an .mca file.
     * Each element is the raw bytes at that chunk's sector position (4-byte length
     * prefix + compression byte + compressed NBT), or {@code null} if the chunk
     * is absent in the file.
     */
    static byte[][] readAllChunks(Path mca) throws IOException {
        byte[] file = Files.readAllBytes(mca);
        byte[][] chunks = new byte[CHUNK_COUNT][];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int locOff = i * 4;
            if (locOff + 4 > file.length) break;

            int sectorOff   = ((file[locOff]     & 0xFF) << 16)
                            | ((file[locOff + 1] & 0xFF) << 8)
                            |  (file[locOff + 2] & 0xFF);
            int sectorCount  =   file[locOff + 3] & 0xFF;

            if (sectorOff == 0 || sectorCount == 0) {
                // Chunk absent
                chunks[i] = null;
                continue;
            }

            int byteOff = sectorOff * SECTOR_SIZE;
            if (byteOff + 4 > file.length) {
                chunks[i] = null;
                continue;
            }

            int dataLen = ((file[byteOff]     & 0xFF) << 24)
                        | ((file[byteOff + 1] & 0xFF) << 16)
                        | ((file[byteOff + 2] & 0xFF) << 8)
                        |  (file[byteOff + 3] & 0xFF);

            if (dataLen <= 0 || byteOff + 4 + dataLen > file.length) {
                chunks[i] = null;
                continue;
            }

            // Store the 4-byte length prefix + dataLen bytes (compression type + compressed data)
            chunks[i] = Arrays.copyOfRange(file, byteOff, byteOff + 4 + dataLen);
        }
        return chunks;
    }

    static int[] readTimestamps(Path mca) throws IOException {
        byte[] file = Files.readAllBytes(mca);
        int[] ts = new int[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int off = SECTOR_SIZE + i * 4;
            if (off + 4 > file.length) break;
            ts[i] = ((file[off]     & 0xFF) << 24)
                  | ((file[off + 1] & 0xFF) << 16)
                  | ((file[off + 2] & 0xFF) << 8)
                  |  (file[off + 3] & 0xFF);
        }
        return ts;
    }

    // -------------------------------------------------------------------------
    // MCA writing
    // -------------------------------------------------------------------------

    /**
     * Packs the given chunk array into a valid .mca file at {@code dest}.
     * The file is written as a byte array then committed atomically via a temp file.
     */
    static void writeMca(byte[][] chunks, int[] timestamps, Path dest) throws IOException {
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());

        // Assign sectors: each chunk needs ceil(payloadBytes / 4096) sectors.
        int[] sectorOffsets = new int[CHUNK_COUNT];
        int[] sectorCounts  = new int[CHUNK_COUNT];
        int   nextSector    = HEADER_SECTORS; // sectors 0 and 1 are the header tables

        for (int i = 0; i < CHUNK_COUNT; i++) {
            if (chunks[i] == null) continue;
            int payloadLen = chunks[i].length;             // includes the 4-byte length prefix
            int sectors    = (payloadLen + SECTOR_SIZE - 1) / SECTOR_SIZE;
            sectorOffsets[i] = nextSector;
            sectorCounts[i]  = sectors;
            nextSector += sectors;
        }

        byte[] out = new byte[nextSector * SECTOR_SIZE]; // zero-filled by Java

        // Location table (sector 0)
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int off = i * 4;
            if (sectorOffsets[i] > 0) {
                out[off]     = (byte) ((sectorOffsets[i] >> 16) & 0xFF);
                out[off + 1] = (byte) ((sectorOffsets[i] >> 8)  & 0xFF);
                out[off + 2] = (byte) ( sectorOffsets[i]        & 0xFF);
                out[off + 3] = (byte) ( sectorCounts[i]         & 0xFF);
            }
        }

        // Timestamp table (sector 1)
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int off = SECTOR_SIZE + i * 4;
            out[off]     = (byte) ((timestamps[i] >> 24) & 0xFF);
            out[off + 1] = (byte) ((timestamps[i] >> 16) & 0xFF);
            out[off + 2] = (byte) ((timestamps[i] >> 8)  & 0xFF);
            out[off + 3] = (byte) ( timestamps[i]        & 0xFF);
        }

        // Chunk data
        for (int i = 0; i < CHUNK_COUNT; i++) {
            if (chunks[i] == null) continue;
            int byteOff = sectorOffsets[i] * SECTOR_SIZE;
            System.arraycopy(chunks[i], 0, out, byteOff, chunks[i].length);
        }

        // Atomic write via temp file
        Path tmp = dest.resolveSibling(dest.getFileName() + ".mergetmp");
        try {
            Files.write(tmp, out);
            Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
