package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogisticsPriceHistoryWorkerTest {
    @Test void slowWorkIsOffCallerThreadAndHundredsOfRequestsAreCoalesced() throws Exception {
        var history=mock(LogisticsPriceHistoryService.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var caller=Thread.currentThread();
        doAnswer(call->{assertNotSame(caller,Thread.currentThread());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(history).rebuildMissing();
        var worker=new LogisticsPriceHistoryWorker(history);
        try{
            var first=worker.refresh(false);assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<1000;i++)assertSame(first,worker.refresh(false));
            for(int i=0;i<1000;i++)assertSame(first,worker.refresh(true));
            verify(history,times(1)).rebuildMissing();
            release.countDown();first.get(3,TimeUnit.SECONDS);
            verify(history,times(2)).rebuildMissing();
        }finally{release.countDown();worker.close();}
    }

    @Test void failureIsNotRetriedAutomaticallyAndManualRetryCanRecover() throws Exception {
        var history=mock(LogisticsPriceHistoryService.class);
        doThrow(new IllegalStateException("broken source")).doNothing().when(history).rebuildMissing();
        var worker=new LogisticsPriceHistoryWorker(history);
        try{
            worker.refresh(false).get(2,TimeUnit.SECONDS);verify(history,times(1)).rebuildMissing();
            worker.refresh(false).get(2,TimeUnit.SECONDS);verify(history,times(2)).rebuildMissing();
        }finally{worker.close();}
    }

    @Test void rejectedSchedulingDoesNotThrowIntoPublicationAndNoTransactionDoesNotCalculate(){
        var history=mock(LogisticsPriceHistoryService.class);var worker=new LogisticsPriceHistoryWorker(history);
        worker.afterPublication();verifyNoInteractions(history);
        worker.close();
        assertTrue(assertDoesNotThrow(()->worker.refresh(true)).isCompletedExceptionally());
        verifyNoInteractions(history);
    }
}
