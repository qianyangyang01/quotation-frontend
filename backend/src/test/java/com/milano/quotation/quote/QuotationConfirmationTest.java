package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class QuotationConfirmationTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode object(String text) { return (ObjectNode) mapper.readTree(text); }
    @Test void confirmationIsIndependentOfDealAndReorderingDoesNotInvalidateIt() {
        var current=object("{\"status\":\"won\",\"quoteConfirmed\":true,\"customerQuote\":{\"quantities\":[1,2],\"rows\":[{\"optionId\":\"a\",\"prices\":[2,3]}]}}");
        var patch=object("{\"customerQuote\":{\"quantities\":[2,1],\"rows\":[{\"optionId\":\"a\",\"prices\":[3.0,2.0]}]}}");
        QuotationConfirmation.prepare(current,patch);assertFalse(patch.has("quoteConfirmed"));
        patch=object("{\"customerQuote\":{\"quantities\":[1,2],\"rows\":[{\"optionId\":\"a\",\"prices\":[2,null]}]}}");
        QuotationConfirmation.prepare(current,patch);assertFalse(patch.path("quoteConfirmed").asBoolean(true));
        assertEquals("won",current.path("status").asText());assertFalse(patch.has("status"));
    }
    @Test void rejectsForgedMetadataAndCombinedConfirmAndEdit() {
        for(var json:java.util.List.of("{\"quoteConfirmed\":\"true\"}","{\"quoteConfirmed\":false}","{\"quoteConfirmedAt\":\"fake\"}","{\"quoteConfirmedBy\":\"other\"}","{\"quoteConfirmed\":true,\"customerQuote\":{}}"))
            assertThrows(RuntimeException.class,()->QuotationConfirmation.prepare(object("{}"),object(json)));
        var confirm=object("{\"quoteConfirmed\":true}");QuotationConfirmation.prepare(object("{}"),confirm);assertTrue(confirm.path("quoteConfirmed").asBoolean());
    }
    @Test void unchangedLegacySystemPricesDoNotCancelConfirmation() {
        var current=object("{\"quoteConfirmed\":true,\"quoteOptions\":[{\"id\":\"a\",\"quote1Usd\":2,\"quote2Usd\":3}]}");
        var patch=object("{\"customerQuote\":{\"quantities\":[1,2,3,0],\"rows\":[{\"optionId\":\"a\",\"prices\":[2,3,null,null]}]}}");
        QuotationConfirmation.prepare(current,patch);assertFalse(patch.has("quoteConfirmed"));
    }
}
