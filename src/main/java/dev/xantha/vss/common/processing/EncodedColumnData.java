package dev.xantha.vss.common.processing;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32C;

public record EncodedColumnData(
        int chunkX,
        int chunkZ,
        int compression,
        int rawSize,
        byte[] encodedBytes,
        long columnStamp,
        int schemaVersion,
        boolean completeColumn,
        int[] sectionYs,
        int encodedCrc32c) {
    /** Bumped because older snapshots discarded the per-cell biome palette. */
    public static final int SCHEMA_VERSION = 4;

    public EncodedColumnData(
            int chunkX, int chunkZ, int compression, int rawSize, byte[] encodedBytes,
            long columnStamp, int schemaVersion, boolean completeColumn) {
        this(chunkX, chunkZ, compression, rawSize, encodedBytes, columnStamp, schemaVersion,
                completeColumn, new int[0], crc32c(encodedBytes));
    }

    public EncodedColumnData {
        encodedBytes = encodedBytes != null ? encodedBytes : new byte[0];
        sectionYs = sectionYs != null ? Arrays.copyOf(sectionYs, sectionYs.length) : new int[0];
        if (encodedCrc32c == 0 && encodedBytes.length > 0) {
            encodedCrc32c = crc32c(encodedBytes);
        }
    }

    public static EncodedColumnData encode(LoadedColumnData rawColumn, long columnStamp) throws IOException {
        if (rawColumn == null || rawColumn.sectionBytes() == null) {
            throw new IOException("Missing raw LOD column data");
        }

        LodByteCompression.Result encoded = LodByteCompression.compressForStorage(rawColumn.sectionBytes());
        return new EncodedColumnData(
                rawColumn.chunkX(),
                rawColumn.chunkZ(),
                encoded.method(),
                encoded.originalLength(),
                encoded.bytes(),
                columnStamp,
                SCHEMA_VERSION,
                rawColumn.completeColumn(),
                rawColumn.sectionYs(),
                crc32c(encoded.bytes()));
    }

    public static EncodedColumnData encodeZstd(LoadedColumnData rawColumn, long columnStamp) throws IOException {
        return encode(rawColumn, columnStamp);
    }

    public EncodedColumnData withColumnStamp(long columnStamp) {
        if (this.columnStamp == columnStamp) {
            return this;
        }
        return new EncodedColumnData(chunkX, chunkZ, compression, rawSize, encodedBytes, columnStamp, schemaVersion,
                completeColumn, sectionYs, encodedCrc32c);
    }

    public int encodedSize() {
        return encodedBytes != null ? encodedBytes.length : 0;
    }

    public boolean hasBody() {
        return encodedBytes != null && encodedBytes.length > 0 && rawSize > 0;
    }

    public boolean isCurrentZstdSchema() {
        return compression == LodByteCompression.METHOD_ZSTD && schemaVersion == SCHEMA_VERSION;
    }

    @Override
    public int[] sectionYs() {
        return Arrays.copyOf(sectionYs, sectionYs.length);
    }

    public static int crc32c(byte[] bytes) {
        CRC32C crc = new CRC32C();
        if (bytes != null) {
            crc.update(bytes, 0, bytes.length);
        }
        return (int) crc.getValue();
    }
}
