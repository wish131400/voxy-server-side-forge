package dev.xantha.vss.client.prediction;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Ensures density and biome queries can see FreeTerraForged's filtered tiles. */
final class FreeTerraForgedTerrainSampler extends ClientTerrainSampler implements AutoCloseable {
    private final FreeTerraForgedCells cells;
    private final AutoCloseable runtimeOwner;
    private final FreeTerraForgedWater water;
    private final FreeTerraForgedSamples exactSamples = new FreeTerraForgedSamples(32768);
    private final FreeTerraForgedSamples previewSamples = new FreeTerraForgedSamples(8192);
    private volatile FreeTerraForgedExterior exterior;
    private boolean closed;

    FreeTerraForgedTerrainSampler(DimensionProfile profile, NoiseBasedChunkGenerator generator,
            RandomState randomState, LevelHeightAccessor heights,
            ClientWorldgenRegistries registries, RegistryAccess clientRegistries) {
        this(profile, generator, randomState, heights, registries, clientRegistries, null);
    }

    FreeTerraForgedTerrainSampler(DimensionProfile profile, NoiseBasedChunkGenerator generator,
            RandomState randomState, LevelHeightAccessor heights,
            ClientWorldgenRegistries registries, RegistryAccess clientRegistries, AutoCloseable runtimeOwner) {
        super(profile.seed(), profile, generator, randomState, heights,
                generator.generatorSettings().value().seaLevel(), registries, clientRegistries);
        try {
            var api = new FreeTerraForgedCompat.Api(getClass().getClassLoader());
            Object context = api.context.invoke(randomState);
            cells = context == null ? null : new FreeTerraForgedCells(context);
            this.runtimeOwner = runtimeOwner;
            water = context == null ? null : new FreeTerraForgedWater(context);
            dev.xantha.vss.common.VSSLogger.info("VSS FreeTerraForged adapter ready: dimension="
                    + profile.dimension() + ", filteredTerrain=" + (cells != null)
                    + ", storage=" + (cells == null ? "inactive" : cells.tiled() ? "tile" : "chunk"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged filtered tile API unavailable", failure);
        }
    }

    private Object prepare(int blockX, int blockZ) {
        return cells == null || FreeTerraForgedDensity.coarse() ? null : cells.cellAt(blockX, blockZ);
    }

    private synchronized FreeTerraForgedExterior exterior() {
        if (closed) throw new java.util.concurrent.CancellationException();
        if (exterior == null) exterior = new FreeTerraForgedExterior(generatorContext(), randomStateContext(),
                LevelHeightAccessor.create(profile().minY(), profile().height()));
        return exterior;
    }

    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top,
            java.util.function.BooleanSupplier valid) {
        return exteriorFootprints(new int[]{x}, new int[]{z}, step, new int[]{bottom}, new int[]{top}, 1, valid)[0];
    }

    @Override boolean[][] exteriorFootprints(int[] xs, int[] zs, int step, int[] bottoms, int[] tops, int count,
            java.util.function.BooleanSupplier valid) {
        return FreeTerraForgedDensity.coarse(false, () -> exterior().sample(xs, zs, step, bottoms, tops, count, valid));
    }

    @Override String densityCompilerDiagnostics() {
        var active = exterior;
        return super.densityCompilerDiagnostics() + ",ftfExterior={" + (active == null ? "state=idle" : active.diagnostics()) + "}"
                + ",ftfSamples={" + exactSamples.diagnostics() + "},ftfPreviews={" + previewSamples.diagnostics() + "}";
    }

    @Override public int surfaceY(int x, int z) { prepare(x, z); return super.surfaceY(x, z); }
    @Override public int groundY(int x, int z) { prepare(x, z); return super.groundY(x, z); }
    @Override Holder<Biome> noiseBiome(int x, int y, int z) {
        prepare(x << 2, z << 2);
        return super.noiseBiome(x, y, z);
    }

    @Override public ClientColumnSample sampleForLod(int x, int z, int stepBlocks) {
        return sampleSurface(x, z);
    }

    @Override public ClientColumnSample sampleSurface(int x, int z) {
        return exactSamples.get(x, z, () -> FreeTerraForgedDensity.coarse(false, () -> super.sampleSurface(x, z)));
    }

    @Override public ClientColumnSample samplePreview(int x, int z) {
        return previewSamples.get(x, z, () -> FreeTerraForgedDensity.coarse(true, () -> super.samplePreview(x,z)));
    }

    @Override boolean supportsLodGrid() { return true; }

    @Override boolean sampleLodGrid(int x, int z, int step, int width, int height,
            ClientColumnSample[] target, int offset, int stride, boolean preview) {
        // Finish a filtered FTF region before visiting the next. Existing/captured grid entries keep their authority.
        var regions = new java.util.LinkedHashMap<Long, java.util.List<Integer>>();
        for (int dz = 0; dz < height; dz++) for (int dx = 0; dx < width; dx++) {
            if (target[offset + dz * stride + dx] != null) continue;
            int bx = x + dx * step, bz = z + dz * step;
            long region = (long) Math.floorDiv(bx, 64) << 32 | Math.floorDiv(bz, 64) & 0xffffffffL;
            regions.computeIfAbsent(region, ignored -> new java.util.ArrayList<>()).add(dz * width + dx);
        }
        for (var points : regions.values()) for (int point : points) {
            int dx = point % width, dz = point / width;
            target[offset + dz * stride + dx] = preview ? samplePreview(x + dx * step, z + dz * step)
                    : sampleForLod(x + dx * step, z + dz * step, step);
        }
        return true;
    }

    @Override int surfaceColorForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.surfaceColorForLod(x,y,z,preview));
    }
    @Override int foliageColorForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.foliageColorForLod(x,y,z,preview));
    }
    @Override int waterTintForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.waterTintForLod(x,y,z,preview));
    }

    @Override ClientColumnSample resolveSurface(ClientColumnSample sample, int x, int z, TerrainFunction terrain) {
        if (water != null && !FreeTerraForgedDensity.coarse()) {
            try {
                Object cell = prepare(x, z);
                int waterY = cell == null ? Integer.MIN_VALUE : Math.min(
                        profile().minY() + profile().height(), water.surfaceY(cell));
                if (waterY > sample.surfaceY() && waterY > sample.fluidY()) {
                    int flags = sample.flags() & ~(ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE);
                    if (noiseBiome(x >> 2, (waterY - 1) >> 2, z >> 2).value().coldEnoughToSnow(
                            new net.minecraft.core.BlockPos(x, waterY - 1, z))) {
                        flags |= ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE;
                    }
                    sample = new ClientColumnSample(sample.surfaceY(), waterY, sample.biomeIndex(), sample.topBlockIndex(),
                            sample.structureIndex(), sample.treeKind(), sample.treeDensity(), sample.treeHeight(), 1, flags,
                            sample.groundFeatureKind(), sample.underBlockIndex(), sample.deepBlockIndex(),
                            sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor());
                }
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged surface water sampling failed", failure); }
        }
        return super.resolveSurface(sample, x, z, terrain);
    }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            exactSamples.close(); previewSamples.close();
            if (exterior != null) exterior.close();
        }
        try {
            if (runtimeOwner != null) runtimeOwner.close();
            else if (cells != null) cells.close();
        }
        catch (Exception failure) {
            dev.xantha.vss.common.VSSLogger.warn("Could not release FreeTerraForged prediction tile cache", failure);
        }
        finally { super.close(); }
    }
}
