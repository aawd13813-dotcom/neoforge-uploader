package com.essentialsync.watchdog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure Java 21 GZIP + binary NBT reader/writer with zero Minecraft/NeoForge imports.
 * Used by CrashWatchdogMain to validate level.dat and extract Data.Player to playerdata/<uuid>.dat.
 */
public final class StandaloneNbtPlayerExtractor {
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    private StandaloneNbtPlayerExtractor() {
    }

    private record NbtTag(byte type, Object value) {
    }

    private record RawString(byte[] bytes) {
        String asUtf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private record NbtList(byte elementType, List<Object> elements) {
    }

    /**
     * Verifies that the file is a complete, uncorrupted GZIP-compressed NBT compound.
     */
    public static boolean isValidGzipNbt(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path))))) {
            byte rootType = in.readByte();
            if (rootType != TAG_COMPOUND) {
                return false;
            }
            readRawString(in);
            readCompound(in);
            byte[] drain = new byte[1024];
            while (in.read(drain) != -1) {
                // Ensure GZIP trailer & CRC are read completely without EOFException/ZipException
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extracts Data.Player from level.dat and writes it to playerdata/<uuid>.dat.
     * Returns true if Data.Player was found and written.
     */
    @SuppressWarnings("unchecked")
    public static boolean extractPlayerToFile(Path levelDat, Path playerDatOut) throws IOException {
        Map<String, NbtTag> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(levelDat))))) {
            byte rootType = in.readByte();
            if (rootType != TAG_COMPOUND) {
                return false;
            }
            readRawString(in);
            root = readCompound(in);
        }

        NbtTag dataTag = root.get("Data");
        if (dataTag == null || dataTag.type() != TAG_COMPOUND) {
            return false;
        }
        Map<String, NbtTag> dataMap = (Map<String, NbtTag>) dataTag.value();
        NbtTag playerTag = dataMap.get("Player");
        if (playerTag == null || playerTag.type() != TAG_COMPOUND) {
            return false;
        }

        if (playerDatOut.getParent() != null) {
            Files.createDirectories(playerDatOut.getParent());
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(playerDatOut))))) {
            out.writeByte(TAG_COMPOUND);
            out.writeShort(0); // Empty root name ""
            writeCompound(out, (Map<String, NbtTag>) playerTag.value());
        }
        return true;
    }

    private static RawString readRawString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = in.readNBytes(len);
        if (bytes.length < len) {
            throw new EOFException("Truncated NBT string");
        }
        return new RawString(bytes);
    }

    private static void writeRawString(DataOutputStream out, RawString raw) throws IOException {
        out.writeShort(raw.bytes().length);
        out.write(raw.bytes());
    }

    private static Map<String, NbtTag> readCompound(DataInputStream in) throws IOException {
        Map<String, NbtTag> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == TAG_END) {
                return map;
            }
            RawString name = readRawString(in);
            Object payload = readPayload(in, type);
            map.put(name.asUtf8(), new NbtTag(type, payload));
        }
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                byte[] arr = in.readNBytes(len);
                if (arr.length < len) {
                    throw new EOFException("Truncated TAG_Byte_Array");
                }
                yield arr;
            }
            case TAG_STRING -> readRawString(in);
            case TAG_LIST -> {
                byte elemType = in.readByte();
                int count = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, count));
                for (int i = 0; i < count; i++) {
                    list.add(readPayload(in, elemType));
                }
                yield new NbtList(elemType, list);
            }
            case TAG_COMPOUND -> readCompound(in);
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readInt();
                }
                yield arr;
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readLong();
                }
                yield arr;
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(DataOutputStream out, Map<String, NbtTag> map) throws IOException {
        for (Map.Entry<String, NbtTag> entry : map.entrySet()) {
            NbtTag tag = entry.getValue();
            out.writeByte(tag.type());
            byte[] nameBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeShort(nameBytes.length);
            out.write(nameBytes);
            writePayload(out, tag.type(), tag.value());
        }
        out.writeByte(TAG_END);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, byte type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE -> out.writeByte((Byte) value);
            case TAG_SHORT -> out.writeShort((Short) value);
            case TAG_INT -> out.writeInt((Integer) value);
            case TAG_LONG -> out.writeLong((Long) value);
            case TAG_FLOAT -> out.writeFloat((Float) value);
            case TAG_DOUBLE -> out.writeDouble((Double) value);
            case TAG_BYTE_ARRAY -> {
                byte[] arr = (byte[]) value;
                out.writeInt(arr.length);
                out.write(arr);
            }
            case TAG_STRING -> writeRawString(out, (RawString) value);
            case TAG_LIST -> {
                NbtList list = (NbtList) value;
                out.writeByte(list.elementType());
                out.writeInt(list.elements().size());
                for (Object elem : list.elements()) {
                    writePayload(out, list.elementType(), elem);
                }
            }
            case TAG_COMPOUND -> writeCompound(out, (Map<String, NbtTag>) value);
            case TAG_INT_ARRAY -> {
                int[] arr = (int[]) value;
                out.writeInt(arr.length);
                for (int v : arr) {
                    out.writeInt(v);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] arr = (long[]) value;
                out.writeInt(arr.length);
                for (long v : arr) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }
}
