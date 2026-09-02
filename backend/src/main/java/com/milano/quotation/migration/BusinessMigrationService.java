package com.milano.quotation.migration;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
class BusinessMigrationService {
    static final String LEGACY_BROWSER = "legacy-browser-report";
    static final String SUMAO_ZIP = "sumao-logistics-zip";
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i)(password|passwd|secret|cookie|session|authorization|access.?token|refresh.?token|private.?key)");
    private static final Set<String> DECISIONS = Set.of("migrate", "exclude", "review");
    private static final Set<String> SOURCE_TYPES = Set.of(LEGACY_BROWSER, SUMAO_ZIP);
    private final BusinessMigrationBatchRepository batches;
    private final ObjectMapper mapper;

    BusinessMigrationService(BusinessMigrationBatchRepository batches, ObjectMapper mapper) { this.batches = batches; this.mapper = mapper; }

    @Transactional
    BusinessMigrationBatch preview(JsonNode report, String actor) {
        validate(report); var hash = sourceHash(report);
        var existing = batches.findBySourceHash(hash);
        if (existing.isPresent()) {
            var row = existing.get();
            if ("pending_review".equals(row.status)) {
                var now = Instant.now(); row.sourceOrigin = report.path("sourceOrigin").asText().trim(); row.sourceType = report.path("sourceType").asText(LEGACY_BROWSER);
                row.counts = count(report.path("entries")); row.report = report.deepCopy(); row.diff = report.path("diff").isObject() ? report.path("diff").deepCopy() : JsonNodeFactory.instance.objectNode();
                row.errors = report.path("errors").isArray() ? report.path("errors").deepCopy() : JsonNodeFactory.instance.arrayNode(); row.updatedAt = now;
                row.checkpoint = JsonNodeFactory.instance.objectNode().put("revalidatedAt", now.toString()); return batches.saveAndFlush(row);
            }
            return row;
        }
        var now = Instant.now(); var row = new BusinessMigrationBatch(); row.id = UUID.randomUUID();
        row.sourceOrigin = report.path("sourceOrigin").asText().trim(); row.sourceHash = hash;
        row.sourceType = report.path("sourceType").asText(LEGACY_BROWSER); row.status = "pending_review"; row.requestedBy = actor;
        row.counts = count(report.path("entries")); row.report = report.deepCopy();
        row.diff = report.path("diff").isObject() ? report.path("diff").deepCopy() : JsonNodeFactory.instance.objectNode();
        row.errors = report.path("errors").isArray() ? report.path("errors").deepCopy() : JsonNodeFactory.instance.arrayNode();
        row.checkpoint = JsonNodeFactory.instance.objectNode().put("validatedAt", now.toString()); row.createdAt = now; row.updatedAt = now;
        return batches.saveAndFlush(row);
    }

    @Transactional(readOnly = true)
    BusinessMigrationBatch get(UUID id) { return batches.findById(id).orElseThrow(() -> AppException.notFound("迁移批次不存在")); }

    @Transactional
    BusinessMigrationBatch approve(UUID id, JsonNode body, String actor) {
        var row = get(id); if ("approved".equals(row.status)) return row;
        if (!"pending_review".equals(row.status)) throw AppException.conflict("当前迁移批次状态不可审批");
        if (!actor.equals(row.requestedBy)) throw AppException.conflict("只允许批次创建人确认迁移白名单");
        if (row.errors.isArray() && !row.errors.isEmpty()) throw AppException.unprocessable("迁移批次仍有阻断错误，不能审批");
        var whitelist = body.path("approvedEntryKeys"); if (!whitelist.isArray() || whitelist.isEmpty()) throw AppException.unprocessable("迁移白名单不能为空");
        var available = new java.util.HashSet<String>(); row.report.path("entries").forEach(entry -> {
            if ("migrate".equals(entry.path("decision").asText()) && entry.has("value")) available.add(entryKey(entry));
        });
        for (var key : whitelist) if (!key.isTextual() || key.asText().isBlank() || key.asText().length() > 300 || !available.contains(key.asText())) throw AppException.unprocessable("迁移白名单包含非法或不存在的条目");
        var updated = (ObjectNode) row.report.deepCopy(); updated.set("approvedEntryKeys", whitelist.deepCopy());
        updated.set("ownerMappings", objectOrEmpty(body.path("ownerMappings"))); updated.set("conflictResolutions", objectOrEmpty(body.path("conflictResolutions")));
        updated.put("approvedBy", actor); updated.put("approvedAt", Instant.now().toString()); row.report = updated; row.status = "approved"; row.updatedAt = Instant.now();
        return batches.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    BusinessMigrationBatch markExecuting(UUID id, String requestId) {
        var row = get(id); if (!"approved".equals(row.status) && !"failed".equals(row.status)) throw AppException.conflict("迁移批次尚未审批或不可重试");
        row.status = "executing"; row.requestId = requestId; row.lastError = null; row.updatedAt = Instant.now();
        row.checkpoint = JsonNodeFactory.instance.objectNode().put("startedAt", row.updatedAt.toString()).put("phase", "executing"); return batches.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    BusinessMigrationBatch markCompleted(UUID id, JsonNode execution) {
        var row = get(id); var now = Instant.now(); row.status = "completed"; row.completedAt = now; row.updatedAt = now;
        var report = (ObjectNode) row.report.deepCopy(); report.set("execution", execution.deepCopy()); row.report = report;
        row.checkpoint = JsonNodeFactory.instance.objectNode().put("completedAt", now.toString()).put("phase", "completed"); return batches.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markFailed(UUID id, Throwable error) {
        var row = get(id); row.status = "failed"; row.lastError = safe(error.getMessage()); row.updatedAt = Instant.now();
        row.checkpoint = JsonNodeFactory.instance.objectNode().put("failedAt", row.updatedAt.toString()).put("phase", "failed"); batches.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    BusinessMigrationBatch markRolledBack(UUID id, JsonNode result) {
        var row = get(id); var now = Instant.now(); row.status = "rolled_back"; row.updatedAt = now; row.completedAt = now;
        var report = (ObjectNode) row.report.deepCopy(); report.set("rollback", result.deepCopy()); row.report = report;
        row.checkpoint = JsonNodeFactory.instance.objectNode().put("rolledBackAt", now.toString()).put("phase", "rolled_back"); return batches.saveAndFlush(row);
    }

    private void validate(JsonNode report) {
        if (report == null || !report.isObject()) throw AppException.unprocessable("迁移报告格式错误");
        var sourceType = report.path("sourceType").asText(LEGACY_BROWSER); if (!SOURCE_TYPES.contains(sourceType)) throw AppException.unprocessable("迁移来源类型不支持");
        var origin = report.path("sourceOrigin").asText("").trim(); if (origin.isEmpty() || origin.length() > 255) throw AppException.unprocessable("迁移来源地址不合法");
        if (LEGACY_BROWSER.equals(sourceType) && !(origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1"))) throw AppException.unprocessable("迁移来源必须是原本地报价系统来源");
        if (SUMAO_ZIP.equals(sourceType) && !origin.startsWith("sumao://")) throw AppException.unprocessable("速猫迁移来源标识不合法");
        var entries = report.path("entries"); if (!entries.isArray()) throw AppException.unprocessable("迁移报告缺少 entries");
        if (entries.size() > 5000) throw AppException.unprocessable("单次迁移报告条目不能超过5000项");
        for (var entry : entries) { if (!DECISIONS.contains(entry.path("decision").asText())) throw AppException.unprocessable("迁移决策值非法"); if (entryKey(entry).length() > 300) throw AppException.unprocessable("迁移条目标识过长"); scanSensitive(entry, "entry"); }
    }

    static String entryKey(JsonNode entry) { return entry.hasNonNull("entryKey") ? entry.path("entryKey").asText() : entry.path("source").asText() + "/" + entry.path("key").asText(); }
    private void scanSensitive(JsonNode node, String path) { if (node == null || node.isNull()) return; if (node.isObject()) node.properties().forEach(property -> { if (SENSITIVE_KEY.matcher(property.getKey()).find()) throw AppException.unprocessable("迁移报告包含敏感字段：" + path + "." + property.getKey()); scanSensitive(property.getValue(), path + "." + property.getKey()); }); else if (node.isArray()) for (int index = 0; index < node.size(); index++) scanSensitive(node.get(index), path + "[" + index + "]"); }
    private ObjectNode count(JsonNode entries) { var counts = JsonNodeFactory.instance.objectNode().put("total", entries.size()).put("migrate", 0).put("exclude", 0).put("review", 0); entries.forEach(entry -> { var decision = entry.path("decision").asText(); counts.put(decision, counts.path(decision).asInt() + 1); }); return counts; }
    private static ObjectNode objectOrEmpty(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value.deepCopy() : JsonNodeFactory.instance.objectNode(); }
    private String sourceHash(JsonNode report) { var declared = report.path("sourceFileSha256").asText("").toLowerCase(Locale.ROOT); if (declared.matches("[0-9a-f]{64}")) return declared; try { var canonical = mapper.writeValueAsString(report).getBytes(StandardCharsets.UTF_8); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)); } catch (Exception error) { throw AppException.unprocessable("迁移报告无法计算哈希"); } }
    private static String safe(String value) { var text = value == null || value.isBlank() ? "迁移执行失败" : value.replaceAll("[\\r\\n]+", " "); return text.substring(0, Math.min(1000, text.length())); }
}
