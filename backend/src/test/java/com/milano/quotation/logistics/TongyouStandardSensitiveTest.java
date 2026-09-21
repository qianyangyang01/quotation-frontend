package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TongyouStandardSensitiveTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/tongyou-standard-sensitive-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",22));
    }
    ObjectNode only(ObjectNode result){assertEquals(1,result.path("channels").size());return (ObjectNode)result.path("channels").get(0);}
    @Test @EnabledIfSystemProperty(named="tongyou.source",matches=".+")
    void reconcilesAllRealPricesAndCountrySpecificMinimums() throws Exception {
        var path=Path.of(System.getProperty("tongyou.source"));var bytes=Files.readAllBytes(path);
        var result=parser.parse(bytes,path.getFileName().toString(),directory());var channel=only(result);
        Files.createDirectories(Path.of("target/tongyou-sensitive"));Files.writeString(Path.of("target/tongyou-sensitive/parsed.json"),result.toPrettyString());
        assertEquals("标准挂号类-敏感",channel.path("channelName").asText());
        assertEquals(79,channel.path("rows").size());assertEquals(35,channel.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().count());
        assertEquals(0,channel.path("errors").asInt(),channel.toPrettyString());assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
        assertEquals(158,result.path("priceCellsParsed").asInt());
        var cases=mapper.createArrayNode();
        try(var reader=new LogisticsSheetReader(bytes,path.getFileName().toString(),name->!name.equals("线上敏感"))) {
            while(reader.hasNext()) {
                var sheet=reader.next();if(!sheet.getSheetName().equals("线上敏感"))continue;
                var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
                for(var row:channel.path("rows")) {
                    var original=sheet.getRow(row.path("sourceRow").asInt()-1);var range=format.formatCellValue(original.getCell(1)).split("-");
                    var lo=new BigDecimal(range[0]);var hi=new BigDecimal(range[1]);
                    var rate=new BigDecimal(format.formatCellValue(original.getCell(2)));var fee=new BigDecimal(format.formatCellValue(original.getCell(3)));
                    assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                    assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());
                    var floor=new BigDecimal(row.path("countryCode").asText().equals("US")?"0.05":"0.01");
                    assertEquals(floor.doubleValue(),row.path("minChargeWeightKg").asDouble(),row.toString());
                    var weights=new ArrayList<BigDecimal>(List.of(hi,lo.add(hi).divide(BigDecimal.TWO)));
                    if(lo.signum()==0)weights.addAll(List.of(new BigDecimal("0.001"),floor.subtract(new BigDecimal("0.000001")),floor,floor.add(new BigDecimal("0.000001"))));
                    for(var actual:weights) {
                        var computed=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("weightKg",actual));
                        var charged=actual.max(floor);assertEquals(charged.doubleValue(),computed.path("chargeWeightKg").asDouble());
                        assertEquals(charged.multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),computed.path("total").asDouble());
                        cases.addObject().put("country",row.path("countryCode").asText()).put("weightKg",actual).set("expected",computed);
                    }
                }
            }
        }
        var evidence=mapper.createObjectNode();evidence.set("channel",channel);evidence.set("cases",cases);Files.writeString(Path.of("target/tongyou-sensitive/billing-cases.json"),evidence.toPrettyString());
    }
    byte[] fixture(double rate,int minimum,boolean badWeight) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("改名后的价格");row(s,2,"标准挂号类-敏感");
            row(s,5,"挂号费/票","国家","运费/kg","重量段");
            row(s,6,24,"美国",rate,badWeight?"":"0-0.1");row(s,7,24,"",rate,"0.101-0.2");
            s.addMergedRegion(new CellRangeAddress(6,7,1,1));row(s,8,23,"加拿大",88,"0-0.1");
            row(s,10,"美国50G起重，其他国家"+minimum+"G起重");
            var other=book.createSheet("线上标准");row(other,0,"国家","重量段","运费/kg","挂号费/票");row(other,1,"美国","0-1","INVALID","INVALID");
            return bytes(book);
        }
    }
    @Test void keepsIdentityAfterSheetColumnAndPriceUpdatesAndReadsNewMinimum() throws Exception {
        var old=only(parser.parse(fixture(91,10,false),"通邮旧价格.xlsx",directory()));
        var next=only(parser.parse(fixture(93,20,false),"通邮更新.xlsx",directory()));
        assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(next.path("quoteReady").asBoolean(),next.toPrettyString());
        assertEquals(old.path("companyChannelId"),next.path("companyChannelId"));assertEquals(old.path("rows").get(0).path("rowKey"),next.path("rows").get(0).path("rowKey"));
        assertNotEquals(old.path("contentHash"),next.path("contentHash"));
        for(var r:next.path("rows"))assertEquals(r.path("countryCode").asText().equals("US")?.05:.02,r.path("minChargeWeightKg").asDouble());
    }
    @Test void blocksMissingWeightInsteadOfImportingPartialCountry() throws Exception {
        var c=only(parser.parse(fixture(91,10,true),"通邮.xlsx",directory()));assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("quoteReady").asBoolean());
    }
    @Test void blocksMissingMinimumEvidence() throws Exception {
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(91,10,false)))) {
            book.getSheetAt(0).removeRow(book.getSheetAt(0).getRow(10));
            var c=only(parser.parse(bytes(book),"通邮.xlsx",directory()));assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("quoteReady").asBoolean());
        }
    }
}
