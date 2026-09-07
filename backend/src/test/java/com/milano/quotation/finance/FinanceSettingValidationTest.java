package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSettingValidationTest {
    private final JsonMapper mapper = new JsonMapper();
    @Test void exchangeRateMustBePositiveAndNumeric() {
        for (var json : new String[]{"{}", "{\"usdCny\":-1}", "{\"usdCny\":0}", "{\"usdCny\":\"7\"}"})
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("exchange-rate", mapper.readTree(json)));
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("exchange-rate", mapper.readTree("{\"usdCny\":7.2}")));
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("exchange-rate", mapper.readTree("{\"usdToCny\":7.2}")));
    }
    @Test void rejectsDuplicateBindingsAndNegativeRates() {
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("customer-grades", mapper.readTree("[{\"grade\":\"S\",\"coefficient\":-1}]")));
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("country-classification", mapper.readTree("[{\"country\":\"美国\"},{\"country\":\"美国\"}]")));
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("tax-settings", mapper.readTree("{\"countries\":[{\"country\":\"美国\",\"ratePercent\":-1}],\"providers\":[]}")));
    }
    @Test void surchargeValidatesIndependentProviderModes() {
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("surcharge-settings", mapper.readTree("""
            {"countries":[{"country":"美国","fixedFeeUsd":2}],"providers":[{"provider":"递四方","mode":"exempt"}]}
            """)));
        for (var mode : new String[]{"channel", "", "included"}) {
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("surcharge-settings", mapper.readTree("{\"countries\":[],\"providers\":[{\"provider\":\"递四方\",\"mode\":\"" + mode + "\"}]}")));
        }
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("surcharge-settings", mapper.readTree("""
            {"countries":[{"country":"美国","fixedFeeUsd":-2}],"providers":[]}
            """)));
    }
}
