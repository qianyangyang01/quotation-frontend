package com.milano.quotation.imports;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PurchaseImportJdbcPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");

    @Test void appliesAndRollsBackProductsAndImageRelationsWithSetBasedSql() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("25")).load().migrate();
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(dataSource);var service=new PurchaseImportJdbcService(new NamedParameterJdbcTemplate(dataSource),jdbc);
        var job=UUID.randomUUID();var existingProduct=UUID.randomUUID();var existingRow=UUID.randomUUID();var insertedRow=UUID.randomUUID();
        var oldAsset=UUID.randomUUID();var updateAsset=UUID.randomUUID();var insertAsset=UUID.randomUUID();
        jdbc.update("insert into import_job(id,job_type,status,requested_by,source_name,source_hash,payload,created_at,updated_at) values (?,?,?,?,?,?,?::jsonb,now(),now())",job,AsyncPurchaseImportService.JOB_TYPE,"ready","ADMIN","fixture.xlsx","a".repeat(64),"{}");
        insertAsset(jdbc,oldAsset,"old",null,"published");insertAsset(jdbc,updateAsset,"update",job,"temporary");insertAsset(jdbc,insertAsset,"insert",job,"temporary");
        jdbc.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready,source_hash) values (?,?,?::jsonb,3,now(),now(),'ready',true,?)",existingProduct,"SKU-UPDATE","{\"sku\":\"SKU-UPDATE\",\"marker\":\"before\"}","b".repeat(64));
        jdbc.update("insert into purchase_product_image(id,product_id,asset_id,image_type,sort_order) values (?,?,?,'product',0)",UUID.randomUUID(),existingProduct,oldAsset);
        insertRow(jdbc,existingRow,job,2,"SKU-UPDATE","update",3L,updateAsset);
        insertRow(jdbc,insertedRow,job,3,"SKU-INSERT","insert",null,insertAsset);
        insertManifest(jdbc,job,updateAsset,"SKU-UPDATE");insertManifest(jdbc,job,insertAsset,"SKU-INSERT");

        var applied=service.apply(job,List.of(existingRow,insertedRow),"c".repeat(64));
        assertEquals(2,applied.applied());assertEquals(0,applied.conflicts());
        assertEquals(4L,jdbc.queryForObject("select version from purchase_product where sku='SKU-UPDATE'",Long.class));
        assertEquals("after",jdbc.queryForObject("select payload->>'marker' from purchase_product where sku='SKU-UPDATE'",String.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from purchase_product where sku='SKU-INSERT'",Integer.class));
        assertEquals(updateAsset,jdbc.queryForObject("select asset_id from purchase_product_image where product_id=? and image_type='product'",UUID.class,existingProduct));
        assertEquals(2,jdbc.queryForObject("select count(*) from asset_object where id in (?,?) and storage_state='published' and staging_job_id is null",Integer.class,updateAsset,insertAsset));

        var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var retired=tx.execute(status->{assertEquals(0,service.lockAndCountRollbackConflicts(job));return service.rollback(job,List.of(existingRow,insertedRow));});
        assertNotNull(retired);assertEquals(2,retired);
        assertEquals("before",jdbc.queryForObject("select payload->>'marker' from purchase_product where sku='SKU-UPDATE'",String.class));
        assertEquals(oldAsset,jdbc.queryForObject("select asset_id from purchase_product_image where product_id=? and image_type='product'",UUID.class,existingProduct));
        assertEquals(0,jdbc.queryForObject("select count(*) from purchase_product where sku='SKU-INSERT'",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from purchase_import_row where job_id=? and rolled_back_at is not null",Integer.class,job));
        assertEquals(2,jdbc.queryForObject("select count(*) from asset_object where id in (?,?) and storage_state='temporary' and staging_job_id=?",Integer.class,updateAsset,insertAsset,job));
    }

    private static void insertAsset(JdbcTemplate jdbc,UUID id,String name,UUID job,String state){jdbc.update("insert into asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,staging_job_id,created_at) values (?,?,?,'image/png',8,?,?,?,now())",id,hex(id),"purchase-test/"+id,name+".png",state,job);}
    private static void insertRow(JdbcTemplate jdbc,UUID id,UUID job,int sourceRow,String sku,String action,Long expected,UUID asset){jdbc.update("insert into purchase_import_row(id,job_id,source_row,sku,payload,product_asset_id,created_at,validation_status,import_action,expected_version) values (?,?,?,?,?::jsonb,?,now(),'valid',?,?)",id,job,sourceRow,sku,"{\"sku\":\""+sku+"\",\"marker\":\"after\",\"quoteReady\":true}",asset,action,expected);}
    private static void insertManifest(JdbcTemplate jdbc,UUID job,UUID asset,String sku){jdbc.update("insert into migration_manifest_entry(id,job_id,sku,image_type,file_name,status,asset_id,asset_owned,updated_at) values (?,?,?,'product',?,'validated',?,true,now())",UUID.randomUUID(),job,sku,sku+"-product.png",asset);}
    private static String hex(UUID id){return id.toString().replace("-","").repeat(2);}
}
