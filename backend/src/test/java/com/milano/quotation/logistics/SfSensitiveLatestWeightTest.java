package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SfSensitiveLatestWeightTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    XSSFWorkbook book() {
        var b=new XSSFWorkbook();var s=b.createSheet("敏货电商专递");
        LogisticsSourceParserTest.row(s,2,"","序号","Country","国家名称","Code","参考时效（工作日）","运费/kg","专递操作费/票","计费重量限制（kg）","折扣率","折扣后运费","专递操作费/票","计费重量限制（kg）");
        LogisticsSourceParserTest.row(s,16,"",4,"France","法国","FR","8-10",97,23,"0.001-0.4KG",.464,45.008,19,"0.001-0.2KG");
        LogisticsSourceParserTest.row(s,17,"",4,"France","法国","FR","8-10",97,23,"",.474,45.978,20,"0.201-0.4KG");
        s.addMergedRegion(new CellRangeAddress(16,17,8,8));
        LogisticsSourceParserTest.row(s,18,"",4,"France","法国","FR","8-10",95,23,"0.401-30KG",.495,47.025,20,"0.401-30KG");
        LogisticsSourceParserTest.row(s,57,"",11,"Poland","波兰","PL","6-8",95,15,"0.201-2KG","","","","");
        LogisticsSourceParserTest.row(s,60,"",12,"Austria","奥地利","AT","6-8",98,23,"0.501-2KG","","","","");
        LogisticsSourceParserTest.row(s,61,"",12,"Austria","奥地利","AT","6-8",98,23,"5.001-30KG","","","","");
        return b;
    }
    JsonNode parse(XSSFWorkbook b,String name)throws Exception{return parser.parse(LogisticsSourceParserTest.bytes(b),name).path("channels").get(0);}
    JsonNode row(JsonNode c,int n){return java.util.stream.StreamSupport.stream(c.path("rows").spliterator(),false).filter(v->v.path("sourceRow").asInt()==n).findFirst().orElseThrow();}
    @Test void selectsLatestWeightWithItsPriceAndFeeAndPreservesSingleGroupCountries()throws Exception {
        try(var b=book()) {
            var c=parse(b,"顺丰20261101.xlsx");assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
            assertFalse(c.path("issues").toString().contains("不连续"));
            var rows=c.path("rows");assertEquals(6,rows.size());
            assertEquals(.2,row(c,17).path("weightToKg").asDouble());assertEquals(.201,row(c,18).path("weightFromKg").asDouble());
            assertEquals("M18",row(c,18).path("sourceWeightCell").asText());assertEquals("L18",row(c,18).path("sourceFeeCell").asText());
            assertEquals(45.978,row(c,18).path("pricePerKg").asDouble());assertEquals(20,row(c,18).path("registrationFee").asDouble());
            assertEquals("I58",row(c,58).path("sourceWeightCell").asText());assertEquals(95,row(c,58).path("pricePerKg").asDouble());assertEquals(15,row(c,58).path("registrationFee").asDouble());
            assertEquals(2.001,row(c,62).path("weightFromKg").asDouble());assertEquals("5.001-30KG",row(c,62).path("sourceWeightRange").asText());
            assertTrue(row(c,62).path("normalizationNote").asText().contains("用户"));
            var engine=new LogisticsBillingEngine(mapper);
            assertEquals(317,engine.calculate(rows,mapper.createObjectNode().put("country","AT").put("weightKg",3)).path("total").asDouble());
            assertEquals(33.79,engine.calculate(rows,mapper.createObjectNode().put("country","FR").put("weightKg",.3)).path("total").asDouble());
        }
    }
    @Test void doesNotCorrectAnotherEditionOrAnotherPrice()throws Exception {
        try(var b=book()) {
            assertEquals(5.001,row(parse(b,"顺丰20261201.xlsx"),62).path("weightFromKg").asDouble());
            b.getSheetAt(0).getRow(61).getCell(6).setCellValue(99);
            assertEquals(5.001,row(parse(b,"顺丰20261101.xlsx"),62).path("weightFromKg").asDouble());
        }
    }
    @Test void missingLatestWeightOrPriceCannotFallBackToOriginal()throws Exception {
        for(int col:new int[]{10,12})try(var b=book()) {
            b.getSheetAt(0).getRow(17).getCell(col).setBlank();
            var c=parse(b,"顺丰20261101.xlsx");assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("quoteReady").asBoolean());
            assertTrue(c.path("issues").toString().contains("禁止回退"));
        }
    }
    @Test @EnabledIfSystemProperty(named="sf.latestWorkbook",matches=".+")
    void verifiesOriginalNovemberWorkbook()throws Exception {
        var file=Path.of(System.getProperty("sf.latestWorkbook"));var parsed=parser.parse(Files.readAllBytes(file),file.getFileName().toString());
        var c=java.util.stream.StreamSupport.stream(parsed.path("channels").spliterator(),false).filter(v->v.path("channelName").asText().equals("敏货电商专递")).findFirst().orElseThrow();
        assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertTrue(c.path("quoteReady").asBoolean(),c.toString());assertEquals(59,c.path("rows").size());
        for(var row:c.path("rows")) {
            var country=row.path("countryCode").asText();var n=row.path("sourceRow").asInt();
            assertEquals((java.util.Set.of("AT","PL").contains(country)?"I":"M")+n,row.path("sourceWeightCell").asText());
            if(n==62)assertEquals(2.001,row.path("weightFromKg").asDouble());
        }
        Files.writeString(Path.of("target/sf-latest-parsed.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
    }
}
