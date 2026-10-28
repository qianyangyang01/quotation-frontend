package com.milano.quotation.logistics;
import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@PreAuthorize("hasAuthority('PERM_logistics')")
public class LogisticsPriceHistoryController {
    private final LogisticsPriceHistoryService history;
    private final LogisticsPriceHistoryWorker worker;
    private final java.util.concurrent.Semaphore readers=new java.util.concurrent.Semaphore(2);
    public LogisticsPriceHistoryController(LogisticsPriceHistoryService history,LogisticsPriceHistoryWorker worker){this.history=history;this.worker=worker;}
    @GetMapping("/api/v1/logistics/price-history")
    public ResponseEntity<?> snapshot(){
        if(!readers.tryAcquire())throw unavailable();
        try{
            // One bounded wait for the publication worker, never polling or rebuilding on this thread.
            try{worker.refresh(false).get(1500,java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException ignored){throw unavailable();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(history.snapshot()));
        }catch(org.springframework.dao.DataAccessException failure){throw unavailable();}
        finally{readers.release();}
    }
    private com.milano.quotation.common.AppException unavailable(){
        return new com.milano.quotation.common.AppException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "PRICE_DISPLAY_UNAVAILABLE","价格展示暂不可用，请稍后手动刷新；不影响价格发布和正常报价");
    }
}
