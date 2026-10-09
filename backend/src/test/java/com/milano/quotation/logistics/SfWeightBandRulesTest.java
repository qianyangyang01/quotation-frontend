package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class SfWeightBandRulesTest {
    final ObjectMapper mapper=new ObjectMapper();
    static final String FOOTER="泰国：单票单件计费\n0.001-1KG：首重0.1KG,续重0.1KG；\n1.001-30KG：首重1KG,续重0.1KG；\n实重材积取其大者计费。";
    @Test @EnabledIfSystemProperty(named="sf.weightBandWorkbook",matches=".+")
    void realWorkbookThaiRowsAreReadyWithUnchangedPrices() throws Exception {
        var path=java.nio.file.Path.of(System.getProperty("sf.weightBandWorkbook"));
        var parsed=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper)).parse(java.nio.file.Files.readAllBytes(path),"顺丰国际电商产品价格表(20261009）.xlsx");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/sf-weight-bands"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/sf-weight-bands/real-workbook.json"),mapper.writeValueAsString(parsed));
        int found=0;
        for(var channel:parsed.path("channels"))if(channel.path("channelName").asText().equals("国际电商专递-CD")) {
            assertEquals(127,channel.path("rows").size());
            for(var row:channel.path("rows"))if(row.path("countryCode").asText().equals("TH")) {
                found++;boolean upper=row.path("sourceRow").asInt()%2==1;
                assertEquals(upper?1:.1,row.path("minChargeWeightKg").asDouble(),row.toString());
                assertEquals(.1,row.path("billingStepKg").asDouble());
                assertTrue(row.path("quoteReady").asBoolean(),channel.path("blockingReasons").toString());
                assertEquals(row.path("sourceRow").asInt()<110?23:25.76,row.path("pricePerKg").asDouble());
            }
        }
        assertEquals(4,found);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/sf-weight-bands"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/sf-weight-bands/real-workbook.json"),mapper.writeValueAsString(parsed));
    }
    @Test @EnabledIfSystemProperty(named="sf.savedImport",matches=".+")
    void repairsTheActualSavedDraftWithoutChangingOtherRowsOrManualPrices() throws Exception {
        var backup=mapper.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("sf.savedImport"))));
        ObjectNode payload=null;
        for(var version:backup.path("versions"))if(version.path("id").asText().equals("1789bf07-66b9-4b63-b722-2620dd88093f"))payload=(ObjectNode)version.path("payload").deepCopy();
        assertNotNull(payload);var before=payload.deepCopy();
        assertEquals(4,SfWeightBandRules.repairDraft(payload).size());
        for(int i=0;i<payload.path("rows").size();i++) {
            var row=payload.path("rows").get(i);var old=before.path("rows").get(i);
            if(!row.path("countryCode").asText().equals("TH"))assertEquals(old,row);
            else {assertEquals(.1,LogisticsStepPricing.step(row,new java.math.BigDecimal("0.5")).doubleValue());assertEquals(row.path("sourceRow").asInt()%2==1?1:.1,row.path("minChargeWeightKg").asDouble());
                for(String field:new String[]{"pricePerKg","registrationFee","weightFromKg","weightToKg","rawValues","etaMinDays","etaMaxDays","billingStepBands","billingStepKg"})assertEquals(old.path(field),row.path(field));}
        }
        assertTrue(SfWeightBandRules.repairDraft(payload).isEmpty());
        LogisticsReadiness.apply(payload);assertTrue(payload.path("pricingReady").asBoolean(),payload.path("blockingReasons").toString());
    }
    @Test void existingDraftRepairPreservesPublishedAndManuallyChangedRanges() {
        var payload=mapper.createObjectNode().put("status","draft").put("providerName","顺丰").put("channelName","国际电商专递-CD");
        var row=row(false).put("sourceRow",108).put("sourceSheet","国际电商专递-CD").put("notes",FOOTER)
                .put("blockingReason","最低计费重量说明冲突，需核对；其他问题").put("weightFromInclusive",true).put("weightToInclusive",true);
        row.putObject("rawValues").put("K108",row.path("sourceWeightRange").asText());payload.putArray("rows").add(row);
        for(String type:new String[]{"published","price-manual","range-manual","minimum-manual","missing-source","wrong-provider"}) {
            var copy=payload.deepCopy();var copyRow=(ObjectNode)copy.path("rows").get(0);
            if(type.equals("published"))copy.put("status","published");
            if(type.equals("price-manual"))copyRow.put("pricePerKg",123.45);
            if(type.equals("range-manual"))copyRow.put("weightToKg",.8);
            if(type.equals("minimum-manual"))copyRow.put("minChargeWeightKg",.2);
            if(type.equals("missing-source"))copyRow.remove("rawValues");
            if(type.equals("wrong-provider"))copy.put("providerName","其他物流商");
            var before=copy.deepCopy();var audit=SfWeightBandRules.repairDraft(copy);
            if(type.equals("price-manual")){assertEquals(1,audit.size());assertEquals(123.45,copyRow.path("pricePerKg").asDouble());assertEquals("其他问题",copyRow.path("blockingReason").asText());}
            else {assertTrue(audit.isEmpty(),type);assertEquals(before,copy,type);}
        }
    }
    ObjectNode row(boolean upper) {
        return mapper.createObjectNode().put("countryCode","TH").put("weightFromKg",upper?1.001:.001).put("weightToKg",upper?30:1)
                .put("sourceWeightRange",upper?"1.001-30KG（续重0.1KG）":"0.001-1KG（首重0.1KG,续重0.1KG）").put("sourceWeightCell",upper?"K109":"K108");
    }
    @Test void matchesEachBandAndPreservesTheEvidence() {
        for(boolean upper:new boolean[]{false,true}) {
            var row=row(upper).put("pendingReason","重量范围附带首续重条件，需核对完整规则；保留其他问题");assertTrue(SfWeightBandRules.apply(row,FOOTER,"C160"));
            assertEquals(upper?1:.1,row.path("minChargeWeightKg").asDouble());assertEquals(.1,row.path("billingStepKg").asDouble());
            assertFalse(row.has("blockingReason"));assertTrue(row.path("sourceMinimumWeightCell").asText().contains("C160"));
            assertEquals("保留其他问题",row.path("pendingReason").asText());
            assertFalse(row.path("sourceMinimumWeightText").asText().contains(upper?"首重0.1KG":"首重1KG"));
        }
    }
    @Test void keepsConflictsUnknownRulesAndIncompleteEvidenceBlocked() {
        for(String footer:new String[]{FOOTER.replace("首重0.1KG","首重0.2KG"),FOOTER.replace("续重0.1KG","续重0.2KG"),
                FOOTER.replace("0.001-1KG","0.001-2KG"),FOOTER.replace("首重0.1KG,续重0.1KG","首重0.1KG收费20元,续重0.1KG")}) {
            var row=row(false);SfWeightBandRules.apply(row,footer,"C160");assertFalse(row.path("blockingReason").asText().isBlank(),footer);
        }
        var upper=row(true);SfWeightBandRules.apply(upper,"","");assertTrue(upper.path("blockingReason").asText().contains("不完整"));
        var explicit=row(false).put("minChargeWeightKg",.3).put("sourceMinimumWeightKind","column");
        SfWeightBandRules.apply(explicit,FOOTER,"C160");assertEquals(.3,explicit.path("minChargeWeightKg").asDouble());assertTrue(explicit.path("blockingReason").asText().contains("冲突"));
        var other=row(false).put("sourceWeightRange","0.001-1KG");assertFalse(SfWeightBandRules.apply(other,"首重50g，续重1g","C161"));
    }
    @Test void importsBothThaiZonesAndBillsAtSourceBoundariesWithoutCrossCountryRules() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("国际电商专递-CD");
            LogisticsSourceParserTest.row(sheet,0,"国家","国家代码","分区","重量段(KG)","公斤运费(元/KG)","处理费(元/件)");
            for(int i=0;i<4;i++)LogisticsSourceParserTest.row(sheet,107+i,"泰国","TH",i<2?"1区":"2区",
                    row(i%2==1).path("sourceWeightRange").asText(),i<2?23:25.76,i<2?(i%2==0?14:10):(i%2==0?15:11));
            LogisticsSourceParserTest.row(sheet,111,"美国","US","","0.001-1KG",60,20);
            LogisticsSourceParserTest.row(sheet,159,"计费标准","泰国",FOOTER);
            LogisticsSourceParserTest.row(sheet,160,"计费标准","美国","首重50g，续重1g");
            var parsed=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper)).parse(LogisticsSourceParserTest.bytes(book),"顺丰国际电商产品价格表(20261009）.xlsx");
            var channel=parsed.path("channels").get(0);assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toString());
            var rows=channel.path("rows");assertEquals(5,rows.size());
            for(var row:rows)if(row.path("countryCode").asText().equals("TH")) {
                assertEquals(row.path("weightFromKg").asDouble()>1?1:.1,row.path("minChargeWeightKg").asDouble());
                assertEquals(.1,row.path("billingStepKg").asDouble());assertTrue(row.path("pricingReady").asBoolean(row.path("quoteReady").asBoolean()),row.toString());
            }else assertEquals(.05,row.path("minChargeWeightKg").asDouble());
            var engine=new LogisticsBillingEngine(mapper);
            for(String zone:new String[]{"1区","2区"})for(double[] example:new double[][]{{.001,.1},{.1,.1},{.101,.2},{1,1},{1.001,1.1},{1.1,1.1},{29.999,30},{30,30}}) {
                var result=engine.calculate(rows,mapper.createObjectNode().put("country","TH").put("zoneName",zone).put("weightKg",example[0]));
                assertEquals(example[1],result.path("chargeWeightKg").asDouble());
                double price=zone.equals("1区")?23:25.76, fee=zone.equals("1区")?(example[1]<=1?14:10):(example[1]<=1?15:11);
                assertEquals(Math.round((price*example[1]+fee)*100)/100.,result.path("total").asDouble());
            }
        }
    }
}
