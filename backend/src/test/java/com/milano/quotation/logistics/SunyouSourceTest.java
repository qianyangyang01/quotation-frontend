package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SunyouSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        try(var stream=getClass().getResourceAsStream("/logistics-repairs/sunyou-company-channels-20260922.json")) {
            var config=(ObjectNode)mapper.readTree(stream.readAllBytes());CompanyChannelService.validate(config.path("entries"));
            return new CompanyChannelScope(config.put("enabled",true).put("revision",23));
        }
    }
    ObjectNode first(byte[] bytes)throws Exception {var result=parser.parse(bytes,"顺友.xlsx",directory());assertEquals(1,result.path("channels").size(),result.toPrettyString());return (ObjectNode)result.path("channels").get(0);}
    byte[] fixture(String secondLimit,boolean closed,boolean reordered,String footer)throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("价格已改名");row(s,2,"顺速宝特货价目表");
            if(reordered) {
                row(s,5,"处理费(元/件)","限重(KG)","代码","单价(元/KG)","中文");
                row(s,6,15,.113,"US",85,"美国(一区)","");row(s,7,16,secondLimit,"US",85,"美国(一区)",closed?"关闭":"");
            } else {
                row(s,5,"代码","中文","单价(元/KG)","处理费(元/件)","限重(KG)");
                row(s,6,"US","美国(一区)",85,15,.113,"");row(s,7,"US","美国(一区)",85,16,secondLimit,closed?"关闭":"");
            }
            if(!footer.isBlank())row(s,10,footer);
            var other=book.createSheet("顺速宝普");row(other,0,"顺速宝普货价目表");row(other,1,"国家","重量段","运费/kg","挂号费/票");row(other,2,"美国","0-1",1,1);
            return bytes(book);
        }
    }
    @Test void parsesUpperBoundsNotMinimumsAndKeepsIdentityAfterSheetAndColumnChanges()throws Exception {
        var a=first(fixture("0.226",false,false,""));var b=first(fixture("0.226",false,true,""));
        assertTrue(a.path("quoteReady").asBoolean(),a.toPrettyString());assertTrue(b.path("quoteReady").asBoolean(),b.toPrettyString());
        assertEquals(a.path("companyChannelId"),b.path("companyChannelId"));assertEquals(a.path("contentHash"),b.path("contentHash"));
        var rows=a.path("rows");assertEquals(2,rows.size());assertEquals(0,rows.get(0).path("weightFromKg").asDouble());
        assertEquals(.113,rows.get(1).path("weightFromKg").asDouble());assertFalse(rows.get(1).path("weightFromInclusive").asBoolean());
        var engine=new LogisticsBillingEngine(mapper);
        assertEquals(15,engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.000001)).path("total").asDouble());
        assertEquals(24.61,engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.113)).path("total").asDouble());
        assertEquals(25.61,engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.113001)).path("total").asDouble());
        assertThrows(AppException.class,()->engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.226001)));
        for(var r:rows){assertEquals(0,r.path("minChargeWeightKg").asDouble());assertEquals("user-confirmed",r.path("sourceMinimumWeightKind").asText());}
    }
    @Test void blocksMissingOrReversedLimitsAndMissingPrices()throws Exception {
        for(var limit:List.of("","bad","0.1","0.113")) {var c=first(fixture(limit,false,false,""));assertFalse(c.path("quoteReady").asBoolean(),c.toPrettyString());assertTrue(c.path("errors").asInt()>0);}
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(".226",false,false,"")))) {
            b.getSheetAt(0).getRow(7).getCell(2).setBlank();var c=first(bytes(b));assertFalse(c.path("quoteReady").asBoolean());
        }
    }
    @Test void filtersClosedDestinationsAndHonorsNewExplicitMinimum()throws Exception {
        assertEquals(1,first(fixture(".226",true,false,"")).path("rows").size());
        var c=first(fixture(".226",false,false,"美国50G起重"));
        for(var row:c.path("rows")){assertEquals(.05,row.path("minChargeWeightKg").asDouble());assertEquals("note",row.path("sourceMinimumWeightKind").asText());}
        assertEquals(19.25,new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.001)).path("total").asDouble());
    }
    @Test void readsExplicitMinimumColumnAndRejectsFutureUnknownRoundingOrExtraPrices()throws Exception {
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(".226",false,false,"")))) {
            var s=b.getSheetAt(0);s.getRow(5).createCell(6).setCellValue("最低计费重量(G)");
            s.getRow(6).createCell(6).setCellValue(50);s.getRow(7).createCell(6).setCellValue(50);
            var c=first(bytes(b));assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());
            for(var row:c.path("rows")){assertEquals(.05,row.path("minChargeWeightKg").asDouble());assertEquals("column",row.path("sourceMinimumWeightKind").asText());}
            s.getRow(7).getCell(6).setBlank();assertFalse(first(bytes(b)).path("quoteReady").asBoolean());
        }
        assertFalse(first(fixture(".226",false,false,"所有国家10G进位")).path("quoteReady").asBoolean());
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture(".226",false,false,"")))) {
            b.getSheetAt(0).getRow(5).createCell(6).setCellValue("燃油附加费");assertFalse(first(bytes(b)).path("quoteReady").asBoolean());
        }
    }
    @Test @EnabledIfSystemProperty(named="sunyou.source",matches=".+")
    void reconcilesEveryRealSourceRowAndBoundaryAgainstIndependentDecimalCalculations()throws Exception {
        var path=Path.of(System.getProperty("sunyou.source"));var bytes=Files.readAllBytes(path);
        var result=parser.parse(bytes,path.getFileName().toString(),directory());
        var out=Path.of("target/sunyou");Files.createDirectories(out);Files.writeString(out.resolve("parsed.json"),result.toPrettyString());
        assertEquals(3,result.path("channels").size());
        var evidence=mapper.createArrayNode();int totalRows=0;
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            for(var channel:result.path("channels")) {
                assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toPrettyString());assertTrue(channel.path("quoteReady").asBoolean(),channel.path("blockingReasons").toPrettyString());
                var sheet=book.getSheet(channel.path("rows").get(0).path("sourceSheet").asText());
                var expectedRows=new LinkedHashMap<Integer,double[]>();var prior=new HashMap<String,Double>();
                for(var r:sheet) {
                    var code=r.getCell(3);if(code==null||!code.toString().matches("[A-Z]{2}"))continue;
                    var label=r.getCell(4).toString();var hi=r.getCell(7).getNumericCellValue();var key=code+"|"+label;
                    var lo=prior.getOrDefault(key,0d);prior.put(key,hi);
                    if(r.getCell(8)!=null&&r.getCell(8).toString().equals("关闭"))continue;
                    expectedRows.put(r.getRowNum()+1,new double[]{lo,hi,r.getCell(5).getNumericCellValue(),r.getCell(6).getNumericCellValue()});
                }
                assertEquals(expectedRows.size(),channel.path("rows").size());totalRows+=expectedRows.size();
                var item=evidence.addObject();item.set("channel",channel);var cases=item.putArray("cases");
                for(var row:channel.path("rows")) {
                    var source=expectedRows.remove(row.path("sourceRow").asInt());assertNotNull(source);
                    assertEquals(source[0],row.path("weightFromKg").asDouble());assertEquals(source[1],row.path("weightToKg").asDouble());
                    assertEquals(source[2],row.path("pricePerKg").asDouble());assertEquals(source[3],row.path("registrationFee").asDouble());
                    assertEquals(0,row.path("minChargeWeightKg").asDouble());
                    var lo=BigDecimal.valueOf(source[0]);var hi=BigDecimal.valueOf(source[1]);var rate=BigDecimal.valueOf(source[2]);var fee=BigDecimal.valueOf(source[3]);
                    for(var weight:List.of(lo.add(new BigDecimal(".000001")),lo.add(hi).divide(BigDecimal.TWO),hi)) {
                        var input=mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight);
                        var actual=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),input);
                        assertEquals(weight.multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),actual.path("total").asDouble(),input.toString());
                        assertEquals(weight.doubleValue(),actual.path("chargeWeightKg").asDouble());
                        cases.addObject().put("country",input.path("country").asText()).put("zoneName",input.path("zoneName").asText()).put("weightKg",weight).set("expected",actual);
                    }
                }
                assertTrue(expectedRows.isEmpty());
            }
        }
        assertEquals(383,totalRows);assertEquals(totalRows*2,result.path("priceCellsParsed").asInt());
        Files.writeString(out.resolve("billing-cases.json"),evidence.toPrettyString());
    }
}
