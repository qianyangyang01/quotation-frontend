package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class JisuSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode only(ObjectNode result) {
        assertEquals(1,result.path("channels").size(),result.toPrettyString());
        return (ObjectNode)result.path("channels").get(0);
    }
    CompanyChannelScope directory() throws Exception {
        var json=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/jisu-us-company-channel.json")));
        CompanyChannelService.validate(json.path("entries"));
        return new CompanyChannelScope(json.put("enabled",true).put("revision",18));
    }
    byte[] fixture(double rate, int shift) throws Exception {
        try(var book=new XSSFWorkbook()) {
            book.createSheet("其他国家").createRow(0).createCell(0).setCellValue("急速化妆品专线");
            var s=book.createSheet("更新后的美国价格");
            row(s,shift,"备注","运费（RMB/KG）","Country 国家","操作费（RMB/件）","重量/KG","渠道代码","参考时效（工作日）","产品属性");
            row(s,shift+1,"不可机器分拣附加费：28RMB/票",rate,"美国",20,"0.05<W≤0.1",JisuSourceRules.CODE,"8-10","化妆品 单瓶容量不超100ml");
            row(s,shift+2,"",65,"",19,"0.1<W≤0.2","","","");
            for(int c:new int[]{0,2,5,6,7})s.addMergedRegion(new CellRangeAddress(shift+1,shift+2,c,c));
            row(s,shift+3,"","INVALID","美国","INVALID","0.05<W≤0.1","JS02-邮编普货-2","8-10","仅限服装");
            row(s,shift+4,"","INVALID","加拿大","INVALID","0.05<W≤0.1",JisuSourceRules.CODE,"8-10","");
            row(s,shift+6,"美国计费说明：长*宽*高cm/8000=KG，包裹实际重量和体积重量取较大者计算");
            return bytes(book);
        }
    }
    @Test void filtersBeforeReadingPricesAndPreservesMergedEvidence() throws Exception {
        var out=parser.parse(fixture(63,4),"JS-更新0921.xlsx",directory());
        var c=only(out);assertEquals(JisuSourceRules.CHANNEL,c.path("channelName").asText());
        assertEquals(JisuSourceRules.PROVIDER,c.path("providerName").asText());
        assertEquals("非液体化妆品",c.path("logisticsAttribute").asText());
        assertEquals(0,c.path("errors").asInt(),c.toPrettyString());
        assertEquals(2,c.path("rows").size());assertEquals(4,out.path("priceCellsParsed").asInt());
        assertEquals(2,out.path("sheets").get(1).path("filteredOtherRows").size());
        for(var r:c.path("rows")) {
            assertEquals("US",r.path("countryCode").asText());assertEquals(JisuSourceRules.CODE,r.path("sourceProductCode").asText());
            assertEquals(8000,r.path("volumeDivisor").asInt());assertEquals(8,r.path("etaMinDays").asInt());assertEquals(10,r.path("etaMaxDays").asInt());
            assertTrue(r.path("notes").asText().contains("100ml"));assertTrue(r.path("notes").asText().contains("28RMB"));
        }
    }
    @Test void updatesPriceWithoutChangingChannelOrTierIdentity() throws Exception {
        var before=only(parser.parse(fixture(63,1),"JS-0825.xlsx",directory()));
        var renamed=only(parser.parse(fixture(63,7),"九月调整.xlsx",directory()));
        var after=only(parser.parse(fixture(88.5,7),"九月调整.xlsx",directory()));
        assertEquals(before.path("companyChannelId"),after.path("companyChannelId"));
        assertEquals(before.path("contentHash"),renamed.path("contentHash"));
        assertNotEquals(before.path("contentHash"),after.path("contentHash"));
        assertEquals(before.path("rows").get(0).path("rowKey"),after.path("rows").get(0).path("rowKey"));
        assertEquals(88.5,after.path("rows").get(0).path("pricePerKg").asDouble());
    }
    @Test void billsMinimumOnceAndRetainsTierBoundariesAfterUpdates() throws Exception {
        var engine=new LogisticsBillingEngine(mapper);
        var c=only(parser.parse(fixture(63,1),"JS-价格.xlsx",directory()));
        for(double[] item:new double[][]{{.012,23.15},{.049,23.15},{.05,23.15},{.051,23.21},{.1,26.3},{.101,25.57},{.2,32}}) {
            var result=engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",item[0]));
            assertEquals(item[1],result.path("total").asDouble());
            assertEquals(Math.max(.05,item[0]),result.path("chargeWeightKg").asDouble());
        }
        assertTrue(c.path("rows").get(0).path("weightFromInclusive").asBoolean());
        assertFalse(c.path("rows").get(1).path("weightFromInclusive").asBoolean());
        assertEquals("0.05<W≤0.1",c.path("rows").get(0).path("sourceWeightRange").asText());
        var updated=only(parser.parse(fixture(88.5,7),"JS-新价.xlsx",directory()));
        assertEquals(24.43,engine.calculate(updated.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.012)).path("total").asDouble());
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.201)));
    }
    @Test void changedStartingBoundaryNeedsExplicitMinimumInsteadOfSilentFiftyGramAssumption() throws Exception {
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(63,1)))) {
            var sheet=book.getSheetAt(1);sheet.getRow(2).getCell(4).setCellValue("0.08<W≤0.1");
            var changed=only(parser.parse(bytes(book),"JS-新价.xlsx",directory()));
            assertFalse(changed.path("quoteReady").asBoolean());
            assertTrue(changed.path("rows").get(0).path("blockingReason").asText().contains("最低计费重量"));
            sheet.getRow(1).createCell(8).setCellValue("最小计费重量(KG)");
            sheet.getRow(2).createCell(8).setCellValue(.08);
            var explicit=only(parser.parse(bytes(book),"JS-新价.xlsx",directory()));
            assertEquals(.08,explicit.path("rows").get(0).path("minChargeWeightKg").asDouble());
            assertEquals(25.04,new LogisticsBillingEngine(mapper).calculate(explicit.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.012)).path("total").asDouble());
        }
    }
    @Test void neverBroadensProductCodeOrDisabledDirectory() throws Exception {
        assertFalse(JisuSourceRules.allowed("US","JS02-邮编普货-2"));
        assertFalse(JisuSourceRules.allowed("CA",JisuSourceRules.CODE));
        assertFalse(JisuSourceRules.allowedStandard("US",JisuSourceRules.CHANNEL,"JS02-邮编普货-2"));
        var disabled=(ObjectNode)directory().snapshot();
        ((ObjectNode)disabled.path("entries").get(0)).put("enabled",false);
        assertEquals(0,parser.parse(fixture(63,1),"JS-更新.xlsx",new CompanyChannelScope(disabled)).path("channels").size());
    }
    @Test @EnabledIfSystemProperty(named="jisu.source",matches=".+")
    void reconcilesEveryRealUsSensitiveTier() throws Exception {
        var p=Path.of(System.getProperty("jisu.source"));
        var result=parser.parse(Files.readAllBytes(p),p.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/jisu"));Files.writeString(Path.of("target/jisu/parsed.json"),result.toPrettyString());
        var c=only(result);assertEquals(0,c.path("errors").asInt(),c.toPrettyString());assertEquals(9,c.path("rows").size());
        double[] from={.05,.1,.2,.3,.45,.7,1,1.5,2},to={.1,.2,.3,.45,.7,1,1.5,2,5};
        double[] rates={63,65,63,63,63,72,72,72,72},fees={20,19,19,19,19,11,11,11,11};
        for(int i=0;i<9;i++) {
            var r=c.path("rows").get(i);assertEquals(from[i],r.path("weightFromKg").asDouble());assertEquals(to[i],r.path("weightToKg").asDouble());
            assertEquals(rates[i],r.path("pricePerKg").asDouble());assertEquals(fees[i],r.path("registrationFee").asDouble());
            assertEquals("US",r.path("countryCode").asText());assertEquals(i+3,r.path("sourceRow").asInt());assertEquals(8000,r.path("volumeDivisor").asInt());
            assertEquals(JisuSourceRules.CODE,r.path("sourceProductCode").asText());
            assertEquals(.05,r.path("minChargeWeightKg").asDouble());
            assertEquals(i==0,r.path("weightFromInclusive").asBoolean());
        }
        assertEquals(18,result.path("priceCellsParsed").asInt());
        var unscoped=only(parser.parse(Files.readAllBytes(p),p.getFileName().toString()));
        assertEquals(c.path("contentHash"),unscoped.path("contentHash"));
        assertEquals(JisuSourceRules.CHANNEL,unscoped.path("channelName").asText());
        var engine=new LogisticsBillingEngine(mapper);var cases=mapper.createArrayNode();
        for(double actual:new double[]{.012,.049,.05,.051,.1,.101,.2,.201,.3,.301,.45,.451,.7,.701,1,1.001,1.5,1.501,2,2.001,5}) {
            int tier=0;while(actual>to[tier])tier++;
            var expected=BigDecimal.valueOf(Math.max(.05,actual)).multiply(BigDecimal.valueOf(rates[tier])).add(BigDecimal.valueOf(fees[tier])).setScale(2,java.math.RoundingMode.HALF_UP);
            var computed=engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",actual));
            assertEquals(expected.doubleValue(),computed.path("total").asDouble());
            cases.addObject().put("actual",actual).put("total",expected).put("chargeWeightKg",Math.max(.05,actual));
        }
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",5.001)));
        var evidence=mapper.createObjectNode();evidence.set("channel",c);evidence.set("cases",cases);
        Files.writeString(Path.of("target/jisu/billing-cases.json"),evidence.toPrettyString());
    }
}
