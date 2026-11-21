package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WanbangSensitiveNameCorrectionTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode directory() {
        var d=mapper.createObjectNode().put("enabled",true);var entries=d.putArray("entries");
        for(var p:List.of(new String[]{"sensitive","万邦优选专线敏感","欧洲优选专线敏感","ECSL-P"},
                new String[]{"clothing","万邦优选服装专线","欧洲优选服装专线","EUSLPHFZ-P"})) {
            var e=entries.addObject().put("id",p[0]).put("providerName","万邦").put("channelName",p[1]).put("enabled",true)
                    .put("logisticsAttribute",p[0].equals("sensitive")?"普货、带电、化妆品":"服装");
            e.putArray("aliases").add(p[2]).add(p[1].replace("万邦","万邦速达")+"报价表(RMB)");
            e.putArray("productCodes").add(p[3]);
        }
        return d;
    }
    byte[] fixture(String sheetName,String title,String country,String name,String code,boolean moved,int increase,boolean invalidPrice) throws Exception {
        try(var b=new XSSFWorkbook()) {
            var s=b.createSheet(sheetName);
            LogisticsSourceParserTest.row(s,0,title);
            var headers=new ArrayList<>(List.of("国家","产品名称","产品代码","参考时效（工作日）","重量限制(KG)","尺寸限制","重量段","公斤重(RMB/KG)","操作费(RMB/PCS)"));
            if(moved)Collections.rotate(headers,3);
            int h=moved?8:4;
            LogisticsSourceParserTest.row(s,h,headers.toArray());
            for(int i=0;i<2;i++) {
                var values=Map.<String,Object>of("国家",country,"产品名称",name,"产品代码",code,"参考时效（工作日）","12--15",
                        "重量限制(KG)","0-10","尺寸限制","60*40*40cm","重量段",i==0?"0-0.5KG":"0.501-10KG",
                        "公斤重(RMB/KG)",invalidPrice&&i==1?"错误价格":75+i*4+increase,"操作费(RMB/PCS)",29);
                var row=s.createRow(h+i+1);
                for(int c=0;c<headers.size();c++) {
                    var key=headers.get(c);if(i==1&&!Set.of("重量段","公斤重(RMB/KG)","操作费(RMB/PCS)").contains(key))continue;
                    var value=values.get(key);var cell=row.createCell(c);
                    if(value instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(value.toString());
                }
            }
            for(int c=0;c<headers.size();c++)if(!Set.of("重量段","公斤重(RMB/KG)","操作费(RMB/PCS)").contains(headers.get(c)))
                s.addMergedRegion(new CellRangeAddress(h+1,h+2,c,c));
            return LogisticsSourceParserTest.bytes(b);
        }
    }
    JsonNode parse(String country,boolean moved,int increase) throws Exception {
        return parser.parse(fixture("万邦优选专线敏感","万邦速达优选专线敏感报价表(RMB)",country,"欧洲优选服装专线","ECSL-P",moved,increase,false),
                moved?"万邦20271201更新.xlsx":"万邦20261123.xlsx",new CompanyChannelScope(directory()));
    }
    @Test void confirmedThreeCountriesKeepAllTiersAndFuturePricesWithTraceableSourceNames() throws Exception {
        for(String country:List.of("希腊","保加利亚","塞浦路斯"))for(boolean moved:List.of(false,true)) {
            int increase=moved?13:0;var result=parse(country,moved,increase);
            assertEquals(1,result.path("channels").size());var c=result.path("channels").get(0);
            assertEquals("sensitive",c.path("companyChannelId").asText());
            assertEquals("万邦优选专线敏感",c.path("channelName").asText());
            assertEquals(0,c.path("errors").asInt(),result.toPrettyString());assertTrue(c.path("quoteReady").asBoolean());
            assertEquals(2,c.path("rows").size());assertEquals(0,result.path("ambiguousChannels").asInt());
            for(int i=0;i<2;i++) {
                var row=c.path("rows").get(i);
                assertEquals(75+i*4+increase,row.path("pricePerKg").asDouble());assertEquals(29,row.path("registrationFee").asDouble());
                assertEquals(i==0?0:0.501,row.path("weightFromKg").asDouble());assertEquals(i==0?0.5:10,row.path("weightToKg").asDouble());
                assertEquals("ECSL-P",row.path("sourceProductCode").asText());assertEquals("欧洲优选服装专线",row.path("sourceProductName").asText());
                assertEquals("wanbang-sensitive-gr-bg-cy-20261121",row.path("sourceIdentityCorrectionRule").asText());
                assertTrue(row.path("sourceIdentityConfirmation").asText().contains("2026-11-21"));
                assertTrue(row.path("reviewWarning").asText().contains("已按用户确认"));
            }
        }
    }
    @Test void unconfirmedCountriesSheetsTitlesAndCodesDoNotGetTheException() throws Exception {
        for(var p:List.of(
                new String[]{"万邦优选专线敏感","万邦速达优选专线敏感报价表(RMB)","德国","ECSL-P"},
                new String[]{"其他表","万邦速达优选专线敏感报价表(RMB)","希腊","ECSL-P"},
                new String[]{"万邦优选专线敏感","万邦速达优选服装专线报价表(RMB)","希腊","ECSL-P"},
                new String[]{"万邦优选专线敏感","","希腊","ECSL-P"})) {
            var result=parser.parse(fixture(p[0],p[1],p[2],"欧洲优选服装专线",p[3],false,0,false),"万邦.xlsx",new CompanyChannelScope(directory()));
            assertTrue(result.path("ambiguousChannels").asInt()>0,result.toPrettyString());
            assertFalse(result.toString().contains("wanbang-sensitive-gr-bg-cy-20261121"));
        }
        var clothing=parser.parse(fixture("万邦优选专线敏感","万邦速达优选专线敏感报价表(RMB)","希腊","欧洲优选服装专线","EUSLPHFZ-P",false,0,false),"万邦.xlsx",new CompanyChannelScope(directory()));
        assertEquals("clothing",clothing.path("channels").get(0).path("companyChannelId").asText());
        assertFalse(clothing.toString().contains("wanbang-sensitive-gr-bg-cy-20261121"));
    }
    @Test void disabledOrAmbiguousRegistrationAndInvalidPricesStillCannotPublish() throws Exception {
        var bytes=fixture("万邦优选专线敏感","万邦速达优选专线敏感报价表(RMB)","希腊","欧洲优选服装专线","ECSL-P",false,0,false);
        for(boolean duplicate:List.of(false,true)) {
            var d=directory();
            if(duplicate)((tools.jackson.databind.node.ArrayNode)d.path("entries")).add(((ObjectNode)d.path("entries").get(0)).deepCopy().put("id","duplicate"));
            else ((ObjectNode)d.path("entries").get(0)).put("enabled",false);
            var result=parser.parse(bytes,"万邦.xlsx",new CompanyChannelScope(d));
            assertFalse(result.toString().contains("wanbang-sensitive-gr-bg-cy-20261121"));
            assertTrue(result.path("ambiguousChannels").asInt()>0);
        }
        var invalid=parser.parse(fixture("万邦优选专线敏感","万邦速达优选专线敏感报价表(RMB)","希腊","欧洲优选服装专线","ECSL-P",false,0,true),"万邦.xlsx",new CompanyChannelScope(directory()));
        assertFalse(invalid.path("channels").get(0).path("quoteReady").asBoolean());
        assertTrue(invalid.path("channels").get(0).path("errors").asInt()>0);
        assertEquals("",LogisticsParserCompatibility.blockingReason("jitong-custom-minimum-2026.11.19-v25"));
    }
}
