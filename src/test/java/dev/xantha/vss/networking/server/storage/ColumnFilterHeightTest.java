package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.ColumnInput;
import dev.xantha.vss.api.column.SectionDecision;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ColumnFilterHeightTest {
    private static final AtomicReference<ColumnInput> observed = new AtomicReference<>();
    private static final int DEPTH_BAND_CHUNK = 32;

    @BeforeAll static void register() {
        ColumnFilterFixtures.biomes();
        ColumnFilters.register("test:height", 1, column -> {
            observed.set(column);
            if (column.chunkX() != DEPTH_BAND_CHUNK) return ColumnDecision.updateAll();
            int top = Integer.MIN_VALUE;
            for (var section : column.sections()) {
                top = Math.max(top, section.maxBlockY());
            }
            assertNotEquals(Integer.MIN_VALUE, top);
            long cutoff = (long) top - 32;
            var selected = new HashMap<Integer, SectionDecision>();
            for (var section : column.sections()) {
                if (section.onlyAir() || section.maxBlockY() >= cutoff) {
                    selected.put(section.sectionY(), SectionDecision.UPDATE);
                }
            }
            return new ColumnDecision(SectionDecision.UNTOUCHED, selected);
        });
    }

    @ParameterizedTest
    @CsvSource({"-128,0,15,15,-2048", "-4,15,15,0,-49", "-2,0,0,0,-32",
            "-1,15,15,15,-1", "0,0,15,0,0", "0,15,0,15,15", "2,15,15,15,47", "127,15,15,15,2047"})
    void computesAbsoluteHeightAtSectionAndHorizontalBoundaries(int sectionY, int localY, int x, int z, int expected) {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(x, localY, z, Blocks.STONE.defaultBlockState());
        var context = new ColumnFilterPipeline.Context(Level.OVERWORLD, sectionY, sectionY + 1, ColumnFilterFixtures.biomes());
        ColumnFilterPipeline.apply(context, source(0, new int[] {sectionY}, section));
        assertFalse(observed.get().section(sectionY).onlyAir());
        assertEquals(expected, observed.get().section(sectionY).maxBlockY());
    }

    @Test void highestBlockWinsRegardlessOfHorizontalScanOrder() {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(0, 2, 0, Blocks.STONE.defaultBlockState());
        section.setBlockState(1, 14, 1, Blocks.STONE.defaultBlockState());
        section.setBlockState(15, 15, 15, Blocks.STONE.defaultBlockState());
        ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), source(0, new int[] {0}, section));
        assertEquals(15, observed.get().section(0).maxBlockY());
    }

    @Test void serializedAndOmittedAirHaveTheEmptyHeightSentinel() {
        var air = new LevelChunkSection(ColumnFilterFixtures.biomes());
        ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), source(0, new int[] {0}, air));
        for (var section : observed.get().sections()) {
            assertTrue(section.onlyAir());
            assertEquals(Integer.MIN_VALUE, section.maxBlockY());
        }
        ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), SectionSerializer.emptyColumn(0, 7, true));
        assertTrue(observed.get().sections().stream().allMatch(s -> s.onlyAir() && s.maxBlockY() == Integer.MIN_VALUE));
    }

    static Stream<Block> nonGroundBlocks() {
        return Stream.of(Blocks.WATER, Blocks.LAVA, Blocks.OAK_LEAVES, Blocks.TALL_GRASS);
    }

    @ParameterizedTest @MethodSource("nonGroundBlocks")
    void fluidsFoliageAndPlantsCountAsNonAir(Block block) {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        section.setBlockState(15, 13, 15, block.defaultBlockState());
        ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), source(0, new int[] {-1}, section));
        assertEquals(-3, observed.get().section(-1).maxBlockY());
    }

    @Test void detachedSnapshotReportsItsOwnHeightAfterOriginalSectionChanges() {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(15, 11, 15, Blocks.STONE.defaultBlockState());
        var snapshot = new SectionSerializer.ColumnSnapshot(0, 7, new SectionSerializer.SectionSnapshot[] {
                new SectionSerializer.SectionSnapshot(-1, section.getStates().copy(), section.getBiomes(), null, null)
        }, true, ColumnFilterFixtures.context());
        section.setBlockState(15, 15, 15, Blocks.STONE.defaultBlockState());
        var result = SectionSerializer.serializeSnapshot(snapshot);
        assertEquals(-5, observed.get().section(-1).maxBlockY());
        assertTrue(result.coverage().isFiltered()); assertFalse(result.completeColumn());
    }

    @Test void diskNbtReportsActualSectionTopWithoutTrustingHeightmaps() {
        ServerLevel level = level();
        var tag = new CompoundTag(); tag.putString("Status", "minecraft:full");
        var sections = new ListTag(); var section = new CompoundTag(); section.putByte("Y", (byte) -1);
        var states = new CompoundTag(); var palette = new ListTag(); var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone"); palette.add(stone); states.put("palette", palette);
        section.put("block_states", states); sections.add(section); tag.put("sections", sections);
        var heightmaps = new CompoundTag(); heightmaps.putLongArray("WORLD_SURFACE", new long[] {0});
        tag.put("Heightmaps", heightmaps);
        var result = NbtSectionSerializer.serializeTag(level, 0, 7, Optional.of(tag));
        assertEquals(-1, observed.get().section(-1).maxBlockY());
        assertEquals(Integer.MIN_VALUE, observed.get().section(0).maxBlockY());
        assertTrue(result.coverage().isFiltered()); assertFalse(result.completeColumn());
    }

    @Test void chunkAccessSerializerUsesTheSameHeightMetadata() {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(15, 13, 15, Blocks.STONE.defaultBlockState());
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] {null, section, null, null, null});
        var result = SectionSerializer.serializeChunkAccess(level(), chunk, 0, 7);
        assertEquals(-3, observed.get().section(-1).maxBlockY());
        assertEquals(Integer.MIN_VALUE, observed.get().section(-2).maxBlockY());
        assertTrue(result.coverage().isFiltered()); assertFalse(result.completeColumn());
    }

    @Test void metadataLetsExternalPolicyKeepTheCutoffSectionAndOmitOnlyLowerSections() {
        int[] ys = {-2, -1, 0, 1, 2};
        int[] localTops = {15, 15, 15, 0, 15};
        var sections = new LevelChunkSection[ys.length];
        for (int i = 0; i < ys.length; i++) {
            sections[i] = new LevelChunkSection(ColumnFilterFixtures.biomes());
            sections[i].setBlockState(15, localTops[i], 15, Blocks.STONE.defaultBlockState());
        }
        var result = ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), source(DEPTH_BAND_CHUNK, ys, sections));
        // Global top 47, cutoff 15: preserve equality and the whole intersecting section.
        assertArrayEquals(new int[] {0, 1, 2}, result.sectionYs());
        assertEquals(15, observed.get().section(0).maxBlockY());
        assertTrue(result.coverage().isFiltered()); assertFalse(result.completeColumn());
        assertEquals(ColumnFilters.snapshot().cacheKey(), result.coverage().filterKey());
    }

    private static ServerLevel level() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD); when(level.getMinSection()).thenReturn(-2);
        when(level.getSectionsCount()).thenReturn(5); when(level.getMinBuildHeight()).thenReturn(-32);
        when(level.getHeight()).thenReturn(80);
        when(level.registryAccess()).thenReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(ColumnFilterFixtures.biomes())));
        return level;
    }

    private static LoadedColumnData source(int chunkX, int[] sectionYs, LevelChunkSection... sections) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(sectionYs.length);
            for (int i = 0; i < sectionYs.length; i++) {
                buffer.writeByte(sectionYs[i]); sections[i].write(buffer);
                buffer.writeBoolean(false); buffer.writeBoolean(false);
            }
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
            return new LoadedColumnData(chunkX, 7, bytes, bytes.length, true);
        } finally { buffer.release(); }
    }
}
