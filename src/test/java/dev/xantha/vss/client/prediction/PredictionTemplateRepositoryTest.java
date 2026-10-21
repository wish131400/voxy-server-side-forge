package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionTemplateRepositoryTest {
    @TempDir Path directory;
    private static final ResourceLocation ID = ResourceLocation.parse("mod:large_house");
    private static final String CATALOG = "a".repeat(64);
    private final ArrayList<StructureTemplateRequestC2SPayload> requests = new ArrayList<>();
    private final Serial executor = new Serial();
    private final AtomicLong time = new AtomicLong(1);

    private PredictionTemplateRepository repository(byte[] expected, byte[] local) {
        return new PredictionTemplateRepository(manifest(Map.of(ID, expected)), directory,
                (id, digest) -> local, requests::add, executor, time::get);
    }

    static JsonObject manifest(Map<ResourceLocation, byte[]> entries) {
        var root = new JsonObject();
        root.addProperty("_vss_catalog", CATALOG);
        entries.forEach((id, data) -> {
            var entry = new JsonObject();
            entry.addProperty("sha256", StructureTemplateDigest.hash(data));
            entry.addProperty("bytes", data.length);
            root.add(id.toString(), entry);
        });
        return root;
    }

    @Test void matchingLocalResourceNeedsNoDownloadAndDoesNotRunIoOnCaller() {
        byte[] data = {1, 2, 3};
        var repository = repository(data, data);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertTrue(requests.isEmpty());
        executor.drain();
        assertArrayEquals(data, repository.bytes(ID));
        assertTrue(requests.isEmpty());
    }

    @Test void localVersionMismatchRequestsAuthoritativeBytesAndValidatesOutOfOrderDuplicates() throws Exception {
        byte[] data = large();
        var repository = repository(data, new byte[] {1});
        pending(repository);
        assertEquals(1, requests.size());
        var fragments = fragments(data);
        repository.accept(fragments.get(2));
        repository.accept(fragments.get(0));
        repository.accept(fragments.get(0));
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        for (var fragment : fragments) repository.accept(fragment);
        assertArrayEquals(data, repository.bytes(ID));
        executor.drain();
        // A new connection can reuse the verified content without a network request.
        repository.close();
        var restored = repository(data, null);
        requests.clear();
        pending(restored);
        assertArrayEquals(data, restored.bytes(ID));
        assertTrue(requests.isEmpty());
        assertEquals(data.length, Files.size(directory.resolve(StructureTemplateDigest.hash(data) + ".nbt")));
    }

    @Test void conflictingDuplicateAndBadCompleteHashNeverPublishPartialTemplate() {
        byte[] data = large();
        var repository = repository(data, null);
        pending(repository);
        var parts = fragments(data);
        repository.accept(parts.get(0));
        byte[] conflict = parts.get(0).data().clone(); conflict[0] ^= 1;
        repository.accept(new StructureTemplateFragmentS2CPayload(CATALOG, ID, StructureTemplateDigest.hash(data),
                data.length, 0, conflict));
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        time.addAndGet(3_000_000_000L);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertEquals(2, requests.size());
        for (var part : parts) {
            byte[] corrupted = part.data().clone(); corrupted[0] ^= 1;
            repository.accept(new StructureTemplateFragmentS2CPayload(CATALOG, ID, StructureTemplateDigest.hash(data),
                    data.length, part.offset(), corrupted));
        }
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        time.addAndGet(3_000_000_000L);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        parts.forEach(repository::accept);
        assertArrayEquals(data, repository.bytes(ID));
    }

    @Test void unavailableTimesOutOrRetriesWithoutPermanentlyMarkingMissing() {
        byte[] data = {7, 8};
        var repository = repository(data, null);
        pending(repository);
        repository.accept(new StructureTemplateFragmentS2CPayload(CATALOG, ID, StructureTemplateDigest.hash(data), 0, 0, new byte[0]));
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertEquals(1, requests.size());
        time.addAndGet(3_000_000_000L);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertEquals(2, requests.size());
        time.addAndGet(80_000_000_000L);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        time.addAndGet(3_000_000_000L);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertEquals(3, requests.size());
        fragments(data).forEach(repository::accept);
        assertArrayEquals(data, repository.bytes(ID));
    }

    @Test void staleCatalogWrongHashAndUnrequestedResourcesCannotAllocateOrComplete() {
        byte[] data = {4};
        var repository = repository(data, null);
        pending(repository);
        repository.accept(new StructureTemplateFragmentS2CPayload("b".repeat(64), ID, StructureTemplateDigest.hash(data), 1, 0, data));
        repository.accept(new StructureTemplateFragmentS2CPayload(CATALOG, ID, "b".repeat(64), 1, 0, data));
        repository.accept(new StructureTemplateFragmentS2CPayload(CATALOG, ResourceLocation.parse("mod:other"),
                StructureTemplateDigest.hash(data), 1, 0, data));
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        repository.close();
        fragments(data).forEach(repository::accept);
        assertThrows(java.util.concurrent.CancellationException.class, () -> repository.bytes(ID));
        assertNull(repository(data, data).bytes(ResourceLocation.parse("mod:absent")));
    }

    @Test void diskCorruptionIsRejectedAndServerOverrideHasDifferentCacheKey() throws Exception {
        byte[] data = {1, 2, 3};
        Files.write(directory.resolve(StructureTemplateDigest.hash(data) + ".nbt"), new byte[] {9, 9, 9});
        var repository = repository(data, null);
        pending(repository);
        assertEquals(1, requests.size());
        fragments(data).forEach(repository::accept);
        executor.drain();
        repository.close();
        requests.clear();
        var changed = repository(new byte[] {5, 6, 7}, data);
        pending(changed);
        assertEquals(1, requests.size());
        assertThrows(PredictionWorkDeferred.class, () -> changed.bytes(ID));
    }

    @Test void serverRequestsAreSerializedAndUnfinishedLocalJobsAreCancelled() {
        ResourceLocation second = ResourceLocation.parse("other:tree");
        byte[] one = {1}, two = {2};
        var repository = new PredictionTemplateRepository(manifest(Map.of(ID, one, second, two)), directory,
                (id, digest) -> null, requests::add, executor, time::get);
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(second));
        executor.drain();
        assertEquals(1, requests.size());
        fragments(one).forEach(repository::accept);
        assertEquals(2, requests.size());
        repository.close();
        executor.drain();
        assertThrows(java.util.concurrent.CancellationException.class, () -> repository.bytes(second));
    }

    private void pending(PredictionTemplateRepository repository) {
        assertThrows(PredictionWorkDeferred.class, () -> repository.bytes(ID));
        executor.drain();
    }

    private static byte[] large() {
        byte[] data = new byte[600 * 1024];
        new java.util.Random(10).nextBytes(data);
        return data;
    }

    private static ArrayList<StructureTemplateFragmentS2CPayload> fragments(byte[] data) {
        var result = new ArrayList<StructureTemplateFragmentS2CPayload>();
        for (int start = 0; start < data.length; start += StructureTemplateDigest.FRAGMENT_BYTES)
            result.add(new StructureTemplateFragmentS2CPayload(CATALOG, ID, StructureTemplateDigest.hash(data), data.length,
                    start, Arrays.copyOfRange(data, start, Math.min(data.length, start + StructureTemplateDigest.FRAGMENT_BYTES))));
        return result;
    }

    private static class Serial implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable task) { jobs.add(task); }
        void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
    }
}
