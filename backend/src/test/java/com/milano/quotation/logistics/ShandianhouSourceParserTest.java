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
