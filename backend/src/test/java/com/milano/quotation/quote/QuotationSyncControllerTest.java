package com.milano.quotation.quote;

import com.milano.quotation.logistics.LogisticsQueryService;
import com.milano.quotation.logistics.LogisticsQuotationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotationSyncControllerTest {
    @Test void reportsAllFinanceVersionsWithoutLoadingPayloadsAlongsidePublishedLogistics() {
        var jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:sync-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", ""));
        // Deliberately no payload column: synchronization must remain a small metadata query.
        jdbc.sql("create table finance_setting(setting_key varchar(64) primary key, version bigint not null)").update();
        var keys=List.of("country-classification","channel-policies","customer-grades","exchange-rate","tax-settings","surcharge-settings","customer-operation-fees");
        for(var key:keys) jdbc.sql("insert into finance_setting values(:key,3)").param("key",key).update();
        var logistics=mock(LogisticsQueryService.class);
        when(logistics.manifestRevision()).thenReturn(new LogisticsQueryService.ManifestRevision("published-r1",7));
        var controller=new QuotationSyncController(jdbc,logistics,mock(LogisticsQuotationGuard.class));

        var response=controller.snapshot(List.of());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        var first=response.getBody().data();
        assertEquals("published-r1",first.logisticsRevision());
        assertTrue(first.purchaseVersions().isEmpty());
        assertEquals(7,first.financeVersions().size());
        for(var key:keys) assertEquals(3L,first.financeVersions().get(key));

        jdbc.sql("update finance_setting set version=4 where setting_key='exchange-rate'").update();
        var second=controller.snapshot(List.of()).getBody().data();
        assertEquals(4L,second.financeVersions().get("exchange-rate"));
        assertEquals(3L,second.financeVersions().get("customer-grades"));
        assertEquals(3L,first.financeVersions().get("exchange-rate"));
        verify(logistics,times(2)).manifestRevision();
        verifyNoMoreInteractions(logistics);
    }
}
