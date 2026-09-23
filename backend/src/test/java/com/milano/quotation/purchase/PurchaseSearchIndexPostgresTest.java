package com.milano.quotation.purchase;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class PurchaseSearchIndexPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate jdbc; PurchaseSearchIndex index; TransactionTemplate tx;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);index=new PurchaseSearchIndex(JdbcClient.create(ds));
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        jdbc.execute("drop table if exists purchase_product");
        jdbc.execute("create table purchase_product(id uuid primary key,sku text,version bigint,updated_at timestamptz,payload jsonb)");
        jdbc.execute("insert into purchase_product select md5(n::text)::uuid,'BIZ-'||n,7,now(),jsonb_build_object('category','服装','name','蓝色 XL','price',n) from generate_series(1,601)n");
    }
    void compare(String query){
        tx.execute(status->{
            var expected=jdbc.queryForList("select id from purchase_product where lower(sku) like '%'||lower(?)||'%' or lower(payload::text) like '%'||lower(?)||'%' order by updated_at desc,id",UUID.class,query,query);
            for(int page=0;page<4;page++){
                var result=index.select(query,page*200L,200);
                assertEquals(expected.size(),result.total());
                assertEquals(expected.subList(Math.min(page*200,expected.size()),Math.min(page*200+200,expected.size())),result.ids());
            }
            return null;
        });
    }
    @Test void repeatedLiteralSearchMatchesPostgresForAllFieldsAndEmptyPages(){for(var q:List.of("BIZ-","biz-1","服装","蓝色","XL","price","not-found","1"))compare(q);}
    @Test void externalUpdatesAndDeletesCannotReturnStaleMatches(){
        compare("服装");compare("new-name");
        jdbc.execute("update purchase_product set payload=jsonb_build_object('name','new-name'),version=version+1,updated_at=now() where sku='BIZ-1'");
        compare("服装");compare("new-name");
        jdbc.execute("delete from purchase_product where sku='BIZ-2'");
        jdbc.execute("insert into purchase_product values(gen_random_uuid(),'replacement',7,now(),'{\"name\":\"new-name\"}')");
        compare("服装");compare("new-name");
        jdbc.execute("update purchase_product set sku='RENAMED',updated_at=now(),payload='{\"name\":\"timestamp-only\"}' where sku='BIZ-3'");
        compare("RENAMED");compare("timestamp-only");compare("BIZ-3");
    }
    @Test void rollbackAndEmptyCatalogInvalidateTextSafely(){
        compare("服装");jdbc.execute("delete from purchase_product");compare("服装");
        jdbc.execute("insert into purchase_product values(gen_random_uuid(),'restored',0,now(),'{\"name\":\"服装\"}')");compare("服装");
    }
}
