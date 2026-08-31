package com.milano.quotation.logistics;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSourceParserTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsWorkbookService standard=new LogisticsWorkbookService(mapper);
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,standard);

    @Test void preservesStrictBoundsWithoutInventingBillingRounding(){
        var range=LogisticsSourceParser.parseRange("0.2＜W<0.5KG");
        assertEquals(0.2,range.from());assertEquals(0.5,range.to());assertFalse(range.includeFrom());assertFalse(range.includeTo());
        assertTrue(LogisticsSourceParser.parseRange("0.201-0.5KG").includeFrom());
        assertEquals(10,LogisticsSourceParser.parseRange("<=10KG").to());assertFalse(LogisticsSourceParser.parseRange("<0.5KG").includeTo());
        assertThrows(IllegalArgumentException.class,()->LogisticsSourceParser.parseRange("小于等于某重量"));
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
    @Test void usesSfSettlementOnceAndBlocksUnreliableCriticalFormula()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("服装专线");row(s,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");
            row(s,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            var parsed=parser.parse(bytes(book),"顺丰.xlsx");assertEquals(45.44,parsed.path("channels").get(0).path("rows").get(0).path("pricePerKg").asDouble());
            s.getRow(1).getCell(4).setCellFormula("1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            parsed=parser.parse(bytes(book),"顺丰.xlsx");assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0);
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
        assertEquals(3,diff.path("changes").size());assertNotEquals("unchanged",diff.path("type").asText());
    }
    @Test void shippedTemplateIsImportableAndExamplesCannotQuote()throws Exception {
        var parsed=parser.parse(Files.readAllBytes(Path.of("../public/templates/logistics-v2.xlsx")),"标准模板.xlsx");
        assertEquals("metadata",parsed.path("sheets").get(0).path("status").asText());
        assertEquals(3,parsed.path("channels").size());int rows=0;
        for(var channel:parsed.path("channels")){assertEquals(0,channel.path("errors").asInt(),channel.toString());assertFalse(channel.path("quoteReady").asBoolean());rows+=channel.path("rows").size();}
        assertEquals(4,rows);
    }
    @Test void changedGlobalFeeClauseChangesBusinessFingerprint()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","0-1",55,20);
            row(s,2,"附加费规则：偏远地区每票附加操作费用人民币20元，需要在报价时另行核对。");
            var first=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            s.getRow(2).getCell(0).setCellValue("附加费规则：偏远地区每票附加操作费用人民币30元，需要在报价时另行核对。");
            var second=parser.parse(bytes(book),"花海.xlsx").path("channels").get(0);
            assertNotEquals(first.path("contentHash"),second.path("contentHash"));
            assertEquals("rule",standard.compare((tools.jackson.databind.node.ArrayNode)second.path("rows"),(tools.jackson.databind.node.ArrayNode)first.path("rows")).path("diffRows").get(0).path("type").asText());
        }
    }
    @Test void keepsSecondaryRedeliveryFeeTableAsPendingEvidenceNotBaseFreight()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","<=1KG",55,20);
            row(s,3,"重量（KG）","重派费（CNY）");row(s,4,"0-0.5KG",30);
            var result=parser.parse(bytes(book),"顺丰.xlsx");var c=result.path("channels").get(0);
            assertEquals(1,c.path("rows").size());assertEquals(0,c.path("errors").asInt());assertFalse(c.path("quoteReady").asBoolean());
            assertEquals(1,result.path("sheets").get(0).path("conditionalPriceRows").size());assertTrue(c.path("rows").get(0).path("notes").asText().contains("30"));
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
    private void assertSample(JsonNode book,String channel,String country,double weight,double price,double fee){
        var rows=new ArrayList<JsonNode>();for(var c:book.path("channels"))if(c.path("channelName").asText().equals(channel))for(var r:c.path("rows"))if(r.path("countryCode").asText().equals(country)&&r.path("zoneName").asText().isBlank()&&(weight>r.path("weightFromKg").asDouble()||(weight==r.path("weightFromKg").asDouble()&&r.path("weightFromInclusive").asBoolean()))&&(weight<r.path("weightToKg").asDouble()||(weight==r.path("weightToKg").asDouble()&&r.path("weightToInclusive").asBoolean())))rows.add(r);
        assertEquals(1,rows.size(),channel+" sample must be unique");assertEquals(price,rows.getFirst().path("pricePerKg").asDouble());assertEquals(fee,rows.getFirst().path("registrationFee").asDouble());
    }
    static byte[] bytes(Workbook book)throws Exception{var out=new ByteArrayOutputStream();book.write(out);return out.toByteArray();}
    static void row(Sheet s,int number,Object...values){var r=s.createRow(number);for(int i=0;i<values.length;i++){var c=r.createCell(i);if(values[i] instanceof Number n)c.setCellValue(n.doubleValue());else c.setCellValue(String.valueOf(values[i]));}}
}
