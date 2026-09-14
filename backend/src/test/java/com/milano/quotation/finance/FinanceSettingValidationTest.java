package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSettingValidationTest {
    private final JsonMapper mapper = new JsonMapper();
    @Test void acceptsIndependentNewCustomerCoefficientAndRejectsInvalidValues() {
        var valid = mapper.readTree("""
            [{"grade":"S","coefficient":1.21605,"enabled":true},{"grade":"NEW","coefficient":1.45678,"enabled":true}]
            """);
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("customer-grades", valid));
        for (var coefficient : new String[]{"0", "-1", "null", "\"1.4\""}) {
            var row = mapper.readTree("[{\"grade\":\"NEW\",\"coefficient\":" + coefficient + ",\"enabled\":true}]");
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("customer-grades", row));
        }
        var duplicate = mapper.readTree("""
            [{"grade":"NEW","coefficient":1.3},{"grade":"NEW","coefficient":1.4}]
            """);
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("customer-grades", duplicate));
    }
    @Test void validatesCountryTaxProviders() {
        var payload = mapper.readTree("""
            {"countries":[{"country":"US","fixedFeeUsd":0.3,"providers":[{"provider":"P","mode":"exempt","selected":true}]}],"providers":[]}
            """);
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("tax-settings", payload));
        ((tools.jackson.databind.node.ObjectNode)payload.path("countries").get(0).path("providers").get(0)).put("mode","invalid");
        assertThrows(AppException.class, () -> FinanceSettingValidation.validate("tax-settings", payload));
    }
    @Test void exchangeRateMustBePositiveAndNumeric() {
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("exchange-rate", mapper.readTree("{\"usdCny\":6.7,\"eurUsd\":1.23456}")));
        for (var euro : new String[]{"0", "-1", "null", "\"1.2\"", "true"}) {
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("exchange-rate", mapper.readTree("{\"usdCny\":6.7,\"eurUsd\":" + euro + "}")));
        }
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
    @Test void validatesScopedSurchargeKeys() {
        for (var keys : new String[]{"null", "true", "[1]", "[\"bad\"]", "[\"1::P::A\",\"1::P::A\"]"}) {
            var body = mapper.readTree("{\"countries\":[{\"country\":\"NZ\",\"fixedFeeUsd\":1.5,\"exemptChannelKeys\":"+keys+"}],\"providers\":[]}");
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("surcharge-settings", body));
        }
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("surcharge-settings", mapper.readTree("""
            {"countries":[{"country":"NZ","fixedFeeUsd":1.5,"exemptChannelKeys":[]}],"providers":[]}
            """)));
    }
}
