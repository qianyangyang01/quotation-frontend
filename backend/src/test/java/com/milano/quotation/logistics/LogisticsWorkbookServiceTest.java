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

    @Test
    void detectsPriceRuleRemovalAndHighRiskInsteadOfReportingNoDifference() {
        var mapper = JsonMapper.builder().build();
        var previous = mapper.createArrayNode();
        previous.addObject().put("rowKey", "us|美国||||||0|1").put("areaName", "美国").put("countryCode", "US").put("weightFromKg", 0).put("weightToKg", 1).put("pricePerKg", 50).put("etaMinDays", 7);
        previous.addObject().put("rowKey", "gb|英国||||||0|1").put("areaName", "英国").put("countryCode", "GB").put("weightFromKg", 0).put("weightToKg", 1).put("pricePerKg", 40).put("etaMinDays", 7);
        var next = mapper.createArrayNode();
        next.addObject().put("rowKey", "us|美国||||||0|1").put("areaName", "美国").put("countryCode", "US").put("weightFromKg", 0).put("weightToKg", 1).put("pricePerKg", 60).put("etaMinDays", 8);

        var result = service.compare(next, previous);

        assertEquals(1, result.path("summary").path("price").asInt());
        assertEquals(1, result.path("summary").path("removed").asInt());
        assertEquals(1, result.path("summary").path("highRisk").asInt());
        assertEquals("price", result.path("diffRows").get(0).path("type").asText());
        assertEquals(2, result.path("diffRows").get(0).path("changes").size());
        assertEquals(20.0, result.path("diffRows").get(0).path("maxPercentChange").asDouble());
    }

    @Test
    void pairsUnambiguousWeightRangeChangesAndKeepsOtherChangeKinds() {
        var mapper = JsonMapper.builder().build();
        var previous = mapper.createArrayNode();
        previous.addObject().put("areaName", "美国").put("countryCode", "US").put("weightFromKg", 0).put("weightToKg", 1)
                .put("pricePerKg", 50).put("startWeightKg", 0.05);
        var next = mapper.createArrayNode();
        next.addObject().put("areaName", "美国").put("countryCode", "US").put("weightFromKg", 0).put("weightToKg", 2)
                .put("pricePerKg", 60).put("startWeightKg", 0.1);

        var result = service.compare(next, previous); var diff = result.path("diffRows").get(0);

        assertEquals("range", diff.path("type").asText());
        assertTrue(diff.path("kinds").toString().contains("range"));
        assertTrue(diff.path("kinds").toString().contains("price"));
        assertTrue(diff.path("kinds").toString().contains("rule"));
        assertEquals(1, result.path("summary").path("range").asInt());
        assertEquals(1, result.path("summary").path("price").asInt());
        assertEquals(1, result.path("summary").path("rule").asInt());
        assertEquals(0, result.path("summary").path("added").asInt());
        assertEquals(0, result.path("summary").path("removed").asInt());
        assertEquals(0, result.path("summary").path("coverageReduced").asInt());
    }

    @Test
    void marksCoverageReductionAndLeavesSplitRangesAsAdditionsAndRemoval() {
        var mapper = JsonMapper.builder().build();
        var previous = mapper.createArrayNode();
        previous.addObject().put("areaName", "德国").put("countryCode", "DE").put("weightFromKg", 0).put("weightToKg", 2).put("pricePerKg", 50);
        var reduced = mapper.createArrayNode();
        reduced.addObject().put("areaName", "德国").put("countryCode", "DE").put("weightFromKg", 0.5).put("weightToKg", 1.5).put("pricePerKg", 50);
        var reduction = service.compare(reduced, previous);
        assertEquals("range", reduction.path("diffRows").get(0).path("type").asText());
        assertEquals(1, reduction.path("summary").path("coverageReduced").asInt());

        var split = mapper.createArrayNode();
        split.addObject().put("areaName", "德国").put("countryCode", "DE").put("weightFromKg", 0).put("weightToKg", 1).put("pricePerKg", 50);
        split.addObject().put("areaName", "德国").put("countryCode", "DE").put("weightFromKg", 1).put("weightToKg", 2).put("pricePerKg", 50);
        var ambiguous = service.compare(split, previous);
        assertEquals(0, ambiguous.path("summary").path("range").asInt());
        assertEquals(2, ambiguous.path("summary").path("added").asInt());
        assertEquals(1, ambiguous.path("summary").path("removed").asInt());
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
