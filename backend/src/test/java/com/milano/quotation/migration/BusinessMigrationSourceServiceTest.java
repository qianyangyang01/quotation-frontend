package com.milano.quotation.migration;

import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsWorkbookService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BusinessMigrationSourceServiceTest {
    private final JsonMapper mapper=JsonMapper.builder().build();private final BusinessMigrationSourceService service=new BusinessMigrationSourceService(mapper,new LogisticsWorkbookService(mapper));
    @Test void normalizesBrowserReportAndAddsStableEntryKey() throws Exception{var json="{\"schemaVersion\":1,\"sourceOrigin\":\"http://localhost:5173\",\"entries\":[{\"source\":\"localStorage\",\"container\":\"http://localhost:5173\",\"key\":\"milano.finance-exchange-rate.v1\",\"category\":\"finance\",\"decision\":\"migrate\",\"count\":1,\"value\":{\"usdCny\":7.2}}]}";var report=service.parse(BusinessMigrationService.LEGACY_BROWSER,new MockMultipartFile("file","report.json","application/json",json.getBytes(StandardCharsets.UTF_8)));assertEquals(BusinessMigrationService.LEGACY_BROWSER,report.path("sourceType").asText());assertFalse(report.path("entries").get(0).path("entryKey").asText().isBlank());assertTrue(report.path("errors").isArray());}
    @Test void forceExcludesTrainingContainersAndRemovesTheirValues() throws Exception{var json="{\"schemaVersion\":2,\"sourceOrigin\":\"http://127.0.0.1:5173\",\"entries\":[{\"source\":\"localStorage\",\"container\":\"http://127.0.0.1:5173\",\"key\":\"milano.training.feedback-records\",\"category\":\"quotation-record\",\"decision\":\"migrate\",\"count\":1,\"value\":[{\"id\":\"feedback-1\"}]}]}";var report=service.parse(BusinessMigrationService.LEGACY_BROWSER,new MockMultipartFile("file","report.json","application/json",json.getBytes(StandardCharsets.UTF_8)));var entry=report.path("entries").get(0);assertEquals("exclude",entry.path("decision").asText());assertEquals("unknown",entry.path("category").asText());assertFalse(entry.has("value"));}
    @Test void parsesSumaoThirdRowHeaderAndProducesReconciliationErrors() throws Exception{var report=service.parse(BusinessMigrationService.SUMAO_ZIP,new MockMultipartFile("file","sumao.zip","application/zip",zip("云途模版/云途挂号普货.xlsx",workbook())));assertEquals(1,report.path("entries").size());assertEquals(1,report.path("diff").path("actualFiles").asInt());assertEquals(1,report.path("diff").path("actualPriceRows").asInt());assertEquals(2,report.path("errors").size());assertEquals("云途",report.path("entries").get(0).path("value").path("providerName").asText());}
    @Test void preservesRowLevelBlockingIssuesForDraftReview() throws Exception{var report=service.parse(BusinessMigrationService.SUMAO_ZIP,new MockMultipartFile("file","sumao.zip","application/zip",zip("云途模版/错误区间.xlsx",workbook(0))));var value=report.path("entries").get(0).path("value");assertEquals(1,value.path("validRows").asInt());assertEquals(1,value.path("errors").asInt());assertEquals("重量区间",value.path("issues").get(0).path("field").asText());}
    @Test void rejectsZipTraversalAndWrongBrowserExtension() throws Exception{assertThrows(AppException.class,()->service.parse(BusinessMigrationService.SUMAO_ZIP,new MockMultipartFile("file","bad.zip","application/zip",zip("../bad.xlsx",workbook()))));assertThrows(AppException.class,()->service.parse(BusinessMigrationService.LEGACY_BROWSER,new MockMultipartFile("file","bad.txt","text/plain","{}".getBytes(StandardCharsets.UTF_8))));}
    @Test void usesApprovedSumaoPriceRowBaseline(){assertEquals(3298,BusinessMigrationSourceService.EXPECTED_PRICE_ROWS);}
    private byte[] workbook() throws Exception{return workbook(2);}
    private byte[] workbook(double weightTo) throws Exception{try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){var sheet=workbook.createSheet("Sheet1");sheet.createRow(0).createCell(0).setCellValue("说明");sheet.createRow(1).createCell(0).setCellValue("必填");var header=sheet.createRow(2);for(int i=0;i<LogisticsWorkbookService.HEADERS.size();i++)header.createCell(i).setCellValue(LogisticsWorkbookService.HEADERS.get(i));var row=sheet.createRow(3);row.createCell(0).setCellValue("美国");row.createCell(1).setCellValue("US");row.createCell(2).setCellValue(7);row.createCell(3).setCellValue(12);row.createCell(15).setCellValue(0);row.createCell(16).setCellValue(weightTo);row.createCell(18).setCellValue(55);workbook.write(output);return output.toByteArray();}}
    private byte[] zip(String name,byte[] content) throws Exception{try(var output=new ByteArrayOutputStream();var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){zip.putNextEntry(new ZipEntry(name));zip.write(content);zip.closeEntry();zip.finish();return output.toByteArray();}}
}
