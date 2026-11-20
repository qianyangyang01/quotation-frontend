package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JitongCustomTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode config() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/jitong-custom-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));return config.put("enabled",true).put("revision",1);
    }
    CompanyChannelScope directory() throws Exception {return new CompanyChannelScope(config());}
    byte[] fixture(String name,String code,boolean minimum,double rate) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet(name);LogisticsSourceParserTest.row(s,1,name);
            LogisticsSourceParserTest.row(s,3,"产品代码","服务国家","重量区间","运费(CNY/KG)","挂号费(CNY/ITEM)","参考时效");
            LogisticsSourceParserTest.row(s,4,code,"德国","0.05<W≤0.5",rate,30,"10-12工作日");
            LogisticsSourceParserTest.row(s,5,code,"德国","0.5<W≤2",60,30,"10-12工作日");
            LogisticsSourceParserTest.row(s,6,code,"瑞典","0<W≤2",88,15,"10-12工作日");
            if(minimum)LogisticsSourceParserTest.row(s,9,"包裹50G起收，不足50G按照50G计费。");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void includesDocumentedMinimumAndKeepsIdentityWhenSourceCodesChange() throws Exception {
        for(var entry:config().path("entries")) {
            String name=entry.path("channelName").asText(),code=entry.path("productCodes").get(0).asText();
            var old=parser.parse(fixture(name,code,true,68),"极通环球.xlsx",directory()).path("channels").get(0);
            var next=parser.parse(fixture(name,"JTHQ-NEXT-2027",true,69),"极通环球.xlsx",directory()).path("channels").get(0);
            assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
            assertEquals(old.path("companyChannelId"),next.path("companyChannelId"));assertEquals(entry.path("id"),next.path("companyChannelId"));
            assertNotEquals(old.path("contentHash"),next.path("contentHash"));
            assertEquals("JTHQ-NEXT-2027",next.path("rows").get(0).path("sourceProductCode").asText());
            var engine=new LogisticsBillingEngine(mapper);
            for(double weight:new double[]{.001,.049999,.05,.050001,.123456,.5,.500001,2}) {
                var result=engine.calculate(next.path("rows"),mapper.createObjectNode().put("country","DE").put("weightKg",weight));
                var charged=BigDecimal.valueOf(weight).max(new BigDecimal("0.05"));
                var rate=weight<=.5?new BigDecimal("69"):new BigDecimal("60");
                assertEquals(charged.doubleValue(),result.path("chargeWeightKg").asDouble());
                assertEquals(charged.multiply(rate).add(new BigDecimal("30")).setScale(2,RoundingMode.HALF_UP).doubleValue(),result.path("total").asDouble());
            }
            assertThrows(RuntimeException.class,()->engine.calculate(next.path("rows"),mapper.createObjectNode().put("country","DE").put("weightKg",2.000001)));
            assertFalse(next.path("rows").get(1).path("weightFromInclusive").asBoolean());
            var noMinimum=parser.parse(fixture(name,code,false,68),"极通环球.xlsx",directory()).path("channels").get(0);
            assertFalse(noMinimum.path("quoteReady").asBoolean());assertTrue(noMinimum.path("errors").asInt()>0);
        }
    }
    @Test void preservesExistingJitongMinimumBoundaryAndBlocksCrossProductCode() throws Exception {
        var config=config();config.withArray("entries").addObject().put("id","d66350f5-359c-4bfc-a856-c9761a8201ab")
                .put("providerName","极通环球").put("channelName","极通环球-其他渠道").put("logisticsAttribute","普货").put("enabled",true);
        var result=parser.parse(fixture("极通环球-其他渠道","OTHER",true,68),"极通环球.xlsx",new CompanyChannelScope(config));
        assertFalse(result.path("channels").get(0).path("rows").get(0).path("weightFromInclusive").asBoolean());
        var bad=parser.parse(fixture("极通环球-定制特惠普货","JTHQ-BSCD",true,68),"极通环球.xlsx",directory());
        assertTrue(bad.path("channels").isEmpty()||bad.path("channels").valueStream().noneMatch(c->c.path("quoteReady").asBoolean()));
    }
    @Test void acceptsRenamedSheetWithRegisteredTitleAndBlocksMissingOrDuplicatePriceRows() throws Exception {
        String name="极通环球-定制特惠普货";
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(name,"JTHQ-UPDATED",true,68)))) {
            book.setSheetName(0,"新版价格");
            var parsed=parser.parse(LogisticsSourceParserTest.bytes(book),"极通环球.xlsx",directory());
            assertEquals(1,parsed.path("channels").size());assertTrue(parsed.path("channels").get(0).path("quoteReady").asBoolean());
            assertEquals(name,parsed.path("channels").get(0).path("channelName").asText());
            book.getSheetAt(0).getRow(4).getCell(3).setBlank();
            parsed=parser.parse(LogisticsSourceParserTest.bytes(book),"极通环球.xlsx",directory());
            assertFalse(parsed.path("channels").get(0).path("quoteReady").asBoolean());
        }
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(name,"JTHQ-MDBS",true,68)))) {
            LogisticsSourceParserTest.row(book.getSheetAt(0),7,"JTHQ-MDBS","德国","0.05<W≤0.5",69,30,"10-12工作日");
            var parsed=parser.parse(LogisticsSourceParserTest.bytes(book),"极通环球.xlsx",directory());
            assertFalse(parsed.path("channels").get(0).path("quoteReady").asBoolean());
            assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0);
        }
        var reused="极通环球-定制特惠普货\nJTHQ-MDBS\n极通环球-特惠普货(中邮)\nJTHQ-MXBS\n（邮政单号挂号费+0.5/票）";
        assertTrue(parser.parse(fixture("哥伦比亚专线",reused,true,68),"极通环球.xlsx",directory()).path("channels").isEmpty());
    }
    @Test @EnabledIfSystemProperty(named="jitong.custom.source",matches=".+")
    void reconcilesEverySourcePriceAndMinimumBoundary() throws Exception {
        var path=Path.of(System.getProperty("jitong.custom.source"));
        var expected=mapper.readTree(Files.readString(Path.of(System.getProperty("jitong.custom.expected"))));
        var parsed=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        var output=Path.of("target/jitong-custom");Files.createDirectories(output);Files.writeString(output.resolve("parsed.json"),parsed.toPrettyString());
        assertEquals(4,parsed.path("channels").size());var corpus=mapper.createArrayNode();int totalRows=0,totalCases=0;
        for(var channel:parsed.path("channels")) {
            var source=expected.path("channels").valueStream().filter(c->c.path("name").equals(channel.path("channelName"))).findFirst().orElseThrow();
            assertTrue(channel.path("quoteReady").asBoolean(),channel.path("issues").toPrettyString());assertEquals(0,channel.path("errors").asInt());
            assertEquals(source.path("rows").size(),channel.path("rows").size());
            assertEquals(13,channel.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().count());
            var evidence=corpus.addObject();evidence.set("channel",channel);var cases=evidence.putArray("cases");
            for(var row:channel.path("rows")) {
                totalRows++;var expectedRow=source.path("rows").valueStream().filter(r->r.path("sourceRow").equals(row.path("sourceRow"))).findFirst().orElseThrow();
                for(var field:List.of("countryCode","areaName","zoneName","sourceSheet","currency","pricingModel"))assertEquals(expectedRow.path(field),row.path(field),field+"/"+row.path("sourceRow"));
                for(var field:List.of("weightFromKg","weightToKg","pricePerKg","registrationFee","minChargeWeightKg","etaMinDays","etaMaxDays"))assertEquals(expectedRow.path(field).asDouble(),row.path(field).asDouble(),field+"/"+row.path("sourceRow"));
                for(var field:List.of("weightFromInclusive","weightToInclusive"))assertEquals(expectedRow.path(field).asBoolean(),row.path(field).asBoolean(),field+"/"+row.path("sourceRow"));
                assertTrue(row.path("sourceProductCode").asText().contains(source.path("productCode").asText()));
                assertEquals(0,row.path("billingStepKg").asDouble());
                var lower=row.path("weightFromKg").decimalValue();var upper=row.path("weightToKg").decimalValue();
                var weights=new LinkedHashSet<BigDecimal>(List.of(lower.add(new BigDecimal("0.000001")),lower.add(upper).divide(new BigDecimal("2")),upper));
                if(lower.compareTo(new BigDecimal("0.05"))<=0)weights.addAll(List.of(new BigDecimal("0.001"),new BigDecimal("0.049999"),new BigDecimal("0.05"),new BigDecimal("0.050001")));
                for(var weight:weights) {
                    var result=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("weightKg",weight));
                    var charged=weight.max(new BigDecimal("0.05"));
                    assertEquals(charged.doubleValue(),result.path("chargeWeightKg").asDouble());
                    assertEquals(charged.multiply(expectedRow.path("pricePerKg").decimalValue()).add(expectedRow.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP).doubleValue(),result.path("total").asDouble());
                    cases.addObject().put("country",row.path("countryCode").asText()).put("weightKg",weight).set("expected",result);totalCases++;
                }
            }
        }
        assertEquals(144,totalRows);assertEquals(594,totalCases);
        assertTrue(parsed.path("sheets").valueStream().filter(s->s.path("name").asText().equals("哥伦比亚专线")).allMatch(s->s.path("status").asText().equals("filtered")));
        Files.writeString(output.resolve("billing-cases.json"),corpus.toPrettyString());
        var independent=mapper.readTree(Files.readString(Path.of(System.getProperty("jitong.custom.expected")).resolveSibling("billing-cases.json")));
        int accepted=0,rejected=0;
        for(var source:independent.path("channels")) {
            var channel=parsed.path("channels").valueStream().filter(c->c.path("channelName").equals(source.path("name"))).findFirst().orElseThrow();
            var engine=new LogisticsBillingEngine(mapper);
            for(var sample:source.path("samples")) {
                var input=mapper.createObjectNode().put("country",sample.path("country").asText());input.set("weightKg",sample.path("weightKg"));
                var result=engine.calculate(channel.path("rows"),input);
                assertEquals(sample.path("chargeWeightKg").asDouble(),result.path("chargeWeightKg").asDouble());
                assertEquals(sample.path("total").asDouble(),result.path("total").asDouble());
                assertEquals(sample.path("sourceRow").asInt(),channel.path("rows").get(result.path("rowIndex").asInt()).path("sourceRow").asInt());accepted++;
            }
            for(var sample:source.path("rejected")) {
                var input=mapper.createObjectNode().put("country",sample.path("country").asText());input.set("weightKg",sample.path("weightKg"));
                assertThrows(RuntimeException.class,()->engine.calculate(channel.path("rows"),input));rejected++;
            }
        }
        assertEquals(784,accepted);assertEquals(124,rejected);
    }
}
