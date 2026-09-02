package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Source-file continuation; only committed, non-rolled-back rows are evidence of success. */
@Service
public class PurchaseImportContinuationService {
    private static final String HISTORY = """
            WITH source_jobs AS (
                SELECT id FROM import_job
                 WHERE job_type = 'purchase-xlsx-async' AND source_name = :sourceName AND id <> :jobId
            ), history_rows AS (
                SELECT r.source_sheet, r.source_row, r.sku, r.payload, r.source_content_hash_without_sku,
                       r.applied_product_id
                  FROM purchase_import_row r JOIN source_jobs j ON j.id = r.job_id
                 WHERE r.applied_at IS NOT NULL AND r.rolled_back_at IS NULL
                UNION ALL
                SELECT r.source_sheet, r.source_row, r.sku, r.payload, r.source_content_hash_without_sku,
                       selected.applied_product_id
                  FROM purchase_import_row r JOIN source_jobs j ON j.id = r.job_id
                  JOIN purchase_import_row selected ON selected.job_id = r.job_id AND selected.sku = r.sku
                       AND selected.applied_at IS NOT NULL AND selected.rolled_back_at IS NULL
                 WHERE r.validation_status = 'duplicate-skipped'
            )
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PurchaseImportContinuationService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    static boolean enabled(ImportJob job) {
        return job.payload != null && "append".equals(job.payload.path("continuation").path("mode").asText());
    }

    static void initialize(ImportJob job) {
        ((ObjectNode) job.payload).putObject("continuation").put("mode", "append")
                .put("sourceName", job.sourceName).put("baselineFound", false).put("skippedRows", 0)
                .put("pendingRows", 0).put("skuBackfillRows",0).put("blocked", false).putArray("sheets");
    }

    /** Same source is serialized across confirmation, batch writes and rollback. */
    public void lockSource(ImportJob job) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("采购来源锁必须在事务内使用");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 821734))",
                (RowCallbackHandler) rs -> {}, job.sourceName);
        jdbc.update("INSERT INTO purchase_import_source_revision(source_name) VALUES (?) ON CONFLICT DO NOTHING", job.sourceName);
    }

    /** Called at preview, confirmation and before applying the first batch. */
    public void refresh(ImportJob job) {
        if (!enabled(job)) return;
        lockSource(job);
        var parameters = parameters(job);
        var history = history(parameters, "");
        var staged = named.query("""
                SELECT id, source_sheet, source_row, sku, validation_status, import_action,
                       applied_at IS NOT NULL AS applied, source_content_hash_without_sku,
                       payload ->> 'skuOrigin' = 'system' AS generated, target_product_id, expected_version,
                       purchase_import_source_fingerprint(payload, true) AS fingerprint
                  FROM purchase_import_row WHERE job_id = :jobId ORDER BY source_sheet, source_row
                """, parameters, (rs, n) -> new Staged(rs.getObject("id", UUID.class),
                new Location(rs.getString("source_sheet"), rs.getInt("source_row")), rs.getString("sku"),
                rs.getString("validation_status"), rs.getBoolean("applied"),
                rs.getString("fingerprint"), rs.getString("source_content_hash_without_sku"),
                rs.getString("import_action"),rs.getBoolean("generated"),rs.getObject("target_product_id",UUID.class),
                rs.getObject("expected_version",Long.class)));
        var locations = new HashSet<Location>();
        var highest = new TreeMap<String, Integer>();
        var historicalSkus = new HashSet<String>();
        history.forEach((location, item) -> {
            highest.merge(location.sheet(), location.row(), Math::max);
            historicalSkus.add(item.sku());
            if(item.currentSku()!=null)historicalSkus.add(item.currentSku());
        });
        var knownHoles = knownHoles(parameters, highest);
        var existingTargets=new HashMap<String,UUID>();
        named.query("SELECT DISTINCT p.sku,p.id FROM purchase_product p JOIN purchase_import_row r ON r.sku=p.sku WHERE r.job_id=:jobId",
                parameters,(RowCallbackHandler)rs->existingTargets.put(rs.getString("sku"),rs.getObject("id",UUID.class)));
        var toSkip = new ArrayList<UUID>();
        var toRestore = new ArrayList<UUID>();
        var toBackfill = new LinkedHashMap<Staged,History>();
        String reason = null;
        for (var item : staged) {
            locations.add(item.location());
            var prior = history.get(item.location());
            if (prior != null) {
                if (prior.ambiguous()) reason = first(reason, ambiguousReason(item.location()));
                else if (!matches(prior, item)) reason = first(reason, changedReason(item.location()));
                else if (!item.applied()) {
                    if("error".equals(item.status()))reason=first(reason,"工作表“"+item.location().sheet()+"”第 "+item.location().row()+" 行补充的正式SKU不合法，请修正后重新上传");
                    else if(prior.currentSku()==null)reason=first(reason,"工作表“"+item.location().sheet()+"”第 "+item.location().row()+" 行对应原商品已不存在，已停止续传，不会新增替代商品");
                    else if(item.generated() || item.sku().equals(prior.currentSku()))toSkip.add(item.id());
                    else if(prior.generated() && prior.formalSku()==null && prior.currentSku()!=null && prior.currentSku().startsWith("AUTO-")){
                        if("sku-backfill".equals(item.action()) && !Objects.equals(item.expectedVersion(),prior.currentVersion()))
                            reason=first(reason,"工作表“"+item.location().sheet()+"”第 "+item.location().row()+" 行对应商品已被修改，请重新上传校验后补SKU");
                        else if(existingTargets.containsKey(item.sku())&&!existingTargets.get(item.sku()).equals(prior.productId()))
                            reason=first(reason,"正式SKU "+item.sku()+" 已被其他商品占用，不能给原商品补SKU");
                        else toBackfill.put(item,prior);
                    }else reason=first(reason,"工作表“"+item.location().sheet()+"”第 "+item.location().row()+" 行对应商品已有不同正式SKU或已不存在，不能改号或新增替代商品");
                }
            } else if (!item.applied()) {
                if ("history-skipped".equals(item.status())||"sku-backfill".equals(item.action())) toRestore.add(item.id());
                if (item.location().row() <= highest.getOrDefault(item.location().sheet(), 0)
                        && !knownHoles.contains(item.location()))
                    reason = first(reason, "工作表“" + item.location().sheet() + "”第 " + item.location().row()
                            + " 行原为空白或未记录，现已出现数据；请检查是否插入、删除或排序了旧行，已停止续传");
                if (historicalSkus.contains(item.sku()))
                    reason = first(reason, "工作表“" + item.location().sheet() + "”第 " + item.location().row()
                            + " 行 SKU " + item.sku() + " 与此表已入库商品重复；续传不能覆盖旧商品，请核对新行SKU");
            }
        }
        for (var entry : history.entrySet()) {
            if (entry.getValue().ambiguous()) reason = first(reason, ambiguousReason(entry.getKey()));
            if (!locations.contains(entry.getKey())) reason = first(reason, "已处理的工作表“" + entry.getKey().sheet()
                    + "”第 " + entry.getKey().row() + " 行在本次表格中缺失；请使用原文件并保留旧行及工作表名称，已停止续传");
        }
        var targetCounts=new HashMap<String,Integer>();
        staged.stream().filter(row->!row.generated()).forEach(row->targetCounts.merge(row.sku(),1,Integer::sum));
        for(var entry:toBackfill.keySet()){
            if(targetCounts.getOrDefault(entry.sku(),0)>1)
                reason=first(reason,"正式SKU "+entry.sku()+" 在本表多行重复且涉及补SKU；请为每个原商品填写唯一SKU，不能合并不同商品");
        }

        // Save the original validation state so a rollback of the earlier import can
        // make a previously skipped row importable again without losing validation errors.
        if (!toRestore.isEmpty()) restore(parameters, toRestore);
        markBackfills(job,toBackfill);
        if (!toSkip.isEmpty()) skip(parameters, toSkip);
        summarize(job, staged, history, highest, new HashSet<>(toSkip),toBackfill.keySet().stream().map(Staged::id).collect(java.util.stream.Collectors.toSet()), reason);
        recordRevision(job);
    }

    /** Rechecks a batch under the same transaction as the actual writes. */
    public void guardBatch(ImportJob job, Collection<UUID> ids) {
        lockSource(job);
        if (!enabled(job)) {
            var claimed = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM import_job j JOIN purchase_import_row r ON r.job_id = j.id
                         WHERE j.job_type = 'purchase-xlsx-async' AND j.source_name = ? AND j.id <> ?
                           AND j.payload -> 'continuation' ->> 'mode' = 'append'
                           AND r.applied_at IS NOT NULL AND r.rolled_back_at IS NULL
                    )
                    """, Boolean.class, job.sourceName, job.id);
            if (Boolean.TRUE.equals(claimed)) throw AppException.conflict("此来源已通过续传任务入库；旧待确认任务不能再次覆盖，请重新上传原表校验");
            return;
        }
        if (job.payload.path("_continuationRevision").asLong(-1) != revision(job))
            throw AppException.conflict("来源导入或回滚记录在入库期间发生变化，已暂停本次写入；请重试以重新核对完整表格");
        if (ids.isEmpty()) return;
        var parameters = parameters(job).addValue("ids", ids);
        var history = history(parameters, """
                 WHERE EXISTS (SELECT 1 FROM purchase_import_row current_row
                                WHERE current_row.id IN (:ids) AND current_row.job_id = :jobId
                                  AND current_row.source_sheet = history_rows.source_sheet
                                  AND current_row.source_row = history_rows.source_row)
                """);
        var current = named.query("""
                SELECT id, source_sheet, source_row, sku, validation_status, import_action,
                       applied_at IS NOT NULL AS applied, source_content_hash_without_sku,
                       payload ->> 'skuOrigin' = 'system' AS generated, target_product_id, expected_version,
                       purchase_import_source_fingerprint(payload, true) AS fingerprint
                  FROM purchase_import_row WHERE id IN (:ids) AND job_id = :jobId
                """, parameters, (rs, n) -> new Staged(rs.getObject("id", UUID.class),
                new Location(rs.getString("source_sheet"), rs.getInt("source_row")), rs.getString("sku"),
                rs.getString("validation_status"), rs.getBoolean("applied"),
                rs.getString("fingerprint"), rs.getString("source_content_hash_without_sku"),
                rs.getString("import_action"),rs.getBoolean("generated"),rs.getObject("target_product_id",UUID.class),
                rs.getObject("expected_version",Long.class)));
        var toSkip = new ArrayList<UUID>();
        for (var item : current) {
            var prior = history.get(item.location());
            if (prior == null || item.applied()) continue;
            if (prior.ambiguous()) throw AppException.conflict(ambiguousReason(item.location()));
            if (!matches(prior, item)) throw AppException.conflict(changedReason(item.location()));
            if(!"sku-backfill".equals(item.action())||item.sku().equals(prior.currentSku()))toSkip.add(item.id());
        }
        if (!toSkip.isEmpty()) skip(parameters, toSkip);
    }

    /** Call after our own successful batch, while the source lock is still held. */
    public void recordRevision(ImportJob job) {
        if (enabled(job)) ((ObjectNode) job.payload).put("_continuationRevision", revision(job));
    }

    private long revision(ImportJob job) {
        return jdbc.queryForObject("SELECT revision FROM purchase_import_source_revision WHERE source_name=?", Long.class, job.sourceName);
    }

    static void requireUnblocked(ImportJob job) {
        if (enabled(job) && job.payload.path("continuation").path("blocked").asBoolean())
            throw AppException.conflict(job.payload.path("continuation").path("reason").asText("来源表格发生变化，已停止续传"));
    }

    private Map<Location, History> history(MapSqlParameterSource parameters, String filter) {
        var result = new LinkedHashMap<Location, History>();
        named.query(HISTORY + """
                SELECT source_sheet, source_row, min(history_rows.sku) AS sku, min(p.sku) AS current_sku,
                       min(p.version) AS current_version, (array_agg(DISTINCT applied_product_id))[1] AS product_id,
                       bool_or(history_rows.payload ->> 'skuOrigin' = 'system') AS generated,
                       min(history_rows.sku) FILTER(WHERE history_rows.payload ->> 'skuOrigin' IS DISTINCT FROM 'system') AS formal_sku,
                       min(purchase_import_source_fingerprint(history_rows.payload,true)) AS fingerprint,
                       min(source_content_hash_without_sku) AS content_hash,
                       count(DISTINCT purchase_import_source_fingerprint(history_rows.payload,true)) > 1
                         OR count(DISTINCT applied_product_id) > 1
                         OR count(DISTINCT source_content_hash_without_sku) > 1
                         OR count(DISTINCT history_rows.sku) FILTER(WHERE history_rows.payload ->> 'skuOrigin' IS DISTINCT FROM 'system') > 1 AS ambiguous
                  FROM history_rows LEFT JOIN purchase_product p ON p.id = history_rows.applied_product_id
                """ + filter + " GROUP BY source_sheet, source_row ORDER BY source_sheet, source_row",
                parameters, (RowCallbackHandler) rs -> result.put(
                        new Location(rs.getString("source_sheet"), rs.getInt("source_row")),
                        new History(rs.getString("sku"), rs.getString("current_sku"), rs.getString("fingerprint"),
                                rs.getString("content_hash"), rs.getBoolean("ambiguous"),rs.getBoolean("generated"),
                                rs.getString("formal_sku"),rs.getObject("product_id",UUID.class),rs.getObject("current_version",Long.class))));
        return result;
    }

    private Set<Location> knownHoles(MapSqlParameterSource parameters, Map<String, Integer> highest) {
        if (highest.isEmpty()) return Set.of();
        var result = new HashSet<Location>();
        named.query("""
                SELECT DISTINCT r.source_sheet, r.source_row
                  FROM purchase_import_row r JOIN import_job j ON j.id = r.job_id
                 WHERE j.job_type = 'purchase-xlsx-async' AND j.source_name = :sourceName AND j.id <> :jobId
                   AND EXISTS (SELECT 1 FROM purchase_import_row applied
                                WHERE applied.job_id = j.id AND applied.applied_at IS NOT NULL
                                  AND applied.rolled_back_at IS NULL)
                """, parameters, (RowCallbackHandler) rs -> {
            var sheet = rs.getString("source_sheet");
            var row = rs.getInt("source_row");
            if (row <= highest.getOrDefault(sheet, 0)) result.add(new Location(sheet, row));
        });
        return result;
    }

    private void skip(MapSqlParameterSource parameters, List<UUID> ids) {
        for (int offset = 0; offset < ids.size(); offset += PurchaseImportBatchService.BATCH_SIZE) {
            parameters.addValue("skipIds", ids.subList(offset, Math.min(ids.size(), offset + PurchaseImportBatchService.BATCH_SIZE)));
            named.update("""
                    UPDATE purchase_import_row SET
                           continuation_validation_status = COALESCE(continuation_validation_status, validation_status),
                           continuation_import_action = COALESCE(continuation_import_action, import_action),
                           continuation_error_message = CASE WHEN continuation_validation_status IS NULL
                               THEN error_message ELSE continuation_error_message END,
                           validation_status = 'history-skipped', import_action = 'skip',
                           error_message = '此来源行已成功处理，本次自动跳过'
                     WHERE id IN (:skipIds) AND job_id = :jobId AND applied_at IS NULL
                    """, parameters);
        }
    }

    private void restore(MapSqlParameterSource parameters, List<UUID> ids) {
        for (int offset = 0; offset < ids.size(); offset += PurchaseImportBatchService.BATCH_SIZE) {
            parameters.addValue("restoreIds", ids.subList(offset, Math.min(ids.size(), offset + PurchaseImportBatchService.BATCH_SIZE)));
            named.update("""
                    UPDATE purchase_import_row r SET
                           validation_status = COALESCE(continuation_validation_status, 'error'),
                           import_action = CASE WHEN continuation_validation_status IN ('valid','conflict')
                               THEN CASE WHEN EXISTS (SELECT 1 FROM purchase_product p WHERE p.sku = r.sku)
                                   THEN 'update' ELSE 'insert' END ELSE COALESCE(continuation_import_action, 'skip') END,
                           expected_version = (SELECT p.version FROM purchase_product p WHERE p.sku = r.sku),
                           error_message = continuation_error_message,
                           continuation_validation_status = NULL, continuation_import_action = NULL,
                           continuation_error_message = NULL, target_product_id = NULL
                     WHERE id IN (:restoreIds) AND job_id = :jobId AND applied_at IS NULL
                       AND (validation_status = 'history-skipped' OR import_action = 'sku-backfill')
                    """, parameters);
        }
    }

    private void markBackfills(ImportJob job,Map<Staged,History> backfills){
        if(backfills.isEmpty())return;
        var params=backfills.entrySet().stream().map(entry->new MapSqlParameterSource("id",entry.getKey().id())
                .addValue("jobId",job.id).addValue("targetId",entry.getValue().productId())
                .addValue("version",entry.getValue().currentVersion())).toArray(MapSqlParameterSource[]::new);
        named.batchUpdate("""
                UPDATE purchase_import_row SET
                       continuation_validation_status=COALESCE(continuation_validation_status,validation_status),
                       continuation_import_action=COALESCE(continuation_import_action,import_action),
                       continuation_error_message=CASE WHEN continuation_validation_status IS NULL THEN error_message ELSE continuation_error_message END,
                       validation_status='valid',import_action='sku-backfill',error_message=NULL,
                       target_product_id=:targetId,expected_version=:version
                 WHERE id=:id AND job_id=:jobId AND applied_at IS NULL
                """,params);
    }

    private void summarize(ImportJob job, List<Staged> staged, Map<Location, History> history,
                           Map<String, Integer> highest, Set<UUID> skipped,Set<UUID> backfills, String reason) {
        var summary = ((ObjectNode) job.payload).putObject("continuation");
        summary.put("mode", "append").put("sourceName", job.sourceName).put("baselineFound", !history.isEmpty())
                .put("skippedRows", skipped.size()).put("pendingRows", staged.size() - skipped.size())
                .put("skuBackfillRows",backfills.size()).put("blocked", reason != null);
        if (reason != null) summary.put("reason", reason);
        var sheets = new TreeMap<String, SheetCounts>();
        for (var sheet : job.payload.path("sheetSummaries")) {
            if (sheet.path("recognized").asBoolean()) sheets.put(sheet.path("sheetName").asText(),
                    new SheetCounts(highest.getOrDefault(sheet.path("sheetName").asText(), sheet.path("headerRow").asInt(1))));
        }
        highest.forEach((name, last) -> sheets.computeIfAbsent(name, key -> new SheetCounts(last)));
        for (var item : staged) {
            var counts = sheets.computeIfAbsent(item.location().sheet(), key -> new SheetCounts(highest.getOrDefault(key, 1)));
            if (skipped.contains(item.id())) counts.skipped++;
            else {
                counts.next = Math.min(counts.next, item.location().row());
                if(backfills.contains(item.id()))counts.backfills++;
                else if (item.location().row() <= counts.last) counts.retryRows++; else counts.newRows++;
            }
        }
        var array = summary.putArray("sheets");
        sheets.forEach((name, counts) -> array.addObject().put("sheetName", name).put("lastImportedRow", counts.last)
                .put("nextRow", counts.next == Integer.MAX_VALUE ? counts.last + 1 : counts.next)
                .put("skippedRows", counts.skipped).put("newRows", counts.newRows).put("retryRows", counts.retryRows).put("skuBackfillRows",counts.backfills));
    }

    private static MapSqlParameterSource parameters(ImportJob job) {
        return new MapSqlParameterSource("jobId", job.id).addValue("sourceName", job.sourceName);
    }
    private static boolean matches(History previous, Staged current) {
        return previous.fingerprint().equals(current.fingerprint())
                && (previous.contentHash() == null || previous.contentHash().equals(current.contentHash()))
                && (current.generated()?previous.generated():previous.formalSku()==null||previous.formalSku().equals(current.sku()));
    }
    private static String first(String previous, String reason) { return previous == null ? reason : previous; }
    private static String changedReason(Location location) {
        return "工作表“" + location.sheet() + "”第 " + location.row()
                + " 行与已处理记录不同；请检查是否修改、插入、删除或排序了旧行，已停止续传，不会覆盖旧商品";
    }
    private static String ambiguousReason(Location location) {
        return "工作表“" + location.sheet() + "”第 " + location.row()
                + " 行存在多个不一致的历史入库记录，无法安全确定续传位置；请先核对历史记录";
    }
    private record Location(String sheet, int row) {}
    private record History(String sku, String currentSku, String fingerprint, String contentHash, boolean ambiguous,
                           boolean generated,String formalSku,UUID productId,Long currentVersion) {}
    private record Staged(UUID id, Location location, String sku, String status, boolean applied,
                          String fingerprint, String contentHash,String action,boolean generated,UUID targetProductId,Long expectedVersion) {}
    private static final class SheetCounts {
        final int last;
        int next = Integer.MAX_VALUE;
        int skipped, newRows, retryRows,backfills;
        SheetCounts(int last) { this.last = last; }
    }
}
