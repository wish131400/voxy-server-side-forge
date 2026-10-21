package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.LongSupplier;

/** Render-thread soft VRAM cache. Visibility means the CPU frustum, never a
 * transient Hi-Z result. A drawable coarse replacement is mandatory. */
final class PredictionGpuResidency<K> {
    static final long MIB = 1024L * 1024L;
    static final long SWEEP_NANOS = 500_000_000L;
    static final long OUTSIDE_GRACE_NANOS = 10_000_000_000L;
    static final long OWNED_GRACE_NANOS = 2_000_000_000L;
    private static final long RETIRE_BYTES = 32L * MIB;
    private static final int RETIRE_TILES = 8;
    enum Reason { BUDGET, OWNERSHIP }
    private static final class Entry {
        long revision, bytes, lastUsed, ownedSince, ownershipRevision;
        boolean visible, protectedTile, fallback, owned, ownershipStarted;
    }
    private record Cold(long revision, Reason reason) { }
    record Retirement<K>(K key, Reason reason) { }
    private final Map<K, Entry> resident = new HashMap<>();
    private final Map<K, Cold> cold = new HashMap<>();
    private final PredictionMaintenanceCursor<K> observations = new PredictionMaintenanceCursor<>();
    private final PredictionMaintenanceCursor<K> coldObservations = new PredictionMaintenanceCursor<>();
    private final Map<K, long[]> contributions = new HashMap<>();
    private final long[] totals = new long[13];
    private int observationsRemaining;
    private long observationChecks;
    private final LongSupplier clock;
    private final long budget, target;
    private long bytes, nextSweep, budgetEvictions, ownedEvictions, releasedBytes, restores;
    private long opaqueBytes, waterBytes, previousBytes, quads, rectangles, waterRectangles;
    private long sourceQuads, displayQuads, sourceTerrain, sourceFeatures, sourcePreviews, sourceWater;
    private long committed, deferredVersions;

    PredictionGpuResidency(long budget, LongSupplier clock) {
        this.budget = Math.max(1, budget); this.target = this.budget - this.budget / 10; this.clock = clock;
    }
    static <K> PredictionGpuResidency<K> runtime() {
        int mib = Math.max(128, Math.min(4096, Integer.getInteger("vss.predictionGpuBudgetMiB", 1024)));
        return new PredictionGpuResidency<>(mib * MIB, System::nanoTime);
    }
    boolean sweepDue() {
        long now = clock.getAsLong();
        if (now < nextSweep) return false;
        nextSweep = now + SWEEP_NANOS; return true;
    }
    void uploaded(K key, long revision, long size) {
        Entry old = resident.get(key);
        if (old != null) bytes -= old.bytes;
        Entry entry = new Entry(); entry.revision = revision; entry.bytes = Math.max(0, size);
        entry.lastUsed = clock.getAsLong(); resident.put(key, entry); bytes += entry.bytes;
        observations.add(key);
    }
    void observe(K key, long size, boolean visible, boolean protectedTile, boolean fallback, boolean owned) {
        observe(key, size, visible, protectedTile, fallback, owned, 0);
    }
    void observe(K key, long size, boolean visible, boolean protectedTile, boolean fallback, boolean owned, long ownershipRevision) {
        Entry entry = resident.get(key); if (entry == null) return;
        bytes += size - entry.bytes; entry.bytes = size;
        entry.visible = visible; entry.protectedTile = protectedTile; entry.fallback = fallback; entry.owned = owned;
        long now = clock.getAsLong();
        if (visible) entry.lastUsed = now;
        if (owned && (!entry.ownershipStarted || entry.ownershipRevision != ownershipRevision)) {
            entry.ownershipStarted = true; entry.ownedSince = now;
        }
        entry.ownershipRevision = ownershipRevision;
        if (!owned) entry.ownershipStarted = false;
    }
    void used(K key) { used(key, clock.getAsLong()); }
    void used(K key, long now) { Entry entry = resident.get(key); if (entry != null) entry.lastUsed = now; }

