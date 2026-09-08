package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ShandianhouSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    @Test void filtersOtherCountriesBeforeInvalidPricesAndIgnoresOtherSheets() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("全球专线普货");
            String[][] cells={{"国家","产品名称","产品代码","重量(KG)","尺寸限制","包裹运费（RMB/KG）","处理费（RMB/票）","时效（工作日）","备注"},
                {"美国","美猴专线普货","19221","0.05<W≤0.2","长*宽*高/8000","55","20","5-12","计费标准：按克计费，50克起重"},
                {"英国","美猴专线普货","19221","0.05<W≤0.2","","INVALID","INVALID","",""}};
            for(int r=0;r<cells.length;r++)for(int c=0;c<cells[r].length;c++) {
                var row=sheet.getRow(r);if(row==null)row=sheet.createRow(r);row.createCell(c).setCellValue(cells[r][c]);
            }
            book.createSheet("澳洲专线普货").createRow(0).createCell(0).setCellValue("未知价格模板");
            var out=new ByteArrayOutputStream();book.write(out);
            var result=parser.parse(out.toByteArray(),"闪电猴.xlsx");
            assertEquals(1,result.path("channels").size());
            var rows=result.path("channels").get(0).path("rows");assertEquals(1,rows.size());
            assertEquals("US",rows.get(0).path("countryCode").asText());
            assertEquals(0.05,rows.get(0).path("minChargeWeightKg").asDouble());
            assertEquals(0.001,rows.get(0).path("billingStepKg").asDouble());
            assertEquals("filtered",result.path("sheets").get(1).path("status").asText());
            assertEquals(0,result.path("sheets").get(1).path("priceCellsParsed").asInt());
        }
    }
    @Test void followsHeadersAndProductIdentityAfterRenamingMovingColumnsAndChangingPrices() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("九月更新");sheet.createRow(3).createCell(4).setCellValue("SDH闪电猴华东全球专线普货");
            String[] headers={"国家","产品名称","产品代码","重量(KG)","尺寸限制","包裹运费（RMB/KG）","处理费（RMB/票）","时效（工作日）","备注"};
            int[] cols={8,2,10,4,12,6,1,14,16};
            var head=sheet.createRow(7);for(int c=0;c<headers.length;c++)head.createCell(cols[c]).setCellValue(headers[c]);
            String[] values={"美国","美猴专线普货","19221","0.08<W≤0.3","长*宽*高/6000","88.5","31","6-13","计费标准：按克计费，80克起重"};
            for(int r=8;r<=9;r++){var row=sheet.createRow(r);for(int c=0;c<values.length;c++)if(r==8||c==3||c==5||c==6)row.createCell(cols[c]).setCellValue(r==9?(c==3?"0.301<W≤1":c==5?"91.2":"32"):values[c]);}
            head.createCell(20).setCellValue("重量限制(KG)");
            sheet.getRow(8).createCell(20).setCellValue("0-30");sheet.getRow(9).createCell(20).setCellValue("0-30");
            for(int c:new int[]{0,1,2,4,7,8})sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(8,9,cols[c],cols[c]));
            var directory=mapper.createObjectNode().put("enabled",true).put("revision",7);
            var entry=directory.putArray("entries").addObject().put("id","d7a9e20e-2414-4d4d-9a64-67819b3ac2cc").put("providerName","闪电猴").put("channelName","全球专线普货-美国").put("logisticsAttribute","普货");
            entry.putArray("aliases").add("美猴专线普货");entry.putArray("productCodes").add("19221");
            var out=new ByteArrayOutputStream();book.write(out);
            var result=parser.parse(out.toByteArray(),"九月最新价格.xlsx",new CompanyChannelScope(directory));
            assertEquals(1,result.path("channels").size(),result.toPrettyString());
            var channel=result.path("channels").get(0);assertEquals(entry.path("id").asText(),channel.path("companyChannelId").asText());
            var rows=channel.path("rows");assertEquals(2,rows.size());
            assertEquals(88.5,rows.get(0).path("pricePerKg").asDouble());assertEquals(91.2,rows.get(1).path("pricePerKg").asDouble());
            assertEquals(31,rows.get(0).path("registrationFee").asDouble());assertEquals(32,rows.get(1).path("registrationFee").asDouble());
            for(var row:rows){assertEquals(0.08,row.path("minChargeWeightKg").asDouble());assertEquals(6000,row.path("volumeDivisor").asInt());assertEquals(6,row.path("etaMinDays").asInt());assertEquals(13,row.path("etaMaxDays").asInt());}
            assertEquals(0.08,rows.get(0).path("weightFromKg").asDouble());assertEquals(0.3,rows.get(0).path("weightToKg").asDouble());
            assertEquals(9,rows.get(0).path("sourceRow").asInt());assertEquals(10,rows.get(1).path("sourceRow").asInt());
        }
    }
    @Test @EnabledIfSystemProperty(named="sdh.source", matches=".+")
    void reconcilesRealWorkbookSixUsProducts() throws Exception {
        var result=parser.parse(Files.readAllBytes(Path.of(System.getProperty("sdh.source"))),"20260604闪电猴价格表(1).xlsx");
        Files.createDirectories(Path.of("target/sdh"));Files.writeString(Path.of("target/sdh/parsed.json"),result.toPrettyString());
        assertEquals(6,result.path("channels").size(),result.toPrettyString());
        var directory=(tools.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/shandianhou-us-company-channels.json")));
        CompanyChannelService.validate(directory.path("entries"));
        directory.put("enabled",true).put("revision",123);
        var scoped=parser.parse(Files.readAllBytes(Path.of(System.getProperty("sdh.source"))),"闪电猴.xlsx",new CompanyChannelScope(directory));
        assertEquals(6,scoped.path("channels").size());
        assertEquals(123,scoped.path("scopeRevision").asLong());
        for(var channel:scoped.path("channels"))assertFalse(channel.path("companyChannelId").asText().isBlank());
        Files.writeString(Path.of("target/sdh/scoped.json"),scoped.toPrettyString());
        var rates=java.util.Map.of("19221",new double[]{55,60,60,65},"14421",new double[]{55,60,60,65},"13601",new double[]{70,70,75,80},"19241",new double[]{70,70,75,80},"14021",new double[]{70,70,75,80},"18821",new double[]{70,70,75,80});
        var fees=java.util.Map.of("19221",new double[]{20,16,16,10},"14421",new double[]{27,23,23,20},"13601",new double[]{20,20,20,10},"19241",new double[]{27,27,27,20},"14021",new double[]{20,20,20,10},"18821",new double[]{27,27,27,20});
        var codes=new java.util.HashSet<String>();
        for(var channel:result.path("channels")) {
            assertEquals("闪电猴",channel.path("providerName").asText());
            assertEquals(4,channel.path("rows").size());
            int tier=0;
            for(var row:channel.path("rows")) {
                assertEquals("US",row.path("countryCode").asText());
                var code=row.path("sourceProductCode").asText();codes.add(code);
                assertEquals(rates.get(code)[tier],row.path("pricePerKg").asDouble());
                assertEquals(fees.get(code)[tier++],row.path("registrationFee").asDouble());
                assertEquals(0.05,row.path("minChargeWeightKg").asDouble());
                assertEquals(0.001,row.path("billingStepKg").asDouble());
                assertEquals(8000,row.path("volumeDivisor").asInt());
                assertTrue(row.path("etaMinDays").asInt()>0);
                assertFalse(row.path("quoteReady").asBoolean());
                assertTrue(row.path("notes").asText().contains("50RMB"));
            }
        }
        assertEquals(java.util.Set.of("19221","14421","13601","19241","14021","18821"),codes);
    }
}
