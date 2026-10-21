package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** Constant layered terrain: decode and reduce once, then share immutable samples at every LOD. */
final class PredictionFlatTerrainSampler extends ClientTerrainSampler {
    private final ClientColumnSample sample;
    private final Holder<Biome> biome;
    private final PredictionColumnVolume volume;

    PredictionFlatTerrainSampler(DimensionProfile profile, JsonObject generator, ClientWorldgenRegistries worldgen) {
        super(profile.seed(), profile);
        var data = generator.getAsJsonObject("flat_layers");
        biome = worldgen.access().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BIOME, new ResourceLocation(data.get("biome").getAsString())));
        BlockState[] layers = new BlockState[profile.height()];
        int count = 0;
        for (var value : data.getAsJsonArray("layers")) {
            var layer = value.getAsJsonObject();
            int height = layer.get("height").getAsInt();
            if (height < 0 || height > 4096 || (long) count + height > 4096)
                throw new IllegalArgumentException("Invalid flat layer height");
            BlockState state = BlockState.CODEC.parse(worldgen.ops(), layer.get("state")).getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
            int end = Math.min(profile.height(), count + height);
            java.util.Arrays.fill(layers, Math.min(count, profile.height()), end, state);
            count += height;
        }
        volume = PredictionColumnVolume.sample(profile.minY(), profile.height(), y -> {
            var state = layers[y - profile.minY()];
            return state == null || state.isAir() ? -1 : BuiltInRegistries.BLOCK.getId(state.getBlock());
        }, id -> {
            var fluid = BuiltInRegistries.BLOCK.byId(id).defaultBlockState().getFluidState();
            return fluid.isEmpty() ? 0 : fluid.is(net.minecraft.world.level.material.Fluids.LAVA) ? 2 : 1;
        });
        int ground = profile.minY(), water = ground, fluid = 0, block = ClientColumnSample.NO_BLOCK;
        int under = block, deep = block;
        for (int i = 0; i < volume.size(); i++) {
            if (volume.fluid(i) == 0) { ground = volume.top(i); block = volume.block(i); }
            else if (volume.top(i) > ground) { water = volume.top(i); fluid = volume.fluid(i); }
        }
        if (block != ClientColumnSample.NO_BLOCK) {
            under = volume.blockAt(ground - 2); deep = volume.blockAt(ground - 4);
            if (under == ClientColumnSample.NO_BLOCK) under = block;
            if (deep == ClientColumnSample.NO_BLOCK) deep = under;
        }
        if (water <= ground) { water = ground; fluid = 0; }
        sample = new ClientColumnSample(ground, water, 0, block, 0, 0, 0, 0, fluid,
                block == ClientColumnSample.NO_BLOCK ? ClientColumnSample.FLAG_NO_SURFACE : 0,
                0, under, deep, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, volume);
    }
    @Override public int surfaceY(int x, int z) { return sample.surfaceY(); }
    @Override public int groundY(int x, int z) { return sample.surfaceY(); }
    @Override public ClientColumnSample sample(int x, int z) { return sample; }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return sample; }
    @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample; }
    @Override public ClientColumnSample samplePreview(int x, int z, int step) { return sample; }
    @Override public ClientColumnSample samplePreview(int x, int z) { return sample; }
    @Override public boolean exactWorldgen() { return true; }
    @Override boolean interiorTerrain() { return false; }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) { return volume; }
    @Override Holder<Biome> noiseBiome(int x, int y, int z) { return biome; }
    @Override List<String> cacheBiomes() { return List.of(biome.unwrapKey().orElseThrow().location().toString()); }
    @Override public int waterTint(int x, int y, int z) { return 0xB2000000 | biome.value().getWaterColor(); }
    @Override public int surfaceColor(int x, int y, int z) { return biome.value().getGrassColor(x, z); }
    @Override public int foliageColor(int x, int y, int z) { return biome.value().getFoliageColor(); }
}
