package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionWorldgenCapabilitiesTest {
    @Test void blueprintCodecCannotSilentlyDiscardMissingRoutingSnapshot() {
        var generator = JsonParser.parseString("""
                {"settings":{},"biome_source":{"type":"blueprint:modded","original_biome_source":{}}}
                """).getAsJsonObject();
        assertEquals("missing Blueprint biome slice snapshot", PredictionWorldgenCapabilities.rejection(generator));
        generator.add("vss_blueprint", new com.google.gson.JsonObject());
        assertNull(PredictionWorldgenCapabilities.rejection(generator));
    }

    @Test void customDecorationDoesNotDisableNativeTerrainOrMutateJavaSnapshot() {
        var generator = JsonParser.parseString("""
                {"settings":{"noise_router":{"final_density":"example:height"}},"biome_source":{"type":"minecraft:multi_noise"}}
                """).getAsJsonObject();
        var registries = JsonParser.parseString("""
                {"configured_features":{"create:ore":{"type":"create:layered_ore"}},
                 "structures":{"example:building":{"type":"example:jigsaw"}},
                 "configured_carvers":{"example:cave":{"type":"example:carver"}},
                 "biomes":{"example:plain":{"features":[[{"type":"example:placement"}]],"carvers":{}}},
                 "density_functions":{"example:height":{"type":"minecraft:constant","argument":0}}}
                """).getAsJsonObject();
        String original = registries.toString();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
        assertEquals(original, registries.toString(), "Java must retain the full feature snapshot");
        registries.getAsJsonObject("density_functions").getAsJsonObject("example:height")
                .addProperty("type", "tectonic:config_noise");
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries)
                .contains("tectonic:config_noise"));
    }

    @Test void biomeCodecsAndExplicitJavaRequestsStillRejectNative() {
        var generator = JsonParser.parseString("""
                {"settings":{},"biome_source":{"type":"example:biome_source"}}
                """).getAsJsonObject();
        var registries = new com.google.gson.JsonObject();
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("biome_source"));
        generator.getAsJsonObject("biome_source").addProperty("type", "minecraft:fixed");
        generator.addProperty("vss_force_java", true);
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("vss_force_java"));
        generator.remove("vss_force_java");
        registries.addProperty("vss_force_java", true);
        assertNotNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
    }

    @Test void unusedCustomDensityDoesNotDisableOtherDimensionsAndCyclesTerminate() {
        var generator = JsonParser.parseString("{\"settings\":{\"noise_router\":{\"final_density\":\"vss:a\"}},\"biome_source\":{\"type\":\"minecraft:fixed\"}}").getAsJsonObject();
        var registries = JsonParser.parseString("{\"density_functions\":{\"vss:a\":\"vss:b\",\"vss:b\":\"vss:a\",\"rtf:unused\":{\"type\":\"reterraforged:cell\"}}}").getAsJsonObject();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
        var root = PredictionWorldgenCapabilities.terrainDocument(generator, registries);
        assertEquals(2, root.getAsJsonObject("density_functions").size());
        generator.getAsJsonObject("settings").getAsJsonObject("noise_router").addProperty("final_density", "rtf:unused");
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("reterraforged:cell"));
    }

    @Test void knownLithostitchedCodecsAreNativeButUnknownExtensionsRemainExplicit() {
        for (String name : new String[]{"axis", "ceil", "floor", "sin", "cos", "sqrt", "mix", "select", "shift", "fast_noise"})
            assertTrue(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("{\"type\":\"lithostitched:" + name + "\"}")));
        assertFalse(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("{\"type\":\"lithostitched:future_noise\"}")));
    }

    @Test void dataPackIdentifiersDoNotDisableVanillaNoiseButCustomCodecsSelectJava() {
        assertTrue(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("""
                {"tectonic:mountains":{"type":"minecraft:add","argument1":"terralith:height","argument2":1}}
                """)));
        assertFalse(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("""
                {"density_functions":{"x":{"type":"tectonic:config_noise"}}}
                """)));
    }

    @Test void serverRuntimeDependencyAndMissingSnapshotCannotBecomeFallbackTerrain() {
        assertNotNull(PredictionWorldgenCapabilities.rejection(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals("region context", PredictionWorldgenCapabilities.rejection(JsonParser.parseString("""
                {"settings":{},"biome_source":{},"vss_unsupported_reason":"region context"}
                """).getAsJsonObject()));
    }

    @Test void biomeSpawnersDoNotDisableNativeTerrainButSurfaceRulesStillDo() {
        // Regression: a biome's spawn list names entities (for example
        // alexscaves:tripodfish). Entities never participate in the terrain
        // density graph, but they sit inside the same `type`-bearing JSON the
        // capability scan walks, so an unfiltered scan rejected every
        // dimension whose biomes listed a modded creature.
        var generator = JsonParser.parseString("""
                {"settings":{"noise_router":{"final_density":"example:height"}},
                 "biome_source":{"type":"minecraft:multi_noise"}}
                """).getAsJsonObject();
        var registries = JsonParser.parseString("""
                {"density_functions":{"example:height":{"type":"minecraft:constant","argument":0}},
                 "biomes":{"example:plain":{
                     "spawners":{"example:creatures":[{"type":"alexscaves:tripodfish"}]},
                     "features":[[{"type":"example:placement"}]],
                     "carvers":{}}}}
                """).getAsJsonObject();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries),
                "spawners carry entity ids and must not disable native terrain");

        // A surface rule does shape the surface, so an unknown codec there must
        // still force the Java sampler. `alexscaves:ac_simplex` used to serve as
        // this probe until the native backend implemented it, so this uses a
        // name deliberately absent from the whitelist.
        generator.getAsJsonObject("settings").add("surface_rule", JsonParser.parseString("""
                {"type":"minecraft:sequence",
                 "sequence":[{"type":"example:unknown_rule"}]}
                """));
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries)
                .contains("example:unknown_rule"));
    }

    @Test void blueprintPayloadSectionIsNotReadAsCodecTypes() {
        // The vss_blueprint snapshot carries provider dispatch entries whose
        // `type` names Blueprint's own providers. Those are replayed by the
        // native backend rather than decoded as worldgen codecs, so scanning
        // them would reject every Blueprint-wrapped dimension. Regression for
        // exactly that: the section used to be walked like any other object.
        var generator = JsonParser.parseString("""
                {"settings":{"noise_router":{"final_density":"example:height"}},
                 "biome_source":{"type":"blueprint:modded",
                     "original_biome_source":{"type":"minecraft:multi_noise","biomes":[]}},
                 "vss_blueprint":{
                     "original_biome_source":{"type":"minecraft:multi_noise","biomes":[]},
                     "slices":[{"name":"test:slice","slice":{"weight":100,
                         "provider":{"type":"blueprint:overlay","overlays":[]}}}],
                     "size":8,"slices_seed":1,"slices_zoom_seed":2}}
                """).getAsJsonObject();
        var registries = JsonParser.parseString("""
                {"density_functions":{"example:height":{"type":"minecraft:constant","argument":0}}}
                """).getAsJsonObject();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries),
                "vss_ payload sections must not be scanned for codec types");
    }

    @Test void liveCapturePassesTheNativeGate() throws Exception {
        // A capture taken in-game is the only ground truth for "would this
        // dimension be accepted". The gate is a pure function over the captured
        // documents, so it replays here without any of the mods that produced
        // them - which is what makes the remaining blockers observable without
        // launching the game. Skips when no capture has been taken.
        java.nio.file.Path dir = java.nio.file.Path.of("build", "capture-2375620064288639166");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(dir.resolve("generator.json")),
                "no live capture present");
        var generator = JsonParser.parseString(java.nio.file.Files.readString(
                dir.resolve("generator.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var registries = JsonParser.parseString(java.nio.file.Files.readString(
                dir.resolve("registries.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        String reason = PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries);
        System.out.println("LIVE_CAPTURE_REJECTION=" + reason);
        assertNull(reason, "a real overworld capture must pass the native gate: " + reason);
    }

    @Test void liveCaptureListsEveryUnsupportedCodec() throws Exception {
        // The gate reports only the first blocker, which makes it useless for
        // planning: fixing one just reveals the next. This walks the same
        // documents and collects every non-whitelisted codec at once, so the
        // remaining work is known up front instead of one round at a time.
        java.nio.file.Path dir = java.nio.file.Path.of("build", "capture-2375620064288639166");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(dir.resolve("generator.json")),
                "no live capture present");
        var generator = JsonParser.parseString(java.nio.file.Files.readString(
                dir.resolve("generator.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var registries = JsonParser.parseString(java.nio.file.Files.readString(
                dir.resolve("registries.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();

        var field = PredictionWorldgenCapabilities.class.getDeclaredField("NATIVE_MOD_CODECS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var allowed = (java.util.Set<String>) field.get(null);

        var found = new java.util.TreeMap<String, Integer>();
        collectUnsupported(generator, allowed, found);
        collectUnsupported(PredictionWorldgenCapabilities.terrainDocument(generator, registries),
                allowed, found);
        System.out.println("UNSUPPORTED_CODECS=" + found.keySet());
        System.out.println("UNSUPPORTED_CODEC_PATHS=" + found);
    }

    private static void collectUnsupported(com.google.gson.JsonElement element,
                                           java.util.Set<String> allowed,
                                           java.util.Map<String, Integer> found) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (var child : element.getAsJsonArray()) collectUnsupported(child, allowed, found);
            return;
        }
        if (!element.isJsonObject()) return;
        for (var entry : element.getAsJsonObject().entrySet()) {
            // Mirrors the gate: VSS's own payload sections are replayed, not
            // decoded, so their `type` keys are not codec names.
            if (entry.getKey().startsWith("vss_")) continue;
            if (entry.getKey().equals("type") && entry.getValue().isJsonPrimitive()) {
                String type = entry.getValue().getAsString();
                if (type.contains(":") && !type.startsWith("minecraft:") && !allowed.contains(type)) {
                    found.merge(type, 1, Integer::sum);
                }
            }
            collectUnsupported(entry.getValue(), allowed, found);
        }
    }
}