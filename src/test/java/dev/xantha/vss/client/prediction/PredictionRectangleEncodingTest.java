package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionRectangleEncodingTest {
    @Test void everyAxisWindingScaleAndUnsignedEndpointRoundTripsWithExceptionalModels() {
        Random random = new Random(23423);
        int[] words = new int[4096 * 12];
        for (int q = 0; q < 4096; q++) {
            int p = q * 12, n = q % 3, u = (n + 1) % 3, v = (n + 2) % 3;
            int[] xyz = random.ints(12, 0, 65536).toArray();
            for (int c = 1; c < 4; c++) for (int a = 0; a < 3; a++) xyz[c * 3 + a] = xyz[a];
            xyz[6 + u] = 65535 - xyz[u]; xyz[6 + v] = 65535 - xyz[v];
            xyz[3 + (q % 2 == 0 ? u : v)] = xyz[6 + (q % 2 == 0 ? u : v)];
            xyz[9 + (q % 2 == 0 ? v : u)] = xyz[6 + (q % 2 == 0 ? v : u)];
            for (int a = 0; a < 3; a++) for (int pair = 0; pair < 2; pair++)
                words[p + (a == 0 ? 0 : a == 2 ? 2 : 4) + pair] = xyz[pair * 6 + a] | xyz[pair * 6 + 3 + a] << 16;
            int flags = random.nextInt() & ~(3 << 24);
            words[p + 6] = flags | (n == 1 ? 0 : n == 0 ? 1 : 2) << 24;
            words[p + 7] = q % 11; words[p + 8] = q % 2 == 0 ? 65535 : q;
            words[p + 9] = 0xfe000000 | q % 11; words[p + 10] = 0xf1000000; words[p + 11] = -1;
            if (q % 97 == 0) words[p + 4] ^= 1; // nonplanar exception, never approximated
        }
        var colors = PredictionGpuEncoding.encode(words);
        var result = PredictionGpuEncoding.rectangles(colors, 4096);
        assertTrue(result.paletteBaseTexel() < 0);
        assertTrue(result.words().length * 4L < colors.words().length * 3L);
        assertArrayEquals(words, PredictionGpuEncoding.decodeGpu(result, 4096));
        assertArrayEquals(result.words(), PredictionGpuEncoding.rectangles(colors, 4096).words());
    }

    @Test void exceptionalHeavyAndSmallMeshesRetainTheOriginalLayout() {
        int[] words = new int[128 * 12];
        var encoded = PredictionGpuEncoding.encode(words);
        assertSame(encoded, PredictionGpuEncoding.rectangles(encoded, 128));
        var small = new PredictionGpuEncoding.Encoded(new int[12], 0);
        assertSame(small, PredictionGpuEncoding.rectangles(small, 1));
    }

    @Test void seamFactoryPreservesSourceGeometryWithoutCreatingDisplayCopies() {
        int[] words = PredictionDisplayGeometryTest.grid(0);
        var seam = PredictionPackedMesh.seamRecords(words, 64);
        assertEquals(words.length, seam.quads().length);
        assertFalse(seam.hasDisplayLod());
        for (int tier = 1; tier <= 3; tier++) assertSame(seam.drawRanges(false, 31), seam.drawRanges(false, 31, tier));
    }
}
