package com.milano.quotation.purchase;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class ShimoSyncService {
    private static final long LOCK=20260927054L;
    private final ShimoClient client;
    private final JdbcTemplate db;
    private final DataSource source;
    private final PurchasePasteService paste;
    private final PurchaseProductRepository products;
    private final PurchaseProductService productService;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final boolean workerEnabled;
    private final int intervalSeconds;
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"shimo-purchase-sync");t.setDaemon(true);return t;});

    public ShimoSyncService(ShimoClient client,JdbcTemplate db,DataSource source,PurchasePasteService paste,
            PurchaseProductRepository products,PurchaseProductService productService,ObjectMapper mapper,
            AuditService audit,PlatformTransactionManager transactions,@Value("${app.shimo.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.shimo.interval-seconds:600}") int intervalSeconds) {
        this.client=client;this.db=db;this.source=source;this.paste=paste;this.products=products;
        this.productService=productService;this.mapper=mapper;this.audit=audit;this.tx=new TransactionTemplate(transactions);this.workerEnabled=workerEnabled;
        this.intervalSeconds=Math.max(300,Math.min(600,intervalSeconds));
        tx.setTimeout(15);
    }
    @PostConstruct void start() { if(workerEnabled&&client.configured()) worker.scheduleWithFixedDelay(this::scheduledRun,30,intervalSeconds,TimeUnit.SECONDS); }
    @PreDestroy void stop() { worker.shutdownNow(); }
    private void scheduledRun() {
        if(!scheduled.compareAndSet(false,true)) return;
        try { runOnce(null); } catch(RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shimo sync unavailable; next scheduled round will retry ({})",e.getClass().getSimpleName());
        } finally { scheduled.set(false); }
    }
    public boolean requestRun() {
        if(!workerEnabled||!client.configured()) throw AppException.unprocessable("尚未配置石墨同步连接");
        if(!enabled()) throw AppException.unprocessable("自动同步已暂停，请先启用");
        if(!scheduled.compareAndSet(false,true)) return false;
        worker.execute(()->{try { runOnce(); } finally { scheduled.set(false); }});
        return true;
    }
    boolean enabled() { return Boolean.TRUE.equals(db.queryForObject("select enabled from shimo_sync_control where id=1",Boolean.class)); }
    public void setEnabled(boolean enabled) {
        if(enabled&&(!workerEnabled||!client.configured())) throw AppException.unprocessable("尚未配置石墨同步连接");
        tx.executeWithoutResult(status->{
            db.update("update shimo_sync_control set enabled=?,updated_at=now() where id=1",enabled);
            audit.record("purchase.shimo-control","shimo-sync",ShimoRowMapper.FILE_GUID,"success",Map.of("enabled",enabled));
        });
    }
    public Map<String,Object> status() {
        var result=new LinkedHashMap<String,Object>();
        result.put("configured",workerEnabled&&client.configured());result.put("enabled",enabled());
        result.put("fileName","国际站2026询价新版");result.put("fileGuid",ShimoRowMapper.FILE_GUID);result.put("sheets",client.sheets());
        result.put("intervalSeconds",intervalSeconds);result.put("running",scheduled.get());
        result.put("runs",db.queryForList("select * from shimo_sync_run order by started_at desc limit 5"));
        result.put("counts",db.queryForList("select status,count(*) as total from shimo_sync_item group by status order by status"));
        return result;
    }
    public Map<String,Object> items(int page, boolean pending) {
        String where=pending?" where status <> 'synced'":"";
        return Map.of("total",Objects.requireNonNull(db.queryForObject("select count(*) from shimo_sync_item"+where,Long.class)),
            "rows",db.queryForList("select sku,sheet,source_row,status,reason,first_seen_at,checked_at,synced_at from shimo_sync_item"+where+" order by checked_at desc,sku limit 50 offset ?",Math.max(0,Math.min(page,10000))*50));
    }
    public Map<String,Object> changes(int page,boolean weightOnly) {
        String where=weightOnly?" where before_payload is not null and before_payload->'weightG' is distinct from after_payload->'weightG'":"";
        var rows=db.queryForList("select id,run_id,sku,sheet,source_row,before_payload,after_payload,created_at,reverted_at from shimo_sync_change"+where+" order by created_at desc,id limit 20 offset ?",Math.max(0,Math.min(page,10000))*20);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var before=row.remove("before_payload");var after=row.remove("after_payload");
            row.put("fields",PurchaseHistoryService.changes(before==null?null:mapper.readTree(before.toString()),mapper.readTree(after.toString())));
            result.add(row);
        }
        return Map.of("total",Objects.requireNonNull(db.queryForObject("select count(*) from shimo_sync_change"+where,Long.class)),"rows",result);
    }
    public Map<String,Object> rollback(UUID run,boolean preview) {
        if(enabled()) throw AppException.conflict("请先暂停石墨自动同步，再预览或执行回退");
        var reverted=new ArrayList<String>();var blocked=new ArrayList<String>();
        var changes=db.queryForList("select * from shimo_sync_change where run_id=? and reverted_at is null order by created_at desc,id desc",run);
        for(var change:changes) {
            String sku=change.get("sku").toString();
            try {
                Boolean ok=tx.execute(status->{
                    if(Boolean.TRUE.equals(db.queryForObject("select enabled from shimo_sync_control where id=1 for share",Boolean.class))) throw AppException.conflict("同步已重新启用，停止回退");
                    var locked=products.findAllLockedBySkuIn(List.of(sku));
                    if(locked.isEmpty()) return false;
                    var current=locked.getFirst();
                    if(!current.id.equals(change.get("product_id"))||current.version!=((Number)change.get("after_version")).longValue()) return false;
                    if(preview) return true;
                    var before=change.get("before_payload");
                    if(before==null) productService.changeCatalogState(sku,"disabled",current.version);
                    else {
                        var payload=(tools.jackson.databind.node.ObjectNode)mapper.readTree(before.toString());
                        payload.put("_version",current.version);productService.restoreSynchronized(payload);
                    }
                    db.update("update shimo_sync_change set reverted_at=now() where id=?",change.get("id"));
                    db.update("update shimo_sync_item set status='rolled_back',reason='已按同步记录回退，后续人工确认',applied_hash='',product_version=? where sku=?",products.findBySku(sku).orElseThrow().version,sku);
                    audit.record("purchase.shimo-rollback","shimo-sync-change",change.get("id").toString(),"success",Map.of("sku",sku,"runId",run.toString(),"action",before==null?"disable-created-product":"restore-prior-payload"));
                    return true;
                });
                (Boolean.TRUE.equals(ok)?reverted:blocked).add(sku);
            } catch(AppException e) {blocked.add(sku);}
        }
        return Map.of("preview",preview,"eligibleOrReverted",reverted,"blockedByLaterChange",blocked);
    }
    /** Dedicated DB session lock covers network reads without holding product transactions or locks. */
    void runOnce() {
        runOnce("manual");
    }
    void runOnce(String requestedMode) {
        UUID run=null;
        var previous=SecurityContextHolder.getContext();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("shimo-sync","",List.of()));
        SecurityContextHolder.setContext(context);
        try(Connection connection=source.getConnection()) {
            try(var lock=connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                lock.setLong(1,LOCK);
                try(var result=lock.executeQuery()) { result.next();if(!result.getBoolean(1)) return; }
            }
            try {
                if(!enabled()) return;
                var now=java.time.Instant.now();
                var day=ShimoSyncSchedule.day(now);
                boolean dailyDone=db.queryForObject("select count(*) from shimo_sync_run where mode='daily' and schedule_day=? and status='completed'",Long.class,java.sql.Date.valueOf(day))>0;
                String mode=requestedMode!=null?requestedMode:ShimoSyncSchedule.dailyWindow(now)&&!dailyDone?"daily":"incremental";
                var resumable=db.queryForList("select id,mode,changed from shimo_sync_run where status in ('fetching','fetch_failed','applying','apply_failed') and (mode <> 'daily' or ?) order by started_at desc limit 1",ShimoSyncSchedule.dailyWindow(now));
                int previousChanges=0;
                if(!resumable.isEmpty()) {
                    var saved=resumable.getFirst();run=(UUID)saved.get("id");mode=saved.get("mode").toString();previousChanges=db.queryForObject("select count(*) from shimo_sync_change where run_id=?",Integer.class,run);
                    db.update("update shimo_sync_run set status='fetching',finished_at=null,reason='' where id=?",run);
                } else {run=UUID.randomUUID();db.update("insert into shimo_sync_run(id,status,mode,schedule_day) values (?,'fetching',?,?)",run,mode,java.sql.Date.valueOf(day));}
                var known=new HashSet<>(db.queryForList("select sku from purchase_product",String.class));
                var pending=new HashSet<>(db.queryForList("select sku from shimo_sync_item where status='pending'",String.class));
                boolean incremental="incremental".equals(mode);
                var rows=selectPreferred(client.readAll(pageCache(run),sku->!incremental||!known.contains(sku)||pending.contains(sku)));
                db.update("update shimo_sync_run set status='applying' where id=?",run);
                var duplicates=rows.stream().collect(Collectors.groupingBy(ShimoClient.SourceRow::sku,Collectors.counting()));
                int processed=0,changed=previousChanges;
                final String applyMode=mode;
                for(var row:rows) {
                    if(!enabled()||Thread.currentThread().isInterrupted()) break;
                    var id=run;
                    try {
                        Boolean wrote=tx.execute(status->apply(id,row,duplicates.get(row.sku())>1,applyMode));
                        if(Boolean.TRUE.equals(wrote)) changed++;
                    } catch(AppException|IllegalArgumentException e) {
                        pending(run,row,"pending",e.getMessage());
                    }
                    processed++;
                    db.update("update shimo_sync_run set processed=?,changed=? where id=?",processed,changed,run);
                }
                if(processed==rows.size()&&!incremental) {
                    // A removed source row is visible for review; never delete its procurement record.
                    db.update("update shimo_sync_item set status='source_missing',reason='本轮未找到来源 SKU，保留系统资料' where run_id <> ? and status <> 'rolled_back'",run);
                }
                db.update("update shimo_sync_run set status=?,processed=?,changed=?,finished_at=now() where id=?",processed==rows.size()?"completed":"paused",processed,changed,run);
                if(processed==rows.size()) {
                    db.update("delete from shimo_sync_fetch_page where run_id=?",run);
                    db.update("delete from shimo_sync_fetch_sheet where run_id=?",run);
                }
            } finally {
                try(var unlock=connection.prepareStatement("select pg_advisory_unlock(?)")) { unlock.setLong(1,LOCK);unlock.execute(); }
            }
        } catch(Exception e) {
            // Deliberately do not persist response bodies, URLs with auth, or arbitrary exception details.
            if(run!=null) db.update("update shimo_sync_run set status=case when status='applying' then 'apply_failed' else 'fetch_failed' end,finished_at=now(),reason=? where id=?",
                e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"同步处理失败，已保存记录保留，下轮重新检查",run);
        } finally { SecurityContextHolder.setContext(previous); }
    }
    static List<ShimoClient.SourceRow> selectPreferred(List<ShimoClient.SourceRow> input) {
        var authoritative=input.stream().filter(r->"老数据更新".equals(r.sheet())).map(ShimoClient.SourceRow::sku).collect(Collectors.toSet());
        return input.stream().filter(r->!authoritative.contains(r.sku())||"老数据更新".equals(r.sheet())).toList();
    }
    ShimoClient.PageCache pageCache(UUID run) {
        return new ShimoClient.PageCache() {
            public void prepare(String sheet,JsonNode index) {
                tx.executeWithoutResult(status->{
                    var found=db.queryForList("select index_hash from shimo_sync_fetch_sheet where run_id=? and sheet=?",run,sheet);
                    db.update("delete from shimo_sync_fetch_page where run_id=? and sheet=? and fetched_at < now()-interval '30 minutes'",run,sheet);
                    var digest=hash(index);
                    if(found.isEmpty()||!digest.equals(found.getFirst().get("index_hash"))) {
                        db.update("delete from shimo_sync_fetch_page where run_id=? and sheet=?",run,sheet);
                        db.update("insert into shimo_sync_fetch_sheet(run_id,sheet,index_hash) values (?,?,?) on conflict(run_id,sheet) do update set index_hash=excluded.index_hash",run,sheet,digest);
                    }
                });
            }
            public JsonNode read(String sheet,int first,int last) {
                db.update("update shimo_sync_run set current_sheet=?,next_row=? where id=?",sheet,first,run);
                var pages=db.queryForList("select payload from shimo_sync_fetch_page where run_id=? and sheet=? and first_row=? and last_row=?",run,sheet,first,last);
                return pages.isEmpty()?null:mapper.readTree(pages.getFirst().get("payload").toString());
            }
            public void write(String sheet,int first,int last,JsonNode rows) {
                tx.executeWithoutResult(status->{
                    db.update("insert into shimo_sync_fetch_page(run_id,sheet,first_row,last_row,payload) values (?,?,?,?,cast(? as jsonb)) on conflict(run_id,sheet,first_row,last_row) do update set payload=excluded.payload,fetched_at=now()",run,sheet,first,last,rows.toString());
                    db.update("update shimo_sync_run set current_sheet=?,next_row=? where id=?",sheet,last+1,run);
                });
            }
        };
    }
    private boolean apply(UUID run,ShimoClient.SourceRow row,boolean duplicate,String mode) {
        if(!Boolean.TRUE.equals(db.queryForObject("select enabled from shimo_sync_control where id=1 for share",Boolean.class))) return false;
        var items=db.queryForList("select * from shimo_sync_item where sku=?",row.sku());
        var old=items.isEmpty()?null:items.getFirst();
        if(old!=null&&"rolled_back".equals(old.get("status"))) {
            pending(run,row,"rolled_back","已回退的资料不再自动覆盖，请人工核对");return false;
        }
        if(duplicate) { pending(run,row,"conflict","新版表存在重复 SKU，需人工核对");return false; }
        var patch=ShimoRowMapper.patch(row.cells());
        var current=products.findBySku(row.sku()).orElse(null);
        if(current!=null&&"legacy_2026".equals(current.payload.path("dataSource").asText())&&!"老数据更新".equals(row.sheet())) {
            pending(run,row,"conflict","同 SKU 属于旧版来源，不自动覆盖");return false;
        }
        if(old!=null&&old.get("product_id")!=null&&
            (current==null||!current.id.equals(old.get("product_id"))||current.version!=((Number)old.get("product_version")).longValue())) {
            pending(run,row,"conflict","系统商品在上次同步后被修改或删除，需人工核对");return false;
        }
        var hash=hash(row.cells());
        if(old!=null&&hash.equals(old.get("applied_hash"))&&current!=null) {
            synced(run,row,hash,current.id,current.version,false);return false;
        }
        if("incremental".equals(mode)&&current!=null&&(old==null||!"pending".equals(old.get("status")))) {
            pending(run,row,"awaiting_daily","已有资料变化，等待每天12:30完整检查");
            // Establish a version guard on the first observation without claiming it was synchronized.
            if(old==null||old.get("product_id")==null) db.update("update shimo_sync_item set product_id=?,product_version=? where sku=?",current.id,current.version,row.sku());
            return false;
        }
        List<JsonNode> input=List.of(patch);
        var preview=paste.preview(input);
        var expected=preview.rows().stream().map(PurchasePasteService.PreviewRow::expected).toList();
        JsonNode before=current==null?null:productService.get(row.sku());
        var result=paste.confirmSynchronized(new PurchasePasteService.Confirmation(input,expected));
        var saved=products.findBySku(row.sku()).orElseThrow();
        boolean changed=!result.added().isEmpty()||!result.updated().isEmpty();
        if(changed) {
            var after=productService.get(row.sku());
            db.update("insert into shimo_sync_change(id,run_id,sku,sheet,source_row,product_id,before_payload,after_payload,after_version) values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)",
                UUID.randomUUID(),run,row.sku(),row.sheet(),row.row(),saved.id,before==null?null:before.toString(),after.toString(),saved.version);
        }
        synced(run,row,hash,saved.id,saved.version,changed);
        return changed;
    }
    private void pending(UUID run,ShimoClient.SourceRow row,String state,String reason) {
        db.update("insert into shimo_sync_item(sku,sheet,source_row,status,reason,source_hash,run_id) values (?,?,?,?,?,?,?) on conflict(sku) do update set sheet=excluded.sheet,source_row=excluded.source_row,status=excluded.status,reason=excluded.reason,source_hash=excluded.source_hash,run_id=excluded.run_id,checked_at=now()",
            row.sku(),row.sheet(),row.row(),state,reason==null?"数据校验未通过":reason,hash(row.cells()),run);
    }
    private void synced(UUID run,ShimoClient.SourceRow row,String hash,UUID productId,long version,boolean changed) {
        db.update("insert into shimo_sync_item(sku,sheet,source_row,status,source_hash,applied_hash,product_id,product_version,run_id,synced_at) values (?,?,?,'synced',?,?,?,?,?,now()) on conflict(sku) do update set sheet=excluded.sheet,source_row=excluded.source_row,status='synced',reason='',source_hash=excluded.source_hash,applied_hash=excluded.applied_hash,product_id=excluded.product_id,product_version=excluded.product_version,run_id=excluded.run_id,checked_at=now(),synced_at=case when ? or shimo_sync_item.synced_at is null then now() else shimo_sync_item.synced_at end",
            row.sku(),row.sheet(),row.row(),hash,hash,productId,version,run,changed);
    }
    private static String hash(JsonNode cells) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cells.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
