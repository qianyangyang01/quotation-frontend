package com.milano.quotation.logistics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Bounded process-local read cache. Callers must verify the live revision before lookup. */
final class RevisionQueryCache<T> {
    private record Entry<T>(T value, long weight, long expires) {}
    private final LinkedHashMap<Object, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Object, CompletableFuture<T>> loading = new LinkedHashMap<>();
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<T> weigh;
    private long weight;

    RevisionQueryCache(int maxEntries, long maxWeight, ToLongFunction<T> weigh) {
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.weigh = weigh;
    }

    T get(Object key, Supplier<T> loader) {
        CompletableFuture<T> future;
        boolean owner = false;
        synchronized (this) {
            var cached = entries.get(key);
            if (cached != null && cached.expires() > System.nanoTime()) return cached.value();
            if (cached != null) { entries.remove(key); weight -= cached.weight(); }
            future = loading.get(key);
            if (future == null && loading.size() < maxEntries) {
                future = new CompletableFuture<>();
                loading.put(key, future);
                owner = true;
            }
        }
        // Unique cold requests beyond the bound do not accumulate retained futures.
        if (future == null) return loader.get();
        if (!owner) {
            try { return future.join(); }
            catch (CompletionException error) {
                if (error.getCause() instanceof RuntimeException cause) throw cause;
                throw error;
            }
        }
        try {
            T value = loader.get();
            long itemWeight = Math.max(1, weigh.applyAsLong(value));
            synchronized (this) {
                if (itemWeight <= maxWeight) {
                    while (!entries.isEmpty() && (entries.size() >= maxEntries || weight + itemWeight > maxWeight)) {
                        var first = entries.entrySet().iterator();
                        var removed = first.next();
                        weight -= removed.getValue().weight();
                        first.remove();
                    }
                    entries.put(key, new Entry<>(value, itemWeight, System.nanoTime() + Duration.ofMinutes(5).toNanos()));
                    weight += itemWeight;
                }
            }
            future.complete(value);
            return value;
        } catch (RuntimeException | Error error) {
            future.completeExceptionally(error);
            throw error;
        } finally {
            synchronized (this) { loading.remove(key); }
        }
    }
}
