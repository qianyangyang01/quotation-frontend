package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** One statement snapshot: concurrent saves cannot shift records between pages. */
@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationAnalyticsController {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public QuotationAnalyticsController(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public record Snapshot(List<JsonNode> items,int total) {}
    @GetMapping("/analytics-records")
    @PreAuthorize("hasAuthority('PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<Snapshot> snapshot() {
        var items=jdbc.sql("""
            SELECT (coalesce((SELECT jsonb_object_agg(key,value) FROM jsonb_each(q.payload)
              WHERE key=ANY(ARRAY['primarySku','customerName','productSummary','salespersonName','salespersonAccount',
                'country','systemQuoteUsd','systemQuoteCny','totalCostCny','exchangeRate','no','status','createdAt','updatedAt'])), '{}'::jsonb)
              || jsonb_build_object('id',q.id,
                'quoteOptions',coalesce((SELECT jsonb_agg(jsonb_build_object('country',option->'country'))
                  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(q.payload->'quoteOptions')='array'
                    THEN q.payload->'quoteOptions'
                    WHEN jsonb_typeof(q.payload->'specifiedQuotes')='array' THEN q.payload->'specifiedQuotes'
                    ELSE '[]'::jsonb END) option),'[]'::jsonb)))::text
            FROM quotation_record q WHERE q.lifecycle_state<>'trashed' ORDER BY q.created_at DESC,q.id
            """).query(String.class).list().stream().map(mapper::readTree).toList();
        return ApiResponse.ok(new Snapshot(items,items.size()));
    }
}
