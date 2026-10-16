package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only projections: no quotation snapshot, review, or procurement writes. */
@Service
public class PurchaseRankingService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final PurchaseSalesController sales;
    public PurchaseRankingService(JdbcClient jdbc, ObjectMapper mapper, PurchaseSalesController sales) {
        this.jdbc=jdbc; this.mapper=mapper; this.sales=sales;
    }
    public record Completion(String owner, long total, long complete, long pending, double rate) {}
    public record Exceptions(String owner, long count, long abnormalQuotes, long relatedQuotes, double rate) {}
    public record Result(String scope, String source, int days, Instant from, Instant to, String salesPeriod,
                         boolean salesConfigured, long unmatchedSalesSkus, long unclassified, long undated,
                         List<Completion> completion, List<Exceptions> exceptions) {}
    record Product(String sku, String source, String owner, JsonNode data) {}
    record Review(String id, JsonNode refs, JsonNode event) {}
    record Target(String sku, String source, String owner) {}
    record Hit(String record, String sku, String source, String owner, Instant at, boolean abnormal, boolean unclassified) {}

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result get(String scope, String source, int days) {
        if(!Set.of("all","sales").contains(scope) || !Set.of("standard","fob","all").contains(source)
                || !Set.of(0,7,30,90).contains(days)) throw AppException.unprocessable("统计筛选条件无效");
        if(scope.equals("sales") && !sales.configured()) throw AppException.unprocessable("尚未配置销量表，不能统计在售SKU");
        var to=Instant.now(); var from=days==0?Instant.EPOCH:to.minus(days,ChronoUnit.DAYS);
        // Explicit small fields only: image/source text blobs never leave PostgreSQL.
        var products=jdbc.sql("""
            select sku,'standard' as source,jsonb_build_object(
              'quotationOwner',payload->'quotationOwner','weightG',payload->'weightG',
              'minOrderQty',payload->'minOrderQty','purchasePriceCny',payload->'purchasePriceCny',
              'taxIncludedPriceCny',payload->'taxIncludedPriceCny','tier2PriceCny',payload->'tier2PriceCny',
              'tier3PriceCny',payload->'tier3PriceCny','taxPoint',payload->'taxPoint',
              'invoiceType',payload->'invoiceType','singleFreightCny',payload->'singleFreightCny',
              'freeShipping',payload->'freeShipping','dataSource',payload->'dataSource',
              'skuOrigin',payload->'skuOrigin')::text as data from purchase_product
            union all
            select sku,'fob',jsonb_build_object('quotationOwner',payload->'quotationOwner',
              'weightRaw',payload->'weightRaw','parsed',payload->'parsed')::text from fob_purchase_product
            """).query((rs,n)->{var data=mapper.readTree(rs.getString("data"));
                return new Product(rs.getString("sku"),rs.getString("source"),data.path("quotationOwner").asText("").trim(),data);}).list();
        var reviews=jdbc.sql(REVIEW_SQL).query((rs,n)->new Review(rs.getString("id"),
                mapper.readTree(rs.getString("refs")),mapper.readTree(rs.getString("event")))).list();
        return aggregate(scope,source,days,from,to,sales.period(),sales.configured(),new HashSet<>(sales.scopeSkus()),products,reviews);
    }

    // Include only completed reviews. FOB itemReviews contains unchanged copies and must NEVER be counted.
    static final String REVIEW_SQL="""
        with ordinary as (
          select q.id::text as id,q.payload,
            coalesce(r.state,q.payload) as state
          from quotation_record q left join quotation_review r on r.id=q.id
        ), ordinary_events as (
          select id,payload,e from ordinary cross join lateral jsonb_array_elements(
            case when jsonb_array_length(coalesce(state->'history','[]'::jsonb))>0 then state->'history'
            when state->>'financeReviewStatus' in ('approved','rejected','channel-exempt','logistics-exempt') then
              jsonb_build_array(jsonb_build_object('action','legacy-review','after',state->'financeReviewStatus','at',state->'financeReviewedAt'))
            else '[]'::jsonb end) e
          where e->>'action' in ('complete','legacy-review','fob-return','fob-approve')
        )
        select 'quote:'||id as id,
          (case when payload->>'quoteMode' in ('freight-trial','shipping-only') then '[]'::jsonb
           when payload->>'quoteMode'='bundle' then coalesce((select jsonb_agg(jsonb_build_object('sku',b->'sku','source','standard')) from jsonb_array_elements(payload->'bundleItems') b),'[]'::jsonb)
           when payload->>'quoteMode'='fob' and payload#>>'{fob,schemaVersion}'='2' then coalesce((select jsonb_agg(jsonb_build_object('sku',b#>'{product,sku}','source',b#>'{product,source}')) from jsonb_array_elements(payload#>'{fob,items}') b),'[]'::jsonb)
           when payload->>'quoteMode'='fob' then jsonb_build_array(jsonb_build_object('sku',payload#>'{fob,product,sku}','source',payload#>'{fob,product,source}'))
           else jsonb_build_array(jsonb_build_object('sku',payload->'primarySku','source','standard')) end)::text as refs,
          jsonb_build_object('action',e->'action','at',e->'at','after',e->'after',
            'priceException',e->'priceException','itemChanges',e->'itemChanges')::text as event
        from ordinary_events
        union all
        select 'batch:'||b.id::text,
          coalesce((select jsonb_agg(jsonb_build_object('sku',i#>'{input,primarySku}','source','standard')) from jsonb_array_elements(b.payload->'items') i),'[]'::jsonb)::text,
          jsonb_build_object('action','batch-complete','at',e->'at','itemChanges',
            (select jsonb_agg(jsonb_build_object('index',i->'index','sku',i->'sku','status',i#>'{after,status}',
              'priceException',i#>'{after,priceException}')) from jsonb_array_elements(e->'items') i))::text
        from batch_single_quotation b join batch_single_review r on r.record_id=b.id
          cross join lateral jsonb_array_elements(coalesce(r.state->'history','[]'::jsonb)) e
        where e->>'action'='complete'
        """;

    static Result aggregate(String scope,String source,int days,Instant from,Instant to,String period,boolean configured,
                            Set<String> salesSkus,List<Product> products,List<Review> reviews) {
        var catalog=new HashMap<String,Product>();
        var completion=new TreeMap<String,long[]>();
        var matched=new HashSet<String>();
        var bySku=new LinkedHashMap<String,List<Product>>();
        for(var p:products) {
            catalog.put(p.source+":"+p.sku,p);
            if(!selected(scope,source,salesSkus,p.sku,p.source))continue;
            matched.add(p.sku);
            bySku.computeIfAbsent(p.sku,k->new ArrayList<>()).add(p);
        }
        for(var entries:bySku.values()) {
            // One SKU is one denominator entry. Conflicting/missing ownership stays unassigned;
            // all its source records must be complete before the SKU is marked complete.
            var owners=entries.stream().map(Product::owner).collect(java.util.stream.Collectors.toSet());
            var owner=owners.size()==1?owners.iterator().next():"";
            var counts=completion.computeIfAbsent(owner,k->new long[2]);counts[0]++;
            if(entries.stream().allMatch(PurchaseRankingService::complete))counts[1]++;
        }
        var hits=new ArrayList<Hit>();var undated=new HashSet<String>();
        for(var row:reviews) {
            Instant at;
            try { at=Instant.parse(row.event.path("at").asText()); }
            catch(java.time.DateTimeException ex) {
                // Unknown timestamps are never silently assigned to a recent window.
                if(isRejected(row.event) && relevantRefs(row.refs,scope,source,salesSkus))undated.add(row.id);
                continue;
            }
            if(at.isBefore(from)||at.isAfter(to))continue;
            var changes=row.event.path("itemChanges");
            if(changes.isArray()) {
                for(var change:changes) {
                    var index=change.path("index").asInt(-1);
                    var ref=index>=0&&index<row.refs.size()?row.refs.get(index):mapperNode(change.path("sku").asText(),"");
                    collect(hits,row.id,List.of(ref),change.path("priceException"),change.path("status").asText().equals("rejected"),at,catalog,scope,source,salesSkus);
                }
            } else collect(hits,row.id,toList(row.refs),row.event.path("priceException"),isRejected(row.event),at,catalog,scope,source,salesSkus);
        }
        // First classified occurrence inside the selected period supplies immutable historical ownership.
        // A repeated submission or another tier for the same record/SKU is one anomaly.
        hits.sort(Comparator.comparing(Hit::at).thenComparing(Hit::owner));
        var anomalies=new LinkedHashMap<String,Hit>();
        for(var h:hits)if(h.abnormal)anomalies.putIfAbsent(key(h),h);
        var related=new TreeMap<String,Set<String>>();var abnormalQuotes=new HashMap<String,Set<String>>();var counts=new HashMap<String,Long>();
        completion.keySet().forEach(owner->related.put(owner,new HashSet<>()));
        var unclassified=new HashSet<String>();
        for(var h:hits) {
            if(h.unclassified)unclassified.add(h.record);
            var attribution=anomalies.getOrDefault(key(h),h);
            related.computeIfAbsent(attribution.owner,k->new HashSet<>()).add(h.record);
        }
        for(var h:anomalies.values()) {
            counts.merge(h.owner,1L,Long::sum);
            abnormalQuotes.computeIfAbsent(h.owner,k->new HashSet<>()).add(h.record);
        }
        var completeRows=completion.entrySet().stream().map(e->new Completion(e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[0]-e.getValue()[1],percent(e.getValue()[1],e.getValue()[0])))
                .sorted(Comparator.comparingDouble(Completion::rate).reversed().thenComparing(Completion::owner)).toList();
        var exceptionRows=related.entrySet().stream().map(e->{long abnormal=abnormalQuotes.getOrDefault(e.getKey(),Set.of()).size();
            return new Exceptions(e.getKey(),counts.getOrDefault(e.getKey(),0L),abnormal,e.getValue().size(),percent(abnormal,e.getValue().size()));})
                .sorted(Comparator.comparingLong(Exceptions::count).reversed().thenComparing(Exceptions::owner)).toList();
        return new Result(scope,source,days,from,to,period,configured,scope.equals("sales")?salesSkus.stream().filter(s->!matched.contains(s)).count():0,
                unclassified.size(),undated.size(),completeRows,exceptionRows);
    }
    private static boolean isRejected(JsonNode event) { return event.path("after").asText().equals("rejected")||event.path("action").asText().equals("fob-return"); }
    private static List<JsonNode> toList(JsonNode array) {var result=new ArrayList<JsonNode>();array.forEach(result::add);return result;}
    private static JsonNode mapperNode(String sku,String source) {return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("sku",sku).put("source",source);}
    private static boolean relevantRefs(JsonNode refs,String scope,String source,Set<String> skus) {for(var r:refs)if(selected(scope,source,skus,r.path("sku").asText(),r.path("source").asText()))return true;return false;}
    private static void collect(List<Hit> hits,String id,List<JsonNode> refs,JsonNode classification,boolean rejected,Instant at,
                                Map<String,Product> catalog,String scope,String source,Set<String> sales) {
        var targets=new LinkedHashMap<String,Target>();
        if(rejected && classification.path("category").asText().equals("purchase-price")) {
            for(var t:classification.path("targets")) {
                var target=new Target(t.path("sku").asText(),t.path("source").asText(),t.path("procurementOwner").asText("").trim());
                targets.put(target.source+":"+target.sku,target);
            }
        }
        var seen=new HashSet<String>();
        for(var ref:refs) {
            String sku=ref.path("sku").asText(""),src=ref.path("source").asText("");
            if(!selected(scope,source,sales,sku,src))continue;
            String key=src+":"+sku;seen.add(key);
            var target=targets.get(key);var product=catalog.get(key);
            hits.add(new Hit(id,sku,src,target!=null?target.owner:product==null?"":product.owner,at,target!=null,
                    rejected && classification.path("category").asText().isBlank()));
        }
        // Captured source can differ from the original after an explicit FOB resubmission.
        for(var entry:targets.entrySet())if(!seen.contains(entry.getKey())) {
            var t=entry.getValue();if(selected(scope,source,sales,t.sku,t.source))hits.add(new Hit(id,t.sku,t.source,t.owner,at,true,false));
        }
    }
    private static String key(Hit h) {return h.record+":"+h.sku;}
    private static boolean selected(String scope,String source,Set<String> sales,String sku,String actualSource) {
        return !sku.isBlank()&&(source.equals("all")||source.equals(actualSource))&&(!scope.equals("sales")||sales.contains(sku));
    }
    private static double percent(long numerator,long denominator) {return denominator==0?0:Math.round(numerator*1000.0/denominator)/10.0;}
    static boolean complete(Product p) {
        var d=p.data;
        if(p.sku.matches("(?i)^(AUTO-|TESTP|TEST|DEMO|MOCK).*")||d.path("skuOrigin").asText().equals("system"))return false;
        if(p.source.equals("fob"))return !d.path("weightRaw").asText("").isBlank() && d.at("/parsed/priceTiers").isArray()
                && !d.at("/parsed/priceTiers").isEmpty() && positive(d.path("parsed"),"minOrderQty")
                && nonNegative(d.at("/parsed/freight"),"totalFreightCny");
        boolean price=d.path("dataSource").asText().equals("legacy_2026")?positive(d,"purchasePriceCny"):
                List.of("purchasePriceCny","taxIncludedPriceCny","tier2PriceCny","tier3PriceCny").stream().anyMatch(k->nonNegative(d,k));
        return positive(d,"weightG") && positive(d,"minOrderQty") && price && nonNegative(d,"taxPoint") && d.path("taxPoint").asDouble()<=1
                && !d.path("invoiceType").asText("").trim().equals("待确认")
                && (d.path("freeShipping").asText().equals("是")||nonNegative(d,"singleFreightCny"));
    }
    private static boolean nonNegative(JsonNode d,String key) {return d.path(key).isNumber()&&Double.isFinite(d.path(key).asDouble())&&d.path(key).asDouble()>=0;}
    private static boolean positive(JsonNode d,String key) {return nonNegative(d,key)&&d.path(key).asDouble()>0;}
}
