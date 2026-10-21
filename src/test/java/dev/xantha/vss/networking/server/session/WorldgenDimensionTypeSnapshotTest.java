package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldgenDimensionTypeSnapshotTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @Test void dimensionMetadataRoundTripsWithoutChangingGeneratorInputs() throws Exception {
        var access = TerrainPredictionFixtures.access();
        var settings = new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(java.util.Optional.empty(),
                access.registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS), java.util.List.of());
        settings.getLayersInfo().add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(4, net.minecraft.world.level.block.Blocks.STONE));
        settings.updateLayers();
        var original = WorldgenCodecSnapshot.encodeGenerator(new net.minecraft.world.level.levelgen.FlatLevelSource(settings), access,
                new DensityFunctionReferences(), new WorldgenRegistryDependencies(access, java.util.List.of()));
        var expected = decode(original);
        for (var key : java.util.List.of(BuiltinDimensionTypes.OVERWORLD, BuiltinDimensionTypes.NETHER, BuiltinDimensionTypes.END)) {
            var type = access.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key);
            var actual = decode(WorldgenCodecSnapshot.withDimensionType(original, type));
            assertEquals(JsonParser.parseString(DimensionType.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, type).getOrThrow(false, message -> { throw new IllegalArgumentException(message); }).toString()),
                    actual.remove("vss_dimension_type"));
            assertEquals(expected, actual);
        }
        var empty = WorldgenCodecSnapshot.Encoded.empty();
        assertSame(empty, WorldgenCodecSnapshot.withDimensionType(empty,
                access.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD)));
    }

    private static com.google.gson.JsonObject decode(WorldgenCodecSnapshot.Encoded value) throws Exception {
        byte[] bytes = LodByteCompression.decompress(value.bytes(), value.compression(), value.rawSize(), 8 * 1024 * 1024);
        return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
