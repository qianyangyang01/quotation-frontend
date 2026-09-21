package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsPiecePricingTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    ObjectNode price(){return mapper.createObjectNode().put("countryCode","US").put("pricingModel",LogisticsPiecePricing.MODEL)
        .put("weightFromKg",0).put("weightToKg",.5).put("weightFromInclusive",false).put("weightToInclusive",true)
        .put("minChargeWeightKg",.5).put("intervalPrice",91).put("pricePerKg",0).put("registrationFee",3);}
    @Test void minimumAndStepAreAppliedOnceAndFeeIsNotMultiplied(){
        var rows=mapper.createArrayNode().add(price()).add(price().put("weightFromKg",.5).put("weightToKg",1).put("intervalPrice",121));
        for(double actual:new double[]{.000001,.012,.499999,.5,.500001,.501,.999999,1}) {
            var result=engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",actual));
            assertEquals(actual<=.5?94:124,result.path("total").asDouble());
            assertEquals(actual<=.5?.5:1,result.path("chargeWeightKg").asDouble());
        }
        for(double actual:new double[]{0,-1,1.000001})assertThrows(AppException.class,()->engine.calculate(rows,mapper.createObjectNode().put("country","US").put("weightKg",actual)));
    }
    @Test void failsClosedOnChangedMinimumInvalidStepOrAmbiguousPrice(){
        for(var bad:java.util.List.of(price().put("minChargeWeightKg",.05),price().put("weightToKg",.6),price().put("weightFromInclusive",true),
            price().put("intervalPrice",0),price().put("intervalPrice",-91),price().put("intervalPrice","91"),price().put("pricePerKg",91),
            price().put("pricingModel","interval"),price().put("surcharge",5))) {
            assertThrows(AppException.class,()->engine.calculate(mapper.createArrayNode().add(bad),mapper.createObjectNode().put("country","US").put("weightKg",.1)),bad.toString());
        }
        assertThrows(AppException.class,()->engine.calculate(mapper.createArrayNode().add(price()).add(price().put("intervalPrice",90)),mapper.createObjectNode().put("country","US").put("weightKg",.1)));
    }
}
