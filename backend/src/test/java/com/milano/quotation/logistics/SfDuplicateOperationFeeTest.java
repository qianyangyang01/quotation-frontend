package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SfDuplicateOperationFeeTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    XSSFWorkbook workbook(Object first,Object duplicate) {
        var book=new XSSFWorkbook();var sheet=book.createSheet("敏货电商专递");
        LogisticsSourceParserTest.row(sheet,0,"","序号","Country","国家名称","Code","参考时效（工作日）","运费/kg","专递操作费/票","计费重量限制（kg）","折扣率","折扣后运费","专递操作费/票","计费重量限制（kg）");
        LogisticsSourceParserTest.row(sheet,1,"",1,"Poland","波兰","PL","6-8",95,first,"0.201-2KG","","",duplicate,"3.001-20KG");
        return book;
    }
    JsonNode parse(XSSFWorkbook book)throws Exception{return parse(book,"顺丰.xlsx");}
    JsonNode parse(XSSFWorkbook book,String file)throws Exception {
        var channels=parser.parse(LogisticsSourceParserTest.bytes(book),file).path("channels");
        assertEquals(1,channels.size());return channels.get(0);
    }
    void blocked(JsonNode channel,String reason) {
        assertTrue(channel.path("errors").asInt()>0,channel.toString());
        assertFalse(channel.path("quoteReady").asBoolean());
        assertFalse(channel.path("rows").get(0).path("quoteReady").asBoolean());
        assertTrue(channel.path("issues").toString().contains(reason),channel.path("issues").toString());
    }

    @Test void usesPopulatedOriginalFeeAndRetainsOriginalWeightBand()throws Exception {
        try(var book=workbook(15,"")) {
            var channel=parse(book);var row=channel.path("rows").get(0);
            assertEquals(0,channel.path("errors").asInt());assertTrue(channel.path("quoteReady").asBoolean());
            assertEquals(95,row.path("pricePerKg").asDouble());assertEquals(15,row.path("registrationFee").asDouble());
            assertEquals("H2",row.path("sourceFeeCell").asText());assertEquals("I2",row.path("sourceWeightCell").asText());
            assertEquals(.201,row.path("weightFromKg").asDouble());assertEquals(2,row.path("weightToKg").asDouble());
            assertEquals(2,row.path("sourceFeeCandidates").size());assertTrue(row.path("sourceFeeCandidates").get(1).path("blank").asBoolean());
            assertEquals(110,new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country","PL").put("weightKg",1)).path("total").asDouble());
        }
    }
    @Test void acceptsMatchingNumbersAndExplicitZeroAndEitherBlankPosition()throws Exception {
        for(var pair:new Object[][]{{15,15.0},{15,"15.00"},{0,0},{0,""},{"",0},{"",15},{" \u00a0",15}}) {
            try(var book=workbook(pair[0],pair[1])) {
                var c=parse(book);assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
                assertTrue(c.path("quoteReady").asBoolean());
                double expected=pair[0] instanceof Number n?n.doubleValue():15;
                if(pair[1] instanceof Number n&&n.doubleValue()==0)expected=0;
                assertEquals(expected,c.path("rows").get(0).path("registrationFee").asDouble());
            }
        }
    }
    @Test void confirmedOperationFeeColumnWinsWithoutDiscountingIt()throws Exception {
        for(var pair:new Object[][]{{15,23},{0,15},{15,0}})try(var book=workbook(pair[0],pair[1])) {
            var sheet=book.getSheetAt(0);sheet.getRow(1).getCell(9).setCellValue(.5);sheet.getRow(1).getCell(10).setCellValue(47.5);
            var c=parse(book);assertEquals(0,c.path("errors").asInt());
            assertEquals(((Number)pair[1]).doubleValue(),c.path("rows").get(0).path("registrationFee").asDouble());
            assertEquals("L2",c.path("rows").get(0).path("sourceFeeCell").asText());
            assertEquals(47.5,c.path("rows").get(0).path("pricePerKg").asDouble());
        }
        try(var book=workbook("待定",15)){assertEquals(15,parse(book).path("rows").get(0).path("registrationFee").asDouble());}
    }
    @Test void unrecognizedDuplicateLayoutKeepsConflictingValuesBlocked()throws Exception {
        for(var pair:new Object[][]{{15,23},{0,15},{15,0}})try(var book=workbook(pair[0],pair[1])) {
            book.getSheetAt(0).getRow(0).getCell(9).setCellValue("备注");
            var c=parse(book);blocked(c,"金额冲突");
            assertTrue(c.path("issues").toString().contains("H2="));assertTrue(c.path("issues").toString().contains("L2="));
            assertFalse(c.path("rows").get(0).has("sourceFeeCell"));
        }
    }
    @Test void bothBlankAndAnyInvalidPopulatedValueStayBlocked()throws Exception {
        try(var book=workbook("","")){blocked(parse(book),"操作费缺失");}
        for(var bad:new Object[]{-1,"待定","#VALUE!","-"}) {
            try(var book=workbook(15,bad)){blocked(parse(book),"L2");}
            try(var book=workbook(bad,"")){blocked(parse(book),"H2");}
        }
        for(boolean net:new boolean[]{true,false})try(var book=workbook(15,"")) {
            if(net)book.getSheetAt(0).getRow(1).getCell(10).setCellValue(47.5);
            else book.getSheetAt(0).getRow(1).getCell(9).setCellValue(.5);
            blocked(parse(book),"禁止回退原价操作费");
        }
    }
    @Test void cachedNumericFormulaIsAcceptedButErrorTextAndExternalFormulaAreNotBlank()throws Exception {
        for(var formula:new String[]{"15","1/0","\"\""})try(var book=workbook(15,"")) {
            book.getSheetAt(0).getRow(1).getCell(11).setCellFormula(formula);
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            var c=parse(book);
            if(formula.equals("15")){assertEquals(0,c.path("errors").asInt());assertEquals(15,c.path("rows").get(0).path("registrationFee").asDouble());}
            else blocked(c,"L2");
        }
        try(var book=workbook(15,15)) {
            var cell=book.getSheetAt(0).getRow(1).getCell(11);
            cell.getCTCell().addNewF().setStringValue("[1]Sheet1!A1");cell.getCTCell().setV("15");
            blocked(parse(book),"L2");
        }
    }
    @Test void mergedOriginalFeesAreResolvedWithinTheMergeOnly()throws Exception {
        try(var book=workbook(15,"")) {
            var sheet=book.getSheetAt(0);
            LogisticsSourceParserTest.row(sheet,2,"",1,"Poland","波兰","PL","6-8",95,"","2.001-20KG","","","","");
            sheet.addMergedRegion(new CellRangeAddress(1,2,7,7));
            var c=parse(book);assertEquals(0,c.path("errors").asInt());assertEquals(2,c.path("rows").size());
            assertEquals(15,c.path("rows").get(1).path("registrationFee").asDouble());
            sheet.removeMergedRegion(0);
            blocked(parse(book),"操作费缺失");
        }
    }
    @Test void differentFeeLabelsRequireReviewAndOtherProvidersKeepExistingSelection()throws Exception {
        try(var book=workbook(15,15)) {
            book.getSheetAt(0).getRow(0).getCell(11).setCellValue("挂号费");blocked(parse(book),"含义不一致");
        }
        try(var book=workbook(15,23)) {
            var c=parse(book,"云速递.xlsx");assertEquals(23,c.path("rows").get(0).path("registrationFee").asDouble());
            assertFalse(c.path("rows").get(0).has("sourceFeeCandidates"));
        }
    }
    @Test @EnabledIfSystemProperty(named="sf.feeWorkbook",matches=".+")
    void currentWorkbookRepairsExactlyFiveMissingFeesAndPreservesAustriaGap()throws Exception {
        var parsed=parser.parse(Files.readAllBytes(Path.of(System.getProperty("sf.feeWorkbook"))),"顺丰.xlsx");
        JsonNode sensitive=null;
        for(var c:parsed.path("channels"))if(c.path("channelName").asText().equals("敏货电商专递"))sensitive=c;
        assertNotNull(sensitive);assertEquals(58,sensitive.path("rows").size());assertEquals(0,sensitive.path("errors").asInt(),sensitive.path("issues").toString());
        int fixed=0;
        for(var row:sensitive.path("rows")) {
            int n=row.path("sourceRow").asInt();
            if(n>=57&&n<=61){assertEquals(n<=58?15:23,row.path("registrationFee").asDouble());assertEquals("H"+n,row.path("sourceFeeCell").asText());fixed++;}
            if(n==56){assertEquals(94.5,row.path("pricePerKg").asDouble());assertEquals(13,row.path("registrationFee").asDouble());}
            if(n==61)assertEquals(5.001,row.path("weightFromKg").asDouble());
        }
        assertEquals(5,fixed);assertTrue(sensitive.path("issues").toString().contains("缺口内不报价"));
        var billing=new LogisticsBillingEngine(mapper);
        assertEquals(110,billing.calculate(sensitive.path("rows"),mapper.createObjectNode().put("country","PL").put("weightKg",1)).path("total").asDouble());
        assertThrows(com.milano.quotation.common.AppException.class,()->billing.calculate(sensitiveRows(parsed),mapper.createObjectNode().put("country","AT").put("weightKg",4)));
    }
    JsonNode sensitiveRows(JsonNode parsed){for(var c:parsed.path("channels"))if(c.path("channelName").asText().equals("敏货电商专递"))return c.path("rows");throw new AssertionError();}
}
