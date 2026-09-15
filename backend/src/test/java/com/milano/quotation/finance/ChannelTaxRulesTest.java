package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ChannelTaxRulesTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode rule(String mode) { return mapper.createObjectNode().put("key","1::燕文::C-1").put("mode",mode).put("currency","USD").put("amount",0.3).put("perKg",0); }
    ObjectNode settings(ObjectNode rule, String country) {
        var settings=mapper.createObjectNode();settings.putArray("providers");
        var row=settings.putArray("countries").addObject().put("country",country).put("selected",true).put("enabled",true).put("fixedFeeUsd",5);
        row.putArray("channelRules").add(rule);return settings;
    }
    ObjectNode exchange() { return mapper.createObjectNode().put("usdCny",6.7).put("eurUsd",1.16); }
    ObjectNode option(ObjectNode rule, BigDecimal fee) {
        var mode=rule.path("mode").asText();
        var option=mapper.createObjectNode().put("country","US").put("channelKey","1::燕文::C-1").put("taxConfigured",true).put("taxIncluded",mode.equals("exempt")).put("taxFeeMode",mode.equals("weight")?"weight-order":mode).put("countryFixedTaxUsd",mode.equals("fixed-order")?fee:BigDecimal.ZERO);
        var snapshots=option.putObject("taxCalculations");
        for (String q:new String[]{"1","2","3","5"}) snapshots.putObject(q).put("rule","channel-tax-v1").put("weightKg",0.755).put("taxUsd",fee).set("setting",rule.deepCopy());
        for(String name:new String[]{"tax1Usd","tax2Usd","tax3Usd","taxCustomUsd"}) option.put(name,fee);
        var samples=option.putArray("logisticsSamples");for(int q:new int[]{1,2,3,5}) samples.addObject().put("quantity",q).put("total",10).putObject("input").put("weightKg",0.755);
        return option;
    }
    @Test void fixedDutyOverridesOldCountryPriceAndDetectsConcurrentChanges() {
        var rule=rule("fixed-order");var settings=settings(rule,"美国");var option=option(rule,new BigDecimal("0.30"));
        assertTrue(ChannelTaxRules.validateQuote(settings,option,exchange(),5));
        rule.put("amount",0.4);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(settings,option,exchange(),5));
    }
    @Test void zeroIncludedAndUnavailableRemainDistinctAndCountryScoped() {
        for (String mode:new String[]{"no-tax","exempt"}) {var rule=rule(mode);assertTrue(ChannelTaxRules.validateQuote(settings(rule,"美国"),option(rule,BigDecimal.ZERO),exchange(),5));}
        var rule=rule("unavailable");var option=option(rule,BigDecimal.ZERO);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(settings(rule,"美国"),option,exchange(),5));
        option.put("country","英国");assertFalse(ChannelTaxRules.validateQuote(settings(rule,"美国"),option,exchange(),5));
    }
    @Test void euroWeightFormulaUsesSharedExchangeAndChecksEveryQuantitySnapshot() {
        var rule=rule("weight").put("amount",0.6).put("perKg",1.5).put("currency","EUR");
        var option=option(rule,new BigDecimal("2.01")).put("country","德国");var settings=settings(rule,"欧盟");
        assertTrue(ChannelTaxRules.validateQuote(settings,option,exchange(),5));
        option.put("taxCustomUsd",1.01);assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(settings,option,exchange(),5));
        option.put("taxCustomUsd",2.01);assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(settings,option,exchange().put("eurUsd",1.2),5));
        ((ObjectNode)option.path("taxCalculations").path("5")).put("weightKg",0.1);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(settings,option,exchange(),5));
    }
    @Test void invalidRulesAndDuplicateChannelsCannotBeSaved() {
        var rule=rule("fixed-order");var country=(ObjectNode)settings(rule,"美国").path("countries").get(0);
        ChannelTaxRules.validateSettings(country);
        country.withArray("channelRules").add(rule.deepCopy());assertThrows(AppException.class,()->ChannelTaxRules.validateSettings(country));
        for (var field:new String[]{"amount","perKg"}) {var invalid=rule("fixed-order").put(field,-1);assertThrows(AppException.class,()->ChannelTaxRules.validateSettings(settings(invalid,"美国").path("countries").get(0)));}
        assertEquals(new BigDecimal("3.44"),ChannelTaxRules.amount(rule("fixed-order").put("amount",23.05).put("currency","CNY"),BigDecimal.ZERO,exchange()));
    }
}
