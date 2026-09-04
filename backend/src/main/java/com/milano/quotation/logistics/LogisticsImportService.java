package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class LogisticsImportService {
    static final int MAX_FILES=30;
    static final long MAX_BATCH_BYTES=500L*1024*1024;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LogisticsImportService.class);
    private static final Pattern LEADING_SOURCE_DATE=Pattern.compile("^(?:(?:19|20)\\d{2}\\s*(?:年|[./_-])\\s*)?\\d{1,2}\\s*(?:月\\s*\\d{1,2}\\s*日?|[./_-]\\s*\\d{1,2})[\\s._-]*");
    @org.springframework.beans.factory.annotation.Value("${app.logistics.resume-on-start:true}")
    private boolean resumeOnStart;
    @org.springframework.beans.factory.annotation.Value("${app.logistics.file-cleanup-enabled:true}")
    private boolean fileCleanupEnabled;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AssetStorageService storage;
    private final LogisticsSourceParser parser;
    private final LogisticsService logistics;
    private final LogisticsDatasetGuard guard;
    private final TransactionTemplate tx;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"logistics-import-worker");t.setDaemon(true);return t;});
    public LogisticsImportService(JdbcClient jdbc,ObjectMapper mapper,AssetStorageService storage,LogisticsSourceParser parser,
                                  LogisticsService logistics,LogisticsDatasetGuard guard,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.mapper=mapper;this.storage=storage;this.parser=parser;this.logistics=logistics;this.guard=guard;tx=new TransactionTemplate(manager);
    }
    @PreDestroy public void close(){worker.shutdown();}
    @EventListener(ApplicationReadyEvent.class)
    public void resumeQueued(){
        if(!resumeOnStart)return;
        jdbc.sql("update logistics_import_batch set status='interrupted',phase='interrupted' where status='processing' and updated_at<now()-interval '15 minutes'").update();
        jdbc.sql("select id from logistics_import_batch where status='queued' order by created_at").query(UUID.class).list().forEach(id->worker.submit(()->process(id)));
    }
    public ObjectNode upload(UUID dataset,List<MultipartFile> files,String actor,String key,boolean replaceDrafts){
        var result=tx.execute(status->{guard.request(actor,"logistics-import",key);return uploadLocked(dataset,files,actor,key,replaceDrafts);});
        if(result!=null&&result.path("status").asText().equals("queued"))worker.submit(()->process(UUID.fromString(result.path("id").asText())));
        return result;
    }
    private ObjectNode uploadLocked(UUID dataset,List<MultipartFile> files,String actor,String key,boolean replaceDrafts){
        if(key==null||key.isBlank()||key.length()>160)throw AppException.unprocessable("缺少有效导入请求标识");
        validateFiles(files);
        var id=UUID.randomUUID();var payload=mapper.createObjectNode().put("replaceDrafts",replaceDrafts);var sources=payload.putArray("files");
        var signature=new StringBuilder(dataset.toString()).append(replaceDrafts);
        var hashes=new ArrayList<String>();
        for(var file:files)try(var input=file.getInputStream()) {var hash=sha256(input);hashes.add(hash);signature.append(file.getOriginalFilename()).append(file.getSize()).append(hash);}catch(IOException e){throw AppException.unprocessable("读取上传文件失败");}
        var requestHash=LogisticsDatasetService.hash(signature.toString());
        var existing=jdbc.sql("select id,payload::text from logistics_import_batch where requested_by=:actor and request_key=:key").param("actor",actor).param("key",key)
                .query((rs,n)->mapper.createObjectNode().put("id",rs.getString(1)).set("payload",mapper.readTree(rs.getString(2)))).optional();
        if(existing.isPresent()) {
            if(!existing.get().path("payload").path("requestHash").asText().equals(requestHash))throw AppException.conflict("同一导入请求标识对应了不同文件");
            return get(UUID.fromString(existing.get().path("id").asText()));
        }
        payload.put("requestHash",requestHash).put("progress",0).put("totalFiles",files.size()).put("totalBytes",files.stream().mapToLong(MultipartFile::getSize).sum());payload.putArray("results");
        // Raw files remain durable evidence even if a later database write fails.
        var storedKeys=new ArrayList<String>();
        try {
            for(int i=0;i<files.size();i++){
                var file=files.get(i);var objectKey="logistics/imports/"+id+"/"+i;
                var originalName=safeFileName(file.getOriginalFilename());var displayName=displayFileName(originalName);
                try(var input=file.getInputStream()){
                    var storedHash=storage.putRawWithSha256(objectKey,input,file.getSize(),"application/octet-stream");
                    storedKeys.add(objectKey);
                    if(!storedHash.equals(hashes.get(i)))throw AppException.conflict("上传文件在保存过程中发生变化，请重新选择文件");
                }
                sources.addObject().put("name",displayName).put("originalName",originalName).put("objectKey",objectKey)
                        .put("sha256",hashes.get(i)).put("size",file.getSize()).put("lifecycleStatus","stored");
            }
        }catch(IOException e){storedKeys.forEach(storage::removeRaw);throw AppException.unprocessable("文件持久化失败");}
        catch(RuntimeException e){storedKeys.forEach(storage::removeRaw);throw e;}
        tx.executeWithoutResult(status->{guard.writable(dataset);jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,:actor,:key,'queued','queued',cast(:payload as jsonb))")
                .param("id",id).param("dataset",dataset).param("actor",actor).param("key",key).param("payload",payload.toString()).update();
            for(int i=0;i<sources.size();i++){var source=sources.get(i);jdbc.sql("insert into logistics_import_file(batch_id,file_index,original_name,object_key,sha256,size_bytes,status) values(:batch,:idx,:name,:key,:sha,:size,'stored')")
                    .param("batch",id).param("idx",i).param("name",source.path("originalName").asText()).param("key",source.path("objectKey").asText()).param("sha",source.path("sha256").asText()).param("size",source.path("size").asLong()).update();}});
        return get(id);
    }
    public List<ObjectNode> list(UUID dataset){
        return jdbc.sql("select (to_jsonb(b)-'payload' || jsonb_build_object('progress',b.payload->'progress','files',b.payload->'files'))::text from logistics_import_batch b where dataset_id=:id order by created_at desc limit 100")
                .param("id",dataset).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).list();
    }
    public ObjectNode get(UUID id){return jdbc.sql("select to_jsonb(b)::text from logistics_import_batch b where id=:id").param("id",id)
            .query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("导入批次不存在"));}
    public void retry(UUID id){
        tx.executeWithoutResult(status->{var batch=get(id);guard.writable(UUID.fromString(batch.path("dataset_id").asText()));
            var count=jdbc.sql("update logistics_import_batch set status='queued',phase='queued',updated_at=now() where id=:id and (status in ('failed','interrupted') or (status='completed' and exists(select 1 from logistics_import_file f where f.batch_id=:id and f.status='failed' and f.retention_until>now())) or (status='processing' and updated_at<now()-interval '15 minutes'))")
                    .param("id",id).update();if(count!=1)throw AppException.conflict("该批次仍在处理或已完成，不能重试");});
        worker.submit(()->process(id));
    }
    public void process(UUID id){
        var lease=UUID.randomUUID();
        if(jdbc.sql("update logistics_import_batch set status='processing',phase='parsing',lease_id=:lease,updated_at=now() where id=:id and status='queued'").param("lease",lease).param("id",id).update()!=1)return;
        var batch=get(id);var payload=(ObjectNode)batch.path("payload").deepCopy();var dataset=UUID.fromString(batch.path("dataset_id").asText());
        var actor=batch.path("requested_by").asText();long start=System.nanoTime();
        var priorReports=payload.path("fileReports").deepCopy();var priorResults=payload.path("results").deepCopy();
        var grouped=new LinkedHashMap<String,ObjectNode>();var fileReports=payload.putArray("fileReports");
        try {
            int index=0;
            for(var file:payload.path("files")) {
                if("deleted".equals(file.path("lifecycleStatus").asText())){if(index<priorReports.size())fileReports.add(priorReports.get(index));index++;payload.put("processedFiles",index);continue;}
                payload.put("currentFileIndex",index).put("currentFileName",file.path("name").asText()).put("processedFiles",index).put("totalFiles",payload.path("files").size());
                save(id,lease,"processing","parsing",payload);
                try(var input=storage.openRaw(file.path("objectKey").asText())) {
                    byte[] bytes=input.readNBytes((int)LogisticsSourceParser.MAX_FILE_BYTES+1);
                    if(bytes.length>LogisticsSourceParser.MAX_FILE_BYTES)throw AppException.unprocessable("单个物流文件不能超过100MB");
                    if(!AssetStorageService.sha256(bytes).equals(file.path("sha256").asText()))throw AppException.conflict("源文件校验失败");
                    var parsed=parser.parse(bytes,file.path("name").asText());
                    var templatePending=false;for(var channel:parsed.path("channels"))if("adapter-required".equals(channel.path("templateStatus").asText()))templatePending=true;
                    var report=parsed.deepCopy();report.remove("channels");report.put("status",templatePending?"template-pending":"parsed").put("fileIndex",index);
                    report.put("originalFileName",file.path("originalName").asText(file.path("name").asText()));
                    // Persist cell-level evidence once. Rewriting it on every channel progress tick
                    // makes multi-provider standard workbooks needlessly expensive to import/poll.
                    var evidence=mapper.writeValueAsBytes(report);var evidenceKey="logistics/evidence/"+id+"/"+lease+"/"+index+".json";
                    storage.putRaw(evidenceKey,new ByteArrayInputStream(evidence),evidence.length,"application/json");
                    for(var sheet:report.path("sheets"))((ObjectNode)sheet).remove("sourceCells");
                    report.putObject("sourceEvidence").put("objectKey",evidenceKey).put("sha256",AssetStorageService.sha256(evidence));
                    fileReports.add(report);
                    for(var value:parsed.path("channels")) {
                        var channel=(ObjectNode)value;var identity=LogisticsSourceParser.identity(channel);
                        channel.put("fileName",file.path("name").asText()).put("originalFileName",file.path("originalName").asText(file.path("name").asText()))
                                .put("sourceFileIndex",index).put("batchId",id.toString()).put("parserVersion",parsed.path("parserVersion").asText());
                        if(!grouped.containsKey(identity))grouped.put(identity,channel);
                        else {
                            var prior=grouped.get(identity);
                            if(!prior.path("contentHash").asText().equals(channel.path("contentHash").asText())) {
                                prior.put("errors",prior.path("errors").asInt()+1);
                                ((ArrayNode)prior.path("issues")).addObject().put("level","error").put("field","批内冲突").put("message","同一渠道在多个文件中存在不同价格/规则，禁止按上传顺序覆盖");
                            } else prior.withArray("duplicateFiles").add(file.path("name").asText());
                        }
                    }
                    if(templatePending){var until=java.time.Instant.now().plus(java.time.Duration.ofDays(7));report.put("retentionUntil",until.toString());((ObjectNode)file).put("lifecycleStatus","failed").put("retentionUntil",until.toString());markFailed(id,index,"新模板待适配",until);}
                    else markParsed(id,index,parsed.path("parserVersion").asText());
                } catch(Exception e){log.warn("Logistics import {} file {} parsing failed",id,index,e);var until=java.time.Instant.now().plus(java.time.Duration.ofDays(7));fileReports.addObject().put("fileName",file.path("name").asText()).put("originalFileName",file.path("originalName").asText(file.path("name").asText())).put("fileIndex",index).put("status","failed").put("retentionUntil",until.toString()).put("message",safe(e));((ObjectNode)file).put("lifecycleStatus","failed").put("retentionUntil",until.toString());markFailed(id,index,safe(e),until);}
                index++;payload.put("processedFiles",index).put("progress",Math.round(index*60.0/payload.path("files").size()));save(id,lease,"processing","parsing",payload);
            }
            payload.put("parsingMs",(System.nanoTime()-start)/1_000_000);long stagingStart=System.nanoTime();
            var results=payload.putArray("results");for(var prior:priorResults){var sourceIndex=prior.path("sourceFileIndex").asInt(-1);if(sourceIndex>=0&&sourceIndex<payload.path("files").size()&&"deleted".equals(payload.path("files").get(sourceIndex).path("lifecycleStatus").asText()))results.add(prior);};int completed=0;
            payload.put("processedChannels",0).put("totalChannels",grouped.size()).remove("currentFileName");
            for(var channel:grouped.values()) {
                payload.put("currentChannelName",channel.path("providerName").asText()+" · "+channel.path("channelName").asText()).put("processedChannels",completed);
                save(id,lease,"processing","staging",payload);
                ObjectNode outcome;
                try {outcome=tx.execute(status->{guard.writable(dataset);var owner=jdbc.sql("select lease_id from logistics_import_batch where id=:id and status='processing' for update").param("id",id).query(UUID.class).optional();if(owner.isEmpty()||!owner.get().equals(lease))throw AppException.conflict("导入执行权已转移，请刷新批次");return importChannel(dataset,channel,actor,payload.path("replaceDrafts").asBoolean());});}
                catch(Exception e){log.warn("Logistics import {} channel staging failed",id,e);outcome=mapper.createObjectNode().put("providerName",channel.path("providerName").asText()).put("channelName",channel.path("channelName").asText()).put("status","blocked").put("message",safe(e));outcome.set("parsed",channel);}
                results.add(outcome);completed++;payload.put("processedChannels",completed).put("progress",60+Math.round(completed*40.0/Math.max(1,grouped.size())));save(id,lease,"processing","staging",payload);
            }
            payload.remove("currentFileName");payload.remove("currentChannelName");payload.put("progress",100).put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("stagingMs",(System.nanoTime()-stagingStart)/1_000_000);LogisticsReadiness.applyBatch(payload);
            var finalStatus=grouped.isEmpty()?"failed":"completed";var finalPhase=grouped.isEmpty()?"failed":"review";
            save(id,lease,finalStatus,finalPhase,payload);
            cleanupParsedFiles(id,payload);save(id,lease,finalStatus,finalPhase,payload);
        } catch(Exception e){log.error("Logistics import {} batch processing failed",id,e);payload.put("error",safe(e));save(id,lease,"failed","failed",payload);}
    }
    static void validateFiles(List<MultipartFile> files){
        if(files.isEmpty()||files.size()>MAX_FILES)throw AppException.unprocessable("每批请选择1至30个文件");
        long total=0;
        for(var file:files){
            if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$"))throw AppException.unprocessable("只支持非空xls/xlsx文件");
            if(file.getSize()>LogisticsSourceParser.MAX_FILE_BYTES)throw AppException.unprocessable("单个物流文件不能超过100MB");
            try{total=Math.addExact(total,file.getSize());}catch(ArithmeticException e){throw AppException.unprocessable("同一批次文件总大小不能超过500MB");}
        }
        if(total>MAX_BATCH_BYTES)throw AppException.unprocessable("同一批次文件总大小不能超过500MB");
    }
    private static String sha256(InputStream input)throws IOException{
        try{var digest=MessageDigest.getInstance("SHA-256");var stream=new java.security.DigestInputStream(input,digest);stream.transferTo(OutputStream.nullOutputStream());return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private ObjectNode importChannel(UUID dataset,ObjectNode input,String actor,boolean replaceDrafts){
        guard.writable(dataset);
        // A dataset-scoped transaction lock makes provider/channel creation deterministic under concurrent uploads.
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:scope))").param("scope",dataset.toString()).query(rs->true);
        var providerName=input.path("providerName").asText();if(providerName.isBlank()||providerName.equals("未识别物流商"))throw AppException.unprocessable("请先确认物流商，不能自动创建未识别物流商");
        var providerCode="P-"+LogisticsDatasetService.hash(LogisticsDatasetService.normalize(providerName)).substring(0,16);
        var provider=jdbc.sql("select id from logistics_provider where dataset_id=:dataset and code=:code").param("dataset",dataset).param("code",providerCode).query(UUID.class).optional();
        UUID providerId;
        if(provider.isPresent())providerId=provider.get();else {
            providerId=UUID.randomUUID();var p=mapper.createObjectNode().put("id",providerId.toString()).put("datasetId",dataset.toString()).put("name",providerName).put("code",providerCode).put("enabled",true);
            jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:dataset,:code,cast(:payload as jsonb),now(),now())")
                    .param("id",providerId).param("dataset",dataset).param("code",providerCode).param("payload",p.toString()).update();
        }
        var channelCode="C-"+LogisticsDatasetService.hash(LogisticsSourceParser.identity(input)).substring(0,20);
        var found=jdbc.sql("select id from logistics_channel where dataset_id=:dataset and code=:code for update").param("dataset",dataset).param("code",channelCode).query(UUID.class).optional();
        UUID channelId;
        if(found.isPresent())channelId=found.get();else {
            channelId=UUID.randomUUID();int rule=guard.nextRuleId();
            var c=mapper.createObjectNode().put("id",channelId.toString()).put("datasetId",dataset.toString()).put("providerId",providerId.toString()).put("ruleId",rule)
                    .put("name",input.path("channelName").asText()).put("code",channelCode).put("enabled",true).put("type","专线").put("logisticsAttribute",input.path("logisticsAttribute").asText("普货"));
            jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:dataset,:provider,:code,:rule,cast(:payload as jsonb),now(),now())")
                    .param("id",channelId).param("dataset",dataset).param("provider",providerId).param("code",channelCode).param("rule",rule).param("payload",c.toString()).update();
        }
        var current=jdbc.sql("select v.payload::text from logistics_channel c join logistics_version v on v.id=c.current_version_id where c.id=:id")
                .param("id",channelId).query(String.class).optional();
        var pendingReasons=new TreeSet<String>();
        for(var row:input.path("rows"))for(var reason:row.path("pendingReason").asText().split("；"))if(!reason.isBlank())pendingReasons.add(reason.trim());
        var outcome=mapper.createObjectNode().put("channelId",channelId.toString()).put("providerName",providerName).put("channelName",input.path("channelName").asText())
                .put("errors",input.path("errors").asInt()).put("pricingReady",input.path("quoteReady").asBoolean()).put("etaReady",input.path("etaReady").asBoolean(false))
                .put("etaMissingCount",input.path("etaMissingCount").asInt()).put("priceRows",input.path("rows").size()).put("sourceFileIndex",input.path("sourceFileIndex").asInt());
        outcome.set("pendingReasons",mapper.valueToTree(pendingReasons));
        outcome.set("missingEtaRoutes",input.path("missingEtaRoutes").deepCopy());outcome.set("blockingReasons",input.path("blockingReasons").deepCopy());outcome.set("reviewWarnings",input.path("reviewWarnings").deepCopy());
        if(current.isPresent() && input.path("errors").asInt()==0) {
            var published=mapper.readTree(current.get());
            if(input.path("contentHash").asText().equals(published.path("contentHash").asText())){
                outcome.putObject("summary").put("added",0).put("price",0).put("rule",0).put("removed",0).put("unchanged",published.path("rows").size());
                return outcome.put("status","unchanged").put("versionId",published.path("id").asText()).put("basePublishedVersionId",published.path("id").asText());
            }
        }
        var body=input.deepCopy().put("sourceHash",input.path("contentHash").asText()).put("importedBy",actor);
        var version=replaceDrafts?logistics.createDraftReplacing(channelId,body,"用户确认由新导入终止旧待审稿"):logistics.createDraft(channelId,body);
        outcome.put("versionId",version.path("id").asText()).put("versionNumber",version.path("versionNumber").asInt()).put("status",input.path("errors").asInt()>0?"blocked":"draft")
                .put("basePublishedVersionId",version.path("basePublishedVersionId").asText());
        outcome.put("pricingReady",version.path("pricingReady").asBoolean(false)).put("etaReady",version.path("etaReady").asBoolean(false)).put("etaMissingCount",version.path("etaMissingCount").asInt());
        outcome.set("missingEtaRoutes",version.path("missingEtaRoutes").deepCopy());outcome.set("blockingReasons",version.path("blockingReasons").deepCopy());outcome.set("reviewWarnings",version.path("reviewWarnings").deepCopy());
        outcome.set("summary",version.path("summary"));outcome.set("issues",input.path("issues"));return outcome;
    }
    private void save(UUID id,UUID lease,String status,String phase,ObjectNode payload){jdbc.sql("update logistics_import_batch set status=:status,phase=:phase,payload=cast(:payload as jsonb),updated_at=now() where id=:id and lease_id=:lease")
            .param("lease",lease).param("id",id).param("status",status).param("phase",phase).param("payload",payload.toString()).update();}
    private void markParsed(UUID batch,int index,String parserVersion){jdbc.sql("update logistics_import_file set status='parsed',parser_version=:parser,retention_until=null,delete_error=null,updated_at=now() where batch_id=:batch and file_index=:idx")
            .param("parser",parserVersion).param("batch",batch).param("idx",index).update();}
    void markFailed(UUID batch,int index,String reason,java.time.Instant until){jdbc.sql("update logistics_import_file set status='failed',retention_until=:until,delete_error=:reason,updated_at=now() where batch_id=:batch and file_index=:idx")
            .param("until",OffsetDateTime.ofInstant(until,ZoneOffset.UTC)).param("reason",reason).param("batch",batch).param("idx",index).update();}
    private void cleanupParsedFiles(UUID batch,ObjectNode payload){for(int i=0;i<payload.path("files").size();i++){var file=(ObjectNode)payload.path("files").get(i);var report=payload.path("fileReports").path(i);if(!"parsed".equals(report.path("status").asText()))continue;
            if(storage.removeRaw(file.path("objectKey").asText())){file.put("lifecycleStatus","deleted").put("deletedAt",java.time.Instant.now().toString());jdbc.sql("update logistics_import_file set status='deleted',deleted_at=now(),delete_error=null,updated_at=now() where batch_id=:batch and file_index=:idx").param("batch",batch).param("idx",i).update();}
            else {file.put("lifecycleStatus","delete-pending").put("deleteError","对象存储删除失败，已进入重试队列");jdbc.sql("update logistics_import_file set status='delete-pending',delete_error='对象存储删除失败，已进入重试队列',updated_at=now() where batch_id=:batch and file_index=:idx").param("batch",batch).param("idx",i).update();}}}
    @Scheduled(fixedDelayString="${app.logistics.file-cleanup-delay-ms:3600000}")
    public void cleanupRetainedFiles(){if(!fileCleanupEnabled)return;List<Map<String,String>> rows;try{rows=jdbc.sql("select batch_id,file_index,object_key,status from logistics_import_file where status='delete-pending' or (status='failed' and retention_until<=now()) order by updated_at limit 100")
            .query((rs,n)->Map.of("batch",rs.getString(1),"index",Integer.toString(rs.getInt(2)),"key",rs.getString(3),"status",rs.getString(4))).list();}catch(org.springframework.jdbc.BadSqlGrammarException migrationNotAvailable){return;}
        for(var row:rows){var batch=UUID.fromString(row.get("batch"));var index=Integer.parseInt(row.get("index"));if(storage.removeRaw(row.get("key"))){jdbc.sql("update logistics_import_file set status='deleted',deleted_at=now(),delete_error=null,updated_at=now() where batch_id=:batch and file_index=:idx").param("batch",batch).param("idx",index).update();syncLifecycle(batch,index,"deleted","");}
            else jdbc.sql("update logistics_import_file set delete_error='对象存储删除失败，等待下次重试',updated_at=now() where batch_id=:batch and file_index=:idx").param("batch",batch).param("idx",index).update();}}
    private void syncLifecycle(UUID batch,int index,String status,String error){var current=get(batch);var payload=(ObjectNode)current.path("payload").deepCopy();if(index<payload.path("files").size()){var file=(ObjectNode)payload.path("files").get(index);file.put("lifecycleStatus",status);if(status.equals("deleted"))file.put("deletedAt",java.time.Instant.now().toString());if(!error.isBlank())file.put("deleteError",error);jdbc.sql("update logistics_import_batch set payload=cast(:payload as jsonb),updated_at=now() where id=:id").param("payload",payload.toString()).param("id",batch).update();}}
    public boolean sourceAvailable(UUID batch,int index){return jdbc.sql("select status<>'deleted' from logistics_import_file where batch_id=:batch and file_index=:idx").param("batch",batch).param("idx",index).query(Boolean.class).optional().orElse(true);}
    static String displayFileName(String originalName){
        var safe=safeFileName(originalName);var display=LEADING_SOURCE_DATE.matcher(safe).replaceFirst("").trim();
        return display.isBlank()?safe:display;
    }
    static String safeFileName(String name){return name==null?"物流价格表.xlsx":name.replaceAll("[\\r\\n\\\\/]","_").trim();}
    private static String safe(Exception e){var message=e instanceof AppException?e.getMessage():"处理失败（"+e.getClass().getSimpleName()+"），正式价格未被替换";return message.substring(0,Math.min(500,message.length()));}
}
