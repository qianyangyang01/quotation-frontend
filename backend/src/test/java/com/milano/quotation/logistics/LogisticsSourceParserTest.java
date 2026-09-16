package com.milano.quotation.logistics;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsWorkbookService standard=new LogisticsWorkbookService(mapper);
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,standard);
    @Test void sfFooterMinimumIsScopedToAdjacentCountryInsteadOfWholeSheet() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("服装专线");
            row(sheet,0,"国家","重量段(KG)","公斤运费(元/KG)","处理费(元/件)");
            row(sheet,1,"美国","0-1",60,20);
            row(sheet,2,"日本","0-1",60,20);
            row(sheet,3,"西班牙","0-1",60,20);
            row(sheet,7,"计费标准","美国","单票单件计费;首重50g，续重按1g计算计费重量");
            row(sheet,8,"计费标准","日本","单票单件计费：首重500克，续重500克计算计费重量");
            var prices=parser.parse(bytes(book),"顺丰价格.xlsx").path("channels").get(0).path("rows");
            assertEquals(3,prices.size());
            for(var price:prices)assertEquals(switch(price.path("countryCode").asText()){case "US"->.05;case "JP"->.5;default->0.;},price.path("minChargeWeightKg").asDouble());
        }
    }
    @Test void readsTongyouMatrixFiftyGramFooter() throws Exception {
        try (var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("美国专线小包");
            row(sheet,0,"重量","美国专线特敏感B","");
            row(sheet,1,"重量(KG)","运费(RMB/KG)","处理费(RMB/票)");
            row(sheet,2,"0-0.1",91,24);
            row(sheet,3,"0.101-0.2",91,24);
            row(sheet,15,"计费标准","单票计费起重50克；");
            var result=parser.parse(bytes(book),"通邮价格.xlsx");
            var channel=result.path("channels").get(0);
            assertEquals("美国专线特敏感B",channel.path("channelName").asText());
            assertEquals(2,channel.path("rows").size());
            for (var price:channel.path("rows")) {
                assertEquals(.05,price.path("minChargeWeightKg").asDouble());
                assertEquals("B16",price.path("sourceMinimumWeightCell").asText());
                assertEquals(91,price.path("pricePerKg").asDouble());
                assertEquals(24,price.path("registrationFee").asDouble());
            }
        }
    }
    @Test void preservesYanwenMinimumWeightAndBillsSmallParcelsAtThirtyGrams()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("燕文化妆品专线");
            row(sheet,0,"国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)","最小计费重量(KG)");
            row(sheet,1,"美国","US",63,20,"0.001-0.1",.03);
            row(sheet,2,"美国","US",67,19,"0.101-0.2",.03);
            var parsed=parser.parse(bytes(book),"燕文价格.xlsx");
            var channel=parsed.path("channels").get(0);
            assertEquals(2,channel.path("rows").size(),parsed.toString());
            for(var price:channel.path("rows"))assertEquals(.03,price.path("minChargeWeightKg").asDouble());
            var engine=new LogisticsBillingEngine(mapper);
            for(double actual:new double[]{.012,.024,.03}) {
                var input=mapper.createObjectNode().put("country","US").put("weightKg",actual);
                assertEquals(21.89,engine.calculate(channel.path("rows"),input).path("total").asDouble());
            }
        }
    }
    @Test void inheritsMinimumWithinCountryAndConvertsGrams()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("全球专线普货");
            row(sheet,0,"国家","重量段(KG)","公斤运费(元/KG)","处理费(元/件)","最低计费重量(G)");
            row(sheet,1,"美国","0-0.1",63,20,50);
            row(sheet,2,"美国","0.101-0.2",67,19,"");
            row(sheet,3,"英国","0-0.1",63,20,"");
            var rows=parser.parse(bytes(book),"云速递价格.xlsx").path("channels").get(0).path("rows");
            for(var price:rows)if(price.path("countryCode").asText().equals("US"))assertEquals(.05,price.path("minChargeWeightKg").asDouble());
                else assertEquals(0,price.path("minChargeWeightKg").asDouble());
        }
    }
    @Test void appliesFooterMinimumToEveryMatrixChannelWithoutChangingRates()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("联邮通服装专线（S1427）");
            row(sheet,0,"国家","重量段(KG)","公斤运费(元/KG)","处理费(元/件)");
            row(sheet,1,"美国","0-0.1",63,20);
            row(sheet,2,"英国","0-0.1",67,19);
            row(sheet,4,"以G为单位进位；不足50G按50G计费；");
            var rows=parser.parse(bytes(book),"递四方价格.xlsx").path("channels").get(0).path("rows");
            assertEquals(2,rows.size());for(var price:rows){assertEquals(.05,price.path("minChargeWeightKg").asDouble());assertEquals("A5",price.path("sourceMinimumWeightCell").asText());}
        }
    }
    @Test void twoLevelUnitHeadersRecognizeNewProvider()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("Sheet1");
            row(sheet,0,"国家/地区","重量","运费","处理费","重量尺寸要求及附加费");
            row(sheet,1,"","(KG)","(RMB/KG)","(RMB/票)");
            row(sheet,2,"美国","0-1",65,23);
            var parsed=parser.parse(bytes(book),"巧捷新价格.xlsx");
            assertEquals(1,parsed.path("channels").get(0).path("rows").size(),parsed.toString());
        }
    }

    @Test void newerDatesReorderedHeadersAndNewChannelNamesKeepSamePrices()throws Exception {
        for(String name:List.of("燕文2026-09-07.xlsx","燕文2027-01-01.xlsx"))try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("新增测试渠道");
            row(sheet,0,"更新日期：2027-01-01");
            row(sheet,2,"挂号费/票","重量范围","目的国","公斤单价(元/公斤)","渠道名称");
            row(sheet,3,12,"0-1","美国",45,"新增测试渠道");
            var parsed=parser.parse(bytes(book),name);var prices=parsed.path("channels").get(0).path("rows");
            assertEquals(1,prices.size());assertEquals(45,prices.get(0).path("pricePerKg").asDouble());
            assertEquals("US",prices.get(0).path("countryCode").asText());
        }
    }

    @Test void unknownProviderStillFiltersOversizedPriceSheet()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("新渠道");row(sheet,0,"国家","重量段","运费(RMB/KG)","挂号费");
            for(int r=1;r<=800;r++)row(sheet,r,"美国",(r-1)+"-"+r,12,3);
            var parsed=parser.parse(bytes(book),"新物流商.xlsx");
            assertEquals("filtered",parsed.path("sheets").get(0).path("status").asText());
            assertEquals(501,parsed.path("sheets").get(0).path("filteredPriceRows").asInt());
            assertTrue(parsed.path("channels").isEmpty());
        }
    }

    @Test void datesAreMetadataWhileGenuineWeightRangesRemainPrices() {
        for(var value:List.of("2026-9-7","2026/09/07","2026.9.7","2026年9月7日","生效时间：2026-9-7","生效日期: 2026-6-22")) {
            assertTrue(LogisticsSourceParser.dateMetadata(value),value);
            assertFalse(LogisticsSourceParser.looksRange(value),value);
            assertThrows(IllegalArgumentException.class,()->LogisticsSourceParser.parseRange(value));
        }
        for(var value:List.of("0-2.0 KG","0.101-0.3","1.001-20","0.7-1"))assertTrue(LogisticsSourceParser.looksRange(value),value);
        assertFalse(LogisticsSourceParser.dateMetadata("2026-9-7起 每公斤价格88元"));
    }

    @Test void movedMergedAndNumericDatesDoNotChangeFixedWeightTablePrices()throws Exception {
        for(int location:List.of(0,1,2,4,7,20))for(boolean numeric:List.of(false,true))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("通邮经济小包");
            row(s,2,"序号","国家","0-2.0 KG");
            row(s,3,"","","运费（RMB/KG）","处理费（RMB/票）");
            s.addMergedRegion(new CellRangeAddress(2,3,0,0));s.addMergedRegion(new CellRangeAddress(2,3,1,1));s.addMergedRegion(new CellRangeAddress(2,2,2,3));
            row(s,4,1,"日本",180,1);row(s,5,2,"加拿大",206,2.5);
            var dateRow=s.getRow(location);if(dateRow==null)dateRow=s.createRow(location);
            dateRow.createCell(6).setCellValue("生效日期：");
            var value=dateRow.createCell(7);
            if(numeric){value.setCellValue(java.time.LocalDateTime.of(2026,9,7,0,0));var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("yyyy-m-d"));value.setCellStyle(style);}
            else value.setCellValue("2026-9-7");
            s.addMergedRegion(new CellRangeAddress(location,location,7,9));
            var parsed=parser.parse(bytes(book),"通邮价格.xlsx");
            assertEquals(1,parsed.path("channels").size(),parsed.toString());
            var channel=parsed.path("channels").get(0);
            assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toString());
            assertEquals(2,channel.path("rows").size(),channel.toString());
            for(var price:channel.path("rows")) {
                boolean japan=price.path("countryCode").asText().equals("JP");
                assertTrue(japan||price.path("countryCode").asText().equals("CA"));
                assertEquals(japan?180:206,price.path("pricePerKg").asDouble());
                assertEquals(japan?1:2.5,price.path("registrationFee").asDouble());
                assertEquals(0,price.path("weightFromKg").asDouble());
                assertEquals(2,price.path("weightToKg").asDouble());
            }
            assertTrue(parsed.path("sheets").get(0).path("sourceCells").toString().contains("2026"));
        }
    }

    @Test void malformedPricesRetainSheetLineAndOriginalCells()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("定位测试");
            row(sheet,0,"国家","重量段","运费/KG","挂号费/票");
            row(sheet,1,"美国","0-1","not-a-price",20);
            var parsed=parser.parse(bytes(book),"花海.xlsx");
            var findings=new ArrayList<JsonNode>();
            parsed.path("channels").forEach(channel->channel.path("issues").forEach(findings::add));
            assertFalse(findings.isEmpty());
            assertTrue(findings.stream().allMatch(issue->issue.path("sourceSheet").asText().equals("定位测试")));
            assertTrue(findings.stream().anyMatch(issue->issue.path("row").asInt()==2&&issue.path("rawValues").toString().contains("not-a-price")));
        }
    }

    @Test void footerEtaMustBeUnambiguousAndBelongToTheDestination()throws Exception {
        for(var note:List.of("参考时效：7-15天","加拿大参考时效：7-15天","参考时效：7-15天，特殊情况20-30天"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普通渠道");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","0-1",55,20);row(s,3,note);
            var channel=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            assertEquals(note.equals("参考时效：7-15天"),channel.path("etaReady").asBoolean(),note);
        }
    }

    @Test void separatesTrackingUploadFromDeliveryButStillRejectsDeliveryConflicts()throws Exception {
        String base="客户交货我司出库后1-2天上网，货到加邮提取，全程签收时效10-12天；";
        for(var note:List.of(base,base+"特殊情况20-30天"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普通渠道");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"加拿大","0-1",55,20);row(s,3,note);
            var channel=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            assertEquals(note.equals(base),channel.path("etaReady").asBoolean());
            if(note.equals(base)) {
                var price=channel.path("rows").get(0);assertEquals(10,price.path("etaMinDays").asInt());assertEquals(12,price.path("etaMaxDays").asInt());
                assertTrue(price.path("sourceEtaText").asText().contains("1-2天上网"));
            }
        }
    }

    @Test void preservesStrictBoundsWithoutInventingBillingRounding(){
        var range=LogisticsSourceParser.parseRange("0.2＜W<0.5KG");
        assertEquals(0.2,range.from());assertEquals(0.5,range.to());assertFalse(range.includeFrom());assertFalse(range.includeTo());
        assertTrue(LogisticsSourceParser.parseRange("0.201-0.5KG").includeFrom());
        assertEquals(10,LogisticsSourceParser.parseRange("<=10KG").to());assertFalse(LogisticsSourceParser.parseRange("<0.5KG").includeTo());
        assertThrows(IllegalArgumentException.class,()->LogisticsSourceParser.parseRange("小于等于某重量"));
    }
    @Test void parsesLayeredHeadersAndPerTicketWeightMatrices()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var layered=book.createSheet("纯电池线路-日本");row(layered,0,"纯电池线路-日本");row(layered,2,"系统下单渠道","重量 KG","首0.5KG","续0.5KG","国家");
            row(layered,3,"纯电池线路","0-0.5",38,0,"日本");row(layered,4,"","0.501-20",35,15,"");layered.addMergedRegion(new CellRangeAddress(3,4,0,0));layered.addMergedRegion(new CellRangeAddress(3,4,4,4));
            var mini=book.createSheet("MINI小包");row(mini,0,"JP-mini小包");row(mini,2,"重量段/KG","JP-mini小包普货X","JP-mini小包带电X");
            row(mini,3,"","运费RMB/票","运费RMB/票");row(mini,4,.05,13.5,13.7);row(mini,5,.1,14,14.5);
            var parsed=parser.parse(bytes(book),"通邮价格.xlsx");
            assertEquals("filtered",parsed.path("sheets").get(0).path("status").asText());
            assertEquals(2,parsed.path("sheets").get(0).path("filteredFirstNextRows").size());
            assertTrue(parsed.path("channels").valueStream().noneMatch(c->c.path("channelName").asText().equals("纯电池线路")));
            assertTrue(parsed.path("channels").isEmpty());
            assertEquals("filtered",parsed.path("sheets").get(1).path("status").asText());
            assertEquals(2,parsed.path("sheets").get(1).path("filteredOtherRows").size());
        }
    }
    @Test void supportsXlsAndReportsEmptyAndUnrecognizedSheets()throws Exception {
        try(var book=new HSSFWorkbook()) {
            var s=book.createSheet("普通渠道");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","0-1",55,20);
            book.createSheet("空表");row(book.createSheet("特殊费率"),0,"无法自动识别的特殊价格说明");
            var parsed=parser.parse(bytes(book),"极通环球.xls");assertEquals(3,parsed.path("sheets").size());
            assertEquals("empty",parsed.path("sheets").get(1).path("status").asText());
            assertTrue(parsed.path("channels").get(1).path("errors").asInt()>0);
            assertEquals(55,parsed.path("channels").get(0).path("rows").get(0).path("pricePerKg").asDouble());
        }
    }
    @Test void mapsPerTicketAliasesButNeverMapsSurchargesIntoRegistrationFee()throws Exception {
        for(var label:List.of("挂号费/票","操作费（RMB/票）","处理费(元/件)","每票费"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","产品代码","重量段","运费/KG","燃油附加费",label,"时效");row(s,1,"美国","PX-US","0-1",55,99,20,"7-15天");
            var channel=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);var price=channel.path("rows").get(0);
            assertEquals(20,price.path("registrationFee").asInt(),label);assertEquals(label,price.path("sourceFeeLabel").asText());assertEquals("PX-US",price.path("sourceProductCode").asText());
            assertEquals(7,price.path("etaMinDays").asInt());assertEquals(15,price.path("etaMaxDays").asInt());assertTrue(channel.path("etaReady").asBoolean());
        }
    }
    @Test void preservesUnknownPriceAddonsAsEvidenceWithoutAddingThemToBasePrice()throws Exception {
        try(var book=new XSSFWorkbook()){
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","超尺寸费","操作费/票","时效");row(s,1,"美国","0-1",55,99,20,"7-15天");
            var channel=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);var price=channel.path("rows").get(0);
            assertEquals(20,price.path("registrationFee").asInt());assertEquals(0,channel.path("errors").asInt(),channel.toString());
            assertTrue(price.path("reviewWarning").asText().contains("未知价格附加费"));assertTrue(channel.path("quoteReady").asBoolean());
            assertEquals(75,new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",1)).path("total").asDouble());
        }
    }
    @Test void usesSfSettlementOnceAndBlocksUnreliableSettlementFormula()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("服装专线");row(s,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");
            row(s,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            var parsed=parser.parse(bytes(book),"顺丰.xlsx");assertEquals(45.44,parsed.path("channels").get(0).path("rows").get(0).path("pricePerKg").asDouble());
            s.getRow(1).getCell(4).setCellFormula("1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            parsed=parser.parse(bytes(book),"顺丰.xlsx");assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0);
            assertFalse(parsed.path("channels").get(0).path("rows").get(0).path("quoteReady").asBoolean());
            s.getRow(1).getCell(2).setCellFormula("1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            parsed=parser.parse(bytes(book),"顺丰.xlsx");assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0);
        }
    }
    @Test void sfUsesRowLevelSettlementDiscountOrOriginalWithoutDoubleDiscounting()throws Exception {
        for(boolean xlsx:List.of(false,true))try(Workbook book=xlsx?new XSSFWorkbook():new HSSFWorkbook()) {
            var s=book.createSheet("服装专线");
            row(s,0,"国家名称","Code","运费/kg","折扣率","折扣后运费","专递操作费/票","计费重量限制（kg）");
            row(s,1,"西班牙","ES",72,"","",18,"0.001-1KG");
            row(s,2,"西班牙","ES",76," ","\u00a0",18,"1.001-30KG");
            row(s,3,"德国","DE",71,.6,45.4,22,"0.001-0.1KG");
            row(s,4,"美国","US",145,.8,"",20,"0.001-0.1KG");
            row(s,5,"英国","GB",82,"-",33,16,"0<W<=0.3");
            row(s,6,"英国","GB",83,"",38,16,"0.3<W<=1");
            row(s,7,"英国","GB",85,"-",40,16,"1<W<=20");
            var channel=parser.parse(bytes(book),xlsx?"顺丰.xlsx":"顺丰.xls").path("channels").get(0);
            assertEquals(0,channel.path("errors").asInt(),channel.toString());
            var expected=Map.of(2,72d,3,76d,4,45.4d,5,116d,6,33d,7,38d,8,40d);
            for(var price:channel.path("rows")) {
                int source=price.path("sourceRow").asInt();
                assertEquals(expected.get(source),price.path("pricePerKg").asDouble());
                assertEquals(source<=3?"original":source==5?"original-times-discount":"settlement",price.path("sourcePricingBasis").asText());
                if(source<=3){assertTrue(price.path("sourceDiscountEmpty").asBoolean());assertTrue(price.path("sourceSettlementEmpty").asBoolean());}
            }
        }
    }
    @Test void sfDoesNotTreatZeroInvalidTextOrEmptyResultFormulasAsNoDiscount()throws Exception {
        for(String target:List.of("discount","settlement"))for(Object bad:List.of(0,-.8,1.2,"待定","FORMULA_EMPTY","FORMULA_ERROR"))try(var book=new XSSFWorkbook()) {
            if(target.equals("settlement")&&bad.equals(1.2))continue;
            var s=book.createSheet("服装专线");
            row(s,0,"国家名称","Code","运费/kg","折扣率","折扣后运费","专递操作费/票","计费重量限制（kg）");
            row(s,1,"西班牙","ES",72,"","",18,"0.001-1KG");
            var cell=s.getRow(1).getCell(target.equals("discount")?3:4);
            if(bad instanceof Number number)cell.setCellValue(number.doubleValue());
            else if(bad.toString().startsWith("FORMULA")){cell.setCellFormula(bad.equals("FORMULA_EMPTY")?"\"\"":"1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();}
            else cell.setCellValue(bad.toString());
            var channel=parser.parse(bytes(book),"顺丰.xlsx").path("channels").get(0);
            assertTrue(channel.path("errors").asInt()>0,target+":"+bad+channel);
            assertFalse(channel.path("quoteReady").asBoolean());
            assertEquals(0,channel.path("rows").get(0).path("pricePerKg").asDouble());
        }
    }
    @Test void skipsLargePostalReferenceSheetsWithoutWeakeningThePriceRowLimit()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var prices=book.createSheet("服装专线");
            row(prices,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");
            row(prices,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            var postal=book.createSheet("国际电商专递CD菲律宾邮编及分区");
            row(postal,100001,"1000","1区");

            var parsed=parser.parse(bytes(book),"顺丰.xlsx");

            assertEquals("reference-only",parsed.path("sheets").get(1).path("status").asText());
            assertEquals(1,parsed.path("channels").size());
            var channel=parsed.path("channels").get(0);
            assertEquals(1,channel.path("rows").size());
            assertTrue(channel.path("quoteReady").asBoolean());
            assertTrue(channel.path("rows").get(0).path("reviewWarning").asText().contains("邮编"));
        }
        try(var book=new XSSFWorkbook()) {
            var prices=book.createSheet("服装专线");
            row(prices,0,"国家","重量段","运费/KG","挂号费/票");
            for(int index=1;index<=LogisticsSourceParser.MAX_PRICE_ROWS_PER_SHEET+1;index++)row(prices,index,"美国","0-1",55,20);
            var normal=book.createSheet("正常渠道");row(normal,0,"国家","重量段","运费/KG","挂号费/票");row(normal,1,"美国","0-1",60,20);
            var result=parser.parse(bytes(book),"顺丰.xlsx");
            assertEquals("filtered",result.path("sheets").get(0).path("status").asText());
            assertEquals(501,result.path("sheets").get(0).path("filteredPriceRows").asInt());
            assertEquals(1,result.path("channels").size());
            assertEquals(60,result.path("channels").get(0).path("rows").get(0).path("pricePerKg").asInt());
        }
    }
    @Test void keepsExactly500PriceRowsForBothExcelFormats()throws Exception {
        for(boolean xlsx:List.of(false,true))try(Workbook book=xlsx?new XSSFWorkbook():new HSSFWorkbook()){
            var prices=book.createSheet("价格表");row(prices,0,"国家","重量段","运费/KG","挂号费/票");
            for(int index=1;index<=500;index++)row(prices,index,"美国",(index-1)+"-"+index,55,20);
            var result=parser.parse(bytes(book),xlsx?"花海.xlsx":"花海.xls");
            assertNotEquals("filtered",result.path("sheets").get(0).path("status").asText());
            assertEquals(500,result.path("channels").get(0).path("rows").size());
        }
    }
    @Test void distinguishesDocumentationSheetsFromChannelsAndCoverageEvidence()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var prices=book.createSheet("服装专线");row(prices,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");row(prices,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            row(book.createSheet("公布价目录"),0,"服装专线","第3页");
            row(book.createSheet("顺丰国际电商系列产品收寄说明"),0,"说明内容");
            row(book.createSheet("理赔标准"),0,"理赔说明");

            var parsed=parser.parse(bytes(book),"顺丰.xlsx");

            assertEquals(1,parsed.path("channels").size());
            assertEquals("documentation",parsed.path("sheets").get(1).path("referenceKind").asText());
            assertEquals("documentation",parsed.path("sheets").get(2).path("referenceKind").asText());
            assertEquals("documentation",parsed.path("sheets").get(3).path("referenceKind").asText());
            assertTrue(parsed.path("channels").get(0).path("quoteReady").asBoolean());
            assertFalse(parsed.path("channels").get(0).path("etaReady").asBoolean());
        }
    }
    @Test void linksCoverageReferenceOnlyToItsNamedChannel()throws Exception {
        try(var book=new XSSFWorkbook()) {
            for(var name:List.of("服装专线","标准专线")){
                var prices=book.createSheet(name);row(prices,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");row(prices,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            }
            row(book.createSheet("服装专线不提供服务的邮编"),0,"1000","1区");

            var parsed=parser.parse(bytes(book),"顺丰.xlsx");

            assertEquals(2,parsed.path("channels").size());
            var clothing=findChannel(parsed,"服装专线");var standard=findChannel(parsed,"标准专线");
            assertTrue(clothing.path("quoteReady").asBoolean());
            assertTrue(clothing.path("rows").get(0).path("reviewWarning").asText().contains("邮编"));
            assertTrue(standard.path("quoteReady").asBoolean());
            assertFalse(standard.path("rows").get(0).path("pendingReason").asText().contains("邮编"));
            assertFalse(standard.path("etaReady").asBoolean());
        }
    }
    @Test void keepsPostalNamedPriceChannelsWhileSkippingQualifiedPostalReferences()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var prices=book.createSheet("万邦美国特惠专线-精选邮编");
            row(prices,0,"国家","重量段","运费/KG","挂号费/票");row(prices,1,"美国","0-1",55,20);
            row(book.createSheet("万邦美国特惠专线-精选邮编可发货邮编"),0,"10001","可发货");

            var parsed=parser.parse(bytes(book),"万邦.xlsx");

            assertEquals(1,parsed.path("channels").size());
            assertEquals("万邦美国特惠专线-精选邮编",parsed.path("channels").get(0).path("channelName").asText());
            assertEquals("reference-only",parsed.path("sheets").get(1).path("status").asText());
            assertTrue(parsed.path("channels").get(0).path("quoteReady").asBoolean());
        }
    }
    @Test void normalizesConfirmedHuahaiOneGramTransition()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","产品名称","重量段始（KG）","重量段终（KG）","运费（RMB/KG）","操作费（RMB/票）");
            row(s,1,"美国","普货渠道",0.05,0.2,55,20);row(s,2,"美国","普货渠道",0.21,0.5,60,20);
            var parsed=parser.parse(bytes(book),"花海.xlsx");var next=parsed.path("channels").get(0).path("rows").get(1);
            assertEquals(0.201,next.path("weightFromKg").asDouble());assertTrue(next.path("weightFromInclusive").asBoolean());
            assertTrue(next.path("sourceWeightRange").asText().contains("0.21"));assertEquals(0,next.path("billingStepKg").asDouble());
        }
    }
    @Test void doesNotInventOneHundredPercentWhenOldPriceIsZero(){
        var old=mapper.createArrayNode();old.addObject().put("rowKey","same").put("pricePerKg",0);
        var next=mapper.createArrayNode();next.addObject().put("rowKey","same").put("pricePerKg",10);
        var diff=standard.compare(next,old).path("diffRows").get(0);
        assertTrue(diff.path("maxPercentChange").isNull());assertEquals(10,diff.path("changes").get(0).path("delta").asDouble());assertTrue(diff.path("changes").get(0).path("percentChange").isNull());
    }
    @Test void businessFingerprintIgnoresFilenameRowNumberAndSourceCodes(){
        var a=mapper.createArrayNode();a.addObject().put("rowKey","row").put("pricePerKg",55).put("sourceFile","旧表.xlsx").put("sourceCode","OLD").put("sourceRow",4);
        var b=a.deepCopy();((ObjectNode)b.get(0)).put("sourceFile","新表.xlsx").put("sourceCode","NEW").put("sourceRow",10);
        assertEquals(parser.businessHash(a),parser.businessHash(b));assertEquals("递四方",LogisticsSourceParser.provider("4px价格.xlsx"));
    }
    @Test void removesOnlyLeadingUploadDateAndNeverRequiresOne(){
        assertEquals("燕文价格.xlsx",LogisticsImportService.displayFileName("8.27燕文价格.xlsx"));
        assertEquals("燕文价格.xlsx",LogisticsImportService.displayFileName("2026-08-27 燕文价格.xlsx"));
        assertEquals("燕文价格.xlsx",LogisticsImportService.displayFileName("2026年8月27日燕文价格.xlsx"));
        assertEquals("4px价格.xlsx",LogisticsImportService.displayFileName("4px价格.xlsx"));
        assertEquals("顺丰价格.xlsx",LogisticsImportService.displayFileName("顺丰价格.xlsx"));
    }
    @Test void blocksCrossSheetOverlappingChannelAndPreservesDuplicatesInFingerprint()throws Exception {
        try(var book=new XSSFWorkbook()) {
            for(var name:List.of("第一页","第二页")) {
                var s=book.createSheet(name);
                row(s,0,"国家","产品名称","重量段始（KG）","重量段终（KG）","运费（RMB/KG）","操作费（RMB/票）");
                row(s,1,"美国","同一普货渠道",0.05,0.2,55,20);
            }
            var c=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            assertEquals(2,c.path("rows").size());assertFalse(c.path("quoteReady").asBoolean());assertTrue(c.path("errors").asInt()>0);
            var one=mapper.createArrayNode().add(c.path("rows").get(0));
            assertNotEquals(parser.businessHash(one),parser.businessHash(one.deepCopy().add(c.path("rows").get(1))));
        }
    }
    @Test void comparesConditionalRulesAndLinehaulFees(){
        var before=mapper.createArrayNode();before.addObject().put("rowKey","same").put("billingStepKg",0).put("linehaulPerKg",0).put("pendingReason","");
        var after=before.deepCopy();((ObjectNode)after.get(0)).put("billingStepKg",0.1).put("linehaulPerKg",5).put("pendingReason","需适配");
        var diff=standard.compare(after,before).path("diffRows").get(0);
        assertEquals(2,diff.path("changes").size());assertNotEquals("unchanged",diff.path("type").asText());
    }
    @Test void shippedTemplateIsImportableAndExamplesCannotQuote()throws Exception {
        var parsed=parser.parse(Files.readAllBytes(Path.of("../public/templates/logistics-v2.xlsx")),"标准模板.xlsx");
        assertEquals("metadata",parsed.path("sheets").get(0).path("status").asText());
        assertEquals(1,parsed.path("channels").size());int rows=0;
        for(var channel:parsed.path("channels")){assertEquals(0,channel.path("errors").asInt(),channel.toString());assertFalse(channel.path("quoteReady").asBoolean());rows+=channel.path("rows").size();}
        assertEquals(2,rows);
    }
    @Test void changedShippingNotesRemainTraceableWithoutGeneratingPriceReview()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","0-1",55,20);
            row(s,2,"附加费规则：偏远地区每票附加操作费用人民币20元，需要在报价时另行核对。");
            var first=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            s.getRow(2).getCell(0).setCellValue("附加费规则：偏远地区每票附加操作费用人民币30元，需要在报价时另行核对。");
            var second=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            assertEquals(first.path("contentHash"),second.path("contentHash"));
            assertNotEquals(first.path("rows").get(0).path("notes"),second.path("rows").get(0).path("notes"));
            assertEquals("unchanged",standard.compare((tools.jackson.databind.node.ArrayNode)second.path("rows"),(tools.jackson.databind.node.ArrayNode)first.path("rows")).path("diffRows").get(0).path("type").asText());
        }
    }
    @Test void keepsSecondaryRedeliveryFeeTableAsPendingEvidenceNotBaseFreight()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","<=1KG",55,20);
            row(s,3,"重量（KG）","重派费（CNY）");row(s,4,"0-0.5KG",30);
            var result=parser.parse(bytes(book),"顺丰.xlsx");var c=result.path("channels").get(0);
            assertEquals(1,c.path("rows").size());assertEquals(0,c.path("errors").asInt());assertTrue(c.path("quoteReady").asBoolean());
            assertEquals(1,result.path("sheets").get(0).path("conditionalPriceRows").size());assertTrue(c.path("rows").get(0).path("notes").asText().contains("30"));
        }
    }
    @Test void enrichesOnlyYanwenFromCountryReferenceBelowPricesAndPreservesInlineEta()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("燕文专线追踪-普货");
            row(s,0,"大洲","国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)","参考时效");
            row(s,1,"北美洲","美国","US",143,20,"0.001-0.1","");row(s,2,"","","",137,18,"0.101-0.2","");
            s.addMergedRegion(new CellRangeAddress(1,2,0,0));s.addMergedRegion(new CellRangeAddress(1,2,1,1));s.addMergedRegion(new CellRangeAddress(1,2,2,2));
            row(s,3,"欧洲","英国","GB",75,25,"0.001-1","3-4工作日");
            row(s,6,"大洲","国家","Country Code","参考时效 (工作日)");row(s,7,"北美洲","美国","US","6-12工作日");row(s,8,"欧洲","英国","GB","5-10个工作日");
            var channel=parser.parse(bytes(book),"8.27燕文价格.xlsx").path("channels").get(0);assertEquals(3,channel.path("rows").size());assertEquals(0,channel.path("errors").asInt(),channel.toString());
            for(var value:channel.path("rows"))if(value.path("countryCode").asText().equals("US")){assertEquals(6,value.path("etaMinDays").asInt());assertEquals(12,value.path("etaMaxDays").asInt());assertEquals("country",value.path("sourceEtaScope").asText());}
            var gb=findCountry(channel,"GB");assertEquals(3,gb.path("etaMinDays").asInt());assertEquals(4,gb.path("etaMaxDays").asInt());assertEquals("row",gb.path("sourceEtaScope").asText());
        }
    }
    @Test void findsYanwenCountryReferencesAbovePricesAndSupportsPlainDays()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("燕文精品追踪-普货");row(s,0,"大洲","国家","Country Code","参考时效 (工作日)");
            row(s,1,"欧洲","意大利","IT","12-14天");row(s,2,"欧洲","西班牙","ES","全段时效：9-14个工作日");
            row(s,5,"大洲","国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)");row(s,6,"欧洲","意大利","IT",80,22,"0.001-1");row(s,7,"欧洲","西班牙","ES",82,22,"0.001-1");
            var channel=parser.parse(bytes(book),"燕文价格.xlsx").path("channels").get(0);assertEquals(12,findCountry(channel,"IT").path("etaMinDays").asInt());assertEquals(14,findCountry(channel,"IT").path("etaMaxDays").asInt());assertEquals(9,findCountry(channel,"ES").path("etaMinDays").asInt());
        }
    }
    @Test void mapsOnlyExplicitEpacketContinentsAndBlocksConflictingCountryReferences()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("中邮上海线下E邮宝");row(s,0,"大洲","国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)");
            row(s,1,"北美洲","加拿大","CA",78,18,"0.001-2");row(s,2,"欧洲","英国","GB",75,25,"0.001-2");row(s,3,"非洲","南非","ZA",75,22,"0.001-2");
            row(s,6,"参考时效","北美洲：揽收-到达目的国15-30个工作日");row(s,7,"", "欧洲：揽收-到达目的国15-25个工作日");
            var channel=parser.parse(bytes(book),"燕文价格.xlsx").path("channels").get(0);assertEquals(15,findCountry(channel,"CA").path("etaMinDays").asInt());assertEquals(30,findCountry(channel,"CA").path("etaMaxDays").asInt());assertEquals(15,findCountry(channel,"GB").path("etaMinDays").asInt());assertEquals(0,findCountry(channel,"ZA").path("etaMinDays").asInt());
        }
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("燕文专线惠选-普货");row(s,0,"国家","CountryCode","公斤运费(元/KG)","处理费(元/件)","重量段(KG)");row(s,1,"美国","US",100,20,"0.001-1");
            row(s,3,"国家","Country Code","参考时效 (工作日)");row(s,4,"美国","US","6-12工作日");row(s,5,"美国","US","8-15工作日");
            var channel=parser.parse(bytes(book),"燕文价格.xlsx").path("channels").get(0);assertEquals(0,channel.path("errors").asInt());assertTrue(channel.path("quoteReady").asBoolean());assertEquals(0,findCountry(channel,"US").path("etaMinDays").asInt());
        }
    }
    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void auditsEveryRealWorkbookAndChecksHandCalculatedSamples()throws Exception {
        var root=Path.of(System.getProperty("logistics.corpusDir"));var report=mapper.createArrayNode();var books=new LinkedHashMap<String,JsonNode>();
        try(var paths=Files.list(root)) {
            for(var path:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList()) {
                long start=System.nanoTime();var parsed=parser.parse(Files.readAllBytes(path),path.getFileName().toString());books.put(path.getFileName().toString(),parsed);
                try(var w=WorkbookFactory.create(path.toFile(),null,true)){assertEquals(w.getNumberOfSheets(),parsed.path("sheets").size());}
                var summary=parsed.deepCopy();summary.remove("channels");var cs=summary.putArray("channels");
                for(var c:parsed.path("channels"))cs.addObject().put("name",c.path("channelName").asText()).put("rows",c.path("rows").size()).put("errors",c.path("errors").asInt()).put("quoteReady",c.path("quoteReady").asBoolean()).set("issues",c.path("issues"));
                summary.put("elapsedMs",(System.nanoTime()-start)/1_000_000);report.add(summary);
                System.out.println(path.getFileName()+" sheets="+parsed.path("sheets").size()+" channels="+parsed.path("channels").size()+" ms="+summary.path("elapsedMs"));
            }
        }
        Files.createDirectories(Path.of("target/logistics-corpus"));Files.writeString(Path.of("target/logistics-corpus/report.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertEquals(11,books.size());
        assertSample(books.get("7.30花海.xlsx"),"美国精选商派专线-普货","US",0.5,64,18);
        assertSample(books.get("8.12容鼎.xlsx"),"美国纯商派DDP专线-普货","US",0.5,72,16);
        assertSample(books.get("8.1云速递价格.xlsx"),"全球专线普货","US",0.5,78,20);
        assertSample(books.get("8.7顺丰价格.xlsx"),"服装专线","DE",0.05,45.44,22);
    }
    @Test @EnabledIfSystemProperty(named="logistics.candidateDir",matches=".+")
    void everyCandidateWorkbookHasARecognizedPriceChannel()throws Exception {
        var root=Path.of(System.getProperty("logistics.candidateDir"));var pending=new ArrayList<String>();var pendingNames=new TreeSet<String>();var files=new ArrayList<Path>();
        try(var paths=Files.list(root)){files.addAll(paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList());}
        assertFalse(files.isEmpty(),"候选物流文件目录不能为空");
        for(var path:files) {
            var parsed=parser.parse(Files.readAllBytes(path),path.getFileName().toString());
            try(var workbook=WorkbookFactory.create(path.toFile(),null,true)){assertEquals(workbook.getNumberOfSheets(),parsed.path("sheets").size(),path.getFileName().toString());}
            int recognized=0,rows=0,errors=0;var errorFields=new TreeMap<String,Integer>();for(var channel:parsed.path("channels")){rows+=channel.path("rows").size();errors+=channel.path("errors").asInt();for(var issue:channel.path("issues"))if("error".equals(issue.path("level").asText()))errorFields.merge(issue.path("field").asText(),1,Integer::sum);if("adapter-required".equals(channel.path("templateStatus").asText())){pendingNames.add(channel.path("channelName").asText());pending.add(path.getFileName()+" / "+channel.path("channelName").asText()+" / "+channel.path("issues"));}else recognized++;}
            System.out.println(path.getFileName()+" sheets="+parsed.path("sheets").size()+" channels="+parsed.path("channels").size()+" recognized="+recognized+" rows="+rows+" errors="+errors+" errorFields="+errorFields);
            assertTrue(recognized>0,path.getFileName()+" 没有识别到可审核的价格渠道");
        }
        var expectedPending=Set.<String>of();
        assertEquals(expectedPending,pendingNames,()->"待人工确认的模板集合发生变化：\n"+String.join("\n",pending));
    }
    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void freezesRealWorkbookBusinessBaselines()throws Exception {
        var root=Path.of(System.getProperty("logistics.corpusDir"));
        var unrelated=new TreeMap<String,String>();var yanwenCore=new TreeMap<String,String>();
        try(var paths=Files.list(root)) {
            for(var path:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList()) {
                var file=path.getFileName().toString();var parsed=parser.parse(Files.readAllBytes(path),file);
                for(var channel:parsed.path("channels")) {
                    var key=file+"|"+LogisticsSourceParser.identity(channel);
                    if(!file.equals("8.27燕文价格.xlsx"))unrelated.put(key,channel.path("contentHash").asText());
                    else {
                        var rows=mapper.createArrayNode();
                        for(var value:channel.path("rows")) {
                            var row=(ObjectNode)value.deepCopy();row.remove(List.of("etaMinDays","etaMaxDays","sourceEtaScope","sourceEtaCell","sourceEtaText"));rows.add(row);
                        }
                        yanwenCore.put(key,parser.businessHash(rows));
                    }
                }
            }
        }
        var unrelatedHash=LogisticsDatasetService.hash(mapper.writeValueAsString(unrelated));
        var yanwenCoreHash=LogisticsDatasetService.hash(mapper.writeValueAsString(yanwenCore));
        assertEquals(Map.of("unrelated","955e5ec198a45bb221c5762854e5af5c4403930f0bbbe574ca1ac714db98ec8c","yanwen","e93803b19029147e8f7c5fa198201fed0e2b175802896889714be68cbbbcdc15"),Map.of("unrelated",unrelatedHash,"yanwen",yanwenCoreHash));
    }
    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void enrichesThePinnedRealYanwenWorkbookWithoutChangingRows()throws Exception {
        var path=Path.of(System.getProperty("logistics.corpusDir")).resolve("8.27燕文价格.xlsx");var bytes=Files.readAllBytes(path);
        assertEquals("67D6E7A198E1AB685F816195BF930731C47298E27A780AA77210569894B525E4",HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var parsed=parser.parse(bytes,path.getFileName().toString());var channels=new LinkedHashMap<String,JsonNode>();for(var channel:parsed.path("channels"))channels.put(channel.path("channelName").asText(),channel);
        var expectedRows=Map.of("燕文专线追踪-普货",187,"燕文专线追踪-特货",195,"燕文专线惠选-普货",34,"燕文化妆品专线",111,"燕文服装专线-普货",51,"燕文精品追踪-普货",12,"中邮上海线下E邮宝",30);
        assertEquals(expectedRows.keySet(),channels.keySet());int total=0,withEta=0;var unknown=new ArrayList<String>();var etaCounts=new LinkedHashMap<String,Integer>();var etaCountries=new LinkedHashMap<String,Set<String>>();
        for(var entry:expectedRows.entrySet()) {
            var channel=channels.get(entry.getKey());assertEquals(entry.getValue(),channel.path("rows").size(),entry.getKey());total+=channel.path("rows").size();
            var countriesWithEta=new TreeSet<String>();int channelEta=0;for(var row:channel.path("rows"))if(row.path("etaMinDays").asInt()>0&&row.path("etaMaxDays").asInt()>=row.path("etaMinDays").asInt()){withEta++;channelEta++;countriesWithEta.add(row.path("countryCode").asText());}else unknown.add(entry.getKey()+"|"+row.path("countryCode").asText()+"|"+row.path("sourceRow").asInt());etaCounts.put(entry.getKey(),channelEta);etaCountries.put(entry.getKey(),countriesWithEta);
        }
        assertEquals(620,total);assertEquals(617,withEta,etaCounts+" countries="+etaCountries);assertEquals(List.of("中邮上海线下E邮宝|EG|32","中邮上海线下E邮宝|MA|33","中邮上海线下E邮宝|ZA|34"),unknown);
        assertEta(channels.get("燕文专线追踪-普货"),"US",6,12);assertEta(channels.get("燕文专线追踪-普货"),"GB",5,10);assertEta(channels.get("燕文专线追踪-普货"),"FR",6,10);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {var sheet=workbook.getSheet("燕文精品追踪-普货");assertEquals("12-14天",sheet.getRow(79).getCell(3).getStringCellValue());assertEquals("9-14天",sheet.getRow(80).getCell(3).getStringCellValue());assertEquals("7-15天",sheet.getRow(81).getCell(3).getStringCellValue());}
        var epacketPairs=new HashSet<String>();for(var row:channels.get("中邮上海线下E邮宝").path("rows"))if(row.path("etaMinDays").asInt()>0)epacketPairs.add(row.path("etaMinDays").asInt()+"-"+row.path("etaMaxDays").asInt());
        assertEquals(Set.of("10-25","15-25","15-30"),epacketPairs);
    }
    private void assertSample(JsonNode book,String channel,String country,double weight,double price,double fee){
        var rows=new ArrayList<JsonNode>();for(var c:book.path("channels"))if(c.path("channelName").asText().equals(channel))for(var r:c.path("rows"))if(r.path("countryCode").asText().equals(country)&&r.path("zoneName").asText().isBlank()&&(weight>r.path("weightFromKg").asDouble()||(weight==r.path("weightFromKg").asDouble()&&r.path("weightFromInclusive").asBoolean()))&&(weight<r.path("weightToKg").asDouble()||(weight==r.path("weightToKg").asDouble()&&r.path("weightToInclusive").asBoolean())))rows.add(r);
        assertEquals(1,rows.size(),channel+" sample must be unique");assertEquals(price,rows.getFirst().path("pricePerKg").asDouble());assertEquals(fee,rows.getFirst().path("registrationFee").asDouble());
    }
    private void assertEta(JsonNode channel,String country,int min,int max){int found=0;for(var row:channel.path("rows"))if(row.path("sourceCountryCode").asText().equals(country)){found++;assertEquals(min,row.path("etaMinDays").asInt(),channel.path("channelName").asText()+" "+country);assertEquals(max,row.path("etaMaxDays").asInt(),channel.path("channelName").asText()+" "+country);}assertTrue(found>0,"missing ETA sample "+country);}
    private JsonNode findChannel(JsonNode parsed,String name){for(var channel:parsed.path("channels"))if(channel.path("channelName").asText().equals(name))return channel;return fail("missing channel "+name);}
    private JsonNode findCountry(JsonNode channel,String country){for(var row:channel.path("rows"))if(row.path("countryCode").asText().equals(country))return row;return fail("missing country "+country);}
    static byte[] bytes(Workbook book)throws Exception{var out=new ByteArrayOutputStream();book.write(out);return out.toByteArray();}
    static void row(Sheet s,int number,Object...values){var r=s.createRow(number);for(int i=0;i<values.length;i++){var c=r.createCell(i);if(values[i] instanceof Number n)c.setCellValue(n.doubleValue());else c.setCellValue(String.valueOf(values[i]));}}
}
