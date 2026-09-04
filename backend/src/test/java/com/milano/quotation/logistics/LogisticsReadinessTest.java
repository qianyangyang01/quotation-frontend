package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class LogisticsReadinessTest {
    @Test void inheritsOneSourceEtaAcrossEveryWeightTierInTheSameRoute() {
        var channel=JsonNodeFactory.instance.objectNode().put("templateStatus","known");channel.putArray("issues");
        var rows=channel.putArray("rows");var first=price("US","1区",0,1);first.put("etaMinDays",7).put("etaMaxDays",15).put("etaSource","source-row");rows.add(first);rows.add(price("US","1区",1,2));

        LogisticsReadiness.apply(channel);

        assertTrue(channel.path("etaReady").asBoolean());assertTrue(channel.path("quoteReady").asBoolean());assertTrue(channel.path("missingEtaRoutes").isEmpty());
        assertEquals(7,rows.get(1).path("etaMinDays").asInt());assertEquals(15,rows.get(1).path("etaMaxDays").asInt());assertEquals("route-inherited",rows.get(1).path("etaSource").asText());
        assertEquals(rows.get(0).path("routeKey"),rows.get(1).path("routeKey"));
    }

    @Test void missingAndConflictingEtaAreRouteLevelPublishBlockers() {
        var missing=JsonNodeFactory.instance.objectNode().put("templateStatus","known");missing.putArray("issues");missing.putArray("rows").add(price("DE","",0,1));
        LogisticsReadiness.apply(missing);
        assertFalse(missing.path("etaReady").asBoolean());assertFalse(missing.path("pricingReady").asBoolean());assertEquals(1,missing.path("missingEtaRoutes").size());assertTrue(missing.path("blockingReasons").toString().contains("缺少时效"));

        var conflict=JsonNodeFactory.instance.objectNode().put("templateStatus","known");conflict.putArray("issues");var rows=conflict.putArray("rows");
        rows.add(price("GB","2区",0,1).put("etaMinDays",5).put("etaMaxDays",8));rows.add(price("GB","2区",1,2).put("etaMinDays",6).put("etaMaxDays",9));
        LogisticsReadiness.apply(conflict);
        assertFalse(conflict.path("etaReady").asBoolean());assertEquals("conflict",conflict.path("missingEtaRoutes").get(0).path("status").asText());assertTrue(conflict.path("issues").toString().contains("ETA_CONFLICT"));
    }

    @Test void onlyPerKgAndFirstNextAreAutomaticallySupported() {
        var channel=JsonNodeFactory.instance.objectNode().put("templateStatus","known");channel.putArray("issues");
        var row=price("FR","",0,1).put("pricingModel","interval").put("etaMinDays",5).put("etaMaxDays",8);channel.putArray("rows").add(row);
        LogisticsReadiness.apply(channel);
        assertFalse(channel.path("pricingReady").asBoolean());assertTrue(row.path("blockingReason").asText().contains("区间价计费方式暂不支持"));
    }

    @Test void blocksMultipleOriginPriceSetsForOneDestinationRoute() {
        var channel=JsonNodeFactory.instance.objectNode().put("templateStatus","known");channel.putArray("issues");var rows=channel.putArray("rows");
        rows.add(price("US","",0,1).put("originRegion","华东").put("etaMinDays",7).put("etaMaxDays",15));
        rows.add(price("US","",0,1).put("originRegion","华南").put("etaMinDays",7).put("etaMaxDays",15));
        LogisticsReadiness.apply(channel);
        assertFalse(channel.path("pricingReady").asBoolean());assertTrue(channel.path("blockingReasons").toString().contains("多套报价区域"));
    }

    private static ObjectNode price(String country,String zone,double from,double to) {
        return JsonNodeFactory.instance.objectNode().put("countryCode",country).put("areaName",country).put("zoneName",zone).put("originRegion","")
                .put("pricingModel","per-kg").put("pricePerKg",10).put("registrationFee",2).put("weightFromKg",from).put("weightToKg",to)
                .put("weightFromInclusive",from==0).put("weightToInclusive",true).put("sourceProductCode","TEST").put("sourceOriginRegion","华东");
    }
}
