package com.milano.quotation.logistics;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.UUID;

@Service
public class LogisticsPriceHistoryService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LogisticsPriceHistoryService.class);
    // All display SQL uses at most one connection at a time, with bounded statements.
    private final java.util.concurrent.Semaphore access=new java.util.concurrent.Semaphore(1);
    @org.springframework.beans.factory.annotation.Autowired
    public LogisticsPriceHistoryService(javax.sql.DataSource source,ObjectMapper mapper,LogisticsPriceHistoryCalculator calculator){
        var template=new org.springframework.jdbc.core.JdbcTemplate(source);template.setQueryTimeout(3);
        this.jdbc=JdbcClient.create(template);this.mapper=mapper;this.calculator=calculator;
    }
    private final java.util.Map<String,Boolean> readiness=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(512,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Boolean> entry){return size()>512;}
    });
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final LogisticsPriceHistoryCalculator calculator;
    public LogisticsPriceHistoryService(JdbcClient jdbc,ObjectMapper mapper,LogisticsPriceHistoryCalculator calculator){this.jdbc=jdbc;this.mapper=mapper;this.calculator=calculator;}

    public void prepare(UUID versionId){
        if(!access.tryAcquire())throw unavailable();
        try{prepareVersion(versionId);}finally{access.release();}
    }
    /** Worker-only autocommit writes, never enlisted in a publication transaction. */
    private void prepareVersion(UUID versionId){
        var source=jdbc.sql("""
            select v.rows_fingerprint,v.quote_rows::text from logistics_version v
            left join logistics_price_history_projection h on h.version_id=v.id
            where v.id=:id and v.status in ('published','superseded') and v.published_at is not null
              and (h.version_id is null or h.rows_fingerprint is distinct from v.rows_fingerprint or h.engine_version<>:engine)
            """).param("id",versionId).param("engine",LogisticsPriceHistoryCalculator.VERSION)
            .query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
        if(source.isEmpty())return;
        var fingerprint=source.get()[0];var prices=calculator.calculate(mapper.readTree(source.get()[1]));
        // The identity predicate also protects a background backfill racing with a source correction.
        jdbc.sql("""
            insert into logistics_price_history_projection(version_id,rows_fingerprint,engine_version,prices)
            select id,rows_fingerprint,:engine,cast(:prices as jsonb) from logistics_version
            where id=:id and rows_fingerprint=:fingerprint and status in ('published','superseded')
            on conflict(version_id) do update set rows_fingerprint=excluded.rows_fingerprint,engine_version=excluded.engine_version,
              prices=excluded.prices,calculated_at=now()
            """).param("id",versionId).param("fingerprint",fingerprint).param("engine",LogisticsPriceHistoryCalculator.VERSION).param("prices",prices.toString()).update();
    }

    public void rebuildMissing(){
        boolean acquired=false;
        try{
            acquired=access.tryAcquire(2,java.util.concurrent.TimeUnit.SECONDS);
            if(!acquired)throw unavailable();
            prepareMissing();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        finally{if(acquired)access.release();}
    }
    private static com.milano.quotation.common.AppException unavailable(){
        return new com.milano.quotation.common.AppException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "PRICE_DISPLAY_UNAVAILABLE","价格展示暂不可用或正在更新，请稍后手动刷新；不影响价格发布和正常报价");
    }
    private void prepareMissing(){
        var ids=jdbc.sql("""
            select v.id from logistics_version v join logistics_channel c on c.id=v.channel_id
            left join logistics_price_history_projection h on h.version_id=v.id
            where c.dataset_id=logistics_active_dataset() and c.archived_at is null
              and v.status in ('published','superseded') and v.published_at is not null
              and (h.version_id is null or h.rows_fingerprint is distinct from v.rows_fingerprint or h.engine_version<>:engine)
            """).param("engine",LogisticsPriceHistoryCalculator.VERSION).query(UUID.class).list();
        for(var id:ids){
            if(Thread.currentThread().isInterrupted())return;
            try{prepareVersion(id);}
            catch(org.springframework.dao.DataAccessException failure){throw failure;}
            catch(RuntimeException failure){log.warn("Invalid display source version {}; continuing other versions",id,failure);}
        }
    }

    public ObjectNode snapshot(){
        if(!access.tryAcquire())throw unavailable();
        try{return readSnapshot();}finally{access.release();}
    }
    private ObjectNode readSnapshot(){
        final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var snapshot=jdbc.sql("""
            with channels as (
              select c.*,p.payload->>'name' provider,coalesce((p.payload->>'enabled')::boolean,true) provider_enabled,
                (not (select paused from logistics_company_state where singleton) and
                 (not (select enabled from logistics_company_state where singleton) or c.id in
                  (select b.channel_id from logistics_company_binding b join logistics_company_channel d on d.id=b.company_channel_id where d.enabled))) visible
              from logistics_channel c join logistics_provider p on p.id=c.provider_id
              where c.dataset_id=logistics_active_dataset() and c.archived_at is null
            ), state as (
              select jsonb_build_object('dataset',logistics_active_dataset(),
                'pendingVersions',(select count(*) from logistics_version v join channels c on c.id=v.channel_id
                  left join logistics_price_history_projection h on h.version_id=v.id and h.rows_fingerprint=v.rows_fingerprint and h.engine_version=:engine
                  where v.status in ('published','superseded') and v.published_at is not null and h.version_id is null),
                'channels',coalesce((select jsonb_agg(jsonb_build_object('id',c.id,'ruleId',c.rule_id,'provider',c.provider,'code',c.code,
                  'current',c.current_version_id,'name',c.payload->>'name','attribute',c.payload->>'logisticsAttribute',
                  'enabled',coalesce((c.payload->>'enabled')::boolean,true) and c.provider_enabled and c.visible,
                  'quoteReady',false,'eligibilityRevision',concat_ws(':',c.dataset_id,c.current_version_id,
                    (select concat_ws(':',v.status,v.rows_fingerprint) from logistics_version v where v.id=c.current_version_id),
                    (select md5(string_agg(concat_ws(':',a.id,a.kind,a.engine_version,a.rows_fingerprint,a.reviewed_at),',' order by a.id)) from logistics_billing_acceptance a where a.version_id=c.current_version_id))) order by c.id) from channels c),'[]'::jsonb),
                'versions',coalesce((select jsonb_agg(jsonb_build_object('id',v.id,'channel',v.channel_id,'number',v.version_number,'status',v.status,
                  'publishedAt',v.published_at,'fileName',v.workspace_payload->>'fileName','rollbackFromVersionId',v.workspace_payload->>'rollbackFromVersionId',
                  'rows','[]'::jsonb,'preparedPrices',h.prices) order by v.id)
                  from logistics_version v join channels c on c.id=v.channel_id
                  join logistics_price_history_projection h on h.version_id=v.id and h.rows_fingerprint=v.rows_fingerprint and h.engine_version=:engine
                  where v.status in ('published','superseded') and v.published_at is not null),'[]'::jsonb)) value
            ) select (value||jsonb_build_object('sourceRevision',md5(value::text),'capturedAt',now()))::text from state
            """).param("engine",LogisticsPriceHistoryCalculator.VERSION).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).single();
        if(snapshot.path("pendingVersions").asInt()>0)throw unavailable();
        for(var channel:snapshot.path("channels")){
            if(System.nanoTime()>deadline)throw unavailable();
            var current=channel.path("current").asText("");
            if(!current.isBlank()&&channel.path("enabled").asBoolean()){
                var revision=channel.path("eligibilityRevision").asText();
                var ready=readiness.get(revision);
                if(ready==null){ready=jdbc.sql("select logistics_version_quote_ready(:id)").param("id",UUID.fromString(current)).query(Boolean.class).single();readiness.put(revision,ready);}
                ((ObjectNode)channel).put("quoteReady",ready);
            }
        }
        return snapshot;
    }
}
