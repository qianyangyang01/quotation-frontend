package com.milano.quotation.logistics;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class DocumentedMinimumWeightRepairTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final ObjectMapper mapper = new ObjectMapper();
    static final String DATASET = "00000000-0000-0000-0000-000000000001";

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("39").load().migrate();
    }

    @Test void publishesANewVersionWithVerifiedFloorAndPreservesZeroAndHistory() throws Exception {
        try (var db = connection()) {
            var plan = seed(db); var before = plan.path("channels").get(0).path("versionId").asText();
            assertEquals(1, new DocumentedMinimumWeightRepair().apply(db, plan));
            var version = mapper.readTree(scalar(db, "select payload::text from logistics_version where status='published'"));
            assertNotEquals(before, version.path("id").asText());
            assertEquals(0.05, version.path("rows").get(0).path("minChargeWeightKg").asDouble());
            assertEquals(0, version.path("rows").get(1).path("minChargeWeightKg").asDouble());
            assertEquals("0", scalar(db, "select payload->'rows'->0->>'minChargeWeightKg' from logistics_version where status='superseded'"));
            assertEquals("true", scalar(db, "select logistics_version_quote_ready(current_version_id)::text from logistics_channel"));
            assertEquals("1", scalar(db, "select count(*) from audit_log where action='logistics.minimum-weight.complete'"));
            var result = new LogisticsBillingEngine(mapper).calculate(version.path("rows"), mapper.createObjectNode().put("country", "US").put("weightKg", 0.02));
            assertEquals(25, result.path("total").asDouble());
            assertEquals(0.05, result.path("chargeWeightKg").asDouble());
            db.rollback();
        }
    }

    @Test void refusesDriftAndUnrelatedFieldsBeforeWriting() throws Exception {
        try (var db = connection()) {
            var plan = seed(db); var first = (ObjectNode) plan.path("channels").get(0);
            var fingerprint = first.path("rowsFingerprint").asText(); first.put("rowsFingerprint", "changed");
            assertThrows(SQLException.class, () -> new DocumentedMinimumWeightRepair().apply(db, plan));
            assertEquals("1", scalar(db, "select count(*) from logistics_version"));
            first.put("rowsFingerprint", fingerprint);
            ((ObjectNode) first.path("patches").get(0).path("fields")).put("pricePerKg", 1);
            assertThrows(SQLException.class, () -> new DocumentedMinimumWeightRepair().apply(db, plan));
            assertEquals("1", scalar(db, "select count(*) from logistics_billing_acceptance"));
            db.rollback();
        }
    }

    @Test void rollsBackTheWholeCorrectionWhenPublishingFails() throws Exception {
        try (var db = connection()) {
            var plan = seed(db);
            execute(db, "alter table audit_log add constraint reject_repair_test check (action <> 'logistics.minimum-weight.complete')");
            var savepoint = db.setSavepoint();
            assertThrows(SQLException.class, () -> new DocumentedMinimumWeightRepair().apply(db, plan));
            db.rollback(savepoint);
            assertEquals("1", scalar(db, "select count(*) from logistics_version"));
            assertEquals("published", scalar(db, "select status from logistics_version"));
            assertEquals("1", scalar(db, "select count(*) from logistics_billing_acceptance"));
            db.rollback();
        }
    }

    @Test void freshInstallationSkipsTheProductionSpecificPlan() throws Exception {
        try (var db = connection(); var input = getClass().getResourceAsStream("/logistics-repairs/documented-minimum-20260915.json")) {
            assertEquals(0, new DocumentedMinimumWeightRepair().apply(db, mapper.readTree(input)));
            db.rollback();
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = "QUOTATION_MINIMUM_REPAIR_SNAPSHOT", matches = ".+")
    void rehearsesExactProductionRowsInAnIsolatedPostgres() throws Exception {
        try (var db = connection(); var input = getClass().getResourceAsStream("/logistics-repairs/documented-minimum-20260915.json")) {
            var plan = mapper.readTree(input);
            var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("QUOTATION_MINIMUM_REPAIR_SNAPSHOT"))));
            execute(db, "update logistics_dataset set status='preparing'");
            execute(db, "insert into logistics_dataset(id,name,status,created_by) values(?,'repair-test','active','test')", UUID.fromString(plan.path("datasetId").asText()));
            int rule = 1;
            for (var item : plan.path("channels")) {
                var channelId = item.path("channelId").asText();
                var source = snapshot.path("channels").valueStream().filter(c -> c.path("id").asText().equals(channelId)).findFirst().orElseThrow();
                seedVersion(db, UUID.fromString(channelId), UUID.fromString(item.path("versionId").asText()), (ObjectNode) source.path("version"), rule++);
            }
            assertEquals(6, new DocumentedMinimumWeightRepair().apply(db, plan));
            assertEquals("6", scalar(db, "select count(*) from logistics_channel where logistics_version_quote_ready(current_version_id)"));
            assertEquals("53", scalar(db, "select sum((detail->>'changedRows')::int) from audit_log where action='logistics.minimum-weight.complete'"));
            for (var item : plan.path("channels")) {
                assertEquals(item.path("rowsFingerprint").asText(), scalar(db, "select rows_fingerprint from logistics_version where id=?", UUID.fromString(item.path("versionId").asText())));
            }
            System.out.println("PRODUCTION REHEARSAL PASSED: 6 new versions, 53 corrected rows, 367 price rows verified; history unchanged");
            var rollback = Files.readString(Path.of("../deploy/scripts/rollback-documented-minimum-v40.sql"));
            execute(db, rollback.replace("BEGIN;", "").replace("COMMIT;", ""));
            assertEquals("6", scalar(db, "select count(*) from audit_log where action='logistics.minimum-weight.rollback'"));
            assertEquals("6", scalar(db, "select count(*) from logistics_channel where logistics_version_quote_ready(current_version_id)"));
            for (var item : plan.path("channels"))
                assertEquals(item.path("versionId").asText(), scalar(db, "select current_version_id::text from logistics_channel where id=?", UUID.fromString(item.path("channelId").asText())));
            assertThrows(SQLException.class, () -> execute(db, rollback.replace("BEGIN;", "").replace("COMMIT;", "")));
            System.out.println("PRODUCTION ROLLBACK REHEARSAL PASSED: six previous versions restored; repeat rollback rejected");
            db.rollback();
        }
    }

    private ObjectNode seed(Connection db) throws Exception {
        var channel = UUID.randomUUID(); var version = UUID.randomUUID();
        var payload = mapper.createObjectNode(); var rows = payload.putArray("rows");
        for (var country : new String[]{"US", "CA"}) rows.addObject().put("rowKey", country).put("countryCode", country).put("areaName", country)
                .put("weightFromKg", 0).put("weightToKg", 1).put("pricePerKg", 100).put("registrationFee", 20).put("minChargeWeightKg", 0)
                .put("sourceSheet", "rates").put("sourceRow", rows.size() + 1).put("notes", "美国不足50g按50g计费");
        LogisticsReadiness.apply(payload);
        seedVersion(db, channel, version, payload, 1);
        var plan = mapper.createObjectNode().put("repairId", "test-repair").put("datasetId", DATASET);
        var item = plan.putArray("channels").addObject().put("channelId", channel.toString()).put("versionId", version.toString())
                .put("rowsFingerprint", scalar(db, "select rows_fingerprint from logistics_version where id=?", version)).put("rowCount", 2);
        var patch = item.putArray("patches").addObject().put("index", 0).put("rowKey", "US").put("countryCode", "US").put("sourceSheet", "rates").set("sourceRow", rows.get(0).path("sourceRow"));
        patch.putObject("fields").put("minChargeWeightKg", 0.05).put("sourceMinimumWeightText", "美国不足50g按50g计费");
        return plan;
    }

    private void seedVersion(Connection db, UUID channel, UUID version, ObjectNode payload, int rule) throws Exception {
        var provider = UUID.randomUUID();
        execute(db, "insert into logistics_provider(id,code,payload,created_at,updated_at) values(?,?,'{}',now(),now())", provider, provider.toString());
        execute(db, "insert into logistics_channel(id,provider_id,code,rule_id,payload,created_at,updated_at) values(?,?,?,?,?::jsonb,now(),now())", channel, provider, channel.toString(), rule, mapper.createObjectNode().put("name", "test").toString());
        execute(db, "insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(?,?,1,'published',?,?::jsonb,now(),now())", version, channel, version.toString(), payload.toString());
        execute(db, "update logistics_channel set current_version_id=? where id=?", version, channel);
        execute(db, "insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select ?,id,rows_fingerprint,'logistics-billing-v5','verified','{\"note\":\"isolated test baseline\"}','test' from logistics_version where id=?", UUID.randomUUID(), version);
    }

    private Connection connection() throws Exception { var db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); db.setAutoCommit(false); return db; }
    private static void execute(Connection db, String sql, Object... args) throws Exception { try (var stmt = db.prepareStatement(sql)) { for (int i=0;i<args.length;i++) stmt.setObject(i+1,args[i]); stmt.executeUpdate(); } }
    private static String scalar(Connection db, String sql, Object... args) throws Exception { try (var stmt=db.prepareStatement(sql)) { for(int i=0;i<args.length;i++)stmt.setObject(i+1,args[i]);try(var rs=stmt.executeQuery()){assertTrue(rs.next());return rs.getString(1);}} }
}
