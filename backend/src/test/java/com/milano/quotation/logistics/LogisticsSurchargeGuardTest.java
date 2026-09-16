package com.milano.quotation.logistics;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class LogisticsSurchargeGuardTest {
  private final JsonMapper mapper = new JsonMapper();
  @Test void aliasesMustNotBypassSurchargeOrBreakExemptions() {
    var settings = mapper.readTree("""
      {"countries":[{"country":"阿联酋","selected":true,"enabled":true,"fixedFeeUsd":1.5,"exemptChannelKeys":["1::P::A"]}]}
      """);
    for (var country : new String[]{"AE", "阿联酋", "阿拉伯联合酋长国"}) {
      var option = mapper.createObjectNode().put("country",country).put("channelKey","1::P::B").put("surchargeUsd",1.5).put("surchargeConfigured",true).put("surchargeEnabled",true).put("surchargeExempt",false);
      assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
      option.put("surchargeUsd",0).put("surchargeEnabled",false);
      assertThrows(AppException.class, () -> LogisticsQuotationGuard.validateSurcharge(settings, option));
      option.put("channelKey","1::P::A").put("surchargeEnabled",true).put("surchargeExempt",true);
      assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    }
  }
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
  @Test void countryProvidersApplyToAllTheirChannelsOnlyInThatCountry() {
    var settings = mapper.readTree("""
      {"countries":[{"country":"NZ","selected":true,"enabled":true,"fixedFeeUsd":1.5,"providers":[{"provider":"P","selected":true,"mode":"exempt"}]},
      {"country":"GB","selected":true,"enabled":true,"fixedFeeUsd":0.5,"providers":[{"provider":"P","selected":true,"mode":"taxable"}]}]}
      """);
    for (var key : new String[]{"1::P::A", "2::P::B"}) {
      var option = mapper.createObjectNode().put("country","NZ").put("channelKey",key).put("surchargeUsd",0).put("surchargeConfigured",true).put("surchargeEnabled",true).put("surchargeExempt",true);
      assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
      option.put("country","GB");
      assertThrows(AppException.class, () -> LogisticsQuotationGuard.validateSurcharge(settings, option));
      option.put("surchargeUsd",0.5).put("surchargeExempt",false);
      assertDoesNotThrow(() -> LogisticsQuotationGuard.validateSurcharge(settings, option));
    }
  }
}
