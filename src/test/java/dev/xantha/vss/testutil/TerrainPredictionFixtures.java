package dev.xantha.vss.testutil;

import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public final class TerrainPredictionFixtures {
    private static RegistryAccess access;
    private TerrainPredictionFixtures() { }
    public static void bootstrap() {
        if (net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build/tmp/terrain-capability-tests"));
        net.minecraftforge.fml.loading.LoadingModList.of(List.of(), List.of(), null);
        dev.xantha.vss.client.prediction.ForgeTestBootstrap.prepare();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Forge initializes state caches during loader startup, outside vanilla Bootstrap.
        // Standalone tests must do the same before querying liquid block states.
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK)
            for (var state : block.getStateDefinition().getPossibleStates()) state.initCache();
    }
    public static synchronized RegistryAccess access() {
        if (access == null) {
            var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            var values = new ArrayList<Registry<?>>();
            net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(data -> values.add(copy(lookup.lookupOrThrow(data.key()))));
            access = new RegistryAccess.ImmutableRegistryAccess(values);
        }
        return access;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Registry<T> copy(HolderLookup.RegistryLookup<T> source) {
        var result = new MappedRegistry<T>((net.minecraft.resources.ResourceKey) source.key(), Lifecycle.stable());
        source.listElements().forEach(holder -> result.register(holder.key(), holder.value(), com.mojang.serialization.Lifecycle.stable()));
        return result.freeze();
    }
    public static NoiseBasedChunkGenerator noise() {
        return new NoiseBasedChunkGenerator(new FixedBiomeSource(access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),
                access().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }
    public static JsonObject emptyRegistries() {
        var result = new JsonObject();
        for (String name : List.of("noises", "density_functions", "biomes", "configured_features", "placed_features"))
            result.add(name, new JsonObject());
        return result;
    }
}
