package com.milano.quotation.migration;

import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsWorkbookService;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

@Service
class BusinessMigrationSourceService {
    private static final long MAX_SOURCE_BYTES = 120L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final int MAX_FILES = 100;
    private static final int EXPECTED_FILES = 66;
    static final int EXPECTED_PRICE_ROWS = 3298;
    private static final Pattern TRAINING_SOURCE = Pattern.compile("(?i)(?:^|[./_-])training(?:[./_-]|$)");
    private final ObjectMapper mapper;
    private final LogisticsWorkbookService logistics;

    BusinessMigrationSourceService(ObjectMapper mapper, LogisticsWorkbookService logistics) { this.mapper = mapper; this.logistics = logistics; }

    ObjectNode parse(String sourceType, MultipartFile file) {
        if (file == null || file.isEmpty()) throw AppException.unprocessable("迁移源文件不能为空");
        if (file.getSize() > MAX_SOURCE_BYTES) throw AppException.unprocessable("迁移源文件不能超过120MB");
        try {
            return switch (sourceType) {
                case BusinessMigrationService.LEGACY_BROWSER -> browser(file);
                case BusinessMigrationService.SUMAO_ZIP -> sumao(file);
                default -> throw AppException.unprocessable("迁移来源类型不支持");
            };
        } catch (AppException exception) { throw exception; }
        catch (Exception exception) { throw AppException.unprocessable("迁移源解析失败：" + safe(exception.getMessage())); }
    }

    private ObjectNode browser(MultipartFile file) throws Exception {
        var name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json")) throw AppException.unprocessable("浏览器迁移报告必须是JSON文件");
        var bytes = file.getBytes(); var node = mapper.readTree(bytes); if (!(node instanceof ObjectNode report)) throw AppException.unprocessable("浏览器迁移报告格式错误");
        report.put("sourceType", BusinessMigrationService.LEGACY_BROWSER);
        report.put("sourceFileSha256", AssetStorageService.sha256(bytes));
        report.path("entries").forEach(entry -> {
            if (!(entry instanceof ObjectNode object)) return;
            if (!object.hasNonNull("entryKey")) object.put("entryKey", BusinessMigrationService.entryKey(object));
            var sourceName = object.path("container").asText("") + "/" + object.path("key").asText("");
            if (TRAINING_SOURCE.matcher(sourceName).find()) {
                object.put("category", "unknown");
                object.put("decision", "exclude");
                object.put("reason", "培训系统数据禁止进入报价迁移");
                object.remove("value");
            }
        });
        if (!report.has("errors")) report.putArray("errors");
        return report;
    }

    private ObjectNode sumao(MultipartFile file) throws Exception {
        var name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip")) throw AppException.unprocessable("速猫物流迁移源必须是ZIP文件");
        var zipBytes = file.getBytes(); var zipHash = AssetStorageService.sha256(zipBytes);
        var entries = mapper.createArrayNode(); var errors = mapper.createArrayNode(); var hashes = new HashSet<String>();
        long total = 0; int fileCount = 0; int priceRows = 0;
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue; var path = normalizePath(entry.getName());
                if (!path.toLowerCase(Locale.ROOT).endsWith(".xlsx")) { issue(errors, path, "ZIP中包含非xlsx文件"); continue; }
                if (++fileCount > MAX_FILES) throw AppException.unprocessable("速猫物流ZIP最多包含100个Excel文件");
                var output = new ByteArrayOutputStream(); var buffer = new byte[8192]; int read; long size = 0;
                while ((read = zip.read(buffer)) >= 0) { size += read; total += read; if (size > MAX_ENTRY_BYTES || total > MAX_SOURCE_BYTES) throw AppException.unprocessable("ZIP解压后文件过大"); output.write(buffer, 0, read); }
                var bytes = output.toByteArray(); var hash = AssetStorageService.sha256(bytes);
                if (!hashes.add(hash)) { issue(errors, path, "ZIP中存在内容完全重复的物流文件"); continue; }
                try {
                    var parsed = logistics.parse(bytes, fileName(path), mapper.createArrayNode()); var value = mapper.createObjectNode();
                    value.put("providerName", providerName(path)); value.put("providerCode", code("PROVIDER", providerName(path)));
                    value.put("channelName", channelName(path)); value.put("channelCode", code("SUMAO", path));
                    value.put("fileName", fileName(path)); value.put("sourceHash", hash); value.set("rows", parsed.path("rows").deepCopy());
                    value.set("summary", parsed.path("summary").deepCopy()); value.set("issues", parsed.path("issues").deepCopy());
                    value.put("validRows", parsed.path("validRows").asInt()); value.put("errors", parsed.path("errors").asInt()); value.put("warnings", parsed.path("warnings").asInt());
                    var item = entries.addObject(); item.put("entryKey", "sumao:" + hash); item.put("source", "sumao-erp"); item.put("container", path);
                    item.put("key", hash); item.put("category", "logistics"); item.put("decision", "migrate"); item.put("reason", "速猫ERP物流文件，作为待审核草稿导入");
                    item.put("count", parsed.path("validRows").asInt()); item.set("value", value); priceRows += parsed.path("validRows").asInt();
                } catch (AppException exception) { issue(errors, path, exception.getMessage()); }
            }
        }
        if (fileCount != EXPECTED_FILES) issue(errors, "source", "文件数量应为66，实际为" + fileCount);
        if (priceRows != EXPECTED_PRICE_ROWS) issue(errors, "source", "价格段基线应为"+EXPECTED_PRICE_ROWS+"，实际解析为" + priceRows + "，必须完成人工差异确认");
        var report = mapper.createObjectNode(); report.put("schemaVersion", 2); report.put("sourceType", BusinessMigrationService.SUMAO_ZIP);
        report.put("sourceOrigin", "sumao://" + zipHash); report.put("sourceFileSha256", zipHash); report.put("exportedAt", Instant.now().toString()); report.set("entries", entries); report.set("errors", errors);
        var diff = report.putObject("diff"); diff.put("expectedFiles", EXPECTED_FILES); diff.put("actualFiles", fileCount); diff.put("expectedPriceRows", EXPECTED_PRICE_ROWS); diff.put("actualPriceRows", priceRows);
        return report;
    }

    private static String normalizePath(String value) { var path = value.replace('\\', '/').replaceAll("^/+", ""); if (path.isBlank() || path.contains("../") || path.equals("..") || path.indexOf('\0') >= 0) throw AppException.unprocessable("ZIP包含非法路径"); return path; }
    private static String fileName(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String channelName(String path) { var file = fileName(path); return file.substring(0, file.length() - 5).trim(); }
    private static String providerName(String path) { var parts = path.split("/"); var value = parts.length > 1 ? parts[parts.length - 2] : "未分类物流商"; return value.replace("模版", "").trim(); }
    private static String code(String prefix, String value) { var hash = AssetStorageService.sha256(value.getBytes(StandardCharsets.UTF_8)); return prefix + "-" + hash.substring(0, 16).toUpperCase(Locale.ROOT); }
    private static void issue(ArrayNode errors, String source, String message) { var item = errors.addObject(); item.put("source", source); item.put("message", safe(message)); item.put("level", "error"); }
    private static String safe(String value) { var text = value == null ? "未知错误" : value.replaceAll("[\\r\\n]+", " "); return text.substring(0, Math.min(500, text.length())); }
}
