package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EuHandlingTaxTest {
    @Test void acceptsExplicitlyIncludedDutyAndHandlingButRejectsForgedInclusion() {
        var value=combinedSettings("exempt","exempt");
        var option=combinedOption(BigDecimal.ZERO,BigDecimal.ZERO,false).put("taxIncluded",true).put("taxFeeMode","exempt");
        assertTrue(ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(1).path("handlingRules").get(0)).put("mode","fixed-order");
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
    }
    private final ChannelTaxRulesTest fixtures = new ChannelTaxRulesTest();
    ObjectNode rule(String mode) { return fixtures.rule(mode); }
    ObjectNode settings(ObjectNode rule, String country) { return fixtures.settings(rule, country); }
    ObjectNode option(ObjectNode rule, BigDecimal amount) { return fixtures.option(rule, amount); }
    ObjectNode exchange() { return fixtures.exchange(); }
    ObjectNode combinedSettings(String euMode, String feeMode) {
        var value = settings(rule(euMode).put("amount",3), "欧盟");
        var local = value.withArray("countries").addObject().put("country","罗马尼亚").put("selected",true).put("enabled",true).put("euTaxMode","add-handling");
        local.putArray("channelRules").add(rule("fixed-order").put("amount",7));
        local.putArray("handlingRules").add(rule(feeMode).put("amount",1));
        return value;
    }
    ObjectNode combinedOption(BigDecimal eu, BigDecimal fee, boolean weighted) {
        var value = option(rule(weighted ? "weight" : "fixed-order"), eu.add(fee)).put("country","RO");
        for (var snapshot : value.path("taxCalculations")) {
            var node = (ObjectNode) snapshot;
            node.put("rule","eu-handling-v1").put("country","罗马尼亚").put("channelKey","1::燕文::C-1").put("euTaxUsd",eu).put("handlingFeeUsd",fee);
        }
        return value;
    }
    @Test void combinesCountryFeeOnceForAllQuantityColumnsAndRejectsEitherPriceChanging() {
        var value = combinedSettings("fixed-order","fixed-order");
        var option = combinedOption(new BigDecimal("3"),new BigDecimal("1"),false);
        assertTrue(ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(0).path("channelRules").get(0)).put("amount",4);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(0).path("channelRules").get(0)).put("amount",3);
        ((ObjectNode)value.path("countries").get(1).path("handlingRules").get(0)).put("amount",2);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
    }
    @Test void exemptionsApplyOnlyToTheirOwnComponentAndUnsupportedRoutesAreBlocked() {
        for (var mode : new String[]{"no-tax","exempt"}) {
            assertTrue(ChannelTaxRules.validateQuote(combinedSettings(mode,"fixed-order"),combinedOption(BigDecimal.ZERO,BigDecimal.ONE,false),exchange(),5));
            assertTrue(ChannelTaxRules.validateQuote(combinedSettings("fixed-order",mode),combinedOption(new BigDecimal("3"),BigDecimal.ZERO,false),exchange(),5));
        }
        var option = combinedOption(new BigDecimal("3"),BigDecimal.ONE,false);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(combinedSettings("unavailable","fixed-order"),option,exchange(),5));
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(combinedSettings("fixed-order","unavailable"),option,exchange(),5));
    }
    @Test void currencyWeightAndSnapshotsAreRecomputed() {
        var value = combinedSettings("weight","fixed-order");
        ((ObjectNode)value.path("countries").get(0).path("channelRules").get(0)).put("amount",0.6).put("perKg",1.5).put("currency","EUR");
        ((ObjectNode)value.path("countries").get(1).path("handlingRules").get(0)).put("amount",6.7).put("currency","CNY");
        var option = combinedOption(new BigDecimal("2.01"),BigDecimal.ONE,true);
        assertTrue(ChannelTaxRules.validateQuote(value,option,exchange(),5));
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange().put("usdCny",7),5));
        ((ObjectNode)option.path("taxCalculations").path("5")).put("weightKg",0.1);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
    }
    @Test void removedEuOrChangedModeAndForgedTotalsRequireRepricing() {
        var value = combinedSettings("fixed-order","fixed-order");
        var option = combinedOption(new BigDecimal("3"),BigDecimal.ONE,false);
        ((ObjectNode)value.path("countries").get(0)).put("selected",false);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(0)).put("selected",true);
        ((ObjectNode)value.path("countries").get(1)).put("euTaxMode","override");
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(1)).put("euTaxMode","add-handling");
        option.put("taxCustomUsd",5);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
    }
    @Test void validatesNewSettingsAndPreservesChcDutyWhileAllowingItsSeparateHandling() {
        var local = (ObjectNode)combinedSettings("fixed-order","fixed-order").path("countries").get(1);
        ChannelTaxRules.validateSettings(local);
        local.put("country","美国");
        assertThrows(AppException.class,()->ChannelTaxRules.validateSettings(local));
        local.put("country","罗马尼亚");
        var fee=(ObjectNode)local.path("handlingRules").get(0);
        fee.put("key","1::云途::C-600c364a09421e97a32f");
        ChannelTaxRules.validateSettings(local);
        fee.put("amount",-1);
        assertThrows(AppException.class,()->ChannelTaxRules.validateSettings(local));
    }
    @Test void legacyEuDutyAndEmptyProcessingStillInheritAndRequireProviderConfiguration() {
        var value = combinedSettings("fixed-order","fixed-order");
        var eu = (ObjectNode)value.path("countries").get(0);
        eu.remove("channelRules"); eu.put("fixedFeeUsd",3);
        var option = combinedOption(new BigDecimal("3"),BigDecimal.ONE,false);
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange(),5));
        eu.putArray("providers").addObject().put("selected",true).put("provider","燕文").put("mode","taxable");
        assertTrue(ChannelTaxRules.validateQuote(value,option,exchange(),5));
        ((ObjectNode)value.path("countries").get(1)).remove("handlingRules");
        assertTrue(ChannelTaxRules.validateQuote(value,combinedOption(new BigDecimal("3"),BigDecimal.ZERO,false),exchange(),5));
    }
    @Test void builtInChcFormulaIsAddedToHandlingAndBlockedWithoutValidExchange() {
        var value = combinedSettings("fixed-order","fixed-order");
        var key = "1::云途::C-600c364a09421e97a32f";
        ((ObjectNode)value.path("countries").get(1).path("handlingRules").get(0)).put("key",key);
        var option = combinedOption(new BigDecimal("2.01"),BigDecimal.ONE,true).put("channelKey",key);
        for (var snapshot : option.path("taxCalculations")) ((ObjectNode)snapshot).put("channelKey",key);
        assertTrue(ChannelTaxRules.validateQuote(value,option,exchange(),5));
        assertThrows(AppException.class,()->ChannelTaxRules.validateQuote(value,option,exchange().put("eurUsd",0),5));
    }
}
