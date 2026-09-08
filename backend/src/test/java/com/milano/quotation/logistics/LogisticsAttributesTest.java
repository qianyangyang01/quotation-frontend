package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsAttributesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void keepsNewAttributesDistinctInPublishedRuleFiltering() {
        for (var attribute : new String[]{"化妆品", "保健品"}) {
            var row = mapper.createObjectNode().put("allowedMarks", "非液体化妆品");
            assertFalse(LogisticsQueryService.eligible(row, attribute));
            row.put("allowedMarks", attribute);
            assertTrue(LogisticsQueryService.eligible(row, attribute));
            row.put("prohibitedMarks", attribute);
            assertFalse(LogisticsQueryService.eligible(row, attribute));
            row.put("allowedMarks", "").put("prohibitedMarks", "");
            assertTrue(LogisticsQueryService.eligible(row, attribute));
        }
    }
    @Test void quotationSubmissionRequiresItsOwnAttributeCountryAndChannelPolicy() {
        for (var attribute : new String[]{"化妆品", "保健品"}) {
            var policies = mapper.createArrayNode();
            var policy = policies.addObject().put("category", "普货").put("enabled", true);
            policy.putArray("countryRules").addObject().put("country", "美国").putArray("allowedChannels").add("1::测试物流::C1");
            assertFalse(LogisticsQuotationGuard.allowed(policies, attribute, "美国", "1::测试物流::C1"));
            policy.put("category", attribute);
            assertTrue(LogisticsQuotationGuard.allowed(policies, attribute, "美国", "1::测试物流::C1"));
            assertFalse(LogisticsQuotationGuard.allowed(policies, attribute, "加拿大", "1::测试物流::C1"));
            assertFalse(LogisticsQuotationGuard.allowed(policies, attribute, "美国", "2::测试物流::C2"));
            policy.put("enabled", false);
            assertFalse(LogisticsQuotationGuard.allowed(policies, attribute, "美国", "1::测试物流::C1"));
        }
    }
}
