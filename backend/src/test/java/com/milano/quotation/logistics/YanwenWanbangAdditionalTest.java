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

class YanwenWanbangAdditionalTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/yanwen-wanbang-additional-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",25));
    }
    byte[] fixture(double rate,String minimum,String code,boolean missingWeight) throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var s=book.createSheet("更新报价");LogisticsSourceParserTest.row(s,1,"燕文精品服装专线-普货");
            LogisticsSourceParserTest.row(s,2,"产品号",code);
            LogisticsSourceParserTest.row(s,4,"处理费(元/件)","最小计费重量(KG)","国家","重量段(KG)","公斤运费(元/KG)","CountryCode");
            LogisticsSourceParserTest.row(s,5,20,minimum,"美国",missingWeight?"":"0.001 - 0.1",rate,"US");
            LogisticsSourceParserTest.row(s,6,18,minimum,"美国","0.101 - 0.2",58,"US");
            var other=book.createSheet("燕文其他专线");LogisticsSourceParserTest.row(other,0,"国家","重量段","运费/kg","挂号费/票");LogisticsSourceParserTest.row(other,1,"美国","0-1","INVALID","INVALID");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void updatesByHeadersAndRejectsMissingMinimaWeightsAndChangedProduct() throws Exception {
        var old=parser.parse(fixture(50,"0.03","1667",false),"燕文旧报价.xlsx",directory()).path("channels").get(0);
        var next=parser.parse(fixture(60,"0.04","1667",false),"燕文更新.xlsx",directory()).path("channels").get(0);
        assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
        assertEquals(old.path("companyChannelId"),next.path("companyChannelId"));assertNotEquals(old.path("contentHash"),next.path("contentHash"));
        var result=new LogisticsBillingEngine(mapper).calculate(next.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.001));
        assertEquals(.04,result.path("chargeWeightKg").asDouble());assertEquals(22.4,result.path("total").asDouble());
        for(var bad:List.of(fixture(50,"","1667",false),fixture(50,"0.03","WRONG",false),fixture(50,"0.03","1667",true))) {
            var c=parser.parse(bad,"燕文.xlsx",directory()).path("channels").get(0);assertTrue(c.path("errors").asInt()>0,c.toPrettyString());assertFalse(c.path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="yanwen.additional.source",matches=".+")
    void reconcilesFourRealChannels() throws Exception {
        var channels=mapper.createArrayNode();var cases=mapper.createArrayNode();
        Files.createDirectories(Path.of("target/yanwen-wanbang-additional"));
        for(var provider:List.of("燕文","万邦")) {
            var path=Path.of(System.getProperty(provider.equals("燕文")?"yanwen.additional.source":"wanbang.additional.source"));
            var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
            Files.writeString(Path.of("target/yanwen-wanbang-additional/"+provider+"-parsed.json"),result.toPrettyString());
            assertEquals(provider.equals("燕文")?3:1,result.path("channels").size());
            for(var c:result.path("channels")) {
                assertEquals(0,c.path("errors").asInt(),c.path("channelName")+c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean(),c.path("blockingReasons").toString());
                channels.add(c);
            }
            try(var reader=new LogisticsSheetReader(Files.readAllBytes(path),path.getFileName().toString(),name->YanwenWanbangAdditionalRules.named(provider,name)==null)) {
                while(reader.hasNext()) {
                    var sheet=reader.next();var product=YanwenWanbangAdditionalRules.named(provider,sheet.getSheetName());if(product==null)continue;
                    var c=result.path("channels").valueStream().filter(v->v.path("channelName").asText().equals(product.name())).findFirst().orElseThrow();
                    var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);var firsts=new HashSet<String>();int expectedRows=0;
                    // Count only the original primary tariff, independently from parsed source-row references.
                    int weightCol=provider.equals("燕文")?5:7,rateCol=provider.equals("燕文")?3:8,feeCol=rateCol+1;
                    for(var original:sheet) {
                        if(original.getRowNum()<(provider.equals("燕文")?4:5))continue;
                        var weight=format.formatCellValue(original.getCell(weightCol));
                        if(weight.matches("(?i)[0-9.]+\\s*-\\s*[0-9.]+(?:KG)?")&&!format.formatCellValue(original.getCell(rateCol)).isBlank())expectedRows++;
                        else if(expectedRows>0)break;
                    }
                    assertEquals(expectedRows,c.path("rows").size(),product.name());
                    for(var row:c.path("rows")) {
                        var original=sheet.getRow(row.path("sourceRow").asInt()-1);
                        var rate=new BigDecimal(format.formatCellValue(original.getCell(rateCol)));var fee=new BigDecimal(format.formatCellValue(original.getCell(feeCol)));
                        var minimum=provider.equals("燕文")?new BigDecimal(format.formatCellValue(original.getCell(6))):BigDecimal.ZERO;
                        var matcher=java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(format.formatCellValue(original.getCell(weightCol)));
                        assertTrue(matcher.find());var lo=new BigDecimal(matcher.group());assertTrue(matcher.find());var hi=new BigDecimal(matcher.group());
                        assertEquals(product.code(),row.path("sourceProductCode").asText());assertEquals("per-kg",row.path("pricingModel").asText());
                        assertEquals(minimum.doubleValue(),row.path("minChargeWeightKg").asDouble());assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());
                        assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                        var weights=new ArrayList<BigDecimal>(List.of(hi,lo.add(hi).divide(BigDecimal.valueOf(2))));
                        if(lo.signum()>0)weights.add(lo.max(minimum));
                        if(firsts.add(row.path("countryCode").asText())) {
                            weights.add(new BigDecimal("0.0005"));
                            if(minimum.signum()>0)weights.addAll(List.of(minimum.subtract(new BigDecimal("0.000001")),minimum,minimum.add(new BigDecimal("0.000001"))));
                        }
                        for(var weight:weights) {
                            var charged=weight.max(minimum);
                            var computed=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("weightKg",weight));
                            assertEquals(charged.doubleValue(),computed.path("chargeWeightKg").asDouble(),product.name()+"/"+row.path("sourceRow")+"/"+computed);
                            assertEquals(charged.multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),computed.path("total").asDouble());
                            cases.addObject().put("channel",product.name()).put("country",row.path("countryCode").asText()).put("zoneName","").put("weightKg",weight).set("expected",computed);
                        }
                    }
                }
            }
        }
        var evidence=mapper.createObjectNode();evidence.set("channels",channels);evidence.set("cases",cases);Files.writeString(Path.of("target/yanwen-wanbang-additional/billing-cases.json"),evidence.toPrettyString());
        roundTrip(channels);
    }

    @Test void wanbangUsesPriceWeightColumnNotAcceptanceLimitAndIgnoresReturnFees() throws Exception {
        String identity="";
        for(var code:List.of("WBSLLP","WRONG"))try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var s=book.createSheet("新版含电报价");LogisticsSourceParserTest.row(s,1,"万邦速达大货专线含电报价表(RMB)");
            LogisticsSourceParserTest.row(s,3,"国家","重量限制(KG)","产品名称","操作费(RMB/PCS)","产品代码","公斤重(RMB/KG)","重量段","参考时效");
            LogisticsSourceParserTest.row(s,4,"美国","0-30KG","万邦大货专线含电",51,code,106,"0-10KG","9--12");
            LogisticsSourceParserTest.row(s,5,"美国","0-30KG","万邦大货专线含电",61,code,111,"10.001-30KG","9--12");
            LogisticsSourceParserTest.row(s,7,"注意事项：");
            LogisticsSourceParserTest.row(s,8,"包裹退回费用");
            LogisticsSourceParserTest.row(s,9,"产品名称","产品代码","公斤重(RMB/KG)","操作费(RMB/票)");
            LogisticsSourceParserTest.row(s,10,"英国大货专线","WBSLLP",0,20);
            var result=parser.parse(LogisticsSourceParserTest.bytes(book),"万邦更新.xlsx",directory());
            assertEquals(1,result.path("channels").size());var c=result.path("channels").get(0);
            assertEquals(2,c.path("rows").size());assertEquals("万邦大货专线挂号含电",c.path("channelName").asText());
            if(code.equals("WRONG")){assertFalse(c.path("quoteReady").asBoolean());continue;}
            assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());identity=c.path("companyChannelId").asText();
            var engine=new LogisticsBillingEngine(mapper);
            assertEquals(52.06,engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.01)).path("total").asDouble());
            assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",10.0005)));
            assertEquals(10,c.path("rows").get(0).path("weightToKg").asDouble());
        }
        assertFalse(identity.isBlank());
    }

    private void roundTrip(tools.jackson.databind.node.ArrayNode channels) throws Exception {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var dataset=UUID.randomUUID();var records=new ArrayList<ObjectNode>();
        for(var c:channels)records.add(mapper.createObjectNode().put("provider",c.path("providerName").asText()).put("channel",c.path("channelName").asText())
            .put("attribute",c.path("logisticsAttribute").asText()).put("id",UUID.randomUUID().toString()).put("status","published").set("version",c));
        org.mockito.Mockito.when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).param("dataset",dataset).param("version",null)
            .query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ObjectNode>>any()).list()).thenReturn(records);
        var exported=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        var restored=parser.parse(exported,"标准导出.xlsx",directory());assertEquals(4,restored.path("channels").size());
        for(var c:restored.path("channels")) {
            assertTrue(c.path("quoteReady").asBoolean(),c.path("issues").toString());
            var original=channels.valueStream().filter(v->v.path("companyChannelId").equals(c.path("companyChannelId"))).findFirst().orElseThrow();
            assertEquals(original.path("rows").size(),c.path("rows").size());
            for(int i=0;i<c.path("rows").size();i++)for(var f:List.of("pricePerKg","registrationFee","minChargeWeightKg","weightFromKg","weightToKg"))
                assertEquals(original.path("rows").get(i).path(f).asDouble(),c.path("rows").get(i).path(f).asDouble());
        }
    }
}
