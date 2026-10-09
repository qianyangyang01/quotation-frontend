package com.milano.quotation.fob;

import com.milano.quotation.audit.*;
import com.milano.quotation.common.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Service
public class FobPurchaseService {
    private static final String RESOURCE = "fob-purchase-product";
    private static final Map<String, String> FIELDS;
    static {
        var m = new LinkedHashMap<String, String>();
        m.put("weightRaw", "克重原文"); m.put("moqRaw", "起订量原文"); m.put("priceRaw", "采购价格原文");
        m.put("freightRaw", "运费原文"); m.put("category", "类别"); m.put("notes", "备注");
        m.put("quotationOwner", "报价人"); m.put("quotationDate", "报价日期");
        m.put("size", "尺码"); m.put("color", "颜色"); m.put("material", "材质");
        m.put("factoryInfo", "工厂信息"); FIELDS = Collections.unmodifiableMap(m);
    }
    private final FobPurchaseRepository repository;
    private final AuditService audit;
    private final AuditLogRepository logs;
    private final ObjectMapper mapper;
    public FobPurchaseService(FobPurchaseRepository repository, AuditService audit, AuditLogRepository logs, ObjectMapper mapper) {
        this.repository = repository; this.audit = audit; this.logs = logs; this.mapper = mapper;
    }
    @Transactional(readOnly = true)
    public Preview preview(List<JsonNode> input) {
        var batch = inputs(input);
        return prepare(batch, repository.findBySkuIn(batch.rows.keySet()).stream().collect(Collectors.toMap(p -> p.sku, p -> p)));
    }
    @Transactional
    public Result confirm(Confirmation input) {
        if (input == null || input.expected == null || input.digest == null) throw AppException.unprocessable("请先预览FOB数据");
        var batch = inputs(input.rows);
        if (!digest(batch).equals(input.digest)) throw AppException.conflict("粘贴内容已变化，请重新预览");
        var existing = repository.findLocked(batch.rows.keySet()).stream().collect(Collectors.toMap(p -> p.sku, p -> p));
        var expectations = new HashMap<String, Expected>();
        for (var e : input.expected) if (e == null || e.sku == null || expectations.put(e.sku, e) != null) throw AppException.unprocessable("预览版本格式错误");
        if (!expectations.keySet().equals(batch.rows.keySet())) throw AppException.conflict("预览商品列表已变化，请重新预览");
        for (var sku : batch.rows.keySet()) {
            var current = existing.get(sku); var expected = expectations.get(sku);
            if (current == null ? expected.version != null || expected.updatedAt != null
                    : !Objects.equals(current.version, expected.version) || !Objects.equals(current.updatedAt, expected.updatedAt))
                throw AppException.conflict("FOB商品 " + sku + " 已被更新，请重新预览；本批次未保存");
        }
        var preview = prepare(batch, existing);
        if (!preview.canSave) throw AppException.unprocessable("存在待修正的FOB资料，本批次未保存");
        int added = 0, updated = 0, unchanged = 0;
        // Existing rows are locked in SKU order. New rows must use the same deterministic
        // order too: reverse-order overlapping inserts otherwise deadlock on unique keys.
        // Keep the user's original row order in the preview and source-row diagnostics.
        for (var row : preview.rows.stream().sorted(Comparator.comparing(PreviewRow::sku)).toList()) {
            if (row.action.equals("unchanged")) { unchanged++; continue; }
            var current = existing.get(row.sku);
            JsonNode before = current == null ? NullNode.getInstance() : current.payload.deepCopy();
            var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (current == null) { current = new FobPurchaseProduct(); current.sku = row.sku; current.createdAt = now; added++; }
            else updated++;
            current.payload = row.effective.deepCopy(); current.updatedAt = now;
            repository.save(current);
            audit.record("fob-purchase." + row.action, RESOURCE, row.sku, "success",
                    Map.of("sku", row.sku, "changes", row.changes, "before", before, "after", row.effective));
        }
        repository.flush();
        return new Result(added, updated, unchanged, preview.skipped.size());
    }
    @Transactional
    public JsonNode getForQuotation(String sku) {
        repository.findLocked(List.of(normalizeSku(sku)));
        return get(sku);
    }
    @Transactional(readOnly = true)
    public JsonNode get(String sku) {
        var p = repository.findById(normalizeSku(sku)).orElseThrow(() -> new AppException(org.springframework.http.HttpStatus.NOT_FOUND, "FOB_PURCHASE_NOT_FOUND", "未找到该SKU的FOB资料"));
        var result = (ObjectNode) p.payload.deepCopy(); result.put("dataSource", "fob").put("version", p.version).put("updatedAt", p.updatedAt.toString()); return result;
    }
    @Transactional(readOnly = true)
    public List<History> history(String sku) {
        return logs.findByResourceTypeAndResourceIdOrderByCreatedAtDescIdDesc(RESOURCE, normalizeSku(sku), PageRequest.of(0, 50))
                .map(x -> new History(x.createdAt, x.actorAccount, x.detail.path("changes"))).getContent();
    }
    private Preview prepare(Batch batch, Map<String, FobPurchaseProduct> existing) {
        var result = new ArrayList<PreviewRow>();
        for (var entry : batch.rows.entrySet()) {
            var sku = entry.getKey(); var patch = entry.getValue(); var current = existing.get(sku);
            var merged = current == null ? mapper.createObjectNode().put("sku", sku) : (ObjectNode) current.payload.deepCopy();
            // Source belongs to the FOB ingestion endpoint, never to editable pasted fields.
            merged.put("dataSource", "fob");
            merged.put("verificationStatus", "pending");
            FIELDS.keySet().forEach(field -> { if (patch.has(field)) merged.set(field, patch.get(field).deepCopy()); });
            var issues = new ArrayList<String>(); var notices = new ArrayList<String>();
            // Derived lists are rebuilt from the complete merged source, never merged by tier index.
            merged.remove("parsed");
            try {
                if (java.util.regex.Pattern.compile("版费|打样费|包装费|另收|另计").matcher(merged.path("notes").asText("")).find())
                    throw new IllegalArgumentException("备注含额外收费条件，请先确认适用费用，不能仅按单价自动报价");
                var parsed = FobSourceParser.parse(merged.path("moqRaw").asText(""), merged.path("priceRaw").asText(""), merged.path("freightRaw").asText(""));
                merged.set("parsed", mapper.valueToTree(parsed)); notices.addAll(parsed.notices());
                if (merged.path("weightRaw").asText("").isBlank()) notices.add("克重未填写，本次仅保存价格与运费资料");
            } catch (IllegalArgumentException e) { issues.add(e.getMessage()); }
            var changes = new ArrayList<Change>();
            String previousSource = current == null ? "" : current.payload.path("dataSource").asText("");
            if (!"fob".equals(previousSource)) changes.add(new Change("dataSource", "数据来源", previousSource, "FOB数据"));
            FIELDS.forEach((field, label) -> {
                String before = current == null ? "" : current.payload.path(field).asText(""); String after = merged.path(field).asText("");
                if (!before.equals(after)) changes.add(new Change(field, label, before, after));
            });
            boolean same = current != null && changes.isEmpty() && equivalent(current.payload.path("parsed"), merged.path("parsed"));
            result.add(new PreviewRow(patch.path("sourceRow").asInt(), sku, current == null ? "create" : same ? "unchanged" : "update",
                    new Expected(sku, current == null ? null : current.version, current == null ? null : current.updatedAt), changes, issues, notices, merged));
        }
        return new Preview(result, batch.skipped, result.stream().allMatch(x -> x.issues.isEmpty()), digest(batch));
    }
    // PostgreSQL JSONB may deserialize decimal 0 as an integer; numeric representation is not a business change.
    private static boolean equivalent(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) return a.size() == b.size() && a.properties().stream().allMatch(e -> b.has(e.getKey()) && equivalent(e.getValue(), b.get(e.getKey())));
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equivalent(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
    private Batch inputs(List<JsonNode> input) {
        if (input == null || input.isEmpty() || input.size() > 100) throw AppException.unprocessable("FOB粘贴每次须为1至100行");
        var rows = new LinkedHashMap<String, ObjectNode>(); var skipped = new ArrayList<Skipped>();
        for (int i = 0; i < input.size(); i++) {
            var node = input.get(i); int row = i + 1;
            if (node == null || !node.isObject() || !node.path("sku").isTextual()) throw AppException.unprocessable("第" + row + "行缺少SKU");
            var sku = normalizeSku(node.path("sku").asText());
            if (rows.containsKey(sku)) { skipped.add(new Skipped(row, sku)); continue; }
            var patch = mapper.createObjectNode().put("sku", sku).put("sourceRow", row);
            for (var field : node.properties()) {
                var name = field.getKey(); var value = field.getValue();
                if (name.equals("sku")) continue;
                if (!FIELDS.containsKey(name)) throw AppException.unprocessable("不允许修改FOB字段：" + name);
                if (value.isNull()) continue;
                if (!value.isTextual() || value.asText().length() > 10000) throw AppException.unprocessable("第" + row + "行字段格式错误或过长：" + name);
                String text = value.asText().replace("\r\n", "\n").trim();
                if (!text.isBlank()) patch.put(name, text);
            }
            rows.put(sku, patch);
        }
        return new Batch(rows, skipped);
    }
    private static String normalizeSku(String sku) {
        String normalized = sku == null ? "" : sku.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (!normalized.matches("[A-Z0-9._/-]{1,96}") || normalized.matches("^(TESTP|TEST|DEMO|MOCK|AUTO-).*$")) throw AppException.unprocessable("请填写有效的正式SKU");
        return normalized;
    }
    private String digest(Batch batch) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(batch.rows).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Batch(LinkedHashMap<String, ObjectNode> rows, List<Skipped> skipped) {}
    public record Expected(String sku, Long version, Instant updatedAt) {}
    public record Skipped(int sourceRow, String sku) {}
    public record Change(String field, String label, String before, String after) {}
    public record PreviewRow(int sourceRow, String sku, String action, Expected expected, List<Change> changes, List<String> issues, List<String> notices, ObjectNode effective) {}
    public record Preview(List<PreviewRow> rows, List<Skipped> skipped, boolean canSave, String digest) {}
    public record Confirmation(List<JsonNode> rows, List<Expected> expected, String digest) {}
    public record Result(int added, int updated, int unchanged, int skipped) {}
    public record History(Instant createdAt, String actorAccount, JsonNode changes) {}
}
