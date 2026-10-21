package dev.xantha.vss.common.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EncodedColumnDataTest {
    @Test
    void legacyConstructorsKeepEmptyBodyAndAutomaticChecksumDefaults() {
        LoadedColumnData loaded = new LoadedColumnData(2, 3, null, 0, false);
        assertArrayEquals(new byte[0], loaded.sectionBytes());
        EncodedColumnData empty = new EncodedColumnData(2, 3, 0, 0, null, 9L,
                EncodedColumnData.SCHEMA_VERSION, false);
        assertArrayEquals(new byte[0], empty.encodedBytes());

        byte[] body = {1, 2, 3};
        EncodedColumnData legacy = new EncodedColumnData(2, 3, 0, body.length, body, 9L,
                EncodedColumnData.SCHEMA_VERSION, true, new int[0], 0);
        assertEquals(EncodedColumnData.crc32c(body), legacy.encodedCrc32c());
        EncodedColumnData explicit = new EncodedColumnData(2, 3, 0, body.length, body, 9L,
                EncodedColumnData.SCHEMA_VERSION, true, new int[0], new int[0], 0, ColumnCoverage.FULL);
        assertEquals(0, explicit.encodedCrc32c());
    }

    @Test
    void encodedColumnCarriesDefensiveManifestAndFrameChecksum() throws Exception {
        int[] sectionYs = {-4, 0, 12};
        LoadedColumnData loaded = new LoadedColumnData(2, 3, new byte[] {3, 1, 2, 3}, 4, true, sectionYs);

        EncodedColumnData encoded = EncodedColumnData.encode(loaded, 9L);
        sectionYs[0] = 99;
        int[] returned = encoded.sectionYs();
        returned[1] = 99;

        assertArrayEquals(new int[] {-4, 0, 12}, encoded.sectionYs());
        assertEquals(EncodedColumnData.crc32c(encoded.encodedBytes()), encoded.encodedCrc32c());
        assertEquals(4, encoded.schemaVersion());
    }
}
