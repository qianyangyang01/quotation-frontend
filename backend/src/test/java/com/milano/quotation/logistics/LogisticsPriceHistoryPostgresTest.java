package com.milano.quotation.logistics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class LogisticsPriceHistoryPostgresTest {
 @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine").withDatabaseName("quotation_test").withUsername("test").withPassword("test");
 static javax.sql.DataSource dataSource;static JdbcClient jdbc;static LogisticsPriceHistoryService service;static TransactionTemplate tx;
 static final JsonMapper mapper=JsonMapper.builder().build();
 UUID channel,old;
 @BeforeAll static void setup(){
  var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
  dataSource=ds;
  Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
  jdbc=JdbcClient.create(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  service=new LogisticsPriceHistoryService(dataSource,mapper,new LogisticsPriceHistoryCalculator(mapper,new LogisticsBillingEngine(mapper)));
 }
 @BeforeEach void seed(){
  var provider=UUID.randomUUID();channel=UUID.randomUUID();
  jdbc.sql("insert into logistics_provider(id,code,payload,version,created_at,updated_at) values(:id,:code,'{\"name\":\"测试商\",\"enabled\":true}',0,now(),now())").param("id",provider).param("code",provider.toString()).update();
  jdbc.sql("insert into logistics_channel(id,provider_id,code,rule_id,payload,version,created_at,updated_at) values(:id,:p,:code,:rule,'{\"name\":\"测试渠道\",\"enabled\":true,\"logisticsAttribute\":\"普货\"}',0,now(),now())")
   .param("id",channel).param("p",provider).param("code",channel.toString()).param("rule",Math.abs(channel.hashCode())).update();
  old=version(1,20,"published");activate(old);service.prepare(old);
 }
 UUID version(int number,int rate,String status){
  var id=UUID.randomUUID();var payload=mapper.createObjectNode().put("fileName","原表"+number);
  payload.putArray("rows").addObject().put("countryCode","DE").put("areaName","德国").put("weightFromKg",0).put("weightToKg",30).put("pricePerKg",rate).put("registrationFee",5).put("pricingModel","per-kg");
  jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:c,:n,:s,:hash,cast(:p as jsonb),now(),case when :s='draft' then null else now() end)")
   .param("id",id).param("c",channel).param("n",number).param("s",status).param("hash",id.toString()).param("p",payload.toString()).update();return id;
 }
 void activate(UUID id){
  jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",id).param("c",channel).update();
  jdbc.sql("insert into logistics_billing_acceptance select gen_random_uuid(),id,rows_fingerprint,'legacy','legacy','{}','QA',now() from logistics_version where id=:id").param("id",id).update();
 }
 String source(UUID id){return jdbc.sql("select payload::text from logistics_version where id=:id").param("id",id).query(String.class).single();}
 @Test void publishesNewDerivedPricesWithoutRewritingHistoryAndCachesUntilSourceChanges(){
  var before=source(old);var oldCalculation=jdbc.sql("select calculated_at::text from logistics_price_history_projection where version_id=:id").param("id",old).query(String.class).single();
  var draft=version(2,30,"draft");service.prepare(draft);
  assertEquals(0,jdbc.sql("select count(*) from logistics_price_history_projection where version_id=:id").param("id",draft).query(Integer.class).single());
  tx.executeWithoutResult(status->{
   jdbc.sql("update logistics_version set status='superseded' where id=:id").param("id",old).update();
   jdbc.sql("update logistics_version set status='published',published_at=now() where id=:id").param("id",draft).update();activate(draft);
  });
  service.rebuildMissing();
  var snapshot=service.snapshot();assertTrue(snapshot.path("channels").findValues("current").stream().anyMatch(n->draft.toString().equals(n.asText())));
  var projected=snapshot.path("versions").findValues("id");assertTrue(projected.stream().anyMatch(n->old.toString().equals(n.asText())));
  assertEquals(35,jdbc.sql("select (prices->'DE|1'->'全国统一'->>'price')::numeric from logistics_price_history_projection where version_id=:id").param("id",draft).query(Double.class).single());
  assertEquals(before,source(old));service.snapshot();
  assertEquals(oldCalculation,jdbc.sql("select calculated_at::text from logistics_price_history_projection where version_id=:id").param("id",old).query(String.class).single());
 }
 @Test void failedPublicationRollsBackSourceAndDerivedPricesTogether(){
  UUID[] created=new UUID[1];
  tx.executeWithoutResult(status->{created[0]=version(2,90,"published");activate(created[0]);status.setRollbackOnly();});
  assertEquals(0,jdbc.sql("select count(*) from logistics_price_history_projection where version_id=:id").param("id",created[0]).query(Integer.class).single());
  assertEquals(old,jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
 }
 @Test void eligibilityRevisionDetectsAcceptanceRemovalWithoutWaitingForATimer(){
  var first=service.snapshot();
  assertTrue(java.util.stream.StreamSupport.stream(first.path("channels").spliterator(),false).filter(c->channel.toString().equals(c.path("id").asText())).findFirst().orElseThrow().path("quoteReady").asBoolean());
  jdbc.sql("delete from logistics_billing_acceptance where version_id=:id").param("id",old).update();
  var second=service.snapshot();assertNotEquals(first.path("sourceRevision"),second.path("sourceRevision"));
  assertFalse(java.util.stream.StreamSupport.stream(second.path("channels").spliterator(),false).filter(c->channel.toString().equals(c.path("id").asText())).findFirst().orElseThrow().path("quoteReady").asBoolean());
 }
 @Test void backgroundDisplayStartsOnlyAfterCommitAndNeverAfterRollback() throws Exception {
  var worker=new LogisticsPriceHistoryWorker(service);var before=source(old);UUID[] next=new UUID[1];
  try{
   tx.executeWithoutResult(status->{
    next[0]=version(2,40,"published");activate(next[0]);worker.afterPublication();
    assertEquals(0,jdbc.sql("select count(*) from logistics_price_history_projection where version_id=:id").param("id",next[0]).query(Integer.class).single());
   });
   worker.refresh(false).get(10,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals(45,jdbc.sql("select (prices->'DE|1'->'全国统一'->>'price')::numeric from logistics_price_history_projection where version_id=:id").param("id",next[0]).query(Double.class).single());
   var display=org.mockito.Mockito.mock(LogisticsPriceHistoryService.class);var rollbackWorker=new LogisticsPriceHistoryWorker(display);
   try{
    tx.executeWithoutResult(status->{version(3,80,"published");rollbackWorker.afterPublication();status.setRollbackOnly();});
    org.mockito.Mockito.verifyNoInteractions(display);
   }finally{rollbackWorker.close();}
   assertEquals(before,source(old));
  }finally{worker.close();}
 }
 @Test void brokenDisplayTableCannotRollbackPublishedPriceOrDisableBilling() throws Exception {
  jdbc.sql("create function fail_display_test() returns trigger language plpgsql as $$ begin raise exception 'injected display storage failure'; end $$").update();
  jdbc.sql("create trigger fail_display_test before insert or update on logistics_price_history_projection for each row execute function fail_display_test()").update();
  var before=source(old);var worker=new LogisticsPriceHistoryWorker(service);UUID[] next=new UUID[1];
  try{
   assertDoesNotThrow(()->tx.executeWithoutResult(status->{next[0]=version(2,60,"published");activate(next[0]);worker.afterPublication();}));
   worker.refresh(false).get(10,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals(next[0],jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
   assertTrue(jdbc.sql("select logistics_version_quote_ready(:id)").param("id",next[0]).query(Boolean.class).single());
   assertEquals(65,new LogisticsBillingEngine(mapper).calculate(mapper.readTree(source(next[0])).path("rows"),mapper.createObjectNode().put("country","DE").put("weightKg",1)).path("total").asDouble());
   assertEquals(before,source(old));
   assertThrows(com.milano.quotation.common.AppException.class,service::snapshot);
  }finally{
   worker.close();jdbc.sql("drop trigger fail_display_test on logistics_price_history_projection").update();jdbc.sql("drop function fail_display_test()").update();service.rebuildMissing();
  }
 }
 @Test void lockedDisplayTableTimesOutButOrdinaryPriceReadsStillWork() throws Exception {
  try(var connection=dataSource.getConnection()){
   connection.setAutoCommit(false);
   try{
    connection.createStatement().execute("lock table logistics_price_history_projection in access exclusive mode");
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(6),()->assertThrows(org.springframework.dao.DataAccessException.class,service::snapshot));
    assertTrue(jdbc.sql("select logistics_version_quote_ready(:id)").param("id",old).query(Boolean.class).single());
    assertFalse(source(old).isBlank());
   }finally{connection.rollback();}
  }
  assertDoesNotThrow(service::snapshot);
 }
 @Test void oneCalculationFailureDoesNotPreventOtherVersionsAndNeverRewritesSource(){
  var bad=version(2,70,"published");var good=version(3,80,"published");var before=source(bad);
  var calculator=org.mockito.Mockito.spy(new LogisticsPriceHistoryCalculator(mapper,new LogisticsBillingEngine(mapper)));
  org.mockito.Mockito.doAnswer(call->{var rows=(tools.jackson.databind.JsonNode)call.getArgument(0);if(rows.get(0).path("pricePerKg").asInt()==70)throw new IllegalArgumentException("damaged display row");return call.callRealMethod();}).when(calculator).calculate(org.mockito.ArgumentMatchers.any());
  var isolated=new LogisticsPriceHistoryService(dataSource,mapper,calculator);
  try{
   assertDoesNotThrow(isolated::rebuildMissing);
   assertEquals(1,jdbc.sql("select count(*) from logistics_price_history_projection where version_id=:id").param("id",good).query(Integer.class).single());
   assertThrows(com.milano.quotation.common.AppException.class,isolated::snapshot);
   assertEquals(before,source(bad));
  }finally{service.rebuildMissing();}
 }
 @Test void optionalRealHistoryReadPerformanceFixture() throws Exception {
  var file=System.getProperty("priceHistoryFixture","");if(file.isEmpty())return;
  var source=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(file)));
  var providers=new java.util.HashMap<String,UUID>();
  tx.executeWithoutResult(status->{
   for(var c:source.path("channels")){
    var name=c.path("provider").asText();
    if(!providers.containsKey(name)){
     var id=UUID.randomUUID();providers.put(name,id);
     jdbc.sql("insert into logistics_provider(id,code,payload,version,created_at,updated_at) values(:id,:code,cast(:p as jsonb),0,now(),now())")
      .param("id",id).param("code",id.toString()).param("p",mapper.createObjectNode().put("name",name).put("enabled",true).toString()).update();
    }
    jdbc.sql("insert into logistics_channel(id,provider_id,code,rule_id,payload,version,created_at,updated_at) values(:id,:p,:code,:rule,cast(:body as jsonb),0,now(),now())")
     .param("id",UUID.fromString(c.path("id").asText())).param("p",providers.get(name)).param("code",c.path("code").asText()).param("rule",c.path("ruleId").asInt())
     .param("body",mapper.createObjectNode().put("name",c.path("name").asText()).put("enabled",c.path("enabled").asBoolean()).put("logisticsAttribute",c.path("attribute").asText()).toString()).update();
   }
   for(var v:source.path("versions")){
    var payload=mapper.createObjectNode().put("fileName",v.path("fileName").asText());payload.set("rows",v.path("rows"));
    jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:c,:n,:s,:hash,cast(:p as jsonb),now(),:published)")
     .param("id",UUID.fromString(v.path("id").asText())).param("c",UUID.fromString(v.path("channel").asText())).param("n",v.path("number").asInt()).param("s",v.path("status").asText())
     .param("hash",v.path("id").asText()).param("p",payload.toString()).param("published",java.time.OffsetDateTime.parse(v.path("publishedAt").asText())).update();
   }
   for(var c:source.path("channels")){
    if(c.path("current").asText("").isEmpty())continue;
    var id=UUID.fromString(c.path("current").asText());
    jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",id).param("c",UUID.fromString(c.path("id").asText())).update();
    if(c.path("quoteReady").asBoolean())jdbc.sql("insert into logistics_billing_acceptance select gen_random_uuid(),id,rows_fingerprint,'legacy','legacy','{}','QA',now() from logistics_version where id=:id").param("id",id).update();
   }
  });
  long start=System.nanoTime();service.rebuildMissing();service.snapshot();long cold=(System.nanoTime()-start)/1000000;
  var durations=new java.util.ArrayList<Long>();int bytes=0;
  for(int i=0;i<3;i++){start=System.nanoTime();var snapshot=service.snapshot();durations.add((System.nanoTime()-start)/1000000);bytes=snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
  var report=mapper.createObjectNode().put("versions",source.path("versions").size()).put("initialBackfillMs",cold).put("responseBytes",bytes);report.set("cachedReadMs",mapper.valueToTree(durations));
  java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("priceHistoryPerformance")),report.toString());
  System.out.println("PRICE_HISTORY_PERFORMANCE="+report);
 }
}
