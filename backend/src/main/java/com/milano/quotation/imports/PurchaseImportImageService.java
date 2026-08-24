package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

@Service
public class PurchaseImportImageService {
    private static final long MAX_PART=500L*1024*1024,MAX_IMAGE=5L*1024*1024,MAX_TOTAL=50L*1024*1024*1024;
    private static final int MAX_ENTRIES=200_000;
    private static final Pattern NAME=Pattern.compile("(?i)^([A-Z0-9._-]+)-(product|physical)\\.(jpg|jpeg|png|webp)$");
    private final ImportJobRepository jobs;private final ImportPartRepository parts;private final PurchaseImportRowRepository rows;
    private final MigrationManifestEntryRepository entries;private final AssetStorageService storage;private final JdbcTemplate jdbc;private final TransactionTemplate transactions;

    public PurchaseImportImageService(ImportJobRepository jobs,ImportPartRepository parts,PurchaseImportRowRepository rows,MigrationManifestEntryRepository entries,AssetStorageService storage,JdbcTemplate jdbc,PlatformTransactionManager transactionManager){this.jobs=jobs;this.parts=parts;this.rows=rows;this.entries=entries;this.storage=storage;this.jdbc=jdbc;this.transactions=new TransactionTemplate(transactionManager);}

    @Transactional
    public ImportPart upload(UUID jobId,int partNumber,MultipartFile file){
        var job=jobs.findById(jobId).orElseThrow(()->AppException.notFound("采购导入任务不存在"));
        if(!List.of("queued","parsing","ready","failed").contains(job.status))throw AppException.conflict("当前任务不能上传图片分包");
        if(partNumber<1||partNumber>10_000)throw AppException.unprocessable("图片分包编号不合法");
        if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip"))throw AppException.unprocessable("图片分包必须是ZIP文件");
        if(file.getSize()>MAX_PART)throw AppException.unprocessable("单个图片ZIP不能超过500MB");
        var existing=parts.findByJobIdAndPartNumber(jobId,partNumber).orElse(null);
        if(existing!=null&&!"failed".equals(existing.status))throw AppException.conflict("该图片分包编号已上传");
        try{
            var key="purchase-import/"+jobId+"/images/"+String.format("%05d",partNumber)+".zip";
            storage.putRaw(key,file.getInputStream(),file.getSize(),"application/zip");
            var part=existing==null?new ImportPart():existing;
            if(existing==null){part.id=UUID.randomUUID();part.jobId=jobId;part.partNumber=partNumber;part.objectKey=key;part.createdAt=Instant.now();}
            else{var owned=entries.findByImportPartId(part.id).stream().filter(e->e.assetOwned&&e.assetId!=null).map(e->e.assetId).toList();storage.retire(owned,jobId);entries.deleteByImportPartId(part.id);}
            part.sha256=sha(file);part.sizeBytes=file.getSize();part.processedBytes=0;part.originalName=safe(file.getOriginalFilename());part.status="uploaded";part.errorMessage=null;part.processedAt=null;
            return parts.save(part);
        }catch(Exception e){if(e instanceof AppException a)throw a;throw AppException.unprocessable("图片ZIP上传失败");}
    }

