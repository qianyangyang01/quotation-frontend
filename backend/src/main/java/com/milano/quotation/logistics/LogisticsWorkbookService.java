package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
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
    static final String[] KEYS = {"areaName", "countryCode", "etaMinDays", "etaMaxDays", "prohibitedMarks", "allowedMarks",
            "maxPerimeterCm", "maxSideCm", "volumeDivisor", "minLengthCm", "maxLengthCm", "minWidthCm", "maxWidthCm", "minSideAreaCm2",
            "maxSideAreaCm2", "weightFromKg", "weightToKg", "startWeightKg", "pricePerKg", "minChargeWeightKg", "firstWeightKg",
            "firstWeightPrice", "nextWeightKg", "nextWeightPrice", "intervalPrice", "registrationFee", "surcharge", "fuelSurchargeRate",
            "specialGoodsContent", "volumetric", "prohibitGeneralCargo", "phoneRequired", "zoneName", "zonePostalPrefix", "zonePostalCode",
            "zoneCity", "zoneState", "zoneExclude"};
    private static final Set<Integer> TEXT = Set.of(0, 1, 4, 5, 28, 32, 33, 34, 35, 36);
    private static final Set<Integer> BOOLEAN = Set.of(29, 30, 31, 37);
    private static final Map<String, String> PRICE_FIELDS = Map.of(
            "pricePerKg", "运费单价", "firstWeightPrice", "首重价", "nextWeightPrice", "续重单价",
            "intervalPrice", "区间运费", "registrationFee", "挂号费", "surcharge", "附加费",
            "fuelSurchargeRate", "燃油附加费率", "linehaulPerKg", "干线费每KG");
    private static final Map<String, String> RANGE_FIELDS = Map.of(
            "weightFromKg", "起始重量", "weightToKg", "截止重量",
            "weightFromInclusive", "下界包含", "weightToInclusive", "上界包含");
    private static final Map<String, String> RULE_FIELDS = Map.ofEntries(
            Map.entry("etaMinDays", "时效最早天数"), Map.entry("etaMaxDays", "时效最晚天数"),
            Map.entry("prohibitedMarks", "禁运商品"), Map.entry("allowedMarks", "允许商品标记"),
            Map.entry("maxPerimeterCm", "三边之和"), Map.entry("maxSideCm", "三边最大长度"),
            Map.entry("volumeDivisor", "计泡系数"), Map.entry("minLengthCm", "最小长度"),
            Map.entry("maxLengthCm", "最大长度"), Map.entry("minWidthCm", "最小宽度"),
            Map.entry("maxWidthCm", "最大宽度"), Map.entry("minSideAreaCm2", "最小侧面积"),
            Map.entry("maxSideAreaCm2", "最大侧面积"), Map.entry("startWeightKg", "起重"),
            Map.entry("minChargeWeightKg", "最小计重"), Map.entry("firstWeightKg", "首重"),
            Map.entry("nextWeightKg", "续重"), Map.entry("specialGoodsContent", "特殊品含量"),
            Map.entry("volumetric", "是否计抛"), Map.entry("currency", "币种"), Map.entry("pricingModel", "计费方式"), Map.entry("prohibitGeneralCargo", "是否禁止普货"),
            Map.entry("phoneRequired", "电话是否必需"), Map.entry("zoneExclude", "排除"),
            Map.entry("billingStepKg", "计费进位KG"), Map.entry("originRegion", "发货区域"),
            Map.entry("notes", "规则备注"), Map.entry("pendingReason", "待适配原因"), Map.entry("quoteReady", "自动报价可用性"));
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
            var comparison = compare(rows, previousRows);
            var result = mapper.createObjectNode(); result.put("fileName", safeName(fileName)); result.put("sourceHash", AssetHash.sha(bytes));
            result.set("rows", rows); result.set("issues", issues); result.put("validRows", rows.size()); result.put("errors", errors);
            result.put("warnings", count(issues, "warning")); result.set("diffRows", comparison.path("diffRows")); result.set("summary", comparison.path("summary")); return result;
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
    public ObjectNode compare(ArrayNode rows, ArrayNode previousRows) {
        var previous = new LinkedHashMap<String,Deque<JsonNode>>();
        previousRows.forEach(row -> previous.computeIfAbsent(comparisonIdentity(row),ignored->new ArrayDeque<>()).add(row));
        var diffRows = mapper.createArrayNode();
        var summary = mapper.createObjectNode().put("added", 0).put("price", 0).put("rule", 0).put("range", 0)
                .put("removed", 0).put("unchanged", 0).put("highRisk", 0).put("coverageReduced", 0);
        var unmatchedNext = new ArrayList<JsonNode>();var keyOccurrences=new LinkedHashMap<String,Integer>();
        for (var row : rows) {
            var key = comparisonIdentity(row);
            var matches=previous.get(key);var before=matches==null?null:matches.pollFirst();
            if(matches!=null&&matches.isEmpty())previous.remove(key);
            if (before == null) unmatchedNext.add(row); else comparedDiff(diffRows, summary, diffKey(key,keyOccurrences), row, before);
        }
        var unmatchedPrevious=previous.values().stream().flatMap(Collection::stream).toList();
        var pairedNext = Collections.newSetFromMap(new IdentityHashMap<JsonNode,Boolean>());var pairedPrevious=Collections.newSetFromMap(new IdentityHashMap<JsonNode,Boolean>());
        var nextGroups = rangeGroups(unmatchedNext); var previousGroups = rangeGroups(unmatchedPrevious);
        for (var entry : nextGroups.entrySet()) {
            var oldRows = previousGroups.get(entry.getKey()); var newRows = entry.getValue();
            if (oldRows == null || oldRows.isEmpty() || oldRows.size() != newRows.size()) continue;
            oldRows.sort(RANGE_ORDER); newRows.sort(RANGE_ORDER);
            for (int index = 0; index < newRows.size(); index++) {
                var row = newRows.get(index); var before = oldRows.get(index);
                if (!rangeChanged(before, row)) continue;
                comparedDiff(diffRows, summary, diffKey(comparisonIdentity(row),keyOccurrences), row, before);
                pairedNext.add(row); pairedPrevious.add(before);
            }
        }
        unmatchedNext.forEach(row -> { if (!pairedNext.contains(row)) simpleDiff(diffRows, summary, diffKey(comparisonIdentity(row),keyOccurrences), row, "added"); });
        unmatchedPrevious.forEach(row -> { if (!pairedPrevious.contains(row)) simpleDiff(diffRows, summary, diffKey(comparisonIdentity(row),keyOccurrences), row, "removed"); });
        return mapper.createObjectNode().set("diffRows", diffRows).set("summary", summary);
    }

    /** Re-validates editable price rows without relying on parser row identifiers. */
    public ArrayNode validateEditableRows(ArrayNode rows) {
        var issues=mapper.createArrayNode();
        var groups=new LinkedHashMap<String,List<JsonNode>>();
        for(var value:rows){
            var row=(ObjectNode)value;var source=row.path("sourceRow").asInt();
            var beforeIssues=issues.size();validateRow(row,source,issues);row.put("rowKey",identity(row));
            for(int i=beforeIssues;i<issues.size();i++)((ObjectNode)issues.get(i)).put("sourceSheet",row.path("sourceSheet").asText()).put("rowKey",row.path("rowKey").asText());
            groups.computeIfAbsent(rangeIdentity(row),ignored->new ArrayList<>()).add(row);
        }
        for(var entry:groups.entrySet()){
            var ordered=entry.getValue();ordered.sort(RANGE_ORDER);
            for(int i=1;i<ordered.size();i++){
                var before=ordered.get(i-1);var current=ordered.get(i);
                var oldTo=before.path("weightToKg").asDouble();var nextFrom=current.path("weightFromKg").asDouble();
                var overlap=nextFrom<oldTo-0.000000001 || (Math.abs(nextFrom-oldTo)<0.000000001&&before.path("weightToInclusive").asBoolean(true)&&current.path("weightFromInclusive").asBoolean(false)==true);
                var gap=nextFrom>oldTo+0.000000001 || (Math.abs(nextFrom-oldTo)<0.000000001&&!before.path("weightToInclusive").asBoolean(true)&&!current.path("weightFromInclusive").asBoolean(false));
                if(overlap||gap){
                    var issue=issues.addObject().put("row",current.path("sourceRow").asInt()).put("field","重量连续性")
                            .put("code",overlap?"WEIGHT_OVERLAP":"WEIGHT_GAP").put("level","error")
                            .put("message",overlap?"与上一重量档重叠":"与上一重量档存在断档")
                            .put("sourceSheet",current.path("sourceSheet").asText()).put("rowKey",current.path("rowKey").asText()).put("relatedRowKey",before.path("rowKey").asText());
                    issue.putObject("suggestedFields").put("weightFromKg",oldTo).put("weightFromInclusive",!before.path("weightToInclusive").asBoolean(true));
                }
            }
        }
        return issues;
    }

    public String rowIdentity(JsonNode row){return identity(row);}
    private static final Comparator<JsonNode> RANGE_ORDER = Comparator.comparingDouble((JsonNode row)->row.path("weightFromKg").asDouble())
            .thenComparingDouble(row->row.path("weightToKg").asDouble());
    private static Map<String,List<JsonNode>> rangeGroups(Collection<JsonNode> rows) {
        var groups=new LinkedHashMap<String,List<JsonNode>>();
        for(var row:rows)groups.computeIfAbsent(rangeIdentity(row),ignored->new ArrayList<>()).add(row);
        return groups;
    }
    private void simpleDiff(ArrayNode diffs,ObjectNode summary,String key,JsonNode row,String type) {
        var diff=diffs.addObject().put("key",key).put("type",type).put("risk",false);diff.set("row",row);
        if(type.equals("removed"))diff.set("previous",row);diff.putArray("changes");diff.putArray("kinds").add(type);diff.putNull("maxPercentChange");increment(summary,type);
    }
    private void comparedDiff(ArrayNode diffs,ObjectNode summary,String key,JsonNode row,JsonNode before) {
        var diff=diffs.addObject().put("key",key);diff.set("row",row);diff.set("previous",before);var changes=diff.putArray("changes");
        var kinds=new LinkedHashSet<String>();double maxPercent=0;boolean hasPercent=false;
        for(var field:RANGE_FIELDS.entrySet())if(!same(before.path(field.getKey()),row.path(field.getKey()))){
            var change=changes.addObject().put("field",field.getValue()).put("kind","range").put("price",false);
            copyValue(change,"before",before.path(field.getKey()));copyValue(change,"after",row.path(field.getKey()));kinds.add("range");
        }
        for(var field:PRICE_FIELDS.entrySet())if(!same(before.path(field.getKey()),row.path(field.getKey()))){
            var change=changes.addObject().put("field",field.getValue()).put("kind","price").put("price",true);
            copyValue(change,"before",before.path(field.getKey()));copyValue(change,"after",row.path(field.getKey()));
            var oldValue=before.path(field.getKey()).asDouble();var nextValue=row.path(field.getKey()).asDouble();
            change.put("delta",BigDecimal.valueOf(nextValue).subtract(BigDecimal.valueOf(oldValue)));
            if(oldValue!=0){var percent=(nextValue-oldValue)/oldValue*100;change.put("percentChange",percent);maxPercent=Math.max(maxPercent,Math.abs(percent));hasPercent=true;}else change.putNull("percentChange");
            kinds.add("price");
        }
        for(var field:RULE_FIELDS.entrySet())if(!same(before.path(field.getKey()),row.path(field.getKey()))){
            var change=changes.addObject().put("field",field.getValue()).put("kind","rule").put("price",false);
            copyValue(change,"before",before.path(field.getKey()));copyValue(change,"after",row.path(field.getKey()));kinds.add("rule");
        }
        var kindArray=diff.putArray("kinds");kinds.forEach(kindArray::add);
        var type=kinds.contains("range")?"range":kinds.contains("price")?"price":kinds.contains("rule")?"rule":"unchanged";
        var reduced=kinds.contains("range")&&coverageReduced(before,row);var priceRisk=hasPercent&&maxPercent>10;
        diff.put("type",type).put("risk",priceRisk||reduced);if(hasPercent)diff.put("maxPercentChange",maxPercent);else diff.putNull("maxPercentChange");
        if(kinds.isEmpty()){kindArray.add("unchanged");increment(summary,"unchanged");}
        else for(var kind:kinds)increment(summary,kind);
        if(priceRisk)increment(summary,"highRisk");if(reduced)increment(summary,"coverageReduced");
    }
    private static boolean rangeChanged(JsonNode before,JsonNode row){for(var field:RANGE_FIELDS.keySet())if(!same(before.path(field),row.path(field)))return true;return false;}
    private static boolean coverageReduced(JsonNode before,JsonNode row){
        var oldFrom=before.path("weightFromKg").asDouble();var nextFrom=row.path("weightFromKg").asDouble();
        var oldTo=before.path("weightToKg").asDouble();var nextTo=row.path("weightToKg").asDouble();
        var lowerLost=nextFrom>oldFrom+0.000000001||(Math.abs(nextFrom-oldFrom)<0.000000001&&before.path("weightFromInclusive").asBoolean(false)&&!row.path("weightFromInclusive").asBoolean(false));
        var upperLost=nextTo<oldTo-0.000000001||(Math.abs(nextTo-oldTo)<0.000000001&&before.path("weightToInclusive").asBoolean(true)&&!row.path("weightToInclusive").asBoolean(true));
        return lowerLost||upperLost;
    }
    private static void increment(ObjectNode summary, String field) { summary.put(field, summary.path(field).asInt() + 1); }
    private static String diffKey(String businessKey,Map<String,Integer> occurrences){var index=occurrences.merge(businessKey,1,Integer::sum);return index==1?businessKey:businessKey+"#"+index;}
    private static boolean same(tools.jackson.databind.JsonNode left, tools.jackson.databind.JsonNode right) {
        if (left.isNumber() || right.isNumber()) return Math.abs(left.asDouble() - right.asDouble()) < 0.000000001;
        return Objects.equals(left.asText(), right.asText());
    }
    private static void copyValue(ObjectNode target, String field, tools.jackson.databind.JsonNode value) {
        if (value.isBoolean()) target.put(field, value.asBoolean()); else if (value.isNumber()) target.put(field, value.decimalValue()); else target.put(field, value.asText());
    }
    private static String comparisonIdentity(JsonNode row) {
        var hasBusinessIdentity=!row.path("countryCode").asText().isBlank()||!row.path("areaName").asText().isBlank();
        if(!hasBusinessIdentity&&!row.path("rowKey").asText().isBlank())return row.path("rowKey").asText();
        return identity(row);
    }
    private static String identity(JsonNode row) { return rangeIdentity(row)+"|"+row.path("weightFromKg").asText()+"|"+row.path("weightToKg").asText(); }
    private static String rangeIdentity(JsonNode row) {
        var effectiveOrigin=row.path("originRegion").asText();
        if(effectiveOrigin.isBlank())effectiveOrigin=row.path("sourceOriginRegion").asText();
        return String.join("|", row.path("countryCode").asText(), row.path("areaName").asText(), row.path("zoneName").asText(), row.path("zonePostalPrefix").asText(), row.path("zonePostalCode").asText(), row.path("zoneCity").asText(), row.path("zoneState").asText(), effectiveOrigin).toLowerCase(Locale.ROOT);
    }
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
