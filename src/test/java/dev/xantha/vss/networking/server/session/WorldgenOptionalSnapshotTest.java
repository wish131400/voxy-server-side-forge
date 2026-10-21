package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldgenOptionalSnapshotTest {
    private static final ResourceKey<net.minecraft.core.Registry<Integer>> KEY =
            ResourceKey.createRegistryKey(ResourceLocation.parse("vss:optional_test"));

    @BeforeAll
    static void bootstrap() {
        if (net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Path.of("build", "tmp", "issue9-tests"));
        }
        net.minecraftforge.fml.loading.LoadingModList.of(List.of(), List.of(), null);
        dev.xantha.vss.client.prediction.ForgeTestBootstrap.prepare();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void skipsBrokenOptionalEntriesAndRetainsEntriesBeforeAndAfterThem() {
        var access = access(1, 2, 3, 4, 5);
        Codec<Integer> codec = Codec.INT.flatXmap(DataResult::success, value -> {
            if (value == 2) throw new IllegalAccessError("ModStructure tried to access Structure.settings");
            if (value == 3) return DataResult.error(() -> "unsupported value");
            if (value == 4) throw new IllegalStateException("broken getter");
            return DataResult.success(value);
        });
        JsonObject encoded = WorldgenCodecSnapshot.encodeOptional(
                access, RegistryOps.create(JsonOps.INSTANCE, access), KEY, codec);

        assertEquals(Set.of("vss:entry_1", "vss:entry_5"), encoded.keySet());
        assertEquals(1, encoded.get("vss:entry_1").getAsInt());
        assertEquals(5, encoded.get("vss:entry_5").getAsInt());
        assertEquals(5, access.registryOrThrow(KEY).size(), "the server's actual registry must remain intact");
    }

    @Test
    void virtualMachineFailuresAreNotTreatedAsUnsupportedModCodecs() {
        var access = access(1);
        var fatal = new OutOfMemoryError("test sentinel");
        Codec<Integer> codec = Codec.INT.xmap(value -> value, value -> { throw fatal; });
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> WorldgenCodecSnapshot.encodeOptional(
                access, RegistryOps.create(JsonOps.INSTANCE, access), KEY, codec)));
    }

    @Test
    void dropsWholeMixedSetsWithoutChangingHealthyWeightsOrPlacements() {
        JsonObject structures = json("{'vss:healthy':{}}");
        JsonObject sets = json("""
                {
                  'vss:healthy_set': {
                    'structures':[{'structure':'vss:healthy','weight':7}],
                    'placement':{'type':'minecraft:random_spread','spacing':32,'separation':8,'salt':1}
                  },
                  'vss:broken_set': {
                    'structures':[{'structure':'mostructures:broken','weight':1}],
                    'placement':{'type':'minecraft:random_spread'}
                  },
                  'vss:mixed_set': {
                    'structures':[{'structure':'vss:healthy','weight':3},
                                  {'structure':'mostructures:broken','weight':1}],
                    'placement':{'type':'minecraft:random_spread'}
                  }
                }
                """);
        JsonObject result = WorldgenCodecSnapshot.filterStructureSets(structures, sets);

        assertEquals(Set.of("vss:healthy_set"), result.keySet());
        assertEquals(sets.get("vss:healthy_set"), result.get("vss:healthy_set"));
        assertEquals(3, sets.size(), "filtering prediction data must not mutate the supplied snapshot");
    }

    @Test
    void removesTransitiveExclusionZoneDependenciesAndPreservesIndependentSets() {
        JsonObject structures = json("{'vss:healthy':{}}");
        JsonObject sets = json("""
                {
                  'vss:last': {
                    'structures':[{'structure':'vss:healthy','weight':1}],
                    'placement':{'exclusion_zone':{'other_set':'vss:middle','chunk_count':1}}
                  },
                  'vss:middle': {
                    'structures':[{'structure':'vss:healthy','weight':1}],
                    'placement':{'exclusion_zone':{'other_set':'vss:broken','chunk_count':1}}
                  },
                  'vss:broken': {
                    'structures':[{'structure':'mostructures:broken','weight':1}], 'placement':{}
                  },
                  'vss:independent': {
                    'structures':[{'structure':'vss:healthy','weight':1}], 'placement':{}
                  }
                }
                """);
        assertEquals(Set.of("vss:independent"),
                WorldgenCodecSnapshot.filterStructureSets(structures, sets).keySet());
    }

    @Test
    void inlineExclusionSetsCannotRetainMissingStructureReferences() {
        JsonObject structures = json("{'vss:healthy':{}}");
        JsonObject sets = json("""
                {
                  'vss:inline': {
                    'structures':[{'structure':'vss:healthy','weight':1}],
                    'placement':{'exclusion_zone':{'other_set':{
                      'structures':[{'structure':'mostructures:broken','weight':1}], 'placement':{}
                    },'chunk_count':1}}
                  }
                }
                """);
        assertEquals(0, WorldgenCodecSnapshot.filterStructureSets(structures, sets).size());
    }

    private static RegistryAccess access(int... values) {
        var registry = new MappedRegistry<Integer>(KEY, Lifecycle.stable());
        for (int value : values) {
            registry.register(ResourceKey.create(KEY, ResourceLocation.parse("vss:entry_" + value)),
                    value, Lifecycle.stable());
        }
        registry.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value.replace('\'', '"')).getAsJsonObject();
    }
}
