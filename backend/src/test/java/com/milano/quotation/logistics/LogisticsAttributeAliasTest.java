package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class LogisticsAttributeAliasTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void oldBatteryPermissionsMatchNewNameButLiquidNeverMeansClothing() throws Exception {
        var policies=mapper.readTree("[{\"category\":\"纯电池\",\"enabled\":true,\"countryRules\":[{\"country\":\"美国\",\"allowedChannels\":[\"x\"]}]}]");
        assertTrue(LogisticsQuotationGuard.allowed(policies,"纯电","美国","x"));
        assertFalse(LogisticsQuotationGuard.allowed(policies,"服装","美国","x"));
        var both=policies.deepCopy();((tools.jackson.databind.node.ArrayNode)both).add(policies.get(0).deepCopy());
        assertFalse(LogisticsQuotationGuard.allowed(both,"纯电","美国","x"));
        var row=mapper.createObjectNode().put("prohibitedMarks","纯电池");
        assertFalse(LogisticsQueryService.eligible(row,"纯电"));
        row.put("prohibitedMarks","").put("allowedMarks","液体");
        assertFalse(LogisticsQueryService.eligible(row,"服装"));
    }
}
