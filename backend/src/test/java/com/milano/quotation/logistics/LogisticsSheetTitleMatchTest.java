package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSheetTitleMatchTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    ObjectNode scope(String provider,String... names) {
        var scope=mapper.createObjectNode().put("enabled",true);var entries=scope.putArray("entries");
        for(int i=0;i<names.length;i++)entries.addObject().put("id","channel-"+i).put("providerName",provider)
                .put("channelName",names[i]).put("enabled",true).put("logisticsAttribute","普货");
        return scope;
    }
    void row(Sheet s,int r,Object... values) {
        var row=s.createRow(r);for(int c=0;c<values.length;c++) {
            var cell=row.createCell(c);if(values[c] instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(values[c].toString());
        }
    }
    byte[] bytes(Workbook w)throws Exception {try(var out=new ByteArrayOutputStream()){w.write(out);return out.toByteArray();}}
    void table(Sheet s,String title) {
        row(s,0,"","","",title);s.addMergedRegion(new CellRangeAddress(0,0,3,8));
        row(s,2,"国家","重量段","运费/KG","处理费/票");row(s,3,"德国","0-0.5",45,28);
    }
    @Test void matchesTongyouMergedSlashTitleAndPreservesPriceEvidence()throws Exception {
        try(var w=new XSSFWorkbook()) {
            table(w.createSheet("线上标准普货"),"通邮专线普货A/ebay专线普货/亚马逊专线普货");
            var parsed=parser.parse(bytes(w),"通邮.xlsx",new CompanyChannelScope(scope("通邮","通邮专线普货A")));
            assertEquals(1,parsed.path("channels").size());var channel=parsed.path("channels").get(0);
            assertEquals("通邮专线普货A",channel.path("channelName").asText());
            assertEquals("channel-0",channel.path("companyChannelId").asText());
            assertEquals(45,channel.path("rows").get(0).path("pricePerKg").asDouble());
            assertEquals(28,channel.path("rows").get(0).path("registrationFee").asDouble());
            var match=parsed.path("sheets").get(0).path("channelMatches").get(0);
            assertEquals("header-title",match.path("matchMethod").asText());
            assertEquals("D1",match.path("titleEvidence").get(0).path("cell").asText());
        }
    }
    @Test void worksForOtherProvidersLegacyXlsAndRegisteredAliases()throws Exception {
        try(var w=new HSSFWorkbook()) {
            table(w.createSheet("电商价格"),"渠道名称：航空普货\n线上普货");
            var scope=scope("甲物流","标准普货");((ObjectNode)scope.path("entries").get(0)).putArray("aliases").add("航空普货").add("线上普货");
            var parsed=parser.parse(bytes(w),"甲物流.xls",new CompanyChannelScope(scope));
            assertEquals(1,parsed.path("channels").size());assertEquals("标准普货",parsed.path("channels").get(0).path("channelName").asText());
        }
    }
    @Test void rejectsAmbiguousTitlesBeforeParsingPrices()throws Exception {
        try(var w=new XSSFWorkbook()) {
            table(w.createSheet("线上价格"),"普货专线/带电专线");
            var parsed=parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线","带电专线")));
            assertEquals(0,parsed.path("channels").size());assertEquals(0,parsed.path("priceCellsParsed").asInt());
            assertEquals("match-pending",parsed.path("sheets").get(0).path("status").asText());
        }
    }
    @Test void doesNotGuessSimilarNamesCrossProvidersDisabledChannelsOrFooterMentions()throws Exception {
        for(String title:new String[]{"普货专线AB","推荐使用普货专线A","带电专线"})try(var w=new XSSFWorkbook()) {
            var sheet=w.createSheet("未登记");table(sheet,title);row(sheet,8,"普货专线A");
            var parsed=parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线A")));
            assertEquals(0,parsed.path("channels").size(),title);
        }
        try(var w=new XSSFWorkbook()) {
            table(w.createSheet("未登记"),"普货专线A");var scope=scope("甲物流","普货专线A");
            ((ObjectNode)scope.path("entries").get(0)).put("enabled",false);
            assertEquals(0,parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope)).path("channels").size());
            assertEquals(0,parser.parse(bytes(w),"乙物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线A"))).path("channels").size());
        }
    }
    @Test void prioritizesBodyTitleOverSheetAndPreservesExplicitRowIdentityAndCodeConflicts()throws Exception {
        try(var w=new XSSFWorkbook()) {
            table(w.createSheet("带电专线"),"普货专线");
            var parsed=parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线","带电专线")));
            assertEquals("普货专线",parsed.path("channels").get(0).path("channelName").asText());
        }
        try(var w=new XSSFWorkbook()) {
            var sheet=w.createSheet("线上价格");table(sheet,"普货专线");
            row(sheet,2,"国家","重量段","运费/KG","处理费/票","渠道名称");row(sheet,3,"德国","0-0.5",45,28,"未登记渠道");
            assertEquals(0,parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线"))).path("channels").size());
            row(sheet,2,"国家","重量段","运费/KG","处理费/票","产品代码");row(sheet,3,"德国","0-0.5",45,28,"POWER");
            var scope=scope("甲物流","普货专线","带电专线");((ObjectNode)scope.path("entries").get(1)).putArray("productCodes").add("POWER");
            var parsed=parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope));
            assertEquals(0,parsed.path("channels").size());assertEquals("match-pending",parsed.path("sheets").get(0).path("status").asText());
        }
    }
    @Test void doesNotTreatServiceTablesAsChannelTitles()throws Exception {
        try(var w=new XSSFWorkbook()) {
            var sheet=w.createSheet("尺寸表");row(sheet,0,"普货专线","带电专线");row(sheet,1,"限长","限宽");row(sheet,2,60,30);
            var parsed=parser.parse(bytes(w),"甲物流.xlsx",new CompanyChannelScope(scope("甲物流","普货专线","带电专线")));
            assertEquals(0,parsed.path("channels").size());assertEquals(0,parsed.path("ambiguousChannels").asInt());
        }
    }
    @Test @EnabledIfSystemProperty(named="logistics.titleSource",matches=".+")
    void parsesRealWorkbookWithProductionDirectory()throws Exception {
        var file=Path.of(System.getProperty("logistics.titleSource"));
        var scope=new CompanyChannelScope(mapper.readTree(Files.readString(Path.of(System.getProperty("logistics.titleScope")))));
        var result=parser.parse(Files.readAllBytes(file),file.getFileName().toString(),scope);
        Files.createDirectories(Path.of("target/title-scan"));
        Files.writeString(Path.of("target/title-scan/tongyou.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        JsonNode found=null;for(var channel:result.path("channels"))if(channel.path("channelName").asText().equals("通邮专线普货A"))found=channel;
        assertNotNull(found);assertTrue(found.path("rows").size()>0);assertEquals(0,found.path("errors").asInt(),found.path("issues").toString());
        assertTrue(found.path("rows").valueStream().anyMatch(r->r.path("countryCode").asText().equals("DE")&&r.path("pricePerKg").asDouble()==45&&r.path("registrationFee").asDouble()==28));
    }
    @Test @EnabledIfSystemProperty(named="logistics.titleCorpus",matches=".+")
    void scansOtherProviderWorkbooksWithProductionDirectory()throws Exception {
        var scope=new CompanyChannelScope(mapper.readTree(Files.readString(Path.of(System.getProperty("logistics.titleScope")))));
        var reports=mapper.createArrayNode();int scanned=0;
        for(var directory:System.getProperty("logistics.titleCorpus").split(";"))try(var files=Files.list(Path.of(directory))) {
            for(var file:files.filter(Files::isRegularFile).filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList()) {
                var result=parser.parse(Files.readAllBytes(file),file.getFileName().toString(),scope);scanned++;
                var report=reports.addObject().put("file",file.toString()).put("channelCount",result.path("channels").size());
                var matches=report.putArray("titleMatches");
                for(var sheet:result.path("sheets"))for(var match:sheet.path("channelMatches"))if(match.has("matchMethod")) {
                    var evidence=match.deepCopy();((ObjectNode)evidence).put("sheet",sheet.path("name").asText());matches.add(evidence);
                }
                var counts=report.putArray("channels");for(var channel:result.path("channels"))counts.addObject()
                        .put("name",channel.path("channelName").asText()).put("rows",channel.path("rows").size())
                        .put("errors",channel.path("errors").asInt()).put("ready",channel.path("quoteReady").asBoolean());
            }
        }
        Files.createDirectories(Path.of("target/title-scan"));
        Files.writeString(Path.of("target/title-scan/corpus.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
        assertTrue(scanned>0);
    }
}
