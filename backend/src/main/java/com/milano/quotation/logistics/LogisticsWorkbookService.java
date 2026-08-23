package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

@Service
public class LogisticsWorkbookService {
    private static final int HEADER_SCAN_ROWS = 5;
    public static final List<String> HEADERS = List.of("区域名称", "国家简码", "时效最早天数", "时效最晚天数", "禁运商品", "允许商品标记",
            "三边之和", "三边最大长度", "计泡系数", "最小长度", "最大长度", "最小宽度", "最大宽度", "最小侧面积", "最大侧面积",
            "起始重量", "截止重量", "起重", "运费单价", "最小计重", "首重", "首重价", "续重", "续重单价", "区间运费",
            "挂号费", "附加费", "燃油附加费率", "特殊品含量", "是否计抛", "是否禁止普货", "电话是否必需", "分区名称",
            "分区邮编前缀", "分区邮编", "分区城市", "分区省州", "排除");
    private static final String[] KEYS = {"areaName", "countryCode", "etaMinDays", "etaMaxDays", "prohibitedMarks", "allowedMarks",
            "maxPerimeterCm", "maxSideCm", "volumeDivisor", "minLengthCm", "maxLengthCm", "minWidthCm", "maxWidthCm", "minSideAreaCm2",
            "maxSideAreaCm2", "weightFromKg", "weightToKg", "startWeightKg", "pricePerKg", "minChargeWeightKg", "firstWeightKg",
            "firstWeightPrice", "nextWeightKg", "nextWeightPrice", "intervalPrice", "registrationFee", "surcharge", "fuelSurchargeRate",
            "specialGoodsContent", "volumetric", "prohibitGeneralCargo", "phoneRequired", "zoneName", "zonePostalPrefix", "zonePostalCode",
            "zoneCity", "zoneState", "zoneExclude"};
    private static final Set<Integer> TEXT = Set.of(0, 1, 4, 5, 28, 32, 33, 34, 35, 36);
    private static final Set<Integer> BOOLEAN = Set.of(29, 30, 31, 37);
    private final ObjectMapper mapper;

