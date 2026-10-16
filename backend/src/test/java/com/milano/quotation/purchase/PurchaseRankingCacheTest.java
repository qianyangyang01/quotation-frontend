package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseRankingCacheTest {
    @Test void cachesForSixtySecondsAndExplicitRefreshReplacesTheSnapshot() {
        var clock=new AtomicLong(); var calls=new AtomicInteger();
        var cache=new PurchaseRankingCache<String,Integer>(4,Duration.ofSeconds(60),clock::get);
        assertEquals(1,cache.get("a",false,calls::incrementAndGet));
        assertEquals(1,cache.get("a",false,calls::incrementAndGet));
        assertEquals(2,cache.get("a",true,calls::incrementAndGet));
        clock.set(Duration.ofSeconds(60).toNanos()-1);
        assertEquals(2,cache.get("a",false,calls::incrementAndGet));
        clock.incrementAndGet();
        assertEquals(3,cache.get("a",false,calls::incrementAndGet));
    }

    @Test void revisionAndPermissionKeysDoNotReuseAnotherSnapshotAndCapacityIsBounded() {
        var cache=new PurchaseRankingCache<String,Integer>(2,Duration.ofSeconds(60),()->0L);
        var calls=new AtomicInteger();
        assertEquals(1,cache.get("all:v1",false,calls::incrementAndGet));
        assertEquals(2,cache.get("standard:v1",false,calls::incrementAndGet));
        assertEquals(3,cache.get("all:v2",false,calls::incrementAndGet));
        assertEquals(2,cache.size());
        assertEquals(4,cache.get("all:v1",false,calls::incrementAndGet));
        assertEquals(2,cache.size());
    }

    @Test void failedComputationIsRemovedAndCanBeRetried() {
        var cache=new PurchaseRankingCache<String,Integer>();
        var error=new IllegalStateException("unavailable");
        assertSame(error,assertThrows(IllegalStateException.class,()->cache.get("a",false,()->{throw error;})));
        assertEquals(0,cache.size());
        assertEquals(7,cache.get("a",false,()->7));
    }

    @Test void concurrentRefreshesShareThePendingComputation() throws Exception {
        var cache=new PurchaseRankingCache<String,Integer>();
        cache.get("a",false,()->0);
        var calls=new AtomicInteger(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var threads=new CopyOnWriteArrayList<Thread>();
        try(var executor=Executors.newFixedThreadPool(4,r->{var t=new Thread(r);threads.add(t);return t;})) {
            var futures=new ArrayList<Future<Integer>>();
            futures.add(executor.submit(()->cache.get("a",true,()->{
                calls.incrementAndGet();entered.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return 8;
            })));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<3;i++)futures.add(executor.submit(()->cache.get("a",true,()->{calls.incrementAndGet();return 99;})));
            try {
                long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
                while(System.nanoTime()<deadline && (threads.size()<4 || threads.stream().anyMatch(t->t.getState()!=Thread.State.WAITING && t.getState()!=Thread.State.TIMED_WAITING)))Thread.yield();
                assertEquals(1,calls.get());
                assertEquals(4,threads.size());
                assertTrue(threads.stream().allMatch(t->t.getState()==Thread.State.WAITING || t.getState()==Thread.State.TIMED_WAITING));
            } finally { release.countDown(); }
            for(var future:futures)assertEquals(8,future.get(2,TimeUnit.SECONDS));
        } finally {release.countDown();}
        assertEquals(1,calls.get());
    }
}
