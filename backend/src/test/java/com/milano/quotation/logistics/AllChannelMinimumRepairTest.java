package com.milano.quotation.logistics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.sql.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class AllChannelMinimumRepairTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final ObjectMapper mapper=new ObjectMapper();
    static final Set<String> FIELDS=Set.of("minChargeWeightKg","sourceMinimumWeightKind","sourceMinimumWeightText","sourceMinimumWeightCell");
    @BeforeAll static void migrate(){Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("41").load().migrate();}
    static ObjectNode plan()throws Exception{
        try(var input=AllChannelMinimumRepairTest.class.getResourceAsStream("/logistics-repairs/all-channel-minimum-20260916.json")){return (ObjectNode)mapper.readTree(input);}
    }
    @Test void everyManifestRuleResolvesToItsDocumentedCountryMinimum()throws Exception{
        var plan=plan();assertEquals(44,plan.path("channels").size());int rows=0;
        for(var c:plan.path("channels"))for(var p:c.path("patches")){
            var result=LogisticsMinimumWeight.fromNotes(p.path("fields").path("sourceMinimumWeightText").asText(),p.path("countryCode").asText());
            assertFalse(result.conflict(),c.path("name")+" "+p.path("countryCode"));
            assertNotNull(result.kg(),c.path("name")+" "+p.path("countryCode"));
            assertEquals(0,result.kg().compareTo(p.path("fields").path("minChargeWeightKg").decimalValue()),c.path("name")+" "+p.path("countryCode"));rows++;
        }
        assertEquals(874,rows);
    }
    @Test void freshInstallationDoesNotCreateBusinessData()throws Exception{
        try(var db=connection()){assertEquals(0,DocumentedMinimumWeightRepair.reviewedSources("test-v42").apply(db,plan()));db.rollback();}
    }
    @Test @EnabledIfEnvironmentVariable(named="QUOTATION_ALL_MINIMUM_AUDIT",matches=".+")
    void exactProductionSnapshotPublishesOnlyReviewedRowsAndRollsBack()throws Exception{
        var snapshot=mapper.readTree(Files.readString(Path.of(System.getenv("QUOTATION_ALL_MINIMUM_AUDIT"),"before.json")).replace("\uFEFF",""));
        var plan=plan();var targets=new HashMap<String,JsonNode>();for(var c:plan.path("channels"))targets.put(c.path("channelId").asText(),c);
        try(var db=connection()){
            execute(db,"update logistics_dataset set status='preparing'");
            execute(db,"insert into logistics_dataset(id,name,status,created_by) values(?,'All-channel snapshot','active','test')",UUID.fromString(plan.path("datasetId").asText()));
            var provider=UUID.randomUUID();execute(db,"insert into logistics_provider(id,code,payload,created_at,updated_at) values(?,?,'{}',now(),now())",provider,provider.toString());
            int ruleId=1000;
            for(var c:snapshot.path("channels")){
                var channel=UUID.fromString(c.path("id").asText());var old=UUID.fromString(c.path("versionId").asText());var payload=c.path("version");
                execute(db,"insert into logistics_channel(id,provider_id,code,rule_id,payload,created_at,updated_at) values(?,?,?,?,'{}',now(),now())",channel,provider,channel.toString(),ruleId++);
                execute(db,"insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(?,?,?,'published',?,?::jsonb,now(),now())",old,channel,payload.path("versionNumber").asInt(),old.toString(),payload.toString());
                execute(db,"update logistics_channel set current_version_id=? where id=?",old,channel);
                execute(db,"insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select ?,id,rows_fingerprint,?,'verified','{}','test' from logistics_version where id=?",UUID.randomUUID(),LogisticsBillingEngine.VERSION,old);
                assertEquals(c.path("rowsFingerprint").asText(),scalar(db,"select rows_fingerprint from logistics_version where id=?",old));
            }
            // Guard failures happen before the first publication, even when a late item drifts.
            var invalid=plan.deepCopy();((ObjectNode)invalid.path("channels").get(43)).put("rowsFingerprint","changed");
            assertThrows(SQLException.class,()->DocumentedMinimumWeightRepair.reviewedSources("test-v42").apply(db,invalid));
            assertEquals("95",scalar(db,"select count(*) from logistics_version"));
            for(var c:snapshot.path("channels"))if(targets.containsKey(c.path("id").asText())){
                var item=targets.get(c.path("id").asText());var payload=(ObjectNode)c.path("version").deepCopy();
                var wrong=(ObjectNode)item.deepCopy();((ObjectNode)wrong.path("patches").get(0)).put("beforeMinimumKg",999);
                assertThrows(SQLException.class,()->DocumentedMinimumWeightRepair.reviewedSources("test-v42").patch(payload.deepCopy(),wrong));
            }
            assertEquals(44,DocumentedMinimumWeightRepair.reviewedSources("migration-v42").apply(db,plan));
            int cases=0;
            for(var c:snapshot.path("channels")){
                var old=UUID.fromString(c.path("versionId").asText());var channel=UUID.fromString(c.path("id").asText());
                assertEquals(c.path("rowsFingerprint").asText(),scalar(db,"select rows_fingerprint from logistics_version where id=?",old));
                var current=mapper.readTree(scalar(db,"select v.payload::text from logistics_channel c join logistics_version v on v.id=c.current_version_id where c.id=?",channel));
                var target=targets.get(c.path("id").asText());
                if(target==null){assertEquals(c.path("version"),current);continue;}
                assertEquals("true",scalar(db,"select logistics_version_quote_ready(current_version_id)::text from logistics_channel where id=?",channel));
                var patches=new HashMap<Integer,JsonNode>();for(var p:target.path("patches"))patches.put(p.path("index").asInt(),p);
                for(int i=0;i<current.path("rows").size();i++){
                    var before=c.path("version").path("rows").get(i);var after=current.path("rows").get(i);var patch=patches.get(i);
                    if(patch==null){assertEquals(before,after);continue;}
                    var normalized=(ObjectNode)after.deepCopy();for(var field:FIELDS){if(before.has(field))normalized.set(field,before.path(field));else normalized.remove(field);}assertEquals(before,normalized);
                    assertEquals(patch.path("fields").path("minChargeWeightKg").decimalValue(),after.path("minChargeWeightKg").decimalValue());
                    if(LogisticsBillingEngine.n(after,"weightFromKg").compareTo(new BigDecimal("0.001"))<=0){
                        var floor=LogisticsBillingEngine.minimum(after);var engine=new LogisticsBillingEngine(mapper);
                        for(var weight:List.of(new BigDecimal("0.001"),floor.divide(BigDecimal.valueOf(2)),floor.subtract(new BigDecimal("0.001")).max(new BigDecimal("0.001")),floor,floor.add(new BigDecimal("0.001")))){
                            if(!LogisticsBillingEngine.includes(after,weight.max(floor)))continue;
                            var zone=after.path("zoneName").asText().split("[/／、,，;；|]",2)[0];
                            var actual=engine.calculate(current.path("rows"),mapper.createObjectNode().put("country",after.path("countryCode").asText()).put("zoneName",zone).put("weightKg",weight));
                            var expected=weight.max(floor).multiply(LogisticsBillingEngine.n(after,"pricePerKg")).add(LogisticsBillingEngine.n(after,"registrationFee")).setScale(2,RoundingMode.HALF_UP);
                            assertEquals(0,expected.compareTo(actual.path("total").decimalValue()));cases++;
                        }
                    }
                }
            }
            assertEquals("874",scalar(db,"select sum((detail->>'changedRows')::int) from audit_log where detail->>'repairId'='all-channel-minimum-20260916'"));
            var rollback=Files.readString(Path.of("../deploy/scripts/rollback-all-channel-minimum-v42.sql")).replace("BEGIN;","").replace("COMMIT;","");execute(db,rollback);
            for(var c:snapshot.path("channels"))assertEquals(c.path("versionId").asText(),scalar(db,"select current_version_id::text from logistics_channel where id=?",UUID.fromString(c.path("id").asText())));
            System.out.println("ALL_CHANNEL_REHEARSAL channels=95 corrected=44 rows=874 boundaryCases="+cases+" history=preserved rollback=passed");db.rollback();
        }
    }
    static Connection connection()throws Exception{var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());db.setAutoCommit(false);return db;}
    static void execute(Connection db,String sql,Object...values)throws SQLException{try(var s=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
    static String scalar(Connection db,String sql,Object...values)throws SQLException{try(var s=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
}
