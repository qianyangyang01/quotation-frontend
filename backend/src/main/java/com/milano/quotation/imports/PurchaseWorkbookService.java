package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductRepository;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class PurchaseWorkbookService {
    public static final List<String> HEADERS = List.of(
            "SKU*", "类别*", "产品图片（嵌入本格）", "实物图（嵌入本格）", "报价人*", "报价日期*", "尺码", "颜色",
            "克重(g)*", "长(cm)*", "宽(cm)*", "高(cm)*", "起订量(件)*", "基准采购单价(CNY/件)*", "阶梯价2起订量",
            "阶梯价2(CNY/件)", "阶梯价3起订量", "阶梯价3(CNY/件)", "1件总运费(CNY)", "10件总运费(CNY)",
            "100件总运费(CNY)", "是否包邮", "含票价(CNY/件)", "票类型", "是否有货*", "备注", "工厂信息", "货源链接1",
            "货源链接2", "货源链接3", "相似货源", "审核备注");
    private static final Set<String> REQUIRED_NUMBERS = Set.of("克重(g)*", "起订量(件)*", "基准采购单价(CNY/件)*");
    private static final Pattern CURRENCY = Pattern.compile("(?i)(CNY|RMB)");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ImportJobRepository jobs;
    private final PurchaseImportRowRepository rows;
    private final PurchaseProductRepository products;
    private final AssetStorageService storage;
    private final ObjectMapper mapper;

    public PurchaseWorkbookService(ImportJobRepository jobs, PurchaseImportRowRepository rows,
                                   PurchaseProductRepository products, AssetStorageService storage, ObjectMapper mapper) {
        this.jobs = jobs;
        this.rows = rows;
        this.products = products;
        this.storage = storage;
        this.mapper = mapper;
    }

    @Transactional
    public Preview preview(MultipartFile file, String actor) {
        validateFile(file);
        try (var workbook = new XSSFWorkbook(file.getInputStream())) {
            var sheet = workbook.getSheet("采购产品导入");
            if (sheet == null) throw AppException.unprocessable("没有找到工作表“采购产品导入”");
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            validateHeaders(sheet, evaluator);
            var pictures = pictures(sheet);
            var issues = mapper.createArrayNode();
            var parsed = new ArrayList<ParsedRow>();
            var jobId = UUID.randomUUID();
            var seen = new HashSet<String>();
            int skipped = 0, generated = 0, productImages = 0, physicalImages = 0;
            var now = LocalDateTime.now(BUSINESS_ZONE);

            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                int excelRow = index + 1;
                if (empty(row, evaluator) && !pictures.containsKey(excelRow + ":2") && !pictures.containsKey(excelRow + ":3")) continue;
                var rowIssues = mapper.createArrayNode();
                var sku = text(row, 0, evaluator).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
                if (sku.isBlank()) {
                    sku = "AUTO-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-R" + excelRow;
                    generated++;
                    addIssue(issues, rowIssues, excelRow, "SKU*", "SKU为空，已生成" + sku + "，必须修改后才能参与报价", "warning");
                }
                if (!seen.add(sku)) {
                    addIssue(issues, rowIssues, excelRow, "SKU*", "同一文件内SKU " + sku + "重复，本行已跳过", "error");
                    skipped++;
                    continue;
                }
                var payload = record(row, excelRow, sku, evaluator, rowIssues, issues);
                UUID productAsset = null, physicalAsset = null;
                var productPicture = pictures.get(excelRow + ":2");
                if (productPicture != null) {
                    var asset = storage.storeTemporaryImage(productPicture.getData(), sku + "-product." + productPicture.suggestFileExtension(), jobId);
                    productAsset = asset.id;
                    payload.put("productImage", "/api/v1/assets/" + asset.id);
                    payload.put("image", "/api/v1/assets/" + asset.id);
                    productImages++;
                }
                var physicalPicture = pictures.get(excelRow + ":3");
                if (physicalPicture != null) {
                    var asset = storage.storeTemporaryImage(physicalPicture.getData(), sku + "-physical." + physicalPicture.suggestFileExtension(), jobId);
                    physicalAsset = asset.id;
                    payload.put("physicalImage", "/api/v1/assets/" + asset.id);
                    physicalImages++;
                }
                parsed.add(new ParsedRow(excelRow, sku, payload, productAsset, physicalAsset));
            }
            if (parsed.isEmpty()) throw AppException.unprocessable("模板中没有可导入的采购数据");
            long errorCount = count(issues, "error"), warningCount = count(issues, "warning");
            var summary = mapper.createObjectNode();
            summary.put("totalRows", parsed.size() + skipped);
            summary.put("added", parsed.stream().filter(item -> products.findBySku(item.sku()).isEmpty()).count());
            summary.put("updated", parsed.stream().filter(item -> products.findBySku(item.sku()).isPresent()).count());
            summary.put("generatedSku", generated);
            summary.put("productImages", productImages);
            summary.put("physicalImages", physicalImages);
            summary.put("skipped", skipped);
            summary.put("errorCount", errorCount);
            summary.put("warningCount", warningCount);
            summary.put("canConfirm", errorCount == 0);
            summary.set("issues", issues);

            var job = new ImportJob();
            job.id = jobId;
            job.jobType = "purchase-xlsx";
            job.status = "preview";
            job.requestedBy = actor;
            job.sourceName = safeName(file.getOriginalFilename());
            job.sourceHash = sha(file);
            job.payload = summary;
            job.createdAt = Instant.now();
            job.updatedAt = job.createdAt;
            jobs.save(job);
            var saved = new ArrayList<PurchaseImportRow>();
            for (var item : parsed) {
                var entity = new PurchaseImportRow();
                entity.id = UUID.randomUUID();
                entity.jobId = job.id;
                entity.sourceRow = item.sourceRow();
                entity.sku = item.sku();
                entity.payload = item.payload();
                entity.productAssetId = item.productAssetId();
                entity.physicalAssetId = item.physicalAssetId();
                entity.createdAt = Instant.now();
                saved.add(entity);
            }
            rows.saveAll(saved);
            return new Preview(job.id, job.sourceName, parsed.stream().map(ParsedRow::payload).toList(), issues, summary);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw AppException.unprocessable("Excel文件解析失败：" + safeMessage(exception));
        }
    }

    private static void validateFile(MultipartFile file) {
        if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw AppException.unprocessable("请选择.xlsx格式的采购模板");
        if (file.getSize() > 30L * 1024 * 1024) throw AppException.unprocessable("Excel文件不能超过30MB");
    }

    private void validateHeaders(Sheet sheet, FormulaEvaluator evaluator) {
        var row = sheet.getRow(0);
        var mismatch = new ArrayList<String>();
        for (int index = 0; index < HEADERS.size(); index++)
            if (!HEADERS.get(index).equals(text(row, index, evaluator))) mismatch.add(column(index) + "列应为“" + HEADERS.get(index) + "”");
        if (!mismatch.isEmpty()) throw AppException.unprocessable("模板列头不匹配：" + String.join("；", mismatch.subList(0, Math.min(4, mismatch.size()))) + (mismatch.size() > 4 ? "…" : ""));
    }

    private Map<String, XSSFPictureData> pictures(XSSFSheet sheet) {
        var result = new HashMap<String, XSSFPictureData>();
        var drawing = sheet.getDrawingPatriarch();
        if (drawing == null) return result;
        for (var shape : drawing.getShapes()) if (shape instanceof XSSFPicture picture) {
            var anchor = picture.getClientAnchor();
            int column = anchor.getCol1(), row = anchor.getRow1() + 1;
            if ((column == 2 || column == 3) && row >= 2) result.put(row + ":" + column, picture.getPictureData());
        }
        return result;
    }

    private ObjectNode record(Row row, int sourceRow, String sku, FormulaEvaluator evaluator, ArrayNode rowIssues, ArrayNode issues) {
        var object = mapper.createObjectNode();
        object.put("sourceRow", sourceRow);
        object.put("sku", sku);
        object.put("skuOrigin", sku.startsWith("AUTO-") ? "system" : "imported");
        object.put("category", requiredText(row, 1, sourceRow, "类别*", evaluator, rowIssues, issues));
        object.put("productImage", ""); object.put("physicalImage", "");
        object.put("quotationOwner", requiredText(row, 4, sourceRow, "报价人*", evaluator, rowIssues, issues));
        object.put("quotationDate", date(row == null ? null : row.getCell(5), sourceRow, evaluator, rowIssues, issues));
        object.put("size", text(row, 6, evaluator)); object.put("color", text(row, 7, evaluator));
        put(object, "weightG", number(row, 8, sourceRow, "克重(g)*", evaluator, rowIssues, issues));
        put(object, "lengthCm", number(row, 9, sourceRow, "长(cm)*", evaluator, rowIssues, issues));
        put(object, "widthCm", number(row, 10, sourceRow, "宽(cm)*", evaluator, rowIssues, issues));
        put(object, "heightCm", number(row, 11, sourceRow, "高(cm)*", evaluator, rowIssues, issues));
        put(object, "minOrderQty", integer(row, 12, sourceRow, "起订量(件)*", evaluator, rowIssues, issues));
        put(object, "purchasePriceCny", number(row, 13, sourceRow, "基准采购单价(CNY/件)*", evaluator, rowIssues, issues));
        put(object, "tier2MinQty", integer(row, 14, sourceRow, "阶梯价2起订量", evaluator, rowIssues, issues));
        put(object, "tier2PriceCny", number(row, 15, sourceRow, "阶梯价2(CNY/件)", evaluator, rowIssues, issues));
        put(object, "tier3MinQty", integer(row, 16, sourceRow, "阶梯价3起订量", evaluator, rowIssues, issues));
        put(object, "tier3PriceCny", number(row, 17, sourceRow, "阶梯价3(CNY/件)", evaluator, rowIssues, issues));
        put(object, "singleFreightCny", number(row, 18, sourceRow, "1件总运费(CNY)", evaluator, rowIssues, issues));
        put(object, "freight10Cny", number(row, 19, sourceRow, "10件总运费(CNY)", evaluator, rowIssues, issues));
        put(object, "freight100Cny", number(row, 20, sourceRow, "100件总运费(CNY)", evaluator, rowIssues, issues));
        object.put("freeShipping", choice(text(row, 21, evaluator), Set.of("是", "否"), sourceRow, "是否包邮", rowIssues, issues, false));
        put(object, "taxIncludedPriceCny", number(row, 22, sourceRow, "含票价(CNY/件)", evaluator, rowIssues, issues));
        object.put("invoiceType", text(row, 23, evaluator));
        object.put("stockStatus", choice(text(row, 24, evaluator), Set.of("有货", "无货", "待确认"), sourceRow, "是否有货*", rowIssues, issues, true));
        String[] keys = {"notes", "factoryInfo", "sourceLink1", "sourceLink2", "sourceLink3", "similarSource", "auditNotes"};
        for (int i = 0; i < keys.length; i++) object.put(keys[i], text(row, 25 + i, evaluator));
        object.set("importWarnings", rowIssues);
        normalizeAliases(object);
        return object;
    }

    private void normalizeAliases(ObjectNode object) {
        var sku = object.path("sku").asText(); var weight = object.get("weightG"); var minimum = object.get("minOrderQty"); var price = object.get("purchasePriceCny");
        boolean ready = !sku.startsWith("AUTO-") && positive(weight) && positive(minimum) && price != null && !price.isNull() && price.asDouble() >= 0;
        object.put("quoteReady", ready); object.put("status", sku.startsWith("AUTO-") ? "系统生成SKU，待修改" : ready ? "资料完整" : "待补充资料");
        object.put("name", object.path("category").asText().isBlank() ? "商品 " + sku : object.path("category").asText());
        object.put("image", object.path("productImage").asText());
        if (weight == null || weight.isNull()) { object.putNull("weightKg"); object.put("weightDescription", ""); }
        else { object.put("weightKg", weight.asDouble() / 1000); object.put("weightDescription", weight.asText()); }
        object.put("colorSku", object.path("color").asText());
        for (var field : List.of("material", "marks", "rawTierPrice", "l6Price", "freightTrial", "invoiceInfo", "taxPoint", "otherNotes", "more")) object.put(field, "");
        object.putArray("shippingMarks"); object.put("taxIncludedPrice", object.path("taxIncludedPriceCny").isNumber() ? object.path("taxIncludedPriceCny").asText() : "");
        object.put("taxDifference", object.path("invoiceType").asText()); object.put("packagingInfo", object.path("factoryInfo").asText());
        var links = object.putArray("sourceLinks"); for (var key : List.of("sourceLink1", "sourceLink2", "sourceLink3", "similarSource")) links.add(object.path(key).asText());
        var candidates = new ArrayList<double[]>(); addTier(candidates, object, "minOrderQty", "purchasePriceCny"); addTier(candidates, object, "tier2MinQty", "tier2PriceCny"); addTier(candidates, object, "tier3MinQty", "tier3PriceCny");
        candidates.sort(Comparator.comparingDouble(item -> item[0])); var tiers = object.putArray("priceTiers");
        for (int index = 0; index < candidates.size(); index++) { var tier = tiers.addObject(); tier.put("minQty", (long)candidates.get(index)[0]); if (index + 1 < candidates.size()) tier.put("maxQty", (long)candidates.get(index + 1)[0] - 1); else tier.putNull("maxQty"); tier.put("unitPriceCny", candidates.get(index)[1]); tier.put("source", index == 0 ? "基准采购单价" : "阶梯价" + (index + 1)); }
    }

    private static void addTier(List<double[]> list, ObjectNode object, String quantity, String price) { if (positive(object.get(quantity)) && object.get(price) != null && !object.get(price).isNull() && object.get(price).asDouble() >= 0) list.add(new double[]{object.get(quantity).asDouble(), object.get(price).asDouble()}); }
    private static boolean positive(JsonNode node) { return node != null && !node.isNull() && node.asDouble() > 0; }
    private static void put(ObjectNode object, String key, BigDecimal value) { if (value == null) object.putNull(key); else object.put(key, value); }
    private static String requiredText(Row row, int cell, int sourceRow, String field, FormulaEvaluator evaluator, ArrayNode rowIssues, ArrayNode issues) { var value = text(row, cell, evaluator); if (value.isBlank()) addIssue(issues, rowIssues, sourceRow, field, field + "不能为空", "error"); return value; }

    static BigDecimal number(Row row, int cellIndex, int sourceRow, String field, FormulaEvaluator evaluator, ArrayNode rowIssues, ArrayNode issues) {
        var cell = row == null ? null : row.getCell(cellIndex); boolean required = REQUIRED_NUMBERS.contains(field);
        if (cell == null || cell.getCellType() == CellType.BLANK) { if (required) addIssue(issues, rowIssues, sourceRow, field, field + "不能为空", "error"); return null; }
        try {
            BigDecimal value;
            if (cell.getCellType() == CellType.NUMERIC) value = BigDecimal.valueOf(cell.getNumericCellValue());
            else if (cell.getCellType() == CellType.FORMULA) { CellValue evaluated = evaluator.evaluate(cell); value = evaluated != null && evaluated.getCellType() == CellType.NUMERIC ? BigDecimal.valueOf(evaluated.getNumberValue()) : parseNumber(evaluated == null ? "" : evaluated.getStringValue()); }
            else value = parseNumber(new DataFormatter(Locale.CHINA).formatCellValue(cell, evaluator));
            if (value == null) { if (required) addIssue(issues, rowIssues, sourceRow, field, field + "不能为空", "error"); return null; }
            if (value.signum() < 0) throw new NumberFormatException(); return value.stripTrailingZeros();
        } catch (Exception exception) { addIssue(issues, rowIssues, sourceRow, field, "“" + new DataFormatter(Locale.CHINA).formatCellValue(cell, evaluator).trim() + "”不是有效非负数字", required ? "error" : "warning"); return null; }
    }

    private static BigDecimal parseNumber(String raw) { if (raw == null || raw.isBlank()) return null; var value = CURRENCY.matcher(raw).replaceAll("").replace("¥", "").replace("￥", "").replace(",", "").replace("，", "").replace("\u00A0", "").replaceAll("\\s+", "").trim(); return value.isBlank() ? null : new BigDecimal(value); }
    private static BigDecimal integer(Row row, int cell, int sourceRow, String field, FormulaEvaluator evaluator, ArrayNode rowIssues, ArrayNode issues) { var value = number(row, cell, sourceRow, field, evaluator, rowIssues, issues); if (value == null) return null; var normalized = value.stripTrailingZeros(); if (normalized.scale() > 0) { addIssue(issues, rowIssues, sourceRow, field, "数量必须为整数", REQUIRED_NUMBERS.contains(field) ? "error" : "warning"); return null; } return normalized; }
    private static String choice(String value, Set<String> allowed, int row, String field, ArrayNode rowIssues, ArrayNode issues, boolean required) { if (value.isBlank()) { if (required) addIssue(issues, rowIssues, row, field, field + "不能为空", "error"); return ""; } if (allowed.contains(value)) return value; addIssue(issues, rowIssues, row, field, "“" + value + "”不在可选值中", required ? "error" : "warning"); return ""; }
    private static String date(Cell cell, int row, FormulaEvaluator evaluator, ArrayNode rowIssues, ArrayNode issues) { if (cell == null || cell.getCellType() == CellType.BLANK) { addIssue(issues, rowIssues, row, "报价日期*", "报价日期*不能为空", "error"); return ""; } try { if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate().toString(); var value = new DataFormatter(Locale.CHINA).formatCellValue(cell, evaluator).trim().replace('/', '-').replace('.', '-'); return LocalDate.parse(value).toString(); } catch (Exception exception) { addIssue(issues, rowIssues, row, "报价日期*", "无法识别报价日期", "error"); return ""; } }
    private static String text(Row row, int cell, FormulaEvaluator evaluator) { return row == null ? "" : new DataFormatter(Locale.CHINA).formatCellValue(row.getCell(cell), evaluator).trim(); }
    private static boolean empty(Row row, FormulaEvaluator evaluator) { if (row == null) return true; for (int index = 0; index < HEADERS.size(); index++) if (!text(row, index, evaluator).isBlank()) return false; return true; }
    private static void addIssue(ArrayNode issues, ArrayNode rowIssues, int row, String field, String message, String level) { var item = issues.addObject(); item.put("row", row); item.put("field", field); item.put("message", message); item.put("level", level); rowIssues.add(message); }
    private static long count(ArrayNode issues, String level) { long count = 0; for (var issue : issues) if (level.equals(issue.path("level").asText())) count++; return count; }
    private static String column(int index) { var result = new StringBuilder(); for (int value = index + 1; value > 0; value = (value - 1) / 26) result.insert(0, (char)('A' + (value - 1) % 26)); return result.toString(); }
    private static String sha(MultipartFile file) throws Exception { try (InputStream input = file.getInputStream()) { var digest = java.security.MessageDigest.getInstance("SHA-256"); input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); return java.util.HexFormat.of().formatHex(digest.digest()); } }
    private static String safeName(String value) { var cleaned = value.replaceAll("[\\r\\n\\\\/]", "_"); return cleaned.substring(0, Math.min(255, cleaned.length())); }
    private static String safeMessage(Exception exception) { var message = exception.getMessage(); return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(200, message.length())); }
    private record ParsedRow(int sourceRow, String sku, ObjectNode payload, UUID productAssetId, UUID physicalAssetId) {}
    public record Preview(UUID jobId, String fileName, List<ObjectNode> records, ArrayNode issues, ObjectNode summary) {}
}
