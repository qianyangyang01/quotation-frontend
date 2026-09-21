package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JitongPureBatteryTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/jitong-pure-battery-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",23));
    }
    byte[] fixture(double rate,String code) throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var s=book.createSheet("九月更新");LogisticsSourceParserTest.row(s,1,JitongPureBatteryRules.NAME);
            LogisticsSourceParserTest.row(s,4,"产品代码","服务国家","重量区间","运费(CNY/KG)","挂号费(CNY/ITEM)","参考时效");
            LogisticsSourceParserTest.row(s,5,code,"美国","0.05<W≤0.1",rate,28,"10-16工作日");
            LogisticsSourceParserTest.row(s,6,code,"日本","重量KG","首0.5KG","续0.5KG","5-7工作日");
            LogisticsSourceParserTest.row(s,7,code,"日本","0.1-30kg",55,18,"5-7工作日");
            LogisticsSourceParserTest.row(s,8,code,"以色列","0.1<W≤2",90,68,"15-20工作日");
            LogisticsSourceParserTest.row(s,9,code,"加拿大","0.05≤W≤0.15",82,28,"8-15工作日");
            LogisticsSourceParserTest.row(s,11,"包裹50G起收，不足50G按照50G计费。");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void retainsFollowingCountriesAfterFirstNextBlockAndUpdatesPriceWithoutChangingIdentity() throws Exception {
        var old=parser.parse(fixture(95,"JT-HQ-MDCD"),"极通环球旧价.xlsx",directory()).path("channels").get(0);
        var next=parser.parse(fixture(96,"JT-HQ-MDCD"),"极通环球更新.xlsx",directory()).path("channels").get(0);
        assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
        assertEquals(3,next.path("rows").size());assertEquals(old.path("companyChannelId"),next.path("companyChannelId"));assertNotEquals(old.path("contentHash"),next.path("contentHash"));
        var result=new LogisticsBillingEngine(mapper).calculate(next.path("rows"),mapper.createObjectNode().put("country","IL").put("weightKg",.01));
        assertEquals(.1,result.path("chargeWeightKg").asDouble());assertEquals(77,result.path("total").asDouble());
        var bad=parser.parse(fixture(95,"JT-HQ-CD"),"极通环球.xlsx",directory()).path("channels").get(0);
        assertTrue(bad.path("errors").asInt()>0);assertFalse(bad.path("quoteReady").asBoolean());
    }
    @Test void blocksMissingMinimumEvidence() throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(95,"JT-HQ-MDCD")))) {
            book.getSheetAt(0).removeRow(book.getSheetAt(0).getRow(11));
            var c=parser.parse(LogisticsSourceParserTest.bytes(book),"极通环球.xlsx",directory()).path("channels").get(0);
            assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="jitong.source",matches=".+")
    void reconcilesRealSource() throws Exception {
        var path=Path.of(System.getProperty("jitong.source"));var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/jitong-battery"));Files.writeString(Path.of("target/jitong-battery/parsed.json"),result.toPrettyString());
        assertEquals(1,result.path("channels").size());var c=result.path("channels").get(0);
        assertEquals(99,c.path("rows").size(),c.path("issues").toString());
        assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean(),c.path("blockingReasons").toString());
        assertEquals(33,c.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().count());
        assertEquals(198,result.path("priceCellsParsed").asInt());
        assertFalse(c.path("rows").valueStream().anyMatch(r->r.path("countryCode").asText().equals("JP")));
        var cases=mapper.createArrayNode();var firsts=new HashSet<String>();
        try(var reader=new LogisticsSheetReader(Files.readAllBytes(path),path.getFileName().toString(),name->!name.equals("极通环球专线-定制纯电"))) {
            while(reader.hasNext()) {
                var sheet=reader.next();if(!sheet.getSheetName().equals("极通环球专线-定制纯电"))continue;
                var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
                for(var row:c.path("rows")) {
                    var original=sheet.getRow(row.path("sourceRow").asInt()-1);int col=row.path("countryCode").asText().equals("AU")?new org.apache.poi.ss.util.CellReference(row.path("sourceZoneCell").asText()).getCol():5;
                    var rate=new BigDecimal(format.formatCellValue(original.getCell(col)));var fee=new BigDecimal(format.formatCellValue(original.getCell(col+1)));
                    assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());assertEquals("JT-HQ-MDCD",row.path("sourceProductCode").asText());
                    var text=java.text.Normalizer.normalize(format.formatCellValue(original.getCell(4)),java.text.Normalizer.Form.NFKC).split("\\(")[0];
                    var matcher=java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(text);assertTrue(matcher.find());var lo=new BigDecimal(matcher.group());assertTrue(matcher.find());var hi=new BigDecimal(matcher.group());
                    assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                    var minimum=new BigDecimal(row.path("countryCode").asText().equals("IL")?"0.1":"0.05");assertEquals(minimum.doubleValue(),row.path("minChargeWeightKg").asDouble());
                    var weights=new ArrayList<BigDecimal>(List.of(hi,lo.add(hi).divide(BigDecimal.valueOf(2))));
                    if(firsts.add(row.path("countryCode").asText()+"|"+row.path("zoneName").asText()))weights.addAll(List.of(new BigDecimal("0.001"),minimum.subtract(new BigDecimal("0.000001")),minimum,minimum.add(new BigDecimal("0.000001"))));
                    for(var weight:weights) {
                        var input=mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight);
                        var computed=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),input);var charged=weight.max(minimum);
                        assertEquals(charged.doubleValue(),computed.path("chargeWeightKg").asDouble());assertEquals(charged.multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),computed.path("total").asDouble());
                        cases.addObject().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight).set("expected",computed);
                    }
                }
            }
        }
        assertEquals(342,cases.size());var evidence=mapper.createObjectNode();evidence.set("channel",c);evidence.set("cases",cases);Files.writeString(Path.of("target/jitong-battery/billing-cases.json"),evidence.toPrettyString());
    }
}
