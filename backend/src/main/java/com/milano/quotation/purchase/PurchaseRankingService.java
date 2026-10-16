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
    private final PurchaseRankingCache<CatalogKey, List<Product>> catalogs =
            new PurchaseRankingCache<>(4, java.time.Duration.ofSeconds(60), System::nanoTime);
    private final PurchaseRankingCache<ResultKey, Result> results = new PurchaseRankingCache<>();
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
    private record CatalogKey(String scope, String source, String revision, List<PurchaseRankingOwners.Account> accounts) {}
    private record ResultKey(CatalogKey catalog, String part, int days, String reviews) {}

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result get(String scope, String source, int days) {
        return get(scope, source, days, "all", false);
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result get(String scope, String source, int days, String part, boolean refresh) {
        if(!Set.of("all","sales").contains(scope) || !Set.of("standard","fob","all").contains(source)
                || !Set.of(0,7,30,90).contains(days) || !Set.of("all","completion","exceptions").contains(part))
            throw AppException.unprocessable("统计筛选条件无效");
        if(scope.equals("sales") && !sales.configured()) throw AppException.unprocessable("尚未配置销量表，不能统计在售SKU");
        // These cheap columns also capture role/name changes made without bumping an account version.
        var accounts=jdbc.sql("select account,display_name,role_key from app_user order by account")
                .query((rs,n)->new PurchaseRankingOwners.Account(rs.getString("account"),rs.getString("display_name"),rs.getString("role_key"))).list();
        var owners=new PurchaseRankingOwners(accounts);
        var catalogKey=new CatalogKey(scope,source,catalogRevision(source),List.copyOf(accounts));
        Result completion=null;
        if(!part.equals("exceptions")) completion=part(catalogKey,owners,"completion",0,"",refresh);
        if(part.equals("completion")) return completion;
        var exceptions=part(catalogKey,owners,"exceptions",days,reviewRevision(),refresh);
        if(part.equals("exceptions")) return exceptions;
        return new Result(scope,source,days,exceptions.from,exceptions.to,exceptions.salesPeriod,exceptions.salesConfigured,
                completion.unmatchedSalesSkus,exceptions.unclassified,exceptions.undated,completion.completion,exceptions.exceptions);
    }

    private Result part(CatalogKey key, PurchaseRankingOwners owners, String part, int days, String reviewRevision, boolean refresh) {
        return results.get(new ResultKey(key,part,days,reviewRevision),refresh,()->{
            var to=Instant.now(); var from=days==0?Instant.EPOCH:to.minus(days,ChronoUnit.DAYS);
            var products=catalogs.get(key,false,()->readProducts(key.scope,key.source,owners));
            var reviews=part.equals("completion")?List.<Review>of():readReviews(owners,from,to);
            var result=aggregate(key.scope,key.source,days,from,to,sales.period(),sales.configured(),new HashSet<>(sales.scopeSkus()),products,reviews,
                    part.equals("completion"));
            return new Result(result.scope,result.source,result.days,result.from,result.to,result.salesPeriod,result.salesConfigured,
                    result.unmatchedSalesSkus,result.unclassified,result.undated,
                    part.equals("completion")?result.completion:List.of(),part.equals("exceptions")?result.exceptions:List.of());
        });
    }

    List<Product> readProducts(String scope, String source, PurchaseRankingOwners owners) {
        return jdbc.sql(PRODUCT_SQL).param("source",source).param("scope",scope)
                // A dummy value keeps the JDBC collection nonempty when sales are unconfigured.
                .param("skus",scope.equals("sales")&&!sales.scopeSkus().isEmpty()?sales.scopeSkus():List.of(""))
                .query((rs,n)->{var data=mapper.readTree(rs.getString("data"));
                    return new Product(rs.getString("sku"),rs.getString("source"),owners.resolve(data.path("quotationOwner").asText("")),data);}).list();
    }

    List<Review> readReviews(PurchaseRankingOwners owners, Instant from, Instant to) {
        // PostgreSQL timestamps have microsecond precision. This is only a conservative prefilter;
        // aggregate still uses Instant.parse and exact inclusive nanosecond bounds. Unknown/other
        // timestamp formats are retained for Java parsing and the existing undated diagnostic.
        return jdbc.sql(REVIEW_SQL).param("queryFrom",from.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1).toString())
                .param("queryTo",to.truncatedTo(ChronoUnit.SECONDS).plusSeconds(2).toString())
                .query((rs,n)->new Review(rs.getString("id"),mapper.readTree(rs.getString("refs")),
                        resolveEvent(mapper.readTree(rs.getString("event")),owners))).list();
    }

    private String catalogRevision(String source) {
        return jdbc.sql("""
            select coalesce(md5(string_agg(revision,',' order by source,sku)),'') from (
              select 'standard' as source,sku,sku||':'||xmin::text||':'||version::text as revision
                from purchase_product where :source in ('all','standard')
              union all select 'fob',sku,sku||':'||xmin::text||':'||version::text
                from fob_purchase_product where :source in ('all','fob')
            ) revisions
            """).param("source",source).query(String.class).single();
    }

    private String reviewRevision() {
        // xmin sees every committed row change, including guarded maintenance SQL. Version also
        // distinguishes repeated application edits inside one transaction. No large JSON is read.
        return jdbc.sql("""
            select coalesce(md5(string_agg(revision,',' order by kind,id)),'') from (
              select 'quote' as kind,id::text as id,id::text||':'||xmin::text||':'||version::text as revision from quotation_record
              union all select 'review',id::text,id::text||':'||xmin::text||':'||version::text from quotation_review
              union all select 'batch',id::text,id::text||':'||xmin::text from batch_single_quotation
              union all select 'batch-review',record_id::text,record_id::text||':'||xmin::text||':'||version::text from batch_single_review
            ) revisions
            """).query(String.class).single();
    }

    // Extract all fields from each large payload in one function call, retaining JSON types and
    // missing/null fields exactly. Non-object payloads retain the previous all-null projection.
    static final String PRODUCT_SQL="""
        select p.sku,'standard' as source,to_jsonb(d)::text as data
        from purchase_product p cross join lateral jsonb_to_record(
          case when jsonb_typeof(p.payload)='object' then p.payload else '{}'::jsonb end
        ) as d("quotationOwner" jsonb,"weightG" jsonb,"minOrderQty" jsonb,"purchasePriceCny" jsonb,
          "taxIncludedPriceCny" jsonb,"tier2PriceCny" jsonb,"tier3PriceCny" jsonb,"taxPoint" jsonb,
          "invoiceType" jsonb,"singleFreightCny" jsonb,"freeShipping" jsonb,"dataSource" jsonb,"skuOrigin" jsonb)
        where :source in ('all','standard') and (:scope='all' or p.sku in (:skus))
        union all
        select p.sku,'fob',to_jsonb(d)::text
        from fob_purchase_product p cross join lateral jsonb_to_record(
          case when jsonb_typeof(p.payload)='object' then p.payload else '{}'::jsonb end
        ) as d("quotationOwner" jsonb,"weightRaw" jsonb,"parsed" jsonb)
        where :source in ('all','fob') and (:scope='all' or p.sku in (:skus))
        """;

    static JsonNode resolveEvent(JsonNode event, PurchaseRankingOwners owners) {
        // These are detached read projections, not persisted review history.
        var resolved=event.deepCopy();
        resolveTargets(resolved.path("priceException"),owners);
        for(var change:resolved.path("itemChanges"))resolveTargets(change.path("priceException"),owners);
        return resolved;
    }
    private static void resolveTargets(JsonNode classification, PurchaseRankingOwners owners) {
        for(var target:classification.path("targets"))if(target instanceof tools.jackson.databind.node.ObjectNode object)
            object.put("procurementOwner",owners.resolve(target.path("procurementOwner").asText("")));
    }

    // Include only completed reviews. FOB itemReviews contains unchanged copies and must NEVER be counted.
    static final String REVIEW_SQL="""
        with ordinary as materialized (
          select q.id::text as id,s.history,s."financeReviewStatus",s."financeReviewedAt",
            d."quoteMode",d."primarySku",d."bundleItems",f."schemaVersion",f.items,f.product
          from quotation_record q left join quotation_review r on r.id=q.id
          cross join lateral jsonb_to_record(case when jsonb_typeof(q.payload)='object' then q.payload else '{}'::jsonb end)
            as d("quoteMode" jsonb,"primarySku" jsonb,"bundleItems" jsonb,fob jsonb)
          cross join lateral jsonb_to_record(case when jsonb_typeof(d.fob)='object' then d.fob else '{}'::jsonb end)
            as f("schemaVersion" jsonb,items jsonb,product jsonb)
          cross join lateral jsonb_to_record(case when jsonb_typeof(coalesce(r.state,q.payload))='object'
            then coalesce(r.state,q.payload) else '{}'::jsonb end)
            as s(history jsonb,"financeReviewStatus" jsonb,"financeReviewedAt" jsonb)
        ), ordinary_events as materialized (
          select id,ordinary."quoteMode",ordinary."primarySku",ordinary."bundleItems",
            ordinary."schemaVersion",ordinary.items,ordinary.product,d.*
          from ordinary cross join lateral jsonb_array_elements(
            case when jsonb_array_length(coalesce(history,'[]'::jsonb))>0 then history
            when "financeReviewStatus" #>> '{}' in ('approved','rejected','channel-exempt','logistics-exempt') then
              jsonb_build_array(jsonb_build_object('action','legacy-review','after',"financeReviewStatus",'at',"financeReviewedAt"))
            else '[]'::jsonb end) e
          cross join lateral jsonb_to_record(case when jsonb_typeof(e)='object' then e else '{}'::jsonb end)
            as d(action jsonb,at jsonb,after jsonb,"priceException" jsonb,"itemChanges" jsonb)
          where e->>'action' in ('complete','legacy-review','fob-return','fob-approve')
            and case when d.at #>> '{}' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,9})?Z$'
                and pg_input_is_valid(d.at #>> '{}','timestamp with time zone')
              then (d.at #>> '{}')::timestamptz between cast(:queryFrom as timestamptz) and cast(:queryTo as timestamptz)
              else true end
        ), batches as materialized (
          select b.id,r.state->'history' as history,b.payload->'items' as quote_items
          from batch_single_quotation b join batch_single_review r on r.record_id=b.id
        ), batch_events as materialized (
          select id,quote_items,d.* from batches cross join lateral jsonb_array_elements(coalesce(history,'[]'::jsonb)) e
          cross join lateral jsonb_to_record(case when jsonb_typeof(e)='object' then e else '{}'::jsonb end)
            as d(action jsonb,at jsonb,items jsonb)
          where e->>'action'='complete'
            and case when d.at #>> '{}' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,9})?Z$'
                and pg_input_is_valid(d.at #>> '{}','timestamp with time zone')
              then (d.at #>> '{}')::timestamptz between cast(:queryFrom as timestamptz) and cast(:queryTo as timestamptz)
              else true end
        )
        select 'quote:'||id as id,
          (case when "quoteMode" #>> '{}' in ('freight-trial','shipping-only') then '[]'::jsonb
           when "quoteMode" #>> '{}'='bundle' then coalesce((select jsonb_agg(jsonb_build_object('sku',b->'sku','source','standard')) from jsonb_array_elements("bundleItems") b),'[]'::jsonb)
           when "quoteMode" #>> '{}'='fob' and "schemaVersion" #>> '{}'='2' then coalesce((select jsonb_agg(jsonb_build_object('sku',b#>'{product,sku}','source',b#>'{product,source}')) from jsonb_array_elements(items) b),'[]'::jsonb)
           when "quoteMode" #>> '{}'='fob' then jsonb_build_array(jsonb_build_object('sku',product->'sku','source',product->'source'))
           else jsonb_build_array(jsonb_build_object('sku',"primarySku",'source','standard')) end)::text as refs,
          jsonb_build_object('action',action,'at',at,'after',after,'priceException',"priceException",'itemChanges',"itemChanges")::text as event
        from ordinary_events
        union all
        select 'batch:'||id::text,
          coalesce((select jsonb_agg(jsonb_build_object('sku',i#>'{input,primarySku}','source','standard'))
            from jsonb_array_elements(quote_items) i),'[]'::jsonb)::text,
          jsonb_build_object('action','batch-complete','at',at,'itemChanges',
            (select jsonb_agg(jsonb_build_object('index',i->'index','sku',i->'sku','status',i#>'{after,status}',
              'priceException',i#>'{after,priceException}')) from jsonb_array_elements(items) i))::text
        from batch_events
        """;

    static Result aggregate(String scope,String source,int days,Instant from,Instant to,String period,boolean configured,
                            Set<String> salesSkus,List<Product> products,List<Review> reviews) {
        return aggregate(scope,source,days,from,to,period,configured,salesSkus,products,reviews,true);
    }

    static Result aggregate(String scope,String source,int days,Instant from,Instant to,String period,boolean configured,
                            Set<String> salesSkus,List<Product> products,List<Review> reviews,boolean includeCompletion) {
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
            if(owner.isBlank())continue;
            var counts=completion.computeIfAbsent(owner,k->new long[2]);counts[0]++;
            if(includeCompletion && entries.stream().allMatch(PurchaseRankingService::complete))counts[1]++;
        }
        var hits=new ArrayList<Hit>();var undated=new HashSet<String>();
        for(var row:reviews) {
            Instant at;
            try { at=Instant.parse(row.event.path("at").asText()); }
            catch(java.time.DateTimeException ex) {
                // Unknown timestamps are never silently assigned to a recent window.
                var unknown=new ArrayList<Hit>();
                collectReview(unknown,row,from,catalog,scope,source,salesSkus);
                if(unknown.stream().anyMatch(h->!h.owner.isBlank()&&(h.abnormal||h.unclassified)))undated.add(row.id);
                continue;
            }
            if(at.isBefore(from)||at.isAfter(to))continue;
            collectReview(hits,row,at,catalog,scope,source,salesSkus);
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
            var attribution=anomalies.getOrDefault(key(h),h);
            if(attribution.owner.isBlank())continue;
            if(h.unclassified)unclassified.add(h.record);
            related.computeIfAbsent(attribution.owner,k->new HashSet<>()).add(h.record);
        }
        for(var h:anomalies.values()) {
            if(h.owner.isBlank())continue;
            counts.merge(h.owner,1L,Long::sum);
            abnormalQuotes.computeIfAbsent(h.owner,k->new HashSet<>()).add(h.record);
        }
        var completeRows=includeCompletion?completion.entrySet().stream().map(e->new Completion(e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[0]-e.getValue()[1],percent(e.getValue()[1],e.getValue()[0])))
                .sorted(Comparator.comparingDouble(Completion::rate).reversed().thenComparing(Completion::owner)).toList():List.<Completion>of();
        var exceptionRows=related.entrySet().stream().map(e->{long abnormal=abnormalQuotes.getOrDefault(e.getKey(),Set.of()).size();
            return new Exceptions(e.getKey(),counts.getOrDefault(e.getKey(),0L),abnormal,e.getValue().size(),percent(abnormal,e.getValue().size()));})
                .sorted(Comparator.comparingLong(Exceptions::count).reversed().thenComparing(Exceptions::owner)).toList();
        return new Result(scope,source,days,from,to,period,configured,scope.equals("sales")?salesSkus.stream().filter(s->!matched.contains(s)).count():0,
                unclassified.size(),undated.size(),completeRows,exceptionRows);
    }
    private static boolean isRejected(JsonNode event) { return event.path("after").asText().equals("rejected")||event.path("action").asText().equals("fob-return"); }
    private static List<JsonNode> toList(JsonNode array) {var result=new ArrayList<JsonNode>();array.forEach(result::add);return result;}
    private static JsonNode mapperNode(String sku,String source) {return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("sku",sku).put("source",source);}
    private static void collectReview(List<Hit> hits,Review row,Instant at,Map<String,Product> catalog,String scope,String source,Set<String> salesSkus) {
        var changes=row.event.path("itemChanges");
        if(changes.isArray()) {
            for(var change:changes) {
                var index=change.path("index").asInt(-1);
                var ref=index>=0&&index<row.refs.size()?row.refs.get(index):mapperNode(change.path("sku").asText(),"");
                collect(hits,row.id,List.of(ref),change.path("priceException"),change.path("status").asText().equals("rejected"),at,catalog,scope,source,salesSkus);
            }
        } else collect(hits,row.id,toList(row.refs),row.event.path("priceException"),isRejected(row.event),at,catalog,scope,source,salesSkus);
    }
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
