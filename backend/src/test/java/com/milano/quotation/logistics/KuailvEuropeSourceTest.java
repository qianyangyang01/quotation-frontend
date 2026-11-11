package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KuailvEuropeSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope scope()throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/kuailv-eu-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",30));
    }
    ObjectNode parse(byte[] bytes)throws Exception {
        var parsed=parser.parse(bytes,"快驴欧洲小包-包税VIP.xlsx",scope());
        assertEquals(1,parsed.path("channels").size(),parsed.toPrettyString());return (ObjectNode)parsed.path("channels").get(0);
    }
    byte[] fixture(boolean moved)throws Exception {
        try(var b=new XSSFWorkbook()) {
            var s=b.createSheet(moved?"最新欧盟页签":KuailvEuropeSourceRules.TITLE);int h=moved?8:1;
            row(s,0,KuailvEuropeSourceRules.TITLE);
            if(moved){row(s,h,"挂号费(RMB/票)","单价（RMB/KG）","重量","国家");row(s,h+1,27,70,"0-1KG（首重0.001KG)","德国");row(s,h+2,30,68,"1-30KG","");row(s,h+3,1,1,"0-30KG（首重0.001KG)","英国");}
            else{row(s,h,"国家","重量","单价（RMB/KG）","挂号费(RMB/票)");row(s,h+1,"德国","0-1KG（首重0.001KG)",70,27);row(s,h+2,"","1-30KG",68,30);row(s,h+3,"英国","0-30KG（首重0.001KG)",1,1);}
            s.addMergedRegion(new CellRangeAddress(h+1,h+2,moved?3:0,moved?3:0));
            row(s,h+4,"渠道须知");row(s,h+5,"重量须知","其他国家1克起重，单票不超过30KG。");
            return bytes(b);
        }
    }
    @Test void dynamicHeadersMergedCountriesEuFilterAndMissingEtaRemainQuotable()throws Exception {
        var a=parse(fixture(false));var b=parse(fixture(true));assertTrue(a.path("quoteReady").asBoolean(),a.toPrettyString());
        assertEquals(2,a.path("rows").size());assertEquals(a.path("contentHash"),b.path("contentHash"));
        assertEquals("保健品",a.path("logisticsAttribute").asText());assertFalse(a.path("etaReady").asBoolean());
        for(var r:a.path("rows")){assertEquals("DE",r.path("countryCode").asText());assertEquals(0,r.path("etaMinDays").asInt());assertEquals("missing",r.path("etaStatus").asText());assertEquals(.001,r.path("minChargeWeightKg").asDouble());}
        var engine=new LogisticsBillingEngine(mapper);
        for(var pair:new double[][]{{.00001,28},{1,97},{1.000001,99},{30,2070}})assertEquals(pair[1],engine.calculate(a.path("rows"),mapper.createObjectNode().put("country","DE").put("weightKg",pair[0])).path("total").asDouble());
        assertThrows(RuntimeException.class,()->engine.calculate(a.path("rows"),mapper.createObjectNode().put("country","GB").put("weightKg",1)));
    }
    @Test void missingPricesRangesCountriesAndDuplicateColumnsBlockInsteadOfGuessing()throws Exception {
        for(int col:new int[]{1,2,3})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(3).getCell(col).setBlank();assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        for(String range:List.of("0.5-30KG","1.5-30KG","1KG","1-30KG（首重0.1KG)"))try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(3).getCell(1).setCellValue(range);assertFalse(parse(bytes(b)).path("quoteReady").asBoolean(),range);
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).removeMergedRegion(0);assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(1).createCell(4).setCellValue("单价（RMB/KG）");assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="kuailv.eu.source",matches=".+")
    void actualWorkbookMatchesIndependent69RowsAndAllBoundarySamples()throws Exception {
        var bytes=Files.readAllBytes(Path.of(System.getProperty("kuailv.eu.source")));var c=parse(bytes);
        assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());assertEquals(69,c.path("rows").size());
        var evidence=mapper.readTree(Files.readString(Path.of("../artifacts/kuailv-eu-special-20261111/source-evidence.json")));
        assertEquals(evidence.path("sha256").asText(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var countries=new HashSet<String>();var engine=new LogisticsBillingEngine(mapper);int cases=0;
        for(var expected:evidence.path("rows")) {
            var actual=c.path("rows").valueStream().filter(r->r.path("sourceRow").asInt()==expected.path("sourceRow").asInt()).findFirst().orElseThrow();
            countries.add(actual.path("countryCode").asText());assertEquals(expected.path("countryCode"),actual.path("countryCode"));
            assertEquals(expected.path("weight").asText(),actual.path("sourceWeightRange").asText());
            for(var key:List.of("pricePerKg","registrationFee","weightFromKg","weightToKg","minChargeWeightKg"))assertEquals(0,expected.path(key).decimalValue().compareTo(actual.path(key).decimalValue()),key+expected);
            for(var key:List.of("weightFromInclusive","weightToInclusive"))assertEquals(expected.path(key),actual.path(key));
            assertEquals(0,actual.path("etaMinDays").asInt());assertEquals(0,actual.path("etaMaxDays").asInt());
            var hi=actual.path("weightToKg").decimalValue();var lo=actual.path("weightFromKg").decimalValue();
            var min=actual.path("minChargeWeightKg").decimalValue();
            var start=actual.path("weightFromInclusive").asBoolean()?lo.max(min):lo.add(new BigDecimal(".001")).max(min);
            for(var charged:List.of(start,hi,start.add(hi).divide(BigDecimal.TWO).setScale(3,RoundingMode.CEILING))) {
                for(var input:List.of(charged,charged.subtract(new BigDecimal(".0005")))) {
                    if(input.signum()<=0)continue;
                    var price=charged.multiply(expected.path("pricePerKg").decimalValue()).add(expected.path("registrationFee").decimalValue()).setScale(0,RoundingMode.CEILING);
                    var result=engine.calculate(c.path("rows"),mapper.createObjectNode().put("country",actual.path("countryCode").asText()).put("weightKg",input));
                    assertEquals(0,price.compareTo(result.path("total").decimalValue()),expected+" weight="+input);cases++;
                }
            }
        }
        assertEquals(KuailvEuropeSourceRules.EU,countries);assertTrue(cases>=400);
        var output=Path.of("../artifacts/kuailv-eu-special-20261111/parsed.json");Files.writeString(output,c.toPrettyString());
        for(var code:List.of("GB","NO","CH","RS","US"))assertThrows(RuntimeException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country",code).put("weightKg",.5)));
        assertEquals(4596,engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","MT").put("weightKg",20)).path("total").asInt());
        assertEquals(287,engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","RO").put("weightKg",3)).path("total").asInt());
        var us=parser.parse(bytes,"快驴欧洲小包-包税VIP.xlsx",new KuailvSpecialLineTest().scope(true));assertEquals(2,us.path("channels").size());
        for(var channel:us.path("channels")){assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());assertEquals(6,channel.path("rows").size());}
    }
}
