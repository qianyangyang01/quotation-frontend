package com.milano.quotation.finance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class FinanceAttributeNormalizationTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void canonicalizesBatteryOnSaveAndKeepsLiquidIndependent() throws Exception {
        var rows=mapper.readTree("[{\"category\":\"纯电池\",\"countryRules\":[]},{\"category\":\"液体\",\"countryRules\":[]},{\"category\":\"服装\",\"countryRules\":[]}]");
        FinanceSettingValidation.validate("channel-policies",rows);
        assertEquals("纯电",rows.get(0).path("category").asText());
        assertEquals("液体",rows.get(1).path("category").asText());
        assertEquals("服装",rows.get(2).path("category").asText());
    }
    @Test void rejectsDuplicateBatteryAliases() throws Exception {
        var rows=mapper.readTree("[{\"category\":\"纯电池\",\"countryRules\":[]},{\"category\":\"纯电\",\"countryRules\":[]}]");
        assertThrows(com.milano.quotation.common.AppException.class,()->FinanceSettingValidation.validate("channel-policies",rows));
    }
}
