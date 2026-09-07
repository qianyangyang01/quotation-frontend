package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Durable chunks belong to one authenticated actor and never enter parsing before full verification. */
@Service
public class LogisticsUploadService {
    public static final int CHUNK_BYTES=4*1024*1024;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AssetStorageService storage;
    private final LogisticsDatasetGuard guard;
    private final LogisticsImportService imports;
    private final TransactionTemplate tx;
    public record FileSpec(String name,long size,String sha256){}
    public record Manifest(List<FileSpec> files,boolean replaceDrafts){}
    private record Session(UUID id,UUID dataset,String key,Manifest manifest,ArrayNode received,UUID batch){}
    public LogisticsUploadService(JdbcClient jdbc,ObjectMapper mapper,AssetStorageService storage,LogisticsDatasetGuard guard,
                                  LogisticsImportService imports,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.mapper=mapper;this.storage=storage;this.guard=guard;this.imports=imports;this.tx=new TransactionTemplate(manager);
    }
    static void validate(Manifest manifest){
        if(manifest==null||manifest.files()==null||manifest.files().isEmpty()||manifest.files().size()>30)throw AppException.unprocessable("请选择1至30个文件");
        long total=0;
        for(var file:manifest.files()){
            if(file==null||file.name()==null||file.name().length()>255||!file.name().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$")
                    ||file.size()<1||file.size()>100L*1024*1024||file.sha256()==null||!file.sha256().matches("[a-f0-9]{64}"))
                throw AppException.unprocessable("文件名称、大小或校验值无效");
            total+=file.size();
        }
        if(total>500L*1024*1024)throw AppException.unprocessable("同一批次文件总大小不能超过500MB");
    }
    public ObjectNode start(UUID dataset,String actor,String key,Manifest manifest){
        validate(manifest);
        return tx.execute(status->{
            guard.request(actor,"logistics-upload-session",key);guard.writable(dataset);
            var existing=jdbc.sql("select id from logistics_upload_session where actor=:actor and request_key=:key and expires_at>now()")
                    .param("actor",actor).param("key",key).query(UUID.class).optional();
            if(existing.isPresent()){
                var session=locked(existing.get(),actor);
                if(!session.dataset().equals(dataset)||!session.manifest().equals(manifest))throw AppException.conflict("续传文件发生变化，请重新选择原文件");
                return state(session);
            }
            // Expired keys are not reused until cleanup has removed their chunks.
            if(jdbc.sql("select count(*) from logistics_upload_session where actor=:actor and request_key=:key").param("actor",actor).param("key",key).query(Long.class).single()>0)
                throw new AppException(org.springframework.http.HttpStatus.GONE,"UPLOAD_EXPIRED","续传已过期，请重新开始上传");
            guard.request(actor,"logistics-upload-quota","active-sessions");
            if(jdbc.sql("select count(*) from logistics_upload_session where actor=:actor and batch_id is null").param("actor",actor).query(Long.class).single()>=3)
                throw AppException.conflict("最多保留3批未完成上传，请先续传已有文件，或等待7天清理");
            var id=UUID.randomUUID();var received=mapper.createArrayNode();manifest.files().forEach(f->received.addArray());
            jdbc.sql("insert into logistics_upload_session(id,dataset_id,actor,request_key,manifest,received) values(:id,:dataset,:actor,:key,:manifest,:received)")
                .param("id",id).param("dataset",dataset).param("actor",actor).param("key",key)
                .param("manifest",mapper.writeValueAsString(manifest)).param("received",received.toString()).update();
            return state(new Session(id,dataset,key,manifest,received,null));
        });
    }
    private Session locked(UUID id,String actor){
        return jdbc.sql("select * from logistics_upload_session where id=:id and actor=:actor and expires_at>now() for update")
            .param("id",id).param("actor",actor).query((rs,n)->new Session(id,rs.getObject("dataset_id",UUID.class),rs.getString("request_key"),
                mapper.readValue(rs.getString("manifest"),Manifest.class),(ArrayNode)mapper.readTree(rs.getString("received")),rs.getObject("batch_id",UUID.class)))
            .optional().orElseThrow(()->AppException.notFound("续传任务不存在或已过期，请重新选择文件上传"));
    }
    private ObjectNode state(Session session){
        var result=mapper.createObjectNode().put("id",session.id().toString()).put("chunkBytes",CHUNK_BYTES);
        result.set("received",session.received());
        if(session.batch()!=null)result.set("batch",imports.get(session.batch()));
        return result;
    }
    public ObjectNode chunk(UUID id,String actor,int file,int chunk,MultipartFile content,String sha){
        if(content==null||content.isEmpty()||content.getSize()>CHUNK_BYTES||sha==null||!sha.matches("[a-f0-9]{64}"))throw AppException.unprocessable("分片大小或校验值无效");
        return tx.execute(status->{
            var session=locked(id,actor);guard.writable(session.dataset());
            if(session.batch()!=null)return state(session);
            if(file<0||file>=session.manifest().files().size())throw AppException.unprocessable("文件序号无效");
            var spec=session.manifest().files().get(file);var hashes=(ArrayNode)session.received().get(file);
            if(chunk<0||chunk>hashes.size()||(long)chunk*CHUNK_BYTES>=spec.size())throw AppException.unprocessable("分片序号无效，请恢复上传进度");
            long expected=Math.min(CHUNK_BYTES,spec.size()-(long)chunk*CHUNK_BYTES);
            if(content.getSize()!=expected)throw AppException.unprocessable("分片长度不匹配");
            try{
                var bytes=content.getBytes();
                if(!AssetStorageService.sha256(bytes).equals(sha))throw AppException.unprocessable("分片校验失败，请重试");
                if(chunk<hashes.size()){
                    if(!hashes.get(chunk).asText().equals(sha))throw AppException.conflict("已上传分片内容不一致");
                    return state(session);
                }
                storage.putRaw(objectKey(id,file,chunk),new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
            }catch(IOException e){throw AppException.unprocessable("读取上传分片失败");}
            hashes.add(sha);
            jdbc.sql("update logistics_upload_session set received=:received,expires_at=now()+interval '7 days' where id=:id")
                .param("received",session.received().toString()).param("id",id).update();
            return state(session);
        });
    }
    public ObjectNode complete(UUID id,String actor){
        return tx.execute(status->{
            var session=locked(id,actor);guard.writable(session.dataset());
            if(session.batch()!=null)return imports.get(session.batch());
            var files=new ArrayList<MultipartFile>();
            try{
                for(int file=0;file<session.manifest().files().size();file++){
                    var spec=session.manifest().files().get(file);var hashes=(ArrayNode)session.received().get(file);
                    if(hashes.size()!=(spec.size()+CHUNK_BYTES-1)/CHUNK_BYTES)throw AppException.conflict("文件尚未上传完整，请继续上传");
                    var assembled=new ChunkFile(spec,id,file,hashes.size());
                    var digest=MessageDigest.getInstance("SHA-256");long size;
                    try(var input=new DigestInputStream(assembled.getInputStream(),digest)){size=input.transferTo(OutputStream.nullOutputStream());}
                    if(size!=spec.size()||!HexFormat.of().formatHex(digest.digest()).equals(spec.sha256()))throw AppException.conflict("完整文件校验失败，请检查原文件");
                    files.add(assembled);
                }
                var result=imports.upload(session.dataset(),files,actor,"resumable:"+id,session.manifest().replaceDrafts());
                jdbc.sql("update logistics_upload_session set batch_id=:batch where id=:id").param("batch",UUID.fromString(result.path("id").asText())).param("id",id).update();
                return result;
            }catch(AppException e){throw e;}catch(Exception e){throw AppException.unprocessable("文件合并失败，请重试完成上传");}
        });
    }
    static String objectKey(UUID id,int file,int chunk){return "logistics/upload-chunks/"+id+"/"+file+"/"+chunk;}
    @Scheduled(fixedDelayString="${app.logistics.upload-cleanup-delay-ms:3600000}")
    public void cleanup(){
        tx.executeWithoutResult(status->{
            var sessions=jdbc.sql("select id,manifest from logistics_upload_session where expires_at<now() or (batch_id is not null and not chunks_cleaned) order by expires_at limit 10 for update skip locked")
                .query((rs,n)->Map.entry(rs.getObject("id",UUID.class),mapper.readValue(rs.getString("manifest"),Manifest.class))).list();
            for(var session:sessions){
                boolean removed=true;
                for(int file=0;file<session.getValue().files().size();file++)
                    for(int chunk=0;(long)chunk*CHUNK_BYTES<session.getValue().files().get(file).size();chunk++)
                        removed=storage.removeRaw(objectKey(session.getKey(),file,chunk))&&removed;
                if(removed){
                    jdbc.sql("update logistics_upload_session set chunks_cleaned=true where id=:id").param("id",session.getKey()).update();
                    jdbc.sql("delete from logistics_upload_session where id=:id and expires_at<now()").param("id",session.getKey()).update();
                }
            }
        });
    }
    private final class ChunkFile implements MultipartFile {
        private final FileSpec spec;private final UUID id;private final int file,count;
        ChunkFile(FileSpec spec,UUID id,int file,int count){this.spec=spec;this.id=id;this.file=file;this.count=count;}
        public String getName(){return "files";}public String getOriginalFilename(){return spec.name();}
        public String getContentType(){return "application/octet-stream";}public boolean isEmpty(){return spec.size()==0;}
        public long getSize(){return spec.size();}
        public byte[] getBytes()throws IOException{try(var input=getInputStream()){return input.readAllBytes();}}
        public InputStream getInputStream(){
            return new SequenceInputStream(new Enumeration<InputStream>(){
                int chunk;
                public boolean hasMoreElements(){return chunk<count;}
                public InputStream nextElement(){if(!hasMoreElements())throw new NoSuchElementException();return storage.openRaw(objectKey(id,file,chunk++));}
            });
        }
        public void transferTo(File dest)throws IOException{try(var input=getInputStream();var output=new FileOutputStream(dest)){input.transferTo(output);}}
    }
}
