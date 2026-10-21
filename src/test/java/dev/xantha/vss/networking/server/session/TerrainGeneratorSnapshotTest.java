package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.api.worldgen.*;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;
import org.junit.jupiter.api.*;

class TerrainGeneratorSnapshotTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void cleanup() { for (var p : TerrainSnapshots.providers()) TerrainSnapshots.unregister(p.id()); }
    private static JsonObject snapshot(ChunkGenerator generator) throws Exception {
        var access = TerrainPredictionFixtures.access();
        var encoded = WorldgenCodecSnapshot.encodeGenerator(generator, access, new DensityFunctionReferences(),
                new WorldgenRegistryDependencies(access, List.of()));
        return JsonParser.parseString(new String(LodByteCompression.decompress(encoded.bytes(), encoded.compression(),
                encoded.rawSize(), 8 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test void standardNoiseKeepsExistingDataAndDoesNotDuplicateItsCodec() throws Exception {
        var alias = new NoiseAlias();
        var root = snapshot(new NoiseBasedChunkGenerator(alias.getBiomeSource(), alias.generatorSettings()));
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(root).kind());
        assertTrue(root.has("settings")); assertTrue(root.has("biome_source"));
        assertFalse(root.has("vss_generator")); assertFalse(root.has("vss_unsupported_reason"));
    }
    @Test void codecOnlyNoiseSubclassReusesTheGraphButChangedColumnImplementationIsRejected() throws Exception {
        var inherited = snapshot(new NoiseAlias());
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(inherited).kind());
        var alias = new NoiseAlias();
        assertEquals(snapshot(new NoiseBasedChunkGenerator(alias.getBiomeSource(), alias.generatorSettings())).get("settings"), inherited.get("settings"));
        var changed = snapshot(new ChangedNoise());
        assertEquals(TerrainPredictionPlan.Kind.UNAVAILABLE, TerrainPredictionPlan.read(changed).kind());
        assertTrue(changed.get("vss_unsupported_reason").getAsString().contains("ChangedNoise"));
    }
    @Test void flatWaterAndSolidLayersAreCapturedBeforeTheyBecomeFeaturePlaceholders() throws Exception {
        var access = TerrainPredictionFixtures.access();
        var settings = new FlatLevelGeneratorSettings(Optional.empty(), access.registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(Biomes.PLAINS), List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(3, Blocks.STONE));
        settings.getLayersInfo().add(new FlatLayerInfo(2, Blocks.WATER)); settings.updateLayers();
        var flat = new FlatLevelSource(settings);
        flat.getBiomeGenerationSettings(settings.getBiome());
        var root = snapshot(flat);
        assertEquals(TerrainPredictionPlan.Kind.FLAT_LAYERS, TerrainPredictionPlan.read(root).kind());
        var layers = root.getAsJsonObject("flat_layers").getAsJsonArray("layers");
        assertEquals(2, layers.size());
        assertEquals("minecraft:water", layers.get(1).getAsJsonObject().getAsJsonObject("state").get("Name").getAsString());
    }
    @Test void explicitProjectionAllowsOneProviderToCoverCustomGeneratorsWithoutRunningThem() throws Exception {
        var projection = snapshot(new NoiseAlias()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        int[] calls = {0};
        TerrainSnapshots.register(new TerrainSnapshotProvider() {
            public String id() { return "example:common_framework"; }
            public Optional<TerrainSnapshot> capture(TerrainSnapshotContext context) {
                calls[0]++; return Optional.of(TerrainSnapshot.noise(id(), 2, true, projection));
            }
        });
        var root = snapshot(new ChangedNoise());
        assertEquals(1, calls[0]);
        assertEquals("example:common_framework", TerrainPredictionPlan.read(root).backend());
        assertFalse(root.has("vss_unsupported_reason"));
        assertEquals(projection.get("settings"), root.get("settings"));
    }
    @Test void priorityFailureAndCancellationHaveDifferentSemantics() {
        var context = new TerrainSnapshotContext(TerrainPredictionFixtures.noise(), TerrainPredictionFixtures.access(),
                new WorldgenRegistryDependencies(TerrainPredictionFixtures.access(), List.of()));
        TerrainSnapshots.register(provider("example:broken", 10, () -> { throw new IllegalAccessError("optional API changed"); }));
        TerrainSnapshots.register(provider("example:working", 1, () -> TerrainSnapshot.lod("example:working", 1, new JsonObject())));
        assertEquals("example:working", TerrainSnapshots.capture(context).orElseThrow().plan().backend());
        TerrainSnapshots.register(provider("example:cancelled", 20, () -> { throw new java.util.concurrent.CancellationException(); }));
        assertThrows(java.util.concurrent.CancellationException.class, () -> TerrainSnapshots.capture(context));
    }
    @Test void completeProjectionSurvivesAnUnavailableOriginalNoiseContext() throws Exception {
        var projection = snapshot(new NoiseAlias()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        TerrainSnapshots.register(provider("example:projection", 1, () -> TerrainSnapshot.noise("example:projection", 1, false, projection)));
        var root = snapshot(new BrokenSettingsNoise());
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(root).kind());
        assertEquals(projection.get("settings"), root.get("settings"));
        assertTrue(root.has("vss_generator_error"));
    }
    @Test void independentSourceSurvivesAnUnavailableOptionalNoiseContext() throws Exception {
        var parameters = new JsonObject(); parameters.addProperty("height", 110);
        TerrainSnapshots.register(provider("example:source", 1, () -> TerrainSnapshot.lod("example:source", 1, parameters)));
        var root = snapshot(new BrokenSettingsNoise());
        assertEquals(TerrainPredictionPlan.Kind.LOD_SOURCE, TerrainPredictionPlan.read(root).kind());
        assertEquals(110, root.getAsJsonObject("vss_lod_parameters").get("height").getAsInt());
        assertFalse(root.has("vss_unsupported_reason"));
    }
    @Test void optionalGeneratorCodecDoesNotSwallowCancellation() throws Exception {
        var projection = snapshot(new NoiseAlias()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        TerrainSnapshots.register(provider("example:projection", 1, () -> TerrainSnapshot.noise("example:projection", 1, false, projection)));
        assertThrows(java.util.concurrent.CancellationException.class, () -> snapshot(new NoiseAlias() {
            @Override public Holder<NoiseGeneratorSettings> generatorSettings() { throw new java.util.concurrent.CancellationException(); }
        }));
    }
    @Test void fatalVmFailureIsNotConvertedToUnavailableTerrain() {
        var fatal = new OutOfMemoryError("sentinel");
        TerrainSnapshots.register(provider("example:fatal", 1, () -> { throw fatal; }));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> TerrainSnapshots.capture(new TerrainSnapshotContext(
                TerrainPredictionFixtures.noise(), TerrainPredictionFixtures.access(),
                new WorldgenRegistryDependencies(TerrainPredictionFixtures.access(), List.of())))));
    }
    private static TerrainSnapshotProvider provider(String id, int priority, java.util.function.Supplier<TerrainSnapshot> capture) {
        return new TerrainSnapshotProvider() {
            public String id() { return id; }
            public int priority() { return priority; }
            public Optional<TerrainSnapshot> capture(TerrainSnapshotContext ignored) { return Optional.of(capture.get()); }
        };
    }
    static class NoiseAlias extends NoiseBasedChunkGenerator {
        NoiseAlias() { super(new FixedBiomeSource(TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),
                Holder.direct(settings())); }
        private static NoiseGeneratorSettings settings() {
            var zero = DensityFunctions.zero();
            var density = DensityFunctions.yClampedGradient(-64, 320, 1, -1);
            return new NoiseGeneratorSettings(NoiseSettings.create(-64, 384, 1, 2), Blocks.STONE.defaultBlockState(),
                    Blocks.WATER.defaultBlockState(), new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, zero,
                            zero, zero, density, density, zero, zero, zero),
                    SurfaceRules.state(Blocks.STONE.defaultBlockState()), List.of(), 63, false, false, false, false);
        }
    }
    static class ChangedNoise extends NoiseAlias {
        @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) {
            throw new AssertionError("snapshot classification must never execute custom generation");
        }
    }
    static class BrokenSettingsNoise extends NoiseAlias {
        @Override public Holder<NoiseGeneratorSettings> generatorSettings() { throw new IllegalAccessError("optional noise context unavailable"); }
    }
}
