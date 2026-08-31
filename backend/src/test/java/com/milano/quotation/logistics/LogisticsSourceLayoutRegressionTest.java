package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSourceLayoutRegressionTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    @Test void mergedZonesSurviveDifferentTierCountsAndFontColors() throws Exception {
        for(int tiers:List.of(2,5,9))for(short color:List.of(IndexedColors.RED.getIndex(),IndexedColors.BLACK.getIndex(),IndexedColors.WHITE.getIndex())) {
            try(var book=new XSSFWorkbook()) {
                var s=book.createSheet("专线挂号普货");row(s,4,"国家","产品名称","重量段","运费/KG","挂号费/票","备注");
                var style=book.createCellStyle();var font=book.createFont();font.setColor(color);font.setBold(true);style.setFont(font);
                for(int zone=1;zone<=4;zone++) {
                    int start=5+(zone-1)*tiers;
                    for(int t=0;t<tiers;t++)row(s,start+t,t==0&&zone==1?"澳大利亚":"",t==0&&zone==1?"专线挂号普货":"",t+"<W≤"+(t+1),40+zone,20+zone,t==0?"50G起重\n"+zone+"区报价":"");
                    s.addMergedRegion(new CellRangeAddress(start,start+tiers-1,5,5));s.getRow(start).getCell(5).setCellStyle(style);
                }
                s.addMergedRegion(new CellRangeAddress(5,5+4*tiers-1,0,0));s.addMergedRegion(new CellRangeAddress(5,5+4*tiers-1,1,1));
                var c=only(parser.parse(bytes(book),"万邦.xlsx"));assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertEquals(4*tiers,c.path("rows").size());
                for(var r:c.path("rows")){assertEquals("AU",r.path("countryCode").asText());assertEquals(0.05,r.path("minChargeWeightKg").asDouble());assertEquals(0,r.path("billingStepKg").asDouble());assertTrue(r.path("zoneName").asText().matches("[1-4]区"));}
            }
        }
    }

    @Test void distinguishesExamplesRedeliveryFeesAndFollowingMainTable() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("纯电池线路");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","0-1",60,20);
            row(s,3,"重量段","试算重量（KG）","试算运费（元）");row(s,4,"0.5-1",0.6,56);
            row(s,6,"澳大利亚重派费用表");row(s,7,"重量","运费");
            row(s,8,"≤500g",35);row(s,9,"501g-1kg",45);row(s,10,"1.01-2kg",50);
            row(s,12,"国家","重量段","运费/KG","挂号费/票");row(s,13,"英国","0-1",50,10);
            var result=parser.parse(bytes(book),"通邮.xlsx");var c=only(result);
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertEquals(2,c.path("rows").size());
            assertEquals(3,result.path("sheets").get(0).path("conditionalPriceRows").size());assertTrue(result.path("sheets").get(0).path("exampleRows").toString().contains("5"));
            assertTrue(c.path("rows").get(0).path("notes").asText().contains("澳大利亚重派费用表"));
        }
    }

    @Test void expandsHorizontalXlsZonePairsWithVariableRowCounts() throws Exception {
        for(int tiers:List.of(2,8))try(var book=new HSSFWorkbook()) {
            var s=book.createSheet("标准定制特货");row(s,3,"澳大利亚","重量区间","一区","","二区","","三区","","四区","");
            row(s,4,"","","运费（CNY/KG）","挂号费","运费（CNY/KG）","挂号费","运费（CNY/KG）","挂号费","运费（CNY/KG）","挂号费");
            s.addMergedRegion(new CellRangeAddress(3,4+tiers,0,0));s.addMergedRegion(new CellRangeAddress(3,4,1,1));
            for(int col=2;col<10;col+=2)s.addMergedRegion(new CellRangeAddress(3,3,col,col+1));
            for(int i=0;i<tiers;i++)row(s,5+i,"",i+"<W≤"+(i+1),40,20,41,30,42,40,43,50);
            var c=only(parser.parse(bytes(book),"极通环球.xls"));assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertEquals(4*tiers,c.path("rows").size());
            for(var r:c.path("rows"))assertEquals(39+Integer.parseInt(r.path("zoneName").asText().substring(0,1)),r.path("pricePerKg").asInt());
        }
    }

    @Test void readsEmbeddedCanadianZonesAndSideColumnAustralianZones() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("通邮专线普货A");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"加拿大","1区（0-10KG)",61,12);row(s,2,"加拿大","2区（0-10KG)",61,24);
            var c=only(parser.parse(bytes(book),"通邮.xlsx"));assertEquals(0,c.path("errors").asInt());assertEquals(2,c.path("rows").size());assertEquals("2区",c.path("rows").get(1).path("zoneName").asText());
        }
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("全球专线普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"澳大利亚","0-20",55,30,"澳大利亚邮编","1区.2区");row(s,2,"澳大利亚偏远地区","0-20",65,50,"澳大利亚偏远邮编","3区.4区");
            var c=only(parser.parse(bytes(book),"云速递.xlsx"));assertEquals(0,c.path("errors").asInt());assertEquals("1区/2区",c.path("rows").get(0).path("zoneName").asText());
        }
    }

    @Test void scopesConfirmedWeightCorrectionsByProviderCountryChannelAndNeighbourNotRowNumber() throws Exception {
        for(String name:List.of("全球专线带电","全球专线敏感","不适用的渠道"))try(var book=new XSSFWorkbook()) {
            var s=book.createSheet(name);row(s,8,"国家","重量段","运费/KG","挂号费/票");
            boolean sensitive=name.equals("全球专线敏感");row(s,10,"美国",sensitive?"0.2<W≤0.3":"2<W≤5",82,12);row(s,15,"美国",sensitive?"0.2<W≤0.45":"2<W≤30",82,12);
            var c=only(parser.parse(bytes(book),"云速递.xlsx"));
            if(name.equals("不适用的渠道")){assertTrue(c.path("errors").asInt()>0);continue;}
            assertEquals(0,c.path("errors").asInt());var next=c.path("rows").get(1);assertEquals(sensitive?0.3:5,next.path("weightFromKg").asDouble());assertFalse(next.path("weightFromInclusive").asBoolean());assertTrue(next.has("normalizationNote"));assertEquals(0,next.path("billingStepKg").asDouble());
        }
    }

    @Test void splitsSfCountryCodeUsesDiscountedFirstPriceAndPreservesSuspension() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("国际电商专递-CD");row(s,0,"国家名称","Code","运费/kg","结算运费","专递操作费/票","计费重量限制（kg）","备注");
            row(s,1,"澳大利亚","AU-1",40,29.6,20,"0.001-1KG","");row(s,2,"澳大利亚","AU-2",40,29.6,30,"0.001-1KG","");
            row(s,3,"新西兰","NZ",77,70.84,30.5,"1,001-5KG","");
            row(s,5,"国家名称","Code","首重0.1kg","结算运费","续重0.1kg","计费重量限制（kg）","备注");
            row(s,6,"印尼","ID-1",20,18.4,12,"<=30KG(首重0.1KG,续重0.1KG)","暂时关停");
            var c=only(parser.parse(bytes(book),"顺丰.xlsx"));assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
            var au=find(c,"AU",1);assertEquals("1区",au.path("zoneName").asText());
            var nz=find(c,"NZ",5);assertEquals(1.001,nz.path("weightFromKg").asDouble());assertTrue(nz.path("sourceWeightRange").asText().contains(","));
            var id=find(c,"ID",30);assertEquals(18.4,id.path("firstWeightPrice").asDouble());assertEquals(12,id.path("nextWeightPrice").asDouble());assertEquals(0.1,id.path("firstWeightKg").asDouble());assertEquals(0,id.path("pricePerKg").asDouble());assertTrue(id.path("pendingReason").asText().contains("暂停"));assertFalse(id.path("quoteReady").asBoolean());
        }
    }

    @Test void sortedRowsAndTierSplitsDoNotCompareUnrelatedPrices() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("普货");row(s,0,"国家","重量段","运费/KG","挂号费/票");row(s,1,"美国","1<W≤2",60,20);row(s,2,"美国","0<W≤1",50,20);
            var original=only(parser.parse(bytes(book),"通邮.xlsx"));assertEquals(0,original.path("errors").asInt());
            row(s,1,"美国","0<W≤1",50,20);row(s,2,"美国","1<W≤2",60,20);
            assertEquals(original.path("contentHash"),only(parser.parse(bytes(book),"通邮.xlsx")).path("contentHash"));
            row(s,2,"美国","1<W≤1.5",55,20);row(s,3,"美国","1.5<W≤2",60,20);
            var next=only(parser.parse(bytes(book),"通邮.xlsx"));var diff=new LogisticsWorkbookService(mapper).compare((ArrayNode)next.path("rows"),(ArrayNode)original.path("rows"));
            assertEquals(2,diff.path("summary").path("added").asInt());assertEquals(1,diff.path("summary").path("removed").asInt());assertEquals(0,diff.path("summary").path("price").asInt());
        }
    }

    @Test void convertsMixedUnitsWithoutGuessingMalformedBounds(){
        assertEquals(0.501,LogisticsSourceParser.parseRange("501g - 1kg").from());assertEquals(1,LogisticsSourceParser.parseRange("501g - 1kg").to());assertEquals(0.5,LogisticsSourceParser.parseRange("≤500g").to());
        assertThrows(IllegalArgumentException.class,()->LogisticsSourceParser.parseRange("1,001-5KG"));
    }

    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    @EnabledIfSystemProperty(named="logistics.templateFile",matches=".+")
    void authoredProviderTemplateRoundTripsEveryBusinessField() throws Exception {
        var expected=new HashMap<String,JsonNode>();var root=Path.of(System.getProperty("logistics.corpusDir"));
        try(var paths=Files.list(root)) {
            for(var path:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList())
                for(var c:parser.parse(Files.readAllBytes(path),path.getFileName().toString()).path("channels"))expected.put(LogisticsSourceParser.identity(c),c);
        }
        var result=parser.parse(Files.readAllBytes(Path.of(System.getProperty("logistics.templateFile"))),"多商标准模板.xlsx");
        assertEquals(88,expected.size());assertEquals(expected.size(),result.path("channels").size());int rows=0;
        for(var c:result.path("channels")) {
            var before=expected.get(LogisticsSourceParser.identity(c));assertNotNull(before,c.path("channelName").asText());
            assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertFalse(c.path("quoteReady").asBoolean());
            assertEquals(before.path("rows").size(),c.path("rows").size());assertEquals(before.path("contentHash"),c.path("contentHash"),c.path("channelName").asText());rows+=c.path("rows").size();
        }
        assertEquals(3094,rows);
    }

    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void everyProviderAcceptsAnInsertedWeightTierInsideItsRealMergedTable() throws Exception {
        var root=Path.of(System.getProperty("logistics.corpusDir"));
        try(var paths=Files.list(root)) {
            for(var path:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList()) {
                String name=path.getFileName().toString();byte[] original=Files.readAllBytes(path);var before=parser.parse(original,name);
                JsonNode chosen=null;for(var c:before.path("channels"))for(var r:c.path("rows"))if(chosen==null&&r.path("pricingModel").asText().equals("per-kg")
                        &&r.path("zoneName").asText().isBlank()&&r.path("sourceCountry").asText().equals("美国")&&!r.has("normalizationNote")
                        &&r.path("weightToKg").asDouble()-r.path("weightFromKg").asDouble()>=0.04)chosen=r;
                // Rongding has an explicit USA destination without a country column; parser records it.
                assertNotNull(chosen,name+" requires a representative weight tier");
                try(var book=WorkbookFactory.create(new java.io.ByteArrayInputStream(original))) {
                    var s=book.getSheet(chosen.path("sourceSheet").asText());int at=chosen.path("sourceRow").asInt()-1;
                    var fmt=new DataFormatter(Locale.ROOT);fmt.setUseCachedValuesForFormulaCells(true);int weightCol=-1;
                    for(var cell:s.getRow(at))if(fmt.formatCellValue(cell).trim().equals(chosen.path("sourceWeightRange").asText()))weightCol=cell.getColumnIndex();
                    boolean separate=name.contains("花海");if(!separate)assertTrue(weightCol>=0,name+" weight column");
                    var ranges=new ArrayList<CellRangeAddress>();for(var range:s.getMergedRegions())ranges.add(range.copy());
                    for(int i=s.getNumMergedRegions()-1;i>=0;i--)s.removeMergedRegion(i);
                    s.shiftRows(at,s.getLastRowNum(),1);var moved=s.getRow(at+1);var added=s.createRow(at);
                    for(var cell:moved){var copy=added.createCell(cell.getColumnIndex());switch(cell.getCellType()){
                        case NUMERIC -> copy.setCellValue(cell.getNumericCellValue());case STRING -> copy.setCellValue(cell.getStringCellValue());
                        case FORMULA -> {assertEquals(CellType.NUMERIC,cell.getCachedFormulaResultType());copy.setCellValue(cell.getNumericCellValue());}
                        case BOOLEAN -> copy.setCellValue(cell.getBooleanCellValue());default -> {} }
                    }
                    for(var range:ranges){if(range.getFirstRow()>at){range.setFirstRow(range.getFirstRow()+1);range.setLastRow(range.getLastRow()+1);}else if(range.getLastRow()>=at)range.setLastRow(range.getLastRow()+1);s.addMergedRegion(range);}
                    double from=chosen.path("weightFromKg").asDouble(),to=chosen.path("weightToKg").asDouble(),mid=Math.round((from+to)/2*1000)/1000.0;
                    if(separate){added.getCell(4).setCellValue(from);added.getCell(5).setCellValue(mid);moved.getCell(4).setCellValue(mid+0.001);moved.getCell(5).setCellValue(to);}
                    else {added.getCell(weightCol).setCellValue(from+"<W≤"+mid);moved.getCell(weightCol).setCellValue(mid+"<W≤"+to);}
                    var after=parser.parse(bytes(book),name);int firstCount=0,nextCount=0;for(var c:before.path("channels"))firstCount+=c.path("rows").size();
                    for(var c:after.path("channels")){assertEquals(0,c.path("errors").asInt(),name+c.path("issues"));nextCount+=c.path("rows").size();}
                    assertEquals(before.path("channels").size(),after.path("channels").size(),name);assertEquals(firstCount+1,nextCount,name+" new tier must not be dropped");
                }
                assertArrayEquals(original,Files.readAllBytes(path));
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void allRealWorkbooksRetainPricesWhenHeadersAndMergedBlocksMove() throws Exception {
        var expected=Map.ofEntries(Map.entry("4px价格.xlsx",208),Map.entry("7.30花海.xlsx",263),Map.entry("8.12容鼎.xlsx",30),Map.entry("8.17万邦价格.xlsx",249),Map.entry("8.17通邮价格.xlsx",388),Map.entry("8.1云速递价格.xlsx",264),Map.entry("8.24云途价格.xlsx",633),Map.entry("8.24极通环球价格.xls",193),Map.entry("8.24递四方价格.xlsx",208),Map.entry("8.27燕文价格.xlsx",626),Map.entry("8.7顺丰价格.xlsx",240));
        var root=Path.of(System.getProperty("logistics.corpusDir"));
        for(var entry:expected.entrySet()) {
            var path=root.resolve(entry.getKey());var originalBytes=Files.readAllBytes(path);var before=parser.parse(originalBytes,entry.getKey());
            int count=0;for(var c:before.path("channels")){assertEquals(0,c.path("errors").asInt(),entry.getKey()+c.path("issues"));count+=c.path("rows").size();assertFalse(c.path("quoteReady").asBoolean(),entry.getKey()+c.path("channelName"));}
            assertEquals(entry.getValue(),count,entry.getKey());
            try(var book=WorkbookFactory.create(new java.io.ByteArrayInputStream(originalBytes))) {
                for(var s:book)if(s.getLastRowNum()>0)s.shiftRows(0,s.getLastRowNum(),12);
                var after=parser.parse(bytes(book),entry.getKey());assertEquals(before.path("channels").size(),after.path("channels").size(),entry.getKey());
                for(var c:before.path("channels")) {
                    JsonNode match=null;for(var other:after.path("channels"))if(c.path("channelName").equals(other.path("channelName")))match=other;
                    assertNotNull(match,entry.getKey()+c.path("channelName"));assertEquals(0,match.path("errors").asInt(),entry.getKey()+match.path("issues"));assertEquals(c.path("contentHash"),match.path("contentHash"),entry.getKey()+c.path("channelName"));
                }
            }
            assertArrayEquals(originalBytes,Files.readAllBytes(path),"源文件必须保持不变");
        }
    }
    private JsonNode only(JsonNode result){assertEquals(1,result.path("channels").size(),result.path("channels").toString());return result.path("channels").get(0);}
    private JsonNode find(JsonNode c,String country,double to){for(var row:c.path("rows"))if(row.path("countryCode").asText().equals(country)&&row.path("weightToKg").asDouble()==to)return row;fail(country);return null;}
}
