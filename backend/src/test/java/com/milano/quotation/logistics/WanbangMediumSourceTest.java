package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WanbangMediumSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);

    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/wanbang-medium-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));
        return new CompanyChannelScope(config.put("enabled",true).put("revision",29));
    }

    byte[] fixture(boolean moved,String defect,double rate) throws Exception {
        try(var book=new XSSFWorkbook()) {
            // A different product may precede the requested sheets and must remain outside the import scope.
            var other=book.createSheet("其他报价");
            LogisticsSourceParserTest.row(other,0,"国家","产品名称","产品代码","重量段","公斤重(RMB/KG)","操作费(RMB/PCS)");
            LogisticsSourceParserTest.row(other,1,"美国","万邦大货专线含电","WBSLLP","0-10KG",999,999);
            for(boolean powered:List.of(false,true)) {
                String name="万邦中包专线挂号"+(powered?"含电":"普货");
                var s=book.createSheet(moved?"更新页"+(powered?2:1):name);
                int h=moved?36:4;
                LogisticsSourceParserTest.row(s,h-3,powered?"万邦速达中包专线含电报价表(RMB)":"万邦速达中包专线挂号普货报价表(RMB)");
                var labels=new ArrayList<>(List.of("国家","产品名称","产品代码","参考时效（工作日）","重量限制(KG)","尺寸限制","重量段","公斤重(RMB/KG)","操作费(RMB/PCS)"));
                if(moved)Collections.rotate(labels,4);
                var header=s.createRow(h);
                for(int c=0;c<labels.size();c++)header.createCell(c).setCellValue(
                    defect.equals("weight-column")&&labels.get(c).equals("重量段")?"未知列":
                    defect.equals("fee-column")&&labels.get(c).contains("操作费")?"未知费用列":labels.get(c));
                var values=new HashMap<String,Object>();
                values.put("国家","美国");values.put("产品名称",defect.equals("title-only")?"":"万邦中包专线"+(powered?"含电":"普货"));
                values.put("产品代码",defect.equals("code")?"WRONG":powered?"WBSLMP":"WBSLMPPH");
                values.put("参考时效（工作日）","9--12");values.put("重量限制(KG)","0-30KG");values.put("尺寸限制","长<120CM");
                for(int i=0;i<2;i++) {
                    values.put("重量段",defect.equals("weight-row")&&i==1?"":i==0?"0-10KG":"10.001-30KG");
                    values.put("公斤重(RMB/KG)",powered?rate:rate-5);values.put("操作费(RMB/PCS)",i==0?50:60);
                    var row=s.createRow(h+1+i);
                    for(int c=0;c<labels.size();c++) {
                        var label=labels.get(c);if(i==1&&!Set.of("重量段","公斤重(RMB/KG)","操作费(RMB/PCS)").contains(label))continue;
                        var value=values.get(label);var cell=row.createCell(c);
                        if(value instanceof Number number)cell.setCellValue(number.doubleValue());else cell.setCellValue(value.toString());
                    }
                }
                for(int c=0;c<labels.size();c++)if(!Set.of("重量段","公斤重(RMB/KG)","操作费(RMB/PCS)").contains(labels.get(c)))
                    s.addMergedRegion(new CellRangeAddress(h+1,h+2,c,c));
                LogisticsSourceParserTest.row(s,h+4,"注意事项：");
                LogisticsSourceParserTest.row(s,h+5,"包裹实重范围","材积计算标准","计费标准");
                LogisticsSourceParserTest.row(s,h+6,"0-30KG","长*宽*高/12000","取实重和体积重中较大值计费");
            }
            return LogisticsSourceParserTest.bytes(book);
        }
    }

    @Test void scansMovedHeadersAndMergedRowsWithStableIdentitiesAndUpdatedPrices() throws Exception {
        var original=parser.parse(fixture(false,"",80),"万邦原表.xlsx",directory());
        var updated=parser.parse(fixture(true,"",83),"更新报价.xlsx",directory());
        for(var result:List.of(original,updated)) {
            assertEquals(2,result.path("channels").size(),result.toPrettyString());
            for(var channel:result.path("channels")) {
                assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
                assertEquals(2,channel.path("rows").size());
                assertEquals(10,channel.path("rows").get(0).path("weightToKg").asDouble());
                assertEquals(10.001,channel.path("rows").get(1).path("weightFromKg").asDouble());
            }
        }
        for(var current:updated.path("channels")) {
            var prior=original.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(current.path("companyChannelId"))).findFirst().orElseThrow();
            assertNotEquals(prior.path("contentHash"),current.path("contentHash"));
            assertEquals(prior.path("rows").get(0).path("pricePerKg").asDouble()+3,current.path("rows").get(0).path("pricePerKg").asDouble());
        }
        var titleOnly=parser.parse(fixture(true,"title-only",80),"更新报价.xlsx",directory());
        assertEquals(2,titleOnly.path("channels").size());
        for(var c:titleOnly.path("channels"))assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());
    }

    @Test void refusesMissingPriceWeightOperationFeeAndIncorrectProductCode() throws Exception {
        for(var defect:List.of("code","weight-row","weight-column","fee-column")) {
            var result=parser.parse(fixture(true,defect,80),"万邦更新.xlsx",directory());
            assertEquals(2,result.path("channels").size(),defect+result.toPrettyString());
            for(var c:result.path("channels")) {
                assertFalse(c.path("quoteReady").asBoolean(),defect+c.toPrettyString());
                assertTrue(c.path("errors").asInt()>0,defect+c.toPrettyString());
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="wanbang.medium.source",matches=".+")
    void reconcilesOriginalWorkbookAndBillingBoundaries() throws Exception {
        var path=Path.of(System.getProperty("wanbang.medium.source"));
        var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));var cases=evidence.putArray("cases");
        Files.createDirectories(Path.of("target/wanbang-medium"));
        Files.writeString(Path.of("target/wanbang-medium/parsed.json"),result.toPrettyString());
        assertEquals(2,result.path("channels").size(),result.toPrettyString());
        for(var channel:result.path("channels")) {
            boolean powered=channel.path("channelName").asText().endsWith("含电");double rate=powered?80:75;
            assertEquals(powered?"带电":"普货",channel.path("logisticsAttribute").asText());
            assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
            assertEquals(0,channel.path("errors").asInt());assertEquals(2,channel.path("rows").size());
            for(int i=0;i<2;i++) {
                var row=channel.path("rows").get(i);
                assertEquals("US",row.path("countryCode").asText());
                assertEquals(powered?"WBSLMP":"WBSLMPPH",row.path("sourceProductCode").asText());
                assertEquals(rate,row.path("pricePerKg").asDouble());assertEquals(i==0?50:60,row.path("registrationFee").asDouble());
                assertEquals(i==0?0:10.001,row.path("weightFromKg").asDouble());assertEquals(i==0?10:30,row.path("weightToKg").asDouble());
                assertEquals(6+i,row.path("sourceRow").asInt());assertEquals(9,row.path("etaMinDays").asInt());assertEquals(12,row.path("etaMaxDays").asInt());
            }
            for(double weight:List.of(.001,1d,9.999,10d,10.001,10.002,20d,30d)) {
                var input=mapper.createObjectNode().put("country","US").put("weightKg",weight);
                double total=BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(weight)).add(BigDecimal.valueOf(weight<=10?50:60)).setScale(2,RoundingMode.HALF_UP).doubleValue();
                assertEquals(total,engine.calculate(channel.path("rows"),input).path("total").asDouble());
                cases.addObject().put("channel",channel.path("channelName").asText()).put("weightKg",weight).put("total",total);
            }
            for(double weight:List.of(10.0005,30.001))assertThrows(AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",weight)));
        }
        Files.writeString(Path.of("target/wanbang-medium/billing-cases.json"),evidence.toPrettyString());
    }
}
