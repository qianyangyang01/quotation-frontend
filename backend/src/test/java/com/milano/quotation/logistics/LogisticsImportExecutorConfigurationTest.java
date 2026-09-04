package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class LogisticsImportExecutorConfigurationTest {
    @Test void keepsOneWorkerAndAppliesBoundedBackpressure()throws Exception{
        var executor=(ThreadPoolTaskExecutor)new LogisticsImportExecutorConfiguration().logisticsImportExecutor(1,1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try{
            executor.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
            executor.execute(()->{});assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));assertEquals(1,executor.getPoolSize());
        }finally{release.countDown();executor.shutdown();}
    }
    @Test void rejectsUnsafeWorkerAndQueueSettings(){var config=new LogisticsImportExecutorConfiguration();assertThrows(IllegalStateException.class,()->config.logisticsImportExecutor(0,10));assertThrows(IllegalStateException.class,()->config.logisticsImportExecutor(1,0));}
}
