package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Scanline rectangle meshing: equal horizontal runs extend into the next row.
 * Independent implementation of the strategy used by Voxy's section mesher.
 * Zero denotes empty space; callers supply complete material/light keys. */
final class PredictionScanMesher2D {
    @FunctionalInterface interface Cells { long at(int x, int y); }
    @FunctionalInterface interface Output { void rectangle(int x, int y, int width, int height, long key); }

    private static final ThreadLocal<Rows> ROWS = ThreadLocal.withInitial(Rows::new);
    private static final class Rows {
        long[] keys = new long[32];
        int[] widths = new int[32], heights = new int[32];
        boolean busy;
        void reset(int width) {
            if (keys.length < width) {
                keys = new long[width]; widths = new int[width]; heights = new int[width];
            } else Arrays.fill(keys, 0, width, 0);
        }
    }

    private PredictionScanMesher2D() { }

    static void scan(int width, int height, int maxWidth, int maxHeight, Cells cells, Output output) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0)
            throw new IllegalArgumentException("positive scan dimensions required");
        Rows rows = ROWS.get();
        if (rows.busy) rows = new Rows();
        rows.busy = true; rows.reset(width);
        try {
            for (int y = 0; y < height; y++) {
                long runKey = 0;
                int length = 0;
                for (int x = 0; x < width; x++) {
                    long key = cells.at(x, y);
                    if (key != runKey || length == maxWidth) {
                        if (runKey != 0) {
                            rows.keys[x - 1] = runKey;
                            rows.widths[x - 1] = length;
                            rows.heights[x - 1] = 1;
                        }
                        runKey = key; length = 0;
                    }
                    length++;
                    if (runKey != 0 && rows.keys[x] == runKey && rows.widths[x] == length) {
                        rows.heights[x]++;
                        runKey = 0; length = 0;
                        if (rows.heights[x] < maxHeight) continue;
                        output.rectangle(x - rows.widths[x] + 1, y - rows.heights[x] + 1,
                                rows.widths[x], rows.heights[x], rows.keys[x]);
                        rows.keys[x] = 0;
                    } else if (rows.keys[x] != 0) {
                        output.rectangle(x - rows.widths[x] + 1, y - rows.heights[x],
                                rows.widths[x], rows.heights[x], rows.keys[x]);
                        rows.keys[x] = 0;
                    }
                }
                if (runKey != 0) {
                    rows.keys[width - 1] = runKey;
                    rows.widths[width - 1] = length;
                    rows.heights[width - 1] = 1;
                }
                if (maxHeight == 1) for (int x = 0; x < width; x++) if (rows.keys[x] != 0) {
                    output.rectangle(x - rows.widths[x] + 1, y, rows.widths[x], 1, rows.keys[x]);
                    rows.keys[x] = 0;
                }
            }
            for (int x = 0; x < width; x++) if (rows.keys[x] != 0)
                output.rectangle(x - rows.widths[x] + 1, height - rows.heights[x],
                        rows.widths[x], rows.heights[x], rows.keys[x]);
        } finally { rows.busy = false; }
    }
}
