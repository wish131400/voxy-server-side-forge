package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Checks WWOO's released 1.20.1 resources against the Forge placement stages. */
@EnabledIfSystemProperty(named = "vss.wwooJar", matches = ".+")
class WwooForgeCompatTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void releasedWwooSurfaceFeaturesKeepTheirStagesAndOptions() throws Exception {
        Map<String, JsonObject> biomes = new HashMap<>();
        Map<String, JsonObject> features = new HashMap<>();
        try (var zip = new ZipFile(System.getProperty("vss.wwooJar"))) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith("data/") || !name.endsWith(".json")) continue;
                String[] parts = name.substring("data/".length()).split("/", 4);
                if (parts.length != 4 || !parts[1].equals("worldgen")) continue;
                var target = switch (parts[2]) {
                    case "biome" -> biomes;
                    case "placed_feature" -> features;
                    default -> null;
                };
                if (target == null) continue;
                try (var input = zip.getInputStream(entry)) {
                    target.put(parts[0] + ":" + parts[3].substring(0, parts[3].length() - 5),
                            JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                }
            }
        }
        assertTrue(biomes.size() >= 50);
        assertTrue(features.size() >= 400);
        assertStage(biomes, features, 3, "wythers:terrain/extended/sandify_rooted_dirt", false);
        assertStage(biomes, features, 5, "wythers:terrain/extended/base_windswept_gravelly_hills", false);
        assertStage(biomes, features, 5, "wythers:vegetation/extended/trees/giant_taiga_edge", true);
        assertStage(biomes, features, 6, "wythers:terrain/local/replace_grass_to_mycelium", false);
        assertStage(biomes, features, 6, "wythers:terrain/local/snow_spread", false);
        assertStage(biomes, features, 6, "wythers:vegetation/local/other/coral_disks", true);
        assertStage(biomes, features, 6, "wythers:vegetation/local/patch/dead_corals_on_gravel", true);
        assertStage(biomes, features, 7, "wythers:vegetation/local/trees/dark_forest_1", true);
        assertStage(biomes, features, 8, "wythers:terrain/local/thermal_savanna_brown", false);
        assertStage(biomes, features, 8, "wythers:terrain/local/thermal_taiga_white", false);

        var moss = features.get("wythers:terrain/local/replace_packed_mud_to_moss");
        assertNotNull(moss);
        var configured = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)), moss.get("feature"))
                .getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
        var seaLevelMoss = new PlacedFeature(Holder.direct(configured), List.of(
                HeightRangePlacement.uniform(VerticalAnchor.absolute(63), VerticalAnchor.absolute(63))));
        assertTrue(PredictionVegetation.surfaceFeature(6, seaLevelMoss, false, true, false));
        assertFalse(PredictionVegetation.surfaceFeature(5, fixture(), false, true, true,
                ResourceLocation.parse("wythers:terrain/extended/volcanic_fallout_ore")));
        assertFalse(PredictionVegetation.surfaceFeature(6, fixture(), false, true, true,
                ResourceLocation.parse("wythers:ores/ore_coal_windswept")));
        assertFalse(PredictionVegetation.surfaceFeature(5, fixture(), false, true, true,
                ResourceLocation.parse("wythers:terrain/local/cave_disk_basalt")));
        assertFalse(PredictionVegetation.surfaceFeature(7, fixture(), false, true, true,
                ResourceLocation.parse("other:vegetation/local/trees/dark_forest_1")));
    }

    private static PlacedFeature fixture() {
        return new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.GRASS)))), List.of());
    }

    private static void assertStage(Map<String, JsonObject> biomes, Map<String, JsonObject> features,
                                    int stage, String id, boolean vegetation) {
        assertNotNull(features.get(id), id + " exists in release resources");
        assertTrue(biomes.values().stream().anyMatch(b -> b.getAsJsonArray("features") != null
                && b.getAsJsonArray("features").size() > stage
                && b.getAsJsonArray("features").get(stage).getAsJsonArray().asList().stream()
                        .anyMatch(x -> x.getAsString().equals(id))), id + " occurs in its expected stage");
        var key = ResourceLocation.parse(id);
        assertTrue(PredictionVegetation.surfaceFeature(stage, fixture(), false, true, false, key), id + " selected");
        if (vegetation)
            assertFalse(PredictionVegetation.surfaceFeature(stage, fixture(), false, false, true, key),
                    id + " requires the vegetation option");
    }
}