    public void processAll(UUID jobId){
        var validRows=new HashMap<String,UUID>();for(var item:rows.findValidSkuIds(jobId))validRows.put(item.getSku(),item.getId());
        var files=new HashSet<String>();var slots=new HashSet<String>();
        for(var item:entries.findIndexByJobId(jobId)){files.add(item.getFileName().toUpperCase(Locale.ROOT));if("validated".equals(item.getStatus()))slots.add(item.getSku()+":"+item.getImageType());}
        long total=parts.findByJobIdOrderByPartNumber(jobId).stream().filter(part->"completed".equals(part.status)).mapToLong(part->part.processedBytes).sum();int count=files.size();var heartbeatAt=Instant.now();
        for(var part:parts.findByJobIdOrderByPartNumber(jobId)){
            if("completed".equals(part.status))continue;
            var attachments=new ArrayList<Attachment>();var manifests=new ArrayList<MigrationManifestEntry>();var ownedAssets=new ArrayList<UUID>();long partBytes=0;
            try(var zip=new ZipInputStream(new BufferedInputStream(storage.openRaw(part.objectKey)))){
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null){
                    if(entry.isDirectory())continue;
                    if(++count>MAX_ENTRIES)throw new IOException("图片总数超过20万");
                    var now=Instant.now();if(now.isAfter(heartbeatAt.plusSeconds(30))){jobs.heartbeat(jobId,AsyncPurchaseImportService.JOB_TYPE,now);heartbeatAt=now;}
                    var file=normalize(entry.getName());
                    if(!files.add(file.toUpperCase(Locale.ROOT))){var skipped=drain(zip);partBytes+=skipped;total+=skipped;if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB");manifests.add(manifest(jobId,part.id,"UNKNOWN","unknown",duplicateName(file,part.partNumber,count),"failed","图片文件名重复："+file,null,false));continue;}
                    var matcher=NAME.matcher(file.toUpperCase(Locale.ROOT));
                    if(!matcher.matches()){var skipped=drain(zip);partBytes+=skipped;total+=skipped;if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB");manifests.add(manifest(jobId,part.id,"UNKNOWN","unknown",file,"failed","文件名必须为 {SKU}-product/physical.jpg|png|webp",null,false));continue;}
                    var sku=matcher.group(1);var type=matcher.group(2).toLowerCase(Locale.ROOT);var rowId=validRows.get(sku);var slot=sku+":"+type;
                    if(rowId==null){var skipped=drain(zip);partBytes+=skipped;total+=skipped;if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB");manifests.add(manifest(jobId,part.id,sku,type,file,"failed","在本批合格数据中找不到SKU",null,false));continue;}
                    if(!slots.add(slot)){var skipped=drain(zip);partBytes+=skipped;total+=skipped;if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB");manifests.add(manifest(jobId,part.id,sku,type,file,"failed","同一SKU的图片类型重复",null,false));continue;}
                    try{
                        var bytes=read(zip);partBytes+=bytes.length;total+=bytes.length;if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB");var media=AssetStorageService.detectImage(bytes);if(!Set.of("image/jpeg","image/png","image/webp").contains(media))throw new IOException("只允许JPG、PNG、WEBP图片");
                        var asset=storage.storeTemporaryImageIndependent(bytes,file,jobId);var owned=jobId.equals(asset.stagingJobId);
                        attachments.add(new Attachment(rowId,type,asset.id));manifests.add(manifest(jobId,part.id,sku,type,file,"validated",null,asset.id,owned));if(owned)ownedAssets.add(asset.id);
                    }catch(Exception error){if(total>MAX_TOTAL)throw new IOException("图片解压总量超过50GB",error);slots.remove(slot);manifests.add(manifest(jobId,part.id,sku,type,file,"failed",PurchaseImportBatchService.shortMessage(error.getMessage()),null,false));}
                }
                var completedBytes=partBytes;transactions.executeWithoutResult(status->savePart(part,attachments,manifests,completedBytes));
            }catch(Exception fatal){storage.retire(ownedAssets,jobId);part.status="failed";part.errorMessage=PurchaseImportBatchService.shortMessage(fatal.getMessage());part.processedBytes=0;part.processedAt=Instant.now();parts.save(part);throw AppException.unprocessable("图片分包处理失败："+part.errorMessage);}
        }
    }

    void savePart(ImportPart part,List<Attachment> attachments,List<MigrationManifestEntry> manifests,long processedBytes){
        if(!attachments.isEmpty())jdbc.batchUpdate("""
                UPDATE purchase_import_row
                   SET product_asset_id = CASE WHEN ? = 'product' THEN ?::uuid ELSE product_asset_id END,
                       physical_asset_id = CASE WHEN ? = 'physical' THEN ?::uuid ELSE physical_asset_id END,
                       payload = CASE WHEN ? = 'product'
                           THEN jsonb_set(jsonb_set(payload,'{productImage}',to_jsonb(?::text),true),'{image}',to_jsonb(?::text),true)
                           ELSE jsonb_set(payload,'{physicalImage}',to_jsonb(?::text),true) END
                 WHERE id = ?
                """,attachments,attachments.size(),(statement,item)->{
                    var url="/api/v1/assets/"+item.assetId();statement.setString(1,item.type());statement.setObject(2,item.assetId());statement.setString(3,item.type());statement.setObject(4,item.assetId());statement.setString(5,item.type());statement.setString(6,url);statement.setString(7,url);statement.setString(8,url);statement.setObject(9,item.rowId());
                });
        if(!manifests.isEmpty())entries.saveAll(manifests);
        part.status="completed";part.errorMessage=null;part.processedBytes=processedBytes;part.processedAt=Instant.now();parts.save(part);
    }

    @Transactional public void attach(PurchaseImportRow row,String type,UUID assetId){var payload=(ObjectNode)row.payload;if("product".equals(type)){row.productAssetId=assetId;payload.put("productImage","/api/v1/assets/"+assetId);payload.put("image","/api/v1/assets/"+assetId);}else{row.physicalAssetId=assetId;payload.put("physicalImage","/api/v1/assets/"+assetId);}rows.save(row);}
    private static MigrationManifestEntry manifest(UUID jobId,UUID partId,String sku,String type,String file,String status,String error,UUID asset,boolean owned){var row=new MigrationManifestEntry();row.id=UUID.randomUUID();row.jobId=jobId;row.importPartId=partId;row.sku=sku;row.imageType=type;row.fileName=file;row.status=status;row.errorMessage=error;row.assetId=asset;row.assetOwned=owned;row.updatedAt=Instant.now();return row;}
    private static byte[] read(InputStream in)throws IOException{var out=new ByteArrayOutputStream();var buffer=new byte[8192];int read;long total=0;while((read=in.read(buffer))!=-1){total+=read;if(total>MAX_IMAGE)throw new IOException("单张图片超过5MB");out.write(buffer,0,read);}return out.toByteArray();}
    private static long drain(InputStream in)throws IOException{var buffer=new byte[8192];long total=0;int read;while((read=in.read(buffer))!=-1){if((total+=read)>MAX_IMAGE)throw new IOException("ZIP条目超过5MB");}return total;}
    private static String normalize(String raw)throws IOException{var value=raw.replace('\\','/');if(value.contains("/")||value.startsWith(".")||value.length()>255)throw new IOException("ZIP包含不安全路径");return value;}
    private static String duplicateName(String file,int partNumber,int count){return file+"#duplicate-p"+partNumber+"-"+count;}
    private static String sha(MultipartFile file)throws Exception{try(var input=file.getInputStream()){var d=MessageDigest.getInstance("SHA-256");input.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),d));return HexFormat.of().formatHex(d.digest());}}
    private static String safe(String value){var v=value.replaceAll("[\\r\\n\\\\/]","_");return v.substring(0,Math.min(255,v.length()));}
    record Attachment(UUID rowId,String type,UUID assetId){}
}
