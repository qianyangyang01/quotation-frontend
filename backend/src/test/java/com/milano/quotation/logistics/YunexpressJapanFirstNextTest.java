package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.nio.file.*;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.util.CellRangeAddress;
import tools.jackson.databind.node.ArrayNode;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class YunexpressJapanFirstNextTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    CompanyChannelScope scope() {
        var config=mapper.createObjectNode().put("enabled",true);
        config.putArray("entries").addObject().put("id","7a3866f4-fdf6-4627-9cda-a5a81c496a34")
            .put("providerName","云途").put("channelName",YunexpressJapanFirstNext.NAME).put("logisticsAttribute","普货").putArray("productCodes").add("BKPHR");
        return new CompanyChannelScope(config);
    }
    byte[] fixture(String fault,double first,double next) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet(YunexpressJapanFirstNext.NAME);
            row(s,1,"",YunexpressJapanFirstNext.NAME,"产品代码：BKPHR");
            row(s,3,"","国家/地区","参考时效","分区","重量(KG)","进位制(KG)","最低计费重(KG)","运费(RMB/KG)","挂号费(RMB/票)");
            row(s,4,"","法国","5-8工作日","","0<W≤2","","",70,20);
            row(s,6,"","国家/地区","参考时效","分区","重量(KG)",fault.equals("unit")?"进位制(G)":"进位制(KG)","最低计费重(KG)",fault.equals("header")?"未知价格":"首重","续重","保价服务费(RMB/票)");
            row(s,7,"","日本","4-5工作日",fault.equals("zone")?"1区":"","0<W≤2",.5,.5,first,next,"费率0.7%");
            if(!fault.equals("missing"))row(s,8,"","日本","4-5工作日","",fault.equals("range")?"2<W≤6":"2<W≤5",fault.equals("step")?.25:.5,.5,first,next+1,"费率0.7%");
            if(fault.equals("duplicate"))row(s,9,"","日本","4-5工作日","","2<W≤5",.5,.5,first,next+1);
            if(fault.equals("price"))s.getRow(8).getCell(8).setCellValue("待定");
            if(fault.equals("blank"))s.getRow(8).getCell(8).setBlank();
            if(fault.equals("negative"))s.getRow(8).getCell(8).setCellValue(-6);
            return bytes(book);
        }
    }
    JsonNode channel(byte[] bytes) {var p=parser.parse(bytes,"云途.xlsx",scope());assertEquals(1,p.path("channels").size(),p.toPrettyString());return p.path("channels").get(0);}
    void verify(JsonNode c,double first,double next,int others) {
        assertEquals(0,c.path("errors").asInt(),c.path("issues").toPrettyString());
        assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());
        var jp=mapper.createArrayNode();var refs=new HashSet<Integer>();
        for(var r:c.path("rows"))if(r.path("countryCode").asText().equals("JP")){jp.add(r);refs.add(r.path("sourceRow").asInt());assertEquals("first-next",r.path("sourcePricingModel").asText());assertEquals(4,r.path("etaMinDays").asInt());assertEquals(5,r.path("etaMaxDays").asInt());}
        assertEquals(10,jp.size());assertEquals(2,refs.size());assertEquals(others+10,c.path("rows").size());
        for(int g=1;g<=5000;g+=7)check(jp,g/1000.,first,next);
        for(double b=.5;b<=5;b+=.5)for(double delta:new double[]{-.000001,0,.000001})if(b+delta<=5)check(jp,b+delta,first,next);
        for(double bad:new double[]{0,-.1,5.000001})assertThrows(RuntimeException.class,()->engine.calculate(jp,mapper.createObjectNode().put("country","JP").put("weightKg",bad)));
    }
    void check(JsonNode rows,double w,double first,double next) {
        double charged=Math.ceil(w*2)/2;double expected=first+(charged/.5-1)*(charged<=2?next:next+1);
        assertEquals(expected,engine.calculate(rows,mapper.createObjectNode().put("country","JP").put("weightKg",w)).path("total").asDouble(),1e-8,"weight="+w);
    }
    @Test void originalAndChangedPricesRoundExactlyAtEveryBoundary() throws Exception {
        var bytes=fixture("",33,5);var original=channel(bytes);verify(original,33,5,1);
        assertEquals(original,channel(bytes));var changed=channel(fixture("",34,5.5));verify(changed,34,5.5,1);
        assertNotEquals(original.path("contentHash"),changed.path("contentHash"));
        assertEquals(original.path("companyChannelId"),changed.path("companyChannelId"));
    }
    @Test void malformedOrIncompleteJapanNeverSilentlyPublishesPartialPrices() throws Exception {
        for(String fault:List.of("unit","header","missing","range","step","duplicate","price","blank","negative","zone")){
            var c=channel(fixture(fault,33,5));assertTrue(c.path("errors").asInt()>0,fault+": "+c);assertFalse(c.path("quoteReady").asBoolean(),fault);
        }
    }
    @Test void actualOriginalWorkbookWhenSupplied() throws Exception {
        String source=System.getenv("YUNEXPRESS_JAPAN_SOURCE");if(source==null||source.isBlank())return;
        var c=channel(Files.readAllBytes(Path.of(source)));verify(c,33,5,65);
        var previous=mapper.readTree(Files.readString(Path.of(source).resolveSibling("candidate.json"))).path("rows");
        var comparison=new LogisticsWorkbookService(mapper).compare((ArrayNode)c.path("rows"),(ArrayNode)previous);
        for(String key:List.of("price","added","removed","range","coverageReduced"))assertEquals(0,comparison.path("summary").path(key).asInt(),comparison.toPrettyString());
        for(var before:previous)if(!before.path("countryCode").asText().equals("JP")) {
            var after=new ArrayList<JsonNode>();for(var r:c.path("rows"))if(r.path("rowKey").equals(before.path("rowKey")))after.add(r);
            assertEquals(1,after.size());
            for(String field:List.of("pricingModel","pricePerKg","registrationFee","billingStepKg","minChargeWeightKg","weightFromKg","weightToKg","zoneName","etaMinDays","etaMaxDays"))
                assertEquals(before.path(field).asText(),after.get(0).path(field).asText(),field+": "+before.path("rowKey"));
        }
        String output=System.getenv("YUNEXPRESS_JAPAN_PARSED");if(output!=null)Files.writeString(Path.of(output),c.toPrettyString());
    }
    @Test void mergedCountryAndShiftedHeaderKeepPricesAndSourceCells() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture("",33,5)))) {
            var s=book.getSheetAt(0);s.getRow(8).getCell(1).setBlank();s.addMergedRegion(new CellRangeAddress(7,8,1,1));
            for(int r=6;r<=8;r++){var row=s.getRow(r);var a=row.getCell(7);var b=row.getCell(8);
                if(r==6){var t=a.getStringCellValue();a.setCellValue(b.getStringCellValue());b.setCellValue(t);}
                else {double t=a.getNumericCellValue();a.setCellValue(b.getNumericCellValue());b.setCellValue(t);}}
            var c=channel(bytes(book));verify(c,33,5,1);
            for(var r:c.path("rows"))if(r.path("countryCode").asText().equals("JP"))assertTrue(r.path("sourceFirstPriceCell").asText().startsWith("I"));
        }
    }
    @Test void duplicateHeadersAndAmbiguousProductCodesBlockAutomaticUpdate() throws Exception {
        for(boolean duplicate:new boolean[]{true,false})try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture("",33,5)))) {
            var s=book.getSheetAt(0);
            if(duplicate)s.getRow(6).getCell(9).setCellValue("续重");else s.getRow(1).createCell(3).setCellValue("产品代码：BKZXR");
            var p=parser.parse(bytes(book),"云途.xlsx",scope());
            for(var c:p.path("channels"))assertFalse(c.path("quoteReady").asBoolean(),p.toPrettyString());
            assertTrue(p.path("channels").isEmpty()||p.path("channels").get(0).path("errors").asInt()>0,p.toPrettyString());
        }
    }
}
