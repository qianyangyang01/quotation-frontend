package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BaizhouSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope scope()throws Exception {
        var c=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/baizhou-company-channel.json")));
        CompanyChannelService.validate(c.path("entries"));return new CompanyChannelScope(c);
    }
    ObjectNode parse(byte[] bytes)throws Exception {
        var result=parser.parse(bytes,"百洲最新报价.xlsx",scope());
        assertEquals(1,result.path("channels").size(),result.toPrettyString());return (ObjectNode)result.path("channels").get(0);
    }
    byte[] fixture()throws Exception {
        try(var b=new XSSFWorkbook()){
            var s=b.createSheet("BZ-欧美服装专线挂号");row(s,0,"BZ-欧美服装专线挂号");row(s,1,"渠道代码：BZ503");
            row(s,2,"国家","重量段（）","运费（RMB/）","挂号费（RMB/票）","参考时效（工作日）");
            String[] names={"美国","英国","德国","法国","意大利","西班牙","葡萄牙"};int[] limits={30,20,30,30,5,30,30};
            for(int i=0;i<7;i++)row(s,i+3,names[i],"0-"+limits[i]+"KG",80,20,9);
            row(s,10,"三、禁限运物品");row(s,11,"原表不包税，用户已确认本渠道包税、全邮编");
            var other=b.createSheet("BZ-欧美化妆品专线挂号");row(other,0,"BZ-欧美化妆品专线挂号");row(other,1,"渠道代码：BZ504");
            return bytes(b);
        }
    }
    @Test void identityAndConfirmedBillingRemainIsolated()throws Exception {
        var c=parse(fixture());assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(1,c.path("rows").size());
        assertEquals("百州",c.path("providerName").asText());assertEquals("服装",c.path("logisticsAttribute").asText());
        var engine=new LogisticsBillingEngine(mapper);
        for(double[] pair:new double[][]{{.00001,21},{.1,28},{.100001,29},{30,2420}})
            assertEquals(pair[1],engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",pair[0])).path("total").asDouble());
    }
    @Test void missingPricesIdentityCountriesAndTiersFailClosed()throws Exception {
        for(int col:new int[]{0,1,2,3})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(3).getCell(col).setBlank();assertFalse(parse(bytes(b)).path("pricingReady").asBoolean(),"column "+col);
        }
        for(String code:List.of("渠道代码：BZ504"))try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(1).getCell(0).setCellValue(code);assertFalse(parse(bytes(b)).path("pricingReady").asBoolean(),code);
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(3).getCell(1).setCellValue("0.001-30KG");assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(2).createCell(5).setCellValue("运费（RMB/KG）");assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());
        }
    }
    @Test void excludedCountriesAreFilteredBeforePriceParsing()throws Exception {
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            for(int r=4;r<=9;r++)for(int col=1;col<=3;col++)b.getSheetAt(0).getRow(r).getCell(col).setCellValue("不导入的其他国家价格");
            b.setSheetName(0,"更新的工作表名");
            var c=parse(bytes(b));assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(1,c.path("rows").size());
            assertEquals("US",c.path("rows").get(0).path("countryCode").asText());
        }
    }
    @Test @EnabledIfSystemProperty(named="baizhou.package",matches=".+")
    void originalWorkbookCorruptionCannotPartiallyReplace()throws Exception {
        var source=Files.readAllBytes(Path.of(System.getProperty("baizhou.package")).resolve("source.xlsx"));
        for(int col:new int[]{2,3,4})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(source))){
            b.getSheet("BZ-欧美服装专线挂号").getRow(9).getCell(col).setBlank();assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());
        }
        for(String range:List.of("0.15-0.45","0.25-0.45"))try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(source))){
            b.getSheet("BZ-欧美服装专线挂号").getRow(5).getCell(2).setCellValue(range);assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="baizhou.package",matches=".+")
    void originalWorkbookAllRatesAndEveryGramMatchIndependentExtraction()throws Exception {
        var p=Path.of(System.getProperty("baizhou.package"));var bytes=Files.readAllBytes(p.resolve("source.xlsx"));
        var e=mapper.readTree(Files.readString(p.resolve("expected.json")));var c=parse(bytes);
        Files.writeString(p.resolve("parsed.json"),c.toPrettyString());
        assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(7,c.path("rows").size());
        assertEquals(e.path("sha256").asText(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        for(var expected:e.path("rows")){
            var actual=c.path("rows").valueStream().filter(r->r.path("sourceRow").equals(expected.path("sourceRow"))).findFirst().orElseThrow();
            for(var f:expected.properties())assertTrue(f.getValue().isNumber()?actual.path(f.getKey()).decimalValue().compareTo(f.getValue().decimalValue())==0:actual.path(f.getKey()).equals(f.getValue()),f.getKey()+expected);
        }
        var engine=new LogisticsBillingEngine(mapper);int checked=0;
        for(String country:List.of("US")){
            var rows=e.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals(country)).toList();
            int max=rows.stream().mapToInt(r->r.path("weightToKg").decimalValue().movePointRight(3).intValueExact()).max().orElseThrow();
            for(int gram=1;gram<=max;gram++){
                BigDecimal w=BigDecimal.valueOf(gram,3);
                var match=rows.stream().filter(r->w.compareTo(r.path("weightFromKg").decimalValue())>=0&&w.compareTo(r.path("weightToKg").decimalValue())<=0).toList();assertEquals(1,match.size());
                var r=match.getFirst();var amount=w.multiply(r.path("pricePerKg").decimalValue()).add(r.path("registrationFee").decimalValue()).setScale(0,RoundingMode.CEILING);
                assertEquals(0,amount.compareTo(engine.calculate(c.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",w)).path("total").decimalValue()));checked++;
            }
        }
        var cases=mapper.readTree(Files.readString(p.resolve("billing-cases.json")));
        for(var sample:cases.path("samples"))assertEquals(0,sample.path("total").decimalValue().compareTo(engine.calculate(c.path("rows"),sample).path("total").decimalValue()),sample.toString());
        for(var sample:cases.path("rejected"))assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),sample));
        Files.writeString(p.resolve("validation.json"),mapper.createObjectNode().put("passed",true).put("rows",7).put("priceFields",14).put("gramCases",checked).put("boundaryCases",cases.path("samples").size()).toPrettyString());
    }
}
