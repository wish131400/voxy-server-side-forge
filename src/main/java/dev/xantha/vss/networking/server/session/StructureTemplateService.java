package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Resource I/O stays off the server tick; sending obeys the existing Netty/BO backpressure. */
public final class StructureTemplateService {
    private static final long MAX_BUFFERED_BYTES = 64L * 1024 * 1024;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final ConcurrentHashMap<UUID, Transfer> TRANSFERS = new ConcurrentHashMap<>();
    private static final AtomicLong BUFFERED_BYTES = new AtomicLong();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                var thread = new Thread(task, "VSS template resources");
                thread.setDaemon(true);
                return thread;
            });
    static { IO.allowCoreThreadTimeOut(true); }
    private StructureTemplateService() { }

    public static void handle(ServerPlayer player, StructureTemplateRequestC2SPayload request) {
        var snapshot = WorldgenTemplateCatalog.current(request.catalog());
        var entry = snapshot == null ? null : snapshot.entries().get(request.id());
        if (entry == null || !entry.digest().sha256().equals(request.hash())) {
            unavailable(player, request);
            return;
        }
        Transfer existing = TRANSFERS.get(player.getUUID());
        if (existing != null) {
            if (!existing.request.equals(request)) unavailable(player, request);
            return;
        }
        int bytes = entry.digest().bytes();
        if (BUFFERED_BYTES.addAndGet(bytes) > MAX_BUFFERED_BYTES) {
            BUFFERED_BYTES.addAndGet(-bytes);
            unavailable(player, request);
            return;
        }
        var transfer = new Transfer(player, request, snapshot, bytes);
        if (TRANSFERS.putIfAbsent(player.getUUID(), transfer) != null) {
            transfer.release();
            unavailable(player, request);
            return;
        }
        try {
            IO.execute(() -> {
                try {
                    if (!transfer.released.get()) transfer.data = entry.read();
                } catch (java.io.IOException | RuntimeException failure) {
                    VSSLogger.debug("VSS template read failed: " + request.id() + ": " + failure);
                } finally {
                    transfer.ready = true;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            discard(transfer);
            unavailable(player, request);
        }
    }

    public static void tick(MinecraftServer server) {
        int fragments = 0;
        long now = System.nanoTime();
        for (Transfer transfer : TRANSFERS.values()) {
            if (transfer.released.get()) continue;
            ServerPlayer player = transfer.player;
            if (player.server != server || server.getPlayerList().getPlayer(player.getUUID()) != player
                    || WorldgenTemplateCatalog.current(transfer.request.catalog()) != transfer.catalog
                    || now - transfer.started > TIMEOUT_NANOS) {
                discard(transfer);
                continue;
            }
            if (!transfer.ready || transfer.encoding.get() || fragments >= 8
                    || !VSSNetworking.canSendQueuedToPlayer(player)) continue;
            if (transfer.data == null) {
                discard(transfer);
                unavailable(player, transfer.request);
                continue;
            }
            int end = Math.min(transfer.data.length, transfer.offset + StructureTemplateDigest.FRAGMENT_BYTES);
            var payload = new StructureTemplateFragmentS2CPayload(transfer.request.catalog(), transfer.request.id(),
                    transfer.request.hash(), transfer.data.length, transfer.offset,
                    Arrays.copyOfRange(transfer.data, transfer.offset, end));
            transfer.offset = end;
            transfer.encoding.set(true);
            fragments++;
            try {
                VSSNetworking.sendQueuedToPlayer(player, payload, () -> {
                    if (end == transfer.data.length) discard(transfer);
                    else transfer.encoding.set(false);
                });
            } catch (RuntimeException failure) {
                discard(transfer);
                VSSLogger.debug("VSS template send failed: " + transfer.request.id() + ": " + failure);
            }
        }
    }

    public static void forgetPlayer(UUID id) {
        Transfer transfer = TRANSFERS.remove(id);
        if (transfer != null) transfer.release();
    }

    public static void clear() {
        TRANSFERS.values().forEach(StructureTemplateService::discard);
        IO.getQueue().clear();
    }

    private static void discard(Transfer transfer) {
        TRANSFERS.remove(transfer.player.getUUID(), transfer);
        transfer.release();
    }

    private static void unavailable(ServerPlayer player, StructureTemplateRequestC2SPayload request) {
        VSSNetworking.sendToPlayer(player, new StructureTemplateFragmentS2CPayload(
                request.catalog(), request.id(), request.hash(), 0, 0, new byte[0]));
    }

    private static final class Transfer {
        final ServerPlayer player;
        final StructureTemplateRequestC2SPayload request;
        final WorldgenTemplateCatalog.Snapshot catalog;
        final int bytes;
        final long started = System.nanoTime();
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicBoolean encoding = new AtomicBoolean();
        volatile byte[] data;
        volatile boolean ready;
        int offset;

        Transfer(ServerPlayer player, StructureTemplateRequestC2SPayload request,
                 WorldgenTemplateCatalog.Snapshot catalog, int bytes) {
            this.player = player;
            this.request = request;
            this.catalog = catalog;
            this.bytes = bytes;
        }
        void release() {
            if (released.compareAndSet(false, true)) BUFFERED_BYTES.addAndGet(-bytes);
        }
    }
}
