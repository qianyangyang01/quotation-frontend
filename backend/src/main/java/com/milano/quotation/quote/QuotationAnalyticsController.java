package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Version-validated projections within one database snapshot. No complete quote,
 * authorization decision or calculation input is served from this analytics cache. */
@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationAnalyticsController {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public synchronized void invalidate(UUID id) { projections.remove(id); }
    private record Header(UUID id,long version,Instant updatedAt) {}
    private record Entry(Header header,JsonNode projection) {}
    private final Map<UUID,Entry> projections=new LinkedHashMap<>(128,.75f,true);
    public QuotationAnalyticsController(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public record Snapshot(List<JsonNode> items,int total) {}
    @GetMapping("/analytics-records")
    @PreAuthorize("hasAuthority('PERM_allRecords')")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public synchronized ApiResponse<Snapshot> snapshot() {
        var headers=jdbc.sql("SELECT id,version,updated_at FROM quotation_record WHERE lifecycle_state IN ('active','archived') ORDER BY created_at DESC,id")
                .query((rs,n)->new Header(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getTimestamp("updated_at").toInstant())).list();
        var current=new HashMap<UUID,Header>();var changed=new ArrayList<UUID>();
        for(var header:headers){
            current.put(header.id(),header);var cached=projections.get(header.id());
            if(cached==null||!cached.header().equals(header))changed.add(header.id());
        }
        projections.keySet().retainAll(current.keySet());
        for(int start=0;start<changed.size();start+=500){
            jdbc.sql("""
            SELECT q.id,(coalesce((SELECT jsonb_object_agg(key,value) FROM jsonb_each(q.payload)
              WHERE key=ANY(ARRAY['primarySku','customerName','productSummary','salespersonName','salespersonAccount',
                'country','systemQuoteUsd','systemQuoteCny','totalCostCny','exchangeRate','no','status','createdAt','updatedAt'])), '{}'::jsonb)
              || jsonb_build_object('id',q.id,
                'quoteOptions',coalesce((SELECT jsonb_agg(jsonb_build_object('country',option->'country'))
                  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(q.payload->'quoteOptions')='array'
                    THEN q.payload->'quoteOptions'
                    WHEN jsonb_typeof(q.payload->'specifiedQuotes')='array' THEN q.payload->'specifiedQuotes'
                    ELSE '[]'::jsonb END) option),'[]'::jsonb)))::text AS projection
            FROM quotation_record q WHERE q.id IN (:ids)
            """).param("ids",changed.subList(start,Math.min(start+500,changed.size())))
                    .query((rs,n)->new Entry(current.get(rs.getObject("id",UUID.class)),mapper.readTree(rs.getString("projection"))))
                    .list().forEach(entry->projections.put(entry.header().id(),entry));
        }
        // Defensive copies keep a response consumer from mutating a cached projection.
        List<JsonNode> items=headers.stream().map(header->(JsonNode)projections.get(header.id()).projection().deepCopy()).toList();
        while(projections.size()>50_000)projections.remove(projections.keySet().iterator().next());
        return ApiResponse.ok(new Snapshot(items,items.size()));
    }
}