    public LogisticsWorkbookService(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode parse(MultipartFile file, ArrayNode previousRows) {
        validate(file);
        try {
            return parse(file.getBytes(), file.getOriginalFilename(), previousRows);
        } catch (AppException exception) { throw exception; }
        catch (Exception exception) { throw AppException.unprocessable("物流Excel解析失败：" + safe(exception.getMessage())); }
    }

    public ObjectNode parse(byte[] bytes, String fileName, ArrayNode previousRows) {
        validate(fileName, bytes.length);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0); var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            var headerIndex = findHeaderRow(sheet, evaluator); validateHeaders(sheet.getRow(headerIndex), evaluator);
            var issues = mapper.createArrayNode(); var rows = mapper.createArrayNode(); var seen = new HashSet<String>();
            for (int index = headerIndex + 1; index <= sheet.getLastRowNum(); index++) {
                var source = sheet.getRow(index); if (empty(source, evaluator)) continue; int rowNumber = index + 1; var row = mapper.createObjectNode(); row.put("sourceRow", rowNumber);
                for (int column = 0; column < KEYS.length; column++) {
                    if (TEXT.contains(column)) row.put(KEYS[column], text(source, column, evaluator));
                    else if (BOOLEAN.contains(column)) row.put(KEYS[column], flag(text(source, column, evaluator)));
                    else putNumber(row, KEYS[column], number(source, column, rowNumber, HEADERS.get(column), evaluator, issues));
                }
                row.put("countryCode", row.path("countryCode").asText().toUpperCase(Locale.ROOT));
                validateRow(row, rowNumber, issues); var key = identity(row); row.put("rowKey", key);
                if (!seen.add(key)) issue(issues, rowNumber, "区域/重量区间", "重复计费区间", "error");
                rows.add(row);
            }
            if (rows.isEmpty()) throw AppException.unprocessable("物流模板中没有可导入的数据");
            var errors = count(issues, "error");
            var previous = new HashMap<String, tools.jackson.databind.JsonNode>(); previousRows.forEach(row -> previous.put(row.path("rowKey").asText(identity(row)), row));
            var diffRows = mapper.createArrayNode(); int added = 0, unchanged = 0;
            for (var row : rows) { var key = row.path("rowKey").asText(); var diff = diffRows.addObject(); diff.put("key", key); diff.set("row", row); diff.putArray("changes"); diff.put("risk", false); diff.putNull("maxPercentChange"); if (previous.remove(key) == null) { diff.put("type", "added"); added++; } else { diff.put("type", "unchanged"); unchanged++; } }
            int removed = previous.size(); previous.forEach((key, row) -> { var diff = diffRows.addObject(); diff.put("key", key); diff.put("type", "removed"); diff.set("row", row); diff.putArray("changes"); diff.put("risk", false); diff.putNull("maxPercentChange"); });
            var summary = mapper.createObjectNode().put("added", added).put("price", 0).put("rule", 0).put("removed", removed).put("unchanged", unchanged).put("highRisk", 0);
            var result = mapper.createObjectNode(); result.put("fileName", safeName(fileName)); result.put("sourceHash", AssetHash.sha(bytes));
            result.set("rows", rows); result.set("issues", issues); result.put("validRows", rows.size()); result.put("errors", errors);
            result.put("warnings", count(issues, "warning")); result.set("diffRows", diffRows); result.set("summary", summary); return result;
        } catch (AppException exception) { throw exception; }
        catch (Exception exception) { throw AppException.unprocessable("物流Excel解析失败：" + safe(exception.getMessage())); }
    }

    private static void validate(MultipartFile file) { validate(file.getOriginalFilename(), file.getSize()); if (file.isEmpty()) throw AppException.unprocessable("请选择.xlsx格式的物流模板"); }
    private static void validate(String fileName, long size) { if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") || size <= 0) throw AppException.unprocessable("请选择.xlsx格式的物流模板"); if (size > 100L * 1024 * 1024) throw AppException.unprocessable("物流Excel不能超过100MB"); }
    private static int findHeaderRow(Sheet sheet, FormulaEvaluator evaluator) {
        for (int index = 0; index <= Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS - 1); index++) {
            var row = sheet.getRow(index);
            if (HEADERS.getFirst().equals(text(row, 0, evaluator)) && HEADERS.get(1).equals(text(row, 1, evaluator))) return index;
        }
        throw AppException.unprocessable("物流模板列头不匹配：前5行内未找到38列标准表头");
    }
    private static void validateHeaders(Row header, FormulaEvaluator evaluator) { var mismatch = new ArrayList<String>(); for (int i = 0; i < HEADERS.size(); i++) if (!HEADERS.get(i).equals(text(header, i, evaluator))) mismatch.add((i + 1) + "列应为“" + HEADERS.get(i) + "”"); if (!mismatch.isEmpty()) throw AppException.unprocessable("物流模板列头不匹配：" + String.join("；", mismatch.subList(0, Math.min(4, mismatch.size())))); }
    private static void validateRow(ObjectNode row, int source, ArrayNode issues) { if (row.path("areaName").asText().isBlank()) issue(issues, source, "区域名称", "区域名称不能为空", "error"); if (row.path("countryCode").asText().isBlank()) issue(issues, source, "国家简码", "国家简码不能为空", "error"); if (row.path("etaMinDays").asDouble() > row.path("etaMaxDays").asDouble()) issue(issues, source, "预计时效", "最早天数不能大于最晚天数", "error"); if (row.path("weightToKg").asDouble() <= row.path("weightFromKg").asDouble()) issue(issues, source, "重量区间", "截止重量必须大于起始重量", "error"); if (!(row.path("pricePerKg").asDouble() > 0 || row.path("intervalPrice").asDouble() > 0 || row.path("firstWeightPrice").asDouble() > 0)) issue(issues, source, "计费价格", "未填写有效计费价格", "error"); }
    private static BigDecimal number(Row row, int column, int source, String field, FormulaEvaluator evaluator, ArrayNode issues) { var cell = row == null ? null : row.getCell(column); if (cell == null || cell.getCellType() == CellType.BLANK) return BigDecimal.ZERO; try { if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue()); if (cell.getCellType() == CellType.FORMULA) { try { var value = evaluator.evaluate(cell); if (value != null && value.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(value.getNumberValue()); } catch (RuntimeException ignored) { if (cell.getCachedFormulaResultType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue()); } } var raw = text(row, column, evaluator).replace(",", "").replace("¥", "").replace("￥", "").replace("\u00A0", "").replace("\u202F", "").replaceAll("(?i)CNY|RMB", "").replaceAll("\\s+", ""); return raw.isBlank() ? BigDecimal.ZERO : new BigDecimal(raw); } catch (Exception exception) { issue(issues, source, field, "不是有效数字", "error"); return BigDecimal.ZERO; } }
    private static void putNumber(ObjectNode row, String key, BigDecimal value) { row.put(key, value.stripTrailingZeros()); }
    private static String identity(tools.jackson.databind.JsonNode row) { return String.join("|", row.path("countryCode").asText(), row.path("areaName").asText(), row.path("zoneName").asText(), row.path("zonePostalPrefix").asText(), row.path("zonePostalCode").asText(), row.path("zoneCity").asText(), row.path("zoneState").asText(), row.path("weightFromKg").asText(), row.path("weightToKg").asText()).toLowerCase(Locale.ROOT); }
    private static boolean flag(String value) { return value.matches("(?i)^(是|1|true|yes)$"); }
    private static String text(Row row, int column, FormulaEvaluator evaluator) {
        var cell = row == null ? null : row.getCell(column); if (cell == null) return "";
        var formatter = new DataFormatter(Locale.CHINA);
        try { return formatter.formatCellValue(cell, evaluator).trim(); }
        catch (RuntimeException exception) { return cachedFormulaText(cell, formatter); }
    }
    private static String cachedFormulaText(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell).trim();
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString()).trim();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR, BLANK, _NONE, FORMULA -> "";
        };
    }
    private static boolean empty(Row row, FormulaEvaluator evaluator) { if (row == null) return true; for (int i = 0; i < HEADERS.size(); i++) if (!text(row, i, evaluator).isBlank()) return false; return true; }
    private static void issue(ArrayNode issues, int row, String field, String message, String level) { var issue = issues.addObject(); issue.put("row", row); issue.put("field", field); issue.put("message", message); issue.put("level", level); }
    private static int count(ArrayNode issues, String level) { int count = 0; for (var issue : issues) if (level.equals(issue.path("level").asText())) count++; return count; }
    private static final class AssetHash { private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } }
    private static String safeName(String value) { return value == null ? "logistics.xlsx" : value.replaceAll("[\\r\\n\\\\/]", "_").substring(0, Math.min(255, value.length())); }
    private static String safe(String value) { return value == null ? "未知错误" : value.substring(0, Math.min(200, value.length())); }
}
