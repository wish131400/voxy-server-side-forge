package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientWorldgenStaticRegistriesTest {
    @BeforeAll static void bootstrapMinecraft() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    @Test void staticCodecsWorkBeforePublicationAndExplicitClientRegistriesKeepPriority() {
        var root = new com.google.gson.JsonObject();
        root.add("noises", new com.google.gson.JsonObject());
        root.add("density_functions", new com.google.gson.JsonObject());
        var decoded = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, java.util.List.of());
        var codec = net.minecraft.resources.RegistryFixedCodec.create(Registries.SOUND_EVENT);
        var id = ResourceLocation.parse("minecraft:block.grass.break");
        var sound = new com.google.gson.JsonPrimitive(id.toString());
        assertSame(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getHolderOrThrow(
                        net.minecraft.resources.ResourceKey.create(Registries.SOUND_EVENT, id)),
                codec.parse(decoded.ops(), sound).result().orElseThrow());
        var clientSounds = new net.minecraft.core.MappedRegistry<net.minecraft.sounds.SoundEvent>(
                Registries.SOUND_EVENT, com.mojang.serialization.Lifecycle.stable());
        var replacement = clientSounds.register(net.minecraft.resources.ResourceKey.create(Registries.SOUND_EVENT, id),
                net.minecraft.sounds.SoundEvent.createVariableRangeEvent(id), com.mojang.serialization.Lifecycle.stable());
        clientSounds.freeze();
        var client = ClientWorldgenRegistries.decode(root,
                new RegistryAccess.ImmutableRegistryAccess(java.util.List.of(clientSounds)), java.util.List.of());
        assertSame(replacement, codec.parse(client.ops(), sound).result().orElseThrow());
        assertSame(clientSounds, client.access().registryOrThrow(Registries.SOUND_EVENT));
    }

    @Test void actualVanillaSnapshotRestoresFeaturesWithOnlyTheLoginNetworkRegistries() throws Exception {
        var factory = net.minecraft.server.packs.repository.ServerPacksSource.class
                .getDeclaredMethod("createVanillaPackSource");
        factory.setAccessible(true);
        try (var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(
                net.minecraft.server.packs.PackType.SERVER_DATA,
                java.util.List.of((net.minecraft.server.packs.PackResources) factory.invoke(null)))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var loaded = net.minecraft.resources.RegistryDataLoader.load(resources, builtin,
                    net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES);
            var all = new java.util.LinkedHashMap<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>,
                    net.minecraft.core.Registry<?>>();
            builtin.registries().forEach(entry -> all.put(entry.key(), entry.value()));
            loaded.registries().forEach(entry -> all.put(entry.key(), entry.value()));
            var server = new RegistryAccess.ImmutableRegistryAccess(java.util.List.copyOf(all.values()));
            loaded.registries().forEach(entry -> bindActualVanillaTags(entry.value(), resources));

            // This is the same codec sent by ClientboundLoginPacket in 1.20.1.
            // Its biomes deliberately omit worldgen settings and static registries.
            var wire = net.minecraft.core.RegistrySynchronization.NETWORK_CODEC.encodeStart(
                    net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, builtin), server)
                    .getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
            var network = net.minecraft.core.RegistrySynchronization.NETWORK_CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, builtin), wire)
                    .getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
            network = network.freeze(); // ClientboundLoginPacket freezes the decoded layer before publishing it.
            for (var key : java.util.List.of(Registries.BLOCK, Registries.CONFIGURED_FEATURE,
                    Registries.PLACED_FEATURE, Registries.STRUCTURE, Registries.STRUCTURE_SET))
                assertTrue(network.registry(key).isEmpty(), "A real login fallback must not carry " + key);
            for (var biome : network.registryOrThrow(Registries.BIOME)) {
                assertTrue(biome.getGenerationSettings().features().isEmpty());
                for (var step : net.minecraft.world.level.levelgen.GenerationStep.Carving.values())
                    assertFalse(biome.getGenerationSettings().getCarvers(step).iterator().hasNext());
            }

            var root = dev.xantha.vss.networking.server.session.WorldgenRegistrySnapshotTestBridge.encode(server);
            var strongholdsId = ResourceLocation.parse("minecraft:strongholds");
            var sourceRings = (net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement)
                    server.registryOrThrow(Registries.STRUCTURE_SET).get(strongholdsId).placement();
            assertTrue(sourceRings.preferredBiomes().size() > 0, "Load the actual stronghold biome tag");
            assertTrue(root.getAsJsonObject("structure_sets").getAsJsonObject(strongholdsId.toString())
                    .getAsJsonObject("placement").get("preferred_biomes").isJsonArray());
            var decoded = ClientWorldgenRegistries.decode(root, network, java.util.List.of());
            var result = decoded.access();
            assertSame(net.minecraft.core.registries.BuiltInRegistries.BLOCK, result.registryOrThrow(Registries.BLOCK));
            assertSame(net.minecraft.core.registries.BuiltInRegistries.BLOCK.holderOwner(),
                    decoded.ops().owner(Registries.BLOCK).orElseThrow());
            for (var key : java.util.List.of(Registries.NOISE, Registries.DENSITY_FUNCTION, Registries.CONFIGURED_CARVER,
                    Registries.CONFIGURED_FEATURE, Registries.PLACED_FEATURE, Registries.BIOME,
                    Registries.PROCESSOR_LIST, Registries.TEMPLATE_POOL, Registries.STRUCTURE, Registries.STRUCTURE_SET)) {
                var expectedKeys = server.registryOrThrow(key).keySet();
                assertFalse(expectedKeys.isEmpty(), "Exercise every actual vanilla registry: " + key);
                assertEquals(expectedKeys, result.registryOrThrow(key).keySet(),
                        "No vanilla entries may be omitted by encoding, disabled, or lost while decoding: " + key);
            }
            assertSnapshotCount(root, "configured_features", result, Registries.CONFIGURED_FEATURE);
            assertSnapshotCount(root, "placed_features", result, Registries.PLACED_FEATURE);
            assertSnapshotCount(root, "biomes", result, Registries.BIOME);
            assertSnapshotCount(root, "structures", result, Registries.STRUCTURE);
            assertSnapshotCount(root, "structure_sets", result, Registries.STRUCTURE_SET);
            assertSnapshotCount(root, "processor_lists", result, Registries.PROCESSOR_LIST);
            assertSnapshotCount(root, "template_pools", result, Registries.TEMPLATE_POOL);
            assertFalse(result.registryOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)
                    .getGenerationSettings().features().isEmpty(), "Snapshot biome generation must override the stripped network biome");
            assertEquals(Feature.SIMPLE_RANDOM_SELECTOR, result.registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .get(ResourceLocation.parse("minecraft:pointed_dripstone")).feature());
            var configured = result.registryOrThrow(Registries.CONFIGURED_FEATURE);
            assertEquals(Feature.TREE, configured.get(ResourceLocation.parse("minecraft:oak")).feature());
            var placed = result.registryOrThrow(Registries.PLACED_FEATURE);
            placed.forEach(feature -> {
                assertTrue(feature.feature().isBound());
                assertSame(configured.getHolderOrThrow(feature.feature().unwrapKey().orElseThrow()), feature.feature());
            });
            var biomes = result.registryOrThrow(Registries.BIOME);
            for (var biome : biomes) {
                biome.getGenerationSettings().features().forEach(set -> set.forEach(holder -> {
                    assertTrue(holder.isBound());
                    assertSame(placed.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder);
                }));
                for (var step : net.minecraft.world.level.levelgen.GenerationStep.Carving.values())
                    biome.getGenerationSettings().getCarvers(step).forEach(holder -> assertTrue(holder.isBound()));
            }
            var structures = result.registryOrThrow(Registries.STRUCTURE);
            structures.forEach(structure -> {
                assertTrue(structure.biomes().size() > 0, "Real vanilla biome tags must remain populated");
                structure.biomes().forEach(holder -> {
                    assertTrue(holder.isBound());
                    assertSame(biomes.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder);
                });
            });
            result.registryOrThrow(Registries.STRUCTURE_SET).forEach(set -> set.structures().forEach(entry -> {
                assertTrue(entry.structure().isBound());
                assertSame(structures.getHolderOrThrow(entry.structure().unwrapKey().orElseThrow()), entry.structure());
            }));
            result.registryOrThrow(Registries.TEMPLATE_POOL).forEach(pool -> assertTrue(pool.getFallback().isBound()));
            var restoredRings = (net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement)
                    result.registryOrThrow(Registries.STRUCTURE_SET).get(strongholdsId).placement();
            assertTrue(restoredRings.preferredBiomes().unwrapKey().isEmpty(), "The restored stronghold membership must not depend on a missing client tag");
            assertEquals(sourceRings.preferredBiomes().stream().map(holder -> holder.unwrapKey().orElseThrow())
                            .collect(java.util.stream.Collectors.toSet()),
                    restoredRings.preferredBiomes().stream().map(holder -> holder.unwrapKey().orElseThrow())
                            .collect(java.util.stream.Collectors.toSet()));
            restoredRings.preferredBiomes().forEach(holder ->
                    assertSame(biomes.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder));
        }
    }

    private static <T> void bindActualVanillaTags(net.minecraft.core.Registry<T> registry,
            net.minecraft.server.packs.resources.ResourceManager resources) {
        var loader = new net.minecraft.tags.TagLoader<net.minecraft.core.Holder<T>>(
                id -> registry.getHolder(net.minecraft.resources.ResourceKey.create(registry.key(), id)),
                net.minecraft.tags.TagManager.getTagDir(registry.key()));
        var resolved = new java.util.LinkedHashMap<net.minecraft.tags.TagKey<T>, java.util.List<net.minecraft.core.Holder<T>>>();
        loader.loadAndBuild(resources).forEach((id, values) -> resolved.put(
                net.minecraft.tags.TagKey.create(registry.key(), id), java.util.List.copyOf(values)));
        registry.bindTags(resolved);
    }

    private static <T> void assertSnapshotCount(com.google.gson.JsonObject snapshot, String section,
            RegistryAccess result, net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key) {
        int expected = snapshot.getAsJsonObject(section).size();
        assertTrue(expected > 0, "The actual vanilla snapshot must populate " + section);
        assertEquals(expected, result.registryOrThrow(key).size(), section + " must survive complete decoding");
    }

}
