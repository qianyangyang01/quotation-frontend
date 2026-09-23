package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class YanwenCosmeticsUpdateTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LogisticsWorkbookService workbooks = new LogisticsWorkbookService(mapper);
    private final LogisticsSourceParser parser = new LogisticsSourceParser(mapper, workbooks);

    @Test void extractionNeverWritesBackToTheSourceFile() throws Exception {
        var source=Files.createTempFile("yanwen-original-", ".xlsx");
        try {
            byte[] original;
            try(var input=new java.io.ByteArrayInputStream(fixture(true));var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input);var bytes=new java.io.ByteArrayOutputStream()) {
                book.createSheet("另一个渠道").createRow(0).createCell(0).setCellValue("必须保留");
                book.write(bytes);original=bytes.toByteArray();
            }
            Files.write(source,original);
            var extracted=extractSheet(Files.readAllBytes(source));
            assertArrayEquals(original,Files.readAllBytes(source));
            try(var input=new java.io.ByteArrayInputStream(original);var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) { assertEquals(2,book.getNumberOfSheets()); }
            try(var input=new java.io.ByteArrayInputStream(extracted);var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) { assertEquals(1,book.getNumberOfSheets());assertEquals("燕文化妆品专线",book.getSheetName(0)); }
        } finally { Files.deleteIfExists(source); }
    }
    private byte[] extractSheet(byte[] original) throws Exception {
        // The file-backed POI factory can save changes on close. Only operate on an in-memory copy.
        try(var input=new java.io.ByteArrayInputStream(original);var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input);var output=new java.io.ByteArrayOutputStream()) {
            var sheet=book.getSheet("燕文化妆品专线");assertNotNull(sheet);
            for(var row:sheet)for(var cell:row)assertNotEquals(org.apache.poi.ss.usermodel.CellType.FORMULA,cell.getCellType(),"Do not extract formula-dependent worksheets");
            for(int i=book.getNumberOfSheets()-1;i>=0;i--)if(!book.getSheetName(i).equals("燕文化妆品专线"))book.removeSheetAt(i);
            book.write(output);return output.toByteArray();
        }
    }

    @Test void updatesRatesAndWeightBoundariesTogetherWithoutCreatingAnotherChannel() throws Exception {
        var old=parser.parse(fixture(false),"燕文旧报价.xlsx").path("channels").get(0);
        var next=parser.parse(fixture(true),"燕文新报价.xlsx").path("channels").get(0);
        assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
        assertEquals(LogisticsSourceParser.identity(old),LogisticsSourceParser.identity(next));
        var summary=workbooks.compare((ArrayNode)next.path("rows"),(ArrayNode)old.path("rows")).path("summary");
        assertEquals(3,summary.path("price").asInt());assertEquals(2,summary.path("range").asInt());
        assertEquals(0,summary.path("added").asInt());assertEquals(0,summary.path("removed").asInt());
        var engine=new LogisticsBillingEngine(mapper);
        for(var example:new double[][]{{.001,21},{.049,21},{.05,21},{.5,48},{.501,53.06},{1,84},{5,332}})
            assertEquals(example[1],engine.calculate(next.path("rows"),mapper.createObjectNode().put("country","IL").put("weightKg",example[0])).path("total").asDouble());
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(next.path("rows"),mapper.createObjectNode().put("country","IL").put("weightKg",.5005)));
    }
    private byte[] fixture(boolean updated) throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet=book.createSheet("燕文化妆品专线");LogisticsSourceParserTest.row(sheet,0,"燕文化妆品专线");
            LogisticsSourceParserTest.row(sheet,1,"生效日期",updated?"2026-09-16 09:00":"2026-09-10 09:00");
            LogisticsSourceParserTest.row(sheet,2,"产品号","1046");
            LogisticsSourceParserTest.row(sheet,3,"大洲","国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)","最小计费重量(KG)");
            LogisticsSourceParserTest.row(sheet,4,"亚洲","以色列","IL",updated?60:65,18,updated?"0.001 - 0.5":"0.001 - 1",.05);
            LogisticsSourceParserTest.row(sheet,5,"亚洲","以色列","IL",updated?62:65,22,updated?"0.501 - 5":"1.001 - 5",.05);
            LogisticsSourceParserTest.row(sheet,6,"亚洲","新加坡","SG",updated?32:35,15,"0.001 - 30",.001);
            return LogisticsSourceParserTest.bytes(book);
        }
    }

    @Test @EnabledIfSystemProperty(named="yanwen.update.source", matches=".+")
    void reconcilesOriginalWorkbookWithPublishedBaseline() throws Exception {
        var dir = Path.of(System.getProperty("yanwen.update.evidence"));
        var baseline = mapper.readTree(Files.readString(dir.resolve("baseline.json")).replace("\uFEFF", ""));
        var source = Path.of(System.getProperty("yanwen.update.source"));
        var originalBytes=Files.readAllBytes(source);
        var backup=dir.resolve("source-original.xlsx");
        if(!Files.exists(backup))Files.write(backup,originalBytes,StandardOpenOption.CREATE_NEW);
        else assertArrayEquals(originalBytes,Files.readAllBytes(backup),"The saved original must not be silently overwritten");
        var parsed = parser.parse(originalBytes, source.getFileName().toString(), new CompanyChannelScope(baseline.path("directory")));
        Files.writeString(dir.resolve("parsed.json"), parsed.toPrettyString());
        var next = parsed.path("channels").valueStream().filter(c -> c.path("channelName").asText().equals("燕文化妆品专线")).findFirst().orElseThrow();
        var old = baseline.path("channels").valueStream().filter(c -> c.path("channelName").asText().equals("燕文化妆品专线")).findFirst().orElseThrow();
        assertEquals(old.path("companyChannelId"), next.path("companyChannelId"));
        assertEquals(0, next.path("errors").asInt(), next.path("issues").toPrettyString());
        assertTrue(next.path("quoteReady").asBoolean(), next.path("blockingReasons").toPrettyString());
        assertEquals(111,next.path("rows").size());
        // Cross-check every numeric tariff cell against the original worksheet, independently of parser output.
        var cases=mapper.createArrayNode();
        try(var input=Files.newInputStream(source);var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) {
            var sheet=book.getSheet("燕文化妆品专线");assertNotNull(sheet);
            var format=new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            int originalRows=0;
            for(var original:sheet) {
                if(original.getRowNum()<4)continue;
                var weight=format.formatCellValue(original.getCell(5));
                if(weight.matches("[0-9.]+\\s*-\\s*[0-9.]+"))originalRows++;
                else if(format.formatCellValue(original.getCell(0)).contains("价格使用说明"))break;
            }
            assertEquals(originalRows,next.path("rows").size());
            for(var row:next.path("rows")) {
                var original=sheet.getRow(row.path("sourceRow").asInt()-1);
                var rate=new java.math.BigDecimal(format.formatCellValue(original.getCell(3)));
                var fee=new java.math.BigDecimal(format.formatCellValue(original.getCell(4)));
                var minimum=new java.math.BigDecimal(format.formatCellValue(original.getCell(6)));
                var bounds=format.formatCellValue(original.getCell(5)).split("\\s*-\\s*");
                var lo=new java.math.BigDecimal(bounds[0]);var hi=new java.math.BigDecimal(bounds[1]);
                assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());
                assertEquals(minimum.doubleValue(),row.path("minChargeWeightKg").asDouble());assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                for(var weight:java.util.List.of(lo.max(minimum),lo.add(hi).divide(java.math.BigDecimal.TWO).max(minimum),hi)) {
                    var expected=weight.multiply(rate).add(fee).setScale(2,java.math.RoundingMode.HALF_UP);
                    var computed=new LogisticsBillingEngine(mapper).calculate(mapper.createArrayNode().add(row),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight));
                    assertEquals(expected.doubleValue(),computed.path("total").asDouble());
                    cases.addObject().put("country",row.path("countryCode").asText()).put("weightKg",weight).put("expected",expected);
                }
            }
            // Produce a channel-only import copy; leave the original workbook untouched.
            var target=dir.resolve("燕文化妆品专线-2026-09-16-原表提取.xlsx");
            assertNotEquals(source.toAbsolutePath().normalize(),target.toAbsolutePath().normalize());
            Files.write(target,extractSheet(originalBytes));
            var extracted=parser.parse(Files.readAllBytes(target),target.getFileName().toString(),new CompanyChannelScope(baseline.path("directory")));
            assertEquals(1,extracted.path("channels").size());
            assertEquals(next.path("contentHash"),extracted.path("channels").get(0).path("contentHash"));
            assertEquals(0,extracted.path("channels").get(0).path("errors").asInt());
            Files.writeString(dir.resolve("cosmetics-import-candidate.json"),extracted.path("channels").get(0).toPrettyString());
        }
        assertArrayEquals(originalBytes,Files.readAllBytes(source),"Original source file must remain byte-identical");
        Files.writeString(dir.resolve("billing-cases.json"),cases.toPrettyString());
        var comparison = workbooks.compare((ArrayNode)next.path("rows"), (ArrayNode)old.path("payload").path("rows"));
        Files.writeString(dir.resolve("cosmetics-comparison.json"), comparison.toPrettyString());
        Files.writeString(dir.resolve("cosmetics-candidate.json"), next.toPrettyString());
        var israel = next.path("rows").valueStream().filter(r -> r.path("countryCode").asText().equals("IL")).toList();
        assertEquals(2, israel.size());
        assertEquals(.001, israel.get(0).path("weightFromKg").asDouble());
        assertEquals(.5, israel.get(0).path("weightToKg").asDouble());
        assertEquals(60, israel.get(0).path("pricePerKg").asDouble());
        assertEquals(18, israel.get(0).path("registrationFee").asDouble());
        assertEquals(.501, israel.get(1).path("weightFromKg").asDouble());
        assertEquals(5, israel.get(1).path("weightToKg").asDouble());
        assertEquals(62, israel.get(1).path("pricePerKg").asDouble());
        assertEquals(22, israel.get(1).path("registrationFee").asDouble());
        for(var row : israel) assertEquals(.05, row.path("minChargeWeightKg").asDouble());
        var engine = new LogisticsBillingEngine(mapper);
        for(var example : new double[][]{{.001,21},{.049,21},{.05,21},{.5,48},{.501,53.06},{1,84},{5,332}}) {
            assertEquals(example[1], engine.calculate(next.path("rows"), mapper.createObjectNode().put("country","IL").put("weightKg",example[0])).path("total").asDouble(), "weight="+example[0]);
        }
    }
}
