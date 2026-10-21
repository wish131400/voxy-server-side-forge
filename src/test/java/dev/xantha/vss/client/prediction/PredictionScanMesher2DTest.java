package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PredictionScanMesher2DTest {
    @Test void installedVoxyFixturesHaveTheSameCountsAndExactMaterialCoverage() {
        long[] cells = new long[1024];
        Arrays.fill(cells, 1); assertEquals(4, check(cells, 32, 16, 16));
        Arrays.fill(cells, 0);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) cells[y * 32 + x] = 1;
        assertEquals(1, check(cells, 32, 16, 16));
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) cells[y * 32 + x] = 1 + ((x + y) & 1);
        assertEquals(1024, check(cells, 32, 16, 16));
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) cells[y * 32 + x] = (x + y) & 1;
        assertEquals(512, check(cells, 32, 16, 16));
        Random random = new Random(713);
        for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(4);
        assertEquals(499, check(cells, 32, 16, 16));
    }

    @Test void unequalDimensionsHolesAndEveryCapIncludingOneRestoreWithoutOverlap() {
        var random = new Random(811);
        for (int width : new int[]{1, 2, 7, 16, 32, 64}) for (int height : new int[]{1, 3, 19, 33}) {
            long[] cells = new long[width * height];
            for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(3);
            for (int capX : new int[]{1, 2, 5, 16, 64}) for (int capY : new int[]{1, 2, 7, 16, 64})
                check(cells, width, capX, capY);
            Arrays.fill(cells, Long.MIN_VALUE); check(cells, width, 1, 1);
        }
    }

    @Test void emissionCanReenterAndExceptionDoesNotPoisonWorkerScratch() {
        PredictionScanMesher2D.scan(2, 2, 1, 1, (x, y) -> 1,
                (x, y, w, h, key) -> assertEquals(1, check(new long[]{2, 2, 2, 2}, 2, 2, 2)));
        assertThrows(IllegalStateException.class, () -> PredictionScanMesher2D.scan(2, 2, 1, 1,
                (x, y) -> { throw new IllegalStateException(); }, (x, y, w, h, key) -> { }));
        assertEquals(1, check(new long[]{1, 1, 1, 1}, 2, 2, 2));
    }

    static int check(long[] input, int width, int capX, int capY) {
        int height = input.length / width;
        long[] restored = new long[input.length]; int[] faces = {0};
        PredictionScanMesher2D.scan(width, height, capX, capY, (x, y) -> input[y * width + x],
                (x, y, w, h, key) -> {
                    assertTrue(key != 0 && w > 0 && h > 0 && w <= capX && h <= capY);
                    assertTrue(x >= 0 && y >= 0 && x + w <= width && y + h <= height);
                    for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) {
                        assertEquals(0, restored[yy * width + xx], "overlapping rectangle");
                        restored[yy * width + xx] = key;
                    }
                    faces[0]++;
                });
        assertArrayEquals(input, restored); return faces[0];
    }
}
