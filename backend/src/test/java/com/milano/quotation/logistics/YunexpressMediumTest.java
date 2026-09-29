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

class YunexpressMediumTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/yunexpress-medium-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",1));
    }
    byte[] fixture(String tab,String title,String code,int offset,boolean reordered,String step,boolean missingWeight) throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            book.createSheet("目录");
            var s=book.createSheet(tab);
            LogisticsSourceParserTest.row(s,offset,title,"产品代码："+code);
            String[] headers={"国家/地区","重量(KG)","最低计费重(KG)","运费(RMB/KG)","挂号费(RMB/票)","参考时效"};
            Object[] data={"美国",missingWeight?"":"0<W≤2",0.1,155,38,"6-12工作日"};
            int[] order=reordered?new int[]{4,2,5,0,3,1}:new int[]{0,1,2,3,4,5};
            var h=s.createRow(offset+2);var r=s.createRow(offset+3);
            for(int i=0;i<order.length;i++) {
                h.createCell(i+2).setCellValue(headers[order[i]]);
                var v=data[order[i]];if(v instanceof Number n)r.createCell(i+2).setCellValue(n.doubleValue());else r.createCell(i+2).setCellValue(v.toString());
            }
            LogisticsSourceParserTest.row(s,offset+5,"所有国家：起重为0.1KG，不足0.1KG的按0.1KG计费，以"+step+"G为单位进位");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void findsTitlesAndProductCodesAfterSheetRenameRowAndColumnMoves() throws Exception {
        for(boolean codeOnly:List.of(false,true))for(boolean reordered:List.of(false,true)) {
            var result=parser.parse(fixture("九月更新",codeOnly?"云途物流":"云途中包专线挂号（特惠普货）","ZBZXRPH",28,reordered,"1",false),"云途报价.xlsx",directory());
            assertEquals(1,result.path("channels").size(),result.toPrettyString());
            var c=result.path("channels").get(0);assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());
            assertEquals("33a7e612-1e5a-460c-b792-af0cb512b671",c.path("companyChannelId").asText());
            assertEquals("普货",c.path("logisticsAttribute").asText());
            var row=c.path("rows").get(0);assertEquals("per-kg-1g",row.path("pricingModel").asText());
            assertEquals(155,row.path("pricePerKg").asDouble());assertEquals(38,row.path("registrationFee").asDouble());
            assertEquals(.1,row.path("minChargeWeightKg").asDouble());
            var billed=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.100001));
            assertEquals(.101,billed.path("chargeWeightKg").asDouble());assertEquals(53.66,billed.path("total").asDouble());
        }
    }
    @Test void blocksConflictingIdentityMissingWeightAndChangedRounding() throws Exception {
        for(var bytes:List.of(
            fixture("云途中包专线挂号（特惠普货）","云途中包专线挂号（特惠普货）","ZBZXRDD",1,false,"1",false),
            fixture("更新","云途中包专线挂号（特惠普货）","ZBZXRPH",1,false,"2",false),
            fixture("更新","云途中包专线挂号（特惠普货）","ZBZXRPH",1,true,"1",true))) {
            var result=parser.parse(bytes,"云途报价.xlsx",directory());
            assertFalse(result.path("channels").isEmpty()&&result.path("sheets").isEmpty());
            for(var c:result.path("channels"))assertFalse(c.path("quoteReady").asBoolean(),c.toPrettyString());
        }
    }
    @Test @EnabledIfSystemProperty(named="yunexpress.medium.source",matches=".+")
    void reconcilesBothRealChannels() throws Exception {
        var path=Path.of(System.getProperty("yunexpress.medium.source"));var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/yunexpress-medium"));Files.writeString(Path.of("target/yunexpress-medium/parsed.json"),result.toPrettyString());
        assertEquals(2,result.path("channels").size());
        var counts=Map.of("ZBZXRDD",42,"ZBZXRPH",42);var cases=mapper.createArrayNode();
        for(var c:result.path("channels")){
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean(),c.path("blockingReasons").toString());
            assertEquals(counts.get(YunexpressAdditionalRules.named(c.path("channelName").asText()).code()),c.path("rows").size());
        }
        assertEquals(252,result.path("priceCellsParsed").asInt());
        try(var reader=new LogisticsSheetReader(Files.readAllBytes(path),path.getFileName().toString(),name->!name.matches("云途中包专线挂号[（(]特惠(?:带电|普货)[）)]"))) {
            while(reader.hasNext()) {
                var sheet=reader.next();var product=YunexpressAdditionalRules.named(sheet.getSheetName());if(product==null||!product.code().startsWith("ZB"))continue;
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
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));evidence.set("cases",cases);Files.writeString(Path.of("target/yunexpress-medium/billing-cases.json"),evidence.toPrettyString());
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var dataset=UUID.randomUUID();var records=new ArrayList<ObjectNode>();
        for(var c:result.path("channels"))records.add(mapper.createObjectNode().put("provider","云途").put("channel",c.path("channelName").asText())
            .put("attribute",c.path("logisticsAttribute").asText()).put("id",UUID.randomUUID().toString()).put("status","published").set("version",c));
        org.mockito.Mockito.when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).param("dataset",dataset).param("version",null)
            .query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ObjectNode>>any()).list()).thenReturn(records);
        var exported=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        var restored=parser.parse(exported,"云途标准导出.xlsx",directory());assertEquals(2,restored.path("channels").size());
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
