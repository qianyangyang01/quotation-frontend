package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JieyitongdaSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/jieyitongda-israel-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));
        return new CompanyChannelScope(config.put("enabled",true).put("revision",1));
    }
    byte[] fixture(boolean moved,String defect) throws Exception {
        JsonNode original;
        try(var in=getClass().getResourceAsStream("/jieyitongda-israel-20261027.json")){original=mapper.readTree(in);}
        int shift=moved?40:0,offset=moved?4:0;
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet(moved?"十月新版":"以色列专线");
            for(var cell:original.path("cells")) {
                int r=cell.get(0).asInt(),c=cell.get(1).asInt();var v=cell.get(2);
                if(defect.equals("missing-tier")&&r==9||defect.equals("last-tier")&&r==13)continue;
                if(defect.equals("minimum")&&r==40)v=mapper.getNodeFactory().textNode(v.asText().replace("起重0.1kg；",""));
                if(defect.equals("fee-header")&&r==1&&c==6)v=mapper.getNodeFactory().textNode("未识别费用");
                if(defect.equals("code")&&r==8&&c==2)v=mapper.getNodeFactory().numberNode(2001);
                if(defect.equals("name")&&r==8&&c==1)v=mapper.getNodeFactory().textNode("以色列敏感-ilpost");
                if(defect.equals("country")&&r==2&&c==0)v=mapper.getNodeFactory().textNode("US美国");
                if((defect.equals("fee")&&c==6||defect.equals("price")&&c==5||defect.equals("weight")&&c==3||defect.equals("step")&&c==4)&&r==9)continue;
                if(defect.equals("updated")&&c==5&&r>=8&&r<=13)v=mapper.getNodeFactory().numberNode(67.5);
                if(defect.equals("excluded-malformed")&&r==24&&(c==3||c==5||c==6))v=mapper.getNodeFactory().textNode("INVALID");
                var row=sheet.getRow(r+shift);if(row==null)row=sheet.createRow(r+shift);
                var target=row.createCell((c+offset)%11);
                if(v.isNumber())target.setCellValue(v.asDouble());else target.setCellValue(v.asText());
                if(defect.equals("formula")&&r==9&&c==5)target.setCellFormula("1/0");
            }
            for(var value:original.path("merges")) {
                var m=CellRangeAddress.valueOf(value.asText());
                if(moved&&m.getFirstColumn()!=m.getLastColumn())continue;
                sheet.addMergedRegion(new CellRangeAddress(m.getFirstRow()+shift,m.getLastRow()+shift,(m.getFirstColumn()+offset)%11,(m.getLastColumn()+offset)%11));
            }
            var other=book.createSheet("美国专线");LogisticsSourceParserTest.row(other,0,"渠道名称","重量范围","公斤单价");
            LogisticsSourceParserTest.row(other,1,"美国敏感","0-30kg",999);
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    JsonNode channel(JsonNode result,String name) {
        return result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(name)).findFirst().orElseThrow();
    }
    void reconcile(JsonNode result,double rate) {
        assertEquals(2,result.path("channels").size(),result.toPrettyString());
        for(var c:result.path("channels")) {
            boolean door=c.path("channelName").asText().contains("到门");
            assertEquals("带电、化妆品、保健品",c.path("logisticsAttribute").asText());
            assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());assertEquals(0,c.path("errors").asInt(),c.toPrettyString());
            assertEquals(3,c.path("rows").size());assertEquals("捷易通达",c.path("providerName").asText());
            var ranges=door?new double[][]{{.1,2,50},{2,5,50},{5,20,65}}:new double[][]{{.1,.5,18},{.5,2,22},{2,5,22}};
            for(int i=0;i<3;i++) {
                var row=c.path("rows").get(i);
                assertEquals(ranges[i][0],row.path("weightFromKg").asDouble());assertEquals(ranges[i][1],row.path("weightToKg").asDouble());
                assertEquals(rate,row.path("pricePerKg").asDouble());assertEquals(ranges[i][2],row.path("registrationFee").asDouble());
                assertEquals(door?"2001":"1961",row.path("sourceProductCode").asText());assertEquals("IL",row.path("countryCode").asText());
                assertEquals(.1,row.path("minChargeWeightKg").asDouble());
                assertEquals(8,row.path("etaMinDays").asInt());assertEquals(15,row.path("etaMaxDays").asInt());
                assertTrue(row.path("notes").asText().contains("保健品"));assertTrue(row.path("notes").asText().contains("8000"));
                assertEquals(2,row.path("billingStepBands").size());
                assertEquals(.001,row.path("billingStepBands").get(0).path("stepKg").asDouble());
                assertEquals(2,row.path("billingStepBands").get(1).path("aboveKg").asInt());
                assertEquals(.1,row.path("billingStepBands").get(1).path("stepKg").asDouble());
            }
            // Independent source arithmetic on both sides of each breakpoint, including sub-gram weights.
            for(double weight:List.of(.001,.0999,.1,.1001,.4999,.5,.5001,1.9991,2.,2.0001,4.9991,5.,5.0001,19.9999,20.,20.001)) {
                if(weight>(door?20:5)) {
                    assertThrows(AppException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","IL").put("weightKg",weight)));continue;
                }
                var base=BigDecimal.valueOf(weight).max(new BigDecimal("0.1"));
                var unit=base.compareTo(new BigDecimal("2"))<=0?new BigDecimal("0.001"):new BigDecimal("0.1");
                var charged=base.divide(unit,0,RoundingMode.CEILING).multiply(unit);
                double fee=door?(charged.doubleValue()<=5?50:65):(charged.doubleValue()<=.5?18:22);
                var expected=charged.multiply(BigDecimal.valueOf(rate)).add(BigDecimal.valueOf(fee)).setScale(2,RoundingMode.HALF_UP);
                var actual=engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","IL").put("weightKg",weight));
                assertEquals(expected.doubleValue(),actual.path("total").asDouble(),c.path("channelName")+" "+weight);
                assertEquals(charged.doubleValue(),actual.path("chargeWeightKg").asDouble());
            }
        }
    }
    @Test void exactTwoProductsSurviveMovedColumnsMergedCellsAndExcludedBadPrices() throws Exception {
        for(boolean moved:List.of(false,true))for(String defect:List.of("","excluded-malformed","updated")) {
            var bytes=fixture(moved,defect);reconcile(parser.parse(bytes,"捷易通达.xlsx",directory()),defect.equals("updated")?67.5:64);
            reconcile(parser.parse(bytes,"捷易通达.xlsx"),defect.equals("updated")?67.5:64);
        }
    }
    @Test void missingOrConflictingCriticalValuesBlockPublication() throws Exception {
        for(String defect:List.of("fee","price","weight","step","formula","code","name","country","minimum","fee-header","missing-tier","last-tier")) {
            var result=parser.parse(fixture(true,defect),"捷易通达.xlsx",directory());
            assertEquals(2,result.path("channels").size(),defect+result.toPrettyString());
            var c=channel(result,defect.equals("last-tier")?"以色列敏感(到门)":"以色列敏感");
            assertFalse(c.path("quoteReady").asBoolean(),defect+c.toPrettyString());assertTrue(c.path("errors").asInt()>0,defect);
        }
    }
    @Test void previousParserVersionRemainsCompatible() {
        assertEquals("",LogisticsParserCompatibility.blockingReason("source-date-reference-2026.10.26-wanbang-preferred-v12"));
        assertEquals("",LogisticsParserCompatibility.blockingReason(LogisticsSourceParser.VERSION));
    }
    @Test @EnabledIfSystemProperty(named="jytd.source",matches=".+")
    void unmodifiedWorkbookAndRepeatParseMatchAllSixSourceTiers() throws Exception {
        var path=Path.of(System.getProperty("jytd.source"));var bytes=Files.readAllBytes(path);
        var result=parser.parse(bytes,path.getFileName().toString(),directory());reconcile(result,64);
        assertEquals(54,result.path("sheets").size());
        assertEquals(result,parser.parse(bytes,path.getFileName().toString(),directory()));
        Files.createDirectories(Path.of("target/jieyitongda"));Files.writeString(Path.of("target/jieyitongda/parsed.json"),result.toPrettyString());
    }
}
