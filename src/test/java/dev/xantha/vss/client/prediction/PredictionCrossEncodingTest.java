package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PredictionCrossEncodingTest {
    @Test void bothDiagonalsAllScalesAndCornerMetadataRoundTripExactly() {
        int[] words = new int[4096 * 12];
        Random random = new Random(71103);
        for (int q = 0; q < 4096; q++) {
            int x = random.nextInt(15000), z = random.nextInt(15000), width = 1 + random.nextInt(200);
            int y = random.nextInt(65000), height = 1 + random.nextInt(500);
            cross(words, q, q % 2 == 0 ? x : x + width, z, y,
                    q % 2 == 0 ? x + width : x, z + width, y + height, q % 2 + 1);
            int p = q * 12;
            words[p + 6] |= (q % 16) << 16 | (q % 3 == 0 ? PredictionPackedMesh.FLAG_FINE_COORDINATES : 0);
            words[p + 8] = q % 2 == 0 ? 65535 : q;
            words[p + 7] = q % 7;
            words[p + 9] = (q % 7) | 0xf9000000;
            words[p + 10] = (q % 7) | 0xfd000000;
            words[p + 11] = (q % 7) | 0xe0000000;
        }
        var colors = PredictionGpuEncoding.encode(words);
        assertTrue(colors.paletteBaseTexel() > 0);
        var compact = PredictionGpuEncoding.rectangles(colors, 4096);
        assertTrue(compact.paletteBaseTexel() < 0);
        assertEquals(4096 * 20 + 7 * 16, compact.words().length * 4);
        assertArrayEquals(words, PredictionGpuEncoding.decodeGpu(compact, 4096));
    }

    @Test void mixedRectanglesWideCrossesIrregularModelsAndFluidsKeepExactFallbacks() {
        int[] words = new int[512 * 12];
        for (int q = 0; q < 512; q++) cross(words, q, 3, 4, 32768, 4, 5, 32776, q % 2 + 1);
        for (int q = 0; q < 64; q++) {
            int p = q * 12;
            switch (q % 8) {
                case 0 -> cross(words, q, 65535, 65535, 0, 0, 0, 65535, 1);
                case 1 -> cross(words, q, 0, 0, 0, 16384, 1, 65535, 1);
                case 2 -> words[p + 4] ^= 1; // unequal bottom heights
                case 3 -> words[p] ^= 1; // endpoint 0 no longer agrees with endpoint 3
                case 4 -> words[p + 6] |= PredictionPackedMesh.FLAG_MODEL_UV;
                case 5 -> words[p + 6] |= 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT;
                case 6 -> words[p + 6] &= ~PredictionPackedMesh.FLAG_UNSHADED;
                case 7 -> words[p + 6] = PredictionPackedMesh.FLAG_CUTOUT; // no cross marker
            }
        }
        var colors = PredictionGpuEncoding.encode(words);
        var compact = PredictionGpuEncoding.rectangles(colors, 512);
        assertTrue(compact.paletteBaseTexel() < 0);
        assertArrayEquals(words, PredictionGpuEncoding.decodeGpu(compact, 512));
        assertTrue(compact.words().length < colors.words().length);
        var pass = PredictionGpuEncoding.selectPass(words, 0, 512, 400, 24, false, true);
        var selected = PredictionGpuEncoding.rectangles(pass, 488);
        assertArrayEquals(PredictionGpuEncoding.decode(pass.words(), pass.paletteBaseTexel(), 488),
                PredictionGpuEncoding.decodeGpu(selected, 488));
    }

    @Test void unrepresentableCrossOnlyMeshesKeepTheOldLayout() {
        int[] words = new int[128 * 12];
        for (int q = 0; q < 128; q++) cross(words, q, 20000, 20000, 32768, 20001, 20001, 32776, q % 2 + 1);
        var colors = PredictionGpuEncoding.encode(words);
        assertSame(colors, PredictionGpuEncoding.rectangles(colors, 128));
    }

    @Test void compressedColdRestoreRebuildsTheCompactPassWithoutChangingDiskGeometry() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int[] words = new int[4096 * 12];
        for (int q = 0; q < 4096; q++) cross(words, q, q % 64, q / 64, 33024,
                q % 64 + 1, q / 64 + 1, 33028, q % 2 + 1);
        var payload = PredictionPackedMesh.terrainRecords(words, 64);
        int[] canonical = payload.quads().clone();
        payload.prepareGpuStorage();
        int[] upload = payload.opaqueUploadWords().clone();
        assertTrue(payload.opaquePaletteBaseTexel() < 0);
        payload.prepareStorage(); assertTrue(payload.compressed());
        payload.uploaded(); PredictionMeshRestore.clear();
        try {
            assertArrayEquals(canonical, payload.restoreWords());
            assertNotNull(PredictionMeshCompressionTest.awaitUpload(payload));
            assertArrayEquals(upload, payload.opaqueUploadWords());
        } finally { PredictionMeshRestore.clear(); }
    }

    static void cross(int[] words, int q, int x0, int z0, int y0, int x2, int z2, int y2, int axis) {
        int p = q * 12;
        words[p] = x0 | x2 << 16; words[p + 1] = x2 | x0 << 16;
        words[p + 2] = z0 | z2 << 16; words[p + 3] = z2 | z0 << 16;
        words[p + 4] = y0 | y0 << 16; words[p + 5] = y2 | y2 << 16;
        words[p + 6] = axis << 24 | PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_CUTOUT | 1;
        words[p + 7] = words[p + 9] = words[p + 11] = 0x709020;
        words[p + 10] = 0x709020 | PredictionPackedMesh.FEATURE;
    }
}
