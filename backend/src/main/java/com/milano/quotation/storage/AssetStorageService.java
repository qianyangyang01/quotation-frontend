package com.milano.quotation.storage;

import com.milano.quotation.common.AppException;
import io.minio.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.io.*;import java.security.MessageDigest;import java.time.Instant;import java.util.HexFormat;import java.util.UUID;

@Service
public class AssetStorageService{
    private static final long MAX_IMAGE_BYTES=20L*1024*1024;
    private final MinioClient minio;private final AssetObjectRepository assets;private final String bucket;private final boolean initialize;
    public AssetStorageService(MinioClient minio,AssetObjectRepository assets,@Value("${app.storage.bucket}")String bucket,@Value("${app.storage.initialize:true}")boolean initialize){this.minio=minio;this.assets=assets;this.bucket=bucket;this.initialize=initialize;}
    @PostConstruct void initialize(){if(!initialize)return;try{if(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());}catch(Exception e){throw new IllegalStateException("Quotation object storage initialization failed",e);}}
    @Transactional public AssetObject storeImage(byte[] bytes,String originalName){return store(bytes,originalName,null);}
    @Transactional public AssetObject storeTemporaryImage(byte[] bytes,String originalName,UUID jobId){return store(bytes,originalName,jobId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public AssetObject storeTemporaryImageIndependent(byte[] bytes,String originalName,UUID jobId){return store(bytes,originalName,jobId);}
    private AssetObject store(byte[] bytes,String originalName,UUID jobId){if(bytes.length==0||bytes.length>MAX_IMAGE_BYTES)throw AppException.unprocessable("图片为空或超过20MB");var mediaType=detectImage(bytes);var sha=sha256(bytes);var existing=assets.findBySha256(sha);if(existing.isPresent())return existing.get();var objectKey="objects/"+sha.substring(0,2)+"/"+sha;try{minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(new ByteArrayInputStream(bytes),bytes.length,-1).contentType(mediaType).build());}catch(Exception e){throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","图片存储暂时不可用");}var row=new AssetObject();row.id=UUID.randomUUID();row.sha256=sha;row.objectKey=objectKey;row.mediaType=mediaType;row.sizeBytes=bytes.length;row.originalName=safeName(originalName);row.storageState=jobId==null?"published":"temporary";row.stagingJobId=jobId;row.expiresAt=jobId==null?null:Instant.now().plus(java.time.Duration.ofDays(7));row.createdAt=Instant.now();return assets.save(row);}
    @Transactional public void publish(java.util.Collection<UUID> ids){for(var id:ids)if(id!=null)assets.findById(id).ifPresent(asset->{asset.storageState="published";asset.stagingJobId=null;asset.expiresAt=null;});}
    @Transactional public void retire(java.util.Collection<UUID> ids,UUID jobId){for(var id:ids)if(id!=null)assets.findById(id).ifPresent(asset->{asset.storageState="temporary";asset.stagingJobId=jobId;asset.expiresAt=Instant.now();});}
    @Transactional public int retireUnreferenced(java.util.Collection<UUID> ids){var unique=ids.stream().filter(java.util.Objects::nonNull).distinct().toList();return unique.isEmpty()?0:assets.retireUnreferenced(unique,Instant.now());}
    @Scheduled(fixedDelayString="${app.storage.cleanup-delay-ms:3600000}") @Transactional public void cleanupExpired(){for(var asset:assets.findExpiredUnreferenced(Instant.now())){try{minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(asset.objectKey).build());assets.delete(asset);}catch(Exception ignored){/* 下一轮继续清理，避免数据库记录与对象状态失配。 */}}}
    public AssetStream open(UUID id){var asset=assets.findById(id).orElseThrow(()->AppException.notFound("图片不存在"));try{return new AssetStream(asset,minio.getObject(GetObjectArgs.builder().bucket(bucket).object(asset.objectKey).build()));}catch(Exception e){throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","图片读取失败");}}
    public void putRaw(String objectKey,InputStream stream,long size,String contentType){try{minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(stream,size,-1).contentType(contentType).build());}catch(Exception e){throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","迁移文件存储失败");}}
    public InputStream openRaw(String objectKey){try{return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());}catch(Exception e){throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","迁移文件读取失败");}}
    public boolean removeRaw(String objectKey){if(objectKey==null||objectKey.isBlank())return true;try{minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());return true;}catch(Exception ignored){return false;}}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String detectImage(byte[] b){if(b.length>=8&&b[0]==(byte)0x89&&b[1]==0x50&&b[2]==0x4e&&b[3]==0x47)return"image/png";if(b.length>=3&&b[0]==(byte)0xff&&b[1]==(byte)0xd8&&b[2]==(byte)0xff)return"image/jpeg";if(b.length>=6&&new String(b,0,6,java.nio.charset.StandardCharsets.US_ASCII).startsWith("GIF8"))return"image/gif";if(b.length>=12&&new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))return"image/webp";throw AppException.unprocessable("文件内容不是受支持的PNG、JPEG、GIF或WebP图片");}
    private static String safeName(String name){var value=name==null?"image":name.replaceAll("[\\r\\n\\\\/]","_").trim();return value.isEmpty()?"image":value.substring(0,Math.min(255,value.length()));}
    public record AssetStream(AssetObject asset,InputStream stream){}
}
