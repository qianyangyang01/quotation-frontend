package com.milano.quotation.logistics;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class LogisticsCountryTaxGuardTest {
 @Test void removingEuGroupRejectsThePreviouslyCalculatedDuty() {
  var m=new JsonMapper();
  var settings=m.readTree("{\"countries\":[{\"country\":\"欧盟\",\"selected\":false,\"enabled\":false,\"fixedFeeUsd\":0,\"providers\":[]}]}");
  var o=m.createObjectNode().put("country","德国").put("channelKey","1::云途::A").put("taxConfigured",true).put("taxIncluded",false).put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",3.52);
  assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o));
  o.put("taxFeeMode","no-tax").put("countryFixedTaxUsd",0);
  assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
 }
 @Test void validatesEuGroupForEveryMemberAndRejectsStaleQuotes() throws Exception {
  var m = new JsonMapper();
  var settings=m.readTree("{\"countries\":[{\"country\":\"欧盟\",\"selected\":true,\"enabled\":true,\"fixedFeeUsd\":3.52,\"providers\":[{\"provider\":\"云途\",\"selected\":true,\"mode\":\"taxable\"}]}]}");
  try (var source = getClass().getResourceAsStream("/eu-member-states.json")) {
   var members=m.readTree(source); assertEquals(27,members.size());
   for(var member:members) for(var alias:member) {
    var o=m.createObjectNode().put("country",alias.asText()).put("channelKey","1::云途::A").put("taxConfigured",true).put("taxIncluded",false).put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",3.52);
    assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
    o.put("countryFixedTaxUsd",0);
    assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o));
   }
  }
  for(var outside:java.util.List.of("GB","UK","英国","CH","NO","IS","US"))
   assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,m.createObjectNode().put("country",outside)));
 }
 @Test void countryOverridesAndChcWeightRuleHavePriorityOverEuGroup() {
  var m=new JsonMapper();
  var settings=m.readTree("{\"countries\":[{\"country\":\"欧盟\",\"selected\":true,\"enabled\":true,\"fixedFeeUsd\":3.52,\"providers\":[]},{\"country\":\"法国\",\"selected\":true,\"enabled\":true,\"fixedFeeUsd\":1,\"providers\":[{\"provider\":\"云途\",\"selected\":true,\"mode\":\"exempt\"}]}]}");
  var o=m.createObjectNode().put("country","FR").put("channelKey","1::云途::A").put("taxConfigured",true).put("taxIncluded",true).put("taxFeeMode","exempt").put("countryFixedTaxUsd",0);
  assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
  for(var code:java.util.List.of("C-600c364a09421e97a32f","C-f79790fa71c225481346","C-12d8524ab88e7866389a")) {
   o.put("country","德国").put("channelKey","1::云途::"+code).put("taxIncluded",false).put("taxFeeMode","weight-eur");
   assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
   o.put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",3.52);
   assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o));
   o.put("countryFixedTaxUsd",0);
  }
  o.put("channelKey","1::云途::A").put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",3.52);
  assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o)); // Empty EU provider settings block saving.
 }
 @Test void rejectsCrossCountryAndStaleExemptions() {
  var m = new JsonMapper();
  var settings=m.readTree("{\"countries\":[{\"country\":\"US\",\"selected\":true,\"enabled\":true,\"fixedFeeUsd\":0.3,\"providers\":[{\"provider\":\"P\",\"selected\":true,\"mode\":\"taxable\"}]}]}");
  var o=m.createObjectNode().put("country","US").put("channelKey","1::P::A").put("taxConfigured",true).put("taxIncluded",false).put("taxFeeMode","fixed-order").put("countryFixedTaxUsd",0.3);
  assertDoesNotThrow(()->LogisticsQuotationGuard.validateCountryTax(settings,o));
  o.put("taxIncluded",true).put("taxFeeMode","exempt").put("countryFixedTaxUsd",0);
  assertThrows(AppException.class,()->LogisticsQuotationGuard.validateCountryTax(settings,o));
 }
}
