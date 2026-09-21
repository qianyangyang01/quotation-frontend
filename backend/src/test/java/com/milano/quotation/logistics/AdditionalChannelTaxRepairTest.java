package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline preparation only. Production mutations require the separate guarded SQL. */
class AdditionalChannelTaxRepairTest {
    @Test @EnabledIfSystemProperty(named="tax.repairDir",matches=".+")
    void serverSaveGuardAcceptsEveryFrontendTaxSnapshot() throws Exception {
        var root=Path.of(System.getProperty("tax.repairDir"));var mapper=new ObjectMapper();
        var finance=mapper.readTree(Files.readString(root.resolve("finance-prepared.json"))).path("tax-settings");
        var baseline=mapper.readTree(Files.readString(root.resolve("live-before.json")));
        var names=new HashMap<String,String>();for(var c:baseline.path("settings").path("country-classification").path("payload"))names.put(c.path("code").asText(),c.path("country").asText());
        var evidence=mapper.readTree(Files.readString(root.resolve("tax-calculation-evidence.json")));int validated=0;
        for(var check:evidence.path("checks")) {
            var result=check.path("result");assertTrue(result.path("configured").asBoolean());
            if(!result.has("calculation")){assertEquals(0,result.path("taxUsd").asDouble());continue;}
            var option=mapper.createObjectNode().put("country",names.get(check.path("country").asText())).put("channelKey",check.path("channel").asText())
                    .put("taxConfigured",true).put("taxIncluded",result.path("included").asBoolean()).put("taxFeeMode",result.path("feeMode").asText())
                    .set("countryFixedTaxUsd",result.path("fixedFeeUsd")).set("tax1Usd",result.path("taxUsd"));
            option.putObject("taxCalculations").set("1",result.path("calculation"));
            assertTrue(com.milano.quotation.finance.ChannelTaxRules.validateQuote(finance,option,mapper.createObjectNode().put("usdCny",6.7).put("eurUsd",1.2),1));validated++;
        }
        assertTrue(validated>600);
        Files.writeString(root.resolve("native-tax-evidence.json"),mapper.createObjectNode().put("validatedSnapshots",validated).put("passed",true).toPrettyString());
    }
    @Test @EnabledIfSystemProperty(named="tax.repairDir",matches=".+")
    void verifiesNativeFinanceValidationAndPreparesReviewedUsOnlyVersion() throws Exception {
        var root=Path.of(System.getProperty("tax.repairDir"));var mapper=new ObjectMapper();
        var finance=mapper.readTree(Files.readString(root.resolve("finance-prepared.json")));
        var validation=Class.forName("com.milano.quotation.finance.FinanceSettingValidation").getDeclaredMethod("validate",String.class,tools.jackson.databind.JsonNode.class);
        validation.setAccessible(true);
        for(var key:List.of("tax-settings","channel-policies"))validation.invoke(null,key,finance.path(key));
        var baseline=mapper.readTree(Files.readString(root.resolve("a-before.json")));assertEquals(0,baseline.path("draftCount").asInt());
        var previous=baseline.path("version").path("payload");var rows=mapper.createArrayNode();
        assertEquals(64,previous.path("rows").size());
        for(var row:previous.path("rows"))if(row.path("countryCode").asText().equals("US"))rows.add(row.deepCopy());
        assertEquals(8,rows.size());
        var payload=(ObjectNode)previous.deepCopy();payload.set("rows",rows);payload.put("validRows",rows.size());
        payload.put("parserVersion",LogisticsSourceParser.VERSION);
        QiaojieSourceRules.validateCountries("巧捷","",QiaojieSourceRules.US_ONLY_CHANNEL_CODE,payload);
        LogisticsReadiness.apply(payload);assertTrue(payload.path("quoteReady").asBoolean(),payload.path("blockingReasons").toString());
        var comparison=new LogisticsWorkbookService(mapper).compare(rows,(ArrayNode)previous.path("rows"));
        assertEquals(56,comparison.path("summary").path("removed").asInt());
        for(var field:List.of("price","rule","added","highRisk"))assertEquals(0,comparison.path("summary").path(field).asInt(),field);
        payload.set("summary",comparison.path("summary"));payload.set("diffRows",comparison.path("diffRows"));
        var proof=mapper.createArrayNode();var engine=new LogisticsBillingEngine(mapper);
        for(var row:rows)for(double weight:new double[]{Math.max(.001,row.path("weightFromKg").asDouble()+.000001),row.path("weightToKg").asDouble()}) {
            var input=mapper.createObjectNode().put("country","US").put("weightKg",weight);
            var old=engine.calculate(previous.path("rows"),input);var next=engine.calculate(rows,input);
            assertSameBill(old,next);proof.addObject().set("input",input).set("expected",next);
        }
        for(double weight:new double[]{.012,.049999,.05,.050001}) {
            var input=mapper.createObjectNode().put("country","US").put("weightKg",weight);
            var result=engine.calculate(rows,input);assertEquals(Math.max(.05,weight),result.path("chargeWeightKg").asDouble());
            assertSameBill(engine.calculate(previous.path("rows"),input),result);proof.addObject().set("input",input).set("expected",result);
        }
        for(var country:previous.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().filter(c->!c.equals("US")).toList())
            assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(rows,mapper.createObjectNode().put("country",country).put("weightKg",.1)));
        var now=Instant.now().toString();var id=UUID.randomUUID().toString();var old=baseline.path("version").path("id").asText();
        var repairId="additional-channel-tax-us-only-20260921";
        var hash=LogisticsDatasetService.hash(repairId+":"+old+":"+rows);
        payload.put("id",id).put("channelId",baseline.path("channel").path("id").asText())
                .put("versionNumber",baseline.path("maxVersion").asInt()+1).put("status","published")
                .put("importedAt",now).put("publishedAt",now).put("importedBy","codex-maintenance").put("publishedBy","codex-maintenance")
                .put("sourceHash",hash).put("contentHash",hash).put("basePublishedVersionId",old).put("derivedFromVersionId",old)
                .put("repairId",repairId).put("auditNote","用户确认巧捷A仅发美国且包税；移除22国56条价格，保留美国8条原价及50g起重、历史报价和原表");
        var prepared=mapper.createObjectNode().set("payload",payload).set("evidence",proof);
        Files.writeString(root.resolve("version-prepared.json"),prepared.toPrettyString());
    }
    private void assertSameBill(tools.jackson.databind.JsonNode before,tools.jackson.databind.JsonNode after) {
        var a=(ObjectNode)before.deepCopy();var b=(ObjectNode)after.deepCopy();a.remove("rowIndex");b.remove("rowIndex");assertEquals(a,b);
    }
}
