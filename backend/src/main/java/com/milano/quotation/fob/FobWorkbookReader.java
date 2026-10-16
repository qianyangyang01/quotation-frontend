package com.milano.quotation.fob;

import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.JsonNodeFactory;
import java.text.Normalizer;
import java.util.*;

/** Read-only, bounded file ingestion. Persistence still uses the FOB preview/confirm transaction. */
public class FobWorkbookReader {
    private static final Map<String, String> HEADERS = new HashMap<>();
    static {
        aliases("sku", "SKU");
        aliases("weightRaw", "克重", "克重/g", "克重(g)", "重量(g)");
        aliases("moqRaw", "起订量", "起订量(件)", "起订量原文");
        aliases("priceRaw", "单价", "采购价格原文");
        aliases("freightRaw", "运费/试拍或议价", "运费原文");
        aliases("category", "类别"); aliases("notes", "备注");
        aliases("quotationOwner", "报价人"); aliases("quotationDate", "报价日期");
        aliases("size", "尺码"); aliases("color", "颜色", "颜色/SKU");
        aliases("material", "材质"); aliases("factoryInfo", "工厂信息");
    }
    private static void aliases(String field, String... labels) { for (var label : labels) HEADERS.put(normalize(label), field); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s*\\u200b]", ""); }
    public record Location(String sheet, int row) {}
    public record Result(List<ObjectNode> rows, List<Location> locations, List<String> notices) {}

    public Result read(MultipartFile file) {
        var name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) throw AppException.unprocessable("请选择 .xlsx 或 .xls 文件");
        if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024) throw AppException.unprocessable("FOB文件须非空且不超过10MB，请拆分后导入");
        try (var input = file.getInputStream(); var book = WorkbookFactory.create(input)) {
            if (book.getNumberOfSheets() > 20) throw AppException.unprocessable("FOB文件最多支持20个工作表，请拆分后导入");
            var formatter = new DataFormatter(Locale.ROOT);
            var rows = new ArrayList<ObjectNode>(); var locations = new ArrayList<Location>();
            var notices = new LinkedHashSet<String>();
            notices.add("仅导入文字与计价资料，Excel图片不导入；公式字段请先在Excel中转换为值。");
            int scanned = 0;
            for (var sheet : book) {
                Map<Integer, String> columns = null;
                boolean nonempty = false;
                for (var row : sheet) {
                    if (++scanned > 10000) throw AppException.unprocessable("工作表行数过多，请移除多余格式行或拆分文件");
                    if (row.getLastCellNum() > 100) throw AppException.unprocessable("工作表列数超过100，请仅保留采购资料列");
                    var cells = new ArrayList<String>();
                    for (int c = 0; c < row.getLastCellNum(); c++) cells.add(formatter.formatCellValue(row.getCell(c)).trim());
                    if (cells.stream().allMatch(String::isBlank)) continue;
                    nonempty = true;
                    if (columns == null) {
                        if (row.getRowNum() >= 20) throw AppException.unprocessable(sheet.getSheetName() + "：前20行未找到SKU表头");
                        if (cells.stream().noneMatch(s -> normalize(s).equals("sku"))) continue;
                        columns = new LinkedHashMap<>();
                        for (int c = 0; c < cells.size(); c++) {
                            var field = HEADERS.get(normalize(cells.get(c)));
                            if (field == null) { if (!cells.get(c).isBlank()) notices.add(sheet.getSheetName() + "：未导入列“" + cells.get(c) + "”"); continue; }
                            if (columns.containsValue(field) && !field.equals("weightRaw") && !field.equals("notes"))
                                throw AppException.unprocessable(sheet.getSheetName() + "：表头重复“" + cells.get(c) + "”");
                            if (!field.equals("notes") || !columns.containsValue(field)) columns.put(c, field);
                        }
                        if (columns.size() < 2) throw AppException.unprocessable(sheet.getSheetName() + "：须包含SKU及至少一个可维护字段");
                        continue;
                    }
                    var patch = JsonNodeFactory.instance.objectNode();
                    for (var entry : columns.entrySet()) {
                        var cell = row.getCell(entry.getKey());
                        if (cell == null) continue;
                        String location = sheet.getSheetName() + " 第" + (row.getRowNum() + 1) + "行";
                        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
                            throw AppException.unprocessable(location + "：公式或错误单元格请转换为有效值后再导入");
                        var value = formatter.formatCellValue(cell).trim();
                        if (value.length() > 10000) throw AppException.unprocessable(location + "：单元格超过10000字");
                        if (!value.isBlank()) patch.put(entry.getValue(), value);
                    }
                    if (patch.isEmpty()) continue;
                    if (patch.path("sku").asText("").isBlank()) throw AppException.unprocessable(sheet.getSheetName() + " 第" + (row.getRowNum() + 1) + "行缺少SKU，本次未保存");
                    rows.add(patch); locations.add(new Location(sheet.getSheetName(), row.getRowNum() + 1));
                    if (rows.size() > 100) throw AppException.unprocessable("FOB每次最多导入100条资料，请拆分文件；本次未保存");
                }
                if (nonempty && columns == null) throw AppException.unprocessable(sheet.getSheetName() + "：未找到SKU表头，请移除说明工作表或补充表头");
            }
            if (rows.isEmpty()) throw AppException.unprocessable("没有找到可导入的FOB资料");
            return new Result(rows, locations, List.copyOf(notices));
        } catch (AppException e) { throw e; }
        catch (Exception e) { throw AppException.unprocessable("Excel文件无法读取，请确认文件未加密且格式完整，另存为.xlsx后重试"); }
    }
}
