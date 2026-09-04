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
            var battery=findChannel(parsed,"纯电池线路");assertEquals(2,battery.path("rows").size(),battery.toString());assertEquals("JP",battery.path("rows").get(0).path("countryCode").asText());
            assertEquals("first-next",battery.path("rows").get(0).path("pricingModel").asText());
            var ordinary=findChannel(parsed,"JP-mini小包普货X");assertEquals(2,ordinary.path("rows").size());assertEquals("interval",ordinary.path("rows").get(0).path("pricingModel").asText());assertEquals(13.5,ordinary.path("rows").get(0).path("intervalPrice").asDouble());
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
    @Test void usesSfSettlementOnceAndBlocksUnreliableCriticalFormula()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("服装专线");row(s,0,"国家名称","Code","运费/kg","折扣率","结算运费","专递操作费/票","计费重量限制（kg）");
            row(s,1,"德国","DE",71,0.64,45.44,22,"0.001-0.1KG");
            var parsed=parser.parse(bytes(book),"顺丰.xlsx");assertEquals(45.44,parsed.path("channels").get(0).path("rows").get(0).path("pricePerKg").asDouble());
            s.getRow(1).getCell(4).setCellFormula("1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            parsed=parser.parse(bytes(book),"顺丰.xlsx");assertTrue(parsed.path("channels").get(0).path("errors").asInt()>0);
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
            assertFalse(channel.path("quoteReady").asBoolean());
            assertTrue(channel.path("rows").get(0).path("pendingReason").asText().contains("邮编"));
        }
        try(var book=new XSSFWorkbook()) {
            var prices=book.createSheet("服装专线");
            row(prices,0,"国家","重量段","运费/KG","挂号费/票");
            for(int index=1;index<=LogisticsSourceParser.MAX_PRICE_ROWS_PER_SHEET+1;index++)row(prices,index,"美国","0-1",55,20);
            var error=assertThrows(com.milano.quotation.common.AppException.class,()->parser.parse(bytes(book),"顺丰.xlsx"));
            assertTrue(error.getMessage().contains("500"));
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
            assertFalse(clothing.path("quoteReady").asBoolean());
            assertTrue(clothing.path("rows").get(0).path("pendingReason").asText().contains("邮编"));
            assertTrue(standard.path("quoteReady").asBoolean());
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
            assertFalse(parsed.path("channels").get(0).path("quoteReady").asBoolean());
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
            var channel=parser.parse(bytes(book),"燕文价格.xlsx").path("channels").get(0);assertTrue(channel.path("errors").asInt()>0);assertEquals(0,findCountry(channel,"US").path("etaMinDays").asInt());
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
        var expectedPending=files.stream().anyMatch(path->path.getFileName().toString().contains("通邮"))?Set.of("俄罗斯专线","ebay挂号保建品"):Set.<String>of();
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
        assertEquals("97172f01a81ec6389773fa8a36864fbd1f356b5f5e1ac83463bee62ad0d3aa7b",unrelatedHash);
        assertEquals("06461d6ecc61d5055d84d4cd794c242d9a146ce11591c8420655bcd6596a9ecb",yanwenCoreHash);
    }
    @Test @EnabledIfSystemProperty(named="logistics.corpusDir",matches=".+")
    void enrichesThePinnedRealYanwenWorkbookWithoutChangingRows()throws Exception {
        var path=Path.of(System.getProperty("logistics.corpusDir")).resolve("8.27燕文价格.xlsx");var bytes=Files.readAllBytes(path);
        assertEquals("67D6E7A198E1AB685F816195BF930731C47298E27A780AA77210569894B525E4",HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var parsed=parser.parse(bytes,path.getFileName().toString());var channels=new LinkedHashMap<String,JsonNode>();for(var channel:parsed.path("channels"))channels.put(channel.path("channelName").asText(),channel);
        var expectedRows=Map.of("燕文专线追踪-普货",190,"燕文专线追踪-特货",198,"燕文专线惠选-普货",34,"燕文化妆品专线",111,"燕文服装专线-普货",51,"燕文精品追踪-普货",12,"中邮上海线下E邮宝",30);
        assertEquals(expectedRows.keySet(),channels.keySet());int total=0,withEta=0;var unknown=new ArrayList<String>();var etaCounts=new LinkedHashMap<String,Integer>();var etaCountries=new LinkedHashMap<String,Set<String>>();
        for(var entry:expectedRows.entrySet()) {
            var channel=channels.get(entry.getKey());assertEquals(entry.getValue(),channel.path("rows").size(),entry.getKey());total+=channel.path("rows").size();
            var countriesWithEta=new TreeSet<String>();int channelEta=0;for(var row:channel.path("rows"))if(row.path("etaMinDays").asInt()>0&&row.path("etaMaxDays").asInt()>=row.path("etaMinDays").asInt()){withEta++;channelEta++;countriesWithEta.add(row.path("countryCode").asText());}else unknown.add(entry.getKey()+"|"+row.path("countryCode").asText()+"|"+row.path("sourceRow").asInt());etaCounts.put(entry.getKey(),channelEta);etaCountries.put(entry.getKey(),countriesWithEta);
        }
        assertEquals(626,total);assertEquals(623,withEta,etaCounts+" countries="+etaCountries);assertEquals(List.of("中邮上海线下E邮宝|EG|32","中邮上海线下E邮宝|MA|33","中邮上海线下E邮宝|ZA|34"),unknown);
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
