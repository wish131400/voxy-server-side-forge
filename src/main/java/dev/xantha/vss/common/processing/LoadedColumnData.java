package dev.xantha.vss.common.processing;

import java.util.Arrays;

public record LoadedColumnData(
        int chunkX,
        int chunkZ,
        byte[] sectionBytes,
        int sizeBytes,
        boolean completeColumn,
        int[] sectionYs) {
    public LoadedColumnData(int chunkX, int chunkZ, byte[] sectionBytes, int sizeBytes, boolean completeColumn) {
        this(chunkX, chunkZ, sectionBytes, sizeBytes, completeColumn, new int[0]);
    }

    public LoadedColumnData {
        sectionBytes = sectionBytes != null ? sectionBytes : new byte[0];
        sectionYs = sectionYs != null ? Arrays.copyOf(sectionYs, sectionYs.length) : new int[0];
    }

    @Override
    public int[] sectionYs() {
        return Arrays.copyOf(sectionYs, sectionYs.length);
    }
}
