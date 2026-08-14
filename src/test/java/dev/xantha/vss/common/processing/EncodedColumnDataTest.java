package dev.xantha.vss.common.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EncodedColumnDataTest {
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
        assertEquals(3, encoded.schemaVersion());
    }
}
