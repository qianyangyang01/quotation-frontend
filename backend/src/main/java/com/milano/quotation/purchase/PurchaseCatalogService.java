package com.milano.quotation.purchase;

import com.milano.quotation.common.PageResponse;
import com.milano.quotation.fob.FobPurchaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;

/** Read-only procurement catalog. Quotation lookup and all writes retain their source-specific APIs. */
@Service
public class PurchaseCatalogService {
    private final JdbcClient jdbc;
    private final PurchaseProductRepository ordinary;
    private final PurchaseProductService products;
    private final FobPurchaseRepository fob;
    public PurchaseCatalogService(JdbcClient jdbc, PurchaseProductRepository ordinary, PurchaseProductService products, FobPurchaseRepository fob) {
        this.jdbc=jdbc; this.ordinary=ordinary; this.products=products; this.fob=fob;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<JsonNode> page(String query,int page,int size,boolean includeFob) {
        String cleaned=query==null?"":query.trim();
        String sku=Normalizer.normalize(cleaned,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s\\u200b]+","");
        // Keep exact SKU precedence, but check both stores before restricting the search.
        boolean exact=!sku.isEmpty() && sku.length()<=96 && jdbc.sql("""
            select exists(select 1 from purchase_product where sku=:sku)
                or (:fob and exists(select 1 from fob_purchase_product where sku=:sku))
            """).param("sku",sku).param("fob",includeFob).query(Boolean.class).single();
        String filter=exact?"sku=:sku":cleaned.isEmpty()?"true":"(lower(sku) like :pattern or lower(payload::text) like :pattern)";
        // Only metadata is materialized. Fetch full payloads for the selected page in two batches.
        var selected=jdbc.sql("""
            with matches as materialized (
              select 'purchase' as kind,sku,updated_at from purchase_product where %s
              union all
              select 'fob' as kind,sku,updated_at from fob_purchase_product where :fob and %s
            ), selected as (
              select * from matches order by updated_at desc,sku,kind limit :limit offset :offset
            )
            select selected.kind,selected.sku,(select count(*) from matches) as total
            from (select 1) anchor left join selected on true order by selected.updated_at desc,selected.sku,selected.kind
            """.formatted(filter,filter)).param("sku",sku).param("pattern","%"+cleaned.toLowerCase(Locale.ROOT)+"%")
                .param("fob",includeFob).param("limit",size).param("offset",(long)page*size)
                .query((rs,n)->new Match(rs.getString("kind"),rs.getString("sku"),rs.getLong("total"))).list();
        var values=new HashMap<String,JsonNode>();
        var ordinarySkus=selected.stream().filter(m->"purchase".equals(m.kind)).map(Match::sku).toList();
        if(!ordinarySkus.isEmpty()) for(var row:ordinary.findAllBySkuIn(ordinarySkus)) values.put("purchase:"+row.sku,products.view(row));
        var fobSkus=selected.stream().filter(m->"fob".equals(m.kind)).map(Match::sku).toList();
        if(!fobSkus.isEmpty()) for(var row:fob.findBySkuIn(fobSkus)) {
            var value=(ObjectNode)row.payload.deepCopy();
            value.put("sku",row.sku).put("dataSource","fob").put("version",row.version).put("updatedAt",row.updatedAt.toString());
            values.put("fob:"+row.sku,value);
        }
        long total=selected.getFirst().total;
        var items=selected.stream().filter(m->m.sku!=null).map(m->Objects.requireNonNull(values.get(m.kind+":"+m.sku))).toList();
        return new PageResponse<>(items,page,size,total,(int)Math.ceil((double)total/size));
    }
    private record Match(String kind,String sku,long total) {}
}
