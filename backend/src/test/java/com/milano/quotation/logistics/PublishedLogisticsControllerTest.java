package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishedLogisticsControllerTest {
    @Test
    void supportsConditionalManifestAndRulesResponses() {
        var service = mock(LogisticsQueryService.class);
        var revision = new LogisticsQueryService.ManifestRevision("revision-1", 1);
        var manifest = new LogisticsQueryService.PublishedManifest("revision-1", Instant.parse("2026-08-24T00:00:00Z"), 1, List.of(), List.of("普货"));
        var rules = new LogisticsQueryService.PublishedRules("revision-1", List.of());
        when(service.manifestRevision()).thenReturn(revision);
        when(service.manifest(revision)).thenReturn(manifest);
        when(service.publishedRules("revision-1", "普货", List.of("美国"), null)).thenReturn(rules);
        when(service.publishedCatalog("revision-1")).thenReturn(rules);
        var controller = new PublishedLogisticsController(service);

        var fresh = controller.manifest(null);
        var unchanged = controller.manifest("\"revision-1\"");
        var ruleResponse = controller.rules("revision-1", "普货", List.of("美国"), null, null);
        var catalogResponse = controller.catalog("revision-1", null);

        assertEquals(200, fresh.getStatusCode().value());
        assertEquals("\"revision-1\"", fresh.getHeaders().getETag());
        assertNotNull(fresh.getBody());
        assertEquals(304, unchanged.getStatusCode().value());
        verify(service, times(1)).manifest(revision);
        assertEquals(200, ruleResponse.getStatusCode().value());
        assertNotNull(ruleResponse.getHeaders().getETag());
        assertEquals(200, catalogResponse.getStatusCode().value());
        assertNotNull(catalogResponse.getHeaders().getETag());
    }
}
