package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionLodBackendsTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void unregister() {
        PredictionLodBackends.unregister("example:lod");
        PredictionTerrainBackends.unregister("example:legacy");
    }
    @TempDir Path cache;
    private static JsonObject generator(int revision, int height) {
        var result = new JsonObject();
        result.add(TerrainPredictionPlan.FIELD, new TerrainPredictionPlan(
                TerrainPredictionPlan.Kind.LOD_SOURCE, "example:lod", revision, false, "").encode());
        var parameters = new JsonObject(); parameters.addProperty("height", height);
        result.add("vss_lod_parameters", parameters); return result;
    }
    private static DimensionProfile profile(JsonObject root) {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        return new DimensionProfile(new ResourceLocation("minecraft", "overworld"), 42, -64, 384,
                "example.Generator", "custom", 0, 0, bytes.length, bytes);
    }
    private static PredictionLodContext context(JsonObject generator) {
        return new PredictionLodContext(profile(generator), TerrainPredictionPlan.read(generator), generator,
                ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access()),
                TerrainPredictionFixtures.access());
    }
    private static ClientColumnSample sample(int height) {
        int block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        return new ClientColumnSample(height, height, -1, block, 0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY,
                0, block, block, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    @Test void fullProfileSelectsFactoryOnceAndKeepsDirectSamplingAfterUnregistration() throws Exception {
        var calls = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 3, context -> {
            calls.incrementAndGet(); int height = context.parameters().get("height").getAsInt();
            ClientColumnSample sample = sample(height);
            return (x, z, step) -> sample;
        });
        JsonObject generator = generator(2, 110);
        byte[] registries = TerrainPredictionFixtures.emptyRegistries().toString().getBytes(StandardCharsets.UTF_8);
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile(generator)));
        var samplers = ClientWorldgenProfileDecoder.decode(payload, TerrainPredictionFixtures.access());
        var sampler = samplers.values().iterator().next();
        assertEquals(1, calls.get());
        PredictionLodBackends.unregister("example:lod");
        for (int i = 0; i < 1000; i++) assertEquals(110, sampler.sampleForLod(i, -i, 32).surfaceY());
        assertEquals(1, calls.get());
        assertTrue(sampler.predictionBackendDiagnostics().contains("executor=source")); sampler.close();
    }
    @Test void missingOrWrongRevisionNeverFallsBackToUnrelatedNoise() {
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        PredictionLodBackends.register("example:lod", 2, 3, ignored -> (x, z, step) -> sample(120));
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(4, 90))));
    }
    @Test void registeredLegacyBridgeIsReportedAsAnExplicitOverrideAndHasItsOwnCache() throws Exception {
        PredictionTerrainBackends.register(new PredictionTerrainBackend() {
            public String id() { return "example:legacy"; }
            public int priority() { return 1000; }
            public java.util.Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed, net.minecraft.core.RegistryAccess access) {
                return profile.generatorType().equals("example.Generator") ? java.util.Optional.of(ClientTerrainSampler.custom(seed, profile, (x, z) -> 100))
                        : java.util.Optional.empty();
            }
        });
        var generator = new JsonObject();
        generator.add(TerrainPredictionPlan.FIELD, TerrainPredictionPlan.unavailable("needs a bridge").encode());
        byte[] registries = TerrainPredictionFixtures.emptyRegistries().toString().getBytes(StandardCharsets.UTF_8);
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile(generator)));
        var sampler = ClientWorldgenProfileDecoder.decode(payload, TerrainPredictionFixtures.access()).values().iterator().next();
        assertEquals(100, sampler.surfaceY(20, -40));
        assertTrue(sampler.predictionBackendDiagnostics().contains("kind=BACKEND_OVERRIDE,snapshotKind=UNAVAILABLE"));
        assertTrue(sampler.predictionBackendDiagnostics().contains("selectedBackend=example:legacy"));
        assertNotNull(sampler.predictionCacheNamespace()); sampler.close();
    }
    @Test void batchesPreserveCapturesAndHonorLodSpacingAndPreview() {
        var steps = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { steps.set(step); return PredictionLodBackendsTest.sample(100 + x - z); }
            public ClientColumnSample preview(int x, int z, int step) { steps.set(step); return PredictionLodBackendsTest.sample(90); }
        });
        var sampler = PredictionLodBackends.open(context(generator(1, 90)));
        var target = new ClientColumnSample[16]; var captured = sample(200); target[6] = captured;
        assertTrue(sampler.sampleLodGrid(-32, 64, 16, 3, 2, target, 5, 4, false));
        assertSame(captured, target[6]); assertEquals(16, steps.get());
        assertNull(target[4]); assertEquals(4, target[5].surfaceY());
        assertEquals(36, target[7].surfaceY());
        assertTrue(sampler.sampleLodGrid(-32, 64, 32, 1, 1, target, 0, 4, true));
        assertEquals(90, target[0].surfaceY()); assertEquals(32, steps.get()); sampler.close();
    }
    @Test void providerClientAndParameterVersionsIsolatePersistentMeshes() {
        PredictionLodBackends.register("example:lod", 1, 5, ignored -> (x, z, step) -> sample(120));
        var a = PredictionLodBackends.open(context(generator(1, 100)));
        var b = PredictionLodBackends.open(context(generator(2, 100)));
        var c = PredictionLodBackends.open(context(generator(1, 110)));
        var storage = new PredictionCacheStorage(cache, null);
        assertNotEquals(storage.directory(a), storage.directory(b)); assertNotEquals(storage.directory(a), storage.directory(c));
        assertEquals(storage.directory(a), storage.directory(PredictionLodBackends.open(context(generator(1, 100)))));
        PredictionLodBackends.register("example:lod", 1, 5, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public int cacheRevision() { return 2; }
        });
        assertNotEquals(storage.directory(a), storage.directory(PredictionLodBackends.open(context(generator(1, 100)))));
        a.close(); b.close(); c.close();
    }
    @Test void disposalIsIdempotentAndFailedFactoryReleasesInitializedResources() {
        var closes = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public void close() { closes.incrementAndGet(); }
        });
        var sampler = PredictionLodBackends.open(context(generator(1, 90))); sampler.close(); sampler.close();
        assertEquals(1, closes.get());
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public int cacheRevision() { return 0; }
            public void close() { closes.incrementAndGet(); }
        });
        assertThrows(IllegalArgumentException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        assertEquals(2, closes.get());
    }
}
