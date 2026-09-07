package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsBillingEngineTest {
    static final ObjectMapper mapper=new ObjectMapper();
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    static ObjectNode row(double from,double to,double price){return mapper.createObjectNode().put("countryCode","US").put("areaName","美国").put("weightFromKg",from).put("weightToKg",to).put("weightFromInclusive",from>0).put("weightToInclusive",true).put("pricePerKg",price).put("registrationFee",10).put("volumetric",false).put("pricingModel","per-kg").put("currency","CNY");}
    static ObjectNode input(double weight){var in=mapper.createObjectNode().put("country","US").put("weightKg",weight);in.putArray("marks").add("普货");return in;}
    @Test void respectsGramBoundariesAndStrictInequality(){var rows=mapper.createArrayNode().add(row(0,.2,50)).add(row(.201,.5,60)).add(row(.501,1,70));
        assertEquals(20,engine.calculate(rows,input(.2)).path("total").asDouble());assertEquals(22.06,engine.calculate(rows,input(.201)).path("total").asDouble());assertEquals(40,engine.calculate(rows,input(.5)).path("total").asDouble());assertEquals(45.07,engine.calculate(rows,input(.501)).path("total").asDouble());
        ((ObjectNode)rows.get(0)).put("weightToInclusive",false);assertThrows(AppException.class,()->engine.calculate(rows,input(.2)));}
    @Test void evaluatesWeightRangesAndRejectsOtherModels(){var r=row(0,5,50).put("minChargeWeightKg",.05);var rows=mapper.createArrayNode().add(r);assertEquals(10.05,engine.calculate(rows,input(.001)).path("total").asDouble());
        r.put("pricingModel","first-next").put("pricePerKg",0).put("firstWeightKg",.1).put("firstWeightPrice",18.4).put("nextWeightKg",.1).put("nextWeightPrice",12);assertThrows(AppException.class,()->engine.calculate(rows,input(.101)));
        r.put("pricingModel","interval").put("intervalPrice",25);assertThrows(AppException.class,()->engine.calculate(rows,input(1)));
        r.put("firstWeightPrice",0);assertThrows(AppException.class,()->engine.calculate(rows,input(1)));}
    @Test void ignoresTraceabilityFieldsButRequiresAnExplicitRealZone(){var r=row(0,5,50).put("zoneName","1区").put("volumetric",true).put("notes","保留原文").put("pendingReason","不参与当前计费");var rows=mapper.createArrayNode().add(r);
        var in=input(.1);in.putObject("dimensions").put("lengthCm",200).put("widthCm",200).put("heightCm",150).put("volumeDivisor",1);assertEquals(15,engine.calculate(rows,in).path("total").asDouble());assertEquals(.1,engine.calculate(rows,in).path("chargeWeightKg").asDouble());
        rows.add(row(0,5,80).put("zoneName","2区"));assertThrows(AppException.class,()->engine.calculate(rows,input(1)));
        var zone=input(1).put("zoneName","澳大利亚2区");assertEquals(90,engine.calculate(rows,zone).path("total").asDouble());
        ((ObjectNode)rows.get(0)).put("zoneName","1区/3区");zone.put("zoneName","澳大利亚3区");assertEquals(60,engine.calculate(rows,zone).path("total").asDouble());
        rows.add(rows.get(0).deepCopy());assertEquals(60,engine.calculate(rows,zone).path("total").asDouble());((ObjectNode)rows.get(2)).put("pricePerKg",99);assertThrows(AppException.class,()->engine.calculate(rows,zone));}
    @Test void blocksDifferentOriginPricesUntilTheOriginIsExplicitlySupported(){var rows=mapper.createArrayNode().add(row(0,1,50).put("originRegion","华南")).add(row(0,1,60).put("originRegion","华东"));assertTrue(engine.unsupported(rows).contains("同一国家、重量和分区存在多套价格，需要明确报价区域"));assertThrows(AppException.class,()->engine.calculate(rows,input(.5)));}
    @Test void usesChannelLevelEligibilityAndStillRejectsInvalidNumbers(){var rows=mapper.createArrayNode().add(row(0,5,50).put("prohibitedMarks","带电"));var in=input(1);in.putArray("marks").add("带电");assertEquals(60,engine.calculate(rows,in).path("total").asDouble());in.putArray("marks");assertEquals(60,engine.calculate(rows,in).path("total").asDouble());assertThrows(AppException.class,()->engine.calculate(rows,input(-1)));}
}
