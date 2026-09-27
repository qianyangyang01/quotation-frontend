package com.milano.quotation.purchase;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ShimoSyncPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired ShimoSyncService sync;
    @Autowired PurchaseProductService products;
    @MockitoSpyBean PurchasePasteService paste;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean ShimoClient client;
    @BeforeEach void before() {
        db.update("delete from shimo_sync_change");db.update("delete from shimo_sync_item");db.update("delete from shimo_sync_fetch_page");db.update("delete from shimo_sync_fetch_sheet");db.update("delete from shimo_sync_run");db.update("update shimo_sync_control set enabled=true");
    }
    String sku() {return "SH-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);}
    ShimoClient.SourceRow source(String sheet,ArrayNode cells) {return new ShimoClient.SourceRow(sheet,2,ShimoRowMapper.sku(cells),cells);}
    void read(ShimoClient.SourceRow... rows) {when(client.readAll(any(),any())).thenReturn(List.of(rows));sync.runOnce();}
    String state(String sku) {return db.queryForObject("select status from shimo_sync_item where sku=?",String.class,sku);}
    long changes() {return db.queryForObject("select count(*) from shimo_sync_change",Long.class);}
    @Test void noonAndEveningEachRunOnceAndRestartsUsePersistedSchedule() {
        db.update("update shimo_sync_control set updated_at='2026-09-27T00:00:00Z'");
        var noon=java.time.Instant.parse("2026-09-27T04:00:00Z");
        var row=ShimoRowMapperTest.cells(sku());
        when(client.readAll(any(),any())).thenReturn(List.of(source("业务新人",row)));
        sync.runOnce(null,noon);
        db.update("update shimo_sync_run set finished_at='2026-09-27T04:00:01Z'");
        sync.runOnce(null,noon.plusSeconds(30));verify(client,times(1)).readAll(any(),any());
        row.set(4,IntNode.valueOf(170));row.set(22,IntNode.valueOf(8));
        sync.runOnce(null,java.time.Instant.parse("2026-09-27T10:00:00Z"));
        assertEquals(170,products.get(ShimoRowMapper.sku(row)).path("weightG").asInt());
        assertEquals(List.of("daily_evening","daily_noon"),db.queryForList("select mode from shimo_sync_run where status='completed' order by mode",String.class));
        db.update("update shimo_sync_run set finished_at='2026-09-27T10:00:01Z' where mode='daily_evening'");
        assertNull(sync.automaticMode(java.time.Instant.parse("2026-09-27T10:00:30Z")));
        assertEquals("incremental",sync.automaticMode(java.time.Instant.parse("2026-09-27T10:10:01Z")));
        assertEquals("daily_noon",sync.automaticMode(java.time.Instant.parse("2026-09-28T04:00:00Z")));
    }
    @Test void failedDailySlotBacksOffAndResumesWithoutSuppressingEvening() {
        db.update("update shimo_sync_control set updated_at='2026-09-27T00:00:00Z'");
        var noon=java.time.Instant.parse("2026-09-27T04:00:00Z");
        when(client.readAll(any(),any())).thenThrow(new IllegalStateException("模拟断网"));sync.runOnce(null,noon);
        var id=db.queryForObject("select id from shimo_sync_run",UUID.class);
        db.update("update shimo_sync_run set finished_at='2026-09-27T04:00:02Z'");
        sync.runOnce(null,noon.plusSeconds(300));verify(client,times(1)).readAll(any(),any());
        doReturn(List.of()).when(client).readAll(any(),any());sync.runOnce(null,noon.plusSeconds(602));
        assertEquals(List.of(id),db.queryForList("select id from shimo_sync_run where status='completed'",UUID.class));
        assertEquals("daily_evening",sync.automaticMode(java.time.Instant.parse("2026-09-27T10:00:00Z")));
    }
    @Test void dateFilteringHappensBeforePagingAndIncludesWholeShanghaiEndDate() {
        var a=ShimoRowMapperTest.cells(sku());var b=ShimoRowMapperTest.cells(sku());var c=ShimoRowMapperTest.cells(sku());
        read(source("业务新人",a),source("业务新人",b),source("业务新人",c));
        db.update("update shimo_sync_change set created_at='2026-09-26T16:00:00Z' where sku=?",ShimoRowMapper.sku(a));
        db.update("update shimo_sync_change set created_at='2026-09-27T15:59:59Z' where sku=?",ShimoRowMapper.sku(b));
        db.update("update shimo_sync_change set created_at='2026-09-27T16:00:00Z' where sku=?",ShimoRowMapper.sku(c));
        var day=java.time.LocalDate.parse("2026-09-27");
        var first=sync.changes(0,false,1,day,day);var second=sync.changes(1,false,1,day,day);
        assertEquals(2L,first.get("total"));
        assertEquals(ShimoRowMapper.sku(b),((Map<?,?>)((List<?>)first.get("rows")).getFirst()).get("sku"));
        assertEquals(ShimoRowMapper.sku(a),((Map<?,?>)((List<?>)second.get("rows")).getFirst()).get("sku"));
        assertEquals(0L,sync.changes(0,true,10,day,day).get("total"));
        assertThrows(com.milano.quotation.common.AppException.class,()->sync.changes(0,false,10,day.plusDays(1),day));
        db.update("update shimo_sync_item set status='pending',checked_at='2026-09-27T15:59:59Z'");
        db.update("update shimo_sync_item set checked_at='2026-09-27T16:00:00Z' where sku=?",ShimoRowMapper.sku(c));
        assertEquals(2L,sync.items(0,true,1,day,day).get("total"));
        assertEquals(1,((List<?>)sync.items(1,true,1,day,day).get("rows")).size());
    }
    @Test void synchronizedProductIsIdenticalForAllAuthorizedRolesWithoutGrantingWrites() throws Exception {
        var row=ShimoRowMapperTest.cells(sku());read(source("业务新人",row));
        row.set(4,IntNode.valueOf(180));row.set(22,JsonNodeFactory.instance.textNode("8%"));read(source("业务新人",row));
        var sku=ShimoRowMapper.sku(row);var expected=products.get(sku);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        for(var role:List.of(new String[]{"SUPER_ADMIN","purchase"},new String[]{"EMPLOYEE","quote"},new String[]{"FINANCE","allRecords"},new String[]{"PURCHASE","purchase"},new String[]{"LOGISTICS","quote"})) {
            var user=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(role[0]).authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role[0]),new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_"+role[1]));
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-products/"+sku).with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            assertEquals(mapper.readTree(mapper.writeValueAsString(expected)),mapper.readTree(response.getContentAsString()).path("data"),role[0]);
            if(!"SUPER_ADMIN".equals(role[0])) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/purchase-shimo-sync/enabled").with(user)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        assertTrue(sync.enabled());
    }
    @Test void missingTaxAndWeightPersistThenZeroTaxCompletesWithoutManualPaste() {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);cells.set(22,NullNode.instance);
        read(source("业务新人",cells));assertFalse(products.exists(sku));assertEquals("pending",state(sku));
        var first=db.queryForObject("select first_seen_at from shimo_sync_item where sku=?",java.sql.Timestamp.class,sku);
        cells.set(22,JsonNodeFactory.instance.textNode("0%"));cells.set(4,NullNode.instance);
        read(source("业务新人",cells));assertFalse(products.exists(sku));assertEquals("pending",state(sku));
        cells.set(4,IntNode.valueOf(130));read(source("业务新人",cells));
        assertTrue(products.exists(sku));assertEquals(0,products.get(sku).path("taxPoint").asDouble());assertEquals("synced",state(sku));
        assertEquals(first,db.queryForObject("select first_seen_at from shimo_sync_item where sku=?",java.sql.Timestamp.class,sku));assertEquals(1,changes());
        var history=products.history(sku,PageRequest.of(0,10)).getContent().getFirst();
        assertEquals("shimo-sync",history.actorAccount());assertEquals("石墨自动同步",history.operation());
    }
    @Test void parityWithPasteSparseUpdateNoopAndHistorySnapshotProtection() {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);var patch=ShimoRowMapper.patch(cells);
        var expected=paste.preview(List.of(patch)).rows().getFirst().effective();
        read(source("业务新人",cells));var first=products.get(sku);
        for(var field:ShimoRowMapper.FIELDS) {
            var a=expected.path(field);var b=first.path(field);
            if(a.isNumber()&&b.isNumber()) assertEquals(0,a.decimalValue().compareTo(b.decimalValue()),field);
            else assertEquals(a,b,field);
        }
        read(source("业务新人",cells));assertEquals(1,changes());assertEquals("synced",state(sku));
        assertEquals(first.path("_version"),products.get(sku).path("_version"));
        var id=UUID.randomUUID();db.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'processed',cast(? as jsonb),3,now(),now(),'active')",id,"SHIMO-"+id.toString().substring(0,8),"BUYER","{\"systemQuote\":50,\"financeReviewStatus\":\"approved\",\"dealStatus\":\"won\"}");
        var snapshot=db.queryForMap("select * from quotation_record where id=?",id);
        cells.set(12,IntNode.valueOf(25));read(source("业务新人",cells));
        assertEquals(25,products.get(sku).path("purchasePriceCny").asInt());assertEquals(2,changes());
        assertEquals(snapshot,db.queryForMap("select * from quotation_record where id=?",id));
    }
    @Test void incompleteExistingRowNeverUsesOldRequiredValuesToPass() {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);read(source("业务新人",cells));
        cells.set(22,NullNode.instance);cells.set(12,IntNode.valueOf(500));read(source("业务新人",cells));
        assertEquals(20,products.get(sku).path("purchasePriceCny").asInt());assertEquals("pending",state(sku));
        cells.set(22,JsonNodeFactory.instance.textNode("8%"));read(source("业务新人",cells));assertEquals(500,products.get(sku).path("purchasePriceCny").asInt());
    }
    @Test void duplicateAndManualChangeAreBlockedAndRemovedSourceDoesNotDelete() {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);
        read(source("业务新人",cells),source("乔月",cells));assertFalse(products.exists(sku));assertEquals("conflict",state(sku));
        read(source("业务新人",cells));assertTrue(products.exists(sku));
        products.update(sku,((ObjectNode)products.get(sku)).put("notes","人工维护"));
        cells.set(12,IntNode.valueOf(77));read(source("业务新人",cells));
        assertEquals("conflict",state(sku));assertEquals(20,products.get(sku).path("purchasePriceCny").asInt());
        read();assertTrue(products.exists(sku));assertEquals("source_missing",state(sku));
    }
    @Test void manualEditBetweenSyncVersionCheckAndPastePreviewIsNeverOverwritten() throws Exception {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);read(source("业务新人",cells));
        cells.set(12,IntNode.valueOf(77));
        when(client.readAll(any(),any())).thenReturn(List.of(source("业务新人",cells)));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var resume=new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation->{
            entered.countDown();
            if(!resume.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout");
            return invocation.callRealMethod();
        }).when(paste).preview(any());
        var task=java.util.concurrent.CompletableFuture.runAsync(sync::runOnce);
        try {
            assertTrue(entered.await(15,java.util.concurrent.TimeUnit.SECONDS));
            products.update(sku,((ObjectNode)products.get(sku)).put("notes","concurrent manual edit"));
        } finally {resume.countDown();}
        task.get(30,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("concurrent manual edit",products.get(sku).path("notes").asText());
        assertEquals(20,products.get(sku).path("purchasePriceCny").asInt());
        assertEquals(1,changes());
    }
    @Test void oldSourceOnlyUpdatesFromExplicitOldUpdateSheetAndKeepsLegacyPriceRule() {
        var sku=sku();var cells=ShimoRowMapperTest.cells(sku);
        products.upsert(ShimoRowMapper.patch(cells).put("dataSource","legacy_2026").put("taxIncludedPriceCny",22));
        cells.set(12,IntNode.valueOf(40));cells.set(21,IntNode.valueOf(44));
        read(source("业务新人",cells));assertEquals("conflict",state(sku));
        read(source("老数据更新",cells));assertEquals("synced",state(sku));
        assertEquals(44,products.get(sku).path("purchasePriceCny").asInt());assertEquals("legacy_2026",products.get(sku).path("dataSource").asText());
    }
    @Test void readFailureAndPauseNeverWrite() {
        assertEquals(600,sync.status().get("intervalSeconds"));
        when(client.readAll(any(),any())).thenThrow(new IllegalStateException("模拟接口失败"));sync.runOnce();
        assertEquals(0,changes());assertEquals("fetch_failed",db.queryForObject("select status from shimo_sync_run",String.class));
        db.update("update shimo_sync_control set enabled=false");reset(client);sync.runOnce();verifyNoInteractions(client);
    }
    @Test void oldUpdateSheetAlwaysWinsAndIncompleteWinnerDoesNotFallBack() {
        var sku=sku();var normal=ShimoRowMapperTest.cells(sku);var latest=normal.deepCopy();latest.set(12,IntNode.valueOf(99));latest.set(22,NullNode.instance);
        read(source("业务新人",normal),source("老数据更新",latest));assertFalse(products.exists(sku));assertEquals("pending",state(sku));
        latest.set(22,IntNode.valueOf(0));read(source("业务新人",normal),source("老数据更新",latest));
        assertEquals(99,products.get(sku).path("purchasePriceCny").asInt());assertEquals(0,products.get(sku).path("taxPoint").asDouble());
        read(source("老数据更新",latest),source("老数据更新",normal));assertEquals("conflict",state(sku));
    }
    @Test void incrementalDefersExistingChangesButAddsNewAndDailyAppliesWholeRow() {
        var sku=sku();var first=ShimoRowMapperTest.cells(sku);read(source("业务新人",first));
        var edited=first.deepCopy();edited.set(4,IntNode.valueOf(140));edited.set(12,IntNode.valueOf(24));
        var added=ShimoRowMapperTest.cells(sku());
        when(client.readAll(any(),any())).thenReturn(List.of(source("业务新人",edited),source("业务新人",added)));
        sync.runOnce("incremental");
        assertEquals("awaiting_daily",state(sku));assertEquals(130,products.get(sku).path("weightG").asInt());assertTrue(products.exists(ShimoRowMapper.sku(added)));
        sync.runOnce("daily");assertEquals(140,products.get(sku).path("weightG").asInt());assertEquals(24,products.get(sku).path("purchasePriceCny").asInt());
    }
    @Test void resumeRetainsPagesAndRowMovementInvalidatesOnlyAffectedSheet() {
        var id=UUID.randomUUID();db.update("insert into shimo_sync_run(id,status) values (?,'fetch_failed')",id);
        var cache=sync.pageCache(id);var index=mapper.createArrayNode().addArray().add("A");
        cache.prepare("业务新人",index);cache.write("业务新人",2,2,mapper.createArrayNode().add("saved"));
        cache.prepare("乔月",index);cache.write("乔月",2,2,mapper.createArrayNode().add("other"));
        var next=sync.pageCache(id);next.prepare("业务新人",index);assertNotNull(next.read("业务新人",2,2));
        next.prepare("业务新人",mapper.createArrayNode().add("B"));assertNull(next.read("业务新人",2,2));assertNotNull(next.read("乔月",2,2));
        when(client.readAll(any(),any())).thenReturn(List.of());sync.runOnce();
        assertEquals(1,db.queryForObject("select count(*) from shimo_sync_run",Integer.class));
        assertEquals("completed",db.queryForObject("select status from shimo_sync_run where id=?",String.class,id));
    }
    @Test void rollbackRequiresPauseAndNeverOverwritesLaterManualChanges() {
        var sku=sku();var row=ShimoRowMapperTest.cells(sku);read(source("业务新人",row));
        row.set(12,IntNode.valueOf(50));read(source("业务新人",row));
        var run=db.queryForObject("select run_id from shimo_sync_change where sku=? order by created_at desc limit 1",UUID.class,sku);
        assertThrows(com.milano.quotation.common.AppException.class,()->sync.rollback(run,true));
        sync.setEnabled(false);assertEquals(1,((List<?>)sync.rollback(run,true).get("eligibleOrReverted")).size(),db.queryForList("select c.after_version,p.version,c.product_id,p.id from shimo_sync_change c join purchase_product p on p.sku=c.sku where c.run_id=?",run).toString());
        sync.rollback(run,false);assertEquals(20,products.get(sku).path("purchasePriceCny").asInt());assertEquals("rolled_back",state(sku));
        var added=sku();var newRow=ShimoRowMapperTest.cells(added);db.update("update shimo_sync_control set enabled=true");read(source("业务新人",newRow));
        assertEquals("rolled_back",state(sku));
        var incomplete=row.deepCopy();incomplete.set(22,NullNode.instance);
        read(source("业务新人",incomplete));assertEquals("rolled_back",state(sku));
        read(source("业务新人",row));assertEquals(20,products.get(sku).path("purchasePriceCny").asInt());
        var addedRun=db.queryForObject("select run_id from shimo_sync_change where sku=?",UUID.class,added);
        products.update(added,((ObjectNode)products.get(added)).put("notes","后续人工维护"));sync.setEnabled(false);
        assertEquals(1,((List<?>)sync.rollback(addedRun,false).get("blockedByLaterChange")).size());
        assertEquals("后续人工维护",products.get(added).path("notes").asText());
    }
}
