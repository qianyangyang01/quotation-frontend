package com.milano.quotation.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class FinanceLogisticsAttributesTest {
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="attribute.matrix",matches=".+")
    void validatesExactPreparedProductionPolicies() throws Exception {
        var payload = new ObjectMapper().readTree(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("attribute.matrix"))));
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("channel-policies", payload));
        for (var attribute : new String[]{"以色列自提", "以色列到门"}) {
            var policy = payload.valueStream().filter(p -> p.path("category").asText().equals(attribute)).findFirst().orElseThrow();
            assertTrue(policy.path("enabled").asBoolean());
            for (var rule : policy.path("countryRules")) if (!rule.path("allowedChannels").isEmpty()) assertEquals("以色列", rule.path("country").asText());
        }
    }
    @Test void acceptsDistinctCosmeticsAndSupplementsPoliciesWithoutChangingThePayload() {
        var mapper = new ObjectMapper();
        var policies = mapper.createArrayNode();
        for (var attribute : new String[]{"普货", "化妆品", "保健品", "非液体化妆品", "以色列自提", "以色列到门"}) {
            var p = policies.addObject().put("category", attribute).put("enabled", true);
            p.putArray("countryRules").addObject().put("country", "澳大利亚").putArray("allowedChannels").add("1::测试物流::C1");
        }
        var before = policies.deepCopy();
        assertDoesNotThrow(() -> FinanceSettingValidation.validate("channel-policies", policies));
        assertEquals(before, policies);
    }
}
