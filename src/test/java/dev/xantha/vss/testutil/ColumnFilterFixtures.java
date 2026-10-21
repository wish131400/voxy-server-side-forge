package dev.xantha.vss.testutil;

import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.networking.server.storage.ColumnFilterPipeline;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Detached serialized fixtures: no game, live chunks, Voxy or graphics context. */
public final class ColumnFilterFixtures {
    private static Registry<Biome> biomes;
    private ColumnFilterFixtures() { }

    public static synchronized Registry<Biome> biomes() {
        if (biomes == null) {
            MinecraftTestBootstrap.initialize();
            // NeoForge fills state IDs during registry setup in a real server.
            // The CPU fixture bootstraps vanilla without starting that lifecycle.
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    if (net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state) == -1)
                        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(state);
                }
            }
            var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
            VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                    .forEach(h -> registry.register(h.key(), h.value(), Lifecycle.stable()));
            biomes = registry.freeze();
        }
        return biomes;
    }

    public static ColumnFilterPipeline.Context context() {
        return new ColumnFilterPipeline.Context(Level.OVERWORLD, -2, 3, biomes());
    }

    public static LevelChunkSection stone() {
        var section = new LevelChunkSection(biomes());
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        return section;
    }

    public static byte[] light(int value) {
        byte[] bytes = new byte[2048];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    public static LoadedColumnData source(int chunkX) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(3);
            int[] lengths = new int[3];
            for (int i = 0; i < 3; i++) {
                int start = buffer.writerIndex();
                buffer.writeByte(i - 1);
                stone().write(buffer);
                buffer.writeBoolean(true).writeBytes(light(0x12));
                buffer.writeBoolean(true).writeBytes(light(0xEF));
                lengths[i] = buffer.writerIndex() - start;
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new LoadedColumnData(chunkX, 7, bytes, bytes.length, true, new int[] {-1, 0, 1}, lengths);
        } finally {
            buffer.release();
        }
    }
}
