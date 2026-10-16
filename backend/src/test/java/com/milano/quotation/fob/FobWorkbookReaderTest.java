package com.milano.quotation.fob;

import com.milano.quotation.common.AppException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FobWorkbookReaderTest {
    final FobWorkbookReader reader = new FobWorkbookReader();
    MockMultipartFile file(Workbook book, String extension) throws Exception {
        var out = new ByteArrayOutputStream(); book.write(out);
        return new MockMultipartFile("file", "采购表." + extension, "application/octet-stream", out.toByteArray());
    }
    void rows(Workbook book, String name, String[][] values) {
        var sheet = book.createSheet(name);
        for (int i = 0; i < values.length; i++) {
            var row = sheet.createRow(i);
            for (int j = 0; j < values[i].length; j++) row.createCell(j).setCellValue(values[i][j]);
        }
    }
    @Test void readsBothExcelFormatsAndPreservesMultilineTiersAndSourceLocations() throws Exception {
        for (var extension : new String[]{"xlsx", "xls"}) {
            try (Workbook book = extension.equals("xlsx") ? new XSSFWorkbook() : new HSSFWorkbook()) {
                rows(book, "批发采购", new String[][]{{"采购表"}, {"SKU", "起订量", "单价", "运费原文", "产品图片"}, {"PF2600100", "100件起订;10的倍数下单", "100-499件单价12.5\n500件起单价11.8", "100件运费35", ""}});
                var result = reader.read(file(book, extension));
                assertEquals(1, result.rows().size()); assertEquals(3, result.locations().getFirst().row());
                assertEquals("批发采购", result.locations().getFirst().sheet());
                var row = result.rows().getFirst();
                var parsed = FobSourceParser.parse(row.path("moqRaw").asText(), row.path("priceRaw").asText(), row.path("freightRaw").asText());
                assertEquals(2, parsed.priceTiers().size()); assertEquals(10, parsed.orderMultiple());
                assertTrue(result.notices().stream().anyMatch(n -> n.contains("未导入列")));
                assertFalse(row.has("productImage"));
            }
        }
    }
    @Test void preservesDuplicateSkuRowsForExistingConflictPreview() throws Exception {
        try (var book = new XSSFWorkbook()) {
            rows(book, "A", new String[][]{{"SKU", "单价"}, {"PF2600100", "12"}});
            rows(book, "B", new String[][]{{"SKU", "单价"}, {"PF2600100", "15"}});
            var result = reader.read(file(book, "xlsx"));
            assertEquals(2, result.rows().size()); assertEquals("B", result.locations().getLast().sheet());
        }
    }
    @Test void rejectsMissingSkuAndUnknownSheetWithoutPartiallyImporting() throws Exception {
        for (var values : new String[][][]{ {{"SKU", "单价"}, {"", "12"}}, {{"说明"}, {"不含SKU表头"}} }) {
            try (var book = new XSSFWorkbook()) { rows(book, "错误", values); assertThrows(AppException.class, () -> reader.read(file(book, "xlsx"))); }
        }
    }
    @Test void rejectsAmbiguousHeadersFormulaErrorsAndOverLimitBatches() throws Exception {
        try (var book = new XSSFWorkbook()) {
            rows(book, "重复", new String[][]{{"SKU", "单价", "采购价格原文"}, {"PF2600100", "12", "13"}});
            assertThrows(AppException.class, () -> reader.read(file(book, "xlsx")));
        }
        try (var book = new XSSFWorkbook()) {
            rows(book, "公式", new String[][]{{"SKU", "单价"}, {"PF2600100", "12"}});
            book.getSheetAt(0).getRow(1).getCell(1).setCellFormula("1+2");
            assertThrows(AppException.class, () -> reader.read(file(book, "xlsx")));
        }
        try (var book = new XSSFWorkbook()) {
            rows(book, "过多", new String[][]{{"SKU", "单价"}});
            for (int i = 1; i <= 101; i++) { var row = book.getSheetAt(0).createRow(i); row.createCell(0).setCellValue("PF" + i); row.createCell(1).setCellValue(12); }
            assertThrows(AppException.class, () -> reader.read(file(book, "xlsx")));
        }
        assertThrows(AppException.class, () -> reader.read(new MockMultipartFile("file","a.csv","text/csv","SKU,单价".getBytes())));
        assertThrows(AppException.class, () -> reader.read(new MockMultipartFile("file","a.xlsx","application/octet-stream",new byte[0])));
    }
}
