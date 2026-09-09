package com.milano.quotation.logistics;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class LogisticsSurchargeGuardTest {
  private final JsonMapper mapper = new JsonMapper();
  @Test void verifiesCountryChannelAndRejectsStaleOrForgedExemption() {
    var settings = mapper.readTree("""
      {"countries":[{"country":"NZ","selected":true,"enabled":true,"fixedFeeUsd":1.5,"exemptChannelKeys":["1::P::A"]},
      {"country":"GB","selected":true,"enabled":true,"fixedFeeUsd":0.5,"exemptChannelKeys":[]}]}
      """);
    var option = mapper.createObjectNode().put("country","NZ").put("channelKey","1::P::A").put("surchargeUsd",0).put("surchargeConfigured",true).put("surchargeEnabled",true).put("surchargeExempt",true);
    assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    option.put("country","GB");
    assertThrows(AppException.class, () -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    option.put("surchargeUsd",0.5).put("surchargeExempt",false);
    assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    option.put("country","NZ").put("channelKey","1::P::B");
    assertThrows(AppException.class, () -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    option.put("surchargeUsd",1.5);
    assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
  }
}
