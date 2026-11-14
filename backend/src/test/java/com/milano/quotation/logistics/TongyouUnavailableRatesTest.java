package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TongyouUnavailableRatesTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    @Test void skipsOnlyTheUnavailableChannelCellsAndKeepsTheFiveKilogramBoundary()throws Exception {
        try(var book=matrix("-","-")) {
            var parsed=parser.parse(bytes(book),"通邮.xlsx");
            var ordinary=channel(parsed,"美国专线特惠普");var battery=channel(parsed,"美国专线带电");
            assertEquals(0,ordinary.path("errors").asInt(),ordinary.path("issues").toString());
            assertTrue(ordinary.path("pricingReady").asBoolean());
            assertEquals(1,ordinary.path("rows").size());assertEquals(2,battery.path("rows").size());
            var evidence=ordinary.path("issues").valueStream().filter(i->i.path("field").asText().equals("无报价已跳过")).findFirst().orElseThrow();
            assertEquals("-",evidence.path("rawValues").path("B4").asText());
            assertEquals("美国专线小包",evidence.path("sourceSheet").asText());
            var engine=new LogisticsBillingEngine(mapper);
            assertEquals(392,engine.calculate(ordinary.path("rows"),input("US",5)).path("total").asDouble());
            assertThrows(AppException.class,()->engine.calculate(ordinary.path("rows"),input("US",5.001)));
            assertThrows(AppException.class,()->engine.calculate(ordinary.path("rows"),input("US",10)));
            assertEquals(486.094,engine.calculate(battery.path("rows"),input("US",5.001)).path("total").asDouble(),.01);
        }
    }

    @ParameterizedTest @CsvSource(value={"-|12","76|-","bad|12","|","0|0","-|0"},delimiter='|',nullValues={""})
    void keepsMissingPartialInvalidAndZeroPricesBlocked(String rate,String fee)throws Exception {
        try(var book=matrix(rate==null?"":rate,fee==null?"":fee)) {
            var c=channel(parser.parse(bytes(book),"通邮.xlsx"),"美国专线特惠普");
            assertTrue(c.path("errors").asInt()>0||!c.path("pricingReady").asBoolean(),c.toString());
        }
    }

    @Test void doesNotTreatFormulaAsAnUnavailableMarker()throws Exception {
        try(var book=matrix("-","-")) {
            book.getSheetAt(0).getRow(3).getCell(1).setCellFormula("\"-\"");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            var c=channel(parser.parse(bytes(book),"通邮.xlsx"),"美国专线特惠普");
            assertTrue(c.path("errors").asInt()>0);
        }
    }

    @Test void skipsExplicitlySuspendedUnpricedIranAndRetainsNeighbouringRates()throws Exception {
        try(var book=table("-","暂停")) {
            var parsed=parser.parse(bytes(book),"通邮.xlsx");var c=channel(parsed,"通邮挂号特货");
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
            assertTrue(c.path("pricingReady").asBoolean());assertEquals(2,c.path("rows").size());
            assertTrue(c.path("rows").valueStream().noneMatch(r->r.path("countryCode").asText().equals("IR")));
            assertThrows(AppException.class,()->new LogisticsBillingEngine(mapper).calculate(c.path("rows"),input("IR",1)));
            assertTrue(parsed.path("sheets").get(0).path("filteredOtherRows").toString().contains("暂停且无报价"));
            assertTrue(c.path("issues").toString().contains("D3"));
        }
    }

    @ParameterizedTest @CsvSource(value={"-|正常","-|未暂停","bad|暂停","|暂停"},delimiter='|',nullValues={""})
    void doesNotHideMalformedOrUnconfirmedTablePrices(String price,String status)throws Exception {
        try(var book=table(price==null?"":price,status)) {
            var c=channel(parser.parse(bytes(book),"通邮.xlsx"),"通邮挂号特货");
            assertTrue(c.path("errors").asInt()>0);
        }
    }

    @Test void keepsOtherProvidersUnchanged()throws Exception {
        try(var book=table("-","暂停")) {
            var c=parser.parse(bytes(book),"万邦.xlsx").path("channels").get(0);
            assertTrue(c.path("errors").asInt()>0);
        }
        assertEquals("",LogisticsParserCompatibility.blockingReason("kuailv-eu-special-2026.11.11-v18"));
    }

    @Test @EnabledIfSystemProperty(named="tongyou.upload",matches=".+")
    void verifiesActualUploadWithinTheProductionImportScope()throws Exception {
        var path=Path.of(System.getProperty("tongyou.upload"));
        var scope=mapper.readTree(Files.readString(Path.of(System.getProperty("tongyou.scope"))));
        var parsed=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),new CompanyChannelScope(scope));
        Files.createDirectories(Path.of("target/tongyou-unavailable"));
        Files.writeString(Path.of("target/tongyou-unavailable/parsed.json"),parsed.toPrettyString());
        assertEquals(12,parsed.path("channels").size());
        for(var c:parsed.path("channels")) {
            assertEquals(0,c.path("errors").asInt(),c.path("channelName")+c.path("issues").toString());
            assertTrue(c.path("pricingReady").asBoolean(),c.path("channelName")+c.path("blockingReasons").toString());
        }
        var ordinary=channel(parsed,"美国专线特惠普");
        assertEquals(7,ordinary.path("rows").size());
        assertTrue(ordinary.path("rows").valueStream().allMatch(r->r.path("weightToKg").asDouble()<=5));
        assertTrue(channel(parsed,"通邮挂号特货").path("rows").valueStream().noneMatch(r->r.path("countryCode").asText().equals("IR")));
        var engine=new LogisticsBillingEngine(mapper);
        assertEquals(392,engine.calculate(ordinary.path("rows"),input("US",5)).path("total").asDouble());
        assertThrows(AppException.class,()->engine.calculate(ordinary.path("rows"),input("US",5.001)));
    }

    private XSSFWorkbook matrix(Object rate,Object fee) {
        var w=new XSSFWorkbook();var s=w.createSheet("美国专线小包");
        row(s,0,"重量","美国专线特惠普","","美国专线带电","");
        row(s,1,"重量(KG)","运费(RMB/KG)","处理费(RMB/票)","运费(RMB/KG)","处理费(RMB/票)");
        row(s,2,"0-5",76,12,94,16);row(s,3,"5.001-10",rate,fee,94,16);return w;
    }
    private XSSFWorkbook table(Object price,String status) {
        var w=new XSSFWorkbook();var s=w.createSheet("通邮挂号特货");
        row(s,0,"序号","国家","重量段","运费(RMB/KG)","挂号费(RMB/票)","状态");
        row(s,1,119,"亚美尼亚","0-2",169,25.8,"");
        row(s,2,120,"伊朗","0-2",price,5.8,status);
        row(s,3,121,"乍得","0-2",238,12,"");return w;
    }
    private JsonNode channel(JsonNode result,String name) {
        return result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(name)).findFirst().orElseThrow();
    }
    private tools.jackson.databind.node.ObjectNode input(String country,double weight) {
        return mapper.createObjectNode().put("country",country).put("weightKg",weight);
    }
}
