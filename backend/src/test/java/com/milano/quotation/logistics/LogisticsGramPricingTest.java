package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsGramPricingTest {
    @Test void roundsAfterMinimumBeforeChoosingTierAndRetainsOrdinaryKilogramBehavior(){
        var mapper=new ObjectMapper();var engine=new LogisticsBillingEngine(mapper);
        var row=mapper.createObjectNode().put("countryCode","US").put("pricingModel","per-kg-1g").put("weightFromKg",0).put("weightToKg",.1)
            .put("minChargeWeightKg",.05).put("pricePerKg",100).put("registrationFee",10);
        var rows=mapper.createArrayNode().add(row).add(row.deepCopy().put("weightFromKg",.1).put("weightToKg",.2).put("pricePerKg",200));
        for(var pair:new double[][]{{.001,.05,15},{.049999,.05,15},{.05,.05,15},{.050001,.051,15.1},{.1,.1,20},{.100001,.101,30.2},{.101,.101,30.2}}) {
            var result=engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",pair[0]));
            assertEquals(pair[1],result.path("chargeWeightKg").asDouble());assertEquals(pair[2],result.path("total").asDouble());
        }
        var ordinary=row.deepCopy().put("pricingModel","per-kg");
        assertEquals(.050001,engine.calculate(mapper.createArrayNode().add(ordinary),mapper.createObjectNode().put("country","US").put("weightKg",.050001)).path("chargeWeightKg").asDouble());
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",.200001)));
    }
}
