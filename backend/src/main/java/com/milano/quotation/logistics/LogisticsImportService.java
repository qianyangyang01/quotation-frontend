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
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class LogisticsImportService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LogisticsImportService.class);
    @org.springframework.beans.factory.annotation.Value("${app.logistics.resume-on-start:true}")
    private boolean resumeOnStart;
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
        if(files.isEmpty()||files.size()>30||files.stream().mapToLong(MultipartFile::getSize).sum()>100L*1024*1024)throw AppException.unprocessable("每批1至30个文件，总大小不超过100MB");
        for(var file:files)if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$"))throw AppException.unprocessable("只支持非空xls/xlsx文件");
        var id=UUID.randomUUID();var payload=mapper.createObjectNode().put("replaceDrafts",replaceDrafts);var sources=payload.putArray("files");
        var signature=new StringBuilder(dataset.toString()).append(replaceDrafts);
        for(var file:files)try { signature.append(file.getOriginalFilename()).append(AssetStorageService.sha256(file.getBytes())); }catch(IOException e){throw AppException.unprocessable("读取上传文件失败");}
        var requestHash=LogisticsDatasetService.hash(signature.toString());
        var existing=jdbc.sql("select id,payload::text from logistics_import_batch where requested_by=:actor and request_key=:key").param("actor",actor).param("key",key)
                .query((rs,n)->mapper.createObjectNode().put("id",rs.getString(1)).set("payload",mapper.readTree(rs.getString(2)))).optional();
        if(existing.isPresent()) {
            if(!existing.get().path("payload").path("requestHash").asText().equals(requestHash))throw AppException.conflict("同一导入请求标识对应了不同文件");
            return get(UUID.fromString(existing.get().path("id").asText()));
        }
        payload.put("requestHash",requestHash).put("progress",0);payload.putArray("results");
        // Raw files remain durable evidence even if a later database write fails.
        for(int i=0;i<files.size();i++)try {
            var file=files.get(i);var bytes=file.getBytes();var objectKey="logistics/imports/"+id+"/"+i;
            storage.putRaw(objectKey,new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
            sources.addObject().put("name",file.getOriginalFilename().replaceAll("[\\r\\n\\\\/]","_")).put("objectKey",objectKey)
                    .put("sha256",AssetStorageService.sha256(bytes)).put("size",bytes.length);
        }catch(IOException e){throw AppException.unprocessable("文件持久化失败");}
        tx.executeWithoutResult(status->{guard.writable(dataset);jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,:actor,:key,'queued','queued',cast(:payload as jsonb))")
                .param("id",id).param("dataset",dataset).param("actor",actor).param("key",key).param("payload",payload.toString()).update();});
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
            var count=jdbc.sql("update logistics_import_batch set status='queued',phase='queued',updated_at=now() where id=:id and (status in ('failed','interrupted') or (status='processing' and updated_at<now()-interval '15 minutes'))")
                    .param("id",id).update();if(count!=1)throw AppException.conflict("该批次仍在处理或已完成，不能重试");});
        worker.submit(()->process(id));
    }
    public void process(UUID id){
        var lease=UUID.randomUUID();
        if(jdbc.sql("update logistics_import_batch set status='processing',phase='parsing',lease_id=:lease,updated_at=now() where id=:id and status='queued'").param("lease",lease).param("id",id).update()!=1)return;
        var batch=get(id);var payload=(ObjectNode)batch.path("payload").deepCopy();var dataset=UUID.fromString(batch.path("dataset_id").asText());
        var actor=batch.path("requested_by").asText();long start=System.nanoTime();
        var grouped=new LinkedHashMap<String,ObjectNode>();var fileReports=payload.putArray("fileReports");
        try {
            int index=0;
            for(var file:payload.path("files")) {
                try(var input=storage.openRaw(file.path("objectKey").asText())) {
                    byte[] bytes=input.readNBytes(100*1024*1024+1);
                    if(!AssetStorageService.sha256(bytes).equals(file.path("sha256").asText()))throw AppException.conflict("源文件校验失败");
                    var parsed=parser.parse(bytes,file.path("name").asText());
                    var report=parsed.deepCopy();report.remove("channels");report.put("status","parsed");
                    // Persist cell-level evidence once. Rewriting it on every channel progress tick
                    // makes multi-provider standard workbooks needlessly expensive to import/poll.
                    var evidence=mapper.writeValueAsBytes(report);var evidenceKey="logistics/evidence/"+id+"/"+lease+"/"+index+".json";
                    storage.putRaw(evidenceKey,new ByteArrayInputStream(evidence),evidence.length,"application/json");
                    for(var sheet:report.path("sheets"))((ObjectNode)sheet).remove("sourceCells");
                    report.putObject("sourceEvidence").put("objectKey",evidenceKey).put("sha256",AssetStorageService.sha256(evidence));
                    fileReports.add(report);
                    for(var value:parsed.path("channels")) {
                        var channel=(ObjectNode)value;var identity=LogisticsSourceParser.identity(channel);
                        channel.put("fileName",file.path("name").asText()).put("sourceFileIndex",index).put("batchId",id.toString());
                        if(!grouped.containsKey(identity))grouped.put(identity,channel);
                        else {
                            var prior=grouped.get(identity);
                            if(!prior.path("contentHash").asText().equals(channel.path("contentHash").asText())) {
                                prior.put("errors",prior.path("errors").asInt()+1);
                                ((ArrayNode)prior.path("issues")).addObject().put("level","error").put("field","批内冲突").put("message","同一渠道在多个文件中存在不同价格/规则，禁止按上传顺序覆盖");
                            } else prior.withArray("duplicateFiles").add(file.path("name").asText());
                        }
                    }
                } catch(Exception e){fileReports.addObject().put("fileName",file.path("name").asText()).put("status","failed").put("message",safe(e));}
                index++;payload.put("progress",Math.round(index*60.0/payload.path("files").size()));save(id,lease,"processing","parsing",payload);
            }
            payload.put("parsingMs",(System.nanoTime()-start)/1_000_000);long stagingStart=System.nanoTime();
            var results=payload.putArray("results");int completed=0;
            for(var channel:grouped.values()) {
                ObjectNode outcome;
                try {outcome=tx.execute(status->{guard.writable(dataset);var owner=jdbc.sql("select lease_id from logistics_import_batch where id=:id and status='processing' for update").param("id",id).query(UUID.class).optional();if(owner.isEmpty()||!owner.get().equals(lease))throw AppException.conflict("导入执行权已转移，请刷新批次");return importChannel(dataset,channel,actor,payload.path("replaceDrafts").asBoolean());});}
                catch(Exception e){log.warn("Logistics import {} channel staging failed",id,e);outcome=mapper.createObjectNode().put("providerName",channel.path("providerName").asText()).put("channelName",channel.path("channelName").asText()).put("status","blocked").put("message",safe(e));outcome.set("parsed",channel);}
                results.add(outcome);completed++;payload.put("progress",60+Math.round(completed*40.0/Math.max(1,grouped.size())));save(id,lease,"processing","staging",payload);
            }
            payload.put("progress",100).put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("stagingMs",(System.nanoTime()-stagingStart)/1_000_000);
            save(id,lease,grouped.isEmpty()?"failed":"completed",grouped.isEmpty()?"failed":"review",payload);
        } catch(Exception e){payload.put("error",safe(e));save(id,lease,"failed","failed",payload);}
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
        var outcome=mapper.createObjectNode().put("channelId",channelId.toString()).put("providerName",providerName).put("channelName",input.path("channelName").asText()).put("errors",input.path("errors").asInt()).put("quoteReady",false);
        if(current.isPresent() && input.path("errors").asInt()==0) {
            var published=mapper.readTree(current.get());
            if(input.path("contentHash").asText().equals(published.path("contentHash").asText()))return outcome.put("status","unchanged").put("versionId",published.path("id").asText());
        }
        var body=input.deepCopy().put("sourceHash",input.path("contentHash").asText()).put("importedBy",actor);
        var version=replaceDrafts?logistics.createDraftReplacing(channelId,body,"用户确认由新导入终止旧待审稿"):logistics.createDraft(channelId,body);
        outcome.put("versionId",version.path("id").asText()).put("versionNumber",version.path("versionNumber").asInt()).put("status",input.path("errors").asInt()>0?"blocked":"draft");
        outcome.set("summary",version.path("summary"));outcome.set("issues",input.path("issues"));return outcome;
    }
    private void save(UUID id,UUID lease,String status,String phase,ObjectNode payload){jdbc.sql("update logistics_import_batch set status=:status,phase=:phase,payload=cast(:payload as jsonb),updated_at=now() where id=:id and lease_id=:lease")
            .param("lease",lease).param("id",id).param("status",status).param("phase",phase).param("payload",payload.toString()).update();}
    private static String safe(Exception e){return e instanceof AppException?e.getMessage():"处理失败（"+e.getClass().getSimpleName()+"），正式价格未被替换";}
}