    List<Retirement<K>> retirements(long committedBytes) {
        return retirements(committedBytes, resident.keySet());
    }

    List<Retirement<K>> retirements(long committedBytes, Collection<K> observed) {
        committed = committedBytes;
        long now = clock.getAsLong();
        boolean pressure = Math.max(bytes, committed) > budget && bytes > target;
        List<Map.Entry<K, Entry>> candidates = new ArrayList<>();
        for (var key : observed) {
            Entry e = resident.get(key); if (e == null) continue;
            if (e.protectedTile || !e.fallback) continue;
            boolean owned = e.owned && now - e.ownedSince >= OWNED_GRACE_NANOS;
            if (owned || pressure && !e.visible && now - e.lastUsed >= OUTSIDE_GRACE_NANOS) candidates.add(Map.entry(key, e));
        }
        candidates.sort(Comparator.<Map.Entry<K, Entry>>comparingInt(e -> e.getValue().owned ? 0 : 1)
                .thenComparingLong(e -> e.getValue().lastUsed).thenComparingLong(e -> -e.getValue().bytes));
        List<Retirement<K>> result = new ArrayList<>(); long released = 0;
        for (var entry : candidates) {
            Entry e = entry.getValue();
            if (!e.owned && bytes - released <= target) continue;
            if (!result.isEmpty() && (result.size() >= RETIRE_TILES || released + e.bytes > RETIRE_BYTES)) break;
            result.add(new Retirement<>(entry.getKey(), e.owned ? Reason.OWNERSHIP : Reason.BUDGET));
            released += e.bytes;
        }
        return result;
    }

