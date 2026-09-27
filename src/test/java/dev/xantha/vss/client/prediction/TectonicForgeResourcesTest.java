package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Uses the unmapped 1.20.1 release resources, without reflecting its Forge runtime classes. */
@EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
class TectonicForgeResourcesTest {
    @Test void releasedOverworldDensityGraphUsesSupportedNodes() throws Exception {
        var root = new JsonObject();
        root.add("density_functions", new JsonObject());
        root.add("noises", new JsonObject());
        JsonObject settings = null;
        try (var zip = new ZipFile(System.getProperty("vss.tectonicJar"))) {
            String prefix = "resourcepacks/tectonic/data/";
            for (var entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
                String[] parts = name.substring(prefix.length()).split("/", 4);
                if (parts.length != 4 || !parts[1].equals("worldgen")) continue;
                try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    var json = JsonParser.parseReader(reader);
                    if (parts[0].equals("minecraft") && parts[2].equals("noise_settings")
                            && parts[3].equals("overworld.json")) settings = json.getAsJsonObject();
                    else if (parts[2].equals("density_function") || parts[2].equals("noise"))
                        root.getAsJsonObject(parts[2].equals("noise") ? "noises" : "density_functions")
                                .add(parts[0] + ":" + parts[3].substring(0, parts[3].length() - 5), json);
                }
            }
        }
        assertNotNull(settings);
        assertTrue(root.getAsJsonObject("density_functions").size() >= 90);
        assertTrue(root.getAsJsonObject("noises").size() >= 30);
        var generator = new JsonObject();
        generator.add("settings", settings);
        generator.add("biome_source", LithostitchedNativeTest.document().get("biome_source"));
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, root));
        assertTrue(PredictionWorldgenCapabilities.terrainDocument(generator, root)
                .getAsJsonObject("density_functions").size() > 50);
    }
}
