package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RongdingProductCellTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    static final String[] CODES={"PK00186","PK00243","PK00187","PK00270","PK00251"};
    static final String[] OTHER={"PK00208","PK00244","PK00209","PK00296","PK00309"};
    static final String[] NAMES={"普货","特货","敏货","化妆品","特敏货"};
    static final String[] RANGES={"0.05-0.1","0.1-0.2","0.201-0.45","0.451-0.7","0.701-2","2.001-5"};
    static final int[][] PRICES={{71,71,77,81,81,81},{77,77,81,84,84,84},{91,91,97,99,99,98},{81,81,86,87,87,87},{114,114,116,118,118,117}};
    static final int[] FEES={20,20,18,16,12,12};
    ObjectNode snapshot() {
        var s=mapper.createObjectNode().put("enabled",true);var entries=s.putArray("entries");
        for(int i=0;i<CODES.length;i++)entries.addObject().put("id",CODES[i]).put("providerName","容鼎")
                .put("channelName","旧渠道"+NAMES[i]).put("enabled",true).putArray("productCodes").add(CODES[i]);
        return s;
    }
    String name(int i){return "美国标准DDP专线-"+NAMES[i]+"A(SP)\n美国标准DDP专线-"+NAMES[i]+"C(SP)";}
    String code(int i){return CODES[i]+"(A)\n"+OTHER[i]+"(C)";}
    @Test void uniqueRegisteredCodeMatchesWithoutAddingTheOtherProduct() {
        var scope=new CompanyChannelScope(snapshot());
        for(int i=0;i<CODES.length;i++)assertEquals(CODES[i],scope.matchSourceCell("容鼎",name(i),code(i)).entry().path("id").asText());
        assertTrue(scope.matchSourceCell("容鼎","旧渠道普货","PK00186").accepted());
        assertEquals("ambiguous",scope.matchSourceCell("容鼎","旧渠道普货","PK001860").status());
        assertFalse(scope.matchSourceCell("其他物流",name(0),code(0)).accepted());
        assertFalse(scope.matchSourceCell("容鼎",name(0),"PK001860(A)\nPK002080(C)").accepted());
    }
    @ParameterizedTest @ValueSource(strings={"PK00186(B)\nPK00208(C)","PK00186(A\nPK00208(C)","PK00186(A) extra\nPK00208(C)","PK00186(A)\nPK00208(A)","PK00186(A)\nPK00186(C)","PK00186(A)\n\nPK00208(C)","PK00186(A)/PK00208(C)","PK00186(A)\nPK00208(C)\nPK00300(C)"})
    void malformedCellsNeverFallBackToNames(String invalid) {
        var s=snapshot();((ObjectNode)s.path("entries").get(0)).putArray("aliases").add(name(0));
        assertEquals("ambiguous",new CompanyChannelScope(s).matchSourceCell("容鼎",name(0),invalid).status());
    }
    @Test void conflictsAndDisabledEntriesCannotBeSilentlyChosen() {
        var s=snapshot();var a=(ObjectNode)s.path("entries").get(0);a.put("enabled",false);
        assertEquals("filtered",new CompanyChannelScope(s).matchSourceCell("容鼎",name(0),code(0)).status());
        s.withArray("entries").addObject().put("id","other").put("providerName","容鼎").put("channelName","另一个渠道")
                .put("enabled",true).putArray("productCodes").add("PK00208");
        assertEquals("ambiguous",new CompanyChannelScope(s).matchSourceCell("容鼎",name(0),code(0)).status());
        s=snapshot();((ObjectNode)s.path("entries").get(1)).putArray("aliases").add(name(0).split("\n")[1]);
        assertEquals("ambiguous",new CompanyChannelScope(s).matchSourceCell("容鼎",name(0),code(0)).status());
        assertEquals("ambiguous",new CompanyChannelScope(snapshot()).matchSourceCell("容鼎",name(0),"PK00208(C)\nPK00186(A)").status());
        assertEquals("ambiguous",new CompanyChannelScope(snapshot()).matchSourceCell("容鼎",name(0).replace("普货C","特货C"),code(0)).status());
    }
    byte[] fixture()throws Exception {
        try(var book=new XSSFWorkbook()) {
            for(int i=0;i<CODES.length;i++) {
                var sheet=book.createSheet("容鼎"+NAMES[i]);
                row(sheet,0,"容鼎美国"+NAMES[i]+"专线");row(sheet,1,"生效日期","2026-10-19");
                row(sheet,2,"渠道类型","渠道名称","产品代码","重量段(KG)","包裹运费(元/KG)","处理费(元/件)","最小计费重量(KG)","时效","渠道说明");
                for(int j=0;j<6;j++)row(sheet,21+j,j==0?"商派":"",j==0?name(i):"",j==0?code(i):"",RANGES[j],PRICES[i][j],FEES[j],j==0?.05:"",j==0?"8-12天":"","商派");
                for(int col:new int[]{0,1,2,6,7})sheet.addMergedRegion(new CellRangeAddress(21,26,col,col));
            }
            return bytes(book);
        }
    }
    void assertPrices(JsonNode result) {
        assertEquals(5,result.path("channels").size(),result.path("sheets").toString());
        int count=0;
        for(var channel:result.path("channels")) {
            var rows=channel.path("rows");assertEquals(6,rows.size());assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toString());
            assertTrue(channel.path("pricingReady").asBoolean(),channel.path("blockingReasons").toString());
            int index=-1;for(int i=0;i<CODES.length;i++)if(rows.get(0).path("sourceProductCode").asText().startsWith(CODES[i]+"(A)"))index=i;
            assertTrue(index>=0);final int i=index;
            for(int j=0;j<6;j++) {
                var r=rows.get(j);count++;
                assertEquals(RANGES[j],r.path("sourceWeightRange").asText());
                assertEquals(new BigDecimal(RANGES[j].split("-")[0]),r.path("weightFromKg").decimalValue());
                assertEquals(0,new BigDecimal(RANGES[j].split("-")[1]).compareTo(r.path("weightToKg").decimalValue()));
                assertEquals(PRICES[i][j],r.path("pricePerKg").asInt());assertEquals(FEES[j],r.path("registrationFee").asInt());
                assertEquals(.05,r.path("minChargeWeightKg").asDouble());assertEquals("US",r.path("countryCode").asText());
                assertEquals(8,r.path("etaMinDays").asInt());assertEquals(12,r.path("etaMaxDays").asInt());
                assertEquals(22+j,r.path("sourceRow").asInt());assertEquals(code(i),r.path("sourceCode").asText());
                assertEquals(PRICES[i][j],r.path("rawValues").path("E"+(22+j)).asInt());
            }
            var billed=new LogisticsBillingEngine(mapper).calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.5));
            assertEquals(0,BigDecimal.valueOf(PRICES[i][3]).multiply(new BigDecimal("0.5")).add(new BigDecimal("16")).compareTo(billed.path("total").decimalValue()));
        }
        assertEquals(30,count);
    }
    @Test void mergedCellsPreserveAllThirtyPriceTiersAndRawEvidence()throws Exception {
        var result=parser.parse(fixture(),"容鼎10月19日.xlsx",new CompanyChannelScope(snapshot()));assertPrices(result);
        assertEquals(result,parser.parse(fixture(),"容鼎10月19日.xlsx",new CompanyChannelScope(snapshot())));
    }
    @Test void malformedNumericPriceCannotCreateAQuoteReadyPartialChannel()throws Exception {
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))) {
            book.getSheetAt(0).getRow(24).getCell(4).setCellValue("待确认");
            var result=parser.parse(bytes(book),"容鼎.xlsx",new CompanyChannelScope(snapshot()));
            var channel=result.path("channels").valueStream().filter(c->c.path("companyChannelId").asText().equals(CODES[0])).findFirst().orElseThrow();
            assertFalse(channel.path("pricingReady").asBoolean());assertTrue(channel.path("errors").asInt()>0);
        }
    }
    @Test void missingWeightAndFeeCannotSilentlyDropTheLastTier()throws Exception {
        for(int col:new int[]{3,5})try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(fixture()))) {
            book.getSheetAt(0).getRow(26).getCell(col).setCellValue("");
            var result=parser.parse(bytes(book),"容鼎.xlsx",new CompanyChannelScope(snapshot()));
            var channel=result.path("channels").valueStream().filter(c->c.path("companyChannelId").asText().equals(CODES[0])).findFirst().orElseThrow();
            assertFalse(channel.path("pricingReady").asBoolean());assertTrue(channel.path("errors").asInt()>0);
        }
    }
    @Test @EnabledIfSystemProperty(named="rongding.workbook",matches=".+")
    void suppliedWorkbookMatchesEveryReviewedPrice()throws Exception {
        var file=Path.of(System.getProperty("rongding.workbook"));
        var scope=mapper.readTree(Files.readString(Path.of(System.getProperty("rongding.scope"))));
        var result=parser.parse(Files.readAllBytes(file),file.getFileName().toString(),new CompanyChannelScope(scope));
        var output=Path.of("target","rongding-parsed.json");Files.createDirectories(output.getParent());Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertPrices(result);assertEquals(25,result.path("filteredChannels").asInt());assertEquals(0,result.path("ambiguousChannels").asInt());
    }
}
