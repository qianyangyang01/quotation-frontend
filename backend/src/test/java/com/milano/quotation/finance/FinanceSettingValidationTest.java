package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSettingValidationTest {
    @Test void validatesSurchargeAmountsAndCountryChannelExemptions() {
        var valid = """
                {"countries":[{"country":"美国","fixedFeeUsd":5,"surchargeFeeUsd":2,"surchargeEnabled":true}],
                 "providers":[],
                 "channelFees":[{"country":"美国","channelKey":"1::物流::A","taxMode":"taxable","surchargeMode":"exempt"}]}
                """;
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("tax-settings", mapper.readTree(valid)));
        for (var invalid : new String[]{
                valid.replace("\"surchargeFeeUsd\":2", "\"surchargeFeeUsd\":-1"),
                valid.replace("\"surchargeEnabled\":true", "\"surchargeEnabled\":\"yes\""),
                valid.replace("\"surchargeMode\":\"exempt\"", "\"surchargeMode\":\"invalid\""),
                valid.replace("\"channelKey\":\"1::物流::A\"", "\"channelKey\":\"\"")})
            assertThrows(AppException.class, () -> FinanceSettingValidation.validate("tax-settings", mapper.readTree(invalid)));
    }
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
}
