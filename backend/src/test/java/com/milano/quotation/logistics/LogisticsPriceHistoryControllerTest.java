package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogisticsPriceHistoryControllerTest {
    @Test void stalledWorkerHasBoundedWaitAndDoesNotStartPolling(){
        var service=mock(LogisticsPriceHistoryService.class);var worker=mock(LogisticsPriceHistoryWorker.class);
        when(worker.refresh(false)).thenReturn(new CompletableFuture<>());
        var controller=new LogisticsPriceHistoryController(service,worker);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->{
            var failure=assertThrows(AppException.class,controller::snapshot);assertEquals("PRICE_DISPLAY_UNAVAILABLE",failure.code());
        });
        verify(worker,times(1)).refresh(false);verifyNoInteractions(service);
    }
    @Test void databaseFailureReturnsDisplayUnavailableAndReleasesReaderSlot(){
        var service=mock(LogisticsPriceHistoryService.class);var worker=mock(LogisticsPriceHistoryWorker.class);
        when(worker.refresh(false)).thenReturn(CompletableFuture.completedFuture(null));
        when(service.snapshot()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("display missing"));
        var controller=new LogisticsPriceHistoryController(service,worker);
        for(int i=0;i<4;i++)assertEquals("PRICE_DISPLAY_UNAVAILABLE",assertThrows(AppException.class,controller::snapshot).code());
        verify(service,times(4)).snapshot();
    }
    @Test void concurrentReadsAreLimitedInsteadOfExhaustingRequestThreads() throws Exception {
        var service=mock(LogisticsPriceHistoryService.class);var worker=mock(LogisticsPriceHistoryWorker.class);
        var entered=new CountDownLatch(2);var pending=new CompletableFuture<Void>();
        when(worker.refresh(false)).thenAnswer(call->{entered.countDown();return pending;});
        var controller=new LogisticsPriceHistoryController(service,worker);
        try(var requests=Executors.newFixedThreadPool(2)){
            var a=requests.submit(()->assertThrows(AppException.class,controller::snapshot));
            var b=requests.submit(()->assertThrows(AppException.class,controller::snapshot));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofMillis(500),()->assertThrows(AppException.class,controller::snapshot));
            pending.completeExceptionally(new IllegalStateException("offline"));a.get();b.get();
            verify(worker,times(2)).refresh(false);verifyNoInteractions(service);
        }
    }
}
