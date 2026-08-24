package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseImportImageServiceTest {
    private ImportJobRepository jobs;private ImportPartRepository parts;private PurchaseImportRowRepository rows;private MigrationManifestEntryRepository entries;private AssetStorageService storage;private JdbcTemplate jdbc;private PurchaseImportImageService service;
    @BeforeEach void setup(){jobs=mock(ImportJobRepository.class);parts=mock(ImportPartRepository.class);rows=mock(PurchaseImportRowRepository.class);entries=mock(MigrationManifestEntryRepository.class);storage=mock(AssetStorageService.class);jdbc=mock(JdbcTemplate.class);var transactions=mock(org.springframework.transaction.PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());service=new PurchaseImportImageService(jobs,parts,rows,entries,storage,jdbc,transactions);when(parts.save(any())).thenAnswer(i->i.getArgument(0));}

    @Test void uploadsZipPartAndSanitizesName(){
        var job=job("ready");when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        var file=new MockMultipartFile("file","folder\\images\n.zip","application/zip",new byte[]{1,2});
        var part=service.upload(job.id,7,file);assertEquals(7,part.partNumber);assertEquals("folder_images_.zip",part.originalName);assertEquals(64,part.sha256.length());
        verify(storage).putRaw(contains("00007.zip"),any(),eq(2L),eq("application/zip"));
    }

    @Test void rejectsInvalidUploadStatesAndInputs(){
        var id=UUID.randomUUID();when(jobs.findById(id)).thenReturn(Optional.empty());assertThrows(AppException.class,()->service.upload(id,1,zipFile(new byte[]{1})));
        var job=job("completed");when(jobs.findById(job.id)).thenReturn(Optional.of(job));assertThrows(AppException.class,()->service.upload(job.id,1,zipFile(new byte[]{1})));
        job.status="ready";assertThrows(AppException.class,()->service.upload(job.id,0,zipFile(new byte[]{1})));
        assertThrows(AppException.class,()->service.upload(job.id,1,new MockMultipartFile("file","a.txt","",new byte[]{1})));
        var huge=mock(org.springframework.web.multipart.MultipartFile.class);when(huge.isEmpty()).thenReturn(false);when(huge.getOriginalFilename()).thenReturn("a.zip");when(huge.getSize()).thenReturn(501L*1024*1024);
        assertThrows(AppException.class,()->service.upload(job.id,1,huge));
        when(parts.findByJobIdAndPartNumber(job.id,1)).thenReturn(Optional.of(part(job.id,1,"x")));assertThrows(AppException.class,()->service.upload(job.id,1,zipFile(new byte[]{1})));
    }

    @Test void processesValidMissingBadAndDuplicateEntries(){
        var id=UUID.randomUUID();var part=part(id,1,"key");when(parts.findByJobIdOrderByPartNumber(id)).thenReturn(List.of(part));
        var png=new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        var zip=zip(Map.of("SKU-1-product.png",png,"SKU-1-physical.png",png,"MISSING-product.png",png,"wrong.txt",new byte[]{1},"SKU-1-product.jpg",png));
        when(storage.openRaw("key")).thenReturn(new ByteArrayInputStream(zip));var rowId=UUID.randomUUID();var skuIndex=skuId("SKU-1",rowId);when(rows.findValidSkuIds(id)).thenReturn(List.of(skuIndex));
        var asset=mock(AssetObject.class);asset.id=UUID.randomUUID();asset.stagingJobId=id;when(storage.storeTemporaryImageIndependent(any(),anyString(),eq(id))).thenReturn(asset);
        service.processAll(id);
        assertEquals("completed",part.status);assertNotNull(part.processedAt);verify(jdbc).batchUpdate(anyString(),argThat((List<?> list)->list.size()==2),eq(2),any());verify(entries).saveAll(argThat(list->((Collection<?>)list).size()==5));
    }

    @Test void recordsInvalidImageWithoutFailingWholePart(){
        var id=UUID.randomUUID();var part=part(id,1,"key");when(parts.findByJobIdOrderByPartNumber(id)).thenReturn(List.of(part));when(storage.openRaw("key")).thenReturn(new ByteArrayInputStream(zip(Map.of("SKU-1-product.png","text".getBytes()))));
        var skuIndex=skuId("SKU-1",UUID.randomUUID());when(rows.findValidSkuIds(id)).thenReturn(List.of(skuIndex));service.processAll(id);assertEquals("completed",part.status);
        verify(entries).saveAll(argThat(list->((Collection<MigrationManifestEntry>)list).stream().anyMatch(e->"failed".equals(e.status)&&e.errorMessage!=null)));
    }

    @Test void marksUnsafeZipPartFailedAndSkipsCompletedPart(){
        var id=UUID.randomUUID();var done=part(id,1,"done");done.status="completed";var unsafe=part(id,2,"unsafe");when(parts.findByJobIdOrderByPartNumber(id)).thenReturn(List.of(done,unsafe));when(storage.openRaw("unsafe")).thenReturn(new ByteArrayInputStream(zip(Map.of("../SKU-product.png",new byte[]{1}))));
        assertThrows(AppException.class,()->service.processAll(id));assertEquals("failed",unsafe.status);assertTrue(unsafe.errorMessage.contains("不安全"));verify(storage,never()).openRaw("done");
    }

    @Test void attachSeparatesProductAndPhysicalPayloadFields(){
        var row=row(UUID.randomUUID(),"SKU");var product=UUID.randomUUID();var physical=UUID.randomUUID();service.attach(row,"product",product);service.attach(row,"physical",physical);
        assertEquals(product,row.productAssetId);assertEquals(physical,row.physicalAssetId);assertTrue(row.payload.path("image").asText().contains(product.toString()));
    }

    @Test void failedPartCanBeReplacedAndOwnedAssetsAreRetired(){
        var job=job("failed");when(jobs.findById(job.id)).thenReturn(Optional.of(job));var old=part(job.id,2,"key");old.status="failed";when(parts.findByJobIdAndPartNumber(job.id,2)).thenReturn(Optional.of(old));var entry=new MigrationManifestEntry();entry.assetId=UUID.randomUUID();entry.assetOwned=true;when(entries.findByImportPartId(old.id)).thenReturn(List.of(entry));
        assertSame(old,service.upload(job.id,2,zipFile(new byte[]{1,2})));verify(storage).retire(List.of(entry.assetId),job.id);verify(entries).deleteByImportPartId(old.id);assertEquals("uploaded",old.status);
    }

    private static ImportJob job(String status){var j=new ImportJob();j.id=UUID.randomUUID();j.status=status;j.jobType=AsyncPurchaseImportService.JOB_TYPE;j.payload=JsonMapper.builder().build().createObjectNode();j.createdAt=Instant.now();j.updatedAt=j.createdAt;return j;}
    private static ImportPart part(UUID id,int n,String key){var p=new ImportPart();p.id=UUID.randomUUID();p.jobId=id;p.partNumber=n;p.objectKey=key;p.status="uploaded";p.createdAt=Instant.now();return p;}
    private static PurchaseImportRow row(UUID id,String sku){var r=new PurchaseImportRow();r.id=UUID.randomUUID();r.jobId=id;r.sku=sku;r.payload=JsonMapper.builder().build().createObjectNode();r.createdAt=Instant.now();return r;}
    private static PurchaseImportRowRepository.SkuId skuId(String sku,UUID id){var item=mock(PurchaseImportRowRepository.SkuId.class);when(item.getSku()).thenReturn(sku);when(item.getId()).thenReturn(id);return item;}
    private static MockMultipartFile zipFile(byte[] bytes){return new MockMultipartFile("file","a.zip","application/zip",bytes);}
    private static byte[] zip(Map<String,byte[]> entries){try{var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var item:entries.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return out.toByteArray();}catch(IOException e){throw new UncheckedIOException(e);}}
}
