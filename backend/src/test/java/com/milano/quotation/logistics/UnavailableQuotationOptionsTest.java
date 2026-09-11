package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class UnavailableQuotationOptionsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode quotation() {
        var root = mapper.createObjectNode();
        var options = root.putArray("quoteOptions");
        options.addObject().put("quote1Usd", 12);
        var missing = options.addObject().put("available", false);
        for (String field : new String[]{"quote1Usd", "quote2Usd", "quote3Usd", "quoteCustomUsd"}) missing.putNull(field);
        return root;
    }
    @Test void acceptsDisplayOnlyRowsAlongsideValidQuotes() {
        assertDoesNotThrow(() -> LogisticsQuotationGuard.validateUnavailableOptions(quotation()));
    }
    @Test void rejectsForgedUnavailablePricesAndPrimary() {
        var root = quotation();
        var missing = (ObjectNode) root.path("quoteOptions").get(1);
        missing.put("quote2Usd", 0);
        assertThrows(RuntimeException.class, () -> LogisticsQuotationGuard.validateUnavailableOptions(root));
        missing.putNull("quote2Usd").put("isPrimary", true);
        assertThrows(RuntimeException.class, () -> LogisticsQuotationGuard.validateUnavailableOptions(root));
    }
    @Test void rejectsQuotesWithNoValidChannel() {
        var root = quotation();
        ((tools.jackson.databind.node.ArrayNode)root.path("quoteOptions")).remove(0);
        assertThrows(RuntimeException.class, () -> LogisticsQuotationGuard.validateUnavailableOptions(root));
    }
}
