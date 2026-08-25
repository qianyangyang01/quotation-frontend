package com.milano.quotation.purchase;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PurchaseProductDeletionGuardPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");

    @Test void countsOnlyStructuredBusinessReferences() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14")).load().migrate();
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(dataSource);var guard=new PurchaseProductDeletionGuard(new NamedParameterJdbcTemplate(dataSource));
        var product=UUID.randomUUID();var supplier=UUID.randomUUID();var job=UUID.randomUUID();
        jdbc.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready) values (?,?,?::jsonb,7,now(),now(),'ready',true)",product,"SKU-1","{\"sku\":\"SKU-1\"}");
        jdbc.update("insert into supplier(id,name,code,enabled,version,created_at,updated_at) values (?,?,'SUP-1',true,0,now(),now())",supplier,"供应商");
        jdbc.update("insert into supplier_product(id,supplier_id,product_id,enabled,created_at,updated_at) values (?,?,?,true,now(),now())",UUID.randomUUID(),supplier,product);
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) values (?,'Q-1','ADMIN','pending',?::jsonb,0,now(),now())",UUID.randomUUID(),"{\"primarySku\":\"SKU-10、SKU-1\"}");
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) values (?,'Q-2','ADMIN','pending',?::jsonb,0,now(),now())",UUID.randomUUID(),"{\"primarySku\":\"SKU-10\"}");
        jdbc.update("insert into quotation_draft(owner_account,payload,version,updated_at) values ('ADMIN',?::jsonb,0,now())","{\"product\":{\"sku\":\"SKU-1\"},\"bundleItems\":[]}");
        jdbc.update("insert into quotation_template(id,owner_account,name,payload,version,created_at,updated_at) values (?,'ADMIN','模板',?::jsonb,0,now(),now())",UUID.randomUUID(),"{\"bundleItems\":[{\"sku\":\"SKU-1\"}]}");
        jdbc.update("insert into import_job(id,job_type,status,requested_by,source_name,payload,created_at,updated_at) values (?,'purchase-xlsx-async','completed','ADMIN','fixture.xlsx','{}',now(),now())",job);
        jdbc.update("insert into purchase_import_row(id,job_id,source_row,sku,payload,created_at,validation_status,applied_product_id,applied_at) values (?,?,2,'SKU-1','{}',now(),'valid',?,now())",UUID.randomUUID(),job,product);
        jdbc.update("insert into asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,created_at) values (?,?,'objects/test','image/png',1,'test.png','published',now())",UUID.randomUUID(),"a".repeat(64));

        var check=guard.inspect(product,"SKU-1",7);
        assertFalse(check.canDelete());assertEquals(7,check.version());assertEquals(1,check.supplierLinks());
        assertEquals(1,check.quotationRecords());assertEquals(1,check.drafts());assertEquals(1,check.templates());assertEquals(1,check.importBatches());
        assertTrue(check.blockingMessage().contains("任务中心整批回滚"));

        jdbc.update("delete from supplier_product");jdbc.update("delete from quotation_record");jdbc.update("delete from quotation_draft");jdbc.update("delete from quotation_template");jdbc.update("update purchase_import_row set rolled_back_at=now()");
        assertTrue(guard.inspect(product,"SKU-1",7).canDelete());
    }
}
