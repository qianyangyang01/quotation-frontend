package com.milano.quotation.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class FinanceLogisticsAttributesTest {
    @Test void acceptsDistinctCosmeticsAndSupplementsPoliciesWithoutChangingThePayload() {
        var mapper = new ObjectMapper();
        var policies = mapper.createArrayNode();
        for (var attribute : new String[]{"普货", "化妆品", "保健品", "非液体化妆品"}) {
            var p = policies.addObject().put("category", attribute).put("enabled", true);
            p.putArray("countryRules").addObject().put("country", "澳大利亚").putArray("allowedChannels").add("1::测试物流::C1");
        }
        var before = policies.deepCopy();
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("channel-policies", policies));
        assertEquals(before, policies);
    }
}
