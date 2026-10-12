package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class HuahaiPriceRegionTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LogisticsSourceParser parser = new LogisticsSourceParser(mapper, new LogisticsWorkbookService(mapper));
    static final String NOTES = "1、不接受异形件\n2、超尺寸费：180元/票\n3、销毁费：10元/票\n4、重派费：50元/票\n5、尺寸限制\n(1)最小尺寸要求：15cm*10cm*1cm；\n(2)单边≤60cm，三边和≤150cm\n6、重量限制：\n重量≤20kg；\n7、无需缴纳3欧/item的临时关税";

    @Test void keepsMergedMainPricesWhenRemarksMentionWeightAndRedelivery() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("普货 ");
            row(sheet, 2, "", "国家", "产品名称", "产品代码", "重量段始(KG)", "重量段终(KG)", "运费(RMB/KG)", "操作费(RMB/票)", "参考时效(工作日)", "备注");
            row(sheet, 3, "", "美国", "美国精选商派专线-普货", "18461", .05, .1, 57, 20, "6-10", "商派可达邮编");
            row(sheet, 4, "", "", "美国全量商派专线-普货", "13361", .05, .1, 60, 20, "6-10", "");
            sheet.addMergedRegion(new CellRangeAddress(3, 4, 1, 1));
            row(sheet, 5, "", "英国", "HH全球专线-普货", "7542", .02, .25, 58, 28, "8-10", "");
            row(sheet, 43, "", "法国", "", "", .02, .25, 60, 28, "8-10", NOTES);
            row(sheet, 44, "", "", "", "", .251, .5, 60, 29, "", "");
            sheet.addMergedRegion(new CellRangeAddress(43, 44, 1, 1));
            sheet.addMergedRegion(new CellRangeAddress(43, 44, 8, 8));
            sheet.addMergedRegion(new CellRangeAddress(43, 44, 9, 9));
            row(sheet, 45, "", "德国", "", "", 0, .1, 59, 25, "8-10", "");
            sheet.addMergedRegion(new CellRangeAddress(5, 45, 2, 2));
            sheet.addMergedRegion(new CellRangeAddress(5, 45, 3, 3));
            var result = parser.parse(bytes(book), "花海.xlsx");
            assertEquals(3, result.path("channels").size());
            for (var channel : result.path("channels")) assertEquals(0, channel.path("errors").asInt(), channel.path("issues").toString());
            var global = channel(result, "HH全球专线-普货");
            assertEquals(4, global.path("rows").size());
            var france = global.path("rows").valueStream().filter(r -> r.path("sourceRow").asInt() == 44).findFirst().orElseThrow();
            assertEquals("FR", france.path("countryCode").asText());
            assertEquals(60, france.path("pricePerKg").asDouble());
            assertEquals(28, france.path("registrationFee").asDouble());
            assertTrue(france.path("notes").asText().contains("重派费：50元/票"));
            assertEquals(0, result.path("sheets").get(0).path("conditionalPriceRows").size());
        }
    }

    @Test void stillSeparatesRealAuxiliaryFeeHeaders() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("普货");
            row(sheet, 0, "国家", "重量段", "运费/KG", "操作费/票", "备注");
            row(sheet, 1, "法国", "0-1", 60, 28, NOTES);
            row(sheet, 3, "重量(KG)", "重派费(元/票)");
            row(sheet, 4, "0-1", 50);
            row(sheet, 5, "1-2", 80);
            row(sheet, 7, "国家", "重量段", "运费/KG", "操作费/票", "备注");
            row(sheet, 8, "德国", "0-1", 59, 25, "");
            var result = parser.parse(bytes(book), "花海.xlsx");
            var parsed = result.path("channels").get(0);
            assertEquals(0, parsed.path("errors").asInt(), parsed.path("issues").toString());
            assertEquals(2, parsed.path("rows").size());
            assertEquals(2, result.path("sheets").get(0).path("conditionalPriceRows").size());
        }
    }

    @Test void remarksCannotHideUncoveredPricesAfterAnUnrecognizedSection() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("普货");
            row(sheet, 0, "国家", "重量段", "运费/KG", "操作费/票", "备注");
            row(sheet, 1, "法国", "0-1", 60, 28, NOTES);
            // A price-like row outside the detected weight column must stay a blocking error.
            row(sheet, 2, "德国", "待确认", 59, 25, "", "0-1");
            var result = parser.parse(bytes(book), "花海.xlsx");
            assertTrue(result.path("channels").get(0).path("errors").asInt() > 0);
            assertTrue(result.path("channels").get(0).path("issues").toString().contains("未覆盖价格行"));
            assertEquals(0, result.path("sheets").get(0).path("conditionalPriceRows").size());
        }
    }

    @Test @EnabledIfSystemProperty(named = "logistics.huahaiFile", matches = ".+")
    void readsOctoberSourceWorkbookWithoutDroppingFranceOrOtherCountries() throws Exception {
        var path = Path.of(System.getProperty("logistics.huahaiFile"));
        var result = parser.parse(Files.readAllBytes(path), path.getFileName().toString());
        Files.createDirectories(Path.of("target/huahai"));
        Files.writeString(Path.of("target/huahai/parsed.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        for (var c : result.path("channels")) System.out.println(c.path("channelName").asText() + " rows=" + c.path("rows").size() + " errors=" + c.path("errors").asInt());
        for (var c : result.path("channels")) assertEquals(0, c.path("errors").asInt(), c.path("channelName") + ": " + c.path("issues"));
        var global = channel(result, "HH全球专线-普货");
        assertEquals(92, global.path("rows").size());
        var france = global.path("rows").valueStream().filter(r -> r.path("sourceRow").asInt() == 44).findFirst().orElseThrow();
        assertEquals("FR", france.path("countryCode").asText());
        assertEquals(.02, france.path("weightFromKg").asDouble());
        assertEquals(.25, france.path("weightToKg").asDouble());
        assertEquals(60, france.path("pricePerKg").asDouble());
        assertEquals(28, france.path("registrationFee").asDouble());
        // Independently reconcile every numeric source price with its original Excel row.
        var prices = new TreeMap<Integer, JsonNode>();
        for (var c : result.path("channels")) for (var price : c.path("rows")) {
            if ("普货 ".equals(price.path("sourceSheet").asText()))
                assertNull(prices.put(price.path("sourceRow").asInt(), price), "duplicate source price");
        }
        assertEquals(126, prices.size());
        try (var workbook = WorkbookFactory.create(path.toFile(), null, true)) {
            int checked = 0;
            for (var source : workbook.getSheet("普货 ")) {
                boolean numeric = true;
                for (int col = 4; col <= 7; col++)
                    numeric &= source.getCell(col) != null && source.getCell(col).getCellType() == CellType.NUMERIC;
                if (!numeric) continue;
                var price = prices.remove(source.getRowNum() + 1);
                assertNotNull(price, "missing source row " + (source.getRowNum() + 1));
                assertEquals(source.getCell(5).getNumericCellValue(), price.path("weightToKg").asDouble());
                assertEquals(source.getCell(6).getNumericCellValue(), price.path("pricePerKg").asDouble());
                assertEquals(source.getCell(7).getNumericCellValue(), price.path("registrationFee").asDouble());
                checked++;
            }
            assertEquals(126, checked);
            assertTrue(prices.isEmpty(), "parsed prices without numeric source rows");
        }
    }

    private JsonNode channel(JsonNode result, String name) {
        return result.path("channels").valueStream().filter(c -> name.equals(c.path("channelName").asText())).findFirst().orElseThrow();
    }
}
