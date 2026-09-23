package com.milano.quotation.purchase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.persistence.EntityManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class PurchaseSearchPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_test").withUsername("quotation_app").withPassword("isolated_test_password");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static JdbcTemplate jdbc;
    static PurchaseProductRepository repository;
    @BeforeAll static void setup(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);
        factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);
        factory.setPackagesToScan("com.milano.quotation.purchase");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer jsonConfig=org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new com.milano.quotation.common.Jackson3HibernateConfig(),"jackson3JsonFormatMapper",new tools.jackson.databind.ObjectMapper());
        var properties=new HashMap<String,Object>();jsonConfig.customize(properties);factory.setJpaPropertyMap(properties);
        factory.afterPropertiesSet();em=factory.getObject().createEntityManager();
        repository=new JpaRepositoryFactory(em).getRepository(PurchaseProductRepository.class);
        for(int i=0;i<31;i++)jdbc.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready) values(?,?,?::jsonb,7,now(),now(),'ready',true)",
                UUID.randomUUID(),"BIZ-"+i,"{\"name\":\"工厂蓝色\",\"size\":\"XL\",\"note\":\"100%_ " + i + "\"}");
    }
    @AfterAll static void close(){if(em!=null)em.close();if(factory!=null)factory.destroy();}
    @BeforeEach void begin(){em.getTransaction().begin();}
    @AfterEach void rollback(){em.getTransaction().rollback();}
    @Test void searchPlanSettingDoesNotLeakToTheNextTransaction(){
        assertEquals("auto",em.createNativeQuery("show plan_cache_mode").getSingleResult());
        repository.useCustomSearchPlan();
        assertEquals("force_custom_plan",em.createNativeQuery("show plan_cache_mode").getSingleResult());
        em.getTransaction().rollback();em.getTransaction().begin();
        assertEquals("auto",em.createNativeQuery("show plan_cache_mode").getSingleResult());
    }
    @Test void analyticsProjectsEveryProductFromOneSnapshotWithoutPrivatePayloads(){
        em.createNativeQuery("""
            insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready)
            select gen_random_uuid(),'ANALYTICS-'||n,
              jsonb_build_object('category',' 服装 ','purchasePriceCny',case when n%2=0 then '12.30' else null end,
                'notes',repeat('private',1000),'productImage','private-url'),0,now(),now(),'disabled',false
            from generate_series(1,601) n
            """).executeUpdate();
        var service=new PurchaseProductService(repository,null,null,null,null);
        var result=service.analyticsCatalog();
        assertEquals(632,result.total());
        assertEquals(632,result.items().size());
        for(var item:result.items()) {
            assertEquals(3,item.size());
            assertTrue(item.has("sku"));assertTrue(item.has("category"));assertTrue(item.has("purchasePriceCny"));
            assertFalse(item.has("notes"));assertFalse(item.has("productImage"));
        }
        var sample=result.items().stream().filter(row->row.path("sku").asText().equals("ANALYTICS-2")).findFirst().orElseThrow();
        assertEquals("12.30",sample.path("purchasePriceCny").asText());
        assertEquals(" 服装 ",sample.path("category").asText());
        em.createNativeQuery("delete from purchase_product").executeUpdate();
        assertEquals(new PurchaseProductService.AnalyticsCatalog(List.of(),0),service.analyticsCatalog());
    }
    @Test void matchingAndTotalsAgreeWithOriginalSearchAcrossPages(){
        for(var query:List.of("BIZ-","蓝色","xl","100%_","not-found")){
            var expected=jdbc.queryForList("select sku from purchase_product where lower(sku) like concat('%',lower(?),'%') or lower(payload::text) like concat('%',lower(?),'%') order by updated_at desc,id",String.class,query,query);
            for(int page=0;page<5;page++){
                var result=repository.searchPage(query,10,page*10L);
                assertEquals(expected.size(),result.getFirst().getTotal());
                var actual=result.stream().filter(row->row.getSku()!=null).map(PurchaseProductRepository.SearchRow::getSku).toList();
                assertEquals(expected.subList(Math.min(page*10,expected.size()),Math.min(page*10+10,expected.size())),actual);
            }
        }
    }
    @Test void servicePreservesPayloadVersionsAndEmptyPageTotals(){
        var service=new PurchaseProductService(repository,null,null,null,null);
        var page=service.page("蓝色",PageRequest.of(0,10));
        assertEquals(31,page.getTotalElements());assertEquals(10,page.getContent().size());
        assertEquals(7,page.getContent().getFirst().path("_version").asInt());
        assertEquals("XL",page.getContent().getFirst().path("size").asText());
        assertTrue(page.getContent().getFirst().path("quoteReady").asBoolean());
        assertEquals(31,service.page("蓝色",PageRequest.of(9,10)).getTotalElements());
        assertEquals(1,service.page(" biz-1 ",PageRequest.of(0,10)).getTotalElements());
        assertEquals(31,service.page("",PageRequest.of(0,10)).getTotalElements());
        assertEquals(new PurchaseProductService.Stats(31,31,0,0),service.stats());
    }
}
