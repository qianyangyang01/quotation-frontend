package com.milano.quotation.imports;

import com.milano.quotation.purchase.PurchaseProductRepository;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PurchaseWorkbookServiceTest {
    private ImportJobRepository jobs;
    private PurchaseImportRowRepository rows;
    private PurchaseProductRepository products;
    private PurchaseWorkbookService service;

    @BeforeEach
    void setUp() {
        jobs = mock(ImportJobRepository.class);
        rows = mock(PurchaseImportRowRepository.class);
        products = mock(PurchaseProductRepository.class);
        when(products.findBySku(any())).thenReturn(Optional.empty());
        service = new PurchaseWorkbookService(jobs, rows, products, mock(AssetStorageService.class),
                JsonMapper.builder().build());
    }

    @Test
    void parsesCurrencyStringsAndFormulaCellsWithoutFalseWarnings() throws Exception {
        var preview = service.preview(workbook(true), "BUYER01");

        assertTrue(preview.summary().path("canConfirm").asBoolean());
        assertEquals(0, preview.summary().path("errorCount").asInt());
        assertEquals(0, preview.summary().path("warningCount").asInt());
        assertEquals("54.91", preview.records().getFirst().path("purchasePriceCny").asText());
        assertEquals(20.0, preview.records().getFirst().path("freight10Cny").asDouble());
    }

    @Test
    void marksMissingRequiredAmountAsBlockingError() throws Exception {
        var preview = service.preview(workbook(false), "BUYER01");

        assertFalse(preview.summary().path("canConfirm").asBoolean());
        assertEquals(1, preview.summary().path("errorCount").asInt());
        assertEquals("error", preview.issues().get(0).path("level").asText());
    }

    private MockMultipartFile workbook(boolean includePrice) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("采购产品导入");
            var header = sheet.createRow(0);
            for (int index = 0; index < PurchaseWorkbookService.HEADERS.size(); index++) {
                header.createCell(index).setCellValue(PurchaseWorkbookService.HEADERS.get(index));
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("SKU-001");
            row.createCell(1).setCellValue("测试商品");
            row.createCell(4).setCellValue("采购员");
            row.createCell(5).setCellValue("2026-08-22");
            row.createCell(8).setCellValue(500);
            row.createCell(9).setCellValue(10);
            row.createCell(10).setCellValue(20);
            row.createCell(11).setCellValue(30);
            row.createCell(12).setCellValue(1);
            if (includePrice) row.createCell(13).setCellValue(" CNY ¥54.91 ");
            row.createCell(18).setCellFormula("5*2");
            row.createCell(19).setCellValue("￥20.00");
            row.createCell(24).setCellValue("有货");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            return new MockMultipartFile("file", "purchase.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
