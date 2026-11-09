package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same source-layout fixture mutations as existing Wanbang products, with independent source expectations. */
class WanbangClothingSourceTest {
    final WanbangPreferredSourceTest helper = new WanbangPreferredSourceTest() {
        @Override JsonNode source() throws Exception {
            try(var in=getClass().getResourceAsStream("/wanbang-clothing-20261109.json")){return mapper.readTree(in);}
        }
    };
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)helper.mapper.readTree(Files.readString(Path.of("../scripts/logistics/wanbang-clothing-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));
        return new CompanyChannelScope(config.put("enabled",true).put("revision",1));
    }
    ObjectNode reconcile(ObjectNode result,int increase) throws Exception {
        assertEquals(1,result.path("channels").size(),result.toPrettyString());
        var channel=result.path("channels").get(0);
        assertEquals("万邦优选服装专线",channel.path("channelName").asText());
        assertEquals("服装",channel.path("logisticsAttribute").asText());
        assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
        assertEquals(0,channel.path("errors").asInt(),channel.toPrettyString());
        assertEquals(44,channel.path("rows").size());
        var evidence=helper.mapper.createObjectNode();evidence.set("channels",result.path("channels"));
        var cases=evidence.putArray("cases");var rejected=evidence.putArray("rejected");
        var upper=new HashMap<String,Double>();
        for(var expected:helper.source().path("channels").get(0).path("rows")) {
            String country=expected.path("countryCode").asText();
            var range=expected.path("weight").asText().toUpperCase(Locale.ROOT).replace("KG","").split("-");
            double from=Double.parseDouble(range[0]),to=Double.parseDouble(range[1]);
            var row=channel.path("rows").valueStream().filter(v->v.path("countryCode").asText().equals(country)&&v.path("weightFromKg").asDouble()==from).findFirst().orElseThrow();
            assertEquals(to,row.path("weightToKg").asDouble());
            assertEquals(expected.path("pricePerKg").asDouble()+increase,row.path("pricePerKg").asDouble());
            assertEquals(expected.path("registrationFee").asDouble(),row.path("registrationFee").asDouble());
            assertEquals("EUSLPHFZ-P",row.path("sourceProductCode").asText());
            assertEquals(expected.path("weight").asText(),row.path("sourceWeightRange").asText());
            assertEquals(0,row.path("minChargeWeightKg").asDouble());
            assertEquals(0,row.path("billingStepKg").asDouble());
            var eta=expected.path("eta").asText().split("-");
            assertEquals(Integer.parseInt(eta[0]),row.path("etaMinDays").asInt());
            assertEquals(Integer.parseInt(eta[1]),row.path("etaMaxDays").asInt());
            for(double weight:List.of(from==0?.001:from,(from+to)/2,to)) {
                double total=BigDecimal.valueOf(expected.path("pricePerKg").asDouble()+increase).multiply(BigDecimal.valueOf(weight)).add(expected.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP).doubleValue();
                assertEquals(total,helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country",country).put("weightKg",weight)).path("total").asDouble(),country+weight);
                cases.addObject().put("country",country).put("weightKg",weight).put("total",total);
            }
            if(from>0) {
                double gap=from-.0005;
                assertThrows(AppException.class,()->helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country",country).put("weightKg",gap)));
                rejected.addObject().put("country",country).put("weightKg",gap);
            }
            upper.merge(country,to,Math::max);
        }
        assertEquals(14,upper.size());
        // Explicit user decision: price bands govern, including HU/HR/SI up to 30kg.
        for(String country:List.of("HU","HR","SI"))assertEquals(30,upper.get(country));
        for(var entry:upper.entrySet()) {
            double overweight=entry.getValue()+.001;
            assertThrows(AppException.class,()->helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country",entry.getKey()).put("weightKg",overweight)));
            rejected.addObject().put("country",entry.getKey()).put("weightKg",overweight);
        }
        return evidence;
    }
    @Test void preservesBandsAndPricesAcrossMovedHeadersAndMergedCountries() throws Exception {
        reconcile(helper.parser.parse(helper.fixture(false,"",0),"万邦报价.xlsx",directory()),0);
        reconcile(helper.parser.parse(helper.fixture(true,"",3),"新版文件.xlsx",directory()),3);
    }
    @Test void blocksMissingPricesRangesFeesAndWrongProductCodes() throws Exception {
        for(String defect:List.of("code","weight-column","weight-row","fee-column","fee-cell","price-cell")) {
            var result=helper.parser.parse(helper.fixture(true,defect,0),"万邦更新.xlsx",directory());
            assertEquals(1,result.path("channels").size(),defect+result.toPrettyString());
            assertFalse(result.path("channels").get(0).path("quoteReady").asBoolean(),defect+result.toPrettyString());
        }
    }
    @Test @EnabledIfSystemProperty(named="wanbang.clothing.source",matches=".+")
    void reconcilesOriginalWorkbookAndExistingProductDirectory() throws Exception {
        var path=Path.of(System.getProperty("wanbang.clothing.source"));
        var result=helper.parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/wanbang-clothing"));
        Files.writeString(Path.of("target/wanbang-clothing/parsed.json"),result.toPrettyString());
        Files.writeString(Path.of("target/wanbang-clothing/billing-cases.json"),reconcile(result,0).toPrettyString());
        var combined=(ObjectNode)helper.directory().snapshot();
        combined.withArray("entries").addAll((tools.jackson.databind.node.ArrayNode)directory().snapshot().path("entries"));
        var all=helper.parser.parse(Files.readAllBytes(path),path.getFileName().toString(),new CompanyChannelScope(combined));
        var clothing=all.path("channels").valueStream().filter(c->c.path("channelName").asText().equals("万邦优选服装专线")).toList();
        assertEquals(1,clothing.size());
        var scoped=helper.mapper.createObjectNode();scoped.putArray("channels").add(clothing.getFirst());reconcile(scoped,0);
        for(var c:all.path("channels"))if(!c.path("channelName").asText().equals("万邦优选服装专线"))
            for(var row:c.path("rows"))assertNotEquals("EUSLPHFZ-P",row.path("sourceProductCode").asText());
    }
}
