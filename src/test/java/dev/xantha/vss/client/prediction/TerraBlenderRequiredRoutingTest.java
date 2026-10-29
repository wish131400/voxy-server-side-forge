package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.*;

class TerraBlenderRequiredRoutingTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void unregister() {
        for (String id : List.of("terrablender", "test:cancel", "test:lower")) PredictionTerrainBackends.unregister(id);
    }

    private static WorldgenProfileS2CPayload payload(String requiredField, JsonObject routing) {
        var access = TerrainPredictionFixtures.access();
        var noise = TerrainPredictionFixtures.noise();
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        var generator = new JsonObject();
        generator.add("settings", NoiseGeneratorSettings.DIRECT_CODEC
                .encodeStart(ops, noise.generatorSettings().value()).result().orElseThrow());
        generator.add("biome_source", BiomeSource.CODEC
                .encodeStart(ops, noise.getBiomeSource()).result().orElseThrow());
        generator.add(requiredField, routing);
        generator.addProperty("vss_force_java", true);
        byte[] encoded = generator.toString().getBytes(StandardCharsets.UTF_8);
        var profile = new DimensionProfile(Level.OVERWORLD.location(), 42, -64, 384,
                "noise", "minecraft:overworld", 0, 0, encoded.length, encoded);
        var snapshot = TerrainPredictionFixtures.emptyRegistries();
        snapshot.addProperty("vss_force_java", true);
        var noises = snapshot.getAsJsonObject("noises");
        access.registryOrThrow(Registries.NOISE).asLookup().listElements().forEach(holder ->
                noises.add(holder.key().location().toString(), NormalNoise.NoiseParameters.DIRECT_CODEC
                        .encodeStart(ops, holder.value()).result().orElseThrow()));
        byte[] registries = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        return new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile));
    }
    private static JsonObject baseRouting() {
        var root = new JsonObject(); root.addProperty("region_size", 2);
        var base = new JsonObject(); base.addProperty("base", true);
        base.addProperty("weighted", true); base.addProperty("weight", 1);
        var regions = new JsonArray(); regions.add(base); root.add("regions", regions);
        return root;
    }
    private static void assertWrappedOnce(ClientTerrainSampler sampler) throws Exception {
        BiomeSource routing = sampler.generatorContext().getBiomeSource();
        assertEquals("RoutingSource", routing.getClass().getSimpleName());
        var base = routing.getClass().getDeclaredField("base"); base.setAccessible(true);
        assertInstanceOf(FixedBiomeSource.class, base.get(routing), "The backend must not apply region routing twice");
    }

    @Test void badRequiredRoutingCannotPublishVanillaThroughTheBackendOrEarlyCache() throws Exception {
        for (String field : List.of("vss_terrablender", "vss_terrablender_end")) {
            for (boolean withBackend : List.of(false, true)) {
                PredictionTerrainBackends.unregister("terrablender");
                if (withBackend) PredictionTerrainBackends.register(new TerraBlenderBackend());
                var published = new AtomicInteger();
                try (var session = ClientWorldgenProfileDecoder.prepare(payload(field, new JsonObject()),
                        TerrainPredictionFixtures.access(), () -> true)) {
                    session.decode(Level.OVERWORLD, () -> true,
                            (key, full) -> { try { published.incrementAndGet(); } finally { full.close(); } },
                            (key, cache) -> { try { published.incrementAndGet(); } finally { cache.close(); } });
                }
                assertEquals(0, published.get(), field + " must be reconstructed before any old cache is published");
            }
        }
    }
    @Test void validRegionRoutingHasOneWrapperWithOrWithoutTheRegisteredBackend() throws Exception {
        for (boolean withBackend : List.of(false, true)) {
            PredictionTerrainBackends.unregister("terrablender");
            if (withBackend) PredictionTerrainBackends.register(new TerraBlenderBackend());
            var published = new ArrayList<ClientTerrainSampler>();
            try (var session = ClientWorldgenProfileDecoder.prepare(payload("vss_terrablender", baseRouting()),
                    TerrainPredictionFixtures.access(), () -> true)) {
                session.decode(Level.OVERWORLD, () -> true,
                        (key, full) -> published.add(full), (key, cache) -> published.add(cache));
                assertEquals(withBackend ? 1 : 2, published.size());
                if (!withBackend) assertInstanceOf(PredictionCacheSampler.class, published.get(0));
                assertWrappedOnce(published.get(published.size() - 1));
            } finally { published.forEach(ClientTerrainSampler::close); }
        }
    }
    @Test void backendCancellationIsNotReplacedByAnotherBackendOrVanilla() {
        var cancelled = new CancellationException("profile superseded");
        var lowerCalls = new AtomicInteger();
        PredictionTerrainBackends.register(new PredictionTerrainBackend() {
            public String id() { return "test:cancel"; }
            public int priority() { return 10000; }
            public Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed, RegistryAccess access) {
                throw cancelled;
            }
        });
        PredictionTerrainBackends.register(new PredictionTerrainBackend() {
            public String id() { return "test:lower"; }
            public int priority() { return -10000; }
            public Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed, RegistryAccess access) {
                lowerCalls.incrementAndGet(); return Optional.empty();
            }
        });
        var profile = payload("vss_terrablender", baseRouting()).dimensions().get(0);
        assertSame(cancelled, assertThrows(CancellationException.class, () -> PredictionTerrainBackends.open(
                profile, profile.seed(), TerrainPredictionFixtures.access())));
        assertEquals(0, lowerCalls.get());
    }
}
