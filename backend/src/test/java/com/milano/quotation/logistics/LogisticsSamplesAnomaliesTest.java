package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSamplesAnomaliesTest {
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode rows = mapper.readTree("[{\"areaName\":\"美国\",\"countryCode\":\"US\",\"zoneName\":\"全国统一\",\"pricingModel\":\"per-kg\",\"weightFromKg\":0,\"weightToKg\":5,\"weightFromInclusive\":false,\"weightToInclusive\":true,\"pricePerKg\":50,\"registrationFee\":10,\"etaMinDays\":6,\"etaMaxDays\":9}]");
    ObjectNode option(double weight, Double total) {
        var o=mapper.createObjectNode().put("quoteRegion","全国统一");
        var s=o.putArray("logisticsSamples").addObject().put("etaMinDays",6).put("etaMaxDays",9);
        s.putObject("input").put("country","美国").put("zoneName","全国统一").put("weightKg",weight);
        if(total==null)s.putNull("total");else s.put("total",total);
        return o;
    }
    void validate(ObjectNode option) throws Throwable {
        var method=LogisticsQuotationGuard.class.getDeclaredMethod("validateSamples",JsonNode.class,JsonNode.class,String.class,String.class);
        method.setAccessible(true);
        try { method.invoke(new LogisticsQuotationGuard(null,null,mapper),rows,option,"普货","美国"); }
        catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void unavailableOverweightTierWithNullPriceIsAccepted(){assertDoesNotThrow(()->validate(option(6.24,null)));}
    @Test void exactlyFiveKgStillQuotes(){assertDoesNotThrow(()->validate(option(5,260.0)));}
    @Test void staleNumericPriceForOverweightIsRejected(){assertThrows(AppException.class,()->validate(option(6.24,322.0)));}
    @Test void falseUnavailableForValidWeightIsRejected(){assertThrows(AppException.class,()->validate(option(3.12,null)));}
    @Test void correctSingleWeightPriceIsAccepted(){assertDoesNotThrow(()->validate(option(3.12,166.0)));}
    @Test void changedPriceIsRejected(){assertThrows(AppException.class,()->validate(option(3.12,167.0)));}
    @Test void changedEtaIsRejected(){var o=option(3.12,166.0);((ObjectNode)o.path("logisticsSamples").get(0)).put("etaMinDays",7);assertThrows(AppException.class,()->validate(o));}
    @Test void mismatchedRegionIsRejected(){var o=option(3.12,166.0);((ObjectNode)o.path("logisticsSamples").get(0).path("input")).put("zoneName","2区");assertThrows(AppException.class,()->validate(o));}
}
