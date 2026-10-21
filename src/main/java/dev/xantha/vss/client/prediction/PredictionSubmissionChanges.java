package dev.xantha.vss.client.prediction;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Bounded invalidation history. Stores IDs only; never retains retired meshes. */
final class PredictionSubmissionChanges {
    static final PredictionSubmissionChanges MESH = new PredictionSubmissionChanges();
    static final PredictionSubmissionChanges GPU = new PredictionSubmissionChanges();
    private static final AtomicLong IDS = new AtomicLong();
    static final int CAPACITY = 8192;
    private final AtomicLongArray changed = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray stamps = new AtomicLongArray(CAPACITY);
    private volatile long epoch;

    static long newId() { return IDS.incrementAndGet(); }
    long epoch() { return epoch; }

    synchronized void publish(long id) {
        long next = epoch + 1;
        int slot = (int) (next & (CAPACITY - 1));
        stamps.set(slot, 0); // Invalidate the old entry before overwriting its ID.
        changed.set(slot, id);
        stamps.set(slot, next);
        epoch = next;
    }

    /** Overflow or an unknown cursor requires the ordinary exact validation. */
    boolean affects(long after, long until, LongSet members) {
        if (after == until) return false;
        long current = epoch;
        if (after < 0 || until < after || until > current || current - after > CAPACITY) return true;
        for (long at = after + 1; at <= until; at++) {
            int slot = (int) (at & (CAPACITY - 1));
            if (stamps.get(slot) != at) return true;
            long id = changed.get(slot);
            if (stamps.get(slot) != at || members.contains(id)) return true;
        }
        return epoch - after > CAPACITY;
    }

    /** Collect affected members only when the entire requested history is known.
     * On failure callers must discard the partial result and validate normally. */
    boolean collect(long after, long until, LongSet members, LongSet result) {
        if (after == until) return true;
        long current = epoch;
        if (after < 0 || until < after || until > current || current - after > CAPACITY) return false;
        for (long at = after + 1; at <= until; at++) {
            int slot = (int) (at & (CAPACITY - 1));
            if (stamps.get(slot) != at) return false;
            long id = changed.get(slot);
            if (stamps.get(slot) != at) return false;
            if (members.contains(id)) result.add(id);
        }
        return epoch - after <= CAPACITY;
    }
}
