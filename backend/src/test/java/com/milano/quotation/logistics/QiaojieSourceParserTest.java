package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class QiaojieSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var json=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/qiaojie-company-channels.json")));
        CompanyChannelService.validate(json.path("entries"));
        return new CompanyChannelScope(json.put("enabled",true).put("revision",21));
    }
    @Test @EnabledIfSystemProperty(named="qiaojie.source",matches=".+")
    void reconcilesSelectedRealSourceSheets() throws Exception {
        var path=Path.of(System.getProperty("qiaojie.source"));
        var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/qiaojie"));Files.writeString(Path.of("target/qiaojie/parsed.json"),result.toPrettyString());
        assertEquals(4,result.path("channels").size());
        var counts=Map.of("QEGBTF",78,"QEUBF",7,"QEGBFA",8,"QEUFAB",10);
        var countries=Map.of("QEGBTF",31,"QEUBF",1,"QEGBFA",1,"QEUFAB",1);
        var cases=mapper.createArrayNode();
        for(var c:result.path("channels")) {
            var product=QiaojieSourceRules.named(c.path("channelName").asText());assertNotNull(product);
            assertEquals(counts.get(product.code()),c.path("rows").size());
            assertEquals(countries.get(product.code()).longValue(),c.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().count());
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean());
            for(var r:c.path("rows")) {
                assertEquals(product.perPiece()?.5:.05,r.path("minChargeWeightKg").asDouble());assertEquals(product.code(),r.path("sourceProductCode").asText());
                var input=mapper.createObjectNode().put("country",r.path("countryCode").asText()).put("weightKg",r.path("weightToKg").asDouble());
                var resultAtUpper=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),input);
                var expected=(product.perPiece()?r.path("intervalPrice").decimalValue():r.path("weightToKg").decimalValue().multiply(r.path("pricePerKg").decimalValue())).add(r.path("registrationFee").decimalValue()).setScale(2,java.math.RoundingMode.HALF_UP);
                assertEquals(expected.doubleValue(),resultAtUpper.path("total").asDouble());
                var item=cases.addObject().put("channel",product.name()).put("country",r.path("countryCode").asText()).put("weightKg",r.path("weightToKg").asDouble());item.set("expected",resultAtUpper);
            }
            for(var country:c.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().toList()) {
                var first=c.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals(country)).min(Comparator.comparingDouble(r->r.path("weightFromKg").asDouble())).orElseThrow();
                var computed=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",.012));
                assertEquals(product.perPiece()?.5:.05,computed.path("chargeWeightKg").asDouble());
                var expected=(product.perPiece()?first.path("intervalPrice").decimalValue():new BigDecimal("0.05").multiply(first.path("pricePerKg").decimalValue())).add(first.path("registrationFee").decimalValue()).setScale(2,java.math.RoundingMode.HALF_UP);
                assertEquals(expected.doubleValue(),computed.path("total").asDouble());
                cases.addObject().put("channel",product.name()).put("country",country).put("weightKg",.012).set("expected",computed);
                if(!product.perPiece())for(double actual:new double[]{.049999,.05,.050001}) {
                    var boundary=new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",actual));
                    var charged=BigDecimal.valueOf(actual).max(new BigDecimal("0.05"));
                    assertEquals(charged.doubleValue(),boundary.path("chargeWeightKg").asDouble());
                    assertEquals(charged.multiply(first.path("pricePerKg").decimalValue()).add(first.path("registrationFee").decimalValue()).setScale(2,java.math.RoundingMode.HALF_UP).doubleValue(),boundary.path("total").asDouble());
                    cases.addObject().put("channel",product.name()).put("country",country).put("weightKg",actual).set("expected",boundary);
                }
            }
        }
        assertEquals(206,result.path("priceCellsParsed").asInt());
        var piece=result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals("巧捷小包快递特惠包税B")).findFirst().orElseThrow();
        double[] amounts={91,121,161,192,231,266,298,358,428,489};
        for(int tier=0;tier<10;tier++)for(double offset:new double[]{.000001,.001,.25,.499999}) {
            double weight=BigDecimal.valueOf(tier).multiply(new BigDecimal("0.5")).add(BigDecimal.valueOf(offset)).doubleValue();
            var computed=new LogisticsBillingEngine(mapper).calculate(piece.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",weight));
            assertEquals(amounts[tier],computed.path("total").asDouble());assertEquals((tier+1)*.5,computed.path("chargeWeightKg").asDouble());
            cases.addObject().put("channel",piece.path("channelName").asText()).put("country","US").put("weightKg",weight).set("expected",computed);
        }
        assertThrows(com.milano.quotation.common.AppException.class,()->new LogisticsBillingEngine(mapper).calculate(piece.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",5.001)));
        // Independently reconcile the named source columns for every retained raw price row.
        try(var reader=new LogisticsSheetReader(Files.readAllBytes(path),path.getFileName().toString(),name->QiaojieSourceRules.named(name)==null)) {
            while(reader.hasNext()) {
                var sheet=reader.next();var product=QiaojieSourceRules.named(sheet.getSheetName());if(product==null)continue;
                var channel=result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(product.name())).findFirst().orElseThrow();
                for(var r:channel.path("rows")) {
                    var original=sheet.getRow(r.path("sourceRow").asInt()-1);var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
                    if(product.perPiece()) {
                        assertEquals(Double.parseDouble(format.formatCellValue(original.getCell(5))),r.path("weightToKg").asDouble());
                        assertEquals(Double.parseDouble(format.formatCellValue(original.getCell(7))),r.path("intervalPrice").asDouble());
                        assertEquals(Double.parseDouble(format.formatCellValue(original.getCell(8))),r.path("registrationFee").asDouble());
                        continue;
                    }
                    var bounds=format.formatCellValue(original.getCell(5)).split("-");
                    assertEquals(Double.parseDouble(bounds[0]),r.path("weightFromKg").asDouble());
                    assertEquals(Double.parseDouble(bounds[1]),r.path("weightToKg").asDouble());
                    assertEquals(Double.parseDouble(format.formatCellValue(original.getCell(7))),r.path("pricePerKg").asDouble());
                    assertEquals(Double.parseDouble(format.formatCellValue(original.getCell(8))),r.path("registrationFee").asDouble());
                }
            }
        }
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));evidence.set("cases",cases);
        Files.writeString(Path.of("target/qiaojie/billing-cases.json"),evidence.toPrettyString());
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var dataset=UUID.randomUUID();var records=new ArrayList<ObjectNode>();
        for(var channel:result.path("channels"))records.add(mapper.createObjectNode().put("provider","巧捷").put("channel",channel.path("channelName").asText())
            .put("attribute",channel.path("logisticsAttribute").asText()).put("id",UUID.randomUUID().toString()).put("status","published").set("version",channel));
        org.mockito.Mockito.when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).param("dataset",dataset).param("version",null)
            .query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ObjectNode>>any()).list()).thenReturn(records);
        var exported=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        var restored=parser.parse(exported,"巧捷标准导出.xlsx",directory());
        assertEquals(4,restored.path("channels").size());
        for(var channel:restored.path("channels")) {
            assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
            var original=result.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(channel.path("companyChannelId"))).findFirst().orElseThrow();
            assertEquals(original.path("rows").size(),channel.path("rows").size());
            for(int i=0;i<channel.path("rows").size();i++) {
                assertEquals(original.path("rows").get(i).path("pricingModel"),channel.path("rows").get(i).path("pricingModel"));
                for(var field:List.of("weightFromKg","weightToKg","minChargeWeightKg","pricePerKg","intervalPrice","registrationFee"))
                    assertEquals(original.path("rows").get(i).path(field).asDouble(),channel.path("rows").get(i).path(field).asDouble(),field);
            }
        }
    }

    byte[] fixture(String title,double rate,boolean missingWeight) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("九月价格更新");row(sheet,2,title);
            row(sheet,6,"处理费","国家/地区","Code","重量","最低计费重","运费","参考时效");
            row(sheet,7,"(RMB/票)","","","(KG)","(KG)","(RMB/KG)","自然日");
            sheet.addMergedRegion(new CellRangeAddress(6,7,1,1));sheet.addMergedRegion(new CellRangeAddress(6,7,2,2));
            row(sheet,8,23,"美国","US",missingWeight?"":"0-0.1",.05,rate,"7-12天");
            row(sheet,9,21,"","","0.1-0.2","",rate,"7-12天");
            sheet.addMergedRegion(new CellRangeAddress(8,9,1,1));sheet.addMergedRegion(new CellRangeAddress(8,9,2,2));
            var excluded=book.createSheet("巧捷小包全球特惠E(服装)");row(excluded,0,"国家","重量KG","运费/KG","操作费");row(excluded,1,"美国","0-1","INVALID","INVALID");
            return bytes(book);
        }
    }
    @Test void recognizesExactTitleAfterSheetAndColumnMovesAndRetainsIdentityAcrossPriceUpdates() throws Exception {
        var title="巧捷小包全球商派E（服装） QEUBF";
        var first=parser.parse(fixture(title,51,false),"巧捷-旧价格.xlsx",directory());
        var next=parser.parse(fixture(title,52.5,false),"最新调整.xlsx",directory());
        assertEquals(1,first.path("channels").size());assertEquals(1,next.path("channels").size());
        var a=first.path("channels").get(0);var b=next.path("channels").get(0);
        assertEquals(0,a.path("errors").asInt(),a.toPrettyString());assertEquals(2,a.path("rows").size());
        assertEquals(a.path("companyChannelId"),b.path("companyChannelId"));assertNotEquals(a.path("contentHash"),b.path("contentHash"));
        assertEquals(a.path("rows").get(0).path("rowKey"),b.path("rows").get(0).path("rowKey"));
        assertEquals(0,first.path("sheets").get(1).path("priceCellsParsed").asInt());
        var unscoped=parser.parse(fixture(title,51,false),"巧捷.xlsx");
        assertEquals(1,unscoped.path("channels").size());assertEquals(a.path("contentHash"),unscoped.path("channels").get(0).path("contentHash"));
        assertEquals(25.55,new LogisticsBillingEngine(mapper).calculate(a.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.012)).path("total").asDouble());
    }
    @Test void rejectsIncompletePriceRowAndFiltersSimilarUnapprovedProducts() throws Exception {
        var result=parser.parse(fixture("巧捷小包全球商派E(服装)QEUBF",51,true),"巧捷.xlsx",directory());
        assertTrue(result.path("channels").get(0).path("errors").asInt()>0);
        assertFalse(result.path("channels").get(0).path("quoteReady").asBoolean());
        for(var name:List.of("巧捷小包全球特惠E(服装)","巧捷专线小包全球特惠F(普货)","巧捷小包全球特惠M(特货)"))assertNull(QiaojieSourceRules.named(name));
        assertFalse(QiaojieSourceRules.allowed("巧捷小包全球商派E(服装)","QEGBFA"));
    }
    @Test void aOnlyRetainsUsBeforeReadingExcludedPriceCellsAndRejectsEmptyCoverage() throws Exception {
        byte[] mixed,foreignOnly;
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture("巧捷小包全球特惠A(服装)",51,false)))) {
            var sheet=book.getSheetAt(0);
            row(sheet,10,"INVALID","加拿大","CA","INVALID","INVALID","INVALID","7-12天");
            mixed=bytes(book);
            sheet.getRow(8).getCell(1).setCellValue("加拿大");sheet.getRow(8).getCell(2).setCellValue("CA");
            foreignOnly=bytes(book);
        }
        var result=parser.parse(mixed,"巧捷.xlsx",directory());var channel=result.path("channels").get(0);
        assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());assertEquals(2,channel.path("rows").size());
        assertTrue(channel.path("rows").valueStream().allMatch(r->r.path("countryCode").asText().equals("US")));
        assertTrue(result.toString().contains(QiaojieSourceRules.US_ONLY_REASON));
        var empty=parser.parse(foreignOnly,"巧捷.xlsx",directory());
        assertTrue(empty.path("channels").valueStream().noneMatch(c->c.path("quoteReady").asBoolean()));
    }
    @Test void standardImportsFilterNonUsRowsBeforeParsingPrices() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("标准价格");var headers=new ArrayList<>(LogisticsWorkbookService.HEADERS);
            headers.addAll(List.of("物流商","渠道名称","原产品代码","计费方式"));
            row(sheet,0,headers.toArray());
            for(int i=1;i<=2;i++) {
                var r=sheet.createRow(i);
                for(int c=0;c<headers.size();c++)r.createCell(c).setCellValue("");
                r.getCell(0).setCellValue(i==1?"美国":"加拿大");r.getCell(1).setCellValue(i==1?"US":"CA");
                r.getCell(2).setCellValue(7);r.getCell(3).setCellValue(12);
                r.getCell(15).setCellValue(0);r.getCell(16).setCellValue(1);r.getCell(19).setCellValue(.05);
                if(i==1)r.getCell(18).setCellValue(51);else r.getCell(18).setCellValue("INVALID");
                r.getCell(38).setCellValue("巧捷");r.getCell(39).setCellValue("巧捷小包全球特惠A(服装)");r.getCell(40).setCellValue("QEGBFA");r.getCell(41).setCellValue("per-kg");
            }
            var result=parser.parse(bytes(book),"巧捷标准表.xlsx",directory());
            var c=result.path("channels").get(0);assertEquals(1,c.path("rows").size());assertEquals(0,c.path("errors").asInt());
            assertEquals("US",c.path("rows").get(0).path("countryCode").asText());
            assertTrue(result.toString().contains(QiaojieSourceRules.US_ONLY_REASON));
        }
    }
    byte[] pieceFixture(double minimum,double rate,double secondPoint,boolean singleHeader) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("新版价格");row(sheet,1,"巧捷小包快递特惠包税B QEUFAB");
            row(sheet,4,"处理费","运费"+(singleHeader?"(RMB/件)":""),"Code","国家/地区","最低计费重","重量","参考时效");
            row(sheet,5,"(RMB/票)",singleHeader?"":"(RMB/件)","","","(KG)","(KG)","自然日");
            row(sheet,6,0,rate,"US","美国",minimum,.5,"4-8天");row(sheet,7,0,121,"US","美国","",secondPoint,"4-8天");
            return bytes(book);
        }
    }
    @Test void pieceUpdatesUseLabeledColumnsAndRejectChangedMinimumOrMissingStep() throws Exception {
        var old=parser.parse(pieceFixture(.5,91,1,false),"巧捷旧价.xlsx",directory()).path("channels").get(0);
        var updated=parser.parse(pieceFixture(.5,93,1,true),"巧捷新价.xlsx",directory()).path("channels").get(0);
        assertTrue(old.path("quoteReady").asBoolean(),old.toPrettyString());assertTrue(updated.path("quoteReady").asBoolean(),updated.toPrettyString());
        assertEquals(old.path("companyChannelId"),updated.path("companyChannelId"));assertEquals(old.path("rows").get(0).path("rowKey"),updated.path("rows").get(0).path("rowKey"));
        assertNotEquals(old.path("contentHash"),updated.path("contentHash"));assertEquals(93,updated.path("rows").get(0).path("intervalPrice").asInt());
        for(var bad:List.of(pieceFixture(.05,91,1,false),pieceFixture(.5,91,1.5,false),pieceFixture(.5,91,.5,false))) {
            var result=parser.parse(bad,"巧捷.xlsx",directory()).path("channels").get(0);
            assertTrue(result.path("errors").asInt()>0);assertFalse(result.path("quoteReady").asBoolean());
        }
    }
    @Test void kilogramMinimumComesFromUpdatedWorkbookInsteadOfHardCodedFiftyGrams() throws Exception {
        byte[] input;
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture("巧捷小包全球商派E(服装)",51,false)))) {
            book.getSheetAt(0).getRow(8).getCell(4).setCellValue(.08);input=bytes(book);
        }
        var channel=parser.parse(input,"巧捷更新.xlsx",directory()).path("channels").get(0);
        assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
        var result=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.012));
        assertEquals(.08,result.path("chargeWeightKg").asDouble());assertEquals(27.08,result.path("total").asDouble());
    }
}
