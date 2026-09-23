package com.milano.quotation.purchase;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Reuses only search text. Every lookup validates all row identities/versions
 * against the caller's repeatable-read snapshot, including external imports,
 * deletes, SKU renames and timestamp-only updates. Prices are always read fresh. */
@Component
public class PurchaseSearchIndex {
    private final JdbcClient jdbc;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private record Header(UUID id, String sku, long version, Instant updatedAt) {}
    private record Entry(Header header, String skuText, String payloadText) {}
    public record Selection(List<UUID> ids, long total) {}
    public PurchaseSearchIndex(JdbcClient jdbc) { this.jdbc=jdbc; }

    public synchronized Selection select(String query, long offset, int limit) {
        var headers=jdbc.sql("SELECT id,sku,version,updated_at FROM purchase_product ORDER BY updated_at DESC,id")
                .query((rs,n)->new Header(rs.getObject("id",UUID.class),rs.getString("sku"),rs.getLong("version"),rs.getTimestamp("updated_at").toInstant())).list();
        var current=new HashMap<UUID,Header>();
        var changed=new ArrayList<UUID>();
        for(var h:headers) {
            current.put(h.id(),h);
            var existing=entries.get(h.id());
            if(existing==null||!existing.header().equals(h)) changed.add(h.id());
        }
        entries.keySet().retainAll(current.keySet());
        for(int start=0;start<changed.size();start+=500) {
            jdbc.sql("SELECT id,lower(sku) AS sku_text,lower(payload::text) AS payload_text FROM purchase_product WHERE id IN (:ids)")
                    .param("ids",changed.subList(start,Math.min(start+500,changed.size())))
                    .query((rs,n)->new Entry(current.get(rs.getObject("id",UUID.class)),rs.getString("sku_text"),rs.getString("payload_text")))
                    .list().forEach(entry->entries.put(entry.header().id(),entry));
        }
        var needle=jdbc.sql("SELECT lower(:query)").param("query",query).query(String.class).single();
        var selected=new ArrayList<UUID>();
        long total=0;
        for(var h:headers) {
            var entry=Objects.requireNonNull(entries.get(h.id()),"Search snapshot changed during refresh");
            if(entry.skuText().contains(needle)||entry.payloadText().contains(needle)) {
                if(total>=offset&&selected.size()<limit)selected.add(h.id());
                total++;
            }
        }
        return new Selection(List.copyOf(selected),total);
    }
}
