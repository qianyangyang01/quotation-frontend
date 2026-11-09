package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class IsraelQuotationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void unionsOnlyExplicitIsraelPoliciesWithoutChangingLegacyScopes() {
        var policies = mapper.createArrayNode();
        var pickup = policies.addObject().put("category", "以色列自提").put("enabled", true);
        pickup.putArray("countryRules").addObject().put("country", "以色列").putArray("allowedChannels").add("pickup").add("both");
        var door = policies.addObject().put("category", "以色列到门").put("enabled", true);
        door.putArray("countryRules").addObject().put("country", "IL").putArray("allowedChannels").add("door").add("both");
        var original = policies.toString();
        for (var key : new String[]{"pickup", "door", "both"}) assertTrue(LogisticsQuotationGuard.allowed(policies, "以色列", "以色列", key));
        assertFalse(LogisticsQuotationGuard.allowed(policies, "以色列自提", "IL", "door"));
        assertFalse(LogisticsQuotationGuard.allowed(policies, "以色列", "美国", "pickup"));
        assertFalse(LogisticsQuotationGuard.allowed(policies, "以色列", "IL", "unknown"));
        assertEquals(original, policies.toString());
        door.put("enabled", false);
        assertFalse(LogisticsQuotationGuard.allowed(policies, "以色列", "IL", "door"));
        assertTrue(LogisticsQuotationGuard.allowed(policies, "以色列", "IL", "pickup"));
        policies.add(pickup.deepCopy());
        assertFalse(LogisticsQuotationGuard.allowed(policies, "以色列", "IL", "pickup"));
    }
    @Test void aggregatesPublishedRowsOnlyInIsraelAndHonorsRestrictions() {
        for (var attribute : new String[]{"以色列自提", "以色列到门"}) {
            var row = mapper.createObjectNode().put("countryCode", "IL").put("allowedMarks", attribute);
            assertTrue(LogisticsQueryService.eligible(row, "以色列"));
            row.put("prohibitedMarks", attribute);
            assertFalse(LogisticsQueryService.eligible(row, "以色列"));
            row.put("prohibitedMarks", "").put("countryCode", "US");
            assertFalse(LogisticsQueryService.eligible(row, "以色列"));
        }
    }
}
