package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedSourceBillingRulesTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LogisticsBillingEngine billing = new LogisticsBillingEngine(mapper);
    static final String CHILE = "关税DDU，50克起重，2KG以上按照0.5进位";
    ObjectNode channel(String provider, String sheet, String country) {
        var c = mapper.createObjectNode().put("providerName", provider).put("channelName", sheet);
        c.putArray("rows").addObject().put("countryCode", country).put("areaName", country).put("sourceSheet", sheet)
                .put("sourceRow", 6).put("pricingModel", "per-kg").put("currency", "CNY")
                .put("weightFromKg", 0).put("weightToKg", 5).put("weightFromInclusive", false).put("weightToInclusive", true)
                .put("pricePerKg", 110).put("registrationFee", 32).put("minChargeWeightKg", .05).put("billingStepKg", 0)
                .putObject("rawValues");
        return c;
    }
    ObjectNode row(ObjectNode c) { return (ObjectNode)c.path("rows").get(0); }
    void apply(ObjectNode c, String cell, String text) {
        ConfirmedSourceBillingRules.apply(c, mapper.createArrayNode().add(mapper.createObjectNode().put(cell, text)));
    }
    double total(ObjectNode c, double weight) {
        return billing.calculate(c.path("rows"), mapper.createObjectNode().put("country", row(c).path("countryCode").asText()).put("weightKg", weight)).path("total").asDouble();
    }
    @Test void explicitGramRulesRoundBeforeSelectingTheNextTariff() {
        var c = channel("递四方", "联邮通经济挂号-普货（O5）", "US");
        row(c).put("weightToKg", .2).put("pricePerKg", 100).put("registrationFee", 20);
        var next = row(c).deepCopy().put("weightFromKg", .201).put("weightFromInclusive", true).put("weightToKg", 5).put("pricePerKg", 120);
        ((tools.jackson.databind.node.ArrayNode)c.path("rows")).add(next);
        apply(c, "A37", "以G为单位进位；");
        assertEquals(40, total(c, .2)); assertEquals(44.12, total(c, .2001));
        assertEquals("A37", row(c).path("sourceBillingStepCell").asText());
        var before = c.deepCopy(); apply(c, "A37", "以G为单位进位；"); assertEquals(before, c);
    }
    @Test void unrelatedSheetsAmbiguousGramDescriptionsAndReturnFeesAreNotPromoted() {
        for (var text : List.of("按克计费，50克起重", "重派费以G为单位进位；", "不以G为单位进位；", "以KG为单位进位；")) {
            var c=channel("云速递", "全球专线普货", "US");var before=c.deepCopy();apply(c,"A89",text);assertEquals(before,c,text);
        }
        var wrongProvider=channel("极通环球","全球专线普货","US");var before=wrongProvider.deepCopy();apply(wrongProvider,"A89","以G为单位进位；");assertEquals(before,wrongProvider);
        var wrongSheet=channel("递四方","新渠道","US");before=wrongSheet.deepCopy();apply(wrongSheet,"A37","以G为单位进位；");assertEquals(before,wrongSheet);
    }
    @Test void explicitLargerIncrementsAndPiecePricesArePreserved() {
        for (boolean bands : new boolean[]{false,true}) {
            var c=channel("云速递","全球专线带电","US");
            if(bands)row(c).putArray("billingStepBands").addObject().put("aboveKg",0).put("stepKg",.5);
            else row(c).put("billingStepKg",.1);
            var before=c.deepCopy();apply(c,"A115","1、以G为单位进位，美国最低50G计费；");assertEquals(before,c);
        }
        var c=channel("巧捷","巧捷小包全球特惠A(服装)","US");row(c).put("pricingModel","per-piece-500g");var before=c.deepCopy();apply(c,"B75","以G为单位进位；");assertEquals(before,c);
    }
    @Test void sfCountryTableNeverLeaksItsRuleIntoSpainOrOtherCountries() {
        for(String country:List.of("US","ES","JP")) {
            var c=channel("顺丰","服装专线",country);
            var cells=mapper.createArrayNode().add(mapper.createObjectNode().put("B60","美国、德国、英国、法国、意大利、澳大利亚")
                    .put("C60","单票单件计费;首重50g，续重按1g计算计费重量"));
            ConfirmedSourceBillingRules.apply(c,cells);assertEquals(country.equals("US")?.001:0,row(c).path("billingStepKg").asDouble());
        }
        var c=channel("顺丰","敏货电商专递","PL");
        ConfirmedSourceBillingRules.apply(c,mapper.createArrayNode().add(mapper.createObjectNode().put("B66","德国、波兰、奥地利").put("C66","单票单件计费;首重50g，续重按1g；体积重和实重取大值作为计费重量")));
        assertEquals(.001,row(c).path("billingStepKg").asDouble());
    }
    @Test void chileHalfKgAppliesOnlyAboveTwoKgAndNeedsMatchingRowAndSourceEvidence() {
        var c=channel("巧捷","巧捷专线小包全球特惠F(敏感)","CL");((ObjectNode)row(c).path("rawValues")).put("K98",CHILE);
        assertEquals(263,total(c,2.1));apply(c,"K98",CHILE);
        assertEquals(252,total(c,2));assertEquals(307,total(c,2.0001));assertEquals(307,total(c,2.5));assertEquals(362,total(c,2.5001));assertEquals(582,total(c,5));
        assertThrows(com.milano.quotation.common.AppException.class,()->total(c,5.0001));
        for(String country:List.of("US","CL")) {
            var other=channel("巧捷","巧捷专线小包全球特惠F(敏感)",country);
            if(country.equals("US"))((ObjectNode)row(other).path("rawValues")).put("K98",CHILE);
            var before=other.deepCopy();apply(other,"K98",CHILE);assertEquals(before,other);
        }
    }
    @Test void chileSpecificMinimumSupersedesOnlyKnownGenericTenGramNote() {
        var c=channel("通邮"," 通邮挂号特货","CL");row(c).put("pricePerKg",101).put("registrationFee",24).put("minChargeWeightKg",.01).put("sourceMinimumWeightKind","note");
        ((ObjectNode)row(c).path("rawValues")).put("I96",CHILE);
        assertEquals(26.02,total(c,.02));apply(c,"I96",CHILE);assertEquals(29.05,total(c,.02));assertEquals(29.05,total(c,.05));assertEquals(34.10,total(c,.1));
        assertEquals("I96",row(c).path("sourceMinimumWeightCell").asText());
        for(String kind:List.of("column","column-inherited","user-confirmed")) {
            var other=c.deepCopy();row(other).put("minChargeWeightKg",.01).put("sourceMinimumWeightKind",kind);var before=other.deepCopy();apply(other,"I96",CHILE);assertEquals(before,other);
        }
        var conflict=c.deepCopy();row(conflict).put("minChargeWeightKg",.02).put("sourceMinimumWeightKind","note");var before=conflict.deepCopy();apply(conflict,"I96",CHILE);assertEquals(before,conflict);
    }
    @Test void realWorkbookImportPersistsTheRuleAndPreservesPrices() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("全球专线普货");
            LogisticsSourceParserTest.row(sheet,0,"国家/地区","参考时效","分区","重量(KG)","进位制","最低计费重","运费(RMB/KG)","挂号费(RMB/票)");
            LogisticsSourceParserTest.row(sheet,1,"美国","6-12","","0<W≤1","",.05,83,25);
            LogisticsSourceParserTest.row(sheet,4,"1、以G为单位进位，美国最低50G计费；加拿大最低100G计费；");
            var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
            var c=(ObjectNode)parser.parse(LogisticsSourceParserTest.bytes(book),"云速递.xlsx").path("channels").get(0);
            assertEquals(0,c.path("errors").asInt(),c.toString());assertEquals(.001,row(c).path("billingStepKg").asDouble());
            assertEquals(83,row(c).path("pricePerKg").asDouble());assertEquals(25,row(c).path("registrationFee").asDouble());assertEquals(29.23,total(c,.0501));
        }
    }
}
