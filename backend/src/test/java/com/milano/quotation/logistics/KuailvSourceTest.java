package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
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

class KuailvSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    static final String NAME="美国商派-特敏感";
    static final String[] RANGES={"0-0.2","0.201-0.453","0.454-0.7","0.701-1","1.001-2","2.001-5"};
    static final int[] RATES={86,88,93,93,96,96}, FEES={23,21,21,18,15,13}, ENDS={200,453,700,1000,2000,5000};
    CompanyChannelScope directory()throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/kuailv-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",17));
    }
    ObjectNode parse(byte[] bytes)throws Exception {
        var parsed=parser.parse(bytes,"快驴欧洲小包-包税VIP.xlsx",directory());
        assertEquals(1,parsed.path("channels").size(),parsed.toPrettyString());return (ObjectNode)parsed.path("channels").get(0);
    }
    byte[] fixture(boolean moved)throws Exception {
        try(var b=new XSSFWorkbook()) {
            var s=b.createSheet(moved?"新版页签":"美国混合派");int h=moved?14:1;
            row(s,0,"美国混合派（商派+USPS）");
            if(moved)row(s,h,"处理费（元/票）","渠道名","时效（自然日）","重量段（KG）","发货属性","运费（元/kg)");
            else row(s,h,"渠道名","重量段（KG）","运费（元/kg)","处理费（元/票）","时效（自然日）","发货属性");
            for(int i=0;i<6;i++) {
                if(moved)row(s,h+1+i,FEES[i],i==0?NAME:"","10-18天",RANGES[i],"种子，植物，树苗等（不能带泥土）",RATES[i]);
                else row(s,h+1+i,i==0?NAME:"",RANGES[i],RATES[i],FEES[i],"10-18天","种子，植物，树苗等（不能带泥土）");
            }
            s.addMergedRegion(new CellRangeAddress(h+1,h+6,moved?1:0,moved?1:0));
            if(moved)row(s,h+7,1,"美国专线-特敏感","8-15天","0-5","保健品",1);
            else row(s,h+7,"美国专线-特敏感","0-5",1,1,"8-15天","保健品");
            row(s,h+8,moved?"":"渠道说明",moved?"渠道说明":"");
            row(s,h+9,"计费方式","起重均为1g，计费向上取整，计费向上取整；计泡公式：长*宽*高/8000");
            row(s,h+10,"参考时效","10-15工作日");row(s,h+11,"附加费","无");
            var other=b.createSheet("欧洲小包");row(other,0,"国家","重量","单价（RMB/KG）","挂号费(RMB/票)");row(other,1,"德国","0-5",1,1);
            return bytes(b);
        }
    }
    @Test void exactProductDynamicLayoutAndAllGramBoundaries()throws Exception {
        var a=parse(fixture(false));var b=parse(fixture(true));
        assertTrue(a.path("quoteReady").asBoolean(),a.toPrettyString());assertEquals(a.path("contentHash"),b.path("contentHash"));
        assertEquals("保健品",a.path("logisticsAttribute").asText());assertEquals(6,a.path("rows").size());
        verifyBilling(a);
        for(var r:a.path("rows")){assertEquals(10,r.path("etaMinDays").asInt());assertEquals(18,r.path("etaMaxDays").asInt());}
    }
    void verifyBilling(ObjectNode channel) {
        var engine=new LogisticsBillingEngine(mapper);
        for(int grams=1;grams<=5000;grams++) {
            int tier=0;while(grams>ENDS[tier])tier++;
            // Independent integer arithmetic: ceil(grams * yuan/kg / 1000 + fee).
            int expected=(grams*RATES[tier]+999)/1000+FEES[tier];
            for(var weight:List.of(BigDecimal.valueOf(grams,3),BigDecimal.valueOf(grams,3).subtract(new BigDecimal("0.0005")))) {
                var result=engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",weight));
                assertEquals(expected,result.path("total").asInt(),weight.toString());assertEquals(tier,result.path("rowIndex").asInt());
                assertEquals(BigDecimal.valueOf(grams,3).doubleValue(),result.path("chargeWeightKg").asDouble());
            }
        }
        for(double w:new double[]{0,-1,5.000001})assertThrows(AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",w)));
        assertThrows(AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","GB").put("weightKg",.1)));
    }
    @Test void missingInvalidAndOverlappingCriticalValuesBlockWholeChannel()throws Exception {
        for(int col:new int[]{1,2,3})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(3).getCell(col).setBlank();assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        for(String range:List.of("bad","0.1-0.453","0.201-0.1","0.3-0.453"))try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(3).getCell(1).setCellValue(range);assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(1).getCell(3).setBlank();assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(1).createCell(6).setCellValue("燃油附加费");assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
    }
    @Test void changedMinimumOrMissingProductIdentityCannotSilentlyPublish()throws Exception {
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(10).getCell(1).setCellValue("起重均为10g，计费向上取整");assertFalse(parse(bytes(b)).path("quoteReady").asBoolean());
        }
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(2).getCell(0).setCellValue("美国商派-特货");
            assertEquals(0,parser.parse(bytes(b),"快驴.xlsx",directory()).path("channels").size());
        }
        assertEquals("",LogisticsParserCompatibility.blockingReason("wanbang-preferred-clothing-2026.11.09-v15"));
    }
    @Test @EnabledIfSystemProperty(named="kuailv.source",matches=".+")
    void realWorkbookReconcilesEveryPriceCellAndWeight()throws Exception {
        var raw=Files.readAllBytes(Path.of(System.getProperty("kuailv.source")));var c=parse(raw);
        assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());assertEquals(6,c.path("rows").size());
        for(int i=0;i<6;i++){var r=c.path("rows").get(i);assertEquals(i+9,r.path("sourceRow").asInt());assertEquals(RATES[i],r.path("pricePerKg").asInt());assertEquals(FEES[i],r.path("registrationFee").asInt());assertEquals(RANGES[i],r.path("sourceWeightRange").asText());}
        verifyBilling(c);
        Files.writeString(Path.of("../artifacts/kuailv-us-sensitive-20261110/parsed.json"),c.toPrettyString());
    }
}
