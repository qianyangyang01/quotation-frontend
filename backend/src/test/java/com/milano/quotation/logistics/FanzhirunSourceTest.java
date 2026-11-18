package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FanzhirunSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode parse(byte[] bytes)throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/fanzhirun-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));
        var result=parser.parse(bytes,"帆之润-价格.xls",new CompanyChannelScope(config));
        assertEquals(1,result.path("channels").size(),result.toPrettyString());return (ObjectNode)result.path("channels").get(0);
    }
    byte[] fixture()throws Exception {
        try(var b=new HSSFWorkbook()){
            var s=b.createSheet("美国小包-普货");row(s,0,"帆之润美国专线小包");
            row(s,1,"国家","渠道名称","可走货属性","重量区间","KG运费","处理费","参考时效","备注");row(s,2,"美国");
            var ranges=List.of("0.05-0.2","0.201-0.45","0.451-0.7","0.701-1","1.001-3");
            for(int i=0;i<5;i++)row(s,i+3,"",i==0?"美国小包-普货":i==3?"代码：PK0003":"",i==0?"只能普货":"",ranges.get(i),i<2?62:76,i<2?17:i==2?16:12,i==0?"7-15自然日":"",i==0?"50g起重 (泡重为:长*宽*高cm/6000)":"");
            for(String m:List.of("A3:A8","B4:B6","B7:B8","C4:C8","G4:G8","H4:H8"))s.addMergedRegion(CellRangeAddress.valueOf(m));
            row(s,8,"只接受上述产品");row(s,9,"责任条款");
            var other=b.createSheet("美国小包-商派");row(other,0,"帆之润美国专线小包");row(other,3,"美国","美国小包-商派","只能普货","0.05-3",1,1);
            b.createSheet("美国小包-仿牌");b.createSheet("禁运清单");return bytes(b);
        }
    }
    @Test void originalLayoutAndOtherSheetsRemainIsolated()throws Exception {
        var c=parse(fixture());assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(5,c.path("rows").size());
        assertEquals("普货、服装",c.path("logisticsAttribute").asText());
        var engine=new LogisticsBillingEngine(mapper);
        for(double[] sample:new double[][]{{.000001,21},{.05,21},{.2,30},{.200001,30},{.45,45},{.450001,51},{.7,70},{.700001,66},{1,88},{1.000001,89},{3,240}})
            assertEquals(sample[1],engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",sample[0])).path("total").asDouble());
    }
    @Test void missingCellsConflictsAndExtraPriceRowsFailClosed()throws Exception {
        for(int[] position:new int[][]{{1,4},{1,5},{3,3},{7,3},{3,4},{7,5},{3,7},{2,0},{3,6},{3,2}})
            try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){b.getSheetAt(0).getRow(position[0]).getCell(position[1]).setBlank();assertFalse(parse(bytes(b)).path("pricingReady").asBoolean(),Arrays.toString(position));}
        for(String range:List.of("0.2-0.45","0.3-0.45"))try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(4).getCell(3).setCellValue(range);assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());
        }
        try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){row(b.getSheetAt(0),8,"美国","","","3.001-4",90,12);assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());}
        try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){b.getSheetAt(0).getRow(3).getCell(4).setCellFormula("60+2");assertFalse(parse(bytes(b)).path("pricingReady").asBoolean());}
    }
    @Test void changingOrMissingProductCodeUsesRegisteredNameAndPreservesActualCode()throws Exception {
        for(String code:List.of("PK0066","PK2027001","NEW-US-99",""))try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(3).getCell(1).setCellValue("美国小包-特惠普货");
            b.getSheetAt(0).getRow(3).getCell(2).setCellValue("普货");
            b.getSheetAt(0).getRow(3).getCell(6).setCellValue("5-8工作日");
            b.getSheetAt(0).getRow(6).getCell(1).setCellValue(code.isEmpty()?"":"代码："+code);
            var c=parse(bytes(b));assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());
            assertEquals(code,c.path("rows").get(0).path("sourceProductCode").asText());
            assertEquals(5,c.path("rows").get(0).path("etaMinDays").asInt());
        }
    }
    @Test void independentFBrandBlockNeverContaminatesGeneralCargoEvenWhenCodeMatches()throws Exception {
        try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            var s=b.getSheetAt(0);row(s,8,"美国","美国小包-F牌","服装","0.05-3",999,999,"5-8工作日","50g起重 (泡重为:长*宽*高cm/6000)");
            row(s,9,"","代码：PK0066");
            var c=parse(bytes(b));assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(5,c.path("rows").size());
            assertEquals(62,c.path("rows").get(0).path("pricePerKg").asInt());
        }
    }
    @Test void blockLocationAndTierCountAreNotFixed()throws Exception {
        try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            var s=b.getSheetAt(0);row(s,8,"美国","","普货","3.001-4",90,12,"5-8工作日","50g起重 (泡重为:长*宽*高cm/6000)");
            s.shiftRows(0,9,3);b.setSheetName(0,"11月新价格");
            var c=parse(bytes(b));assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(6,c.path("rows").size());
        }
    }
    @Test void conflictingNameAndCodeStillBlocks()throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/fanzhirun-company-channel.json")));
        var other=((ObjectNode)config.path("entries").get(0)).deepCopy().put("id",UUID.randomUUID().toString()).put("channelName","另一渠道");
        other.putArray("aliases");other.putArray("productCodes").add("PKOTHER");config.withArray("entries").add(other);
        try(var b=new HSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))){
            b.getSheetAt(0).getRow(6).getCell(1).setCellValue("代码：PKOTHER");
            var result=parser.parse(bytes(b),"帆之润.xls",new CompanyChannelScope(config));
            assertEquals(0,result.path("channels").size());assertTrue(result.toString().contains("产品代码与渠道名称指向不同渠道"));
        }
    }
    @Test @EnabledIfSystemProperty(named="fanzhirun.package",matches=".+")
    void originalWorkbookRatesAndEveryGramMatchIndependentExtraction()throws Exception {
        var p=Path.of(System.getProperty("fanzhirun.package"));var raw=Files.readAllBytes(p.resolve("source.xls"));
        var e=mapper.readTree(Files.readString(p.resolve("expected.json")));var c=parse(raw);Files.writeString(p.resolve("parsed.json"),c.toPrettyString());
        assertTrue(c.path("pricingReady").asBoolean(),c.toPrettyString());assertEquals(5,c.path("rows").size());
        assertEquals(e.path("sha256").asText(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        for(var expected:e.path("rows")){
            var actual=c.path("rows").valueStream().filter(r->r.path("sourceRow").equals(expected.path("sourceRow"))).findFirst().orElseThrow();
            for(var f:expected.properties())assertTrue(f.getValue().isNumber()?actual.path(f.getKey()).decimalValue().compareTo(f.getValue().decimalValue())==0:actual.path(f.getKey()).equals(f.getValue()),f.getKey()+actual);
        }
        var engine=new LogisticsBillingEngine(mapper);
        for(int gram=1;gram<=3000;gram++){
            var w=BigDecimal.valueOf(Math.max(50,gram),3);
            var r=e.path("rows").valueStream().filter(x->w.compareTo(x.path("weightFromKg").decimalValue())>=0&&w.compareTo(x.path("weightToKg").decimalValue())<=0).findFirst().orElseThrow();
            var amount=w.multiply(r.path("pricePerKg").decimalValue()).add(r.path("registrationFee").decimalValue()).setScale(0,RoundingMode.CEILING);
            assertEquals(0,amount.compareTo(engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",BigDecimal.valueOf(gram,3))).path("total").decimalValue()));
        }
        var cases=mapper.readTree(Files.readString(p.resolve("billing-cases.json")));
        for(var sample:cases.path("samples"))assertEquals(0,sample.path("total").decimalValue().compareTo(engine.calculate(c.path("rows"),sample).path("total").decimalValue()),sample.toString());
        for(var sample:cases.path("rejected"))assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),sample));
        Files.writeString(p.resolve("validation.json"),mapper.createObjectNode().put("passed",true).put("rows",5).put("priceFields",10).put("gramCases",3000).put("boundaryCases",cases.path("samples").size()).toPrettyString());
    }
}
