package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LogisticsWorkbookServiceTest {
    private final LogisticsWorkbookService service = new LogisticsWorkbookService(JsonMapper.builder().build());

    @Test
    void reparsesFormulaAndCurrencyOnServer() throws Exception {
        var result = service.parse(workbook(true), JsonMapper.builder().build().createArrayNode());
        assertEquals(1, result.path("validRows").asInt());
        assertEquals(0, result.path("errors").asInt());
        assertEquals(54.91, result.path("rows").get(0).path("pricePerKg").asDouble());
        assertEquals(2.0, result.path("rows").get(0).path("weightToKg").asDouble());
        assertEquals(16.0, result.path("rows").get(0).path("registrationFee").asDouble());
    }

    @Test
    void keepsInvalidWeightRangeInBlockedDraftPreview() throws Exception {
        var result = service.parse(workbook(false), JsonMapper.builder().build().createArrayNode());
        assertEquals(1, result.path("validRows").asInt());
        assertEquals(1, result.path("errors").asInt());
        assertEquals("重量区间", result.path("issues").get(0).path("field").asText());
    }

    @Test
    void fallsBackToCachedValueForUnsupportedExternalFormula() throws Exception {
        var result = service.parse(externalFormulaWorkbook(), JsonMapper.builder().build().createArrayNode());
        assertEquals(1, result.path("validRows").asInt());
        assertEquals(0, result.path("errors").asInt());
        assertEquals("US", result.path("rows").get(0).path("countryCode").asText());
    }

    @Test
    void parsesSumaoWorkbookWithTwoInstructionRows() throws Exception {
        var result = service.parse(sumaoWorkbook(), JsonMapper.builder().build().createArrayNode());

        assertEquals(1, result.path("validRows").asInt());
        assertEquals("US", result.path("rows").get(0).path("countryCode").asText());
        assertEquals(54.91, result.path("rows").get(0).path("pricePerKg").asDouble());
        assertEquals(4, result.path("rows").get(0).path("sourceRow").asInt());
    }

    private MockMultipartFile workbook(boolean valid) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("物流价格"); var header = sheet.createRow(0);
            for (int index = 0; index < LogisticsWorkbookService.HEADERS.size(); index++) header.createCell(index).setCellValue(LogisticsWorkbookService.HEADERS.get(index));
            var row = sheet.createRow(1); row.createCell(0).setCellValue("美国区"); row.createCell(1).setCellValue("US");
            row.createCell(2).setCellValue(7); row.createCell(3).setCellValue(12); row.createCell(15).setCellValue(0);
            row.createCell(16).setCellFormula(valid ? "1+1" : "0"); row.createCell(18).setCellValue("CNY ¥54.91"); row.createCell(25).setCellValue("\u00A016.00\u00A0");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll(); workbook.write(output);
            return new MockMultipartFile("file", "logistics.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile sumaoWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(1).setCellValue("多个国家用,隔开");
            sheet.createRow(1).createCell(0).setCellValue("必填");
            var header = sheet.createRow(2);
            for (int index = 0; index < LogisticsWorkbookService.HEADERS.size(); index++) header.createCell(index).setCellValue(LogisticsWorkbookService.HEADERS.get(index));
            var row = sheet.createRow(3); row.createCell(0).setCellValue("美国"); row.createCell(1).setCellValue("US");
            row.createCell(2).setCellValue(7); row.createCell(3).setCellValue(12); row.createCell(15).setCellValue(0);
            row.createCell(16).setCellValue(2); row.createCell(18).setCellValue("CNY ¥54.91");
            workbook.write(output);
            return new MockMultipartFile("file", "sumao.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile externalFormulaWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1"); var header = sheet.createRow(0);
            for (int index = 0; index < LogisticsWorkbookService.HEADERS.size(); index++) header.createCell(index).setCellValue(LogisticsWorkbookService.HEADERS.get(index));
            var row = sheet.createRow(1); row.createCell(0).setCellValue("美国"); var country = row.createCell(1);
            country.setCellFormula("VLOOKUP(A:A,[1]Sheet1!A:B,2,0)"); country.setCellValue("US");
            row.createCell(2).setCellValue(7); row.createCell(3).setCellValue(12); row.createCell(15).setCellValue(0);
            row.createCell(16).setCellValue(2); row.createCell(18).setCellValue(55); workbook.write(output);
            return new MockMultipartFile("file", "external-formula.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
