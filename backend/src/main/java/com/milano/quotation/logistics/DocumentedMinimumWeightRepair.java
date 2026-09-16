package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** One-time, source-backed correction. Caller owns the transaction; no HTTP entry point. */
public final class DocumentedMinimumWeightRepair {
    private final String actor;
    private final String confirmedNote;
    private boolean reviewedSources;
    public static DocumentedMinimumWeightRepair reviewedSources(String actor) {
        var repair=new DocumentedMinimumWeightRepair(actor,null);repair.reviewedSources=true;return repair;
    }
    public DocumentedMinimumWeightRepair() { this("migration-v40", null); }
    /** Explicit business confirmation for a guarded, one-time migration whose stored notes are missing. */
    public DocumentedMinimumWeightRepair(String actor, String confirmedNote) {
        this.actor = Objects.requireNonNull(actor);
        this.confirmedNote = confirmedNote;
    }
    private static final Set<String> FIELDS = Set.of("minChargeWeightKg", "sourceMinimumWeightKind", "sourceMinimumWeightText", "sourceMinimumWeightCell");
    private final ObjectMapper mapper = new ObjectMapper();
    private final LogisticsBillingEngine engine = new LogisticsBillingEngine(mapper);

    public int apply(Connection db, JsonNode plan) throws SQLException {
        if (db.getAutoCommit()) throw new SQLException("Minimum repair requires a transaction");
        var dataset = UUID.fromString(plan.path("datasetId").asText());
        if (!scalar(db, "select exists(select 1 from logistics_dataset where id=?)", dataset).equals("t")) return 0;
        require(scalar(db, "select logistics_active_dataset()::text").equals(dataset.toString()), "Repair dataset is no longer active");
        require(scalar(db, "select paused::text from logistics_company_state where singleton=true for share").equals("false"), "Logistics rebuild is active");
        require(scalar(db, "select status from logistics_dataset where id=? for share", dataset).equals("active"), "Repair dataset changed");
        require(scalar(db, "select count(*) from logistics_import_batch where status in ('queued','processing')").equals("0"), "Logistics import is active");
        var prepared = new ArrayList<ObjectNode>();
        // Lock and validate every baseline before changing any version.
        for (var item : plan.path("channels").valueStream().sorted(Comparator.comparing(value -> value.path("channelId").asText())).toList()) {
            var channel = UUID.fromString(item.path("channelId").asText());
            try (var statement = db.prepareStatement("""
                    select v.id,v.rows_fingerprint,v.payload::text
                    from logistics_channel c join logistics_version v on v.id=c.current_version_id
                    where c.id=? and c.dataset_id=? and c.archived_at is null and v.status='published'
                    for update of c,v
                    """)) {
                statement.setObject(1, channel); statement.setObject(2, dataset);
                try (var result = statement.executeQuery()) {
                    require(result.next(), "Missing published repair channel: " + channel);
                    require(result.getString(1).equals(item.path("versionId").asText()), "Published version changed: " + channel);
                    require(result.getString(2).equals(item.path("rowsFingerprint").asText()), "Published prices changed: " + channel);
                    require(scalar(db, "select count(*) from logistics_version where channel_id=? and status='draft'", channel).equals("0"), "Existing draft: " + channel);
                    var payload = (ObjectNode) mapper.readTree(result.getString(3));
                    var before = (ArrayNode) payload.path("rows").deepCopy();
                    patch(payload, item);
                    LogisticsReadiness.apply(payload);
                    require(payload.path("errors").asInt() == 0 && payload.path("pricingReady").asBoolean()
                            && payload.path("blockingReasons").isEmpty(), "Correction is not publication ready");
                    var evidence = verify((ArrayNode) payload.path("rows"), before);
                    var comparison = new LogisticsWorkbookService(mapper).compare((ArrayNode) payload.path("rows"), before);
                    for (var field : List.of("added", "removed", "range", "coverageReduced"))
                        require(comparison.path("summary").path(field).asInt() == 0, "Unexpected change: " + field);
                    long reviewedRisks=reviewedSources?item.path("patches").valueStream().filter(p->p.has("sourceEvidence")&&p.path("beforeMinimumKg").asDouble()>0
                            &&Math.abs(p.path("fields").path("minChargeWeightKg").asDouble()/p.path("beforeMinimumKg").asDouble()-1)>.1).count():0;
                    require(comparison.path("summary").path("highRisk").asInt()==reviewedRisks,"Unexpected high-risk changes outside reviewed minimum corrections");
                    payload.set("summary", comparison.path("summary")); payload.set("diffRows", comparison.path("diffRows"));
                    prepared.add(mapper.createObjectNode().set("plan", item).set("payload", payload).set("evidence", evidence));
                }
            }
        }
        for (var item : prepared) publish(db, plan.path("repairId").asText(), item);
        return prepared.size();
    }

