package com.milano.quotation.logistics;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class LogisticsCountryTaxGuardTest {
 @Test void rejectsCrossCountryAndStaleExemptions() {
  var m = new JsonMapper();
  var settings=m.readTree("{\"countries\":[{\"country\":\"US\",\"selected\":true,\"enabled\":true,\"fixedFeeUsd\":0.3,\"providers\":[{\"provider\":\"P\",\"selected\":true,\"mode\":\"taxable\"}]}]}");
  var o=m.createObjectNode().put("country","US").put("channelKey","1::P::A").put("taxConfigured",true).put("taxIncluded",false).put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",0.3);
  assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
  o.put("taxIncluded",true).put("taxFeeMode","exempt").put("countryFixedTaxUsd",0);
  assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o));
 }
}
