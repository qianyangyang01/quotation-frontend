package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WuyouBrazilSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode parse(byte[] bytes)throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/wuyou-brazil-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));
        return parser.parse(bytes,"无忧物流和线上发货运费报价-20261010生效.xlsx",new CompanyChannelScope(config.put("enabled",true).put("revision",31)));
    }
    byte[] fixture()throws Exception {
        try(var b=new XSSFWorkbook()) {
            var s=b.createSheet("无忧物流-标准");
            row(s,7,"1. 小包1g起重，按克计费。大包计重规则，实际重量与体积重取大值");
            row(s,8,"2. 小包普货计费：适用于普货（申报且实际品类不含有电池/非液体化妆品），且包裹重量（申报重量且实际重量）＜=2kg，且包裹单边长度<=60cm，且包裹长+宽+高<=90cm");
            row(s,219,"运达国家/地区","运达国家/地区（英文）","Code","小包普货计费","","","","","","","","小包非普货计费");
            row(s,220,"","","","0~200g(含)","","200~300g(含)","","300~500g(含)","","500~2000g(含)");
            row(s,221,"","","","配送服务费\n元（RMB）/KG\n*每1g计重","挂号服务费\n元（RMB）/包裹","配送服务费\n元（RMB）/KG\n*每1g计重","挂号服务费\n元（RMB）/包裹","配送服务费\n元（RMB）/KG\n*每1g计重","挂号服务费\n元（RMB）/包裹","配送服务费\n元（RMB）/KG\n*每1g计重","挂号服务费\n元（RMB）/包裹");
            row(s,222,"巴西","Brazil","BR",68.26,29.01,81.92,32.01,81.92,34.01,89.36,35.01,1,1,1,1);
            row(s,230,"美国","United States","US",1,1);
            row(b.createSheet("无忧物流-特货标准"),0,"巴西","Brazil","BR",1,1);
            return bytes(b);
        }
    }
    void check(ObjectNode result) {
        assertEquals(1,result.path("channels").size(),result.toPrettyString());
        var c=result.path("channels").get(0);assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());
        assertEquals("无忧物流",c.path("providerName").asText());assertEquals("普货",c.path("logisticsAttribute").asText());
        assertEquals(4,c.path("rows").size());
        String[] rates={"68.26","81.92","81.92","89.36"},fees={"29.01","32.01","34.01","35.01"};
        for(int i=0;i<4;i++) {
            var r=c.path("rows").get(i);assertEquals("BR",r.path("countryCode").asText());
            assertEquals(0,new BigDecimal(rates[i]).compareTo(r.path("pricePerKg").decimalValue()));
            assertEquals(0,new BigDecimal(fees[i]).compareTo(r.path("registrationFee").decimalValue()));
            assertEquals("per-kg-1g",r.path("pricingModel").asText());assertFalse(r.path("volumetric").asBoolean());
            assertEquals(223,r.path("sourceRow").asInt());assertEquals(.001,r.path("minChargeWeightKg").asDouble());
        }
        var engine=new LogisticsBillingEngine(mapper);
        for(int grams=1;grams<=2000;grams++) {
            int i=grams<=200?0:grams<=300?1:grams<=500?2:3;
            var w=new BigDecimal(grams).movePointLeft(3);
            var expected=w.multiply(new BigDecimal(rates[i])).add(new BigDecimal(fees[i])).setScale(2,RoundingMode.HALF_UP);
            for(var actual:List.of(w,w.subtract(new BigDecimal("0.0001"))))
                assertEquals(0,expected.compareTo(engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","BR").put("weightKg",actual)).path("total").decimalValue()),"grams="+grams);
        }
        for(String country:List.of("US","CN"))assertThrows(RuntimeException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",.1)));
        assertThrows(RuntimeException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","BR").put("weightKg",2.00001)));
    }
    @Test void isolatesOnlyBrazilGeneralAndChecksEveryGram()throws Exception {check(parse(fixture()));}
    @Test void refusesMissingPricesHeadersRulesAndDuplicateBrazil()throws Exception {
        for(int col=3;col<11;col++)try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))) {
            b.getSheetAt(0).getRow(222).getCell(col).setBlank();assertFalse(parse(bytes(b)).path("channels").get(0).path("quoteReady").asBoolean());
        }
        for(int r:new int[]{7,8,219,220,221})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))) {
            b.getSheetAt(0).getRow(r).getCell(r<9?0:3).setCellValue("变更后未适配");assertFalse(parse(bytes(b)).path("channels").get(0).path("quoteReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))) {
            row(b.getSheetAt(0),235,"巴西","Brazil","BR",1,1);assertFalse(parse(bytes(b)).path("channels").get(0).path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="wuyou.source",matches=".+")
    void actualWorkbook()throws Exception {
        var result=parse(Files.readAllBytes(Path.of(System.getProperty("wuyou.source"))));check(result);
        var out=Path.of("target/wuyou/parsed.json");Files.createDirectories(out.getParent());Files.writeString(out,result.toPrettyString());
    }
}
