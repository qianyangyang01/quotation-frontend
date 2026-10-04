package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YanwenOctoberLayoutTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    byte[] fixture(String product,String etaHeader,String eta) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("燕文专线快递-普货");
            LogisticsSourceParserTest.row(sheet,1,"燕文专线快递-普货","产品编号："+product+"\n生效日期：2026-09-29 09:00");
            LogisticsSourceParserTest.row(sheet,3,"大洲","国家","CountryCode",etaHeader,"重量限制(KG)","重量段(KG)","最小计费重量(KG)","公斤运费(元/KG)","处理费(元/件)");
            LogisticsSourceParserTest.row(sheet,4,"北美洲","美国","US",eta,"0-30","0.001 - 0.11","0.03",119,25);
            LogisticsSourceParserTest.row(sheet,6,"国家维度具体要求");
            LogisticsSourceParserTest.row(sheet,7,"欧洲","芬兰","FI","","","22000-22999的邮编不通达","",0,0);
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    @Test void acceptsInlineProductCodeAndDaysSpecifiedInHeaderWithoutParsingPostalNotesAsWeights() throws Exception {
        var result=parser.parse(fixture("440","参考时效(工作日)","5-8"),"燕文更新.xlsx");
        var c=result.path("channels").get(0);
        assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());assertEquals(1,c.path("rows").size());
        var row=c.path("rows").get(0);
        assertEquals("440",row.path("sourceProductCode").asText());assertEquals(5,row.path("etaMinDays").asInt());assertEquals(8,row.path("etaMaxDays").asInt());
        assertEquals("5-8",row.path("sourceEtaText").asText());
        assertEquals(28.57,new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",.01)).path("total").asDouble());
    }
    @Test void wrongProductCodeStillBlocksAndAmbiguousEtaStillRequiresReview() throws Exception {
        var wrong=parser.parse(fixture("557","参考时效(工作日)","5-8"),"燕文更新.xlsx").path("channels").get(0);
        assertTrue(wrong.path("errors").asInt()>0);assertFalse(wrong.path("quoteReady").asBoolean());
        for(var eta:List.of("8-5","5-8/10-12","约5","5-8小时")) {
            var c=parser.parse(fixture("440","参考时效(工作日)",eta),"燕文更新.xlsx").path("channels").get(0);
            assertEquals(0,c.path("rows").get(0).path("etaMinDays").asInt(),eta);
        }
        var unitless=parser.parse(fixture("440","参考时效","5-8"),"燕文更新.xlsx").path("channels").get(0);
        assertEquals(0,unitless.path("rows").get(0).path("etaMinDays").asInt());
    }
    @Test void genericYanwenTrackingStopsAtCountryRequirements() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var s=book.createSheet("燕文专线追踪-普货");
            LogisticsSourceParserTest.row(s,0,"国家","重量段(KG)","公斤运费(元/KG)","处理费(元/件)");
            LogisticsSourceParserTest.row(s,1,"芬兰","0-2",100,20);
            LogisticsSourceParserTest.row(s,2,"国家维度具体要求");
            LogisticsSourceParserTest.row(s,3,"芬兰","22000-22999的邮编不通达",100,20);
            var c=parser.parse(LogisticsSourceParserTest.bytes(book),"燕文.xlsx").path("channels").get(0);
            assertEquals(1,c.path("rows").size());assertEquals(0,c.path("errors").asInt(),c.path("issues").toString());
        }
    }
    @Test @EnabledIfSystemProperty(named="yanwen.october.evidence",matches=".+")
    void replaysRetainedSourceCellsAndReconcilesEveryPriceAndWeightAgainstTheOriginalImport() throws Exception {
        var base=Path.of(System.getProperty("yanwen.october.evidence"));
        var evidence=mapper.readTree(Files.readString(base.resolve("evidence.json")));
        var checkpoint=mapper.readTree(Files.readString(base.resolve("checkpoint.json")));
        var directory=new CompanyChannelScope(mapper.readTree(Files.readString(base.resolve("scope.json"))));
        byte[] bytes;
        try(var book=new XSSFWorkbook()) {
            for(var sheet:evidence.path("sheets")) {
                var target=book.createSheet(sheet.path("name").asText());
                for(var row:sheet.path("sourceCells"))for(var cell:row.properties()) {
                    var address=new CellReference(cell.getKey());
                    var targetRow=target.getRow(address.getRow());if(targetRow==null)targetRow=target.createRow(address.getRow());
                    targetRow.createCell(address.getCol()).setCellValue(cell.getValue().asText());
                }
            }
            bytes=LogisticsSourceParserTest.bytes(book);
        }
        var output=Path.of("target/yanwen-october");Files.createDirectories(output);
        Files.write(output.resolve("source-evidence-replay.xlsx"),bytes);
        var parsed=parser.parse(bytes,evidence.path("fileName").asText(),directory);
        Files.writeString(output.resolve("parsed.json"),parsed.toString());
        assertEquals(checkpoint.path("channels").size(),parsed.path("channels").size());
        for(var c:parsed.path("channels")) {
            var original=checkpoint.path("channels").valueStream().filter(v->v.path("channelName").equals(c.path("channelName"))).findFirst().orElseThrow();
            assertEquals(0,c.path("errors").asInt(),c.path("channelName")+c.path("issues").toString());
            assertEquals(original.path("rows").size(),c.path("rows").size(),c.path("channelName").asText());
            for(var row:c.path("rows")) {
                var old=original.path("rows").valueStream().filter(r->r.path("sourceRow").equals(row.path("sourceRow"))&&r.path("sourceSheet").equals(row.path("sourceSheet"))).findFirst().orElseThrow();
                for(var field:List.of("pricePerKg","registrationFee","weightFromKg","weightToKg","minChargeWeightKg","countryCode","zoneName","pricingModel")) {
                    var label=c.path("channelName")+"/"+row.path("sourceRow")+"/"+field;
                    if(old.path(field).isNumber())assertEquals(0,old.path(field).decimalValue().compareTo(row.path(field).decimalValue()),label);
                    else assertEquals(old.path(field),row.path(field),label);
                }
            }
        }
        var before=mapper.readTree(Files.readString(base.resolve("before.json")));
        var batch=before.path("batch");
        var plan=mapper.createObjectNode().put("batchId",batch.path("id").asText()).put("datasetId",batch.path("dataset_id").asText())
            .put("parserVersion",LogisticsSourceParser.VERSION).put("sourceFileIndex",0)
            .put("sourceSha256",batch.path("payload").path("files").get(0).path("sha256").asText())
            .put("evidenceSha256",batch.path("payload").path("fileReports").get(0).path("sourceEvidence").path("sha256").asText());
        plan.set("expectedBatchPayload",batch.path("payload").deepCopy());
        var repairs=plan.putArray("channels");
        for(var prior:batch.path("payload").path("results")) {
            if(!prior.path("status").asText().equals("blocked")||prior.path("sourceFileIndex").asInt(-1)!=0)continue;
            var c=(ObjectNode)parsed.path("channels").valueStream().filter(v->v.path("channelName").equals(prior.path("channelName"))).findFirst().orElseThrow().deepCopy();
            var matches=before.path("channels").valueStream().filter(v->v.path("dataset_id").equals(batch.path("dataset_id"))&&v.path("archived_at").isNull()&&v.path("payload").path("name").equals(c.path("channelName"))).toList();
            assertEquals(1,matches.size(),c.path("channelName").asText());var channel=matches.getFirst();
            var published=before.path("versions").valueStream().filter(v->v.path("id").equals(channel.path("current_version_id"))).findFirst().orElseThrow();
            var item=repairs.addObject().put("channelId",channel.path("id").asText()).put("basePublishedVersionId",published.path("id").asText());
            item.set("expectedPublishedPayload",published.path("payload").deepCopy());
            var drafts=item.putArray("expectedDrafts");
            for(var v:before.path("versions"))if(v.path("channel_id").equals(channel.path("id"))&&v.path("status").asText().equals("draft")) {
                drafts.addObject().put("id",v.path("id").asText()).set("payload",v.path("payload").deepCopy());
                LogisticsImportService.inheritManualEta(c,v.path("payload"));
            }
            LogisticsImportService.inheritManualEta(c,published.path("payload"));LogisticsReadiness.apply(c);
            assertTrue(c.path("pricingReady").asBoolean());assertTrue(c.path("etaReady").asBoolean());
            var hash=parser.businessHash((tools.jackson.databind.node.ArrayNode)c.path("rows"));
            c.put("contentHash",hash).put("sourceHash",LogisticsDatasetService.hash(hash+":"+published.path("id").asText()));
            c.put("basePublishedVersionId",published.path("id").asText()).put("sourceFileIndex",0);
            var comparison=new LogisticsWorkbookService(mapper).compare((tools.jackson.databind.node.ArrayNode)c.path("rows"),(tools.jackson.databind.node.ArrayNode)published.path("payload").path("rows"));
            c.set("summary",comparison.path("summary"));c.set("diffRows",comparison.path("diffRows"));
            item.set("payload",c);
        }
        assertEquals(7,repairs.size());
        var nextBatch=(ObjectNode)batch.path("payload").deepCopy();
        var results=nextBatch.putArray("results");
        for(var prior:batch.path("payload").path("results")) {
            var repair=repairs.valueStream().filter(v->v.path("payload").path("channelName").equals(prior.path("channelName"))&&prior.path("sourceFileIndex").asInt(-1)==0).findFirst();
            if(repair.isEmpty()){results.add(prior);continue;}
            var p=repair.get().path("payload");var result=results.addObject();
            for(var field:List.of("providerName","channelName","errors","pricingReady","etaReady","etaMissingCount","pendingReasons","missingEtaRoutes","blockingReasons","reviewWarnings","summary","issues","basePublishedVersionId"))
                if(p.has(field))result.set(field,p.path(field));
            result.put("status","draft").put("channelId",repair.get().path("channelId").asText()).put("sourceFileIndex",0).put("priceRows",p.path("rows").size());
        }
        LogisticsReadiness.applyBatch(nextBatch);plan.set("nextBatchPayload",nextBatch);
        Files.writeString(output.resolve("draft-plan.json"),plan.toString());
    }
}
