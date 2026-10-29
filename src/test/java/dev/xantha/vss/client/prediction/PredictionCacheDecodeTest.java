package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCacheDecodeTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    private static ClientWorldgenProfileDecoder.Session session() throws Exception {
        var document=LithostitchedNativeTest.document();var registries=TerrainPredictionFixtures.emptyRegistries();var generator=new JsonObject();
        registries.add("biomes",document.get("biomes"));
        for(String key:List.of("noises","density_functions"))registries.add(key,document.get(key));
        for(String key:List.of("settings","biome_source"))generator.add(key,document.get(key));
        byte[] data=generator.toString().getBytes(StandardCharsets.UTF_8),registry=registries.toString().getBytes(StandardCharsets.UTF_8);
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",123,0,data.length,data);
        var payload=new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION,42,1,0,registry.length,registry,List.of(profile));
        var session=ClientWorldgenProfileDecoder.prepare(payload,TerrainPredictionFixtures.access(),()->true);
        // Supply the exported resource colors without creating a Minecraft window.
        var inputsField=ClientWorldgenProfileDecoder.Session.class.getDeclaredField("shared");inputsField.setAccessible(true);
        var inputs=(RustWorldgenDocument.SharedInputs)inputsField.get(session);inputs.prepare();
        var colors=new JsonObject();for(String key:List.of("grass_colormap","foliage_colormap"))colors.add(key,document.get(key));
        var colorsField=RustWorldgenDocument.SharedInputs.class.getDeclaredField("colors");colorsField.setAccessible(true);colorsField.set(inputs,colors);
        return session;
    }
    @Test void cacheContextPublishesBeforeFullRustContextWithTheSamePaletteAndVoxelIdentity() throws Exception {
        var stages=new ArrayList<String>();var owned=new ArrayList<ClientTerrainSampler>();
        try(var session=session()) {
            session.decode(Level.OVERWORLD,()->true,(key,full)->{
                owned.add(full);stages.add("generation");assertInstanceOf(RustTerrainSampler.class,full);
                var cached=owned.get(0);assertEquals(cached.cacheBiomes(),full.cacheBiomes());
                assertEquals(cached.predictionCacheNamespace(),full.predictionCacheNamespace());
                assertArrayEquals(PredictionVoxelCache.identity(cached),PredictionVoxelCache.identity(full));
            },(key,cached)->{
                owned.add(cached);stages.add("cache");assertInstanceOf(PredictionCacheSampler.class,cached);
                assertNull(cached.generatorContext());assertNull(cached.randomStateContext());
            });
            assertEquals(List.of("cache","generation"),stages);
        } finally {owned.forEach(PredictionResources::releaseSampler);}
    }
    @Test void cancellationAfterCachePublicationDoesNotPublishOrAllocateAFullBackend() throws Exception {
        var active=new AtomicBoolean(true);var owned=new ArrayList<ClientTerrainSampler>();
        try(var session=session()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->session.decode(Level.OVERWORLD,active::get,
                    (key,full)->{owned.add(full);fail("Cancelled cache visit cannot publish a generation backend");},
                    (key,cached)->{owned.add(cached);active.set(false);}));
            assertEquals(1,owned.size());assertInstanceOf(PredictionCacheSampler.class,owned.get(0));
        } finally {owned.forEach(PredictionResources::releaseSampler);}
    }
    @Test void nativeInitializationCancellationReleasesTheUnpublishedJavaContext() throws Exception {
        var cancelled = new java.util.concurrent.CancellationException("profile superseded during native initialization");
        var observed = new java.util.concurrent.atomic.AtomicReference<ClientTerrainSampler>();
        var cached = new ArrayList<ClientTerrainSampler>();
        try (var session = session(); var nativeFactory = org.mockito.Mockito.mockStatic(RustTerrainSampler.class)) {
            nativeFactory.when(() -> RustTerrainSampler.open(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                ClientTerrainSampler context = call.getArgument(3);
                observed.set(context);
                throw cancelled;
            });
            assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class, () ->
                    session.decode(Level.OVERWORLD, () -> true,
                            (key, full) -> { full.close(); fail("Cancelled native initialization cannot publish a backend"); },
                            (key, cache) -> cached.add(cache))));
            assertNotNull(observed.get(), "The native factory must receive a fully decoded Java context");
            assertTrue(observed.get().densityCompilerDiagnostics().startsWith("state=retired"),
                    "Cancellation must retire the actual unpublished Java sampler's compiler");
            assertEquals(1, cached.size());
            assertInstanceOf(PredictionCacheSampler.class, cached.get(0));
        } finally { cached.forEach(PredictionResources::releaseSampler); }
    }
}
