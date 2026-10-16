package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;

import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RongdingImportIdentitySafetyTest {
    private final RongdingProductCellTest fixture=new RongdingProductCellTest();

    @Test void unknownCodeInLastTierCannotSilentlyCreateAPublishablePartialChannel()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture.fixture()))) {
            var sheet=book.getSheetAt(0);
            unmergeProductCodes(sheet);
            for(int r=21;r<=26;r++)sheet.getRow(r).getCell(2).setCellValue(fixture.code(0));
            sheet.getRow(26).getCell(2).setCellValue("PK09000(A)\nPK09001(C)");

            var result=parse(book);
            assertBlocked(result,RongdingProductCellTest.CODES[0]);
        }
    }

    @Test void worksheetTitleCannotOverrideMalformedExplicitProductCode()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("旧渠道普货");
            row(sheet,0,"渠道名称：旧渠道普货");
            row(sheet,1,"国家","产品代码","重量段(KG)","包裹运费(元/KG)","处理费(元/件)","时效");
            row(sheet,2,"美国","PK00186","0.05-1",71,20,"8-12天");
            row(sheet,3,"美国","PK00186(B)\nPK00208(C)","1-2",81,12,"8-12天");

            var result=parse(book);
            assertBlocked(result,RongdingProductCellTest.CODES[0]);
        }
    }

    @Test void whollyAmbiguousSecondSheetCannotLeaveTheSameChannelPublishable()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture.fixture()))) {
            var continuation=book.cloneSheet(0,"容鼎普货续表");
            continuation.getRow(21).getCell(2).setCellValue("PK00186(B)\nPK00208(C)");

            var result=parse(book);
            assertTrue(result.path("ambiguousChannels").asInt()>0,result.path("sheets").toString());
            assertBlocked(result,RongdingProductCellTest.CODES[0]);
        }
    }

    @Test void twentyFiveUnregisteredGroupsDoNotBlockTheFiveCompleteRegisteredChannels()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture.fixture()))) {
            var sheet=book.getSheetAt(0);
            for(int i=0;i<25;i++) {
                String name="未登记测试组"+i;
                String codes="PK"+(90000+i*2)+"(A)\nPK"+(90001+i*2)+"(C)";
                row(sheet,30+i,"商派",name+"A(SP)\n"+name+"C(SP)",codes,"0.05-5",100+i,20,.05,"8-12天","商派");
            }

            var result=parse(book);
            fixture.assertPrices(result);
            assertEquals(25,result.path("filteredChannels").asInt(),result.path("sheets").toString());
            assertEquals(0,result.path("ambiguousChannels").asInt(),result.path("sheets").toString());
        }
    }

    @Test void mergedFooterNotesNeverBecomeProductIdentityConflicts()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture.fixture()))) {
            var sheet=book.getSheetAt(0);
            row(sheet,34,"计费规则");sheet.addMergedRegion(new CellRangeAddress(34,34,0,8));
            row(sheet,35,"说明：本段文字仅用于说明产品服务，不是价格记录，保留原文供核对。");
            sheet.addMergedRegion(new CellRangeAddress(35,36,0,8));
            row(sheet,38,"重量与尺寸限制","","","","请核对产品适用尺寸；这里的说明并非价格行或产品代码。");
            sheet.addMergedRegion(new CellRangeAddress(38,38,0,3));
            sheet.addMergedRegion(new CellRangeAddress(38,38,4,8));
            row(sheet,40,"","申报价值","","","该文字从原价格列开始，但前方说明合并名称和产品代码，仍不是价格记录。");
            sheet.addMergedRegion(new CellRangeAddress(40,40,1,3));
            sheet.addMergedRegion(new CellRangeAddress(40,40,4,8));

            var result=parse(book);
            fixture.assertPrices(result);
            assertEquals(0,result.path("ambiguousChannels").asInt(),result.path("sheets").toString());
            assertEquals(5,result.path("matchedChannels").asInt());
            assertTrue(result.path("sheets").toString().contains("保留原文供核对"),"Footer evidence must remain available");
        }
    }

    @Test void missingOrMalformedWeightOnARealPriceRowStillBlocksTheChannel()throws Exception {
        for(String weight:new String[]{"","待确认"})try(var book=new XSSFWorkbook(new ByteArrayInputStream(fixture.fixture()))) {
            book.getSheetAt(0).getRow(26).getCell(3).setCellValue(weight);
            assertBlocked(parse(book),RongdingProductCellTest.CODES[0]);
        }
    }

    private JsonNode parse(XSSFWorkbook book)throws Exception {
        return fixture.parser.parse(bytes(book),"容鼎身份安全回归.xlsx",new CompanyChannelScope(fixture.snapshot()));
    }

    private void assertBlocked(JsonNode result,String companyChannelId) {
        var channel=result.path("channels").valueStream()
                .filter(value->value.path("companyChannelId").asText().equals(companyChannelId))
                .findFirst().orElseThrow(()->new AssertionError("The valid rows must remain visible for review: "+result.path("sheets")));
        assertFalse(channel.path("rows").isEmpty(),"The test must exercise an otherwise importable partial channel");
        assertTrue(channel.path("errors").asInt()>0,channel.toString());
        assertFalse(channel.path("pricingReady").asBoolean(),channel.toString());
        assertFalse(channel.path("quoteReady").asBoolean(),channel.toString());
        for(var price:channel.path("rows"))assertFalse(price.path("quoteReady").asBoolean(),price.toString());
    }

    private void unmergeProductCodes(Sheet sheet) {
        for(int i=sheet.getNumMergedRegions()-1;i>=0;i--) {
            var range=sheet.getMergedRegion(i);
            if(range.getFirstColumn()==2&&range.getLastColumn()==2)sheet.removeMergedRegion(i);
        }
    }
}
