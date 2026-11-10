package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KuailvSpecialLineTest {
    static final String NAME="美国专线-特敏感";
    static final int[] RATES={90,90,93,93,96,96},FEES={23,23,23,21,18,15};
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope scope(boolean both)throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/kuailv-special-line-company-channel.json")));
        if(both)config.withArray("entries").add(mapper.readTree(Files.readString(Path.of("../scripts/logistics/kuailv-company-channel.json"))).path("entries").get(0));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",22));
    }
    byte[] fixture(boolean moved)throws Exception {
        try(var b=new XSSFWorkbook()) {
            var s=b.createSheet(moved?"新版页签":"美国混合派");int h=moved?14:1;
            row(s,0,"美国混合派（商派+USPS）");
            if(moved)row(s,h,"处理费（元/票）","渠道名","时效（自然日）","重量段（KG）","发货属性","运费（元/kg)");
            else row(s,h,"渠道名","重量段（KG）","运费（元/kg)","处理费（元/票）","时效（自然日）","发货属性");
            for(int product=0;product<2;product++) {
                String name=product==0?KuailvSourceTest.NAME:NAME;int[] rates=product==0?KuailvSourceTest.RATES:RATES,fees=product==0?KuailvSourceTest.FEES:FEES;
                for(int i=0;i<6;i++) {
                    if(moved)row(s,h+1+product*6+i,fees[i],i==0?name:"","10-18天",KuailvSourceTest.RANGES[i],"种子、植物、树苗（不能带泥土）",rates[i]);
                    else row(s,h+1+product*6+i,i==0?name:"",KuailvSourceTest.RANGES[i],rates[i],fees[i],"10-18天","种子、植物、树苗（不能带泥土）");
                }
                s.addMergedRegion(new CellRangeAddress(h+1+product*6,h+6+product*6,moved?1:0,moved?1:0));
            }
            if(moved)row(s,h+13,1,"美国专线-特货","8-15天","0-5","普货",1);else row(s,h+13,"美国专线-特货","0-5",1,1,"8-15天","普货");
            row(s,h+14,moved?"":"渠道说明",moved?"渠道说明":"");
            row(s,h+15,"计费方式","起重均为1g，计费向上取整，计费向上取整；计泡公式：长*宽*高/8000");row(s,h+16,"附加费","无");
            var other=b.createSheet("欧洲小包");row(other,0,"国家","重量","单价（RMB/KG）","挂号费(RMB/票)");row(other,1,"德国","0-5",1,1);
            return bytes(b);
        }
    }
    ObjectNode channel(ObjectNode parsed,String name) {
        var matches=parsed.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(name)).toList();
        assertEquals(1,matches.size(),parsed.toPrettyString());return (ObjectNode)matches.getFirst();
    }
    byte[] onlySpecialFixture()throws Exception {
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(2).getCell(0).setCellValue("美国商派-特货");return bytes(b);
        }
    }
    @Test void isolatedScopeAndDualImportNeverMixSimilarChannelPrices()throws Exception {
        var bytes=fixture(false);var single=parser.parse(bytes,"快驴.xlsx",scope(false));assertEquals(1,single.path("channels").size());
        var both=parser.parse(bytes,"快驴.xlsx",scope(true));assertEquals(2,both.path("channels").size());
        var selected=channel(single,NAME);assertTrue(selected.path("quoteReady").asBoolean(),single.toPrettyString());
        assertEquals(selected.path("contentHash"),channel(both,NAME).path("contentHash"));
        var moved=parser.parse(fixture(true),"快驴.xlsx",scope(true));assertEquals(2,moved.path("channels").size());
        assertEquals(selected.path("contentHash"),channel(moved,NAME).path("contentHash"));
        new KuailvSourceTest().verifyBilling(selected,RATES,FEES);
        new KuailvSourceTest().verifyBilling(channel(both,KuailvSourceTest.NAME));
        assertTrue(both.path("sheets").get(0).path("filteredOtherRows").valueStream().noneMatch(r->r.path("row").asInt()>=3&&r.path("row").asInt()<=14));
        assertEquals("",LogisticsParserCompatibility.blockingReason("kuailv-us-sensitive-2026.11.10-v16"));
    }
    @Test void missingSpecialLineFeeBlocksOnlyThatProductWithoutBorrowingMerchantFee()throws Exception {
        for(int col:new int[]{1,2,3})try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(false)))) {
            b.getSheetAt(0).getRow(9).getCell(col).setBlank();var parsed=parser.parse(bytes(b),"快驴.xlsx",scope(true));
            assertFalse(channel(parsed,NAME).path("quoteReady").asBoolean());
            assertTrue(channel(parsed,KuailvSourceTest.NAME).path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="kuailv.source",matches=".+")
    void realWorkbookReconcilesAllSpecialLineCellsAndPreservesMerchant()throws Exception {
        var parsed=parser.parse(Files.readAllBytes(Path.of(System.getProperty("kuailv.source"))),"快驴欧洲小包-包税VIP.xlsx",scope(true));
        assertEquals(2,parsed.path("channels").size());var special=channel(parsed,NAME);
        assertTrue(special.path("quoteReady").asBoolean(),special.toPrettyString());assertEquals(6,special.path("rows").size());
        for(int i=0;i<6;i++) {var r=special.path("rows").get(i);assertEquals(21+i,r.path("sourceRow").asInt());assertEquals(RATES[i],r.path("pricePerKg").asInt());assertEquals(FEES[i],r.path("registrationFee").asInt());assertEquals(KuailvSourceTest.RANGES[i],r.path("sourceWeightRange").asText());assertEquals(10,r.path("etaMinDays").asInt());assertEquals(18,r.path("etaMaxDays").asInt());}
        new KuailvSourceTest().verifyBilling(special,RATES,FEES);new KuailvSourceTest().verifyBilling(channel(parsed,KuailvSourceTest.NAME));
        var output=Path.of("../artifacts/kuailv-us-special-line-20261110/parsed.json");Files.createDirectories(output.getParent());Files.writeString(output,special.toPrettyString());
    }
}
