package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.api.column.*;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColumnFilterPipelineTest {
    private static final AtomicInteger calls = new AtomicInteger();
    @BeforeAll static void register() {
        ColumnFilterFixtures.biomes();
        ColumnFilters.register("test:source", 1, c -> {
            calls.incrementAndGet();
            if (c.chunkX() == 95) return new ColumnDecision(SectionDecision.UNTOUCHED, Map.of(0, SectionDecision.UPDATE));
            assertEquals(5, c.sections().size());
            assertTrue(c.section(-2).onlyAir(), "omitted confirmed source air remains distinct from UNTOUCHED");
            if (c.chunkX() == 99) throw new IllegalStateException("failed policy");
            if (c.chunkX() == 98) return new ColumnDecision(SectionDecision.UNTOUCHED, Map.of());
            if (c.chunkX() == 97) return ColumnDecision.updateAll();
            if (c.chunkX() == 96) return new ColumnDecision(SectionDecision.UNTOUCHED, Map.of(-2, SectionDecision.UPDATE));
            return new ColumnDecision(SectionDecision.UNTOUCHED, Map.of(
                    -1, SectionDecision.UPDATE,
                    0, new SectionDecision(SectionDecision.Action.CLEAR, SectionDecision.LightMode.SKY_ONLY),
                    1, new SectionDecision(SectionDecision.Action.UPDATE, SectionDecision.LightMode.NONE)));
        });
    }

    @Test void rebuildsManifestBodyAndAllThreeLightModesInOneCallback() throws Exception {
        LoadedColumnData source = ColumnFilterFixtures.source(0);
        byte[] original = source.sectionBytes().clone(); int before = calls.get();
        LoadedColumnData result = ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), source);
        assertEquals(1, calls.get() - before);
        assertFalse(result.completeColumn()); assertTrue(result.isDeliverable());
        assertEquals(ColumnFilters.snapshot().cacheKey(), result.coverage().filterKey());
        assertArrayEquals(new int[] {-1, 0, 1}, result.sectionYs());
        assertArrayEquals(original, source.sectionBytes());
        var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(result.sectionBytes()));
        try {
            assertEquals(3, input.readVarInt());
            for (int i = 0; i < 3; i++) {
                int start = input.readerIndex(); assertEquals(i - 1, input.readByte());
                var section = new LevelChunkSection(ColumnFilterFixtures.biomes()); section.read(input);
                assertEquals(i == 1, section.hasOnlyAir());
                assertEquals(i == 0, input.readBoolean());
                if (i == 0) { byte[] light = new byte[2048]; input.readBytes(light); assertArrayEquals(ColumnFilterFixtures.light(0x12), light); }
                assertEquals(i != 2, input.readBoolean());
                if (i != 2) { byte[] light = new byte[2048]; input.readBytes(light); assertArrayEquals(ColumnFilterFixtures.light(0xEF), light); }
                assertEquals(result.sectionLengths()[i], input.readerIndex() - start);
            }
            assertFalse(input.isReadable()); assertEquals(result.sectionBytes().length, result.sizeBytes());
        } finally { input.release(); }
        EncodedColumnData encoded = EncodedColumnData.encode(result, 123L);
        assertTrue(encoded.hasValidEncodedCrc32c()); assertEquals(result.coverage(), encoded.coverage());
        assertFalse(encoded.withColumnStamp(456L).completeColumn());
    }

    @Test void allUntouchedIsAnExplicitEmptyScopedUpdate() {
        var result = ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), ColumnFilterFixtures.source(98));
        assertArrayEquals(new byte[] {0}, result.sectionBytes()); assertEquals(0, result.sectionYs().length);
        assertTrue(result.isDeliverable()); assertFalse(result.completeColumn());
    }

    @Test void updateOfOmittedSourceAirStillEmitsAnExplicitAirSection() {
        var result = ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), ColumnFilterFixtures.source(96));
        assertArrayEquals(new int[] {-2}, result.sectionYs());
    }

    @Test void evenAnAllUpdatePolicyKeepsItsFilteredProvenance() {
        var result = ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), ColumnFilterFixtures.source(97));
        assertEquals(5, result.sectionYs().length); assertTrue(result.coverage().isFiltered());
        assertFalse(result.completeColumn());
    }

    @Test void tallCompleteSourceCanBeFilteredToASmallWireBand() {
        var input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            input.writeVarInt(131);
            for (int sy = -4; sy < 127; sy++) {
                input.writeByte(sy); new LevelChunkSection(ColumnFilterFixtures.biomes()).write(input);
                input.writeBoolean(false); input.writeBoolean(false);
            }
            byte[] bytes = new byte[input.readableBytes()]; input.readBytes(bytes);
            var source = new LoadedColumnData(95, 7, bytes, bytes.length, true);
            var result = ColumnFilterPipeline.apply(new ColumnFilterPipeline.Context(Level.OVERWORLD, -4, 127, ColumnFilterFixtures.biomes()), source);
            assertArrayEquals(new int[] {0}, result.sectionYs()); assertTrue(result.isDeliverable());
            assertFalse(result.completeColumn());
        } finally { input.release(); }
    }

    @Test void failureNeverFallsBackToUnfilteredAndIncompleteSourcesStayUndeliverable() {
        assertThrows(IllegalStateException.class,
                () -> ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), ColumnFilterFixtures.source(99)));
        var raw = ColumnFilterFixtures.source(0);
        var incomplete = new LoadedColumnData(0, 7, raw.sectionBytes(), raw.sizeBytes(), false, raw.sectionYs(), raw.sectionLengths());
        int before = calls.get();
        assertSame(incomplete, ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), incomplete));
        assertFalse(incomplete.isDeliverable()); assertEquals(before, calls.get());
    }

    @Test void snapshotSerializerFiltersOnceBeforeEncoding() {
        var section = ColumnFilterFixtures.stone();
        var snapshot = new SectionSerializer.ColumnSnapshot(0, 7, new SectionSerializer.SectionSnapshot[] {
                new SectionSerializer.SectionSnapshot(0, section.getStates().copy(), section.getBiomes(), null, null)
        }, true, ColumnFilterFixtures.context());
        int before = calls.get();
        assertTrue(SectionSerializer.serializeSnapshot(snapshot).coverage().isFiltered());
        assertEquals(1, calls.get() - before);
    }

    @Test void publicDiskNbtSerializerUsesTheSameSingleFilterBoundary() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD); when(level.getMinSection()).thenReturn(-2);
        when(level.getSectionsCount()).thenReturn(5); when(level.getMinBuildHeight()).thenReturn(-32);
        when(level.getHeight()).thenReturn(80);
        when(level.registryAccess()).thenReturn(new RegistryAccess.ImmutableRegistryAccess(java.util.List.of(ColumnFilterFixtures.biomes())));
        var tag = new CompoundTag(); tag.putString("Status", "minecraft:full");
        var sections = new ListTag(); var section = new CompoundTag(); section.putByte("Y", (byte) 0);
        var states = new CompoundTag(); var palette = new ListTag(); var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone"); palette.add(stone); states.put("palette", palette);
        section.put("block_states", states); sections.add(section); tag.put("sections", sections);
        int before = calls.get();
        assertTrue(NbtSectionSerializer.serializeTag(level, 0, 7, Optional.of(tag)).coverage().isFiltered());
        assertEquals(1, calls.get() - before);
    }
}
