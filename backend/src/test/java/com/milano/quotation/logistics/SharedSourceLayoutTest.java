package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.List;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedSourceLayoutTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    @ParameterizedTest @ValueSource(strings={"花海","容鼎","通邮","万邦","云速递","递四方","极通环球","云途","燕文","顺丰"})
    void notesNeverBecomePricesAndARealFollowingHeaderResumesParsing(String provider) throws Exception {
        for(var label:List.of("价格使用说明","报价使用说明：","国家维度具体要求","注意事项"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");
            row(s,3,"国家","产品编号","重量段","运费/KG","挂号费/票","时效（工作日）","备注");
            row(s,4,"美国","TEST-US","0-1",55,20,"7--15","价格使用说明");
            row(s,5,"美国","TEST-US","1-2",56,20,"7--15","客户须知：请核对当地派送要求");
            row(s,6,label);
            row(s,7,"美国","TEST-US","22000-22999",88,99,"2026-10-04","邮编不通达");
            row(s,9,"国家","产品号","重量段","运费/KG","挂号费/票","时效（自然日）");
            row(s,10,"英国","TEST-GB","0-1",60,21,"8");
            var result=parser.parse(bytes(book),provider+".xlsx");var c=result.path("channels").get(0);
            assertEquals(3,c.path("rows").size(),provider+label+c.path("issues"));
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
            var first=c.path("rows").valueStream().filter(r->r.path("sourceRow").asInt()==5).findFirst().orElseThrow();
            assertEquals(55,first.path("pricePerKg").asInt());assertEquals(20,first.path("registrationFee").asInt());
            assertEquals("TEST-US",first.path("sourceProductCode").asText());assertEquals(7,first.path("etaMinDays").asInt());assertEquals(15,first.path("etaMaxDays").asInt());
            var last=c.path("rows").valueStream().filter(r->r.path("sourceRow").asInt()==11).findFirst().orElseThrow();
            assertEquals(8,last.path("etaMinDays").asInt());assertEquals(8,last.path("etaMaxDays").asInt());
        }
    }

    @ParameterizedTest @ValueSource(strings={"2026-10-04","24-48小时","8-5天","0-8天","5-8/10-12天","上网1-2天，签收7-15天","5-8天或10-12天","约5","1.5-3天"})
    void ambiguousEtaRetainsEvidenceAndCannotBeOverwrittenByAnotherTierOrFooter(String invalid) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票","时效（工作日）");
            row(s,1,"美国","0-1",55,20,invalid);row(s,2,"美国","1-2",56,20,"7-15天");row(s,4,"参考时效：7-15天");
            var c=parser.parse(bytes(book),"通邮.xlsx").path("channels").get(0);var first=c.path("rows").get(0);
            assertEquals(0,first.path("etaMinDays").asInt());assertEquals(0,first.path("etaMaxDays").asInt());
            assertEquals(invalid,first.path("sourceEtaText").asText());assertEquals("E2",first.path("sourceEtaCell").asText());
            assertFalse(c.path("etaReady").asBoolean());assertTrue(c.path("pricingReady").asBoolean());assertEquals(2,c.path("rows").size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"5-8天","5—8个工作日","5~8自然日","5至8日","5--8","5-8 working days","全段时效：5-8个工作日"})
    void acceptsExplicitDayFormats(String eta) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票","时效（工作日）");row(s,1,"美国","0-1",55,20,eta);
            var c=parser.parse(bytes(book),"通邮.xlsx").path("channels").get(0);var first=c.path("rows").get(0);
            assertEquals(5,first.path("etaMinDays").asInt());assertEquals(8,first.path("etaMaxDays").asInt());assertEquals(eta,first.path("sourceEtaText").asText());
        }
    }

    @Test void yunexpressRecognizesTitleCodeAliasesButStillRejectsConflicts() throws Exception {
        var existing=new YunexpressAdditionalTest();
        for(var label:List.of("产品代码","产品编号","产品号"))for(boolean adjacent:List.of(false,true))try(var book=new XSSFWorkbook(new ByteArrayInputStream(existing.fixture(98,.05,"BKZXR","1",false)))) {
            var s=book.getSheetAt(0);s.getRow(2).getCell(1).setCellValue(adjacent?label:label+"：BKZXR\n生效日期：2026-10-04");
            if(adjacent)s.getRow(2).createCell(2).setCellValue("BKZXR");
            var c=parser.parse(bytes(book),"云途.xlsx",existing.directory()).path("channels").get(0);
            assertTrue(c.path("pricingReady").asBoolean(),c.path("issues").toString());assertEquals(98,c.path("rows").get(0).path("pricePerKg").asInt());
            row(s,3,"产品代码：WRONG");assertFalse(parser.parse(bytes(book),"云途.xlsx",existing.directory()).path("channels").get(0).path("pricingReady").asBoolean());
        }
    }

    @Test void piecePricesUseTheSameEtaAndNotesProtection() throws Exception {
        var existing=new QiaojieSourceParserTest();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(existing.pieceFixture(.5,91,1,true)))) {
            var s=book.getSheetAt(0);s.getRow(6).getCell(6).setCellValue("2026-10-04");
            row(s,8,"价格使用说明");row(s,9,0,999,"US","美国",.5,1.5,"7-15天");
            var c=parser.parse(bytes(book),"巧捷.xlsx",existing.directory()).path("channels").get(0);
            assertEquals(2,c.path("rows").size());assertEquals(91,c.path("rows").get(0).path("intervalPrice").asInt());
            assertEquals(0,c.path("rows").get(0).path("etaMinDays").asInt());assertFalse(c.path("etaReady").asBoolean());assertTrue(c.path("pricingReady").asBoolean(),c.path("issues").toString());
        }
    }

    @Test void matrixPricesStopBeforeReferencePostcodes() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("美国专线小包");row(s,0,"重量","美国专线特敏感B","");
            row(s,1,"重量(KG)","运费(RMB/KG)","处理费(RMB/票)");row(s,2,"0-0.1",91,24);
            row(s,3,"价格使用说明");row(s,4,"22000-22999",99,99);
            var c=parser.parse(bytes(book),"通邮.xlsx").path("channels").get(0);assertEquals(1,c.path("rows").size());assertEquals(91,c.path("rows").get(0).path("pricePerKg").asInt());
        }
    }

    @Test void sunyouKeepsReferenceRowsOutOfConsecutiveWeightTiers() throws Exception {
        var existing=new SunyouSourceTest();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(existing.fixture(".226",false,false,"")))) {
            var s=book.getSheetAt(0);row(s,8,"国家维度具体要求");row(s,9,"US","美国",99,99,22000,"邮编说明");
            row(s,11,"代码","中文","单价(元/KG)","处理费(元/件)","限重(KG)");row(s,12,"CA","加拿大",80,20,.5);
            var c=parser.parse(bytes(book),"顺友.xlsx",existing.directory()).path("channels").get(0);
            assertEquals(3,c.path("rows").size());assertTrue(c.path("pricingReady").asBoolean(),c.path("issues").toString());
            assertEquals(.5,c.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals("CA")).findFirst().orElseThrow().path("weightToKg").asDouble());
        }
    }

    @Test void aPreviouslyReviewedEtaCannotHideExplicitlyInvalidNewSourceText() {
        var previous=mapper.createObjectNode();previous.putArray("rows").addObject().put("countryCode","US").put("areaName","美国")
                .put("etaMinDays",7).put("etaMaxDays",15).put("etaSource","manual-review");
        var incoming=mapper.createObjectNode();var r=incoming.putArray("rows").addObject().put("countryCode","US").put("areaName","美国")
                .put("sourceEtaStatus","invalid").put("sourceEtaText","24-48小时");
        LogisticsImportService.inheritManualEta(incoming,previous);assertFalse(r.has("etaMinDays"));
        r.put("etaMinDays",2).put("etaMaxDays",3).put("etaSource","manual-review").put("pricePerKg",55).put("pricingModel","per-kg");
        LogisticsReadiness.apply(incoming);assertTrue(incoming.path("etaReady").asBoolean());assertEquals("24-48小时",r.path("sourceEtaText").asText());
    }
}
