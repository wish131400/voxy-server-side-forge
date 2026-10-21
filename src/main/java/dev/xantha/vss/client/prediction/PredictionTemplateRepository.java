package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

/** Local lookup and transfer verification never wait on the render thread or a generation worker. */
public final class PredictionTemplateRepository {
    private static final long MEMORY_BYTES = 32L * 1024 * 1024;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(75);
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                var thread = new Thread(task, "VSS local templates");
                thread.setDaemon(true);
                return thread;
            });
    static { IO.allowCoreThreadTimeOut(true); }
    private static volatile PredictionTemplateRepository current;

    private final String identity;
    private final Map<ResourceLocation, StructureTemplateDigest> catalog;
    private final Map<ResourceLocation, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<ResourceLocation, byte[]> ready = new LinkedHashMap<>(32, .75f, true);
    private final PredictionTemplateStore store;
    private final BiFunction<ResourceLocation, StructureTemplateDigest, byte[]> local;
    private final Consumer<StructureTemplateRequestC2SPayload> requests;
    private final Executor executor;
    private final LongSupplier clock;
    private ResourceLocation downloading;
    private long memoryBytes;
    private volatile boolean closed;

    static synchronized PredictionTemplateRepository open(JsonObject manifest) {
        String identity = manifest.get("_vss_catalog").getAsString();
        if (current != null && current.identity.equals(identity) && !current.closed) return current;
        if (current != null) current.close();
        var owner = new java.util.concurrent.atomic.AtomicReference<PredictionTemplateRepository>();
        var repository = new PredictionTemplateRepository(manifest,
                FMLPaths.GAMEDIR.get().resolve("vss/prediction/templates"), PredictionLocalTemplates::read,
                request -> Minecraft.getInstance().execute(() -> {
                    PredictionTemplateRepository active = current;
                    if (active == owner.get() && active != null && !active.closed
                            && Minecraft.getInstance().getConnection() != null) VSSNetworking.sendToServer(request);
                }), IO, System::nanoTime);
        current = repository;
        owner.set(repository);
        return repository;
    }

    static PredictionTemplateRepository forCatalog(JsonObject manifest) {
        PredictionTemplateRepository active = current;
        if (active == null || active.closed || !active.identity.equals(manifest.get("_vss_catalog").getAsString()))
            throw new java.util.concurrent.CancellationException("Expired template catalog");
        return active;
    }

    PredictionTemplateRepository(JsonObject manifest, Path directory,
            BiFunction<ResourceLocation, StructureTemplateDigest, byte[]> local,
            Consumer<StructureTemplateRequestC2SPayload> requests, Executor executor, LongSupplier clock) {
        identity = manifest.get("_vss_catalog").getAsString();
        if (!StructureTemplateDigest.validHash(identity)) throw new IllegalArgumentException("Invalid template catalog");
        var entries = new LinkedHashMap<ResourceLocation, StructureTemplateDigest>();
        for (var value : manifest.entrySet()) {
            if (value.getKey().equals("_vss_catalog")) continue;
            JsonObject descriptor = value.getValue().getAsJsonObject();
            entries.put(ResourceLocation.parse(value.getKey()), new StructureTemplateDigest(
                    descriptor.get("sha256").getAsString(), descriptor.get("bytes").getAsInt()));
        }
        catalog = Map.copyOf(entries);
        store = new PredictionTemplateStore(directory);
        this.local = local;
        this.requests = requests;
        this.executor = executor;
        this.clock = clock;
    }

    /** null means absent in the authoritative catalog; a known pending resource defers the entire job. */
    synchronized byte[] bytes(ResourceLocation id) {
        checkOpen();
        StructureTemplateDigest digest = catalog.get(id);
        if (digest == null) return null;
        byte[] bytes = ready.get(id);
        if (bytes != null) return bytes;
        Pending state = pending.computeIfAbsent(id, key -> new Pending());
        long now = clock.getAsLong();
        if (!state.localDone && !state.looking && now >= state.retryAt) {
            state.looking = true;
            submit(() -> lookup(id, digest, state), () -> { state.looking = false; state.retryAt = now + RETRY_NANOS; });
        }
        pump(now);
        throw new PredictionWorkDeferred();
    }

    void checkOpen() {
        if (closed) throw new java.util.concurrent.CancellationException("Expired template catalog");
    }

    private void lookup(ResourceLocation id, StructureTemplateDigest digest, Pending state) {
        if (closed) return;
        byte[] data = store.read(digest);
        try {
            if (data == null) data = local.apply(id, digest);
            if (data != null && !digest.matches(data)) data = null;
        } catch (RuntimeException failure) { data = null; }
        synchronized (this) {
            if (closed || pending.get(id) != state) return;
            state.looking = false;
            state.localDone = true;
            if (data != null) publish(id, data);
            pump(clock.getAsLong());
        }
    }

    private void pump(long now) {
        if (closed) return;
        if (downloading != null) {
            Pending state = pending.get(downloading);
            if (state != null && now - state.sent < TIMEOUT_NANOS) return;
            if (state != null) { state.parts = null; state.retryAt = now + RETRY_NANOS; }
            downloading = null;
        }
        for (var entry : pending.entrySet()) {
            Pending state = entry.getValue();
            if (!state.localDone || now < state.retryAt) continue;
            ResourceLocation id = entry.getKey();
            downloading = id;
            state.sent = now;
            try { requests.accept(new StructureTemplateRequestC2SPayload(identity, id, catalog.get(id).sha256())); }
            catch (RuntimeException unavailable) {
                downloading = null;
                state.retryAt = now + RETRY_NANOS;
            }
            return;
        }
    }

    public static void receive(StructureTemplateFragmentS2CPayload fragment) {
        PredictionTemplateRepository active = current;
        if (active == null || active.closed || !active.identity.equals(fragment.catalog())) return;
        try { IO.execute(() -> active.accept(fragment)); }
        catch (java.util.concurrent.RejectedExecutionException busy) { /* The outstanding request expires and retries. */ }
    }

    synchronized void accept(StructureTemplateFragmentS2CPayload fragment) {
        if (closed || !identity.equals(fragment.catalog()) || !fragment.id().equals(downloading)) return;
        StructureTemplateDigest digest = catalog.get(fragment.id());
        if (digest == null || !digest.sha256().equals(fragment.hash())) return;
        Pending state = pending.get(fragment.id());
        if (fragment.total() == 0) { retry(state); return; }
        if (fragment.total() != digest.bytes()) { retry(state); return; }
        if (state.parts == null) state.parts = new Assembly(fragment.total());
        Assembly parts = state.parts;
        int index = fragment.offset() / StructureTemplateDigest.FRAGMENT_BYTES;
        if (parts.received.get(index)) {
            if (!Arrays.equals(parts.data, fragment.offset(), fragment.offset() + fragment.data().length,
                    fragment.data(), 0, fragment.data().length)) retry(state);
            return;
        }
        System.arraycopy(fragment.data(), 0, parts.data, fragment.offset(), fragment.data().length);
        parts.received.set(index);
        if (parts.received.cardinality() != parts.count) return;
        byte[] data = parts.data;
        if (!digest.matches(data)) { retry(state); return; }
        downloading = null;
        publish(fragment.id(), data);
        // Runtime delivery runs on IO. Tests also use a serial executor.
        submit(() -> {
            try { store.write(digest, data); }
            catch (java.io.IOException failure) { VSSLogger.debug("VSS template cache write failed: " + failure); }
        }, () -> { });
        pump(clock.getAsLong());
    }

    private void retry(Pending state) {
        state.parts = null;
        state.retryAt = clock.getAsLong() + RETRY_NANOS;
        downloading = null;
        pump(clock.getAsLong());
    }

    private void publish(ResourceLocation id, byte[] data) {
        pending.remove(id);
        while (!ready.isEmpty() && (ready.size() >= 128 || memoryBytes + data.length > MEMORY_BYTES)) {
            var oldest = ready.entrySet().iterator();
            memoryBytes -= oldest.next().getValue().length;
            oldest.remove();
        }
        ready.put(id, data);
        memoryBytes += data.length;
    }

    private void submit(Runnable task, Runnable rejected) {
        try { executor.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException busy) { rejected.run(); }
    }

    synchronized void close() {
        closed = true;
        pending.clear();
        ready.clear();
        downloading = null;
        memoryBytes = 0;
    }

    public static void reset() {
        synchronized (PredictionTemplateRepository.class) {
            if (current != null) current.close();
            current = null;
            IO.getQueue().clear();
        }
        PredictionStructureTemplates.clearShared();
    }

    private static final class Pending {
        boolean localDone, looking;
        long retryAt, sent;
        Assembly parts;
    }
    private static final class Assembly {
        final byte[] data;
        final BitSet received = new BitSet();
        final int count;
        Assembly(int bytes) {
            data = new byte[bytes];
            count = (bytes + StructureTemplateDigest.FRAGMENT_BYTES - 1) / StructureTemplateDigest.FRAGMENT_BYTES;
        }
    }
}
