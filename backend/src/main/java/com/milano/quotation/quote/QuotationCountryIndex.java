package com.milano.quotation.quote;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Cache only per-record country extraction, never the permitted record scope.
 * Call within the search transaction's repeatable-read snapshot. */
@Component
public class QuotationCountryIndex {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public synchronized void invalidate(UUID id) { entries.remove(id); }
    private record Header(UUID id,long version,Instant updatedAt) {}
    private record Entry(Header header,Set<String> countries,boolean confirmed) {}
    public record Snapshot(List<String> countries,List<UUID> confirmedIds) {}
    private final Map<UUID,Entry> entries=new LinkedHashMap<>(128,.75f,true);
    public QuotationCountryIndex(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public List<String> countries(String owner,String lifecycle){return snapshot(owner,lifecycle).countries();}
    public synchronized Snapshot snapshot(String owner,String lifecycle){
        var query=jdbc.sql("SELECT id,version,updated_at FROM quotation_record WHERE lifecycle_state=:lifecycle"+(owner==null?"":" AND owner_account=:owner"))
                .param("lifecycle",lifecycle);
        if(owner!=null)query.param("owner",owner);
        var headers=query.query((rs,n)->new Header(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getTimestamp("updated_at").toInstant())).list();
        var current=new HashMap<UUID,Header>();var changed=new ArrayList<UUID>();
        for(var h:headers){current.put(h.id(),h);var cached=entries.get(h.id());if(cached==null||!cached.header().equals(h))changed.add(h.id());}
        for(int start=0;start<changed.size();start+=500){
            jdbc.sql("""
                SELECT id,jsonb_build_object('confirmed',payload->>'quoteConfirmed','country',payload->'country','options',
                  CASE WHEN jsonb_typeof(payload->'quoteOptions')='array'
                  THEN jsonb_path_query_array(payload,'$.quoteOptions[*].country') ELSE '[]'::jsonb END)::text AS countries
                FROM quotation_record WHERE id IN (:ids)
                """).param("ids",changed.subList(start,Math.min(start+500,changed.size())))
                .query((rs,n)->{
                    var json=mapper.readTree(rs.getString("countries"));var countries=new HashSet<String>();
                    if(!json.path("country").isNull())countries.add(json.path("country").asText());
                    json.path("options").forEach(value->{if(!value.isNull())countries.add(value.asText());});
                    countries.remove("");countries.remove("—");
                    return new Entry(current.get(rs.getObject("id",UUID.class)),Set.copyOf(countries),"true".equals(json.path("confirmed").asText()));
                }).list().forEach(entry->entries.put(entry.header().id(),entry));
        }
        var countries=new HashSet<String>();var confirmedIds=new ArrayList<UUID>();
        for(var h:headers){var entry=entries.get(h.id());countries.addAll(entry.countries());if(entry.confirmed())confirmedIds.add(h.id());}
        // Preserve the database's collation, including non-Chinese country labels.
        var result=jdbc.sql("SELECT value FROM jsonb_array_elements_text(CAST(:countries AS jsonb)) AS value ORDER BY value")
                .param("countries",mapper.writeValueAsString(countries)).query(String.class).list();
        while(entries.size()>50_000)entries.remove(entries.keySet().iterator().next());
        return new Snapshot(result,List.copyOf(confirmedIds));
    }
}
