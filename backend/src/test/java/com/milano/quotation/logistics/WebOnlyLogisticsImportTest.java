package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.concurrent.Executor;
import static org.mockito.Mockito.*;

class WebOnlyLogisticsImportTest {
    @Test void webSlotNeverRecoversOrDispatchesWorkerJobs() {
        var jdbc=mock(JdbcClient.class);
        var executor=mock(Executor.class);
        var service=new LogisticsImportService(jdbc,null,null,null,null,null,mock(PlatformTransactionManager.class),executor);
        ReflectionTestUtils.setField(service,"webOnly",true);
        ReflectionTestUtils.setField(service,"resumeOnStart",true);
        ReflectionTestUtils.setField(service,"reconcileEnabled",true);
        service.resumeQueued();
        ReflectionTestUtils.invokeMethod(service,"dispatch",UUID.randomUUID());
        verifyNoInteractions(jdbc,executor);
    }
}
