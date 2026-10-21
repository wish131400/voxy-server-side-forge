package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

class PredictionSubmissionChangesTest {
    @Test void collectsOnlyChangedMembersAndDeduplicatesPublications() {
        var journal = new PredictionSubmissionChanges();
        var members = new LongOpenHashSet(new long[]{1, 3});
        var result = new LongOpenHashSet();
        journal.publish(1); journal.publish(2); journal.publish(1); journal.publish(3);
        assertTrue(journal.collect(0, journal.epoch(), members, result));
        assertEquals(members, result);
    }

    @Test void adoptsOnlyTheRequestedSnapshot() {
        var journal = new PredictionSubmissionChanges();
        var members = new LongOpenHashSet(new long[]{1, 2});
        var result = new LongOpenHashSet();
        journal.publish(1);
        long snapshot = journal.epoch();
        journal.publish(2);
        assertTrue(journal.collect(0, snapshot, members, result));
        assertEquals(new LongOpenHashSet(new long[]{1}), result);
        result.clear();
        assertTrue(journal.collect(snapshot, journal.epoch(), members, result));
        assertEquals(new LongOpenHashSet(new long[]{2}), result);
    }

    @Test void overwrittenOrUnknownHistoryRequiresFullValidation() {
        var journal = new PredictionSubmissionChanges();
        var members = new LongOpenHashSet(new long[]{1});
        var result = new LongOpenHashSet();
        for (int i = 0; i <= PredictionSubmissionChanges.CAPACITY; i++) journal.publish(1);
        assertFalse(journal.collect(0, journal.epoch(), members, result));
        assertFalse(journal.collect(-1, journal.epoch(), members, result));
        assertFalse(journal.collect(5, 4, members, result));
        assertFalse(journal.collect(0, journal.epoch() + 1, members, result));
        assertTrue(journal.collect(1, journal.epoch(), members, result));
        assertEquals(members, result);
    }

    @Test void unrelatedPublicationsDoNotInvalidateResidentMembers() {
        var journal = new PredictionSubmissionChanges();
        var result = new LongOpenHashSet();
        journal.publish(2);
        assertTrue(journal.collect(0, journal.epoch(), new LongOpenHashSet(new long[]{1}), result));
        assertTrue(result.isEmpty());
    }
}
