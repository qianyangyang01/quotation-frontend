package com.milano.quotation.purchase;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Small process-local cache. Pending entries share work; failed work is never cached. */
final class PurchaseRankingCache<K, V> {
    private record Entry<V>(CompletableFuture<V> value, long started) {}
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long ttl;
    private final LongSupplier ticker;

    PurchaseRankingCache() { this(64, Duration.ofSeconds(60), System::nanoTime); }
    PurchaseRankingCache(int capacity, Duration ttl, LongSupplier ticker) {
        this.capacity = capacity; this.ttl = ttl.toNanos(); this.ticker = ticker;
    }

    V get(K key, boolean refresh, Supplier<V> loader) {
        Entry<V> entry;
        boolean load = false;
        synchronized (entries) {
            long now = ticker.getAsLong();
            entries.entrySet().removeIf(e -> e.getValue().value.isDone() && now - e.getValue().started >= ttl);
            entry = entries.get(key);
            if (entry == null || (refresh && entry.value.isDone())) {
                entry = new Entry<>(new CompletableFuture<>(), now);
                // Never evict active computations to make room for another active computation.
                if (!entries.containsKey(key) && entries.size() >= capacity) {
                    var iterator = entries.entrySet().iterator();
                    while (iterator.hasNext() && entries.size() >= capacity) {
                        if (iterator.next().getValue().value.isDone()) iterator.remove();
                    }
                }
                if (entries.containsKey(key) || entries.size() < capacity) entries.put(key, entry);
                load = true;
            }
        }
        if (load) {
            try { entry.value.complete(loader.get()); }
            catch (Throwable error) {
                entry.value.completeExceptionally(error);
                synchronized (entries) { entries.remove(key, entry); }
            }
        }
        try { return entry.value.join(); }
        catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    int size() { synchronized (entries) { return entries.size(); } }
}
