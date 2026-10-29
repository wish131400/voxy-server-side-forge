package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;

class TerraBlenderEndSnapshotTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @Test void biomeSourcesWithoutTheEndExtensionDoNotAddAnEndContract() {
        var source = TerrainPredictionFixtures.noise().getBiomeSource();
        assertNull(TerraBlenderEndSnapshot.capture(source, 42));
        var root = new JsonObject(); root.add("settings", new JsonObject());
        root.add("biome_source", new JsonObject());
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        var original = new WorldgenCodecSnapshot.Encoded(0, bytes.length, bytes);
        assertSame(original, WorldgenCodecSnapshot.withRuntimeBiomeSource(original, source, 42));
    }
    @Test void releasedForge120TerraBlenderDoesNotExposeTheLaterEndExtension() throws Exception {
        String jar = System.getProperty("vss.terrablenderJar");
        Assumptions.assumeTrue(jar != null && !jar.isBlank(), "Optional real TerraBlender 3.0.1.11 API audit");
        try (var archive = new ZipFile(Path.of(jar).toFile())) {
            assertNotNull(archive.getEntry("terrablender/api/Regions.class"));
            assertNull(archive.getEntry("terrablender/api/EndBiomeRegistry.class"));
            assertNull(archive.getEntry("terrablender/worldgen/IExtendedTheEndBiomeSource.class"));
        }
    }
}
