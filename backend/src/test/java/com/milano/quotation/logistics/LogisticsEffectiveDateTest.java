package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;

class LogisticsEffectiveDateTest {
    final ObjectMapper mapper=new ObjectMapper();
    private static tools.jackson.databind.JsonNode only(tools.jackson.databind.JsonNode result) {
        assertEquals(1,result.path("channels").size(),result.toString());
        return result.path("channels").get(0);
    }

    @Test void usesExplicitLabelsAndBeijingTimeIncludingTheExactBoundary() {
        assertEquals("",LogisticsEffectiveDate.parse("报价表 2026-10-20.xlsx"));
        assertEquals("2026-10-20T00:00+08:00",LogisticsEffectiveDate.parse("生效日期：2026-10-20 00:00"));
        assertEquals("2026-10-20T12:30+08:00",LogisticsEffectiveDate.parse("生效时间：2026年10月20日 12:30"));
        assertEquals("2026-10-20T12:30+08:00",LogisticsEffectiveDate.parse("Effective from: 2026/10/20 12:30"));
        assertThrows(IllegalArgumentException.class,()->LogisticsEffectiveDate.parse("生效日期：2026-02-30"));
        var dates=mapper.createArrayNode().add("2026-10-20T00:00+08:00");
        assertFalse(LogisticsEffectiveDate.blockingDates(dates,Instant.parse("2026-10-19T15:59:59Z")).isBlank());
        assertEquals("",LogisticsEffectiveDate.blockingDates(dates,Instant.parse("2026-10-19T16:00:00Z")));
        assertFalse(LogisticsEffectiveDate.blockingDates(mapper.createArrayNode().add("invalid"),Instant.now()).isBlank());
    }

    @Test void capturesAdjacentDateCellsAndMakesEffectiveTimePartOfImportIdentity() throws Exception {
        var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("专线");row(s,0,"生效日期","2099-10-20 12:30");
            row(s,1,"国家","重量段","运费/KG","挂号费/票");row(s,2,"美国","0-1",60,20);
            var channel=only(parser.parse(bytes(book),"云途.xlsx"));
            var rows=(tools.jackson.databind.node.ArrayNode)channel.path("rows");
            assertEquals("2099-10-20T12:30+08:00",rows.get(0).path("effectiveAt").asText());
            assertTrue(rows.get(0).path("sourceEffectiveAtText").asText().contains("第1行"));
            assertFalse(LogisticsEffectiveDate.blockingReason(channel,Instant.now()).isBlank());
            var hash=parser.businessHash(rows);
            ((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("effectiveAt","2099-10-21T12:30+08:00");
            assertNotEquals(hash,parser.businessHash(rows));
        }
    }

    @Test void conflictingOrInvalidHeaderDatesBlockTheImport() throws Exception {
        var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        for(var date:java.util.List.of("生效日期：2099-02-30","生效日期：2099-10-21"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("专线");row(s,0,"生效日期：2099-10-20");row(s,1,date);
            row(s,2,"国家","重量段","运费/KG","挂号费/票");row(s,3,"美国","0-1",60,20);
            var channel=only(parser.parse(bytes(book),"云途.xlsx"));
            assertTrue(channel.path("errors").asInt()>0);assertFalse(channel.path("pricingReady").asBoolean());
        }
    }
}
