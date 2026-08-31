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
    @Test void evaluatesMinimumFirstNextAndFixedPricesWithoutSecondDiscount(){var r=row(0,5,50).put("minChargeWeightKg",.05);var rows=mapper.createArrayNode().add(r);assertEquals(12.5,engine.calculate(rows,input(.001)).path("total").asDouble());
        r.put("pricingModel","first-next").put("pricePerKg",0).put("firstWeightKg",.1).put("firstWeightPrice",18.4).put("nextWeightKg",.1).put("nextWeightPrice",12);assertEquals(40.4,engine.calculate(rows,input(.101)).path("total").asDouble());
        r.put("pricingModel","interval").put("intervalPrice",25);assertThrows(AppException.class,()->engine.calculate(rows,input(1)));
        r.put("firstWeightPrice",0);assertEquals(35,engine.calculate(rows,input(1)).path("total").asDouble());}
    @Test void rejectsUnknownRulesMissingDimensionsZonesAndAmbiguousMatches(){var r=row(0,5,50);var rows=mapper.createArrayNode().add(r);r.put("zoneName","1区");assertThrows(AppException.class,()->engine.calculate(rows,input(1)));r.remove("zoneName");r.put("volumetric",true);assertThrows(AppException.class,()->engine.calculate(rows,input(1)));r.put("volumeDivisor",6000);assertThrows(AppException.class,()->engine.calculate(rows,input(1)));
        var in=input(.1);in.putObject("dimensions").put("lengthCm",20).put("widthCm",20).put("heightCm",15).put("volumeDivisor",999999);assertEquals(60,engine.calculate(rows,in).path("total").asDouble());
        ((ObjectNode)in.path("dimensions")).put("volumeMultiplier",.5);assertThrows(AppException.class,()->engine.calculate(rows,in));((ObjectNode)in.path("dimensions")).remove("volumeMultiplier");
        r.put("notes","半泡特殊规则");assertThrows(AppException.class,()->engine.calculate(rows,in));r.remove("notes");rows.add(r.deepCopy());assertThrows(AppException.class,()->engine.calculate(rows,in));}
    @Test void rejectsMissingAndProhibitedMarksAndInvalidNumbers(){var rows=mapper.createArrayNode().add(row(0,5,50).put("prohibitedMarks","带电"));var in=input(1);in.putArray("marks").add("带电");assertThrows(AppException.class,()->engine.calculate(rows,in));in.putArray("marks");assertThrows(AppException.class,()->engine.calculate(rows,in));assertThrows(AppException.class,()->engine.calculate(rows,input(-1)));}
}
