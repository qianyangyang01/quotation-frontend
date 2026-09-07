package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsFirstNextFilterTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    @Test void skipsMalformedFirstNextBeforeParsingAndKeepsFollowingPerKgSection() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("混合报价");
            row(sheet,0,"国家","重量段","首重0.5kg","续重0.5kg");
            row(sheet,1,"未知国家","坏重量","坏价格","坏价格");
            row(sheet,2,"法国","0-2",35,10);
            row(sheet,4,"国家","重量段","运费/KG","挂号费/票","时效");
            row(sheet,5,"美国","0-1",50,10,"5-8天");
            var parsed=parser.parse(bytes(book),"花海.xlsx");
            assertEquals(1,parsed.path("channels").size());var channel=parsed.path("channels").get(0);
            assertEquals(1,channel.path("rows").size());assertEquals(50,channel.path("rows").get(0).path("pricePerKg").asInt());
            assertEquals(0,channel.path("errors").asInt());assertTrue(channel.path("quoteReady").asBoolean(),channel.toString());
            assertTrue(parsed.path("sheets").get(0).path("unparsedPriceRows").isEmpty());
            assertEquals(2,parsed.path("sheets").get(0).path("filteredFirstNextRows").size());
        }
    }

    @Test void skipsFirstNextStandardRowsWithoutValidatingTheirNumbers() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("价格");row(sheet,0,LogisticsWorkbookService.HEADERS.toArray());
            var bad=sheet.createRow(1);bad.createCell(0).setCellValue("未知国家");bad.createCell(21).setCellValue("不能解析的首重价");
            var parsed=parser.parse(bytes(book),"花海.xlsx");
            assertTrue(parsed.path("channels").isEmpty());
            assertEquals("filtered",parsed.path("sheets").get(0).path("status").asText());
            assertEquals(0,parsed.path("sheets").get(0).path("errors").asInt());
        }
    }

    @Test void sharedHeaderDoesNotDiscardRowsThatOnlyUsePerKg() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("混合列");
            row(sheet,0,"国家","重量段","运费/KG","挂号费/票","首重0.5kg","续重0.5kg","时效");
            row(sheet,1,"美国","0-1",50,10,"","","5-8天");
            row(sheet,2,"法国","0-2","","",20,5,"");
            var parsed=parser.parse(bytes(book),"花海.xlsx");var channel=parsed.path("channels").get(0);
            assertEquals(1,channel.path("rows").size());assertEquals("US",channel.path("rows").get(0).path("countryCode").asText());
            assertTrue(channel.path("quoteReady").asBoolean(),channel.toString());
            assertEquals(1,parsed.path("sheets").get(0).path("filteredFirstNextRows").size());
        }
    }
}
