package dev.xantha.vss.api.worldgen;

import com.google.gson.JsonElement;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Read-only inputs. Snapshot callbacks run while a profile is built, without generating chunks. */
public record TerrainSnapshotContext(ChunkGenerator generator, RegistryAccess registries,
                                     WorldgenRegistryDependencies dependencies) {
    public RegistryOps<JsonElement> ops() { return dependencies.ops(); }
    public void requireRegistry(ResourceLocation key) { dependencies.require(key); }
    public JsonElement generatorCodec() { return ChunkGenerator.CODEC.encodeStart(ops(), generator).getOrThrow(false, message -> { throw new IllegalArgumentException(message); }); }
}
