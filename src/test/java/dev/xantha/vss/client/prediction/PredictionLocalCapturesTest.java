package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionLocalCapturesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void thousandsOfBlockEditsCoalesceAndContinuousChangesHaveAMaximumDelay() {
        var inbox = new PredictionLocalCaptures();
        for (int i = 0; i < 1000; i++) inbox.changed(-1, 2, true, i * 100_000L);
        assertEquals(1, inbox.size()); assertEquals(999, inbox.coalesced);
        assertNull(inbox.next(100_000_000L));
        var request = inbox.next(200_000_000L); assertNotNull(request);
        inbox.changed(-1, 2, true, 300_000_000L);
        inbox.completed(request);
        assertEquals(1, inbox.size(), "a newer edit must survive completion of an older snapshot");
        inbox.changed(-1, 2, true, 499_000_000L);
        assertNotNull(inbox.next(500_000_000L), "continuous TNT cannot postpone capture forever");
    }
    @Test void queueIsBoundedAndEditsTakePriorityOverUnmodifiedArrivals() {
        var inbox = new PredictionLocalCaptures();
        for (int i = 0; i < PredictionLocalCaptures.MAX_PENDING; i++) assertTrue(inbox.changed(i, 0, false, 0));
        assertFalse(inbox.changed(9000, 0, false, 0));
        assertTrue(inbox.changed(9000, 0, true, 0));
        assertEquals(PredictionLocalCaptures.MAX_PENDING, inbox.size());
        assertTrue(inbox.next(500_000_000L).edited());
    }
    @Test void unloadCapturesAnArrivalThatPrecededPredictionInitializationWithoutLosingEditPriority() {
        var inbox = new PredictionLocalCaptures();
        var first = inbox.beforeUnload(-2, 3, 1);
        assertNotNull(first); assertFalse(first.edited()); assertEquals(1, inbox.size());
        inbox.changed(-2, 3, true, 2);
        var edited = inbox.get(-2, 3);
        assertSame(edited, inbox.beforeUnload(-2, 3, 3));
        assertTrue(edited.edited()); assertEquals(1, inbox.size());
        inbox.completed(first); assertSame(edited, inbox.get(-2, 3));
    }
    @Test void detachedPalettesDoNotChangeWhenTheLiveChunkIsEditedAgain() {
        var section = ClientCaptureExtractorTest.section();
        section.setBlockState(8, 8, 8, Blocks.GRAVEL.defaultBlockState());
        var chunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        org.mockito.Mockito.when(chunk.getSections()).thenReturn(new net.minecraft.world.level.chunk.LevelChunkSection[]{section});
        org.mockito.Mockito.when(chunk.getMinSection()).thenReturn(0);
        var snapshot = PredictionLocalCaptures.snapshot(chunk);
        assertNotNull(snapshot); assertTrue(snapshot.completesRequest());
        var detached = snapshot.sections()[0].section();
        section.setBlockState(8, 8, 8, Blocks.AIR.defaultBlockState());
        assertTrue(detached.getBlockState(8, 8, 8).is(Blocks.GRAVEL));
    }
}