    void patch(ObjectNode payload, JsonNode item) throws SQLException {
        var rows = (ArrayNode) payload.path("rows");
        require(rows.size() == item.path("rowCount").asInt(), "Price row count changed");
        var indices = new HashSet<Integer>();
        for (var patch : item.path("patches")) {
            int index = patch.path("index").asInt(-1);
            require(index >= 0 && index < rows.size() && indices.add(index), "Invalid or duplicate repair row");
            var row = (ObjectNode) rows.get(index);
            for (var key : List.of("rowKey", "countryCode", "sourceSheet", "sourceRow"))
                require(row.path(key).equals(patch.path(key)), "Source row changed: " + key);
            if(reviewedSources) {
                require(patch.path("beforeMinimumKg").isNumber()&&LogisticsBillingEngine.minimum(row).compareTo(patch.path("beforeMinimumKg").decimalValue())==0,"Reviewed baseline minimum changed");
                require(!Set.of("column","column-inherited").contains(row.path("sourceMinimumWeightKind").asText()),"Explicit country minimum column must be preserved");
            } else require(LogisticsBillingEngine.minimum(row).signum() == 0, "Explicit existing minimum must be preserved");
            var fields = patch.path("fields");
            require(FIELDS.containsAll(fields.propertyNames()), "Repair attempted an unrelated field");
            var minimum = fields.path("minChargeWeightKg");
            require(minimum.isNumber() && minimum.decimalValue().signum() > 0, "Repair requires a documented positive minimum");
            var text = fields.path("sourceMinimumWeightText").asText();
            var notes = row.path("notes").asText() + row.path("sourceNotes").asText() + payload.path("sourceNotes").asText();
            boolean confirmed = confirmedNote != null && confirmedNote.equals(text)
                    && fields.path("sourceMinimumWeightKind").asText().equals("user-confirmed");
            boolean reviewed=false;
            if(reviewedSources) {
                var resolution=LogisticsMinimumWeight.fromNotes(text,row.path("countryCode").asText());
                require(!resolution.conflict()&&resolution.kg()!=null&&resolution.kg().compareTo(minimum.decimalValue())==0,"Reviewed evidence does not resolve to minimum");
                if(patch.has("sourceEvidence")) {
                    var source=patch.path("sourceEvidence");
                    require(source.path("file").asText().equals(row.path("sourceFile").asText()),"Reviewed source file differs");
                    require(source.path("sheet").asText().equals(row.path("sourceSheet").asText()),"Reviewed source sheet differs");
                    require(source.path("sha256").asText().matches("[a-f0-9]{64}")&&!source.path("cell").asText().isBlank(),"Missing reviewed source hash/cell");
                    require(source.path("countries").valueStream().anyMatch(c->c.asText().equals(row.path("countryCode").asText())),"Source rule does not cover country");
                    require(fields.path("sourceMinimumWeightKind").asText().equals("reviewed-source")&&fields.path("sourceMinimumWeightCell").equals(source.path("cell")),"Reviewed provenance differs");
                    reviewed=true;
                }
            }
            if (confirmed) {
                var resolution = LogisticsMinimumWeight.fromNotes(text, row.path("countryCode").asText());
                require(!resolution.conflict() && resolution.kg() != null
                        && resolution.kg().compareTo(minimum.decimalValue()) == 0, "Confirmation does not match the minimum");
            }
            require(confirmed || reviewed || !text.isBlank() && notes.contains(text), "Minimum evidence is absent from original notes or explicit confirmation");
            fields.properties().forEach(field -> row.set(field.getKey(), field.getValue()));
        }
    }

