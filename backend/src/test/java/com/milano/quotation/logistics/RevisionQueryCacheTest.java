package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RevisionQueryCacheTest {
    @Test void sharesColdRequestsWithoutBlockingOtherKeys() throws Exception {
        var cache = new RevisionQueryCache<String>(4, 100, String::length);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> cache.get("revision1|US", () -> {
                calls.incrementAndGet(); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return "US";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> cache.get("revision1|US", () -> { calls.incrementAndGet(); return "duplicate"; }));
            assertEquals("AU", executor.submit(() -> cache.get("revision1|AU", () -> "AU")).get(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(30, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("US", first.get(5, TimeUnit.SECONDS));
            assertEquals("US", second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void separatesRevisionsQueriesAndRetriesFailures() {
        var cache = new RevisionQueryCache<String>(4, 100, String::length);
        assertEquals("old", cache.get("dataset1|v1|US", () -> "old"));
        assertEquals("new", cache.get("dataset1|v2|US", () -> "new"));
        assertEquals("other", cache.get("dataset2|v2|US", () -> "other"));
        assertThrows(IllegalStateException.class, () -> cache.get("failure", () -> { throw new IllegalStateException("failed query"); }));
        assertEquals("recovered", cache.get("failure", () -> "recovered"));
    }

    @Test void boundsEntriesAndWeightAndDoesNotRetainOversizedResults() {
        var cache = new RevisionQueryCache<String>(2, 4, String::length);
        cache.get("a", () -> "aa");
        cache.get("b", () -> "bb");
        cache.get("c", () -> "cc");
        assertEquals("A", cache.get("a", () -> "A"));
        cache.get("large", () -> "oversized");
        assertEquals("retried", cache.get("large", () -> "retried"));
    }
}
