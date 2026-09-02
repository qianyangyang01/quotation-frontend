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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void importsMissingRequiredAmountAsPendingTemplate() throws Exception {
        var preview = service.preview(workbook(false), "BUYER01");

        assertTrue(preview.summary().path("canConfirm").asBoolean());
        assertEquals(0, preview.summary().path("errorCount").asInt());
        assertEquals(1, preview.summary().path("pending").asInt());
        assertEquals("pending_template", preview.records().getFirst().path("catalogState").asText());
    }

    @Test
    void mergesInternationalSheetsAndIgnoresDefaultOnlyRows() throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var first=workbook.createSheet("张汝玉");var second=workbook.createSheet("陈晨BK");
            for(var sheet:java.util.List.of(first,second)){var header=sheet.createRow(0);for(int i=0;i<PurchaseWorkbookService.INTERNATIONAL_HEADERS.size();i++)header.createCell(i).setCellValue(PurchaseWorkbookService.INTERNATIONAL_HEADERS.get(i));}
            var row=first.createRow(1);row.createCell(1).setCellValue("2026-08-27");row.createCell(2).setCellValue("张汝玉");row.createCell(4).setCellValue("SKU-INT-1");row.createCell(6).setCellValue("200g左右");row.createCell(13).setCellValue(1);row.createCell(14).setCellValue(10);row.createCell(24).setCellValue(0.08);row.createCell(25).setCellValue("普票");row.createCell(27).setCellValue("有");
            second.createRow(1).createCell(23).setCellValue(0);workbook.write(output);
            var file=new MockMultipartFile("file","international.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());var preview=service.preview(file,"BUYER01");
            assertEquals(1,preview.records().size());assertEquals(2,preview.summary().path("sheetCount").asInt());assertEquals(1,preview.summary().path("ignoredRows").asInt());assertEquals("张汝玉",preview.records().getFirst().path("sourceSheet").asText());assertEquals(0.08,preview.records().getFirst().path("taxPoint").asDouble(),0.0001);
        }
    }

    @Test void groupsDuplicateSkuChoicesBySheetAndRow() throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            for(var name:java.util.List.of("采购一组","采购二组")){var sheet=workbook.createSheet(name);var header=sheet.createRow(0);for(int index=0;index<PurchaseWorkbookService.HEADERS.size();index++)header.createCell(index).setCellValue(PurchaseWorkbookService.HEADERS.get(index));var row=sheet.createRow(1);row.createCell(0).setCellValue("SKU-DUP");row.createCell(8).setCellValue(100);row.createCell(12).setCellValue(1);row.createCell(13).setCellValue(8.5);}
            workbook.write(output);var preview=service.preview(new MockMultipartFile("file","duplicate.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray()),"BUYER01");
            assertFalse(preview.summary().path("canConfirm").asBoolean());assertEquals(0,preview.summary().path("blockingErrorCount").asInt());assertEquals(1,preview.summary().path("duplicateGroups").size());assertEquals(2,preview.summary().path("duplicateGroups").get(0).path("choices").size());
        }
    }

    @Test void standardPreviewDirectsClearlyLegacyHeadersToDedicatedEntry() throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("陈晨");var header=sheet.createRow(0);var headers=new String[]{"SKU","克重/g","1件运费","报价","含票价"};for(int i=0;i<headers.length;i++)header.createCell(i).setCellValue(headers[i]);
            var row=sheet.createRow(1);row.createCell(0).setCellValue("OLD-1");row.createCell(1).setCellValue(70);row.createCell(2).setCellValue(1.7);row.createCell(3).setCellValue(6);row.createCell(4).setCellValue(6.18);workbook.write(output);
            var file=new MockMultipartFile("file","陈晨.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
            assertTrue(assertThrows(com.milano.quotation.common.AppException.class,()->service.preview(file,"BUYER01")).getMessage().contains("旧数据导入"));
        }
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