    ArrayNode verify(ArrayNode rows, ArrayNode before) throws SQLException {
        require(rows.size() == before.size() && engine.unsupported(rows).isEmpty(), "Unsupported correction");
        var evidence = mapper.createArrayNode();
        var covered = new HashMap<String, Set<String>>();
        BigDecimal maximum = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); var old = before.get(i);
            var keys = new HashSet<>(row.propertyNames()); keys.addAll(old.propertyNames());
            for (var key : keys) if (!FIELDS.contains(key)) require(row.path(key).equals(old.path(key)), "Unrelated field changed: " + key);
            var from = LogisticsBillingEngine.n(row, "weightFromKg");
            var to = LogisticsBillingEngine.n(row, "weightToKg"); maximum = maximum.max(to);
            for (int part : new int[]{1, 2}) {
                var weight = from.add(to.subtract(from).multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(3), 9, RoundingMode.HALF_UP));
                var result = checkSample(rows, row, weight, evidence);
                covered.computeIfAbsent(LogisticsBillingEngine.acceptanceTierKey(rows.get(result.path("rowIndex").asInt())), k -> new HashSet<>()).add(weight.toPlainString());
            }
            var floor = LogisticsBillingEngine.minimum(row);
            if (floor.compareTo(LogisticsBillingEngine.minimum(old)) > 0 && LogisticsBillingEngine.includes(row, floor))
                checkSample(rows, row, floor.divide(BigDecimal.valueOf(2)), evidence);
        }
        for (var row : rows) if (LogisticsBillingEngine.available(row))
            require(covered.getOrDefault(LogisticsBillingEngine.acceptanceTierKey(row), Set.of()).size() >= 2, "Price tier lacks two distinct verification weights");
        var rejected = input(rows.get(0), maximum.add(BigDecimal.ONE));
        boolean didReject = false;
        try { engine.calculate(rows, rejected); } catch (AppException expected) { didReject = true; }
        require(didReject, "Weight beyond maximum unexpectedly quoted");
        evidence.addObject().set("sample", mapper.createObjectNode().set("input", rejected).put("expectRejected", true).put("sourceReference", "Original table maximum weight + 1kg"));
        return evidence;
    }

    private ObjectNode checkSample(ArrayNode rows, JsonNode row, BigDecimal weight, ArrayNode evidence) throws SQLException {
        var input = input(row, weight);
        var expected = weight.max(LogisticsBillingEngine.minimum(row)).multiply(LogisticsBillingEngine.n(row, "pricePerKg"))
                .add(LogisticsBillingEngine.n(row, "registrationFee")).setScale(2, RoundingMode.HALF_UP);
        var actual = engine.calculate(rows, input);
        require(actual.path("total").decimalValue().compareTo(expected) == 0, "Independent freight calculation differs");
        var source = row.path("sourceFile").asText() + " / " + row.path("sourceSheet").asText() + " / " + row.path("sourceRow").asText();
        evidence.addObject().set("sample", mapper.createObjectNode().set("input", input).put("expectedTotal", expected).put("sourceReference", source)).set("result", actual);
        return actual;
    }

    private ObjectNode input(JsonNode row, BigDecimal weight) {
        var zone = row.path("zoneName").asText();
        return mapper.createObjectNode().put("country", row.path("countryCode").asText()).put("weightKg", weight)
                .put("zoneName", zone.isBlank() ? "全国统一" : zone.split("[/／、,，;；|]", 2)[0]);
    }

    private void publish(Connection db, String repairId, ObjectNode item) throws SQLException {
        var plan = item.path("plan"); var payload = (ObjectNode) item.path("payload");
        var channel = UUID.fromString(plan.path("channelId").asText()); var old = UUID.fromString(plan.path("versionId").asText());
        var id = UUID.randomUUID(); var now = Instant.now().toString();
        int number = Integer.parseInt(scalar(db, "select coalesce(max(version_number),0)+1 from logistics_version where channel_id=?", channel));
        var hash = LogisticsDatasetService.hash(repairId + ":" + old + ":" + payload.path("rows"));
        var note = confirmedNote == null ? "原表明确最低计重补录；保留0起点、原单价、每票费和重量段；未推算正数首档或冲突备注"
                : confirmedNote + "；保留原单价、每票费、重量段及历史版本";
        if(reviewedSources)note="全渠道起重核对：依据留存备注或同版原表单元格修复最低计费重；保留原单价、每票费、重量段及历史版本；不推断未注明的起重";
        payload.put("id", id.toString()).put("channelId", channel.toString()).put("versionNumber", number).put("status", "published")
                .put("importedAt", now).put("publishedAt", now).put("importedBy", actor).put("publishedBy", actor)
                .put("sourceHash", hash).put("contentHash", hash).put("basePublishedVersionId", old.toString())
                .put("derivedFromVersionId", old.toString()).put("repairId", repairId).put("auditNote", note);
        update(db, "insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(?,?,?,'published',?,?::jsonb,now(),now())", id, channel, number, hash, payload.toString());
        update(db, "update logistics_version set status='superseded',payload=jsonb_set(payload,'{status}','\"superseded\"') where id=? and status='published'", old);
        update(db, "update logistics_channel set current_version_id=?,version=version+1,updated_at=now(),payload=payload||jsonb_build_object('currentVersionId',?::text,'updatedAt',?::text) where id=? and current_version_id=?", id, id.toString(), now, channel, old);
        var proof = mapper.createObjectNode().put("note", note).put("repairId", repairId).put("sourceReference", confirmedNote == null
                ? "Original stored source notes, exact version and source row guards" : "Explicit user confirmation on 2026-09-16; exact version, fingerprint and source row guards").set("evidence", item.path("evidence"));
        if(reviewedSources)proof.put("sourceReference","Reviewed same-edition source cells or original stored notes; exact version/fingerprint/row/country guards").set("sourcePatches",plan.path("patches"));
        update(db, "insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select ?,id,rows_fingerprint,?,'verified',?::jsonb,? from logistics_version where id=?", UUID.randomUUID(), LogisticsBillingEngine.VERSION, proof.toString(), actor, id);
        require(scalar(db, "select logistics_version_quote_ready(?)::text", id).equals("true"), "Corrected version is not quote ready");
        var detail = mapper.createObjectNode().put("repairId", repairId).put("beforeVersionId", old.toString()).put("afterVersionId", id.toString())
                .put("beforeRowsFingerprint", plan.path("rowsFingerprint").asText())
                .put("afterRowsFingerprint", scalar(db, "select rows_fingerprint from logistics_version where id=?", id))
                .put("changedRows", plan.path("patches").size()).put("note", note);
        update(db, "insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values(?,?,?,'logistics.minimum-weight.complete','logistics-channel',?,'success',?::jsonb,now())", UUID.randomUUID(), repairId, actor, channel.toString(), detail.toString());
        require(scalar(db, "select rows_fingerprint from logistics_version where id=?", old).equals(plan.path("rowsFingerprint").asText()), "Historical rows changed");
    }

    private static String scalar(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var result = statement.executeQuery()) { require(result.next(), "Missing repair prerequisite"); return result.getString(1); }
        }
    }
    private static void update(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            require(statement.executeUpdate() == 1, "Repair write count differs");
        }
    }
    private static void require(boolean condition, String message) throws SQLException { if (!condition) throw new SQLException(message); }
}
