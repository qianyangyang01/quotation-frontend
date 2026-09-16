package com.milano.quotation.logistics;

import db.migration.V41__tongyou_sensitive_b_minimum_weight;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class TongyouMinimumWeightRepairTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final ObjectMapper mapper=new ObjectMapper();
    static final String CONFIRMATION=V41__tongyou_sensitive_b_minimum_weight.CONFIRMATION;
    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("40").load().migrate();
    }
    static ObjectNode plan() throws Exception {
        try(var input=TongyouMinimumWeightRepairTest.class.getResourceAsStream("/logistics-repairs/tongyou-sensitive-b-minimum-20260916.json")) {
            return (ObjectNode)mapper.readTree(input);
        }
    }
    @Test void confirmationIsRequiredAndMustMatchTheFloor() throws Exception {
        var plan=plan(); var item=(ObjectNode)plan.path("channels").get(0);
        var payload=syntheticPayload(item);
        assertThrows(SQLException.class,()->new DocumentedMinimumWeightRepair().patch(payload.deepCopy(),item));
        assertThrows(SQLException.class,()->new DocumentedMinimumWeightRepair("test","different confirmation").patch(payload.deepCopy(),item));
        var invalid=item.deepCopy();((ObjectNode)invalid.path("patches").get(0).path("fields")).put("minChargeWeightKg",.1);
        assertThrows(SQLException.class,()->new DocumentedMinimumWeightRepair("test",CONFIRMATION).patch(payload.deepCopy(),invalid));
        new DocumentedMinimumWeightRepair("test",CONFIRMATION).patch(payload,item);
        checkFreight(payload);
    }
    @Test void publishesAndRollsBackGuardedVersionInPostgres() throws Exception {
        try(var db=connection()) { var plan=plan();rehearse(db,plan,syntheticPayload(plan.path("channels").get(0)),false);db.rollback(); }
    }
    @Test @EnabledIfEnvironmentVariable(named="QUOTATION_TONGYOU_SNAPSHOT",matches=".+")
    void rehearsesExactLiveRowsAndFingerprint() throws Exception {
        var snapshot=mapper.readTree(Files.readString(Path.of(System.getenv("QUOTATION_TONGYOU_SNAPSHOT"))));
        try(var db=connection()) {
            rehearse(db,plan(),(ObjectNode)snapshot.path("channel").path("version"),true);db.rollback();
        }
    }
    @Test void freshInstallationSkipsSpecificDataset() throws Exception {
        try(var db=connection()) {assertEquals(0,new DocumentedMinimumWeightRepair("migration-v41",CONFIRMATION).apply(db,plan()));db.rollback();}
    }
    void rehearse(Connection db,ObjectNode plan,ObjectNode payload,boolean exact) throws Exception {
        var item=(ObjectNode)plan.path("channels").get(0);
        var channel=UUID.fromString(item.path("channelId").asText());var old=UUID.fromString(item.path("versionId").asText());
        var provider=UUID.randomUUID();
        execute(db,"update logistics_dataset set status='preparing'");
        execute(db,"insert into logistics_dataset(id,name,status,created_by) values(?,'Tongyou test','active','test')",UUID.fromString(plan.path("datasetId").asText()));
        execute(db,"insert into logistics_provider(id,code,payload,created_at,updated_at) values(?,?,'{}',now(),now())",provider,provider.toString());
        execute(db,"insert into logistics_channel(id,provider_id,code,rule_id,payload,created_at,updated_at) values(?,?,?,569,'{}',now(),now())",channel,provider,channel.toString());
        execute(db,"insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(?,?,2,'published',?,?::jsonb,now(),now())",old,channel,old.toString(),payload.toString());
        execute(db,"update logistics_channel set current_version_id=? where id=?",old,channel);
        execute(db,"insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select ?,id,rows_fingerprint,'logistics-billing-v5','verified','{}','test' from logistics_version where id=?",UUID.randomUUID(),old);
        var fingerprint=scalar(db,"select rows_fingerprint from logistics_version where id=?",old);
        if(exact) assertEquals(item.path("rowsFingerprint").asText(),fingerprint);else item.put("rowsFingerprint",fingerprint);
        assertEquals(1,new DocumentedMinimumWeightRepair("migration-v41",CONFIRMATION).apply(db,plan));
        assertEquals("true",scalar(db,"select logistics_version_quote_ready(current_version_id)::text from logistics_channel where id=?",channel));
        var current=mapper.readTree(scalar(db,"select payload::text from logistics_version where status='published'"));
        assertEquals(3,current.path("versionNumber").asInt());checkFreight(current);
        for(int i=0;i<8;i++) {
            var after=(ObjectNode)current.path("rows").get(i).deepCopy();
            after.remove("sourceMinimumWeightKind");after.remove("sourceMinimumWeightText");after.put("minChargeWeightKg",0);
            assertEquals(payload.path("rows").get(i),after);
        }
        assertEquals(fingerprint,scalar(db,"select rows_fingerprint from logistics_version where id=?",old));
        assertEquals("8",scalar(db,"select detail->>'changedRows' from audit_log where action='logistics.minimum-weight.complete'"));
        assertThrows(SQLException.class,()->new DocumentedMinimumWeightRepair("migration-v41",CONFIRMATION).apply(db,plan));
        var rollback=Files.readString(Path.of("../deploy/scripts/rollback-tongyou-minimum-v41.sql")).replace("BEGIN;", "").replace("COMMIT;", "");
        execute(db,rollback);
        assertEquals(old.toString(),scalar(db,"select current_version_id::text from logistics_channel where id=?",channel));
        assertEquals("true",scalar(db,"select logistics_version_quote_ready(current_version_id)::text from logistics_channel where id=?",channel));
        System.out.println("TONGYOU_REHEARSAL exact="+exact+" rows=8 boundaries=7 newVersion=3 rollback=passed");
    }
    static ObjectNode syntheticPayload(tools.jackson.databind.JsonNode item) {
        var payload=mapper.createObjectNode();var rows=payload.putArray("rows");
        double[] from={0,.101,.201,.451,.701,1.001,2.001,5.001},to={.1,.2,.45,.7,1,2,5,10},rates={91,91,91,97,92,92,84,84};
        for(int i=0;i<8;i++) {
            var row=LogisticsBillingEngineTest.row(from[i],to[i],rates[i]).put("registrationFee",i<5?24:16).put("minChargeWeightKg",0);
            for(var field:new String[]{"rowKey","sourceSheet","sourceRow"})row.set(field,item.path("patches").get(i).path(field));
            rows.add(row);
        }
        LogisticsReadiness.apply(payload);return payload;
    }
    static void checkFreight(tools.jackson.databind.JsonNode payload) {
        var engine=new LogisticsBillingEngine(mapper);
        for(double[] sample:new double[][]{{.001,28.55},{.02,28.55},{.049,28.55},{.05,28.55},{.051,28.64},{.06,29.46},{.1,33.1}}) {
            var result=engine.calculate(payload.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",sample[0]));
            assertEquals(sample[1],result.path("total").asDouble());assertEquals(Math.max(.05,sample[0]),result.path("chargeWeightKg").asDouble());
            assertEquals(sample[0],result.path("actualWeightKg").asDouble());
        }
    }
    Connection connection() throws Exception {var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());db.setAutoCommit(false);return db;}
    static void execute(Connection db,String sql,Object...values)throws SQLException {try(var s=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
    static String scalar(Connection db,String sql,Object...values)throws SQLException {try(var s=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
}
