package dev.xantha.vss.networking.server.runtime;

import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Shares one coordinate-level persistent-column read between live requests and preload work. */
public final class PersistentColumnReadCoordinator {
    private final DiskTaskRuntime diskRuntime;
    private final ConcurrentHashMap<Key, SharedRead> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong duplicateReadSuppressed = new AtomicLong();
    private final AtomicLong preloadLiveJoins = new AtomicLong();
    private final AtomicLong completedReads = new AtomicLong();
    private final AtomicLong failedReads = new AtomicLong();

    public PersistentColumnReadCoordinator(
            PersistentColumnLodStore ignoredStore,
            DiskTaskRuntime diskRuntime) {
        this.diskRuntime = diskRuntime;
    }

    public boolean submit(
            ResourceKey<Level> dimension,
            int cx,
            int cz,
            int queueLimit,
            boolean preload,
            Supplier<PersistentColumnLodStore.Entry> readTask,
            Consumer<PersistentColumnLodStore.Entry> onComplete,
            Consumer<Throwable> onFailure,
            Consumer<RejectedExecutionException> onRejected) {
        Key key = new Key(dimension, cx, cz);
        SharedRead created = new SharedRead(preload);
        SharedRead existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            duplicateReadSuppressed.incrementAndGet();
            if (!preload && existing.preload) {
                preloadLiveJoins.incrementAndGet();
            }
            existing.add(onComplete, onFailure, onRejected);
            return true;
        }
        created.add(onComplete, onFailure, onRejected);

        Runnable work = () -> {
            try {
                created.complete(readTask.get());
                completedReads.incrementAndGet();
            } catch (Throwable error) {
                failedReads.incrementAndGet();
                if (error instanceof RejectedExecutionException rejected) {
                    created.reject(rejected);
                } else {
                    created.fail(error);
                }
            } finally {
                inFlight.remove(key, created);
            }
        };
        Consumer<RejectedExecutionException> reject = error -> {
            inFlight.remove(key, created);
            created.reject(error);
        };
        boolean submitted = diskRuntime.submitPrioritizedRead(
                queueLimit,
                preload,
                work,
                reject);
        if (!submitted) {
            // TrackedTaskExecutor invokes reject for a full queue, but keep this invariant
            // for custom executors that return false without invoking the callback.
            inFlight.remove(key, created);
            created.reject(new RejectedExecutionException("VSS disk task queue is full"));
        }
        return submitted;
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public long duplicateReadSuppressed() {
        return duplicateReadSuppressed.get();
    }

    public long preloadLiveJoins() {
        return preloadLiveJoins.get();
    }

    public long completedReads() {
        return completedReads.get();
    }

    public long failedReads() {
        return failedReads.get();
    }

    public void clear() {
        RejectedExecutionException shutdown = new RejectedExecutionException("VSS disk read coordinator stopped");
        for (var entry : inFlight.entrySet()) {
            if (inFlight.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().fail(shutdown);
            }
        }
    }

    private record Key(ResourceKey<Level> dimension, int cx, int cz) {
    }

    private static final class SharedRead {
        private final boolean preload;
        private final ArrayList<Listener> listeners = new ArrayList<>();
        private boolean completed;
        private PersistentColumnLodStore.Entry value;
        private Throwable failure;
        private RejectedExecutionException rejection;

        private SharedRead(boolean preload) {
            this.preload = preload;
        }

        void add(
                Consumer<PersistentColumnLodStore.Entry> onComplete,
                Consumer<Throwable> onFailure,
                Consumer<RejectedExecutionException> onRejected) {
            Listener listener = new Listener(onComplete, onFailure, onRejected);
            synchronized (this) {
                if (!completed) {
                    listeners.add(listener);
                    return;
                }
            }
            notifyListener(listener);
        }

        void complete(PersistentColumnLodStore.Entry value) {
            ArrayList<Listener> pending;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                this.value = value;
                pending = drain();
            }
            pending.forEach(this::notifyListener);
        }

        void fail(Throwable failure) {
            ArrayList<Listener> pending;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                this.failure = failure;
                pending = drain();
            }
            pending.forEach(this::notifyListener);
        }

        void reject(RejectedExecutionException rejection) {
            ArrayList<Listener> pending;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                this.rejection = rejection;
                pending = drain();
            }
            pending.forEach(this::notifyListener);
        }

        private ArrayList<Listener> drain() {
            ArrayList<Listener> pending = new ArrayList<>(listeners);
            listeners.clear();
            return pending;
        }

        private void notifyListener(Listener listener) {
            if (rejection != null) {
                if (listener.onRejected != null) {
                    listener.onRejected.accept(rejection);
                }
            } else if (failure != null) {
                if (listener.onFailure != null) {
                    listener.onFailure.accept(failure);
                }
            } else if (listener.onComplete != null) {
                listener.onComplete.accept(value);
            }
        }
    }

    private record Listener(
            Consumer<PersistentColumnLodStore.Entry> onComplete,
            Consumer<Throwable> onFailure,
            Consumer<RejectedExecutionException> onRejected) {
    }
}
