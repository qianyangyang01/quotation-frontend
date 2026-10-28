package com.milano.quotation.logistics;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/** Optional display work: no calculation or database access on the publication thread. */
@Component
public class LogisticsPriceHistoryWorker {
    private static final org.slf4j.Logger log=LoggerFactory.getLogger(LogisticsPriceHistoryWorker.class);
    private final LogisticsPriceHistoryService history;
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1),task->{var thread=new Thread(task,"logistics-price-display");thread.setDaemon(true);return thread;},
        new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Void> active;
    private boolean again;

    public LogisticsPriceHistoryWorker(LogisticsPriceHistoryService history){this.history=history;}

    public void afterPublication(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){refresh(true);}
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm(){refresh(false);}

    /** Readers join existing work; new committed publications request at most one additional pass. */
    public synchronized CompletableFuture<Void> refresh(boolean published){
        if(active!=null&&!active.isDone()){again|=published;return active;}
        var completion=new CompletableFuture<Void>();active=completion;
        try{executor.execute(()->run(completion));}
        catch(RuntimeException failure){completion.completeExceptionally(failure);log.warn("Price display scheduling unavailable",failure);}
        return completion;
    }

    private void run(CompletableFuture<Void> completion){
        while(true){
            try{history.rebuildMissing();}
            catch(RuntimeException failure){log.warn("Price display update failed; published source remains unchanged",failure);}
            synchronized(this){
                if(again&&!executor.isShutdown()){again=false;continue;}
                completion.complete(null);return;
            }
        }
    }

    @PreDestroy public void close(){executor.shutdownNow();}
}
