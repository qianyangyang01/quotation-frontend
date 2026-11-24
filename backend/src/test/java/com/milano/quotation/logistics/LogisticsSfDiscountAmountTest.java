package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSfDiscountAmountTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test @EnabledIfSystemProperty(named="sf.original",matches=".+")
    void suppliedOriginalKeepsFullNumericPrecisionAcrossAllSettlementRows()throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(System.getProperty("sf.original")));
        assertEquals("23e01af4e378f34564b69feecdcb2c6747f205074abceb258b864922f8b110c8",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var scope=mapper.readTree(Files.readString(Path.of(System.getProperty("sf.scope"))));
        var parsed=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper)).parse(bytes,"顺丰国际电商产品价格表(20261123）.xlsx",new CompanyChannelScope(scope));
        assertEquals(6,parsed.path("channels").size());int count=0;
        try(var book=WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            for(var channel:parsed.path("channels")){
                assertEquals(0,channel.path("errors").asInt(),channel.path("channelName").asText());
                for(var row:channel.path("rows")){
                    if(!row.path("sourcePricingBasis").asText().equals("settlement"))continue;
                    var cell=new org.apache.poi.ss.util.CellReference(row.path("sourceSettlementRateCell").asText());
                    var source=book.getSheet(row.path("sourceSheet").asText()).getRow(cell.getRow()).getCell(cell.getCol());
                    assertEquals(source.getNumericCellValue(),row.path("pricePerKg").asDouble(),cell.formatAsString());count++;
                }
            }
        }
        assertTrue(count>150);Files.writeString(Path.of(System.getProperty("sf.parsed")),parsed.toPrettyString());
    }
    @Test void unknownPopulatedSettlementHeaderBlocksInsteadOfSilentlyUsingListRate()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("国际电商专递-CD");
            String[] labels={"国家名称","Code","运费/kg","折扣","折后人民币单价","专递操作费/票","重量段"};
            var head=sheet.createRow(0);for(int c=0;c<labels.length;c++)head.createCell(c).setCellValue(labels[c]);
            var row=sheet.createRow(1);row.createCell(0).setCellValue("英国");row.createCell(1).setCellValue("GB");
            row.createCell(2).setCellValue(100);row.createCell(3).setCellValue(.48);row.createCell(4).setCellValue(44.2);
            row.createCell(5).setCellValue(16);row.createCell(6).setCellValue("0<W<=1");
            var out=new ByteArrayOutputStream();book.write(out);
            var channel=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper)).parse(out.toByteArray(),"顺丰.xlsx").path("channels").get(0);
            assertTrue(channel.path("errors").asInt()>0);assertFalse(channel.path("pricingReady").asBoolean());
            assertEquals("E2",channel.path("rows").get(0).path("sourceUnrecognizedSettlementCell").asText());
            assertTrue(channel.path("issues").toString().contains("禁止回退"));
        }
    }
    @Test void november23UkUsesExplicitSettlementRatherThanConflictingDiscount()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("国际电商专递-CD");
            Object[][] data={
                {"国家名称","Code","参考时效（工作日）","运费/kg","折扣","折扣后金额","专递操作费/票","计费重量限制（kg）"},
                {"英国","GB","6-10",99,.48,43.68,16,"0<W<=0.3"},
                {"英国","GB","6-10",100,.48,44.16,16,"0.3<W<=1"},
                {"英国","GB","6-10",102,.48,45.12,16,"1<W<=20"}
            };
            for(int r=0;r<data.length;r++)for(int c=0;c<data[r].length;c++){
                var row=s.getRow(r);if(row==null)row=s.createRow(r);var cell=row.createCell(c);
                if(data[r][c] instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(data[r][c].toString());
            }
            var out=new ByteArrayOutputStream();book.write(out);
            var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
            var result=parser.parse(out.toByteArray(),"顺丰国际电商产品价格表(20261123)-英国回归夹具.xlsx");
            var channel=result.path("channels").get(0);assertEquals(0,channel.path("errors").asInt(),channel.toString());
            var rows=channel.path("rows");assertEquals(3,rows.size());
            double[] rates={43.68,44.16,45.12};for(int i=0;i<3;i++){
                assertEquals(rates[i],rows.get(i).path("pricePerKg").asDouble());
                assertEquals("settlement",rows.get(i).path("sourcePricingBasis").asText());
                assertFalse(rows.get(i).path("sourceSettlementEmpty").asBoolean());
                assertEquals("F"+(i+2),rows.get(i).path("sourceSettlementRateCell").asText());
            }
            var engine=new LogisticsBillingEngine(mapper);double[] weight={.48,.96,1.44},freight={37.20,58.39,80.97};
            for(int i=0;i<3;i++)assertEquals(freight[i],engine.calculate(rows,mapper.createObjectNode().put("country","GB").put("weightKg",weight[i])).path("total").asDouble());
            String fixture=System.getProperty("sf.fixture");if(fixture!=null)Files.write(Path.of(fixture),out.toByteArray());
        }
    }
}
