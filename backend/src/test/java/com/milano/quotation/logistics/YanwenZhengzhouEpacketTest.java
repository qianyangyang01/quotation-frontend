package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YanwenZhengzhouEpacketTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LogisticsSourceParser parser = new LogisticsSourceParser(mapper, new LogisticsWorkbookService(mapper));
    byte[] source() throws Exception { return getClass().getResourceAsStream("/logistics/yanwen-zhengzhou-epacket.xlsx").readAllBytes(); }
    CompanyChannelScope scope() { return new CompanyChannelScope(mapper.readTree("""
        {"enabled":true,"revision":1,"entries":[{"id":"zhengzhou","providerName":"燕文","channelName":"中邮郑州线下E邮宝","productCodes":["556"],"aliases":[],"logisticsAttribute":"普货","enabled":true}]}
        """)); }
    @Test void originalWorkbookUsesExistingParserAndAddsLinehaulExactlyOnce() throws Exception {
        var parsed=parser.parse(source(),"中邮郑州线下E邮宝.xlsx",scope());
        Files.writeString(Path.of("target/zhengzhou-parsed.json"),parsed.toPrettyString());
        assertEquals(1,parsed.path("channels").size(),parsed.toString());
        var channel=parsed.path("channels").get(0);
        assertEquals("燕文",channel.path("providerName").asText());
        assertEquals("中邮郑州线下E邮宝",channel.path("channelName").asText());
        assertEquals(58,channel.path("rows").size());
        assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toString());
        var countries=new HashSet<String>();var canada=new HashSet<String>();
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(source()))) {
            var sheet=workbook.getSheetAt(0);
            for(var row:channel.path("rows")) {
                var raw=sheet.getRow(row.path("sourceRow").asInt()-1);
                var price=new BigDecimal(raw.getCell(3).toString());
                var linehaul=new BigDecimal(raw.getCell(7).toString());
                assertEquals(0,price.add(linehaul).compareTo(row.path("pricePerKg").decimalValue()),row.toString());
                assertEquals(0,linehaul.compareTo(row.path("sourceLinehaulPerKg").decimalValue()));
                assertEquals(0,row.path("linehaulPerKg").asDouble());
                assertEquals(new BigDecimal(raw.getCell(4).toString()).doubleValue(),row.path("registrationFee").asDouble());
                assertEquals("556",row.path("sourceProductCode").asText());
                assertEquals(.001,row.path("weightFromKg").asDouble());
                assertTrue(row.path("weightFromInclusive").asBoolean());
                assertTrue(row.path("weightToInclusive").asBoolean());
                assertFalse(row.path("blockingReason").asText().contains("干线费"),row.toString());
                countries.add(row.path("countryCode").asText());
                if(row.path("countryCode").asText().equals("CA")) {canada.add(row.path("zoneName").asText());assertEquals(15,row.path("etaMinDays").asInt());assertEquals(30,row.path("etaMaxDays").asInt());}
            }
        }
        assertEquals(52,countries.size());assertEquals(7,canada.size());
        var au=find(channel,"AU");assertEquals(65,au.path("pricePerKg").asDouble());assertEquals(25,au.path("registrationFee").asDouble());
        assertEquals(0,find(channel,"MA").path("etaMinDays").asInt());
        var once=channel.deepCopy();LogisticsReadiness.apply((ObjectNode)once);LogisticsReadiness.apply((ObjectNode)once);
        assertEquals(65,find(once,"AU").path("pricePerKg").asDouble());
        var engine=new LogisticsBillingEngine(mapper);
        assertTrue(engine.unsupported(channel.path("rows")).isEmpty());
        for(double weight:new double[]{.001,.1,1,2}) {
            var input=mapper.createObjectNode().put("country","AU").put("weightKg",weight);
            assertEquals(BigDecimal.valueOf(weight).multiply(BigDecimal.valueOf(65)).add(BigDecimal.valueOf(25)).setScale(2,java.math.RoundingMode.HALF_UP),engine.calculate(channel.path("rows"),input).path("total").decimalValue().setScale(2));
        }
        for(double weight:new double[]{.0005,2.001})assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","AU").put("weightKg",weight)));
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","CA").put("weightKg",1)));
        for(var row:channel.path("rows"))if(row.path("countryCode").asText().equals("CA"))assertEquals(row.path("pricePerKg").asDouble()+row.path("registrationFee").asDouble(),engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country","CA").put("weightKg",1).put("zoneName",row.path("zoneName").asText())).path("total").asDouble());
    }
    @Test void invalidOrMissingLinehaulBlocksRatherThanSilentlyUndercharging() throws Exception {
        for(String replacement:new String[]{"", "未知", "-3"})try(var book=WorkbookFactory.create(new ByteArrayInputStream(source()))) {
            book.getSheetAt(0).getRow(32).getCell(7).setCellValue(replacement);
            var bytes=new java.io.ByteArrayOutputStream();book.write(bytes);
            var parsed=parser.parse(bytes.toByteArray(),"中邮郑州线下E邮宝.xlsx",scope());
            assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0,replacement);
        }
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(source()))) {
            book.getSheetAt(0).getRow(3).getCell(7).setCellValue("干线费(元/票)");
            var bytes=new java.io.ByteArrayOutputStream();book.write(bytes);
            assertTrue(parser.parse(bytes.toByteArray(),"中邮郑州线下E邮宝.xlsx",scope()).path("channels").get(0).path("errors").asInt()>0);
        }
    }
    @Test void namedWorksheetAndSheet1HaveIdenticalBusinessPrices() throws Exception {
        var original=parser.parse(source(),"中邮郑州线下E邮宝.xlsx",scope()).path("channels").get(0);
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(source()))) {
            book.setSheetName(0,"中邮郑州线下E邮宝");
            var bytes=new java.io.ByteArrayOutputStream();book.write(bytes);
            var renamed=parser.parse(bytes.toByteArray(),"中邮郑州线下E邮宝.xlsx",scope()).path("channels").get(0);
            assertEquals(original.path("contentHash"),renamed.path("contentHash"));
            assertEquals(original.path("rows").size(),renamed.path("rows").size());
            assertEquals(original.path("errors"),renamed.path("errors"));
        }
    }
    @Test void otherYanwenChannelsRetainTheirExistingLinehaulReviewRule() throws Exception {
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(source()))) {
            book.setSheetName(0,"中邮上海线下E邮宝");book.getSheetAt(0).getRow(0).getCell(0).setCellValue("中邮上海线下E邮宝");
            var bytes=new java.io.ByteArrayOutputStream();book.write(bytes);
            var channel=parser.parse(bytes.toByteArray(),"燕文.xlsx").path("channels").get(0);
            assertEquals(62,find(channel,"AU").path("pricePerKg").asDouble());
            assertEquals(3,find(channel,"AU").path("linehaulPerKg").asDouble());
            assertTrue(find(channel,"AU").path("reviewWarning").asText().contains("干线费"));
        }
    }
    JsonNode find(JsonNode channel,String country) {for(var row:channel.path("rows"))if(row.path("countryCode").asText().equals(country))return row;throw new AssertionError(country);}
}
