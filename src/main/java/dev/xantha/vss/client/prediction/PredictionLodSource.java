package dev.xantha.vss.client.prediction;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Deterministic LOD queries, with no chunk-generation scheduler or live world access.
 * Implementations must support concurrent calls, or keep their mutable state per worker.
 * stepBlocks is the requested LOD spacing, not permission to change random seeds or material IDs.
 */
public interface PredictionLodSource extends AutoCloseable {
    ClientColumnSample sample(int blockX, int blockZ, int stepBlocks);
    default ClientColumnSample preview(int blockX, int blockZ, int stepBlocks) {
        return sample(blockX, blockZ, stepBlocks);
    }
    /** A batch must preserve non-null entries: they may be authoritative captures or cached samples. */
    default void sampleGrid(int x, int z, int step, int width, int height,
                            ClientColumnSample[] target, int offset, int stride, boolean preview) {
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int column = 0; column < width; column++) {
                int at = offset + row * stride + column;
                if (target[at] == null) target[at] = preview
                        ? preview(x + column * step, z + row * step, step)
                        : sample(x + column * step, z + row * step, step);
            }
        }
    }
    default int surfaceY(int x, int z) { return sample(x, z, 1).surfaceY(); }
    /** Increment when the client evaluator's semantics change independently of server parameters. */
    default int cacheRevision() { return 1; }
    /** Sample biomeIndex values refer to this stable list, or the supplied noise context's list. */
    default List<Holder<Biome>> biomes() { return List.of(); }
    default Holder<Biome> biome(int quartX, int quartY, int quartZ) { return null; }
    default boolean interiorTerrain() { return false; }
    default PredictionColumnVolume column(int x, int z) { return null; }
    default int waterTint(int x, int y, int z) { return Integer.MIN_VALUE; }
    default int surfaceColor(int x, int y, int z) { return Integer.MIN_VALUE; }
    default int foliageColor(int x, int y, int z) { return Integer.MIN_VALUE; }
    @Override default void close() { }
}