    void evicted(Retirement<K> retirement) {
        Entry entry = resident.remove(retirement.key()); if (entry == null) return;
        observations.remove(retirement.key()); removeContribution(retirement.key());
        bytes -= entry.bytes; releasedBytes += entry.bytes;
        cold.put(retirement.key(), new Cold(entry.revision, retirement.reason()));
        coldObservations.add(retirement.key());
        if (retirement.reason() == Reason.OWNERSHIP) ownedEvictions++; else budgetEvictions++;
    }
    boolean allowsUpload(K key, long revision, boolean visible, boolean owned) {
        return allowsUpload(key, revision, visible, owned, false);
    }
    boolean allowsUpload(K key, long revision, boolean visible, boolean owned, boolean required) {
        Cold entry = cold.get(key); if (entry == null) return true;
        // Refinement changes CPU content, not whether an offscreen GPU copy is needed.
        boolean wanted = required || (entry.reason() == Reason.OWNERSHIP ? !owned : visible);
        if (wanted) { cold.remove(key); coldObservations.remove(key); restores++; }
        else if (entry.revision() != revision) {
            cold.put(key, new Cold(revision, entry.reason())); deferredVersions++;
        }
        return wanted;
    }
    void removed(K key) {
        Entry entry = resident.remove(key); if (entry != null) bytes -= entry.bytes;
        cold.remove(key); coldObservations.remove(key); observations.remove(key); removeContribution(key);
    }
    boolean beginObservation() {
        if (observationsRemaining > 0) return true;
        if (!sweepDue()) return false;
        observationsRemaining = observations.size();
        return observationsRemaining > 0;
    }
    K nextObservation() {
        if (observationsRemaining <= 0) return null;
        observationsRemaining--; observationChecks++; return observations.next();
    }
    void contribution(K key, long[] values) {
        var previous = contributions.put(key, values);
        for (int j = 0; j < totals.length; j++) totals[j] += values[j] - (previous == null ? 0 : previous[j]);
        publishTotals();
    }
    private void removeContribution(K key) {
        var previous = contributions.remove(key);
        if (previous != null) for (int j = 0; j < totals.length; j++) totals[j] -= previous[j];
        publishTotals();
    }
    private void publishTotals() {
        census(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5]);
        sourceCensus(totals[6], totals[7], totals[8], totals[9], totals[10], totals[11]);
    }
    long standaloneBytes() { return totals[12]; }
    void retainCold(java.util.function.Predicate<K> exists) {
        for (var key : List.copyOf(cold.keySet())) if (!exists.test(key)) { cold.remove(key); coldObservations.remove(key); }
    }
    void retainColdStep(java.util.function.Predicate<K> exists) {
        int count = Math.min(64, coldObservations.size());
        for (int j = 0; j < count; j++) {
            var key = coldObservations.next(); if (!exists.test(key)) { cold.remove(key); coldObservations.remove(key); }
        }
    }
    void census(long opaque, long water, long previous, long quads, long rectangles, long waterRectangles) {
        opaqueBytes = opaque; waterBytes = water; previousBytes = previous;
        this.quads = quads; this.rectangles = rectangles; this.waterRectangles = waterRectangles;
    }
    long residentBytes() { return bytes; }
    void sourceCensus(long source, long display, long terrain, long features, long previews, long water) {
        sourceQuads=source; displayQuads=display; sourceTerrain=terrain;
        sourceFeatures=features; sourcePreviews=previews; sourceWater=water;
    }
    int coldCount() { return cold.size(); }
    boolean cold(K key) { return cold.containsKey(key); }
    java.util.Map<String, Long> snapshot() {
        var result = new java.util.LinkedHashMap<String, Long>();
        result.put("budgetBytes",budget); result.put("liveBytes",bytes); result.put("committedBytes",committed);
        result.put("sourceQuads",sourceQuads); result.put("displayQuads",displayQuads);
        result.put("sourceTerrain",sourceTerrain); result.put("sourceFeatures",sourceFeatures); result.put("sourceWater",sourceWater);
        result.put("releasedBytes",releasedBytes); result.put("restores",restores);
        result.put("deferredVersions",deferredVersions); result.put("coldTiles",(long)cold.size());
        result.put("observationChecks", observationChecks); result.put("observationRemaining", (long) observationsRemaining);
        return result;
    }
    String diagnostics() {
        return "budgetBytes=" + budget + ",targetBytes=" + target + ",liveBytes=" + bytes + ",committedGeometryBytes=" + committed
                + ",budgetEvictions=" + budgetEvictions + ",ownedEvictions=" + ownedEvictions
                + ",releasedBytes=" + releasedBytes + ",coldTiles=" + cold.size() + ",restoreRequests=" + restores
                + ",deferredVersionUploads=" + deferredVersions + ",opaquePayloadBytes=" + opaqueBytes + ",waterPayloadBytes=" + waterBytes
                + ",waterDuplicationSavedBytes=" + (previousBytes - opaqueBytes - waterBytes)
                + ",quads=" + quads + ",rectangles=" + rectangles + ",waterRectangles=" + waterRectangles
                + ",residentSourceQuads=" + sourceQuads + ",residentDisplayVariantQuads=" + displayQuads
                + ",residentSourceTerrain=" + sourceTerrain + ",residentSourceFeatures=" + sourceFeatures
                + ",residentSourcePreviews=" + sourcePreviews + ",residentSourceWater=" + sourceWater
                + ",coordinateSavingUpperBoundBytes=" + rectangles * 12;
    }
    void clear() {
        resident.clear(); cold.clear(); bytes = nextSweep = committed = 0;
        observations.clear(); coldObservations.clear(); contributions.clear(); Arrays.fill(totals, 0); observationsRemaining = 0; observationChecks = 0;
        budgetEvictions = ownedEvictions = releasedBytes = restores = deferredVersions = 0;
        opaqueBytes = waterBytes = previousBytes = quads = rectangles = waterRectangles = 0;
        sourceQuads=displayQuads=sourceTerrain=sourceFeatures=sourcePreviews=sourceWater=0;
    }
}
