package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import static org.junit.jupiter.api.Assertions.*;

class YunexpressAdditionalTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/yunexpress-additional-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",24));
    }
    byte[] fixture(double rate,double floor,String code,String step,boolean missingWeight) throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var s=book.createSheet("更新报价");LogisticsSourceParserTest.row(s,2,"云途全球专线挂号（标快带电）","产品代码："+code);
            LogisticsSourceParserTest.row(s,5,"挂号费(RMB/票)","最低计费重(KG)","国家/地区","重量(KG)","运费(RMB/KG)","参考时效");
            LogisticsSourceParserTest.row(s,6,25,floor,"加拿大",missingWeight?"":"0<W≤0.15",rate,"5-7工作日");
            LogisticsSourceParserTest.row(s,7,25,floor,"加拿大","0.15<W≤0.3",rate,"5-7工作日");
            LogisticsSourceParserTest.row(s,8,27,"","美国","0<W≤0.1",120,"5-8工作日");
            LogisticsSourceParserTest.row(s,10,"加拿大：起重"+floor+"KG，以"+step+"G为单位进位，包裹按计费重量收费");
            var other=book.createSheet("云途全球专线挂号（特快带电）");LogisticsSourceParserTest.row(other,0,"国家","重量段","运费/kg","挂号费/票");LogisticsSourceParserTest.row(other,1,"美国","0-1","INVALID","INVALID");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void updatesPricesAndMinimaByHeadersAndBlocksChangedCodesOrRounding() throws Exception {
        var old=parser.parse(fixture(98,.05,"BKZXR","1",false),"云途旧价格.xlsx",directory()).path("channels").get(0);
        var next=parser.parse(fixture(101,.08,"BKZXR","1",false),"云途新价格.xlsx",directory()).path("channels").get(0);
        assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
        assertEquals(old.path("companyChannelId"),next.path("companyChannelId"));assertNotEquals(old.path("contentHash"),next.path("contentHash"));
        var computed=new LogisticsBillingEngine(mapper).calculate(next.path("rows"),mapper.createObjectNode().put("country","CA").put("weightKg",.080001));
        assertEquals(.081,computed.path("chargeWeightKg").asDouble());assertEquals(33.18,computed.path("total").asDouble());
        for(var bad:List.of(fixture(98,.05,"BKPHR","1",false),fixture(98,.05,"BKZXR","2",false),fixture(98,.05,"BKZXR","1",true))) {
            var c=parser.parse(bad,"云途.xlsx",directory()).path("channels").get(0);assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="yunexpress.source",matches=".+")
    void reconcilesFourRealChannels() throws Exception {
        var path=Path.of(System.getProperty("yunexpress.source"));var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/yunexpress-additional"));Files.writeString(Path.of("target/yunexpress-additional/parsed.json"),result.toPrettyString());
        assertEquals(4,result.path("channels").size());
        var counts=Map.of("BKZXR",66,"BKPHR",65,"DHZXR",39,"DHZXRPH",33);var cases=mapper.createArrayNode();
        for(var c:result.path("channels")){
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean(),c.path("blockingReasons").toString());
            assertEquals(counts.get(YunexpressAdditionalRules.named(c.path("channelName").asText()).code()),c.path("rows").size());
        }
        assertEquals(609,result.path("priceCellsParsed").asInt()); // 406 prices plus 203 explicit rounding-column reads.
        try(var reader=new LogisticsSheetReader(Files.readAllBytes(path),path.getFileName().toString(),name->YunexpressAdditionalRules.named(name)==null)) {
            while(reader.hasNext()) {
                var sheet=reader.next();var product=YunexpressAdditionalRules.named(sheet.getSheetName());if(product==null)continue;
                var c=result.path("channels").valueStream().filter(v->v.path("channelName").asText().equals(product.name())).findFirst().orElseThrow();
                var firsts=new HashSet<String>();var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
                for(var row:c.path("rows")) {
                    var original=sheet.getRow(row.path("sourceRow").asInt()-1);var rate=new BigDecimal(format.formatCellValue(original.getCell(7)));var fee=new BigDecimal(format.formatCellValue(original.getCell(8)));
                    var text=java.text.Normalizer.normalize(format.formatCellValue(original.getCell(4)),java.text.Normalizer.Form.NFKC);var matches=java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(text);
                    assertTrue(matches.find());var lo=new BigDecimal(matches.group());assertTrue(matches.find());var hi=new BigDecimal(matches.group());
                    var floorText=format.formatCellValue(original.getCell(6));var minimum=floorText.isBlank()?BigDecimal.ZERO:new BigDecimal(floorText);
                    boolean gram=product.large()||row.path("countryCode").asText().equals("CA");
                    assertEquals(product.code(),row.path("sourceProductCode").asText());assertEquals(gram?"per-kg-1g":"per-kg",row.path("pricingModel").asText());
                    assertEquals(minimum.doubleValue(),row.path("minChargeWeightKg").asDouble());assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());
                    assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                    var weights=new ArrayList<BigDecimal>(List.of(hi,lo.add(hi).divide(BigDecimal.valueOf(2))));
                    if(gram&&lo.signum()>0)weights.add(lo.add(new BigDecimal("0.000001")));
                    if(firsts.add(row.path("countryCode").asText()+"|"+row.path("zoneName").asText())) {
                        weights.add(new BigDecimal("0.001"));
                        if(minimum.signum()>0)weights.addAll(List.of(minimum.subtract(new BigDecimal("0.000001")),minimum,minimum.add(new BigDecimal("0.000001"))));
                    }
                    for(var weight:weights) {
                        var charged=weight.max(minimum);if(gram)charged=charged.setScale(3,RoundingMode.CEILING);
                        var computed=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight));
                        assertEquals(charged.doubleValue(),computed.path("chargeWeightKg").asDouble());assertEquals(charged.multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),computed.path("total").asDouble());
                        cases.addObject().put("channel",product.name()).put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight).set("expected",computed);
                    }
                }
            }
        }
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));evidence.set("cases",cases);Files.writeString(Path.of("target/yunexpress-additional/billing-cases.json"),evidence.toPrettyString());
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var dataset=UUID.randomUUID();var records=new ArrayList<ObjectNode>();
        for(var c:result.path("channels"))records.add(mapper.createObjectNode().put("provider","云途").put("channel",c.path("channelName").asText())
            .put("attribute",c.path("logisticsAttribute").asText()).put("id",UUID.randomUUID().toString()).put("status","published").set("version",c));
        org.mockito.Mockito.when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).param("dataset",dataset).param("version",null)
            .query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ObjectNode>>any()).list()).thenReturn(records);
        var exported=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        var restored=parser.parse(exported,"云途标准导出.xlsx",directory());assertEquals(4,restored.path("channels").size());
        for(var c:restored.path("channels")) {
            assertTrue(c.path("quoteReady").asBoolean(),c.path("issues").toString());
            var original=result.path("channels").valueStream().filter(v->v.path("companyChannelId").equals(c.path("companyChannelId"))).findFirst().orElseThrow();
            assertEquals(original.path("rows").size(),c.path("rows").size());
            for(int i=0;i<c.path("rows").size();i++) {
                assertEquals(original.path("rows").get(i).path("pricingModel"),c.path("rows").get(i).path("pricingModel"));
                for(var f:List.of("pricePerKg","registrationFee","minChargeWeightKg","weightFromKg","weightToKg"))assertEquals(original.path("rows").get(i).path(f).asDouble(),c.path("rows").get(i).path(f).asDouble());
            }
        }
    }
}
