package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class LogisticsQueryService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_COUNTRIES = 100;
    private static final int MAX_CHANNEL_CODES = 100;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public LogisticsQueryService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> providers(int page, int size, String query, Boolean enabled) {
        var params = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(" where dataset_id=logistics_active_dataset()");
        if (query != null && !query.isBlank()) {
            where.append(" and (lower(payload->>'name') like :query or lower(code) like :query)");
            params.put("query", "%" + query.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (enabled != null) {
            where.append(" and coalesce((payload->>'enabled')::boolean, true) = :enabled");
            params.put("enabled", enabled);
        }
        return jsonPage("logistics_provider" + where, "payload::text as payload, version", "updated_at desc", page, size, params, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> channels(int page, int size, String query, UUID providerId, Boolean enabled, Boolean archived) {
        var params = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(" where dataset_id=logistics_active_dataset()");
        if (query != null && !query.isBlank()) {
            where.append(" and (lower(payload->>'name') like :query or lower(code) like :query)");
            params.put("query", "%" + query.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (providerId != null) {
            where.append(" and provider_id = :providerId");
            params.put("providerId", providerId);
        }
        if (enabled != null) {
            where.append(" and coalesce((payload->>'enabled')::boolean, true) = :enabled");
            params.put("enabled", enabled);
        }
        if (archived != null) where.append(archived ? " and archived_at is not null" : " and archived_at is null");
        var select = "(payload || jsonb_build_object('archived', archived_at is not null, 'archivedAt', coalesce(archived_at::text,''), 'archivedBy', coalesce(archived_by,''), 'archiveReason', coalesce(archive_reason,'')))::text as payload, version";
        return jsonPage("logistics_channel" + where, select, "updated_at desc", page, size, params, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> versions(int page, int size, UUID channelId, String status) {
        var params = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(" where channel_id in (select id from logistics_channel where dataset_id=logistics_active_dataset())");
        if (channelId != null) {
            where.append(" and channel_id = :channelId");
            params.put("channelId", channelId);
        }
        if (status != null && !status.isBlank()) {
            if (!Set.of("draft", "published", "superseded", "rejected").contains(status)) {
                throw AppException.unprocessable("物流版本状态不合法");
            }
            where.append(" and status = :status");
            params.put("status", status);
        }
        var select = "(payload - 'rows' - 'issues' - 'diffRows')::text as payload, " +
                "jsonb_array_length(case when jsonb_typeof(payload->'rows')='array' then payload->'rows' else '[]'::jsonb end) as row_count, " +
                "jsonb_array_length(case when jsonb_typeof(payload->'issues')='array' then payload->'issues' else '[]'::jsonb end) as issue_count, " +
                "jsonb_array_length(case when jsonb_typeof(payload->'diffRows')='array' then payload->'diffRows' else '[]'::jsonb end) as diff_count, " +
                "(select count(distinct coalesce(nullif(item->>'countryCode',''), item->>'areaName')) from jsonb_array_elements(case when jsonb_typeof(payload->'rows')='array' then payload->'rows' else '[]'::jsonb end) item) as country_count";
        return jsonPage("logistics_version" + where, select, "created_at desc", page, size, params, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> versionRows(UUID versionId, int page, int size, String countryCode, String query) {
        return versionArrayPage(versionId, "rows", page, size, countryCode, query);
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> versionIssues(UUID versionId, int page, int size) {
        return versionArrayPage(versionId, "issues", page, size, null, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> versionDiff(UUID versionId, int page, int size) {
        return versionArrayPage(versionId, "diffRows", page, size, null, null);
    }

    @Transactional(readOnly = true)
    public PublishedManifest manifest() {
        var revisionParts = jdbc.sql("""
                select concat_ws('|', p.id::text, p.version::text, p.payload->>'enabled',
                  c.id::text, c.version::text, c.code, c.payload->>'enabled', c.payload->>'name', c.payload->>'type', c.payload->>'logisticsAttribute',
                  v.id::text, v.source_hash, coalesce(v.published_at::text,''),md5(coalesce(v.payload->'rows','[]'::jsonb)::text),
                  (select max(a.reviewed_at)::text from logistics_billing_acceptance a where a.version_id=v.id)) as part
                from logistics_channel c
                join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id and v.status='published'
                where coalesce((p.payload->>'enabled')::boolean,true)=true
                  and coalesce((c.payload->>'enabled')::boolean,true)=true
                  and c.archived_at is null
                  and c.dataset_id=logistics_active_dataset()
                  and logistics_version_quote_ready(v.id)
                order by c.id
                """).query(String.class).list();
        var revision = sha256(String.join("\n", revisionParts));
        var countries = jdbc.sql("""
                select distinct coalesce(item->>'countryCode','') as code, coalesce(item->>'areaName','') as name
                from logistics_channel c
                join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id and v.status='published'
                cross join lateral jsonb_array_elements(case when jsonb_typeof(v.payload->'rows')='array' then v.payload->'rows' else '[]'::jsonb end) item
                where coalesce((p.payload->>'enabled')::boolean,true)=true
                  and coalesce((c.payload->>'enabled')::boolean,true)=true
                  and c.archived_at is null
                  and c.dataset_id=logistics_active_dataset()
                  and logistics_version_quote_ready(v.id)
                  and coalesce(item->>'areaName','')<>''
                order by name, code
                """).query((rs, rowNum) -> new PublishedCountry(rs.getString("code"), rs.getString("name"))).list();
        var attributes = jdbc.sql("""
                select distinct coalesce(nullif(c.payload->>'logisticsAttribute',''),'普货') as attribute
                from logistics_channel c
                join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id and v.status='published'
                where coalesce((p.payload->>'enabled')::boolean,true)=true
                  and coalesce((c.payload->>'enabled')::boolean,true)=true
                  and c.archived_at is null
                  and c.dataset_id=logistics_active_dataset()
                  and logistics_version_quote_ready(v.id)
                order by attribute
                """).query(String.class).list();
        return new PublishedManifest(revision, Instant.now(), revisionParts.size(), countries, attributes);
    }

    @Transactional(readOnly = true)
    public PublishedRules publishedRules(String expectedRevision, String attribute, List<String> countries, List<String> channelCodes) {
        var manifest = manifest();
        if (expectedRevision != null && !expectedRevision.isBlank() && !expectedRevision.equals(manifest.revision())) {
            throw new AppException(HttpStatus.CONFLICT, "LOGISTICS_REVISION_CHANGED", "物流正式版本已更新，请重新加载规则");
        }
        var normalizedCountries = normalized(countries, MAX_COUNTRIES, "报价国家不能为空", "报价国家一次最多查询100个");
        var normalizedChannels = normalizedOptional(channelCodes, MAX_CHANNEL_CODES, "渠道一次最多查询100个");
        var params = new LinkedHashMap<String, Object>();
        var countryConditions = new ArrayList<String>();
        for (int index = 0; index < normalizedCountries.size(); index++) {
            var key = "country" + index;
            params.put(key, normalizedCountries.get(index).toLowerCase(Locale.ROOT));
            countryConditions.add("lower(coalesce(item->>'countryCode','')) = :" + key + " or lower(coalesce(item->>'areaName','')) = :" + key);
        }
        var sql = new StringBuilder("""
                select c.id::text as channel_id, c.rule_id, c.code as channel_code,
                  c.payload::text as channel_payload, p.payload::text as provider_payload,
                  ((v.payload - 'rows' - 'issues' - 'diffRows') || jsonb_build_object('id',v.id,'legacyBillingCompatible',
                    exists(select 1 from logistics_billing_acceptance a where a.version_id=v.id and a.kind='legacy'
                    and a.rows_fingerprint=md5(coalesce(v.payload->'rows','[]'::jsonb)::text))))::text as version_payload, item::text as row_payload
                from logistics_channel c
                join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id and v.status='published'
                cross join lateral jsonb_array_elements(case when jsonb_typeof(v.payload->'rows')='array' then v.payload->'rows' else '[]'::jsonb end) item
                where coalesce((p.payload->>'enabled')::boolean,true)=true
                  and coalesce((c.payload->>'enabled')::boolean,true)=true
                  and c.archived_at is null
                  and c.dataset_id=logistics_active_dataset()
                  and logistics_version_quote_ready(v.id)
                  and (
                """).append(String.join(" or ", countryConditions)).append(")");
        if (!normalizedChannels.isEmpty()) {
            var channelConditions = new ArrayList<String>();
            for (int index = 0; index < normalizedChannels.size(); index++) {
                var key = "channel" + index;
                params.put(key, normalizedChannels.get(index).toLowerCase(Locale.ROOT));
                channelConditions.add("lower(c.code) = :" + key);
            }
            sql.append(" and (").append(String.join(" or ", channelConditions)).append(")");
        }
        sql.append(" order by c.rule_id, coalesce(item->>'countryCode',''), coalesce((item->>'weightFromKg')::numeric,0)");

        var grouped = new LinkedHashMap<String, ObjectNode>();
        jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> {
            var row = json(rs.getString("row_payload"));
            row.put("quoteReady",true);
            if (!LogisticsBillingEngine.available(row)) return null;
            if (!eligible(row, attribute)) return null;
            var channelId = rs.getString("channel_id");
            var rule = grouped.computeIfAbsent(channelId, ignored -> {
                var channel = json(rsString(rs, "channel_payload"));
                var provider = json(rsString(rs, "provider_payload"));
                var version = json(rsString(rs, "version_payload"));
                var value = mapper.createObjectNode();
                value.put("id", rsInt(rs, "rule_id"));
                value.put("logisticsChannelId",channelId);
                value.put("logisticsVersionId",version.path("id").asText());
                value.put("billingVerified",!version.path("legacyBillingCompatible").asBoolean());
                value.put("name", channel.path("name").asText(rsString(rs, "channel_code")));
                value.put("englishName", rsString(rs, "channel_code").toLowerCase(Locale.ROOT));
                value.put("type", channel.path("type").asText("专线"));
                value.put("currency", "CNY");
                value.put("published", "发布");
                value.put("status", "启用");
                value.put("dates", channel.path("createdAt").asText("") + "|" + channel.path("updatedAt").asText(""));
                value.put("users", version.path("importedBy").asText("") + "|" + version.path("publishedBy").asText(""));
                value.putArray("relations").addObject()
                        .put("carrier", provider.path("name").asText(""))
                        .put("channel", channel.path("name").asText(""))
                        .put("channelCode", rsString(rs, "channel_code"))
                        .put("discounts", "-\n-");
                value.put("phoneRequired", false);
                value.put("areaCount", 0);
                value.put("priceRowCount", 0);
                value.putArray("prices");
                return value;
            });
            ((ArrayNode) rule.path("prices")).add(row);
            if (row.path("phoneRequired").asBoolean(false)) rule.put("phoneRequired", true);
            return channelId;
        }).list();
        grouped.values().forEach(rule -> {
            var prices = (ArrayNode) rule.path("prices");
            var areas = new java.util.HashSet<String>();
            prices.forEach(row -> areas.add(row.path("countryCode").asText(row.path("areaName").asText())));
            rule.put("areaCount", areas.size());
            rule.put("priceRowCount", prices.size());
        });
        return new PublishedRules(manifest.revision(), new ArrayList<>(grouped.values()));
    }

    private PageResponse<JsonNode> versionArrayPage(UUID versionId, String field, int page, int size, String countryCode, String query) {
        validatePage(page, size);
        if (!Set.of("rows", "issues", "diffRows").contains(field)) throw new IllegalArgumentException("Unsupported logistics array");
        if (!jdbc.sql("select count(*) from logistics_version where id=:id").param("id", versionId).query(Long.class).single().equals(1L)) {
            throw AppException.notFound("物流版本不存在");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("id", versionId);
        var where = new StringBuilder(" where 1=1");
        if (countryCode != null && !countryCode.isBlank()) {
            where.append(" and (lower(item->>'countryCode')=:country or lower(item->>'areaName')=:country)");
            params.put("country", countryCode.trim().toLowerCase(Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            where.append(" and lower(item::text) like :query");
            params.put("query", "%" + query.trim().toLowerCase(Locale.ROOT) + "%");
        }
        var from = "logistics_version v cross join lateral jsonb_array_elements(case when jsonb_typeof(v.payload->'" + field + "')='array' then v.payload->'" + field + "' else '[]'::jsonb end) with ordinality as array_items(item, ordinal)" + where + " and v.id=:id";
        var total = jdbc.sql("select count(*) from " + from).params(params).query(Long.class).single();
        List<JsonNode> items = jdbc.sql("select item::text as payload from " + from + " order by ordinal limit :limit offset :offset")
                .params(withPage(params, page, size)).query((rs, rowNum) -> (JsonNode) json(rs.getString("payload"))).list();
        return new PageResponse<>(items, page, size, total, pages(total, size));
    }

    private PageResponse<JsonNode> jsonPage(String from, String select, String order, int page, int size, Map<String, Object> params, boolean includeVersion) {
        validatePage(page, size);
        var total = jdbc.sql("select count(*) from " + from).params(params).query(Long.class).single();
        List<JsonNode> items = jdbc.sql("select " + select + " from " + from + " order by " + order + " limit :limit offset :offset")
                .params(withPage(params, page, size)).query((rs, rowNum) -> {
                    var body = json(rs.getString("payload"));
                    if (includeVersion) body.put("_version", rs.getLong("version"));
                    if (!includeVersion && hasColumn(rs, "row_count")) {
                        body.put("rowCount", rs.getLong("row_count"));
                        body.put("issueCount", rs.getLong("issue_count"));
                        body.put("diffCount", rs.getLong("diff_count"));
                        body.put("countryCount", rs.getLong("country_count"));
                    }
                    return (JsonNode) body;
                }).list();
        return new PageResponse<>(items, page, size, total, pages(total, size));
    }

    private static boolean hasColumn(java.sql.ResultSet rs, String name) {
        try { rs.findColumn(name); return true; } catch (java.sql.SQLException ignored) { return false; }
    }

    private ObjectNode json(String value) {
        try { return (ObjectNode) mapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("物流JSON数据无法解析", exception); }
    }

    private static boolean eligible(JsonNode row, String attribute) {
        var mark = attribute == null || attribute.isBlank() ? "普货" : attribute.trim();
        if ("化妆品".equals(mark)) mark = "非液体化妆品";
        var prohibited = splitMarks(row.path("prohibitedMarks").asText(""));
        if (prohibited.contains(mark)) return false;
        if (row.path("prohibitGeneralCargo").asBoolean(false) && "普货".equals(mark)) return false;
        var allowed = splitMarks(row.path("allowedMarks").asText(""));
        return allowed.isEmpty() || "普货".equals(mark) || allowed.contains(mark);
    }

    private static Set<String> splitMarks(String value) {
        var result = new java.util.HashSet<String>();
        for (var item : value.split("[,，、;；|]")) if (!item.isBlank()) result.add(item.trim());
        return result;
    }

    private static List<String> normalized(List<String> values, int limit, String emptyMessage, String limitMessage) {
        var normalized = normalizedOptional(values, limit, limitMessage);
        if (normalized.isEmpty()) throw AppException.unprocessable(emptyMessage);
        return normalized;
    }

    private static List<String> normalizedOptional(List<String> values, int limit, String limitMessage) {
        if (values == null) return List.of();
        var normalized = values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (normalized.size() > limit) throw AppException.unprocessable(limitMessage);
        return normalized;
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw AppException.unprocessable("分页参数不合法，size范围为1到200");
    }

    private static Map<String, Object> withPage(Map<String, Object> source, int page, int size) {
        var params = new LinkedHashMap<>(source);
        params.put("limit", size);
        params.put("offset", (long) page * size);
        return params;
    }

    private static int pages(long total, int size) { return total == 0 ? 0 : (int) Math.ceil((double) total / size); }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法生成物流版本号", exception); }
    }

    private static int rsInt(java.sql.ResultSet rs, String column) {
        try { return rs.getInt(column); } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
    }

    private static String rsString(java.sql.ResultSet rs, String column) {
        try { return rs.getString(column); } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
    }

    public record PublishedCountry(String code, String name) {}
    public record PublishedManifest(String revision, Instant generatedAt, long publishedChannels, List<PublishedCountry> countries, List<String> attributes) {}
    public record PublishedRules(String revision, List<ObjectNode> rules) {}
}
