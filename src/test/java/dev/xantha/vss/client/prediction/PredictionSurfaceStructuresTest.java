package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSurfaceStructuresTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void vanillaDimensionStructuresUseTheirActualDecorationStages() {
        var registry = VanillaRegistries.createLookup().lookupOrThrow(Registries.STRUCTURE);
        var fortress = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS).value();
        var bastion = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.BASTION_REMNANT).value();
        var city = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY).value();
        var ancient = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.ANCIENT_CITY).value();
        assertTrue(PredictionSurfaceStructures.supported(fortress,true));
        assertTrue(PredictionSurfaceStructures.supported(bastion,true),"Mixed fortress/bastion sets must not be dropped");
        assertTrue(PredictionSurfaceStructures.supported(city,false));
        assertFalse(PredictionSurfaceStructures.supported(ancient,false),"Overworld underground structures remain excluded");
    }

    @Test void endCityEntityMarkersCannotAbortTheBuildingButChestMarkersRemain() {
        var tag = new net.minecraft.nbt.CompoundTag();
        var blocks = new net.minecraft.nbt.ListTag();
        for (String marker : List.of("Sentry","Elytra","Chest")) {
            var block = new net.minecraft.nbt.CompoundTag();
            var data = new net.minecraft.nbt.CompoundTag(); data.putString("metadata",marker);
            block.put("nbt",data); blocks.add(block);
        }
        tag.put("blocks",blocks);
        var untouched = tag.copy();
        PredictionStructureTemplates.stripEndCityEntityMarkers(ResourceLocation.parse("mod:end_city/ship"),untouched);
        assertEquals(tag,untouched);
        PredictionStructureTemplates.stripEndCityEntityMarkers(ResourceLocation.withDefaultNamespace("end_city/ship"),tag);
        assertEquals("",blocks.getCompound(0).getCompound("nbt").getString("metadata"));
        assertEquals("",blocks.getCompound(1).getCompound("nbt").getString("metadata"));
        assertEquals("Chest",blocks.getCompound(2).getCompound("nbt").getString("metadata"));
    }

    @Test void oceanRuinEntityMarkerIsSkippedWithoutChangingChestOrCustomTemplates() {
        var tag = new net.minecraft.nbt.CompoundTag();
        var blocks = new net.minecraft.nbt.ListTag();
        for (String marker : List.of("drowned", "chest", "unrelated")) {
            var block = new net.minecraft.nbt.CompoundTag();
            var data = new net.minecraft.nbt.CompoundTag(); data.putString("metadata", marker);
            block.put("nbt", data); blocks.add(block);
        }
        tag.put("blocks", blocks);
        var untouched = tag.copy();
        PredictionStructureTemplates.stripOceanRuinEntityMarkers(ResourceLocation.parse("vss:underwater_ruin/cold_1"), untouched);
        assertEquals(tag, untouched);
        PredictionStructureTemplates.stripOceanRuinEntityMarkers(ResourceLocation.withDefaultNamespace("village/plains/houses/a"), untouched);
        assertEquals(tag, untouched);
        PredictionStructureTemplates.stripOceanRuinEntityMarkers(ResourceLocation.withDefaultNamespace("underwater_ruin/cold_1"), tag);
        assertEquals("", blocks.getCompound(0).getCompound("nbt").getString("metadata"));
        assertEquals("chest", blocks.getCompound(1).getCompound("nbt").getString("metadata"));
        assertEquals("unrelated", blocks.getCompound(2).getCompound("nbt").getString("metadata"));
    }

    @Test void syncedVillageExpandsItsStartIntoRoadsAndHouses() throws Exception {
        JsonObject pools;
        try (var input = getClass().getResourceAsStream("/vss/prediction/plains-template-pools.json")) {
            assertNotNull(input);
            pools = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject root = JsonParser.parseString("""
                {"noises":{},"density_functions":{},
                 "biomes":{"vss:plain":{"has_precipitation":false,"temperature":0.7,"downfall":0.0,
                    "effects":{"fog_color":0,"water_color":0,"water_fog_color":0,"sky_color":0},
                    "spawners":{},"spawn_costs":{},"carvers":{},"features":[]}},
                 "structures":{"vss:village":{"type":"minecraft:jigsaw","biomes":["vss:plain"],
                    "step":"surface_structures","spawn_overrides":{},"terrain_adaptation":"none",
                    "start_pool":"minecraft:village/plains/town_centers","size":6,
                    "start_height":{"absolute":64},"use_expansion_hack":true,"max_distance_from_center":80}},
                 "structure_sets":{"vss:village":{"structures":[{"structure":"vss:village","weight":1}],
                    "placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":10387312}}}}
                """).getAsJsonObject();
        root.add("template_pools", pools);
        JsonObject templates = new JsonObject();
        // Use the actual dependency templates, including jigsaw connector NBT.
        // Both layouts are used by the supported Minecraft versions.
        loadPoolTemplates(pools, templates);
        root.add("structure_templates", templates);
        var access = ClientWorldgenRegistries.decode(root, dev.xantha.vss.testutil.TerrainPredictionFixtures.access()).access();
        var context = sampler(access, templates);
        {
            var manager = PredictionStructureTemplates.open(templates);
            var structure = access.registryOrThrow(Registries.STRUCTURE).iterator().next();
            var start = structure.generate(access, context.generatorContext(), context.biomeSourceContext(),
                    context.randomStateContext(), manager, 42, new net.minecraft.world.level.ChunkPos(0, 0), 0,
                    net.minecraft.world.level.LevelHeightAccessor.create(-64, 384), biome -> true);
            assertTrue(start.isValid());
            assertTrue(start.getPieces().size() > 10, "a village must contain roads/houses beyond its start: " + start.getPieces().size());
            var elements = start.getPieces().stream()
                    .filter(net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece.class::isInstance)
                    .map(piece -> ((net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece)piece).getElement().toString())
                    .toList();
            assertTrue(elements.stream().anyMatch(element -> element.contains("/streets/")), "assembled village roads");
            assertTrue(elements.stream().anyMatch(element -> element.contains("/houses/")), "assembled village houses");
            System.out.println("VILLAGE_RUNTIME pieces=" + start.getPieces().size() + " roads=true houses=true");
        }
    }

    @Test void syncedVanillaHouseIsPlacedAsBlocksAndMissingTemplateProducesNothing() throws Exception {
        JsonObject root = JsonParser.parseString("""
                {"noises":{},"density_functions":{},
                 "biomes":{"vss:plain":{"has_precipitation":false,"temperature":0.7,"downfall":0.0,
                    "effects":{"fog_color":0,"water_color":0,"water_fog_color":0,"sky_color":0},
                    "spawners":{},"spawn_costs":{},"carvers":{},"features":[]}},
                 "processor_lists":{"vss:empty":{"processors":[]}},
                 "template_pools":{
                    "minecraft:empty":{"fallback":"minecraft:empty","elements":[]},
                    "vss:house":{"fallback":"minecraft:empty","elements":[{"weight":1,"element":{
                        "element_type":"minecraft:single_pool_element",
                        "location":"minecraft:village/plains/houses/plains_small_house_1",
                        "processors":"vss:empty","projection":"rigid"}}]}},
                 "structures":{"vss:house":{"type":"minecraft:jigsaw","biomes":["vss:plain"],
                    "step":"surface_structures","spawn_overrides":{},"terrain_adaptation":"none",
                    "start_pool":"vss:house","size":1,"start_height":{"absolute":64},
                    "use_expansion_hack":false,"max_distance_from_center":80}},
                 "structure_sets":{"vss:houses":{"structures":[{"structure":"vss:house","weight":1}],
                    "placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":10387312}}}}
                """).getAsJsonObject();
        JsonObject templates = new JsonObject();
        String id = "minecraft:village/plains/houses/plains_small_house_1";
        try (var input = getClass().getResourceAsStream("/data/minecraft/structures/village/plains/houses/plains_small_house_1.nbt")) {
            assertNotNull(input, "vanilla Minecraft dependency must supply the original village template");
            templates.addProperty(id, java.util.Base64.getEncoder().encodeToString(input.readAllBytes()));
        }
        root.add("structure_templates", templates);
        var registries = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY);
        var access = registries.access();
        assertEquals(1, access.registryOrThrow(Registries.STRUCTURE).size());
        assertEquals(2, access.registryOrThrow(Registries.TEMPLATE_POOL).size());
        var sampler = sampler(access, templates);
        var structures = new PredictionSurfaceStructures(sampler);
        assertTrue(structures.available(), structures.diagnostics());
        var spread = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                access.registryOrThrow(Registries.STRUCTURE_SET).iterator().next().placement();
        var candidate = spread.getPotentialStructureChunk(42, 0, 0);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> placed = new java.util.HashMap<>();
        for (int z = candidate.z - 1; z <= candidate.z + 1; z++) for (int x = candidate.x - 1; x <= candidate.x + 1; x++) {
            var level = new PredictionDecorationLevel(sampler, sampler, access, x, z);
            structures.place(level, x, z, 0);
            placed.putAll(level.placed());
        }
        assertTrue(placed.size() > 50, structures.diagnostics());
        assertTrue(placed.values().stream().anyMatch(state -> state.is(Blocks.OAK_STAIRS)), "original house roof/stair states");
        assertTrue(placed.values().stream().anyMatch(state -> state.is(Blocks.COBBLESTONE)), "original house foundation");
        assertTrue(placed.keySet().stream().allMatch(pos -> Math.abs(pos.getX() - candidate.getMinBlockX()) < 32));

        JsonObject missing = new JsonObject(); missing.addProperty("vss:unrelated", templates.get(id).getAsString());
        var missingSampler = sampler(access, missing);
        var unsupported = new PredictionSurfaceStructures(missingSampler);
        var emptyLevel = new PredictionDecorationLevel(missingSampler, missingSampler, access, candidate.x, candidate.z);
        unsupported.place(emptyLevel, candidate.x, candidate.z, 0);
        assertTrue(emptyLevel.placed().isEmpty(), "missing templates must not create bounding boxes or partial buildings");

        // Use the new async manifest path on the same original vanilla building.
        byte[] house = java.util.Base64.getDecoder().decode(templates.get(id).getAsString());
        JsonObject manifest = PredictionTemplateRepositoryTest.manifest(Map.of(ResourceLocation.parse(id), house));
        var io = new java.util.ArrayDeque<Runnable>();
        var repository = new PredictionTemplateRepository(manifest, java.nio.file.Files.createTempDirectory("vss-template-retry-test"),
                (key, digest) -> house, request -> fail("A matching local template should not download"), io::add, System::nanoTime);
        var current = PredictionTemplateRepository.class.getDeclaredField("current"); current.setAccessible(true);
        current.set(null, repository);
        PredictionStructureTemplates.clearShared();
        try {
            var pendingSampler = sampler(access, manifest);
            var retrying = new PredictionSurfaceStructures(pendingSampler);
            var pendingLevel = new PredictionDecorationLevel(pendingSampler, pendingSampler, access, candidate.x, candidate.z);
            assertThrows(PredictionWorkDeferred.class, () -> retrying.place(pendingLevel, candidate.x, candidate.z, 0));
            assertTrue(pendingLevel.placed().isEmpty());
            while (!io.isEmpty()) io.remove().run();
            retrying.place(pendingLevel, candidate.x, candidate.z, 0);
            assertFalse(pendingLevel.placed().isEmpty(), "Pending templates must not cache INVALID_START");
        } finally { PredictionTemplateRepository.reset(); }
    }

    private static void loadPoolTemplates(com.google.gson.JsonElement value, JsonObject templates) throws Exception {
        if (value.isJsonArray()) { for (var entry : value.getAsJsonArray()) loadPoolTemplates(entry, templates); }
        else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("location") && object.get("location").isJsonPrimitive()) {
                String id = object.get("location").getAsString();
                var loc = ResourceLocation.parse(id);
                for (String directory : List.of("structure", "structures")) {
                    try (var input = PredictionSurfaceStructuresTest.class.getResourceAsStream(
                            "/data/" + loc.getNamespace() + "/" + directory + "/" + loc.getPath() + ".nbt")) {
                        if (input != null) { templates.addProperty(id, java.util.Base64.getEncoder().encodeToString(input.readAllBytes())); break; }
                    }
                }
            }
            for (var entry : object.entrySet()) loadPoolTemplates(entry.getValue(), templates);
        }
    }

    private static ClientTerrainSampler sampler(RegistryAccess access, JsonObject templates) {
        var lookup = VanillaRegistries.createLookup();
        var source = new FixedBiomeSource(access.registryOrThrow(Registries.BIOME).holders().findFirst().orElseThrow());
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        return new ClientTerrainSampler(42, profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0,
                        0, 0, 0, 0, 0, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override net.minecraft.world.level.biome.BiomeSource biomeSourceContext() { return source; }
            @Override RegistryAccess decorationAccess() { return access; }
            @Override JsonObject structureTemplates() { return templates; }
        };
    }
}
