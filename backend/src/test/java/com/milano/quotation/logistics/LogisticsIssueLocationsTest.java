package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsIssueLocationsTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void legacyUnparsedRowsKeepExactSheetAndLineWithoutInventingPriceRows() {
        var payload=(ObjectNode)mapper.readTree("""
            {"rows":[{"sourceSheet":"通邮专线特货","sourceRow":4,"pricePerKg":88}],
             "issues":[{"row":0,"level":"error","field":"未覆盖价格行","message":"原始行号：[24, 25]"}]}
            """);
        var original=payload.path("rows").deepCopy();
        LogisticsIssueLocations.enrich(payload);
        var issue=payload.path("issues").get(0);
        assertEquals("通邮专线特货",issue.path("sourceSheet").asText());
        assertEquals("[24,25]",issue.path("sourceRows").toString());
        assertEquals(original,payload.path("rows"));
        assertEquals("error",issue.path("level").asText());
    }
    @Test void ambiguousSheetsStayUnknownAndExactKeysTakePrecedence() {
        var payload=(ObjectNode)mapper.readTree("""
            {"rows":[{"sourceSheet":"A","sourceRow":4,"rowKey":"a"},{"sourceSheet":"B","sourceRow":4,"rowKey":"b"}],
             "issues":[{"row":4},{"row":4,"rowKey":"b"}]}
            """);
        LogisticsIssueLocations.enrich(payload);
        assertFalse(payload.path("issues").get(0).has("sourceSheet"));
        assertEquals("B",payload.path("issues").get(1).path("sourceSheet").asText());
    }
    @Test void existingStructuredLocationsAndRawEvidenceArePreserved() {
        var payload=(ObjectNode)mapper.readTree("""
            {"rows":[],"issues":[{"row":0,"sourceSheet":"原始表","sourceRows":[8,9],"rawValues":{"A":"原值"}}]}
            """);
        var before=payload.deepCopy();LogisticsIssueLocations.enrich(payload);assertEquals(before,payload);
    }
}
